package com.pushbound.game.engine

/**
 * Plays one level: owns the current [GameState] plus a simple snapshot history
 * that makes every valid move (pushes included) reversible until the level start.
 */
class GameSession(private val level: LevelDefinition) {

    var state: GameState = GameEngine.initialState(level)
        private set

    private val history = ArrayDeque<GameState>()

    val canUndo: Boolean
        get() = history.isNotEmpty()

    val isComplete: Boolean
        get() = GameEngine.isComplete(level, state)

    fun move(direction: Direction): MoveResult {
        val result = GameEngine.move(level, state, direction)
        if (result is MoveResult.Moved) {
            history.addLast(result.previous)
            state = result.state
        }
        return result
    }

    /** @return true when a snapshot was restored, false when there was nothing to undo. */
    fun undo(): Boolean {
        if (history.isEmpty()) return false
        state = history.removeLast()
        return true
    }

    fun restart() {
        history.clear()
        state = GameEngine.initialState(level)
    }
}
