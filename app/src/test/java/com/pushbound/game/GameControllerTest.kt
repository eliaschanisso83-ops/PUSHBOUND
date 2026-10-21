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

    /**
     * Waits until a controller signal actually shows up, up to three seconds.
     *
     * THE PROBLEM THIS SOLVES: the controller starts a 110ms animation and
     * QUEUES any input that arrives while one is running. A fixed sleep can
     * expire before that animation has been scheduled through, so the press
     * under test is queued instead of performed — and the signal it should have
     * produced is simply absent when the assertion runs. On a quiet machine
     * 170ms was always enough, which is exactly why the race was invisible: it
     * only surfaced once the suite grew and the device was busy.
     *
     * Polling for the signal itself removes the race completely: a queued press
     * still executes moments later, and a genuinely broken signal fails within
     * a bounded time instead of hanging. Sleeping the JUnit thread is safe
     * because the controller's work runs on [Dispatchers.Default].
     */
    private fun awaitSignal(what: String, poll: () -> Any?) {
        val deadline = System.currentTimeMillis() + 3_000L
        while (poll() == null) {
            assertTrue(
                "$what: never appeared within 3s",
                System.currentTimeMillis() < deadline
            )
            Thread.sleep(5)
        }
    }

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
        awaitSignal("the blocked-effort pulse") { controller.lastBlocked }
        val blocked = controller.lastBlocked
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
        awaitSignal("the blocked-effort pulse") { controller.lastBlocked }
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
        awaitSignal("the blocked-effort pulse") { controller.lastBlocked }
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
        // The queued press only lands once the running animation releases the
        // input, so wait for the position it must reach rather than for a
        // fixed slice of wall clock.
        awaitSignal("the queued input") { controller.state.player.takeIf { it == Position(2, 2) } }
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
        // If the window had not closed yet this press would be queued and the
        // old id would still be showing, so wait for the id that can only come
        // from the second step having actually run.
        awaitSignal("the second step") { controller.currentMove?.takeIf { it.id > first } }
        val second = controller.currentMove!!.id
        assertTrue("ids must increase", second > first)
    }
}
