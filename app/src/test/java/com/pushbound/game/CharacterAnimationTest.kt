package com.pushbound.game

import com.pushbound.game.engine.Direction
import com.pushbound.game.ui.CharacterAnimation
import com.pushbound.game.ui.CharacterPose
import com.pushbound.game.ui.MoveAnimKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Character animation invariants (pure timing/pose math): walk timeline,
 * push/box synchronization, blocked-push envelope, idle breathing, and the
 * guarantee that undo/restore transitions and move arrivals always settle
 * on IDLE (never stuck in a stale animation).
 */
class CharacterAnimationTest {

    private val eps = 0.0001f

    // ------------------------------------------------------ walk timeline --

    @Test
    fun walkCycleFollowsStrideAStrideBThenSettle() {
        assertEquals(CharacterPose.STRIDE_A, CharacterAnimation.movePose(0.0f, MoveAnimKind.STEP))
        assertEquals(CharacterPose.STRIDE_A, CharacterAnimation.movePose(0.20f, MoveAnimKind.STEP))
        assertEquals(CharacterPose.STRIDE_B, CharacterAnimation.movePose(0.40f, MoveAnimKind.STEP))
        assertEquals(CharacterPose.STRIDE_B, CharacterAnimation.movePose(0.60f, MoveAnimKind.STEP))
        assertEquals(CharacterPose.IDLE, CharacterAnimation.movePose(0.70f, MoveAnimKind.STEP))
        assertEquals(CharacterPose.IDLE, CharacterAnimation.movePose(1.0f, MoveAnimKind.STEP))
    }

    @Test
    fun walkPhaseBoundariesAreExactAndContiguous() {
        assertTrue(CharacterAnimation.WALK_PHASE_A_END < CharacterAnimation.WALK_PHASE_B_END)
        assertEquals(
            CharacterPose.STRIDE_A,
            CharacterAnimation.movePose(CharacterAnimation.WALK_PHASE_A_END - 0.001f, MoveAnimKind.STEP)
        )
        assertEquals(
            CharacterPose.STRIDE_B,
            CharacterAnimation.movePose(CharacterAnimation.WALK_PHASE_A_END, MoveAnimKind.STEP)
        )
        assertEquals(
            CharacterPose.STRIDE_B,
            CharacterAnimation.movePose(CharacterAnimation.WALK_PHASE_B_END - 0.001f, MoveAnimKind.STEP)
        )
        assertEquals(
            CharacterPose.IDLE,
            CharacterAnimation.movePose(CharacterAnimation.WALK_PHASE_B_END, MoveAnimKind.STEP)
        )
    }

    @Test
    fun progressIsClampedNotWrapped() {
        assertEquals(CharacterPose.STRIDE_A, CharacterAnimation.movePose(-3f, MoveAnimKind.STEP))
        assertEquals(CharacterPose.IDLE, CharacterAnimation.movePose(7f, MoveAnimKind.STEP))
        assertEquals(CharacterPose.IDLE, CharacterAnimation.movePose(7f, MoveAnimKind.PUSH))
    }

    // ------------------------------------------------------------ walk bob --

    @Test
    fun walkBobIsZeroAtContactsAndArrivalAndNeverExceedsPeak() {
        assertEquals(0f, CharacterAnimation.walkBob(0f), eps)
        assertEquals(0f, CharacterAnimation.walkBob(CharacterAnimation.WALK_PHASE_A_END), eps)
        assertEquals(0f, CharacterAnimation.walkBob(CharacterAnimation.WALK_PHASE_B_END), eps)
        assertEquals(0f, CharacterAnimation.walkBob(1f), eps)
        // settle phase stays grounded (no extra bounce on arrival)
        assertEquals(0f, CharacterAnimation.walkBob(0.85f), eps)
        var max = 0f
        for (i in 0..200) {
            val t = i / 200f
            val bob = CharacterAnimation.walkBob(t)
            assertTrue("bob must be lift-only", bob >= 0f)
            if (bob > max) max = bob
        }
        assertTrue(max <= CharacterAnimation.WALK_BOB + eps)
        assertTrue("bob must actually move", max > 0.005f)
    }

    @Test
    fun walkBobLiftsMidStridePhase() {
        val midA = CharacterAnimation.WALK_PHASE_A_END / 2f
        val midB = (CharacterAnimation.WALK_PHASE_A_END + CharacterAnimation.WALK_PHASE_B_END) / 2f
        assertTrue(CharacterAnimation.walkBob(midA) > CharacterAnimation.WALK_BOB * 0.9f)
        assertTrue(CharacterAnimation.walkBob(midB) > CharacterAnimation.WALK_BOB * 0.9f)
    }

    // -------------------------------------------------------- push / box ---

    @Test
    fun pushHoldsItsLeanForTheWholeMove() {
        for (i in 0..99) {
            val t = i / 100f
            assertEquals(CharacterPose.PUSH, CharacterAnimation.movePose(t, MoveAnimKind.PUSH))
        }
        assertEquals(CharacterPose.IDLE, CharacterAnimation.movePose(1f, MoveAnimKind.PUSH))
    }

    @Test
    fun boxStartsAfterContactAndFinishesWithTheCharacter() {
        // Any move duration: the delay/duration split is the invariant, the
        // renderer feeds it the app's real MOVE_ANIMATION_MS at wiring time.
        val moveMs = 110L
        val delay = CharacterAnimation.boxStartDelayMs(moveMs)
        val duration = CharacterAnimation.boxDurationMs(moveMs)
        assertTrue("box must not move before contact", delay > 0L)
        assertTrue(duration > 0L)
        assertEquals(moveMs, delay + duration)
        assertEquals(
            (moveMs * CharacterAnimation.PUSH_BOX_DELAY).toLong(),
            delay
        )
    }

    // ------------------------------------------------------------- blocked --

    @Test
    fun blockedPulseAttacksAndReleasesWithinItsWindow() {
        assertEquals(0f, CharacterAnimation.blockedPulse(-1L), eps)
        assertEquals(0f, CharacterAnimation.blockedPulse(0L), eps)
        assertEquals(0f, CharacterAnimation.blockedPulse(CharacterAnimation.BLOCKED_PULSE_MS), eps)
        assertEquals(0f, CharacterAnimation.blockedPulse(9999L), eps)
        assertEquals(1f, CharacterAnimation.blockedPulse(CharacterAnimation.BLOCKED_ATTACK_MS), eps)
        // monotonic attack
        assertTrue(CharacterAnimation.blockedPulse(20L) < CharacterAnimation.blockedPulse(40L))
        // monotonic release
        assertTrue(CharacterAnimation.blockedPulse(60L) > CharacterAnimation.blockedPulse(120L))
        // peak offset stays extremely subtle: <= 1.4% of a cell
        assertTrue(CharacterAnimation.blockedOffset(45L) <= CharacterAnimation.BLOCKED_OFFSET + eps)
    }

    @Test
    fun blockedLeanPointsForwardAlongFacing() {
        val right = CharacterAnimation.forwardCells(Direction.RIGHT, 0.01f)
        assertEquals(0.01f, right.col, eps)
        assertEquals(0f, right.row, eps)
        val up = CharacterAnimation.forwardCells(Direction.UP, 0.01f)
        assertEquals(0f, up.col, eps)
        assertEquals(-0.01f, up.row, eps)
        val down = CharacterAnimation.forwardCells(Direction.DOWN, 0.01f)
        assertEquals(0.01f, down.row, eps)
        val left = CharacterAnimation.forwardCells(Direction.LEFT, 0.01f)
        assertEquals(-0.01f, left.col, eps)
    }

    // --------------------------------------------------------------- idle --

    @Test
    fun idleBreathIsACalmBoundedCycle() {
        assertEquals(0f, CharacterAnimation.idleBreath(0L), eps)
        // half period: zero crossing
        assertEquals(0f, CharacterAnimation.idleBreath(CharacterAnimation.IDLE_PERIOD_MS / 2), 0.0005f)
        // full period repeats
        assertEquals(
            CharacterAnimation.idleBreath(300L),
            CharacterAnimation.idleBreath(300L + CharacterAnimation.IDLE_PERIOD_MS),
            eps
        )
        var maxAbs = 0f
        for (i in 0..1000) {
            val v = kotlin.math.abs(CharacterAnimation.idleBreath(i * 5L))
            if (v > maxAbs) maxAbs = v
        }
        assertTrue(maxAbs <= CharacterAnimation.IDLE_BREATH + eps)
        assertTrue("breath must be visible but tiny", maxAbs > 0.003f)
    }

    // -------------------------------------------- undo / restart / settle --

    @Test
    fun undoTransitionNeverWalksOrPushes() {
        for (i in 0..100) {
            val t = i / 100f
            assertEquals(
                CharacterPose.IDLE,
                CharacterAnimation.movePose(t, MoveAnimKind.TRANSITION)
            )
        }
    }

    @Test
    fun everyMoveKindSettlesToIdleOnArrival() {
        assertEquals(CharacterPose.IDLE, CharacterAnimation.movePose(1f, MoveAnimKind.STEP))
        assertEquals(CharacterPose.IDLE, CharacterAnimation.movePose(1f, MoveAnimKind.PUSH))
        assertEquals(CharacterPose.IDLE, CharacterAnimation.movePose(1f, MoveAnimKind.TRANSITION))
    }

    @Test
    fun walkAndPushNeverMixTimelines() {
        // A progress that is a stride phase in STEP must not leak into PUSH
        assertEquals(CharacterPose.STRIDE_A, CharacterAnimation.movePose(0.1f, MoveAnimKind.STEP))
        assertEquals(CharacterPose.PUSH, CharacterAnimation.movePose(0.1f, MoveAnimKind.PUSH))
        assertEquals(CharacterPose.IDLE, CharacterAnimation.movePose(0.1f, MoveAnimKind.TRANSITION))
    }
}
