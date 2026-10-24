package com.pushbound.game.ads.admob

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import com.pushbound.game.ads.AdsGateway
import com.pushbound.game.ads.RewardedAvailability
import com.pushbound.game.ads.RewardedOutcome
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The only place in Pushbound where a Google AdMob type appears.
 *
 * BOUNDARY — this class sits strictly between the UI and the SDK. Nothing in
 * `engine/`, `progress/` or the gameplay rules knows that AdMob exists; they
 * see an [AdsGateway] and only ever learn that a reward was granted. If this
 * file were deleted the game would still compile, still run, and still grant
 * free hints — it would simply offer no ads.
 *
 * THE ONE FORMAT is Rewarded, on the `Pushbound_Rewarded_Hint` unit. There is
 * deliberately no banner, no interstitial unit and no app-open unit; see
 * [com.pushbound.game.ads.InterstitialPolicy] for why the interstitial path
 * exists only as a seam.
 *
 * THREADING — AdMob delivers every callback on the main thread, so all mutation
 * here happens there. The one field read from elsewhere is the availability
 * tri-state, which [com.pushbound.game.ui.GameController] reads from its
 * background hint search; those fields are `@volatile` so the read sees a
 * coherent value without dragging a lock onto a hot path.
 *
 * WHAT THIS CLASS MUST NEVER DO — touch progress, medals, counters, the board
 * or the undo history. Its entire observable output is one [RewardedOutcome]
 * per offer, delivered exactly once.
 */
class AdMobAdsGateway(
    activity: Activity,
    /** Google's demo unit in debug builds, the production unit in release. */
    private val rewardedUnitId: String
) : AdsGateway {

    /**
     * Held weakly: the gateway is owned by the activity that created it, and
     * holding it strongly would leak a destroyed activity through a long-lived
     * SDK callback. AdMob's `show()` and UMP's consent form both need a live
     * `Activity`, so if this has been collected the offer honestly reports
     * [RewardedOutcome.UNAVAILABLE] rather than showing over a dead window.
     */
    private val activityRef = WeakReference(activity)

    private val appContext: Context = activity.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- rewarded slot -------------------------------------------------------

    /** The one ad waiting to be shown, or null. Main-thread writes. */
    @Volatile
    private var loadedAd: RewardedAd? = null

    /** True while a load is in flight, so we never start a second one. */
    @Volatile
    private var loadInFlight = false

    /**
     * The settled state of consent, which is what decides whether an ad may be
     * requested AT ALL — not merely whether we have asked recently.
     *
     * [ConsentState.REFUSED] is a decision the player made and must never be
     * re-litigated by a retry: re-running the consent form after they closed
     * it would nag them. [ConsentState.FAILED] is the opposite — consent could
     * not be established (offline), which is worth retrying so that ads recover
     * on their own when connectivity returns.
     *
     * [ConsentState.NOT_CHECKED] starts out as LOADING rather than UNAVAILABLE,
     * because an ad genuinely CAN be attempted: nothing has been ruled out yet.
     */
    @Volatile
    private var consent: ConsentState = ConsentState.NOT_CHECKED

    /**
     * Whether the most recent load attempt failed.
     *
     * Consent can be perfectly fine while there is simply no ad to show, and
     * the two must not be confused: one means "may not ask", the other means
     * "asked and got nothing".
     */
    @Volatile
    private var lastLoadFailed = false

    /** The offer currently waiting for an ad to arrive. Main-thread only. */
    private var pendingShow: Delivery? = null
    private var showTimeout: Runnable? = null

    override fun isRewardedAvailable(): Boolean =
        rewardedAvailability() != RewardedAvailability.UNAVAILABLE

    override fun rewardedAvailability(): RewardedAvailability = when {
        loadedAd != null -> RewardedAvailability.READY
        loadInFlight -> RewardedAvailability.LOADING
        consent == ConsentState.NOT_CHECKED -> RewardedAvailability.LOADING
        consent == ConsentState.GRANTED && !lastLoadFailed -> RewardedAvailability.LOADING
        // Either consent forbids asking, or asking was tried and produced
        // nothing. Both mean the same thing to the player right now.
        else -> RewardedAvailability.UNAVAILABLE
    }

    /**
     * Begins the work behind a confirmed offer: consent, then the fetch.
     *
     * LOADING IS NOT SHOWING: nothing here covers the board, interrupts a
     * move, or appears at launch. It is a background fetch that either
     * succeeds — and an ad appears — or fails, which changes nothing except
     * that the offer is answered "unavailable" and the player carries on.
     *
     * Safe to call repeatedly and from any thread; a second call while one is
     * in flight is a no-op.
     */
    override fun preloadRewarded() = onMain {
        if (loadedAd != null || loadInFlight) return@onMain
        loadInFlight = true

        when (consent) {
            // Already authorised this session: go straight to loading, and do
            // NOT put the consent form in front of the player a second time.
            ConsentState.GRANTED -> loadAd()

            // The player declined, and that answer stands. No ad may be
            // requested — and re-showing the form would nag them, which is
            // exactly the interruption this design exists to avoid.
            ConsentState.REFUSED -> {
                loadInFlight = false
                failPending(RewardedOutcome.DISMISSED)
            }

            // Not checked yet, or the last check could not reach Google.
            // Worth attempting again so that ads recover on their own when
            // connectivity returns, rather than staying dead for the session.
            ConsentState.NOT_CHECKED, ConsentState.FAILED -> withConsent { outcome ->
                when (outcome) {
                    ConsentOutcome.CAN_REQUEST -> {
                        consent = ConsentState.GRANTED
                        loadAd()
                    }

                    ConsentOutcome.DECLINED -> {
                        consent = ConsentState.REFUSED
                        loadInFlight = false
                        failPending(RewardedOutcome.DISMISSED)
                    }

                    ConsentOutcome.UNKNOWN -> {
                        consent = ConsentState.FAILED
                        loadInFlight = false
                        failPending(RewardedOutcome.UNAVAILABLE)
                    }
                }
            }
        }
    }

    /**
     * Shows an ad, or reports that one could not be shown.
     *
     * [onOutcome] fires EXACTLY ONCE, always. The reward is granted for
     * [RewardedOutcome.REWARDED] and for nothing else — a closed or dismissed
     * ad grants nothing, which is Google's reward contract and not a rule this
     * layer is free to relax.
     *
     * If no ad is loaded yet the offer WAITS for the in-flight one instead of
     * answering "unavailable" immediately: consent and the fetch both begin
     * here, the moment the player has said yes, and a normal mobile fetch is
     * shorter than the time they just spent reading the confirmation. A wait
     * that outlasts [SHOW_TIMEOUT_MS] reports [RewardedOutcome.UNAVAILABLE] and
     * hands control straight back, so the confirmation can never hang.
     */
    override fun showRewarded(onOutcome: (RewardedOutcome) -> Unit) = onMain {
        val delivery = Delivery(onOutcome)

        if (pendingShow != null) {
            // Defence in depth: the controller already refuses a second offer
            // while one is outstanding, so this should be unreachable. Answering
            // rather than queueing keeps the exactly-once contract obvious.
            delivery.deliver(RewardedOutcome.UNAVAILABLE)
            return@onMain
        }

        loadedAd?.let { present(it, delivery); return@onMain }

        pendingShow = delivery
        startShowTimeout(delivery)
        preloadRewarded()
    }

    // --- consent (UMP) -------------------------------------------------------

    /**
     * What consent settled at, for the life of this gateway.
     *
     * Distinct from [ConsentOutcome] — a single result of one check — because
     * this is the memory that decides whether a later retry re-asks the player
     * or quietly proceeds. The distinction is what stops an offline session
     * from either nagging with the form or going permanently deaf to ads.
     */
    private enum class ConsentState {
        /** Nothing has been checked yet; an ad can still be attempted. */
        NOT_CHECKED,

        /** Consent is satisfied; ads may be requested without re-prompting. */
        GRANTED,

        /** The player declined. That decision is final for this session. */
        REFUSED,

        /**
         * The check could not complete — offline, or Google unreachable.
         * Deliberately retryable, unlike [REFUSED].
         */
        FAILED
    }

    /**
     * What the consent step concluded.
     *
     * Three states rather than a boolean because the two failures are
     * genuinely different to the player: saying no to a consent form is a
     * decision they made, while being unable to reach the consent service at
     * all is not.
     */
    private enum class ConsentOutcome {
        /** Consent is satisfied; ads may be requested. */
        CAN_REQUEST,

        /**
         * The consent form was offered and consent was not granted.
         *
         * UMP reports an ordinary dismissal as `formError == null` — closing
         * the form is not an error — so the signal is "no form error AND ads
         * still may not be requested", read after the callback has run.
         */
        DECLINED,

        /** Consent could not be established: offline, or the SDK failed. */
        UNKNOWN
    }

    /**
     * Resolves consent, then reports whether ads may be requested.
     *
     * RUN LAZILY, ON THE FIRST CONFIRMED OFFER — never at launch. Pushbound's
     * opening frames are the game, and a consent form dropped on the splash
     * screen would be exactly the interruption this design exists to avoid.
     * Most players never see a form at all: outside regulated regions UMP has
     * nothing to ask and the callback returns immediately.
     *
     * A failure is not fatal. Consent that cannot be established means ads may
     * not be requested, which reports as unavailable and leaves the game
     * untouched.
     */
    private fun withConsent(onResolved: (ConsentOutcome) -> Unit) {
        val activity = liveActivity()
        if (activity == null) {
            onResolved(ConsentOutcome.UNKNOWN)
            return
        }

        val information = UserMessagingPlatform.getConsentInformation(appContext)

        fun conclude(formError: FormError?, formWasOffered: Boolean) {
            onResolved(
                when {
                    information.canRequestAds() -> ConsentOutcome.CAN_REQUEST
                    formError == null && formWasOffered -> ConsentOutcome.DECLINED
                    else -> ConsentOutcome.UNKNOWN
                }
            )
        }

        information.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                // Whether a form was needed has to be read BEFORE showing it:
                // once the callback runs, `false` no longer tells us whether
                // one was declined or simply never existed.
                val formWasOffered = information.isConsentFormAvailable
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        Log.w(TAG, "Consent form failed: ${formError.message}")
                    }
                    conclude(formError, formWasOffered)
                }
            },
            { updateError ->
                Log.w(TAG, "Consent update failed: ${updateError.message}")
                conclude(updateError, formWasOffered = false)
            }
        )
    }

    // --- loading -------------------------------------------------------------

    private fun loadAd() {
        lastLoadFailed = false
        RewardedAd.load(
            appContext,
            rewardedUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    loadInFlight = false
                    lastLoadFailed = false
                    loadedAd = ad
                    settlePending()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadInFlight = false
                    lastLoadFailed = true
                    // Logged for QA, not shown to the player: a failed load is
                    // an ordinary, expected event (offline, no fill) and never
                    // a reason to say anything alarming over the board.
                    Log.w(TAG, "Rewarded ad failed to load: ${error.code} ${error.message}")
                    settlePending()
                }
            }
        )
    }

    /** Hands a waiting offer its ad, or tells it there is none. */
    private fun settlePending() {
        val pending = pendingShow ?: return
        pendingShow = null
        cancelShowTimeout()

        val ad = loadedAd
        if (ad != null) present(ad, pending)
        else pending.deliver(RewardedOutcome.UNAVAILABLE)
    }

    /**
     * Ends a waiting offer with [outcome] without showing anything.
     *
     * Used for the consent outcomes, where the answer must be DISMISSED or
     * UNAVAILABLE rather than "no fill" — and where no ad exists to present
     * even if one were somehow loaded, because consent has just said no.
     */
    private fun failPending(outcome: RewardedOutcome) {
        val pending = pendingShow ?: return
        pendingShow = null
        cancelShowTimeout()
        pending.deliver(outcome)
    }

    /**
     * The interstitial seam.
     *
     * DELIBERATELY DOES NOTHING. Pushbound ships no interstitial ad unit and
     * will not get one under the current design: a puzzle game does not put an
     * interruption between levels. The method exists so that the interstitial
     * policy remains real, enforced logic with a place to go if a future
     * decision ever adds a unit — the policy would still decide, not the
     * caller.
     *
     * [onClosed] runs unconditionally, exactly as the interface promises, so
     * the completion flow it sits in can never stall waiting on an ad that
     * does not exist.
     */
    override fun showInterstitial(onClosed: () -> Unit) {
        onClosed()
    }

    // --- showing -------------------------------------------------------------

    private fun present(ad: RewardedAd, delivery: Delivery) {
        // THE WAIT IS OVER — stop the "could not get one in time" timer before
        // it can fire over the top of a PLAYING ad. If it did, the player would
        // be told they got nothing while watching it, and the real reward would
        // then be swallowed by the exactly-once guard.
        cancelShowTimeout()

        val activity = liveActivity()
        if (activity == null) {
            loadedAd = null
            delivery.deliver(RewardedOutcome.UNAVAILABLE)
            return
        }

        loadedAd = null

        // Set before the ad is shown, so a dismissal that arrives without a
        // reward is correctly read as a skip.
        var rewardEarned = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                // THE REWARD DECISION. The ad is gone; the only question is
                // whether Google's reward callback fired while it was up.
                //
                // Delivered here rather than at the reward callback so the
                // whole UI transition happens once, after the full-screen ad has
                // actually left the screen — the player never sees the hint
                // granted while an ad still covers the board.
                cancelShowTimeout()
                delivery.deliver(
                    if (rewardEarned) RewardedOutcome.REWARDED
                    else RewardedOutcome.SKIPPED
                )
                // Refill for next time, quietly.
                preloadRewarded()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                cancelShowTimeout()
                Log.w(TAG, "Rewarded ad failed to show: ${error.code} ${error.message}")
                delivery.deliver(RewardedOutcome.UNAVAILABLE)
                preloadRewarded()
            }
        }

        ad.show(activity) { rewardEarned = true }
    }

    // --- helpers -------------------------------------------------------------

    /**
     * The activity to present on, or null if it has gone.
     *
     * Also refuses a finishing or destroyed activity: showing a full-screen ad
     * over a window that is on its way out throws in the SDK and would take the
     * player's confirmation with it.
     */
    private fun liveActivity(): Activity? {
        val activity = activityRef.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        return activity
    }

    private fun startShowTimeout(delivery: Delivery) {
        val runnable = Runnable {
            // Clear the slot BEFORE answering. Leaving it latched would make
            // every later offer look like a second concurrent one, and would
            // also let a late-arriving ad be presented to a player who has
            // already been told there was none.
            pendingShow = null
            showTimeout = null
            // A no-op if the ad already answered: the guard lives in Delivery.
            delivery.deliver(RewardedOutcome.UNAVAILABLE)
        }
        showTimeout = runnable
        mainHandler.postDelayed(runnable, SHOW_TIMEOUT_MS)
    }

    private fun cancelShowTimeout() {
        showTimeout?.let { mainHandler.removeCallbacks(it) }
        showTimeout = null
    }

    /** Runs [block] on the main thread, where AdMob and UMP expect to be. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            // Wrapped in a lambda literal so the SAM conversion to Runnable is
            // unambiguous for a captured function value.
            mainHandler.post { block() }
        }
    }

    /**
     * Delivers one outcome and never a second.
     *
     * AdMob can plausibly reach a terminal callback through more than one
     * route — a dismissal after a failure, a timeout racing a late load — and a
     * double delivery would grant two hints for one watch, while a delivery
     * that never arrives would leave the confirmation stuck on "Loading…"
     * forever. This is the single place that decides.
     */
    private class Delivery(private val onOutcome: (RewardedOutcome) -> Unit) {
        private val delivered = AtomicBoolean(false)

        fun deliver(outcome: RewardedOutcome) {
            if (delivered.compareAndSet(false, true)) onOutcome(outcome)
        }
    }

    private companion object {
        const val TAG = "PushboundAds"

        /**
         * How long a confirmed offer waits for a fetch before giving up.
         *
         * Long enough for a normal mobile fetch, short enough that a player who
         * tapped "Watch ad" is never left staring at a spinner. Exceeding it
         * reports UNAVAILABLE, which the UI already explains as "your hint is
         * untouched" — so the worst case is a wasted tap, never a lost reward
         * or a blocked game.
         */
        const val SHOW_TIMEOUT_MS = 10_000L
    }
}
