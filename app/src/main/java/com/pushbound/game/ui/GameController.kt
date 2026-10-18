package com.pushbound.game.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameSession
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.MoveResult
import com.pushbound.game.engine.Position
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One animated push, expressed in grid coordinates. */
data class PushAnim(val from: Position, val to: Position, val id: Long)

/**
 * Thin Compose glue around the pure [GameSession]: exposes observable state for
 * the renderer, keeps a single queued input while a move animates, and never
 * contains puzzle rules.
 */
class GameController(
    val level: LevelDefinition,
    private val scope: CoroutineScope
) {
    private val session = GameSession(level)

    var state by mutableStateOf(session.state)
        private set
    var isComplete by mutableStateOf(session.isComplete)
        private set
    var canUndo by mutableStateOf(session.canUndo)
        private set
    var lastPush by mutableStateOf<PushAnim?>(null)
        private set
    var resetEpoch by mutableStateOf(0)
        private set

    private var animationJob: Job? = null
    private var queuedDirection: Direction? = null
    private var pushId = 0L

    fun onDirectionPressed(direction: Direction) {
        if (isComplete) return
        if (animationJob?.isActive == true) {
            queuedDirection = direction // keep exactly one queued input
            return
        }
        performMove(direction)
    }

    private fun performMove(direction: Direction) {
        val result = session.move(direction)
        if (result !is MoveResult.Moved) return

        syncFromSession()
        val pushed = result.pushedBox
        if (pushed != null) {
            pushId += 1
            lastPush = PushAnim(pushed.first, pushed.second, pushId)
        }
        animationJob = scope.launch {
            delay(MOVE_ANIMATION_MS)
            val queued = queuedDirection
            queuedDirection = null
            if (queued != null) performMove(queued)
        }
    }

    fun undo() {
        if (animationJob?.isActive == true) return
        queuedDirection = null
        if (!session.undo()) return
        syncFromSession()
        lastPush = null // boxes snap back to their logical cells
    }

    fun restart() {
        animationJob?.cancel()
        animationJob = null
        queuedDirection = null
        session.restart()
        syncFromSession()
        lastPush = null
        resetEpoch += 1 // renderer snaps instead of animating across the board
    }

    private fun syncFromSession() {
        state = session.state
        isComplete = session.isComplete
        canUndo = session.canUndo
    }

    companion object {
        const val MOVE_ANIMATION_MS = 110L
    }
}
