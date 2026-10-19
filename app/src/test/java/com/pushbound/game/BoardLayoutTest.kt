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
import com.pushbound.game.ui.CharacterFrames
import com.pushbound.game.ui.CharacterPose
import com.pushbound.game.ui.SpritePlacement
import com.pushbound.game.ui.SpriteSubject
import com.pushbound.game.ui.WallTiling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun wallFaceBandIsTheApprovedMasonryCourse() {
        // Material regression guard: the face band must sample the art's
        // HERO masonry course (mortar at 392, bright brick face, AO
        // under-shadow, next mortar peak at 529 = one 137px course
        // period). The old y[529..619] band sat in the art's floor-AO
        // shadow and crushed into dark thin lines at cell scale — the
        // constants are pinned so that band can never come back.
        assertEquals(392, ApprovedSprites.WALL_FACE_TOP)
        assertEquals(529, ApprovedSprites.WALL_FACE_BOTTOM)
        assertEquals(137, ApprovedSprites.WALL_FACE_BOTTOM - ApprovedSprites.WALL_FACE_TOP)
        // At frame scale the pitch is exactly one course period.
        assertEquals(137f, WallTiling.facePitch(ApprovedSprites.FRAME.toFloat()), 0.001f)
    }

    @Test
    fun wallRunningBondAlternatesOnGlobalCourseParity() {
        // faceTileIndices can produce negative m near the board top, so the
        // parity must hold for negative indices too: the same course gets
        // the same offset from every wall cell.
        assertFalse(WallTiling.isBondOffsetCourse(-2))
        assertTrue(WallTiling.isBondOffsetCourse(-1))
        assertFalse(WallTiling.isBondOffsetCourse(0))
        assertTrue(WallTiling.isBondOffsetCourse(1))
        assertFalse(WallTiling.isBondOffsetCourse(2))
        // Half of the ~177px measured brick period at frame scale.
        assertEquals(
            ApprovedSprites.WALL_BRICK_BOND.toFloat(),
            WallTiling.bondOffset(ApprovedSprites.FRAME.toFloat()), 0.001f
        )
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

    // ------------------------------------------------- directional frames --

    @Test
    fun frameSubjectsMatchTheNormalizedDeliveryMeasurements() {
        // Pinned from tools/png_bbox.sh on the approved delivery (md5-verified):
        // all frames normalized to the master's height 773 and bottom row 901.
        val frames = mapOf(
            "down" to ApprovedSprites.player,
            "up" to ApprovedSprites.playerUp,
            "left" to ApprovedSprites.playerLeft,
            "right" to ApprovedSprites.playerRight
        )
        frames.forEach { (name, subject) ->
            assertEquals("$name frame height", 773, subject.height)
            assertEquals("$name frame bottom row", 901, subject.y + subject.height - 1)
        }
        assertEquals(378, ApprovedSprites.player.width)   // front (master)
        assertEquals(356, ApprovedSprites.playerUp.width) // rear
        assertEquals(193, ApprovedSprites.playerLeft.width)   // profile
        assertEquals(193, ApprovedSprites.playerRight.width)  // profile
    }

    @Test
    fun directionChangeNeverMovesResizesOrReanchorsTheCharacter() {
        val g = BoardGeometry(9, 8, 48f, 4f, 6f)
        val rects = Direction.entries.associateWith {
            BoardSprites.playerFrames.rectIn(g, 3f, 2f, it)
        }
        val down = rects.getValue(Direction.DOWN)
        rects.forEach { (facing, rect) ->
            assertEquals("$facing height", down.height, rect.height, 0.0001f)
            assertEquals("$facing top", down.top, rect.top, 0.0001f)
            assertEquals("$facing bottom (ground line)", down.bottom, rect.bottom, 0.0001f)
            assertEquals("$facing center x", down.centerX, rect.centerX, 0.0001f)
        }
        // profile frames are naturally narrower, never wider
        assertTrue(rects.getValue(Direction.LEFT).width < down.width)
        assertTrue(rects.getValue(Direction.RIGHT).width < down.width)
        assertEquals(rects.getValue(Direction.LEFT).width, rects.getValue(Direction.RIGHT).width, 0.0001f)
    }

    @Test
    fun downFrameReproducesTheLegacyPlayerPlacementExactly() {
        val g = BoardGeometry(9, 8, 61f, 3f, 5f)
        val legacy = BoardSprites.player.rectIn(g, 4f, 3f)
        val down = BoardSprites.playerFrames.rectIn(g, 4f, 3f, Direction.DOWN)
        assertEquals(legacy.left, down.left, 0.0001f)
        assertEquals(legacy.top, down.top, 0.0001f)
        assertEquals(legacy.width, down.width, 0.0001f)
        assertEquals(legacy.height, down.height, 0.0001f)
    }

    @Test
    fun characterFramesRejectFramesThatWouldChangeTheScale() {
        val mismatchedHeight = mapOf(
            Direction.DOWN to ApprovedSprites.player,
            Direction.UP to ApprovedSprites.playerUp,
            Direction.LEFT to ApprovedSprites.playerLeft,
            Direction.RIGHT to SpriteSubject(411, 129, 193, 700)
        )
        val thrown = try {
            CharacterFrames(ApprovedSprites.player, 0.42f, mismatchedHeight)
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue(thrown != null)
    }

    @Test
    fun animationPoseFramesShareTheMasterHeightAndGroundLine() {
        // The 12 approved animation frames (walk A/B + push, four directions)
        // are pinned from tools/png_profile soft bboxes (alpha > 16) on the
        // md5-verified deliveries: every frame normalized to the masters'
        // subject constants — y = 129, height = 773, bottom row = 901.
        val poseSubjects = mapOf(
            "walk_down_a" to ApprovedSprites.walkDownA,
            "walk_down_b" to ApprovedSprites.walkDownB,
            "walk_up_a" to ApprovedSprites.walkUpA,
            "walk_up_b" to ApprovedSprites.walkUpB,
            "walk_left_a" to ApprovedSprites.walkLeftA,
            "walk_left_b" to ApprovedSprites.walkLeftB,
            "walk_right_a" to ApprovedSprites.walkRightA,
            "walk_right_b" to ApprovedSprites.walkRightB,
            "push_down" to ApprovedSprites.pushDown,
            "push_up" to ApprovedSprites.pushUp,
            "push_left" to ApprovedSprites.pushLeft,
            "push_right" to ApprovedSprites.pushRight
        )
        assertEquals(12, poseSubjects.size)
        poseSubjects.forEach { (name, subject) ->
            assertEquals("$name height", 773, subject.height)
            assertEquals("$name bottom row", 901, subject.y + subject.height - 1)
        }
    }

    @Test
    fun everyPoseHasOneFramePerDirectionAndNoSilentFallback() {
        val frames = BoardSprites.playerFrames
        CharacterPose.entries.forEach { pose ->
            Direction.entries.forEach { facing ->
                assertEquals(
                    "$pose/$facing frame must be a dedicated measured subject",
                    773,
                    frames.subjectFor(facing, pose).height
                )
            }
        }
        // The production set is complete: each pose map covers all directions
        // (CharacterFrames would otherwise fall back to the idle frame).
        assertEquals(4, frames.walkA.size)
        assertEquals(4, frames.walkB.size)
        assertEquals(4, frames.pushFrames.size)
        // Stride frames must differ from each other — an A/B pair that is
        // accidentally identical would freeze the alternating-leg illusion.
        Direction.entries.forEach { facing ->
            assertTrue(
                "walk A and B must differ for $facing",
                frames.subjectFor(facing, CharacterPose.STRIDE_A) !=
                    frames.subjectFor(facing, CharacterPose.STRIDE_B)
            )
        }
    }

    @Test
    fun poseChangeNeverMovesOrResizesTheCharacter() {
        val g = BoardGeometry(9, 8, 48f, 4f, 6f)
        Direction.entries.forEach { facing ->
            val idle = BoardSprites.playerFrames.rectIn(g, 3f, 2f, facing, CharacterPose.IDLE)
            CharacterPose.entries.forEach { pose ->
                val rect = BoardSprites.playerFrames.rectIn(g, 3f, 2f, facing, pose)
                assertEquals("$facing/$pose height", idle.height, rect.height, 0.0001f)
                assertEquals("$facing/$pose top", idle.top, rect.top, 0.0001f)
                assertEquals("$facing/$pose ground line", idle.bottom, rect.bottom, 0.0001f)
                assertEquals("$facing/$pose center x", idle.centerX, rect.centerX, 0.0001f)
            }
        }
    }

    @Test
    fun poseWidthsFollowTheMeasuredArtwork() {
        val g = BoardGeometry(9, 8, 48f, 4f, 6f)
        val scale = 0.42f * 48f / 378f // footprint * cell / master width
        fun width(facing: Direction, pose: CharacterPose) =
            BoardSprites.playerFrames.rectIn(g, 3f, 2f, facing, pose).width
        // Only the DRAWN WIDTH follows the pose art, on the one shared scale:
        // pinned from the measured soft bboxes of the approved deliveries
        // (idle down 378, walk_down_b 324, walk_right_a 491, push_right 708).
        // Stride frames may be wider or narrower than idle depending on the
        // art; height, ground line and center never move (see the invariant
        // test above).
        assertEquals(378 * scale, width(Direction.DOWN, CharacterPose.IDLE), 0.0001f)
        assertEquals(324 * scale, width(Direction.DOWN, CharacterPose.STRIDE_B), 0.0001f)
        assertEquals(491 * scale, width(Direction.RIGHT, CharacterPose.STRIDE_A), 0.0001f)
        assertEquals(708 * scale, width(Direction.RIGHT, CharacterPose.PUSH), 0.0001f)
    }

    @Test
    fun characterFramesRejectPoseFramesThatWouldChangeTheScale() {
        val mismatchedPose = mapOf(
            Direction.DOWN to ApprovedSprites.walkDownA,
            Direction.UP to ApprovedSprites.walkUpA,
            Direction.LEFT to ApprovedSprites.walkLeftA,
            Direction.RIGHT to SpriteSubject(262, 129, 491, 700)
        )
        val thrown = try {
            CharacterFrames(
                ApprovedSprites.player,
                0.42f,
                mapOf(
                    Direction.DOWN to ApprovedSprites.player,
                    Direction.UP to ApprovedSprites.playerUp,
                    Direction.LEFT to ApprovedSprites.playerLeft,
                    Direction.RIGHT to ApprovedSprites.playerRight
                ),
                walkA = mismatchedPose
            )
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue(thrown != null)
    }

    @Test
    fun missingPoseSetsFallBackToTheIdleFrame() {
        // Frames sets are optional (e.g. minimal test fixtures): a missing
        // pose must reuse the idle frame, never crash or invent geometry.
        val idleOnly = CharacterFrames(
            ApprovedSprites.player,
            0.42f,
            mapOf(
                Direction.DOWN to ApprovedSprites.player,
                Direction.UP to ApprovedSprites.playerUp,
                Direction.LEFT to ApprovedSprites.playerLeft,
                Direction.RIGHT to ApprovedSprites.playerRight
            )
        )
        Direction.entries.forEach { facing ->
            assertEquals(
                idleOnly.subjectFor(facing, CharacterPose.IDLE),
                idleOnly.subjectFor(facing, CharacterPose.STRIDE_A)
            )
            assertEquals(
                idleOnly.rectIn(geom(9, 8), 3f, 2f, facing, CharacterPose.IDLE),
                idleOnly.rectIn(geom(9, 8), 3f, 2f, facing, CharacterPose.PUSH)
            )
        }
    }
}
