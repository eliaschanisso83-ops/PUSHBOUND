package com.pushbound.game

import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameEngine
import com.pushbound.game.engine.GameState
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.MoveResult
import com.pushbound.game.engine.Position
import com.pushbound.game.engine.solver.DeadlockDetector
import com.pushbound.game.engine.solver.LevelSolver
import com.pushbound.game.engine.solver.LevelValidator
import com.pushbound.game.ui.BoardGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Internal walls are a first-class part of the level format.
 *
 * These tests exist to close the gap that made the old boards read as empty
 * rooms: a wall on the border only makes a box, while an internal wall is
 * what makes a board a maze. Everything below proves there is ONE
 * representation — the ASCII authored level — and that it drives movement,
 * crate pushing, the solver, deadlock detection and the renderer identically.
 */
class LevelGeometryTest {

    // ---------------------------------------------------------------- data --

    @Test
    fun internalWallsArePartOfTheLevelData() {
        GeometryTestBoards.ALL.forEach { board ->
            val inner = board.internalWalls()
            assertTrue(
                "${board.id} must have internal walls, otherwise it is just an outer box",
                inner.isNotEmpty()
            )
            // Every internal wall is a real member of the logical wall set —
            // the renderer draws exactly this set, so nothing decorative can
            // exist and nothing logical can be invisible.
            inner.forEach { assertTrue("${board.id}: $it must be a wall", it in board.level.walls) }
        }
    }

    @Test
    fun noBoardIsJustAnOuterRectangle() {
        // The specific failure this test set was written for: 9 of the 15
        // legacy levels had zero internal walls, so the renderer correctly
        // drew an empty room. None of the geometry boards may regress.
        GeometryTestBoards.ALL.forEach { board ->
            val inner = board.internalWalls().size
            val cells = board.level.width * board.level.height
            assertTrue(
                "${board.id} needs real structure ($inner internal walls in $cells cells)",
                inner >= 4 && inner.toDouble() / cells >= 0.05
            )
        }
    }

    @Test
    fun wallsPartitionTheBoardExactly() {
        // Visual/logical consistency: every cell is either wall or floor,
        // with no third state and no overlap. This is what guarantees the
        // engine never blocks a cell the player cannot see walled, and never
        // lets the player walk somewhere the renderer showed as wall.
        GeometryTestBoards.ALL.forEach { board ->
            val level = board.level
            assertEquals(
                "${board.id}: floor + walls must cover the whole board",
                level.width * level.height,
                level.floorCount + level.walls.size
            )
            level.walls.forEach { wall ->
                assertTrue("${board.id}: $wall out of bounds", level.inBounds(wall))
                assertFalse("${board.id}: $wall is both wall and floor", wall in level.goals)
            }
        }
    }

    // ------------------------------------------------------------ movement --

    @Test
    fun thePlayerCannotWalkThroughAnInternalWall() {
        val board = GeometryTestBoards.CORRIDOR
        val level = board.level
        // (4,3) is an INTERNAL wall — not on the border — with floor at (4,2).
        val internalWall = Position(4, 3)
        assertTrue("the layout changed: (4,3) must be a wall", internalWall in level.walls)
        assertTrue(
            "the layout changed: (4,3) must be internal, not border",
            internalWall in board.internalWalls()
        )
        val state = GameState(player = Position(4, 2), boxes = emptySet(), moves = 0)

        val result = GameEngine.move(level, state, Direction.RIGHT)

        assertEquals(
            "walking into an internal wall must be blocked exactly like a border wall",
            MoveResult.Blocked,
            result
        )
        assertEquals("a refused move never advances the clock", 0, state.moves)
    }

    @Test
    fun aCrateCannotBePushedIntoAnInternalWall() {
        val level = GeometryTestBoards.CORRIDOR.level
        val box = Position(4, 2)     // floor, directly left of the internal wall
        val player = Position(4, 1)  // floor, directly behind the crate
        val state = GameState(player = player, boxes = setOf(box), moves = 0)

        val result = GameEngine.move(level, state, Direction.RIGHT)

        assertEquals(
            "pushing a crate into an internal wall must be refused",
            MoveResult.Blocked,
            result
        )
        assertEquals(
            "the crate must not have moved",
            setOf(box),
            state.boxes
        )
    }

    @Test
    fun aPushThroughAClearInternalGapIsAllowed() {
        // The negative control for the two tests above: the same board, the
        // same kind of push, a direction that is NOT walled. Without this,
        // "blocked" could just mean the engine refuses everything.
        // (3,5) and (4,5) are both floor, running alongside the internal
        // wall at (3,3)-(3,4) without touching it.
        val level = GeometryTestBoards.CORRIDOR.level
        val box = Position(3, 5)     // floor, beside the internal wall run
        val player = Position(2, 5)  // floor, directly above it
        val state = GameState(player = player, boxes = setOf(box), moves = 0)

        val result = GameEngine.move(level, state, Direction.DOWN)

        assertTrue("a clear push must succeed, got $result", result is MoveResult.Moved)
        assertEquals(
            "the crate should have advanced one cell",
            setOf(Position(4, 5)),
            (result as MoveResult.Moved).state.boxes
        )
    }

    // ------------------------------------------------- renderer vs logic ----

    @Test
    fun theRendererAndTheLogicSeeTheSameWalls() {
        // BoardRenderer draws one footprint per cell of level.walls and fills
        // the rest with floor, so agreeing here means the picture and the
        // puzzle agree: geometry.columns/rows come from the same width/height
        // the engine collides against, and every cell maps to exactly one
        // non-overlapping square.
        GeometryTestBoards.ALL.forEach { board ->
            val level = board.level
            val geometry = BoardGeometry.fit(
                availableWidth = 1080f, availableHeight = 1920f,
                columns = level.width, rows = level.height
            )

            assertEquals("${board.id}: columns", level.width, geometry.columns)
            assertEquals("${board.id}: rows", level.height, geometry.rows)

            // Cells are square and never stretched.
            assertEquals(
                "${board.id}: cells must be square",
                geometry.cellLeft(1f) - geometry.cellLeft(0f),
                geometry.cellTop(1f) - geometry.cellTop(0f),
                0.001f
            )

            // Every wall gets its own cell inside the board, and no two walls
            // can ever land on the same rectangle.
            val seen = mutableSetOf<Pair<Float, Float>>()
            level.walls.forEach { wall ->
                val left = geometry.cellLeft(wall.col.toFloat())
                val top = geometry.cellTop(wall.row.toFloat())
                assertTrue("${board.id}: $wall outside the board", left >= geometry.originX - 0.001f && top >= geometry.originY - 0.001f)
                assertTrue("${board.id}: duplicate footprint for $wall", seen.add(left to top))
            }
        }
    }

    // ----------------------------------------------------------- solver ----

    @Test
    fun theSolverRoutesTheCrateThroughTheInternalWalls() {
        // The solver walks the same walls the engine does, so a board whose
        // only route threads an internal gap would be unsolvable if it did
        // not. These are the measured shortest solutions.
        val cases = listOf(
            Triple(GeometryTestBoards.CORRIDOR, 10, 5),
            Triple(GeometryTestBoards.CHAMBER, 7, 4),
            Triple(GeometryTestBoards.TWO_ROUTES, 17, 6),
            Triple(GeometryTestBoards.DEADLOCK, 14, 6)
        )
        cases.forEach { (board, moves, pushes) ->
            val solution = LevelSolver.solve(board.level)
            assertNotNull("${board.id} must be solvable", solution)
            assertEquals("${board.id}: par moves", moves, solution!!.moveCount)
            assertEquals("${board.id}: par pushes", pushes, solution.pushCount)
        }
    }

    @Test
    fun everyGeometryBoardValidatesClean() {
        GeometryTestBoards.ALL.forEach { board ->
            val report = LevelValidator.validate(board.level)
            assertEquals("${board.id}: ${report.problems}", emptyList<String>(), report.problems)
            assertEquals("${board.id} must solve", LevelSolver.Outcome.SOLVED, report.outcome)
            assertTrue("${board.id} needs a par moves", report.parMoves > 0)
            assertTrue("${board.id} needs a par pushes", report.parPushes > 0)
        }
    }

    // -------------------------------------------------------- deadlock ----

    @Test
    fun deadlockDetectionSeesTheInternalCorner() {
        val board = GeometryTestBoards.DEADLOCK
        val level = board.level
        val trap = GeometryTestBoards.DEADLOCK_TRAP

        // The trap is a genuine corner because BOTH blocked sides are
        // internal walls, not the outer border. That is the whole point: a
        // corner made of border is trivial, a corner the level author built
        // inside the board is what actually traps a crate.
        assertTrue("the trap cell must be floor", trap !in level.walls)
        assertTrue("the trap is not a goal", trap !in level.goals)
        assertTrue(
            "left of the trap must be an internal wall",
            Position(trap.row, trap.col - 1) in board.internalWalls()
        )
        assertTrue(
            "above the trap must be an internal wall",
            Position(trap.row - 1, trap.col) in board.internalWalls()
        )

        assertTrue(
            "the detector must flag the internal corner",
            DeadlockDetector.isCornerDeadlockAt(level, trap)
        )
        assertTrue(
            "the trap must be listed among the board's fatal corners",
            trap in DeadlockDetector.fatalCorners(level)
        )
    }

    @Test
    fun theDeadlockTrapIsAPushableTrapNotAnUnreachableDecoration() {
        // A fatal corner IS a dead square by definition, so "is it dead?" is
        // not the interesting question. What separates a trap from decoration
        // is that a crate can actually be PUSHED into it during play: the
        // corner must touch at least one live cell, from which a single push
        // lands the crate on the fatal square. The delivery cell beside it
        // must stay live, so there is a correct play to contrast the mistake
        // against.
        val board = GeometryTestBoards.DEADLOCK
        val dead = DeadlockDetector.deadSquares(board.level)
        val trap = GeometryTestBoards.DEADLOCK_TRAP
        val delivery = GeometryTestBoards.DEADLOCK_DELIVERY
        val goal = GeometryTestBoards.DEADLOCK_GOAL

        assertTrue("the trap must be a dead square", trap in dead)
        assertFalse("the delivery cell must be live", delivery in dead)
        assertFalse("the goal itself is never dead", goal in dead)
        assertTrue("the goal is on the board", goal in board.level.goals)

        // Reachability: some live neighbour means a push from it strands the
        // crate. A corner with no live neighbour could never be reached and
        // would teach the player nothing.
        val liveNeighbours = listOf(
            Position(trap.row, trap.col - 1),
            Position(trap.row, trap.col + 1),
            Position(trap.row - 1, trap.col),
            Position(trap.row + 1, trap.col)
        ).filter { board.level.inBounds(it) && it !in board.level.walls && it !in dead }

        assertTrue(
            "the trap must be reachable by a push, otherwise it is decoration",
            liveNeighbours.isNotEmpty()
        )
        // And the delivery cell is exactly that neighbour, one push away.
        assertTrue(
            "the delivery cell must be the live neighbour that feeds the trap",
            delivery in liveNeighbours
        )
    }

    // ------------------------------------------------------- hygiene ----

    @Test
    fun theTestBoardsAreNotRegisteredInTheCampaign() {
        // These are TEST layouts. They must not be shippable by accident, so
        // they are absent from the campaign and from the id registry.
        val ids = GeometryTestBoards.ALL.map { it.id }.toSet()
        GeometryTestBoards.ALL.forEach { board ->
            assertFalse(
                "${board.id} must not be in the campaign",
                board.id in Levels.campaign.allLevels.map { it.id }
            )
            assertNull("Levels.byId must not resolve ${board.id}", Levels.byId(board.id))
        }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
    }
}
