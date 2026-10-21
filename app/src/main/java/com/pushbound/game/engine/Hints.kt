package com.pushbound.game.engine

import com.pushbound.game.engine.solver.LevelSolver
import com.pushbound.game.engine.solver.Solution

/**
 * Optional, escalating help for one level.
 *
 * THREE TIERS, deliberately ordered from COARSE to EXACT:
 *
 *  1. [Tier.INDICATE_DIRECTION] — which way progress lies: the direction of the
 *     first PUSH in the best continuation, i.e. the direction a box has to
 *     travel. Deliberately the vaguest hint: it tells a lost player which part
 *     of the board to think about without telling them what to do.
 *  2. [Tier.IDENTIFY_BLOCK] — WHICH box is the one that matters right now, and
 *     which way it needs to go. On a multi-box board this is usually the real
 *     insight, because picking the wrong box is what wastes attempts.
 *  3. [Tier.REVEAL_NEXT_MOVE] — the single exact next move.
 *
 * WHAT A HINT NEVER DOES — it never solves the puzzle. Tier 3 reveals ONE move;
 * after taking it the player is on their own again, and asking repeatedly gives
 * no better a reward than asking once did. The hint is an assist at the point of
 * frustration, not a walkthrough.
 *
 * ALL THREE ARE DERIVED FROM THE CURRENT STATE, not from the level's cached
 * solution. A player who has wandered off the optimal line would be handed a
 * step that no longer applies, so the continuation is recomputed from where they
 * actually stand ([LevelSolver.solveFrom]).
 *
 * COST — solving runs on demand at the moment a hint is requested, never per
 * move, under its own smaller node budget so the UI stays responsive. If the
 * budget runs out the hint is withheld (null) rather than guessed: a wrong hint
 * is worse than none.
 */
object Hints {

    /** Number of tiers available to the player. */
    const val TIERS = 3

    /**
     * Search budget for one hint, smaller than the validation budget because
     * this runs while the player is waiting on a tap.
     */
    const val MAX_NODES = 150_000

    enum class Tier {
        INDICATE_DIRECTION,
        IDENTIFY_BLOCK,
        REVEAL_NEXT_MOVE
    }

    /** One step of the best continuation, annotated with whether it pushes. */
    private data class AnnotatedMove(val direction: Direction, val pushes: Boolean)

    sealed interface Hint {
        val tier: Tier

        /** Player-facing explanation. */
        val message: String

        /** Tier 1: the direction in which progress lies. */
        data class DirectionHint(val direction: Direction, val isPush: Boolean) : Hint {
            override val tier: Tier get() = Tier.INDICATE_DIRECTION
            override val message: String
                get() = if (isPush) {
                    "Try pushing toward the ${direction.label}."
                } else {
                    "Head ${direction.label} — that is where the solution begins."
                }
        }

        /** Tier 2: which block matters, and the direction it must travel. */
        data class BlockHint(val block: Position, val toward: Direction) : Hint {
            override val tier: Tier get() = Tier.IDENTIFY_BLOCK
            override val message: String
                get() = "This block is the one to move — it needs to travel ${toward.label}."
        }

        /** Tier 3: the exact next move. */
        data class NextMoveHint(val direction: Direction, val isPush: Boolean) : Hint {
            override val tier: Tier get() = Tier.REVEAL_NEXT_MOVE
            override val message: String
                get() = if (isPush) {
                    "Push the block ${direction.label}."
                } else {
                    "Move ${direction.label}."
                }
        }
    }

    /**
     * The hint for [tier], given where the player actually is.
     *
     * @return the hint, or null when none can be produced honestly — the level
     *   is already solved, the position is unsolvable (the player wedged a box
     *   into a corner), or the search budget ran out.
     */
    fun hintFor(level: LevelDefinition, state: GameState, tier: Tier): Hint? {
        if (GameEngine.isComplete(level, state)) return null

        val solution: Solution =
            LevelSolver.solveFrom(level, state, MAX_NODES).solution ?: return null
        val continuation = annotate(level, state, solution)
        if (continuation.isEmpty()) return null

        val firstPush = continuation.firstOrNull { it.pushes }
        val first = continuation.first()

        return when (tier) {
            Tier.INDICATE_DIRECTION -> {
                // Prefer the direction of the first push: it describes where the
                // puzzle is going rather than where the player happens to stand.
                val useful = firstPush ?: first
                Hint.DirectionHint(direction = useful.direction, isPush = useful.pushes)
            }

            Tier.IDENTIFY_BLOCK -> {
                // The block moved by the first push is the one the player must
                // care about. Its position NOW is what to point at.
                val push = firstPush ?: return null
                val origin = blockAfterPush(level, state, continuation, push) ?: return null
                Hint.BlockHint(block = origin, toward = push.direction)
            }

            Tier.REVEAL_NEXT_MOVE -> Hint.NextMoveHint(
                direction = first.direction,
                isPush = first.pushes
            )
        }
    }

    /** Convenience: the first available hint tier, then the next, and so on. */
    fun hintFor(level: LevelDefinition, state: GameState, tierIndex: Int): Hint? {
        val tier = Tier.entries.getOrNull(tierIndex.coerceIn(0, Tier.entries.lastIndex)) ?: return null
        return hintFor(level, state, tier)
    }

    /**
     * Replays [solution] from [from], recording for each step whether it pushed.
     *
     * Replay runs through the real engine, so the annotation can never disagree
     * with what the player would actually see. A step the engine refuses means
     * the solver and the engine have diverged — reported as an empty list so the
     * caller withholds the hint rather than showing a wrong one.
     */
    private fun annotate(level: LevelDefinition, from: GameState, solution: Solution): List<AnnotatedMove> {
        val annotated = ArrayList<AnnotatedMove>(solution.moves.size)
        var state = from

        for (direction in solution.moves) {
            val result = GameEngine.move(level, state, direction)
            if (result !is MoveResult.Moved) return emptyList()
            annotated += AnnotatedMove(direction = direction, pushes = result.pushedBox != null)
            state = result.state
        }
        return annotated
    }

    /**
     * The cell a box occupied BEFORE the identified push — the cell the player
     * should be looking at, since that is the block in front of them now.
     *
     * Walks the continuation from [from] until the annotated [push] step, then
     * reports the pushed box's origin.
     */
    private fun blockAfterPush(
        level: LevelDefinition,
        from: GameState,
        continuation: List<AnnotatedMove>,
        push: AnnotatedMove
    ): Position? {
        var state = from
        for (step in continuation) {
            val result = GameEngine.move(level, state, step.direction)
            if (result !is MoveResult.Moved) return null
            if (step === push) return result.pushedBox?.first
            state = result.state
        }
        return null
    }
}
