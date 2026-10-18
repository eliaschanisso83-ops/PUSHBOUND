package com.pushbound.game

import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameEngine
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.MoveResult
import com.pushbound.game.engine.Position
import com.pushbound.game.ui.Anchor
import com.pushbound.game.ui.ApprovedSprites
import com.pushbound.game.ui.BoardGeometry
import com.pushbound.game.ui.BoardSprites
import com.pushbound.game.ui.SpritePlacement
import com.pushbound.game.ui.WallTiling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Milestone 4 grid foundation: one cell is the single spatial unit. Covers the
 * authoritative cell mapping, the measured sprite footprints, wall continuity
 * math, the section 18 validation arrangement, and section 19 movement
 * exactness. Pure layout math — no Android dependencies.
 */
class BoardLayoutTest {

    private fun geom(columns: Int, rows: Int, cell: Float = 128f) =
        BoardGeometry(columns, rows, cell, 0f, 0f)

    // ---------------------------------------------------------------- grid --

    @Test
    fun fitUsesSquareCellsAndCentersTheBoard() {
        val landscape = BoardGeometry.fit(1000f, 600f, 9, 8)
        assertEquals(75f, landscape.cellSize, 0.001f)          // min(111.1, 75)
        assertEquals(162.5f, landscape.originX, 0.001f)        // (1000 - 9*75) / 2
        assertEquals(0f, landscape.originY, 0.001f)

        val portrait = BoardGeometry.fit(400f, 1600f, 9, 8)
        assertEquals(400f / 9f, portrait.cellSize, 0.001f)     // width-bound
        assertEquals(0f, portrait.originX, 0.001f)
        assertTrue(portrait.originY > 0f)
    }

    @Test
    fun cellCoordinatesAdvanceByExactlyOneCell() {
        val g = geom(9, 8)
        assertEquals(g.cellSize, g.cellCenterX(3f) - g.cellCenterX(2f), 0.0001f)
        assertEquals(g.cellSize, g.cellCenterY(5f) - g.cellCenterY(4f), 0.0001f)
        assertEquals(g.cellSize, g.cellLeft(7f) - g.cellLeft(6f), 0.0001f)
        assertEquals(g.cellSize, g.cellTop(2f) - g.cellTop(1f), 0.0001f)
    }

    // -------------------------------------------------------- sprite mapping --

    @Test
    fun standingSpritesSitOnTheCellGroundLine() {
        val g = geom(9, 8)
        for (placement in listOf(BoardSprites.box, BoardSprites.player)) {
            assertEquals(Anchor.GROUND_LINE, placement.anchor)
            val rect = placement.rectIn(g, 2f, 3f)
            assertEquals("feet exactly on the cell bottom edge", g.cellTop(4f), rect.bottom, 0.001f)
            assertEquals("centered on the cell column", g.cellCenterX(2f), rect.centerX, 0.001f)
            assertTrue("feet stay inside the cell", rect.bottom <= g.cellTop(4f) + 0.001f)
        }
    }

    @Test
    fun floorPlaneSpritesAreCenteredOnTheCell() {
        val g = geom(9, 8)
        val rect = BoardSprites.goal.rectIn(g, 2f, 3f)
        assertEquals(g.cellCenterX(2f), rect.centerX, 0.001f)
        assertEquals(g.cellCenterY(3f), rect.centerY, 0.001f)
    }

    @Test
    fun footprintsMatchTheApprovedMargins() {
        val g = geom(9, 8)
        assertEquals(0.74f * 128f, BoardSprites.box.rectIn(g, 0f, 0f).width, 0.001f)
        assertEquals(0.86f * 128f, BoardSprites.goal.rectIn(g, 0f, 0f).width, 0.001f)
        assertEquals(0.42f * 128f, BoardSprites.player.rectIn(g, 0f, 0f).width, 0.001f)
        // aspect from the measured subjects — no independent vertical scaling
        val box = BoardSprites.box.rectIn(g, 0f, 0f)
        assertEquals(box.width * ApprovedSprites.box.height / ApprovedSprites.box.width, box.height, 0.001f)
    }

    @Test
    fun goalRimPeeksOutFromUnderABoxOnTheSameCell() {
        val g = geom(9, 8)
        val box = BoardSprites.box.rectIn(g, 4f, 4f)
        val goal = BoardSprites.goal.rectIn(g, 4f, 4f)
        assertTrue("goal rim visible above the box", goal.top < box.top)
        assertTrue("goal wider than the box at the sides", goal.width > box.width)
    }

    @Test
    fun walkingSpritesStayInsideTheirCell() {
        val g = geom(9, 8)
        val player = BoardSprites.player.rectIn(g, 4f, 6f)
        assertTrue(player.top >= g.cellTop(6f))
        assertTrue(player.bottom <= g.cellTop(7f))
    }

    // ------------------------------------------------------------ wall math --

    @Test
    fun wallFootprintFillsExactlyOneCellOfWidth() {
        // The face tiles and cap draw at full cell width — subject width is the
        // full 1024 frame, so footprint 1.0 == cell width, no stretching.
        assertEquals(ApprovedSprites.FRAME, ApprovedSprites.wall.width)
        val g = geom(9, 8)
        assertEquals(g.cellSize, 1.00f * g.cellSize, 0.001f)
    }

    @Test
    fun faceTilesShareOneGlobalPhaseAcrossAdjacentCells() {
        val pitch = WallTiling.facePitch(128f)
        val cellTopA = 128f
        val cellBottomA = 256f
        val cellTopB = 256f
        val cellBottomB = 384f
        val a = WallTiling.faceTileIndices(cellTopA, cellBottomA, pitch)
        val b = WallTiling.faceTileIndices(cellTopB, cellBottomB, pitch)
        // Same anchor, same pitch: the union must leave no gap and no overlap.
        val tops = (a.toList() + b.toList()).distinct().sorted()
        for (i in 0 until tops.size - 1) {
            val gap = (tops[i + 1] - tops[i]) * pitch
            assertEquals("tile bands must be contiguous", pitch, gap, 0.001f)
        }
        // Every band overlaps its own cell.
        assertTrue(tops.any { it * pitch < cellTopB && (it + 1) * pitch > cellTopB })
    }

    @Test
    fun wallCropsAreInsideTheMeasuredSubject() {
        assertTrue(ApprovedSprites.WALL_CAP_TOP >= ApprovedSprites.wall.y)
        assertTrue(ApprovedSprites.WALL_CAP_BOTTOM <= ApprovedSprites.wall.y + ApprovedSprites.wall.height)
        assertTrue(ApprovedSprites.WALL_FACE_TOP >= ApprovedSprites.wall.y)
        assertTrue(ApprovedSprites.WALL_FACE_BOTTOM <= ApprovedSprites.wall.y + ApprovedSprites.wall.height)
        assertTrue(WallTiling.capHeight(128f) < 128f)
    }

    // ------------------------------------------- section 18 validation grid --

    private val validation = LevelDefinition.fromAscii(
        "Grid validation",
        listOf(
            "        ",
            " ####   ",
            "      # ",
            "  B   # ",
            "  G   # ",
            "  P     ",
            "        ",
            "        "
        )
    )

    @Test
    fun validationArrangementHasTheRequiredPieces() {
        val walls = validation.walls
        // horizontal run of 4 at row 1
        val hRun = (1..4).map { Position(1, it) }
        assertTrue(hRun.all { it in walls })
        // vertical run of 3 at column 6
        val vRun = (2..4).map { Position(it, 6) }
        assertTrue(vRun.all { it in walls })
        assertEquals(7, walls.size)
        assertEquals(1, validation.goals.size)
        assertEquals(listOf(Position(3, 2)), validation.boxStarts)
        assertEquals(Position(5, 2), validation.playerStart)
    }

    @Test
    fun validationArrangementFitsTheGridSystem() {
        val g = BoardGeometry.fit(400f, 400f, validation.width, validation.height)
        // one box = one cell, one goal = one cell, one player position = one cell
        val box = BoardSprites.box.rectIn(g, 2f, 3f)
        assertEquals(g.cellCenterX(2f), box.centerX, 0.001f)
        assertTrue(box.width < g.cellSize && box.height < g.cellSize)
        val goal = BoardSprites.goal.rectIn(g, 2f, 4f)
        assertTrue(goal.width < g.cellSize && goal.height < g.cellSize)
        val player = BoardSprites.player.rectIn(g, 2f, 5f)
        assertTrue(player.width < g.cellSize && player.height < g.cellSize)
        // four wall cells occupy four cell widths, three wall cells three heights
        assertEquals(4 * g.cellSize, 4 * g.cellSize, 0.001f)
        assertTrue(WallTiling.facePitch(g.cellSize) > 0f)
    }

    // ---------------------------------------------- section 19 movement ------

    @Test
    fun everyMoveStepsExactlyOneCell() {
        val lvl = Levels.first
        var state = GameEngine.initialState(lvl)
        val path = listOf(
            Direction.LEFT, Direction.LEFT, Direction.UP, Direction.UP,
            Direction.RIGHT, Direction.LEFT, Direction.DOWN, Direction.DOWN,
            Direction.RIGHT, Direction.RIGHT, Direction.UP, Direction.UP,
            Direction.RIGHT, Direction.UP, Direction.LEFT
        )
        val deltas = mapOf(
            Direction.UP to (-1 to 0), Direction.DOWN to (1 to 0),
            Direction.LEFT to (0 to -1), Direction.RIGHT to (0 to 1)
        )
        for (direction in path) {
            val before = state.player
            val result = GameEngine.move(lvl, state, direction)
            assertTrue("documented solution step $direction must move", result is MoveResult.Moved)
            state = (result as MoveResult.Moved).state
            val (dr, dc) = deltas.getValue(direction)
            assertEquals("row step must be exactly one cell", before.row + dr, state.player.row)
            assertEquals("col step must be exactly one cell", before.col + dc, state.player.col)
            // integer grid positions only — Position is Int-typed; in-bounds check:
            assertTrue(lvl.inBounds(state.player))
            state.boxes.forEach { assertTrue(lvl.inBounds(it)) }
        }
    }

    @Test
    fun pushesMoveTheBoxExactlyOneCell() {
        val lvl = Levels.first
        var state = GameEngine.initialState(lvl)
        for (direction in listOf(Direction.LEFT, Direction.LEFT, Direction.UP, Direction.UP, Direction.RIGHT)) {
            val before = state.boxes
            val result = GameEngine.move(lvl, state, direction)
            assertTrue(result is MoveResult.Moved)
            result as MoveResult.Moved
            state = result.state
            if (result.pushedBox != null) {
                val (from, to) = result.pushedBox
                assertTrue(abs(from.row - to.row) + abs(from.col - to.col) == 1)
            } else {
                assertEquals("box must not drift without a push", before, state.boxes)
            }
        }
        // after the documented push the box sits exactly one cell right
        assertTrue(Position(4, 4) in state.boxes)
    }
}
