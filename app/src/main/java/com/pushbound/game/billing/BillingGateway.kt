package com.pushbound.game.billing

/** How a Remove Ads purchase attempt ended. Exactly one is delivered per attempt. */
enum class RemoveAdsPurchaseOutcome {
    /**
     * No Play Console product is configured yet, so NOTHING was attempted and
     * nothing can be bought. This is the only outcome Pushbound can produce
     * today, and it must never be mistaken for a failure the player caused.
     */
    NOT_CONFIGURED,

    /** The player already owns Remove Ads; there is nothing left to sell. */
    ALREADY_OWNED,

    /** The player left the Play purchase sheet. No change. */
    USER_CANCELLED,

    /**
     * Billing is not reachable — offline, Play services unavailable, or the
     * service disconnected mid-attempt. Nothing was attempted.
     */
    UNAVAILABLE,

    /** Play answered with any other error. Nothing was granted. */
    FAILED
}

/**
 * The seam between the game and Google Play Billing.
 *
 * WHY THIS EXISTS, IN ONE SENTENCE — the rest of the application must be able
 * to ask "may ads be shown?" and "may this be bought?" without ever importing
 * a `BillingClient`. Every Google type, every connection rule and every
 * response code lives behind this interface, which is pure Kotlin and
 * Android-free, so the policy above it stays unit testable on the JVM.
 *
 * WHAT IS DELIBERATELY TRUE ABOUT IT TODAY:
 *
 *  - **No product exists.** [RemoveAdsProduct.REMOVE_ADS_PRODUCT_ID] is empty
 *    until a real id is created in Play Console, so [purchaseRemoveAds]
 *    answers [RemoveAdsPurchaseOutcome.NOT_CONFIGURED] without contacting
 *    anything. Nothing in the shipped interface can be reached from player
 *    facing UI, and no screen offers Remove Ads, because the game does not
 *    sell it.
 *  - **Nothing is enticed.** [isRemoveAdsOwned] starts false and is only ever
 *    true when Google actually reported the purchase; there is no local
 *    boolean that could be flipped to fake an entitlement.
 *  - **Gameplay does not depend on it.** [NoOpBillingGateway] is the default,
 *    and both it and an unavailable Play service leave the game byte-for-byte
 *    identical — no billing, no ad and no puzzle behaviour changes with it.
 *
 * [close] exists so the owner can release the service connection; it is
 * ignored by the no-op implementation because there is nothing to release.
 */
interface BillingGateway {

    /**
     * Whether a Remove Ads purchase COULD be attempted at all.
     *
     * False for as long as no Play Console product id is configured, which is
     * the signal any future "Buy" affordance must gate on so the app can never
     * offer something it does not have.
     */
    val isRemoveAdsConfigured: Boolean

    /**
     * Whether the player is believed to own Remove Ads RIGHT NOW.
     *
     * A local cache, and deliberately labelled as one: it is what a UI reads
     * to decide what to draw without waiting on Play. Truth comes from
     * [refreshRemoveAdsOwnership], which asks Google Play Billing rather than
     * this process's own memory.
     */
    val isRemoveAdsOwned: Boolean

    /**
     * Re-reads ownership from Google Play Billing and reports it.
     *
     * THE ENTITLEMENT MUST BE REFRESHABLE, not merely remembered: a purchase
     * made on another device, or a refund Play has since applied, has to be
     * able to reach this install. [onOwned] always runs — with the cached
     * value when the check cannot be completed — so a caller can never be left
     * hanging waiting on a service that is not there.
     */
    fun refreshRemoveAdsOwnership(onOwned: (Boolean) -> Unit)

    /**
     * Starts the purchase flow for the future Remove Ads product.
     *
     * [onOutcome] runs EXACTLY ONCE. NO outcome grants anything by itself:
     * ownership comes from [refreshRemoveAdsOwnership] reading what Google
     * actually recorded, which is the whole point of basing the entitlement on
     * purchase state rather than on a local flag.
     *
     * With no product configured this returns
     * [RemoveAdsPurchaseOutcome.NOT_CONFIGURED] immediately and contacts
     * nothing — see [RemoveAdsProduct] for why the id stays empty.
     */
    fun purchaseRemoveAds(onOutcome: (RemoveAdsPurchaseOutcome) -> Unit)

    /**
     * Releases any service connection. Safe to call at any time, including
     * when nothing was ever connected.
     */
    fun close()
}

/**
 * The default: no SDK, no service, no product, no purchase.
 *
 * This is what a test, a preview, or any caller that was never given a real
 * gateway gets. It behaves exactly like billing that refused to start: every
 * callback still fires, so the no-billing path and the billing path are the
 * SAME code path and gameplay never learns the difference.
 *
 * [RemoveAdsPurchaseOutcome.NOT_CONFIGURED] rather than [RemoveAdsPurchaseOutcome.UNAVAILABLE]
 * is the right answer here, because nothing is broken and nothing is missing
 * from the network — the product simply does not exist yet. Saying
 * "unavailable" would send a future caller retrying forever for something
 * that will only appear when a human creates it in Play Console.
 */
object NoOpBillingGateway : BillingGateway {

    override val isRemoveAdsConfigured: Boolean = false

    override val isRemoveAdsOwned: Boolean = false

    override fun refreshRemoveAdsOwnership(onOwned: (Boolean) -> Unit) {
        // Nothing has ever been bought, because nothing can be.
        onOwned(false)
    }

    override fun purchaseRemoveAds(onOutcome: (RemoveAdsPurchaseOutcome) -> Unit) {
        onOutcome(RemoveAdsPurchaseOutcome.NOT_CONFIGURED)
    }

    override fun close() {
        // Nothing to release.
    }
}
