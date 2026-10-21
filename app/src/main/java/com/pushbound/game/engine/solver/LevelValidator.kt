package com.pushbound.game.engine.solver

import com.pushbound.game.engine.Difficulty
import com.pushbound.game.engine.DifficultyModel
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.MedalThresholds
import com.pushbound.game.engine.Position

/**
 * What the validator learned about one level. The report is the artefact a
 * level author reads: it says whether the level may ship, and if not, why not.
 */
data class LevelReport(
    val levelId: String,
    val structuralProblems: List<String>,
    val outcome: LevelSolver.Outcome,
    val solution: Solution?,
    val fatalCorners: List<Position>,
    val parMoves: Int,
    val parPushes: Int,
    val difficulty: Difficulty
) {
    /** Every reason this level is not production-ready. Empty means approved. */
    val problems: List<String>
        get() = structuralProblems + when (outcome) {
            LevelSolver.Outcome.SOLVED -> emptyList()
            LevelSolver.Outcome.BUDGET_EXHAUSTED ->
                listOf("solver budget exhausted — solvability unproven")
            LevelSolver.Outcome.UNSOLVABLE ->
                listOf("no solution found — level is unsolvable")
        }

    val approved: Boolean get() = problems.isEmpty()
}

/**
 * The level validation pipeline:
 *
 *   LEVEL
 *   -> VALIDATE BOARD     (structure: goals, boxes, walls, placements)
 *   -> CHECK SOLVABILITY  (a fatal starting position is rejected immediately)
 *   -> SOLVE              (shortest solution, within a node budget)
 *   -> CALCULATE PAR      (moves and pushes of that shortest solution)
 *   -> CALCULATE DIFFICULTY (scored from the level's real measures)
 *   -> APPROVE            (par and difficulty are stamped onto the level)
 *
 * Every official level must pass this before it ships. A level test that runs
 * [approveAll] over the whole campaign is what turns "we think it is solvable"
 * into "the shortest solution is known and measured".
 *
 * COST — this runs offline, at build/test time, never during play. The solver
 * is the expensive part and is only reached once per level.
 */
object LevelValidator {

    /** Default search budget per level. */
    const val DEFAULT_MAX_NODES = LevelSolver.DEFAULT_MAX_NODES

    /**
     * Validation results, memoised per level definition.
     *
     * Validation is the expensive part of startup and of the test suite — the
     * same level gets asked about by the campaign bootstrap, the medal checks
     * and the solvability checks. The answer can never change for a given
     * grid, so it is computed once. The map is bounded: this is tooling, and a
     * level list is small by construction.
     */
    private val reportCache = HashMap<LevelDefinition, LevelReport>()

    /** Drops cached reports. Intended for tests that need a cold run. */
    fun clearCache() = reportCache.clear()

    /** Structural soundness: everything checkable without searching. */
    fun structuralProblems(level: LevelDefinition): List<String> {
        val problems = mutableListOf<String>()

        if (level.width <= 0 || level.height <= 0) {
            problems += "board must have positive dimensions"
        }
        if (level.goals.isEmpty()) problems += "level has no goals"
        if (level.boxStarts.isEmpty()) problems += "level has no boxes"
        if (level.boxStarts.size != level.goals.size) {
            problems += "box count (${level.boxStarts.size}) must equal goal count (${level.goals.size})"
        }
        if (level.playerStart in level.walls) problems += "player starts inside a wall"
        if (!level.inBounds(level.playerStart)) problems += "player starts outside the board"

        level.boxStarts.forEachIndexed { index, box ->
            if (box in level.walls) problems += "box $index starts inside a wall"
            if (!level.inBounds(box)) problems += "box $index starts outside the board"
        }
        level.goals.forEach { goal ->
            if (goal in level.walls) problems += "goal at $goal is inside a wall"
            if (!level.inBounds(goal)) problems += "goal at $goal is outside the board"
        }
        if (level.boxStarts.toSet().size != level.boxStarts.size) {
            problems += "two boxes start on the same cell"
        }
        if (level.playerStart in level.boxStarts) problems += "player starts on a box"

        // A box that begins wedged in a corner it does not belong in can never
        // move: the level is dead on arrival.
        level.boxStarts.forEach { box ->
            if (DeadlockDetector.isCornerDeadlockAt(level, box)) {
                problems += "box starts in a fatal corner at $box"
            }
        }

        return problems
    }

    fun validate(level: LevelDefinition, maxNodes: Int = DEFAULT_MAX_NODES): LevelReport {
        reportCache[level]?.let { return it }

        val structural = structuralProblems(level)
        val corners = DeadlockDetector.fatalCorners(level)

        val report = if (structural.isNotEmpty()) {
            // No point searching a structurally broken board.
            LevelReport(
                levelId = level.id,
                structuralProblems = structural,
                outcome = LevelSolver.Outcome.UNSOLVABLE,
                solution = null,
                fatalCorners = corners,
                parMoves = 0,
                parPushes = 0,
                difficulty = DifficultyModel.evaluate(level)
            )
        } else {
            val result = LevelSolver.solveDetailed(level, maxNodes)
            val solution = result.solution

            LevelReport(
                levelId = level.id,
                structuralProblems = structural,
                outcome = result.outcome,
                solution = solution,
                fatalCorners = corners,
                parMoves = solution?.moveCount ?: 0,
                parPushes = solution?.pushCount ?: 0,
                difficulty = DifficultyModel.evaluate(
                    DifficultyModel.Inputs.of(level).copy(
                        parMoves = solution?.moveCount ?: level.parMoves,
                        parPushes = solution?.pushCount ?: level.parPushes
                    )
                )
            )
        }

        reportCache[level] = report
        return report
    }

    /**
     * Stamps the measured par and derived difficulty onto the level.
     *
     * @throws IllegalArgumentException when the level is not production-ready,
     *   so an unsolvable level can never be silently shipped.
     */
    fun approve(
        level: LevelDefinition,
        maxNodes: Int = DEFAULT_MAX_NODES,
        thresholds: MedalThresholds? = null
    ): LevelDefinition {
        val report = validate(level, maxNodes)
        require(report.approved) {
            "Level '${level.id}' is not production-ready: ${report.problems.joinToString("; ")}"
        }
        return level.copy(
            parMoves = report.parMoves,
            parPushes = report.parPushes,
            difficulty = report.difficulty,
            medalThresholds = thresholds ?: level.medalThresholds
        )
    }

    /** Approves a whole campaign. Throws on the first level that fails. */
    fun approveAll(
        levels: List<LevelDefinition>,
        maxNodes: Int = DEFAULT_MAX_NODES
    ): List<LevelDefinition> = levels.map { approve(it, maxNodes) }

    /** Non-throwing variant: reports every failing level instead of stopping at the first. */
    fun reportAll(levels: List<LevelDefinition>, maxNodes: Int = DEFAULT_MAX_NODES): List<LevelReport> =
        levels.map { validate(it, maxNodes) }
}
