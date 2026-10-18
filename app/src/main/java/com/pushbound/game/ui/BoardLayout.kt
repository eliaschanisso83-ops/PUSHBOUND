package com.pushbound.game.ui

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

    // Wall internals, located with tools/png_rowprofile.sh (per-row brightness):
    // mortar lines sit at y=392..395 (joint under the cap) and y=529/576/619
    // (course joints of the lower face).
    const val WALL_CAP_TOP = 332
    const val WALL_CAP_BOTTOM = 395
    const val WALL_FACE_TOP = 529
    const val WALL_FACE_BOTTOM = 619
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
}

/**
 * Wall rendering: the approved wall art is a full-width brick strip (one lit
 * cap course + face courses). To make ONE WALL TILE = ONE GRID CELL with no
 * gaps in either horizontal or vertical runs (section 4/12), each wall cell
 * is filled with the face crop tiled vertically at its natural course pitch,
 * phase-anchored GLOBALLY (board coordinate space) so vertically adjacent
 * wall cells continue the brick pattern exactly. The cap course is drawn once
 * at the exposed end of a wall run.
 *
 * No stretching: the face tiles keep the art's own course height; only the
 * uniform cell scale applies.
 */
object WallTiling {
    /** Face tile pitch for a given cell size (art course height × cell scale). */
    fun facePitch(cellSize: Float): Float =
        (ApprovedSprites.WALL_FACE_BOTTOM - ApprovedSprites.WALL_FACE_TOP) * cellSize / ApprovedSprites.FRAME

    /** Cap strip height for a given cell size. */
    fun capHeight(cellSize: Float): Float =
        (ApprovedSprites.WALL_CAP_BOTTOM - ApprovedSprites.WALL_CAP_TOP) * cellSize / ApprovedSprites.FRAME

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
