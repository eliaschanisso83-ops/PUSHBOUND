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
 * The logical move the renderer should animate right now: which visual kind
 * (walk timeline, push hold, or a plain transition glide) and a monotonic id
 * so each transition can restart its own timeline exactly once.
 */
data class MoveAnim(val kind: MoveAnimKind, val id: Long)

/**
 * One blocked-push effort reaction: the engine refused the move (no state
 * change, facing untouched), but the character visibly leans into the
 * ATTEMPTED direction and releases — purely visual, driven by a fresh id so
 * repeated presses replay the pulse.
 */
data class BlockedAnim(val direction: Direction, val id: Long)

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
    var currentMove by mutableStateOf<MoveAnim?>(null)
        private set
    var lastBlocked by mutableStateOf<BlockedAnim?>(null)
        private set
    var resetEpoch by mutableStateOf(0)
        private set

    private var animationJob: Job? = null
    private var queuedDirection: Direction? = null
    private var pushId = 0L
    private var moveId = 0L
    private var blockedId = 0L

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
        if (result is MoveResult.Blocked) {
            // Refused input: no state change at all, but the character puts
            // in a brief visual effort in the ATTEMPTED direction.
            blockedId += 1
            lastBlocked = BlockedAnim(direction, blockedId)
            return
        }
        if (result !is MoveResult.Moved) return

        syncFromSession()
        lastBlocked = null // a real move supersedes any pending effort pulse
        val pushed = result.pushedBox
        moveId += 1
        currentMove = MoveAnim(
            kind = if (pushed != null) MoveAnimKind.PUSH else MoveAnimKind.STEP,
            id = moveId
        )
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
        moveId += 1
        currentMove = MoveAnim(MoveAnimKind.TRANSITION, moveId) // glide, stays in IDLE
        syncFromSession()
        lastPush = null // boxes snap back to their logical cells
        lastBlocked = null // cancel any pending effort pulse
    }

    fun restart() {
        animationJob?.cancel()
        animationJob = null
        queuedDirection = null
        session.restart()
        syncFromSession()
        lastPush = null
        lastBlocked = null
        currentMove = null
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
