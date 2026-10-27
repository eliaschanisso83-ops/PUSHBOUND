package com.pushbound.game.undo

/**
 * Where the Undo credit balance is read from and written to.
 *
 * BEHIND AN INTERFACE for the same reason [com.pushbound.game.progress.ProgressStore]
 * is: every rule in [UndoGate] stays Android-free and unit testable, and the
 * game never learns that a preferences file exists. The shipped implementation
 * is local and needs no account, no network and no login — Undo credits work
 * completely offline because nothing above this interface can reach a socket.
 */
interface UndoCreditStore {

    /** The saved balance, or null when this player has never had one. */
    fun load(): UndoCreditState?

    /** Writes [state] durably. Implementations must not throw on routine failure. */
    fun save(state: UndoCreditState)
}

/**
 * A store that never persists anything: a fresh player every time.
 *
 * This is the default handed to [com.pushbound.game.ui.GameController], so a
 * test, a preview or an animation harness gets working credits with no
 * Android behind it — and so a build that somehow lost its storage still plays
 * instead of crashing.
 */
class InMemoryUndoCreditStore(initial: UndoCreditState? = null) : UndoCreditStore {

    var state: UndoCreditState? = initial
        private set

    override fun load(): UndoCreditState? = state

    override fun save(state: UndoCreditState) {
        this.state = state
    }
}

/**
 * Text encoding for a [UndoCreditState], following the same hand-rolled
 * approach as [com.pushbound.game.progress.ProgressCodec]: three integers do
 * not justify a serialisation dependency, and a format this small is faster to
 * read, to test and to keep compatible than the machinery it would replace.
 *
 * ONE LINE, fields separated by `|`:
 *
 *     <credits>|<restorationsToday>|<lastResetEpochDay>
 *
 * WHY [decode] RETURNS NULL RATHER THAN THROWING — this value is a convenience
 * resource, not an achievement. A save written by a future build (which may
 * append fields), or one a platform restore mangled, must degrade to "this
 * player has never had a balance", i.e. a full five credits, rather than take
 * the whole screen down. Receiving credits back is the benign failure
 * direction here; losing a medal would not be, which is why progress uses the
 * stricter skip-the-line decoder instead.
 *
 * FORWARD COMPATIBILITY is handled by reading the FIRST three fields only, so
 * a build that appends a fourth does not reset a returning player. Out-of-range
 * values are clamped rather than trusted: a balance above the maximum or below
 * zero is a corrupt write, not a reason to hand out extra moves.
 */
object UndoCreditCodec {

    private const val FIELD_SEP = '|'

    fun encode(state: UndoCreditState): String = listOf(
        state.credits.toString(),
        state.restorationsToday.toString(),
        state.lastResetEpochDay.toString()
    ).joinToString(separator = FIELD_SEP.toString())

    fun decode(payload: String?): UndoCreditState? {
        if (payload.isNullOrBlank()) return null

        val fields = payload.trim().split(FIELD_SEP)
        if (fields.size < 3) return null // malformed: refuse rather than guess

        val credits = fields[0].toIntOrNull() ?: return null
        val restorations = fields[1].toIntOrNull() ?: return null
        // A negative restoration count is nonsense and is rejected. A negative
        // epoch day is NOT: it is simply a date before 1970, which a device
        // with a clock set that far back will genuinely produce, and rejecting
        // it would silently reset that player's balance on every launch.
        val epochDay = fields[2].toLongOrNull() ?: return null
        if (restorations < 0) return null

        return UndoCreditState(
            credits = credits.coerceIn(0, UndoGate.MAX_UNDO_CREDITS),
            restorationsToday = restorations,
            lastResetEpochDay = epochDay
        )
    }
}
