package com.pushbound.game.ads

/** How an offered rewarded ad ended. Exactly one is delivered per offer. */
enum class RewardedOutcome {
    /** The player watched it through and Google fired the reward callback. */
    REWARDED,

    /** The player closed it early. No reward. */
    SKIPPED,

    /** The player dismissed the consent form before any ad was shown. */
    DISMISSED,

    /**
     * The ad could not be shown at all — nothing loaded, or it expired between
     * loading and showing. No reward, and the caller must treat this as "ads
     * are not available right now", not as a player decision.
     */
    UNAVAILABLE
}

/**
 * How offerable a rewarded ad is right now, so the UI can explain the choice
 * it is about to make rather than discovering the answer after the tap.
 */
enum class RewardedAvailability {
    /** A playable ad is loaded and ready. */
    READY,

    /**
     * No playable ad is loaded, but one can still be requested. The offer is
     * honest — the player is told an ad will be attempted — and an attempt that
     * fails comes back as [RewardedOutcome.UNAVAILABLE].
     */
    LOADING,

    /**
     * Nothing can be offered: no SDK configured, the user is offline, or the
     * last load failed. The hint is simply withheld and play continues.
     */
    UNAVAILABLE
}

/**
 * The seam between the game and an advertising SDK.
 *
 * WHY THIS EXISTS — the puzzle engine must know only that a reward was
 * granted, never that Google AdMob exists. Every AdMob type, every callback and
 * every lifecycle rule lives behind this interface, which is pure Kotlin and
 * Android-free, so the whole policy above it stays unit testable on the JVM.
 *
 * THE ONE AD FORMAT IN USE is Rewarded, on the `Pushbound_Rewarded_Hint` unit.
 * There is deliberately no banner, no interstitial unit and no app-open unit:
 * Pushbound is a puzzle game and the player's concentration outranks ad
 * impressions. [showInterstitial] exists as a seam only, so that a future
 * decision to add one has a place to go that is still governed by
 * [InterstitialPolicy] — it is not wired to any unit and shows nothing.
 *
 * [NoOpAdsGateway] remains the default, so a build without the SDK, an
 * instrumented test, or a preview never touches the network and gameplay is
 * byte-for-byte identical.
 */
interface AdsGateway {

    /**
     * Whether a rewarded ad can be OFFERED right now.
     *
     * Deliberately coarser than [RewardedAvailability]: callers that only need
     * a yes/no (the hint gate, the menu) ask this, while a caller that wants to
     * explain the choice asks for the richer state.
     */
    fun isRewardedAvailable(): Boolean

    /**
     * The precise state of the rewarded slot, for UI that must tell the player
     * what will happen before they commit to watching anything.
     */
    fun rewardedAvailability(): RewardedAvailability = RewardedAvailability.UNAVAILABLE

    /**
     * Requests that an ad be loaded in the background, so that a confirmed
     * offer has something to show. Safe to call at any time and from any
     * thread; a failure is reported only through [rewardedAvailability].
     *
     * Loading is not showing: an implementation must never place an ad on
     * screen as a result of this call.
     *
     * An implementation that gates on consent must also make this a no-op
     * once the player has declined it — retrying a refusal is nagging.
     */
    fun preloadRewarded() {}

    /**
     * Offers a rewarded video, starting with any consent form the SDK requires.
     *
     * [onOutcome] runs EXACTLY ONCE with one of [RewardedOutcome]'s values. A
     * reward must be granted for [RewardedOutcome.REWARDED] and for no other
     * outcome — in particular a skipped or dismissed ad grants nothing, which
     * is Google's own rule and not something this layer is free to relax.
     *
     * Consent is not an interruption of gameplay: a form the user declines is
     * reported as [RewardedOutcome.DISMISSED] and the flow continues.
     */
    fun showRewarded(onOutcome: (RewardedOutcome) -> Unit)

    /**
     * Offers an interstitial. [onClosed] always runs, even if nothing was shown,
     * so the caller's flow never stalls waiting on an ad.
     */
    fun showInterstitial(onClosed: () -> Unit)
}

/**
 * The default: no SDK, no network, nothing shown.
 *
 * This is the shipped default when the SDK is not wired in — an instrumented
 * test, a preview, or a build without AdMob. It behaves exactly as an SDK that
 * declined to fill: every callback still fires, so the no-ad path and the ad
 * path are the SAME code path and gameplay never learns the difference.
 *
 * [RewardedOutcome.UNAVAILABLE] rather than [RewardedOutcome.SKIPPED] is the
 * right answer here, because the player made no decision — nothing was ever
 * offered. Telling the caller "unavailable" keeps the distinction the UI needs
 * between "you skipped it" and "there was nothing to skip".
 */
object NoOpAdsGateway : AdsGateway {
    override fun isRewardedAvailable(): Boolean = false

    override fun rewardedAvailability(): RewardedAvailability =
        RewardedAvailability.UNAVAILABLE

    override fun showRewarded(onOutcome: (RewardedOutcome) -> Unit) {
        // Nothing to watch: the reward is correctly not granted.
        onOutcome(RewardedOutcome.UNAVAILABLE)
    }

    override fun showInterstitial(onClosed: () -> Unit) {
        // Nothing to show; the caller continues regardless.
        onClosed()
    }
}
