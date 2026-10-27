package com.pushbound.game.undo

/**
 * How the player may use Undo RIGHT NOW, derived purely from their balance.
 *
 * Three states rather than a boolean because the two "no credits" answers are
 * genuinely different to the player: [OFFER_REWARDED] is a voluntary way to
 * earn more, [EXHAUSTED] means there is genuinely nothing left to offer today
 * and saying so is more honest than showing an offer that could not pay out.
 */
enum class UndoAccess {
    /** Credits remain: an ordinary undo may be performed. */
    AVAILABLE,

    /**
     * No credits, but today's rewarded allowance still has room, so a
     * restoration can be OFFERED. Offering is never demanding: the player may
     * decline and lose nothing they had.
     */
    OFFER_REWARDED,

    /**
     * No credits and no rewarded restoration left on this local calendar day.
     *
     * The player is told this plainly rather than being led into an ad that
     * could not grant anything — the daily limit counts only SUCCESSFUL
     * restorations, so an offer made here would be refused by the same rule a
     * moment later.
     */
    EXHAUSTED
}

/**
 * The player's Undo resource: a balance plus the day's rewarded top-up count.
 *
 * WHY A RESOURCE AT ALL — Undo is the one control that directly walks back a
 * decision the player already made, so it is the natural place for a small,
 * clearly visible limit. It is deliberately NOT a barrier to finishing: the
 * engine, the levels and progression never consult this class, a player who
 * never uses Undo plays exactly the same game, and every credit spent can be
 * earned back by CHOOSING to watch one short ad.
 *
 * THREE FIELDS, AND EACH ONE EXISTS FOR A DIFFERENT REASON:
 *
 *  - [credits] is the balance. It is never reset by the calendar: a player
 *    who ends day one with two credits starts day two with two.
 *  - [restorationsToday] counts only SUCCESSFUL rewarded restorations, so a
 *    failed load, a closed ad or a declined offer never spends it.
 *  - [lastResetEpochDay] is the local calendar day that count belongs to. It
 *    is an epoch day rather than a formatted string for the same reason the
 *    daily puzzle uses one: a pure integer cannot be malformed by a locale.
 *
 * All arithmetic here is pure and Android-free, so the whole rule — including
 * the midnight rollover — is unit tested on the JVM.
 */
data class UndoCreditState(
    /** Credits left. Always within `0..[UndoGate.MAX_UNDO_CREDITS]`. */
    val credits: Int,
    /** Successful rewarded restorations already taken on [lastResetEpochDay]. */
    val restorationsToday: Int,
    /** The local calendar day [restorationsToday] belongs to. */
    val lastResetEpochDay: Long
)

/**
 * The Undo credit rules.
 *
 * THE GUARANTEES THAT MATTER:
 *
 *  - The balance never goes negative and never exceeds
 *    [MAX_UNDO_CREDITS]; there is no code path that can produce either.
 *  - A credit is spent only through [spend], and only for an undo the engine
 *    actually performed — [com.pushbound.game.ui.GameController] charges after
 *    `session.undo()` has returned true, so a refused undo costs nothing.
 *  - The calendar resets [UndoCreditState.restorationsToday] and NOTHING
 *    else. [rollForward] never touches [UndoCreditState.credits], which is
 *    exactly the "carry your remaining moves into tomorrow" rule.
 *  - [restore] refuses once the day's allowance is spent, so the fourth
 *    attempt in a day grants nothing even if an offer somehow reached it.
 *
 * Pure arithmetic — no clock, no storage, no Android. The caller supplies
 * today's epoch day, which is what makes the midnight rollover testable.
 */
object UndoGate {

    /** The starting and maximum balance. There is no way to hold more. */
    const val MAX_UNDO_CREDITS = 5

    /**
     * Successful rewarded restorations allowed per local calendar day.
     *
     * Counts rewards, not attempts: a load failure, a skipped ad or a
     * declined offer leaves this untouched, so a player who is simply offline
     * never burns their allowance for the day.
     */
    const val DAILY_REWARDED_RESTORATIONS = 3

    /** A brand-new player: full balance, nothing taken today. */
    fun initial(todayEpochDay: Long): UndoCreditState =
        UndoCreditState(
            credits = MAX_UNDO_CREDITS,
            restorationsToday = 0,
            lastResetEpochDay = todayEpochDay
        )

    /**
     * Applies the local-calendar-day rollover, if one is due.
     *
     * ONLY the restoration count resets. [UndoCreditState.credits] is carried
     * across untouched, so a player holding two moves at midnight still holds
     * two in the morning — the daily reset governs the ad allowance, never
     * the balance the player is holding.
     *
     * Idempotent: rolling an already-current state returns it unchanged, which
     * is why it can be called from every entry point without a "have I already
     * done this" flag.
     */
    fun rollForward(state: UndoCreditState, todayEpochDay: Long): UndoCreditState =
        if (state.lastResetEpochDay == todayEpochDay) state
        else state.copy(
            restorationsToday = 0,
            lastResetEpochDay = todayEpochDay
        )

    /** What a tap on the Undo control should do right now. */
    fun access(state: UndoCreditState): UndoAccess = when {
        state.credits > 0 -> UndoAccess.AVAILABLE
        state.restorationsToday < DAILY_REWARDED_RESTORATIONS -> UndoAccess.OFFER_REWARDED
        else -> UndoAccess.EXHAUSTED
    }

    /**
     * Spends one credit.
     *
     * @return the state one credit lower, or **null when nothing may be
     *   spent** — at zero the balance must stay at zero rather than go
     *   negative, so refusal is expressed as "no next state" instead of a
     *   clamped value the caller might forget to check.
     */
    fun spend(state: UndoCreditState): UndoCreditState? {
        if (state.credits <= 0) return null
        return state.copy(credits = state.credits - 1)
    }

    /**
     * Applies a CONFIRMED rewarded restoration: the full balance, plus one
     * counted against today's allowance.
     *
     * @param todayEpochDay the local calendar day the reward arrived on, so a
     *   reward that spans midnight lands on the new day rather than the old.
     * @return the restored state, or **null when the allowance for
     *   [todayEpochDay] is already spent** — the fourth attempt of a day
     *   restores nothing, whatever the caller believed.
     */
    fun restore(state: UndoCreditState, todayEpochDay: Long): UndoCreditState? {
        val rolled = rollForward(state, todayEpochDay)
        if (rolled.restorationsToday >= DAILY_REWARDED_RESTORATIONS) return null
        return rolled.copy(
            credits = MAX_UNDO_CREDITS,
            restorationsToday = rolled.restorationsToday + 1
        )
    }

    /**
     * What the Undo control announces to a screen reader.
     *
     * The NUMBER IS NOT THE ONLY SIGNAL, deliberately: a player navigating by
     * speech output must hear that Undo is unavailable, not merely infer it
     * from a count they may not have heard read out. English is hard-coded
     * because this is exactly what the rest of the interface does — Pushbound
     * has no localisation layer, so inventing one for this string alone would
     * be a larger change than the feature.
     */
    fun accessibilityLabel(state: UndoCreditState): String = when {
        state.credits <= 0 -> "Undo unavailable, no moves remaining"
        state.credits == 1 -> "Undo, 1 move remaining"
        else -> "Undo, ${state.credits} moves remaining"
    }
}
