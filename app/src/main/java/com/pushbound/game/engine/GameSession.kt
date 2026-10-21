package com.pushbound.game.engine

/**
 * Plays one level: owns the current [GameState] plus two snapshot stacks that
 * make every valid move (pushes included) reversible and repeatable until the
 * level start.
 *
 * HISTORY MODEL
 *
 *  - [undoStack] holds the states the player has left behind, most recent last.
 *  - [redoStack] holds the states the player can step forward into again.
 *  - A new valid move pushes the current state onto [undoStack] and CLEARS
 *    [redoStack], because a branched history can no longer be replayed
 *    faithfully (the classic undo/redo contract).
 *
 * Both stacks store the very same immutable [GameState] instances the engine
 * produced — nothing is cloned per move, so an unlimited history costs one
 * reference plus the shared snapshot per entry. Undo out of a completed level
 * therefore restores the pre-completion state exactly, and [isComplete] simply
 * re-derives from wherever the cursor currently sits.
 */
class GameSession(private val level: LevelDefinition) {

    var state: GameState = GameEngine.initialState(level)
        private set

    private val undoStack = ArrayDeque<GameState>()
    private val redoStack = ArrayDeque<GameState>()

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    val isComplete: Boolean
        get() = GameEngine.isComplete(level, state)

    fun move(direction: Direction): MoveResult {
        val result = GameEngine.move(level, state, direction)
        if (result is MoveResult.Moved) {
            undoStack.addLast(result.previous)
            redoStack.clear()
            state = result.state
        }
        return result
    }

    /** @return true when a snapshot was restored, false when there was nothing to undo. */
    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.addLast(state)
        state = undoStack.removeLast()
        return true
    }

    /**
     * Steps forward into the state that was undone. Refuses when the redo
     * history was cleared by a new action.
     *
     * @return true when a snapshot was restored, false when there was nothing to redo.
     */
    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.addLast(state)
        state = redoStack.removeLast()
        return true
    }

    fun restart() {
        undoStack.clear()
        redoStack.clear()
        state = GameEngine.initialState(level)
    }
}
