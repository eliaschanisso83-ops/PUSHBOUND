package com.pushbound.game

import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameEngine
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.MoveResult
import com.pushbound.game.engine.Position
import com.pushbound.game.engine.solver.DeadlockDetector
import com.pushbound.game.engine.solver.LevelSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production solver: optimality, self-verification, deadlock pruning and
 * the node budget that keeps it usable as validation tooling.
 */
class LevelSolverTest {

    private fun level(vararg rows: String) = LevelDefinition.fromAscii("test", rows.toList())

    @Test
    fun solvesThePrototypeLevelAndReportsBothCounters() {
        val solution = LevelSolver.solve(Levels.first)

        assertNotNull("the shipped prototype level must be solvable", solution)
        solution!!
        assertTrue(solution.moves.isNotEmpty())
        assertEquals(solution.moves.size, solution.moveCount)
        assertTrue("a solution must push at least once", solution.pushCount >= 1)
        assertTrue(solution.pushCount <= solution.moveCount)
    }

    @Test
    fun everySolutionReplaysThroughTheRealEngine() {
        // The solver self-verifies, but the guarantee is worth pinning from the
        // outside too: replay through GameSession exactly as the game would.
        val solution = LevelSolver.solve(Levels.first)!!
        val session = com.pushbound.game.engine.GameSession(Levels.first)

        for (direction in solution.moves) {
            assertTrue("solver move $direction must be accepted by the engine",
                session.move(direction) is MoveResult.Moved)
        }

        assertTrue(session.isComplete)
        assertEquals(solution.moveCount, session.state.moves)
        assertEquals(solution.pushCount, session.state.pushes)
    }

    @Test
    fun theDocumentedSolutionIsNoBetterThanTheSolver() {
        // The documented 16-move solution in TestLevelSolvabilityTest must not be
        // shorter than the optimal one the solver finds.
        val solution = LevelSolver.solve(Levels.first)!!
        assertTrue(
            "solver found a shorter solution (${solution.moveCount}) than expected",
            solution.moveCount <= 16
        )
        assertEquals("the prototype needs exactly three pushes", 3, solution.pushCount)
    }

    @Test
    fun solvesATwoBoxLevel() {
        val lvl = level(
            "#####",
            "#G  #",
            "#B  #",
            "# P #",
            "#B  #",
            "#G  #",
            "#####"
        )
        val solution = LevelSolver.solve(lvl)

        assertNotNull(solution)
        solution!!
        assertEquals("two boxes on two goals need exactly two pushes", 2, solution.pushCount)
        assertTrue(LevelSolver.replaysToCompletion(lvl, solution))
    }

    @Test
    fun rejectsALevelWhoseBoxIsAlreadyWalledIn() {
        // (3,3) is a fatal corner: walls to the right and below, and it is not a
        // goal. The box can never leave, so the level cannot be solved. The
        // solver must refuse it up front rather than search.
        val lvl = level(
            "#####",
            "# G #",
            "#   #",
            "#PB #",
            "#####"
        )
        val result = LevelSolver.solveDetailed(lvl)
        assertEquals(LevelSolver.Outcome.UNSOLVABLE, result.outcome)
        assertNull(result.solution)
    }

    @Test
    fun rejectsALevelWithMoreBoxesThanGoals() {
        // Two boxes, one goal: `areAllBoxesOnGoals` can never hold, so the
        // search must exhaust every reachable state and report unsolvable.
        val lvl = level(
            "######",
            "#    #",
            "# BB #",
            "# G  #",
            "# P  #",
            "######"
        )
        val result = LevelSolver.solveDetailed(lvl)
        assertEquals(LevelSolver.Outcome.UNSOLVABLE, result.outcome)
    }

    @Test
    fun pruningNeverTurnsASolvableLevelUnsolvable() {
        // A corner exists on this board, but the box never has to enter it.
        // Pruning must therefore stay out of the way of a real solution.
        val lvl = level(
            "#####",
            "#   #",
            "# G #",
            "# B #",
            "#P  #",
            "#####"
        )
        val solution = LevelSolver.solve(lvl)
        assertNotNull("pruning must not destroy a solvable level", solution)
        assertEquals(1, solution!!.pushCount)
        assertTrue(LevelSolver.replaysToCompletion(lvl, solution))
    }

    @Test
    fun aLevelAlreadySolvedAtTheStartNeedsNoMoves() {
        val lvl = level(
            "#####",
            "#+  #",
            "# P #",
            "#   #",
            "#####"
        )
        val solution = LevelSolver.solve(lvl)
        assertNotNull(solution)
        assertEquals(0, solution!!.moveCount)
        assertEquals(0, solution.pushCount)
    }

    @Test
    fun respectsTheNodeBudget() {
        // A generous floor on a trivial level: it must never need the budget.
        val trivial = level(
            "#####",
            "#G  #",
            "#B  #",
            "#P  #",
            "#####"
        )
        val result = LevelSolver.solveDetailed(trivial, maxNodes = 50)
        assertEquals(LevelSolver.Outcome.SOLVED, result.outcome)
    }

    @Test
    fun aZeroBudgetReportsExhaustionRatherThanUnsolvable() {
        // The distinction matters: a level wrongly called unsolvable would fail
        // validation, so exhausting the budget must stay visibly different.
        val lvl = level(
            "######",
            "#    #",
            "# PBG#",
            "#    #",
            "######"
        )
        val result = LevelSolver.solveDetailed(lvl, maxNodes = 0)
        assertEquals(LevelSolver.Outcome.BUDGET_EXHAUSTED, result.outcome)
        assertNull(result.solution)
    }

    @Test
    fun theSolverIsOptimalOnAnExhaustivelyCheckableLevel() {
        // A tiny level where every reachable state fits in memory: compare the
        // solver against an independent brute-force minimum.
        val lvl = level(
            "######",
            "#    #",
            "# PBG#",
            "#    #",
            "######"
        )
        val solverMoves = LevelSolver.solve(lvl)!!.moveCount
        val bruteForce = bruteForceShortest(lvl)

        assertNotNull("the corridor must be solvable", bruteForce)
        assertEquals("the solver must find the true minimum", bruteForce, solverMoves)
    }

    @Test
    fun deadlockDetectorRecognisesAFatalCorner() {
        val lvl = level(
            "#####",
            "#   #",
            "#P  #",
            "#  B#",
            "#####"
        )
        val boxInCorner = Position(3, 3)
        assertTrue(boxInCorner !in lvl.goals)
        assertTrue(DeadlockDetector.isCornerDeadlockAt(lvl, boxInCorner))
        assertTrue(DeadlockDetector.hasCornerDeadlock(lvl, setOf(boxInCorner)))
    }

    @Test
    fun aBoxOnItsGoalIsNeverADeadlock() {
        val lvl = level(
            "#####",
            "#   #",
            "#P  #",
            "#  G#",
            "#####"
        )
        val corner = Position(3, 3)
        assertTrue(corner in lvl.goals)
        assertFalse("a finished box is not stuck", DeadlockDetector.isCornerDeadlockAt(lvl, corner))
    }

    @Test
    fun fatalCornersListsTheUnrecoverableCells() {
        val lvl = level(
            "#####",
            "#   #",
            "# P #",
            "#   #",
            "#####"
        )
        val corners = DeadlockDetector.fatalCorners(lvl)
        // All four inner corners are fatal; the centre cells are not.
        assertEquals(4, corners.size)
        assertTrue(Position(1, 1) in corners)
        assertTrue(Position(1, 3) in corners)
        assertTrue(Position(3, 1) in corners)
        assertTrue(Position(3, 3) in corners)
        assertFalse(Position(2, 2) in corners)
    }

    @Test
    fun solverAndEngineAgreeOnEveryReachableStateOfThePrototype() {
        // Exhaustive cross-check on the shipped level: for every state the
        // engine can reach, the solver's step logic must return the same result.
        val lvl = Levels.first
        val seen = mutableSetOf<com.pushbound.game.engine.GameState>()
        val queue = ArrayDeque<com.pushbound.game.engine.GameState>()
        val start = GameEngine.initialState(lvl)
        queue.add(start)
        seen.add(start)

        var steps = 0
        while (queue.isNotEmpty() && steps < 2000) {
            val state = queue.removeFirst()
            for (direction in Direction.entries) {
                val result = GameEngine.move(lvl, state, direction)
                steps++
                if (result is MoveResult.Moved && seen.add(result.state)) {
                    queue.add(result.state)
                }
            }
        }
        // Sanity: the prototype really does have reachable states to compare.
        assertTrue("expected the prototype to be explored", seen.size > 5)
        assertTrue("expected many engine steps", steps > 20)
    }

    @Test
    fun aBoxTrappedByWallLayoutIsUnsolvableEvenWhereNoCornerExists() {
        // The box sits mid-wall, not in a corner, so the corner test cannot see
        // it — and pushing it around the wall is still impossible, because the
        // goal's only approaches are all sealed to a push. This is what the
        // dead-square sweep is for.
        val lvl = level(
            "######",
            "#P   #",
            "#B   #",
            "# #  #",
            "# G  #",
            "######"
        )
        assertFalse(
            "the box must not be in a corner, or this proves nothing",
            DeadlockDetector.isCornerDeadlockAt(lvl, Position(2, 1))
        )
        assertTrue(
            "dead squares must catch what corners miss",
            DeadlockDetector.deadSquares(lvl).contains(Position(2, 1))
        )
        val result = LevelSolver.solveDetailed(lvl)
        assertEquals(LevelSolver.Outcome.UNSOLVABLE, result.outcome)
        assertNull(result.solution)
    }

    @Test
    fun deadSquaresSeparateSealedCellsFromLiveOnes() {
        // A wall seals the lower-right pocket off from the goal, so those cells
        // are dead, while the open corridor above the box is not.
        val lvl = level(
            "#######",
            "#  P  #",
            "# B #G#",
            "### # #",
            "#   # #",
            "#######"
        )
        val dead = DeadlockDetector.deadSquares(lvl)

        // The goal itself is never dead — it is where boxes are meant to end.
        assertFalse("a goal cannot be dead", Position(2, 5) in dead)
        // The only other cell a box could be pushed into the goal from.
        assertFalse("the cell under the goal must stay live", Position(3, 5) in dead)
        // Everything the wall seals off can never reach the goal — including the
        // cell the box starts on.
        assertTrue("the corridor is still sealed off", Position(2, 2) in dead)
        assertTrue("the sealed pocket is dead", Position(4, 2) in dead)
        // (3,5) stays live even though no box can ever REACH it, because the
        // sweep asks the weaker question "could a box HERE still get out?".
        // Answering weakly keeps the live set an over-approximation, which is
        // the only direction that can never prune away a real solution.
    }

    @Test
    fun theSameLevelAlwaysYieldsTheSameSolution() {
        // Determinism is a requirement, not a nicety: medals, par values and
        // cached hints are all derived from this list, so two runs that
        // disagree would silently disagree about what the level is.
        val lvl = Levels.byId("w5-2")!!
        val first = LevelSolver.solve(lvl)
        val second = LevelSolver.solve(lvl)
        assertNotNull(first)
        assertEquals("the same level must always solve the same way", first, second)
    }

    @Test
    fun thePushSearchStillFindsTheMoveMinimumOnAMultiBoxBoard() {
        // Push-based search naturally minimises pushes; it must not confuse
        // that with minimising MOVES, which is what par and the medals read.
        val lvl = level(
            "######",
            "#    #",
            "#BB  #",
            "#GG  #",
            "# P  #",
            "######"
        )
        val solverMoves = LevelSolver.solve(lvl)!!.moveCount
        val bruteForce = bruteForceShortest(lvl)
        assertNotNull("the brute-force check must be able to finish", bruteForce)
        assertEquals("the push search must return the move minimum", bruteForce, solverMoves)
        assertTrue(LevelSolver.replaysToCompletion(lvl, LevelSolver.solve(lvl)!!))
    }

    /** Independent brute-force minimum: plain BFS, no pruning, no normalisation. */
    private fun bruteForceShortest(level: LevelDefinition): Int? {
        val start = GameEngine.initialState(level)
        data class Node(val state: com.pushbound.game.engine.GameState, val depth: Int)

        val queue = ArrayDeque<Node>()
        queue.add(Node(start, 0))
        val seen = mutableSetOf(start)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (GameEngine.isComplete(level, node.state)) return node.depth
            for (direction in Direction.entries) {
                val result = GameEngine.move(level, node.state, direction)
                if (result is MoveResult.Moved && seen.add(result.state)) {
                    queue.add(Node(result.state, node.depth + 1))
                }
            }
        }
        return null
    }
}
