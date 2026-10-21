package com.pushbound.game.ads

/**
 * The seam between the game and an advertising SDK.
 *
 * WHY THIS EXISTS AT ALL — Pushbound currently ships with **no advertising of
 * any kind**: no AdMob dependency, no analytics, no `INTERNET` permission. That
 * is not an omission to patch blindly. Integrating an SDK before real ad unit
 * IDs exist produces a build that earns nothing and can fail at load time, and
 * it would put a network dependency into a game that is offline-first by
 * design.
 *
 * So the game talks to this interface, the default implementation does nothing,
 * and gameplay never learns the difference:
 *
 *  - Hints work with or without ads. An unavailable ad never blocks play,
 *    never blocks progression and never blocks a completion.
 *  - [InterstitialPolicy] is real, enforced logic that runs today, so when an
 *    SDK is wired in, "show an interstitial" already means "show it only where
 *    it is allowed".
 *
 * ENABLING REAL ADS requires two things that only the publisher can supply: an
 * AdMob app/unit IDs, and the `android.permission.INTERNET` permission. Until
 * both exist, [NoOpAdsGateway] is the correct production default.
 */
interface AdsGateway {

    /** Whether a rewarded video can be offered to the player right now. */
    fun isRewardedAvailable(): Boolean

    /**
     * Offers a rewarded video. [onReward] runs ONLY if the player actually
     * watched it through; [onSkipped] runs if they closed it or it could not be
     * loaded. Exactly one of the two runs, exactly once.
     */
    fun showRewarded(onReward: () -> Unit, onSkipped: () -> Unit)

    /**
     * Offers an interstitial. [onClosed] always runs, even if nothing was shown,
     * so the caller's flow never stalls waiting on an ad.
     */
    fun showInterstitial(onClosed: () -> Unit)
}

/**
 * The default: no SDK, no network, nothing shown.
 *
 * This is the shipped implementation while ad unit IDs do not exist. It behaves
 * exactly as an SDK that declined to fill — every callback still fires — so the
 * no-ad path and the ad path are the same code path.
 */
object NoOpAdsGateway : AdsGateway {
    override fun isRewardedAvailable(): Boolean = false

    override fun showRewarded(onReward: () -> Unit, onSkipped: () -> Unit) {
        // Nothing to watch: the reward is correctly not granted.
        onSkipped()
    }

    override fun showInterstitial(onClosed: () -> Unit) {
        // Nothing to show; the caller continues regardless.
        onClosed()
    }
}
