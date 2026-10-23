package com.pushbound.game.progress

import com.pushbound.game.engine.ContentKey
import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameState
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.Position

/**
 * One entry in an undo or redo stack: a full [GameState] without the stacks.
 *
 * Snapshots are exactly what the engine already produces per move, so restoring
 * one is a constructor call and no move has to be replayed. Storing them as data
 * rather than as moves is deliberate: replaying a move list would silently
 * produce a different board the moment a level's geometry changed, whereas
 * restoring a snapshot either matches the level or is rejected outright.
 */
data class SessionSnapshot(
    val player: Position,
    val boxes: List<Position>,
    val moves: Int,
    val pushes: Int,
    val facing: Direction
) {
    fun toState(): GameState =
        GameState(
            player = player,
            boxes = boxes.toSet(),
            moves = moves,
            pushes = pushes,
            facing = facing
        )

    companion object {
        fun from(state: GameState): SessionSnapshot =
            SessionSnapshot(
                player = state.player,
                // Sorted so the encoding is canonical: the same board always
                // serialises to the same text, which is what makes a save
                // comparable and testable.
                boxes = state.boxes.sortedWith(compareBy({ it.row }, { it.col })),
                moves = state.moves,
                pushes = state.pushes,
                facing = state.facing
            )
    }
}

/**
 * The player's most recent ACTIVE game, in enough detail to return them to
 * exactly where they were.
 *
 * THIS IS WHAT "RESUME" MEANS. Before this type existed there was no saved game
 * at all: the RESUME entry re-derived "the first campaign level not yet
 * finished" from progress and then restarted it from the initial position, so
 * it was a second Campaign button that also threw the board away.
 *
 * A session records the MODE ([key]'s [ContentKey.mode]) alongside the content
 * id, because a session must come back as the thing it was: resuming a daily
 * must land on `daily/2026-10-23` and record a daily completion, never quietly
 * convert into a campaign run.
 *
 * [undo] and [redo] are persisted too, so the back and forward buttons survive
 * leaving the app. They are stored oldest-first on purpose: the codec writes
 * them in that order and the restoring code pushes them back in the same order,
 * so the stack tops land where the player left them.
 */
data class SavedSession(
    /** Mode AND content id — the record this session belongs to. */
    val key: ContentKey,
    /** Where the board is right now. */
    val current: SessionSnapshot,
    /** States the player can step back to, most recent LAST. */
    val undo: List<SessionSnapshot>,
    /** States the player can step forward to, most recent LAST. */
    val redo: List<SessionSnapshot>,
    /** When this session was written, for a future "last played" display. */
    val savedAtEpochMillis: Long
) {
    val moves: Int get() = current.moves
    val pushes: Int get() = current.pushes

    /**
     * True when this session can honestly be played on [level].
     *
     * A save is a snapshot of ONE board. Levels get redesigned and campaigns get
     * rebalanced, so an old session can describe crates at cells that are now
     * walls, or the wrong number of crates entirely. Restoring that would put
     * an impossible board on screen; refusing it drops the player back at the
     * level's real start, which is the only sane answer.
     */
    fun isRestorableOn(level: LevelDefinition): Boolean {
        val state = current.toState()
        if (state.boxes.size != level.boxCount) return false
        if (level.isBlocked(state.player)) return false
        if (state.boxes.any { level.isBlocked(it) }) return false
        if (state.boxes.size != state.boxes.toSet().size) return false
        return true
    }
}

/**
 * Where the resumable session is read from and written to.
 *
 * The same shape as [ProgressStore]: the game talks to an interface, never to
 * SharedPreferences, so the rules and the session model stay unit testable
 * without a device.
 */
interface SessionStore {
    /** The saved session, or null when the player has nothing in progress. */
    fun load(): SavedSession?

    /** Writes [session], or clears it when null. Must not throw on routine failure. */
    fun save(session: SavedSession?)
}

/** A store that keeps the session in memory only. Used by tests. */
class InMemorySessionStore(initial: SavedSession? = null) : SessionStore {
    var session: SavedSession? = initial
        private set

    override fun load(): SavedSession? = session
    override fun save(session: SavedSession?) {
        this.session = session
    }
}

/**
 * Text encoding for a [SavedSession], hand-rolled like [ProgressCodec] for the
 * same reason: the payload is a handful of small integers and adding a
 * serialisation dependency to carry them would be machinery in search of a
 * problem.
 *
 * FIRST LINE IS THE HEADER:
 *
 *     <formatVersion>|<storageKey>|<savedAtEpochMillis>
 *
 * FOLLOWED BY ONE LINE PER SNAPSHOT, STACK TAG FIRST:
 *
 *     c|<player>|<boxes>|<moves>|<pushes>|<facing>   current
 *     u|...                                          undo, oldest first
 *     r|...                                          redo, oldest first
 *
 * Positions are `row,col` and the box list is `r,c;r,c`, already sorted by
 * [SessionSnapshot.from] so one board always produces one text.
 *
 * A missing or malformed header returns null rather than a partial session: a
 * half-restored game is worse than none, and the player is simply offered a
 * fresh start.
 */
object SessionCodec {

    private const val FORMAT_VERSION = "1"
    private const val SEP = '|'
    private const val BOX_SEP = ";"

    fun encode(session: SavedSession): String = buildString {
        append(FORMAT_VERSION)
        append(SEP).append(session.key.storageKey)
        append(SEP).append(session.savedAtEpochMillis)
        append('\n')
        appendLine(snapshot("c", session.current))
        session.undo.forEach { appendLine(snapshot("u", it)) }
        session.redo.forEach { appendLine(snapshot("r", it)) }
    }

    fun decode(payload: String): SavedSession? {
        if (payload.isBlank()) return null
        val lines = payload.lineSequence().filter { it.isNotBlank() }.toList()
        val header = lines.firstOrNull()?.split(SEP) ?: return null
        if (header.size < 3 || header[0] != FORMAT_VERSION) return null

        val key = ContentKey.fromStorageKey(header[1]) ?: return null
        val savedAt = header[2].toLongOrNull() ?: return null

        var current: SessionSnapshot? = null
        val undo = mutableListOf<SessionSnapshot>()
        val redo = mutableListOf<SessionSnapshot>()

        for (line in lines.drop(1)) {
            val fields = line.split(SEP)
            if (fields.size != 6) return null // a truncated save restores nothing
            val snapshot = snapshot(fields) ?: return null
            when (fields[0]) {
                "c" -> current = snapshot
                "u" -> undo += snapshot
                "r" -> redo += snapshot
                else -> return null
            }
        }

        val resolved = current ?: return null
        return SavedSession(
            key = key,
            current = resolved,
            undo = undo,
            redo = redo,
            savedAtEpochMillis = savedAt
        )
    }

    private fun snapshot(tag: String, s: SessionSnapshot): String =
        listOf(
            tag,
            position(s.player),
            s.boxes.joinToString(BOX_SEP) { position(it) },
            s.moves.toString(),
            s.pushes.toString(),
            s.facing.name
        ).joinToString(SEP.toString())

    /** Parses the six fields of a snapshot line, or null when any part is bad. */
    private fun snapshot(fields: List<String>): SessionSnapshot? {
        val player = position(fields[1]) ?: return null
        val boxes = if (fields[2].isEmpty()) emptyList()
        else fields[2].split(BOX_SEP).map { position(it) ?: return null }
        val moves = fields[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val pushes = fields[4].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val facing = Direction.entries.firstOrNull { it.name == fields[5] } ?: return null
        return SessionSnapshot(player, boxes, moves, pushes, facing)
    }

    private fun position(p: Position): String = "${p.row},${p.col}"

    private fun position(text: String): Position? {
        val parts = text.split(',')
        if (parts.size != 2) return null
        val row = parts[0].toIntOrNull() ?: return null
        val col = parts[1].toIntOrNull() ?: return null
        return Position(row, col)
    }
}
