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
     * Successful pushes only. Moves and pushes are counted separately because
     * they measure different skills: moving is free navigation, pushing is the
     * scarce resource a solver optimises. A refused push is never counted (the
     * engine returns [MoveResult.Blocked] and leaves the state untouched), so
     * this number only ever increases by exactly one, on a real push.
     */
    val pushes: Int = 0,

    /**
     * Direction of the last successful move; the renderer swaps the
     * directional character frame to match. Starts facing DOWN (toward the
     * viewer), undo/redo of snapshots restores the facing they carried.
     */
    val facing: Direction = Direction.DOWN
)
