package com.pushbound.game.engine

/**
 * Deterministic Sokoban rules. Pure Kotlin: no Android or UI dependencies,
 * fully covered by unit tests. Supports any number of boxes and goals.
 */
object GameEngine {

    fun initialState(level: LevelDefinition): GameState =
        GameState(
            player = level.playerStart,
            boxes = level.boxStarts.toSet(),
            moves = 0
        )

    /** Solved when at least one goal exists and every box rests on a goal. */
    fun isComplete(level: LevelDefinition, state: GameState): Boolean =
        level.goals.isNotEmpty() &&
            state.boxes.size == level.goals.size &&
            state.boxes.all { it in level.goals }

    /**
     * Applies one grid move:
     *  - empty floor ahead: the player steps.
     *  - box ahead with free floor behind it: the player pushes it one cell.
     *  - wall, board edge, second box, or a completed level: [MoveResult.Blocked].
     */
    fun move(level: LevelDefinition, state: GameState, direction: Direction): MoveResult {
        if (isComplete(level, state)) return MoveResult.Blocked

        val target = state.player.moved(direction)
        if (level.isBlocked(target)) return MoveResult.Blocked

        if (target !in state.boxes) {
            return MoveResult.Moved(
                previous = state,
                state = state.copy(player = target, moves = state.moves + 1),
                pushedBox = null
            )
        }

        val beyond = target.moved(direction)
        if (level.isBlocked(beyond) || beyond in state.boxes) return MoveResult.Blocked

        return MoveResult.Moved(
            previous = state,
            state = state.copy(
                player = target,
                boxes = state.boxes - target + beyond,
                moves = state.moves + 1
            ),
            pushedBox = target to beyond
        )
    }
}
