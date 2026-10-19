package com.pushbound.game.ui

import com.pushbound.game.engine.Direction
import kotlin.math.min

/**
 * The authoritative cell -> screen mapping. ONE GRID CELL IS THE SINGLE
 * SPATIAL UNIT OF THE GAME: every floor tile, wall, box, goal, player
 * position, animation step and collision check is derived from this single
 * geometry. There is no second coordinate system.
 *
 * screenX = originX + column * cellSize
 * screenY = originY + row    * cellSize
 *
 * Cells are always square (never stretched); the runtime cell size may vary
 * with the available board area, but the relationship between all elements
 * is constant.
 */
data class BoardGeometry(
    val columns: Int,
    val rows: Int,
    val cellSize: Float,
    val originX: Float,
    val originY: Float
) {
    fun cellLeft(col: Float): Float = originX + col * cellSize
    fun cellTop(row: Float): Float = originY + row * cellSize
    fun cellCenterX(col: Float): Float = originX + (col + 0.5f) * cellSize
    fun cellCenterY(row: Float): Float = originY + (row + 0.5f) * cellSize

    val boardWidth: Float get() = cellSize * columns
    val boardHeight: Float get() = cellSize * rows

    companion object {
        /** Fits square cells into the available area, board centered. */
        fun fit(availableWidth: Float, availableHeight: Float, columns: Int, rows: Int): BoardGeometry {
            require(columns > 0 && rows > 0) { "Board must have at least one cell" }
            require(availableWidth > 0f && availableHeight > 0f) { "Available area must be positive" }
            val cell = min(availableWidth / columns, availableHeight / rows)
            return BoardGeometry(
                columns = columns,
                rows = rows,
                cellSize = cell,
                originX = (availableWidth - cell * columns) / 2f,
                originY = (availableHeight - cell * rows) / 2f
            )
        }
    }
}

/** Rectangle in canvas (pixel) space. */
data class CellRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

/**
 * Content bounding box of the visible subject inside an approved sprite's
 * 1024x1024 frame. Measured from the approved artwork with tools/png_bbox.sh
 * (alpha > 16). The assets carry different internal transparent margins, so
 * the renderer maps each SUBJECT into its cell instead of blindly drawing the
 * whole frame (section 16: account for internal margins during rendering).
 */
data class SpriteSubject(val x: Int, val y: Int, val width: Int, val height: Int)

/** Measured subject rects and internal crops of the approved Milestone 1 art. */
object ApprovedSprites {
    const val FRAME = 1024

    val floor = SpriteSubject(0, 0, 1024, 1024)          // opaque, fills the frame exactly
    val wall = SpriteSubject(0, 332, 1024, 327)          // full-width brick strip
    val box = SpriteSubject(154, 146, 694, 694)
    val goal = SpriteSubject(105, 102, 807, 819)
    val player = SpriteSubject(319, 129, 378, 773)

    // Directional character frames (Milestone 5). The approved master above
    // is the DOWN frame; the other three were generated from it, processed
    // and normalized to the master's exact constants (subject height 773,
    // subject bottom row 901, subject cx ~507.5 in the 1024 frame), measured
    // with tools/png_bbox.sh after processing:
    val playerUp = SpriteSubject(330, 129, 356, 773)      // rear view, shoulders slightly narrower
    val playerLeft = SpriteSubject(411, 129, 193, 773)    // profile; intrinsically half as wide
    val playerRight = SpriteSubject(411, 129, 193, 773)   // profile; identical metrics to left

    // Wall internals, located with tools/png_profile.c (per-row/column mean
    // brightness on the approved strip). The material bands are:
    //   cap course   y[332..391]  large lit bricks seen slightly from above
    //   mortar       y[392..395]  bright peak after the dark recess (b~82→170)
    //   hero course  y[396..491]  bright fired-clay brick face (b~125)
    //   AO shadow    y[492..525]  the course's recessed under-edge (b~77)
    //   next mortar  y[526..531]  peak at 529
    // One full masonry course period = 137px: mortar + brick face + AO
    // under-shadow, i.e. exactly what makes a course read as a physical
    // brick with recessed mortar. The small courses below y=532 live in the
    // art's floor AO gradient (b~58-78); tiling THAT band (the old
    // y[529..619] crop) crushed the material into dark thin lines at cell
    // scale and must not be used. Vertical brick joints measured at
    // x≈60/247/423/601/777/945 (~177px period, natural variation); the
    // frame seam splits one brick, so the strip tiles horizontally with a
    // believable joint at cell boundaries.
    const val WALL_CAP_TOP = 332
    const val WALL_CAP_BOTTOM = 395
    const val WALL_FACE_TOP = 392
    const val WALL_FACE_BOTTOM = 529
    const val WALL_BRICK_BOND = 88  // half of the ~177px brick period
}

/** Where the subject anchors inside its cell. */
enum class Anchor {
    /** Floor-plane objects (goal pad): subject center = cell center. */
    FLOOR_PLANE,

    /**
     * Standing objects (wall face, box, player): subject bottom edge sits on
     * the cell's ground line, which is the cell's bottom edge. The unified
     * ground line is what makes wall, box and player read as one spatial
     * system (master tile: bottom-center anchor).
     */
    GROUND_LINE
}

/**
 * How one asset's subject maps into one cell. footprint is the visible
 * subject width as a fraction of the cell; height follows from the measured
 * subject aspect (no independent vertical scaling, no arbitrary stretching).
 */
data class SpritePlacement(
    val subject: SpriteSubject,
    val footprint: Float,
    val anchor: Anchor
) {
    /** Screen rect of this sprite standing on the given logical cell. */
    fun rectIn(geometry: BoardGeometry, col: Float, row: Float): CellRect {
        val width = footprint * geometry.cellSize
        val height = width * subject.height / subject.width
        val left = geometry.cellCenterX(col) - width / 2f
        val top = when (anchor) {
            Anchor.FLOOR_PLANE -> geometry.cellCenterY(row) - height / 2f
            Anchor.GROUND_LINE -> geometry.cellTop(row + 1) - height
        }
        return CellRect(left, top, width, height)
    }
}

/**
 * The four directional frames of the ONE approved character. All frames were
 * normalized to the master's exact subject constants (height 773, bottom row
 * 901, cx ~507.5), so the set shares ONE scale, ONE ground line and ONE
 * center. To guarantee the character never jumps in size or position when the
 * facing changes (direction change = sprite change), every frame is scaled by
 * the SAME factor — derived from the master subject and its footprint, never
 * from an individual frame's own width — and placed by its own measured
 * subject rect on the shared bottom-center anchor. Profile frames are
 * intrinsically narrower; their height, baseline and center are identical.
 */
data class CharacterFrames(
    val scaleSubject: SpriteSubject,
    val footprint: Float,
    val frames: Map<Direction, SpriteSubject>
) {
    init {
        require(Direction.entries.all { it in frames }) {
            "CharacterFrames needs one subject per direction"
        }
        val referenceHeight = scaleSubject.height
        frames.values.forEach { subject ->
            require(subject.height == referenceHeight) {
                "All directional frames must share the master subject height " +
                    "($referenceHeight) so direction changes cannot resize the character"
            }
        }
    }

    fun subjectFor(facing: Direction): SpriteSubject = frames.getValue(facing)

    fun rectIn(geometry: BoardGeometry, col: Float, row: Float, facing: Direction): CellRect {
        val scale = footprint * geometry.cellSize / scaleSubject.width
        val subject = frames.getValue(facing)
        val width = subject.width * scale
        val height = subject.height * scale
        val left = geometry.cellCenterX(col) - width / 2f
        val top = geometry.cellTop(row + 1) - height
        return CellRect(left, top, width, height)
    }
}

/**
 * Gameplay footprints. Values chosen against the measured subjects:
 * wall = full cell width so adjacent wall cells connect continuously;
 * box = 0.74 (controlled margin, clearly one cell); goal = 0.86 (a flat pad,
 * subordinate in weight to the cube, but wide enough that its rim peeks out
 * from under a box standing on it); player = 0.42 (human silhouette keeps
 * breathing room; feet establish exactly one cell).
 */
object BoardSprites {
    val floor = SpritePlacement(ApprovedSprites.floor, 1.00f, Anchor.FLOOR_PLANE)
    val box = SpritePlacement(ApprovedSprites.box, 0.74f, Anchor.GROUND_LINE)
    val goal = SpritePlacement(ApprovedSprites.goal, 0.86f, Anchor.FLOOR_PLANE)
    val player = SpritePlacement(ApprovedSprites.player, 0.42f, Anchor.GROUND_LINE)

    /** Directional character set: master = DOWN/scale reference. */
    val playerFrames = CharacterFrames(
        scaleSubject = ApprovedSprites.player,
        footprint = 0.42f,
        frames = mapOf(
            Direction.DOWN to ApprovedSprites.player,
            Direction.UP to ApprovedSprites.playerUp,
            Direction.LEFT to ApprovedSprites.playerLeft,
            Direction.RIGHT to ApprovedSprites.playerRight
        )
    )
}

/**
 * Wall rendering: the approved wall art is a full-width masonry strip (one
 * lit cap course + hero face course + AO-shadowed lower courses). To make
 * ONE WALL TILE = ONE GRID CELL with no gaps in either horizontal or
 * vertical runs (section 4/12), each wall cell is filled with the FACE
 * crop tiled vertically at its natural course pitch (one full brick
 * course: mortar + face + recessed under-shadow), phase-anchored GLOBALLY
 * (board coordinate space) so vertically adjacent wall cells continue the
 * brick pattern exactly. Alternate courses are shifted half a brick
 * (running bond) on the same global parity. The cap course is drawn once
 * at the exposed end of a wall run.
 *
 * Geometry (footprint, cell mapping, cap semantics, continuity) is the
 * corrected grid system and stays untouched; only the material band the
 * face samples was corrected to the art's hero brick course.
 *
 * No stretching: the face tiles keep the art's own course height; only the
 * uniform cell scale applies.
 */
object WallTiling {
    /** Face tile pitch for a given cell size (one art course period × cell scale). */
    fun facePitch(cellSize: Float): Float =
        (ApprovedSprites.WALL_FACE_BOTTOM - ApprovedSprites.WALL_FACE_TOP) * cellSize / ApprovedSprites.FRAME

    /** Cap strip height for a given cell size. */
    fun capHeight(cellSize: Float): Float =
        (ApprovedSprites.WALL_CAP_BOTTOM - ApprovedSprites.WALL_CAP_TOP) * cellSize / ApprovedSprites.FRAME

    /** Half-brick running-bond offset for a course (art px × cell scale). */
    fun bondOffset(cellSize: Float): Float =
        ApprovedSprites.WALL_BRICK_BOND * cellSize / ApprovedSprites.FRAME

    /**
     * Whether the course with global index m is drawn shifted half a brick
     * (running bond, as the approved art stacks its own courses). Parity is
     * computed on the GLOBAL course index, so every wall cell derives the
     * same alternation for the same canvas row: vertical runs keep both the
     * pattern phase and the bond alternation across cell boundaries.
     */
    fun isBondOffsetCourse(m: Int): Boolean = (((m % 2) + 2) % 2) == 1

    /**
     * Tile indices (m) whose band [anchorY + m*pitch, anchorY + (m+1)*pitch]
     * overlaps the given cell. anchorY is the global phase anchor in canvas
     * space (the renderer uses 0 = board top), so vertically adjacent wall
     * cells continue the brick pattern exactly — one continuous wall.
     */
    fun faceTileIndices(cellTop: Float, cellBottom: Float, pitch: Float, anchorY: Float = 0f): IntRange {
        val first = kotlin.math.floor((cellTop - anchorY) / pitch).toInt() - 1
        val last = kotlin.math.ceil((cellBottom - anchorY) / pitch).toInt() + 1
        return first..last
    }
}
