package com.pushbound.game.engine

/** Outcome of one attempted move. */
sealed interface MoveResult {

    /** The move was applied. Exactly one box moves per push ([pushedBox] = origin to destination). */
    data class Moved(
        val previous: GameState,
        val state: GameState,
        val pushedBox: Pair<Position, Position>?
    ) : MoveResult

    /** The move was refused (wall, board edge, blocked push, or completed level). Nothing changed. */
    data object Blocked : MoveResult
}
