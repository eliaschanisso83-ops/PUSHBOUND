package com.pushbound.game.billing.play

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.pushbound.game.billing.BillingGateway
import com.pushbound.game.billing.RemoveAdsBilling
import com.pushbound.game.billing.RemoveAdsProduct
import com.pushbound.game.billing.RemoveAdsPurchaseOutcome
import java.lang.ref.WeakReference

/**
 * The only place in Pushbound where a Google Play Billing type appears.
 *
 * BOUNDARY — this class sits strictly between the application shell and the
 * SDK, exactly as [com.pushbound.game.ads.admob.AdMobAdsGateway] does for ads.
 * Nothing in `engine/`, `progress/`, `undo/` or the gameplay rules knows that
 * billing exists; they see a [BillingGateway] and only ever learn that a
 * product is owned or that an attempt was refused. If this file were deleted
 * the game would still compile, still run and still be identical to play.
 *
 * WHAT IT DOES **NOT** DO TODAY — anything a player can see. Pushbound ships
 * with **no Remove Ads product**, so [RemoveAdsProduct.REMOVE_ADS_PRODUCT_ID]
 * is empty, [purchaseRemoveAds] answers
 * [RemoveAdsPurchaseOutcome.NOT_CONFIGURED] before contacting Play, and no
 * screen offers a purchase. This class is also never asked to connect until
 * something actually queries it, so adding it changed no runtime behaviour at
 * all: no service is bound at launch, no request is made, and nothing about
 * advertising, hints or Undo moved.
 *
 * THREADING — the billing client delivers its callbacks on the main thread,
 * and every entry point hops onto it first, so all mutation here happens
 * there. [com.pushbound.game.ui.GameController] may therefore read
 * [isRemoveAdsOwned] without a lock.
 *
 * WHAT THIS CLASS MUST NEVER DO — touch progress, medals, counters, the board,
 * the undo balance or the ad gateway. Its entire observable output is one
 * [RemoveAdsPurchaseOutcome] per attempt and one ownership answer per query,
 * delivered exactly once each.
 */
class GooglePlayBillingGateway(
    activity: Activity
) : BillingGateway {

    /**
     * Held weakly, for the same reason the ad gateway holds its activity
     * weakly: the gateway outlives configuration changes, and holding a
     * destroyed activity strongly through a long-lived SDK callback would leak
     * it. Launching a purchase needs a live window, so a collected activity
     * honestly reports [RemoveAdsPurchaseOutcome.UNAVAILABLE] rather than
     * starting a sheet over a dead one.
     */
    private val activityRef = WeakReference(activity)

    private val appContext: Context = activity.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The service connection, created on first use. Main-thread writes. */
    @Volatile
    private var client: BillingClient? = null

    @Volatile
    private var connected = false

    /**
     * The cached entitlement. Starts false and is ONLY ever set from what
     * Google Play actually returned — see [RemoveAdsBilling.ownsRemoveAds] —
     * so nothing in this process can invent ownership for itself.
     */
    @Volatile
    private var cachedOwned = false

    /**
     * The single caller waiting for a connection, if any.
     *
     * ONE AT A TIME, REFUSED OTHERWISE, rather than queued. Billing calls come
     * from deliberate user actions, a second one while a connection is being
     * established means the UI let the player double up, and answering
     * "unavailable" immediately keeps the exactly-once contract of both
     * pending callbacks obvious instead of silently serialising work.
     */
    private var connectionWaiter: ((Boolean) -> Unit)? = null

    private var pendingPurchase: ((RemoveAdsPurchaseOutcome) -> Unit)? = null
    private var pendingRefresh: ((Boolean) -> Unit)? = null

    override val isRemoveAdsConfigured: Boolean get() = RemoveAdsProduct.isConfigured

    override val isRemoveAdsOwned: Boolean get() = cachedOwned

    override fun refreshRemoveAdsOwnership(onOwned: (Boolean) -> Unit) = onMain {
        // A product that does not exist cannot be owned, and saying so
        // immediately is both true and the answer that stops a future caller
        // waiting on a service it has no reason to reach.
        if (!RemoveAdsProduct.isConfigured) {
            onOwned(false)
            return@onMain
        }
        if (pendingRefresh != null) {
            onOwned(cachedOwned)
            return@onMain
        }

        pendingRefresh = onOwned
        withConnection { ok ->
            if (ok) queryOwned()
            // Could not reach Play: report what we last knew rather than
            // leaving the caller waiting for a service that is not there.
            else finishRefresh(cachedOwned)
        }
    }

    override fun purchaseRemoveAds(onOutcome: (RemoveAdsPurchaseOutcome) -> Unit) = onMain {
        // DECIDED LOCALLY, BEFORE ANY CONNECTION — the two refusals that have
        // nothing to do with the network. Today the first of these always
        // fires, which is why Pushbound can guarantee it never contacts Play
        // for a product that does not exist.
        RemoveAdsBilling.refusePurchase(
            configured = RemoveAdsProduct.isConfigured,
            owned = cachedOwned
        )?.let {
            onOutcome(it)
            return@onMain
        }

        val live = liveActivity()
        if (live == null) {
            onOutcome(RemoveAdsPurchaseOutcome.UNAVAILABLE)
            return@onMain
        }
        if (pendingPurchase != null) {
            // Defence in depth: nothing in the shipped game can reach this
            // twice, because no purchase affordance exists at all.
            onOutcome(RemoveAdsPurchaseOutcome.UNAVAILABLE)
            return@onMain
        }

        pendingPurchase = onOutcome
        withConnection { ok ->
            if (ok) queryProductDetails(live)
            else finishPurchase(RemoveAdsPurchaseOutcome.UNAVAILABLE)
        }
    }

    override fun close() = onMain {
        // Every waiter is answered rather than dropped: a callback that never
        // arrives is how a caller ends up stuck on a spinner forever.
        finishPurchase(RemoveAdsPurchaseOutcome.UNAVAILABLE)
        finishRefresh(cachedOwned)
        connectionWaiter = null
        connected = false
        client?.endConnection()
        client = null
    }

    // --- connection ---------------------------------------------------------

    /**
     * Runs [onReady] once the service is up, or with false if it cannot be.
     *
     * Never throws and never blocks: an absent Play service, no connectivity
     * or a device without billing all end at `false`, which every caller
     * translates into "unavailable" — the game plays exactly as before.
     */
    private fun withConnection(onReady: (Boolean) -> Unit) {
        val current = client
        if (connected && current != null && current.isReady) {
            onReady(true)
            return
        }
        if (connectionWaiter != null) {
            onReady(false)
            return
        }

        connectionWaiter = onReady
        val target = current ?: buildClient().also { client = it }
        if (target.isReady) {
            connected = true
            settleConnection(true)
            return
        }
        target.startConnection(billingStateListener)
    }

    private fun settleConnection(ok: Boolean) {
        val waiter = connectionWaiter
        connectionWaiter = null
        waiter?.invoke(ok)
    }

    private fun buildClient(): BillingClient =
        BillingClient.newBuilder(appContext)
            // A purchase Play reports outside a launch of ours still matters —
            // a refund, or a purchase completed on another device and synced.
            .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
            // Required by every 6.x+ client. One-time products only: Remove
            // Ads is a single non-consumable purchase, never a subscription,
            // and no prepaid plan exists in this product.
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()

    private val billingStateListener = object : BillingClientStateListener {
        override fun onBillingSetupFinished(result: BillingResult) {
            val ok = result.responseCode == BillingClient.BillingResponseCode.OK
            connected = ok
            if (!ok) {
                Log.w(TAG, "Billing setup failed: ${result.responseCode} ${result.debugMessage}")
            }
            settleConnection(ok)
        }

        override fun onBillingServiceDisconnected() {
            // Not fatal and not logged loudly: Play being unavailable is an
            // ordinary state, and the next attempt simply reconnects.
            connected = false
            settleConnection(false)
        }
    }

    // --- ownership ----------------------------------------------------------

    /**
     * Asks Play what this install actually owns and settles on that answer.
     *
     * THE ENTITLEMENT IS READ, NEVER ASSUMED. This is the refresh half of the
     * rule that a local boolean is a cache and Google Play Billing is the
     * truth: a purchase made elsewhere, or one Play has since reversed, has to
     * be able to reach this install.
     */
    private fun queryOwned() {
        val active = client
        if (active == null || !active.isReady) {
            finishRefresh(cachedOwned)
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        active.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "Purchase query failed: ${result.responseCode} ${result.debugMessage}")
                finishRefresh(cachedOwned)
                return@queryPurchasesAsync
            }
            cachedOwned = RemoveAdsBilling.ownsRemoveAds(
                configured = RemoveAdsProduct.isConfigured,
                purchasedProductIds = purchasedIds(purchases)
            )
            finishRefresh(cachedOwned)
        }
    }

    /**
     * Product ids Play reported as PURCHASED.
     *
     * PENDING is filtered out deliberately: Play has not finished taking the
     * money yet, and granting an entitlement on an unconfirmed purchase is
     * exactly the shortcut this design refuses to take.
     */
    private fun purchasedIds(purchases: List<Purchase>?): List<String> =
        purchases.orEmpty()
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .flatMap { it.products }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                // A null list means Play told us nothing, so the cached value
                // stands; a list is Play's own answer and replaces it.
                if (purchases != null) {
                    cachedOwned = RemoveAdsBilling.ownsRemoveAds(
                        configured = RemoveAdsProduct.isConfigured,
                        purchasedProductIds = purchasedIds(purchases)
                    )
                }
                finishPurchase(
                    if (cachedOwned) RemoveAdsPurchaseOutcome.ALREADY_OWNED
                    else RemoveAdsPurchaseOutcome.FAILED
                )
                finishRefresh(cachedOwned)
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> {
                // The player dismissed the sheet. Nothing changed and nothing
                // is wrong, so this is its own outcome rather than a failure.
                finishPurchase(RemoveAdsPurchaseOutcome.USER_CANCELLED)
            }

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // Confirm it from Play rather than flipping a local flag on
                // hearsay — the same rule the refresh path follows.
                queryOwned()
                finishPurchase(RemoveAdsPurchaseOutcome.ALREADY_OWNED)
            }

            else -> {
                Log.w(TAG, "Purchase failed: ${result.responseCode} ${result.debugMessage}")
                finishPurchase(RemoveAdsPurchaseOutcome.FAILED)
            }
        }
    }

    // --- the future purchase flow ------------------------------------------

    /**
     * Looks up the product Play knows about, then opens Play's own sheet.
     *
     * UNREACHABLE TODAY, ON PURPOSE. [RemoveAdsProduct.REMOVE_ADS_PRODUCT_ID]
     * is empty, so [purchaseRemoveAds] refuses before this can be called; the
     * code exists so that supplying a real id is the only change a future
     * release needs, not a rewrite. It is written against the real SDK and
     * compile-checked, so "prepared" means prepared rather than sketched.
     */
    private fun queryProductDetails(activity: Activity) {
        val active = client
        if (active == null || !active.isReady) {
            finishPurchase(RemoveAdsPurchaseOutcome.UNAVAILABLE)
            return
        }

        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(RemoveAdsProduct.REMOVE_ADS_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()

        active.queryProductDetailsAsync(params) { result, queryResult ->
            val details = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                queryResult.productDetailsList.firstOrNull()
            } else {
                null
            }

            if (details == null) {
                Log.w(TAG, "No product details: ${result.responseCode} ${result.debugMessage}")
                finishPurchase(RemoveAdsPurchaseOutcome.FAILED)
                return@queryProductDetailsAsync
            }

            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(details)
                            .build()
                    )
                )
                .build()

            val launched = active.launchBillingFlow(activity, flowParams)
            if (launched.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "Could not launch billing flow: ${launched.responseCode}")
                finishPurchase(RemoveAdsPurchaseOutcome.FAILED)
            }
            // Success is NOT the end of the attempt: Play answers through
            // onPurchasesUpdated, which is where the outcome is decided.
        }
    }

    // --- helpers ------------------------------------------------------------

    private fun finishPurchase(outcome: RemoveAdsPurchaseOutcome) {
        val waiter = pendingPurchase
        pendingPurchase = null
        waiter?.invoke(outcome)
    }

    private fun finishRefresh(owned: Boolean) {
        val waiter = pendingRefresh
        pendingRefresh = null
        waiter?.invoke(owned)
    }

    /**
     * The activity to launch Play's sheet on, or null if it has gone.
     *
     * Also refuses a finishing or destroyed activity for the same reason the
     * ad gateway does: presenting a full-screen flow over a window that is on
     * its way out throws inside the SDK and would take the caller's callback
     * with it.
     */
    private fun liveActivity(): Activity? {
        val activity = activityRef.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        return activity
    }

    /** Runs [block] on the main thread, where the billing client expects to be. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            // Wrapped in a lambda literal so the SAM conversion to Runnable is
            // unambiguous for a captured function value — the ad gateway does
            // the same thing, for the same compiler reason.
            mainHandler.post { block() }
        }
    }

    private companion object {
        const val TAG = "PushboundBilling"
    }
}
