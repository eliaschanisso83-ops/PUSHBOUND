package com.pushbound.game.engine

/**
 * A complete, immutable snapshot of the puzzle. Every valid move produces a new
 * snapshot, which makes undo a simple matter of keeping previous snapshots.
 */
data class GameState(
    val player: Position,
    val boxes: Set<Position>,
    val moves: Int,

    /**
     * Direction of the last successful move; the renderer swaps the
     * directional character frame to match. Starts facing DOWN (toward the
     * viewer), undo/redo of snapshots restores the facing they carried.
     */
    val facing: Direction = Direction.DOWN
)
