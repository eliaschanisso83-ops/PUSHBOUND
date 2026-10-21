package com.pushbound.game.progress

import com.pushbound.game.engine.Medal

/**
 * Where progress is read from and written to.
 *
 * Keeping persistence BEHIND THIS INTERFACE is what makes the campaign
 * offline-first and cloud-ready at the same time: the game talks to a store,
 * never to SharedPreferences or a socket. The shipped implementation writes
 * locally and needs no account; a future sync implementation can implement the
 * same two methods over the network without touching a single rule in
 * [Progress].
 */
interface ProgressStore {
    /** The stored state, or a fresh empty one when nothing has been saved yet. */
    fun load(): ProgressState

    /** Writes [state] durably. Implementations must not throw on routine failure. */
    fun save(state: ProgressState)
}

/**
 * A store that never persists anything. Used before a real store is wired in
 * and by tests, so gameplay code never has to ask whether storage exists.
 */
class InMemoryProgressStore(initial: ProgressState = ProgressState()) : ProgressStore {
    var state: ProgressState = initial
        private set

    override fun load(): ProgressState = state
    override fun save(state: ProgressState) {
        this.state = state
    }
}

/**
 * Text encoding for a [ProgressState].
 *
 * Deliberately hand-rolled rather than pulled from a serialisation library:
 * the campaign needs no new dependency to remember five integers per level, and
 * a format this small is faster to read, to test and to keep compatible than
 * the machinery it would replace.
 *
 * ONE RECORD PER LINE, fields separated by '|':
 *
 *     <id>|<completed>|<medal>|<bestMoves>|<bestPushes>
 *
 * A missing optional value is written as '-'. Level ids come from
 * [com.pushbound.game.engine.LevelDefinition.slug], so they are lowercase
 * alphanumerics and hyphens and can never contain either separator — that is
 * asserted on write rather than assumed.
 *
 * FORWARD COMPATIBILITY — an unrecognised line is SKIPPED rather than failing
 * the whole load, and an unrecognised medal falls back to no medal. A player
 * upgrading from a newer version to an older one keeps their progress instead
 * of losing it to a parse error.
 */
object ProgressCodec {

    private const val FIELD_SEP = '|'
    private const val LINE_SEP = '\n'
    private const val ABSENT = "-"

    fun encode(state: ProgressState): String =
        state.records.values
            .sortedBy { it.levelId }
            .joinToString(separator = LINE_SEP.toString()) { record ->
                listOf(
                    record.levelId,
                    if (record.completed) "1" else "0",
                    record.bestMedal?.name ?: ABSENT,
                    record.bestMoves?.toString() ?: ABSENT,
                    record.bestPushes?.toString() ?: ABSENT
                ).joinToString(separator = FIELD_SEP.toString())
            }

    fun decode(payload: String): ProgressState {
        if (payload.isBlank()) return ProgressState()

        val records = mutableMapOf<String, LevelRecord>()

        for (line in payload.lineSequence()) {
            if (line.isBlank()) continue
            val fields = line.split(FIELD_SEP)
            if (fields.size < 5) continue // malformed: skip, never throw

            val id = fields[0]
            if (id.isEmpty() || id.any { it == FIELD_SEP || it == LINE_SEP }) continue

            val completed = fields[1] == "1"
            val medal = fields[2].let { raw ->
                if (raw == ABSENT) null
                else Medal.entries.firstOrNull { it.name == raw }
            }
            val moves = fields[3].toIntOrNull()?.takeIf { it >= 0 }
            val pushes = fields[4].toIntOrNull()?.takeIf { it >= 0 }

            records[id] = LevelRecord(
                levelId = id,
                completed = completed,
                bestMedal = medal,
                bestMoves = moves,
                bestPushes = pushes
            )
        }

        return ProgressState(records)
    }
}
