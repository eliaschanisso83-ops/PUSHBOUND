package com.pushbound.game.ads

/**
 * How many hints a player has used on the current level attempt.
 *
 * Kept per ATTEMPT — it resets when the level restarts or a new level opens —
 * so a player who is simply stuck can never accumulate a wall that stops them
 * finishing. Hints are assistance, never a resource with a permanent price.
 */
data class HintAllowance(
    /** Hints taken from the free allowance on this attempt. */
    val freeUsed: Int = 0,
    /** Hints granted in exchange for watching a rewarded ad on this attempt. */
    val adGranted: Int = 0
) {
    val totalGranted: Int get() = freeUsed + adGranted

    val remaining: Int get() = (HintGate.FREE_HINTS_PER_LEVEL - freeUsed).coerceAtLeast(0)
}

/** The outcome of asking for a hint. */
enum class HintAccess {
    /** Inside the free allowance — just hand it over, no ad, no friction. */
    GRANTED_FREE,

    /**
     * The free allowance is spent, but the player can CHOOSE to watch a
     * rewarded ad. This is an offer, never a demand: declining changes nothing
     * about their ability to play or to finish the level.
     */
    OFFER_REWARDED_AD,

    /**
     * The allowance is spent and no rewarded ad can be offered (no SDK
     * configured, or offline). The hint is withheld. Play continues normally —
     * a hint is optional by definition and its absence blocks nothing.
     */
    DENIED
}

/**
 * The hint access rule: free first, then optionally ad-assisted, then simply
 * unavailable.
 *
 * WHY AN ALLOWANCE AT ALL — rewarded ads are meant to be the primary
 * monetization, and the hint is the one thing a stuck player actively wants.
 * A small free allowance keeps the feature honest (nobody is ever forced to
 * pay or watch anything to finish a level) while leaving a natural, voluntary
 * moment where an ad can be offered with real value attached.
 *
 * THE GUARANTEES THAT MATTER:
 *
 *  - **Progression is never gated.** No rule here touches unlocking, medals or
 *    completion. A player with zero hints available plays exactly the same
 *    game.
 *  - **Declining is free.** [HintAccess.OFFER_REWARDED_AD] is an offer; the
 *    player may simply not take it and lose nothing they had.
 *  - **No SDK still works.** With [NoOpAdsGateway] the gate returns [HintAccess.DENIED]
 *    past the allowance, and every other part of the game is untouched.
 *
 * Pure arithmetic — unit tested without Android or an SDK.
 */
object HintGate {

    /** Free hints per level attempt. Deliberately small and deliberately free. */
    const val FREE_HINTS_PER_LEVEL = 3

    /** What happens when the player asks for a hint right now. */
    fun access(state: HintAllowance, rewardedAvailable: Boolean): HintAccess = when {
        state.freeUsed < FREE_HINTS_PER_LEVEL -> HintAccess.GRANTED_FREE
        rewardedAvailable -> HintAccess.OFFER_REWARDED_AD
        else -> HintAccess.DENIED
    }

    /** Consumes one of the free hints. Only called when the free path was taken. */
    fun onFreeHintUsed(state: HintAllowance): HintAllowance =
        state.copy(freeUsed = state.freeUsed + 1)

    /** The player watched a rewarded ad through: an extra hint is granted. */
    fun onRewardEarned(state: HintAllowance): HintAllowance =
        state.copy(adGranted = state.adGranted + 1)

    /** A fresh attempt: the allowance starts over. */
    fun reset(): HintAllowance = HintAllowance()
}
