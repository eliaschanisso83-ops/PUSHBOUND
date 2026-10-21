package com.pushbound.game.engine

/** The four grid moves. Row/column deltas are in logical cells. */
enum class Direction(val rowDelta: Int, val colDelta: Int) {
    UP(-1, 0),
    DOWN(1, 0),
    LEFT(0, -1),
    RIGHT(0, 1);

    /**
     * Lowercase word for player-facing hint text. Kept on the enum rather than
     * in the UI so hint wording stays in one place and unit tests can assert it
     * without Android.
     */
    val label: String
        get() = name.lowercase()

    /** The opposite move, used to describe where a player must stand to push. */
    val opposite: Direction
        get() = when (this) {
            UP -> DOWN
            DOWN -> UP
            LEFT -> RIGHT
            RIGHT -> LEFT
        }
}
