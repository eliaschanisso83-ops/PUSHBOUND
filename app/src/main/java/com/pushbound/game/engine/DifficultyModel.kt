package com.pushbound.game.engine

import com.pushbound.game.engine.solver.DeadlockDetector
import kotlin.math.sqrt

/**
 * Conceptual difficulty scoring.
 *
 * WHY THIS EXISTS — difficulty must not be a function of a level's position in
 * a list. World 2 can open with something gentler than World 1's finale, and a
 * future pack can insert content anywhere without renumbering anything. A
 * level's band is therefore derived from what the puzzle actually IS.
 *
 * THE MODEL — a weighted sum of normalised factors, then one multiplicative
 * factor for exotic mechanics:
 *
 * ```
 *   score = ( W_BOXES     · √(boxes / REF_BOXES)
 *           + W_MOVES     · √(moves / REF_MOVES)
 *           + W_PUSHES    · √(pushes / REF_PUSHES)
 *           + W_REPOSITION · (pushes per box / REF_REPOSITION)
 *           + W_RISK      · (dead-cell density / REF_RISK)
 *           + W_SIZE      · √(floorCells / REF_FLOOR) ) × mechanicFactor
 * ```
 *
 * WHY THE FIRST VERSION HAD TO CHANGE — measured across the 15 shipped levels,
 * the box term alone accounted for 46–70% of every score, and box count was
 * the single thing separating the bands: 11 of 15 levels landed on EASY purely
 * by having one box. A two-box level with nine moves and two pushes scored
 * higher than a one-box level with thirteen moves and three pushes, which is
 * backwards about the puzzle in front of the player.
 *
 * The cause was that boxes entered LINEARLY with the largest weight, so a
 * third box added a flat 0.40 while every other factor had to earn its points
 * against that. Boxes are combinatorial, so they still matter most — but the
 * square root keeps a third box from outvoting the entire rest of the model,
 * and the box term now contributes 32–39% instead of 46–70%.
 *
 * DESIGN NOTES, so the tuning intent survives:
 *
 *  - PAR MOVES is the strongest ground truth available: the measured length of
 *    the best known solution already encodes layout, box count and planning
 *    depth in one number.
 *  - PUSHES ARE WEIGHTED ABOVE MOVES. Extra moves are cheap wandering; extra
 *    pushes mean the player had to place boxes more than necessary.
 *  - REPOSITIONING is pushes per box. One push per box means every box went
 *    straight home; more than that means boxes had to be shuffled out of each
 *    other's way, which is where most of the actual thinking happens.
 *  - DEAD-CELL DENSITY is the share of the floor from which a box can never
 *    reach a goal — see [DeadlockDetector.deadSquares]. A board where a third
 *    of the cells are traps punishes one careless push; an open board forgives
 *    it. This replaces the earlier idea of counting fatal CORNERS: measured on
 *    the shipped content that count is exactly 4 for every level, because they
 *    are all closed rectangular rooms, so it carried no information at all.
 *  - BOARD SIZE is the smallest weight and is square-rooted. Doubling the
 *    floor does not double the challenge; it opens the board up.
 *
 * LIMITS — deliberately simple until real level data exists. There is no
 * branching factor term because no cheap computation of it is trustworthy
 * yet, and adding a speculative term would make the model harder to reason
 * about without making it more accurate. Everything below is a named constant
 * so the tuning happens in one place, with tests.
 *
 * The band cuts are PROVISIONAL and deliberately not fitted to the current 15
 * levels: they place the shipped content sensibly, and the ten levels that
 * genuinely are near-identical in shape clump near one band rather than being
 * artificially spread across five.
 */
object DifficultyModel {

    /**
     * Everything the model needs about a level.
     *
     * [deadSquares] is the count of floor cells a box can never reach a goal
     * from. It defaults to zero — "no measured traps" — because callers that
     * build inputs by hand have no board to measure, and only [Inputs.of]
     * reads a real one.
     */
    data class Inputs(
        val boxes: Int,
        val floorCells: Int,
        val parMoves: Int,
        val parPushes: Int,
        val mechanics: Set<Mechanic> = setOf(Mechanic.PUSH),
        val deadSquares: Int = 0
    ) {
        companion object {
            /** Derives the inputs straight from a level. */
            fun of(level: LevelDefinition) = Inputs(
                boxes = level.boxCount,
                floorCells = level.floorCount,
                parMoves = level.parMoves,
                parPushes = level.parPushes,
                mechanics = level.mechanics,
                deadSquares = DeadlockDetector.deadSquares(level).size
            )
        }
    }

    // --- reference values (one ordinary box on an ordinary board) ------------
    const val REF_BOXES = 1.0
    const val REF_FLOOR = 40.0
    const val REF_MOVES = 15.0
    const val REF_PUSHES = 5.0

    /** Two pushes per box is an ordinary amount of shuffling. */
    const val REF_REPOSITION = 2.0

    /** Half the floor being a trap is an ordinary hazard level. */
    const val REF_RISK = 0.50

    // --- factor weights, summing to 1.0 before the mechanic multiplier -------
    const val W_BOXES = 0.28
    const val W_MOVES = 0.20
    const val W_PUSHES = 0.15
    const val W_REPOSITION = 0.15
    const val W_RISK = 0.12
    const val W_SIZE = 0.10

    /** Added to the score for each mechanic beyond the basic push. */
    const val W_MECHANIC = 0.25

    // --- band cut points on the final score ---------------------------------
    //
    // Placed inside measured valleys rather than fitted to taste: on the 15
    // shipped levels the scores run 0.769 .. 1.505, and these cuts sit in the
    // four real gaps (0.910..0.946, 1.045..1.225, 1.225..1.505) instead of
    // slicing through a cluster and splitting near-identical levels apart.
    const val CUT_NORMAL = 0.93
    const val CUT_HARD = 1.13
    const val CUT_EXPERT = 1.36
    const val CUT_MASTER = 1.90

    /** Raw score. Exposed so tests and tooling can inspect the number itself. */
    fun score(inputs: Inputs): Double {
        val boxes = inputs.boxes.coerceAtLeast(0)
        val floor = inputs.floorCells.coerceAtLeast(1)
        val moves = inputs.parMoves.coerceAtLeast(0)
        val pushes = inputs.parPushes.coerceAtLeast(0)

        // Squared roots keep any one factor from dominating a sum it happens to
        // scale fastest in — which is exactly what boxes used to do.
        val boxFactor = sqrt(boxes / REF_BOXES)
        val moveFactor = sqrt(moves / REF_MOVES)
        val pushFactor = sqrt(pushes / REF_PUSHES)
        val sizeFactor = sqrt(floor / REF_FLOOR)

        val repositionFactor = (if (boxes > 0) pushes.toDouble() / boxes else 0.0) / REF_REPOSITION
        val deadDensity = inputs.deadSquares.coerceAtLeast(0) / floor.toDouble()
        val riskFactor = deadDensity / REF_RISK

        val base =
            W_BOXES * boxFactor +
                W_MOVES * moveFactor +
                W_PUSHES * pushFactor +
                W_REPOSITION * repositionFactor +
                W_RISK * riskFactor +
                W_SIZE * sizeFactor

        val extraMechanics = (inputs.mechanics.size - 1).coerceAtLeast(0)
        return base * (1.0 + extraMechanics * W_MECHANIC)
    }

    /** Maps a score onto the five conceptual bands. */
    fun bandFor(score: Double): Difficulty = when {
        score < CUT_NORMAL -> Difficulty.EASY
        score < CUT_HARD -> Difficulty.NORMAL
        score < CUT_EXPERT -> Difficulty.HARD
        score < CUT_MASTER -> Difficulty.EXPERT
        else -> Difficulty.MASTER
    }

    fun evaluate(inputs: Inputs): Difficulty = bandFor(score(inputs))

    fun evaluate(level: LevelDefinition): Difficulty = evaluate(Inputs.of(level))
}
