package com.pushbound.game

import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.Position
import com.pushbound.game.ui.GameController
import com.pushbound.game.ui.MoveAnimKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Character Animation System, Stage 2: the controller must classify every
 * accepted move (walk vs push), expose the blocked-effort signal without any
 * engine change, and cancel every animation signal on undo/restart. The
 * engine stays the single source of puzzle truth — these tests only pin the
 * renderer-facing signals.
 */
class GameControllerTest {

    private val scope = CoroutineScope(Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun level(vararg rows: String) = LevelDefinition.fromAscii("controller", rows.toList())

    private val openRoom = level("#####", "#   #", "# P #", "#####")
    private val boxAhead = level("#####", "#PB #", "#   #", "#####")
    private val wallAhead = level("#####", "#P# #", "#   #", "#####")
    private val deadEnd = level("#####", "#PB #", "#####")

    /** Waits out the 110ms animation window so the next input is accepted. */
    private fun pastAnimation() = runBlocking { delay(GameController.MOVE_ANIMATION_MS + 60L) }

    @Test
    fun stepMoveExposesTheWalkKindAndMovesOneCell() {
        val controller = GameController(openRoom, scope)
        controller.onDirectionPressed(Direction.UP)
        assertEquals(Position(1, 2), controller.state.player)
        val move = controller.currentMove
        assertNotNull("a step must expose a move signal", move)
        assertEquals(MoveAnimKind.STEP, move!!.kind)
        assertNull("a step must not animate a box", controller.lastPush)
        assertNull(controller.lastBlocked)
    }

    @Test
    fun pushMoveExposesThePushKindAndTheBoxTweenCells() {
        val controller = GameController(boxAhead, scope)
        controller.onDirectionPressed(Direction.RIGHT)
        assertEquals(Position(1, 2), controller.state.player)
        assertEquals(setOf(Position(1, 3)), controller.state.boxes)
        assertEquals(MoveAnimKind.PUSH, controller.currentMove!!.kind)
        val push = controller.lastPush
        assertNotNull("a push must expose the box tween", push)
        assertEquals(Position(1, 2), push!!.from) // box origin cell
        assertEquals(Position(1, 3), push.to)     // box destination cell
        assertEquals(Direction.RIGHT, controller.state.facing)
    }

    @Test
    fun blockedMoveSignalsEffortWithoutAnyStateChange() {
        val controller = GameController(wallAhead, scope)
        val before = controller.state
        controller.onDirectionPressed(Direction.RIGHT)
        assertEquals("blocked moves never change the state", before, controller.state)
        assertNull("blocked moves never start a move animation", controller.currentMove)
        assertNull("blocked pushes never move the box", controller.lastPush)
        val blocked = controller.lastBlocked
        assertNotNull("a refused push must expose the effort pulse", blocked)
        assertEquals("the lean follows the ATTEMPTED direction", Direction.RIGHT, blocked!!.direction)
        assertEquals("each pulse gets a fresh id", 1L, blocked.id)
    }

    @Test
    fun blockedDirectionIsTheAttemptedOneEvenWhenFacingDiffers() {
        val controller = GameController(deadEnd, scope)
        controller.onDirectionPressed(Direction.RIGHT) // push: facing becomes RIGHT
        assertEquals(Direction.RIGHT, controller.state.facing)
        pastAnimation()
        controller.onDirectionPressed(Direction.UP) // blocked by the wall
        val blocked = controller.lastBlocked
        assertNotNull(blocked)
        assertEquals(Direction.UP, blocked!!.direction)     // effort leans with the input
        assertEquals(Direction.RIGHT, controller.state.facing) // facing untouched
    }

    @Test
    fun aRealMoveSupersedesAPendingEffortPulse() {
        val controller = GameController(wallAhead, scope)
        controller.onDirectionPressed(Direction.RIGHT) // blocked: pulse pending
        assertNotNull(controller.lastBlocked)
        controller.onDirectionPressed(Direction.DOWN) // accepted step
        assertNull("the walk signal must not inherit the pulse", controller.lastBlocked)
        assertEquals(MoveAnimKind.STEP, controller.currentMove!!.kind)
    }

    @Test
    fun undoGlidesAsATransitionAndClearsEverySignal() {
        val controller = GameController(boxAhead, scope)
        controller.onDirectionPressed(Direction.RIGHT) // push
        assertNotNull(controller.lastPush)
        pastAnimation()
        controller.onDirectionPressed(Direction.RIGHT) // blocked, then undo
        assertNotNull(controller.lastBlocked)
        pastAnimation()
        controller.undo()
        assertEquals("undo must restart the timeline as a plain glide",
            MoveAnimKind.TRANSITION, controller.currentMove!!.kind)
        assertNull("undo snaps boxes back with no tween", controller.lastPush)
        assertNull("undo cancels any effort pulse", controller.lastBlocked)
        assertEquals(boxAhead.playerStart, controller.state.player)
        assertEquals("undo never bumps the snap epoch", 0, controller.resetEpoch)
    }

    @Test
    fun restartResetsEveryAnimationSignalAndBumpsTheEpoch() {
        val controller = GameController(boxAhead, scope)
        controller.onDirectionPressed(Direction.RIGHT)
        pastAnimation()
        controller.onDirectionPressed(Direction.RIGHT) // blocked pulse pending
        assertNotNull(controller.lastBlocked)
        val epochBefore = controller.resetEpoch
        controller.restart()
        assertNull(controller.currentMove)
        assertNull(controller.lastPush)
        assertNull(controller.lastBlocked)
        assertEquals(epochBefore + 1, controller.resetEpoch)
        assertEquals(boxAhead.playerStart, controller.state.player)
        assertEquals(setOf(Position(1, 2)), controller.state.boxes)
    }

    @Test
    fun queuedInputChainsAsAFreshMoveAfterTheAnimationWindow() {
        val controller = GameController(openRoom, scope)
        controller.onDirectionPressed(Direction.UP)
        controller.onDirectionPressed(Direction.DOWN) // queued: exactly one input
        pastAnimation()
        assertEquals("the queued input must have executed", Position(2, 2), controller.state.player)
        assertEquals("the queued step restarts the timeline", 2, controller.currentMove!!.id)
        assertEquals(MoveAnimKind.STEP, controller.currentMove!!.kind)
    }

    @Test
    fun moveIdsAreMonotonicSoEveryMoveRestartsItsTimelineOnce() {
        val controller = GameController(openRoom, scope)
        controller.onDirectionPressed(Direction.UP)
        val first = controller.currentMove!!.id
        pastAnimation()
        controller.onDirectionPressed(Direction.DOWN)
        val second = controller.currentMove!!.id
        assertTrue("ids must increase", second > first)
    }
}
