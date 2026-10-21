package com.pushbound.game.ads

/**
 * Where the player is in the flow when an interstitial would be considered.
 *
 * Enumerated rather than inferred so the *call site* states its intent, and the
 * policy — not the caller — decides. That is what stops a future screen from
 * quietly inserting an ad somewhere disruptive: it can only pick a placement,
 * never bypass the rules below.
 */
enum class AdPlacement {
    /** On the board with the level live. Absolutely never. */
    DURING_GAMEPLAY,

    /** Immediately after an attempt that failed or was abandoned. */
    AFTER_FAILURE,

    /** Immediately after pressing restart. */
    AFTER_RESTART,

    /** On the completion overlay, before the player moves on. */
    LEVEL_COMPLETE,

    /** On the level-select / world screen. */
    LEVEL_SELECT,

    /** On the main menu. */
    MENU
}

/** The policy's memory of what has already been shown. */
data class InterstitialState(
    /** When an interstitial last displayed, or [InterstitialPolicy.NEVER]. */
    val lastShownAtMs: Long = InterstitialPolicy.NEVER,
    /** When a rewarded ad last displayed, or [InterstitialPolicy.NEVER]. */
    val rewardedShownAtMs: Long = InterstitialPolicy.NEVER,
    /** Levels finished since the last interstitial, used to throttle frequency. */
    val completionsSinceLast: Int = 0
)

/**
 * When an interstitial is allowed to appear.
 *
 * THE RULES, in the order they are checked, and why each one exists:
 *
 *  1. **Never in the moment it would hurt.** [AdPlacement.DURING_GAMEPLAY],
 *     [AdPlacement.AFTER_FAILURE] and [AdPlacement.AFTER_RESTART] are refused
 *     outright. A player mid-thought, a player who just lost, and a player who
 *     just asked to start over are the three moments where an ad reads as a
 *     punishment.
 *  2. **Never straight after a rewarded ad.** A player who just chose to watch
 *     an ad in exchange for something must not immediately be shown another
 *     one; stacking them is both rude and the fastest way to make the rewarded
 *     offer feel like a trap. [MIN_GAP_AFTER_REWARDED_MS] enforces the gap.
 *  3. **Not on every completion.** [DEFAULT_MIN_COMPLETIONS_BETWEEN] completions
 *     must pass between showings, so finishing a level stays frictionless and
 *     the ad is a punctuation mark rather than a toll.
 *  4. **Not more often than a floor interval.** [DEFAULT_MIN_INTERVAL_MS] is the
 *     hard rate limit, independent of everything else. Tunable per release in
 *     one place — never scattered across call sites.
 *
 * EVERYTHING IS PURE — a function of (placement, state, now). No SDK, no clock
 * read inside the policy, no Android. The rules can therefore be pinned exactly
 * by unit tests before any ad SDK exists, which is the only way to guarantee
 * they are still enforced after one is wired in.
 */
object InterstitialPolicy {

    /** Sentinel for "has never happened". Chosen so arithmetic on it cannot overflow. */
    const val NEVER = Long.MIN_VALUE / 4

    /** Hard floor between interstitials. Four minutes by default. */
    const val DEFAULT_MIN_INTERVAL_MS = 240_000L

    /** Mandatory quiet period after any rewarded ad. Three minutes by default. */
    const val MIN_GAP_AFTER_REWARDED_MS = 180_000L

    /** Completions required between interstitials. Two by default. */
    const val DEFAULT_MIN_COMPLETIONS_BETWEEN = 2

    /** Placements that are never eligible, whatever the timing. */
    val ALWAYS_BLOCKED: Set<AdPlacement> = setOf(
        AdPlacement.DURING_GAMEPLAY,
        AdPlacement.AFTER_FAILURE,
        AdPlacement.AFTER_RESTART
    )

    fun isEligible(placement: AdPlacement): Boolean = placement !in ALWAYS_BLOCKED

    /**
     * Whether an interstitial may be shown at [placement] at [nowMs] given
     * [state]. Consult [explain] when this returns false and the reason is not
     * obvious.
     */
    fun shouldShow(placement: AdPlacement, state: InterstitialState, nowMs: Long): Boolean =
        blockingReason(placement, state, nowMs) == null

    /**
     * The first rule preventing a showing, or null when nothing does.
     * Returned as text so tests and debug logs state exactly which rule fired.
     */
    fun blockingReason(
        placement: AdPlacement,
        state: InterstitialState,
        nowMs: Long
    ): String? {
        if (placement in ALWAYS_BLOCKED) {
            return "'$placement' is never an ad moment"
        }

        if (state.rewardedShownAtMs != NEVER) {
            val sinceRewarded = nowMs - state.rewardedShownAtMs
            if (sinceRewarded < MIN_GAP_AFTER_REWARDED_MS) {
                return "only ${MIN_GAP_AFTER_REWARDED_MS - sinceRewarded}ms after a rewarded ad " +
                    "(need ${MIN_GAP_AFTER_REWARDED_MS}ms)"
            }
        }

        if (state.completionsSinceLast < DEFAULT_MIN_COMPLETIONS_BETWEEN) {
            return "only ${state.completionsSinceLast} completion(s) since the last interstitial " +
                "(need ${DEFAULT_MIN_COMPLETIONS_BETWEEN})"
        }

        if (state.lastShownAtMs != NEVER) {
            val sinceLast = nowMs - state.lastShownAtMs
            if (sinceLast < DEFAULT_MIN_INTERVAL_MS) {
                return "only ${DEFAULT_MIN_INTERVAL_MS - sinceLast}ms since the last interstitial " +
                    "(need ${DEFAULT_MIN_INTERVAL_MS}ms)"
            }
        }

        return null
    }

    /** Records that an interstitial displayed, resetting the completion count. */
    fun onInterstitialShown(state: InterstitialState, nowMs: Long): InterstitialState =
        state.copy(lastShownAtMs = nowMs, completionsSinceLast = 0)

    /** Records that a rewarded ad displayed; starts the mandatory quiet period. */
    fun onRewardedShown(state: InterstitialState, nowMs: Long): InterstitialState =
        state.copy(rewardedShownAtMs = nowMs)

    /** A level finished; moves the completion-based throttle along. */
    fun onLevelCompleted(state: InterstitialState): InterstitialState =
        state.copy(completionsSinceLast = state.completionsSinceLast + 1)
}
