package com.pushbound.game.ui

import com.pushbound.game.engine.Direction
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure timing and pose math for the character animation system.
 * No Android/Compose imports — every value here is unit-testable on the JVM
 * (CharacterAnimationTest), following the BoardLayout/ControlLayout pattern.
 *
 * The system is purely visual: it never feeds back into Sokoban rules. The
 * logical game stays grid-based; this file only answers "which pose does the
 * renderer show at move progress t, and by how much does it breathe/bob".
 *
 * Frame manifest consumed by the renderer (approved artwork, Stage 2 wiring):
 *   IDLE       -> existing character_idle{,_up,_left,_right} frames
 *   STRIDE_A/B -> character_walk_{down,up,left,right}_{a,b}
 *   PUSH       -> character_push_{down,up,left,right}
 * BLOCKED_PUSH feedback reuses the PUSH frame plus [blockedOffset] — no
 * extra artwork, no game-state change.
 */
enum class CharacterPose { IDLE, STRIDE_A, STRIDE_B, PUSH }

/** What kind of logical action the current cell transition came from. */
enum class MoveAnimKind {
    /** Directional input step: walk cycle. */
    STEP,

    /** Directional input step that pushed a box: push pose, delayed box. */
    PUSH,

    /** Undo/restore glide (and any other non-input repositioning): idle. */
    TRANSITION
}

/** A small offset in cell fractions (col right positive, row down positive). */
data class CellOffset(val col: Float, val row: Float)

object CharacterAnimation {

    // ---------------------------------------------------------- walk -----
    // One cell move = STRIDE_A (legs one way) -> STRIDE_B (legs the other
    // way) -> settle onto IDLE for the arrival, so the character ends the
    // move in the exact pose it will stand in (no pose pop at arrival).
    // Fractions of the cell-move timeline (GameController.MOVE_ANIMATION_MS).
    const val WALK_PHASE_A_END = 0.37f
    const val WALK_PHASE_B_END = 0.66f

    // Peak vertical bob per stride phase, as a fraction of the cell size.
    // Subtle: ~1.2% of a cell (about 1.2px at a 105px cell). The curve is
    // sin over each phase, so it starts/ends at 0 (feet grounded at contact)
    // and lifts slightly through passing — no bounce, no landing thump.
    const val WALK_BOB = 0.012f

    // ---------------------------------------------------------- push -----
    // The box stays put while the character closes/leans (contact first),
    // then travels and arrives together with the character. Fraction of the
    // move during which the box has not started yet.
    const val PUSH_BOX_DELAY = 0.30f

    // -------------------------------------------------------- blocked ----
    // Envelope of the blocked-push reaction: quick effort, brief hold,
    // release back to idle. Extremely subtle forward lean (1.4% of a cell).
    const val BLOCKED_PULSE_MS = 140L
    const val BLOCKED_ATTACK_MS = 45L
    const val BLOCKED_OFFSET = 0.014f

    // ---------------------------------------------------------- idle -----
    // Almost imperceptible breathing: +/-0.6% of a cell, one calm cycle.
    const val IDLE_PERIOD_MS = 2400L
    const val IDLE_BREATH = 0.006f

    fun clamp01(value: Float): Float = when {
        value < 0f -> 0f
        value > 1f -> 1f
        else -> value
    }

    /**
     * Pose to render at [progress] (0..1) of the current cell transition.
     * TRANSITION (undo/restore) never leaves IDLE; PUSH holds its lean for
     * the whole move; STEP walks the A -> B -> settle timeline. At progress
     * 1 (and outside a move) the answer is always IDLE, so nothing can be
     * left standing in an old animation.
     */
    fun movePose(progress: Float, kind: MoveAnimKind): CharacterPose {
        val p = clamp01(progress)
        return when (kind) {
            MoveAnimKind.TRANSITION -> CharacterPose.IDLE
            MoveAnimKind.PUSH -> if (p >= 1f) CharacterPose.IDLE else CharacterPose.PUSH
            MoveAnimKind.STEP -> when {
                p >= 1f -> CharacterPose.IDLE
                p < WALK_PHASE_A_END -> CharacterPose.STRIDE_A
                p < WALK_PHASE_B_END -> CharacterPose.STRIDE_B
                else -> CharacterPose.IDLE
            }
        }
    }

    /**
     * Vertical torso bob for a walking move, in cell fractions, positive =
     * lifted off the baseline. sin over each stride phase: zero at every
     * contact (phase boundaries and arrival), peak mid-phase. Zero during
     * the settle phase and for every non-walk kind.
     */
    fun walkBob(progress: Float): Float {
        val p = clamp01(progress)
        val phase = when {
            p < WALK_PHASE_A_END -> p / WALK_PHASE_A_END
            p < WALK_PHASE_B_END -> (p - WALK_PHASE_A_END) / (WALK_PHASE_B_END - WALK_PHASE_A_END)
            else -> return 0f // settle: grounded arrival, no extra bounce
        }
        return WALK_BOB * sin(PI * phase).toFloat()
    }

    /** Box tween start delay inside one push move, ms. */
    fun boxStartDelayMs(moveMs: Long): Long = (moveMs * PUSH_BOX_DELAY).toLong()

    /** Box tween duration: starts delayed, finishes with the character. */
    fun boxDurationMs(moveMs: Long): Long = moveMs - boxStartDelayMs(moveMs)

    /**
     * Blocked-push effort envelope over time: 0 -> 1 (attack) -> 0 (release).
     * Returns 0 outside [0, BLOCKED_PULSE_MS). No game state is involved.
     */
    fun blockedPulse(elapsedMs: Long): Float {
        if (elapsedMs < 0L || elapsedMs >= BLOCKED_PULSE_MS) return 0f
        return if (elapsedMs < BLOCKED_ATTACK_MS) {
            elapsedMs.toFloat() / BLOCKED_ATTACK_MS
        } else {
            1f - (elapsedMs - BLOCKED_ATTACK_MS).toFloat() / (BLOCKED_PULSE_MS - BLOCKED_ATTACK_MS)
        }
    }

    /** Forward lean offset (cell fractions) for a blocked push at [elapsedMs]. */
    fun blockedOffset(elapsedMs: Long): Float = blockedPulse(elapsedMs) * BLOCKED_OFFSET

    /**
     * Forward direction of a lean/push in cell fractions: how many cells
     * (fractional, can be negative on an axis) the reaction offset points.
     */
    fun forwardCells(direction: Direction, cells: Float): CellOffset =
        CellOffset(col = direction.colDelta * cells, row = direction.rowDelta * cells)

    /**
     * Idle breathing offset (cell fractions) at a settled timestamp.
     * One calm 2.4s cycle, +/- [IDLE_BREATH]; renderer applies it only while
     * the character is actually settled (not during moves).
     */
    fun idleBreath(elapsedMs: Long): Float =
        (IDLE_BREATH * sin(2.0 * PI * elapsedMs / IDLE_PERIOD_MS)).toFloat()
}
