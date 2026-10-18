package com.pushbound.game.engine

/**
 * A complete, immutable snapshot of the puzzle. Every valid move produces a new
 * snapshot, which makes undo a simple matter of keeping previous snapshots.
 */
data class GameState(
    val player: Position,
    val boxes: Set<Position>,
    val moves: Int
)
