package com.pushbound.game.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushbound.game.R
import com.pushbound.game.engine.Position
import kotlin.math.roundToInt

/**
 * Milestone 4 grid foundation. ONE GRID CELL IS THE SINGLE SPATIAL UNIT:
 * [BoardGeometry] derives every placement from one authoritative cell size,
 * and each approved sprite's measured SUBJECT (not its full 1024² frame) is
 * mapped into its cell via [BoardSprites] footprints. Walls fill one cell of
 * footprint with the brick face tiled at the art's natural course pitch
 * (global phase, so vertical runs are continuous) and a lit cap course at
 * exposed run ends. See BoardLayout.kt for the measured source values.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun BoardView(controller: GameController, modifier: Modifier = Modifier) {
    val level = controller.level
    val floor = rememberGameBitmap(R.drawable.environment_floor_base)
    val wall = rememberGameBitmap(R.drawable.environment_wall_straight)
    val goal = rememberGameBitmap(R.drawable.object_goal)
    val box = rememberGameBitmap(R.drawable.object_box)
    val player = rememberGameBitmap(R.drawable.character_idle)
    val textMeasurer = rememberTextMeasurer()

    val state = controller.state

    val playerTarget = Offset(state.player.col.toFloat(), state.player.row.toFloat())
    val playerAnim = remember { Animatable(playerTarget, Offset.VectorConverter) }
    var lastEpoch by remember { mutableStateOf(controller.resetEpoch) }
    LaunchedEffect(playerTarget, controller.resetEpoch) {
        if (lastEpoch != controller.resetEpoch) {
            lastEpoch = controller.resetEpoch
            playerAnim.snapTo(playerTarget)
        } else {
            playerAnim.animateTo(
                playerTarget,
                tween(GameController.MOVE_ANIMATION_MS.toInt(), easing = FastOutSlowInEasing)
            )
        }
    }

    val push = controller.lastPush
    var activePush by remember { mutableStateOf<PushAnim?>(null) }
    val boxAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(push?.id, controller.resetEpoch) {
        val current = push
        if (current == null) {
            activePush = null
        } else {
            activePush = current
            boxAnim.snapTo(Offset(current.from.col.toFloat(), current.from.row.toFloat()))
            boxAnim.animateTo(
                Offset(current.to.col.toFloat(), current.to.row.toFloat()),
                tween(GameController.MOVE_ANIMATION_MS.toInt(), easing = FastOutSlowInEasing)
            )
        }
    }

    BoxWithConstraints(modifier) {
        val geometry = BoardGeometry.fit(
            availableWidth = maxWidth.toPx(),
            availableHeight = maxHeight.toPx(),
            columns = level.width,
            rows = level.height
        )
        if (geometry.cellSize > 0f) {
            Canvas(
                Modifier
                    .align(Alignment.Center)
                    .size(geometry.boardWidth.toDp(), geometry.boardHeight.toDp())
            ) {
                val wallSubset = level.walls

                // --- floor: exact cell fill, tiles edge to edge -------------
                for (row in 0 until level.height) {
                    for (col in 0 until level.width) {
                        val l = geometry.cellLeft(col.toFloat()).roundToInt()
                        val t = geometry.cellTop(row.toFloat()).roundToInt()
                        val r = geometry.cellLeft((col + 1).toFloat()).roundToInt()
                        val b = geometry.cellTop((row + 1).toFloat()).roundToInt()
                        floor.drawSubject(this, l, t, r - l, b - t)
                    }
                }

                // --- goals: floor-plane, centered on the cell center ---------
                for (goalPosition in level.goals) {
                    BoardSprites.goal.draw(this, goal, geometry, goalPosition.col.toFloat(), goalPosition.row.toFloat())
                }

                // --- walls: one cell footprint, tiled face + cap at run ends -
                val pitch = WallTiling.facePitch(geometry.cellSize)
                    .let { if (it > 0f) it else 1f }
                for (wallPosition in wallSubset) {
                    val l = geometry.cellLeft(wallPosition.col.toFloat()).roundToInt()
                    val t = geometry.cellTop(wallPosition.row.toFloat()).roundToInt()
                    val r = geometry.cellLeft((wallPosition.col + 1).toFloat()).roundToInt()
                    val b = geometry.cellTop((wallPosition.row + 1).toFloat()).roundToInt()
                    clipRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()) {
                        for (m in WallTiling.faceTileIndices(t.toFloat(), b.toFloat(), pitch)) {
                            val top = (m * pitch).roundToInt()
                            val bottom = ((m + 1) * pitch).roundToInt()
                            wall.drawSubject(
                                this, l, top, r - l, bottom - top,
                                srcX = 0, srcY = ApprovedSprites.WALL_FACE_TOP,
                                srcW = ApprovedSprites.FRAME,
                                srcH = ApprovedSprites.WALL_FACE_BOTTOM - ApprovedSprites.WALL_FACE_TOP
                            )
                        }
                        val above = Position(wallPosition.row - 1, wallPosition.col) in wallSubset
                        val below = Position(wallPosition.row + 1, wallPosition.col) in wallSubset
                        if (!above || !below) {
                            wall.drawSubject(
                                this, l, t, r - l, WallTiling.capHeight(geometry.cellSize).roundToInt(),
                                srcX = 0, srcY = ApprovedSprites.WALL_CAP_TOP,
                                srcW = ApprovedSprites.FRAME,
                                srcH = ApprovedSprites.WALL_CAP_BOTTOM - ApprovedSprites.WALL_CAP_TOP
                            )
                        }
                    }
                }

                // --- boxes: ground-line anchored, animated in grid coords ---
                for (boxPosition in state.boxes) {
                    val animated = activePush?.to == boxPosition
                    val colF: Float
                    val rowF: Float
                    if (activePush != null && animated) {
                        colF = boxAnim.value.x
                        rowF = boxAnim.value.y
                    } else {
                        colF = boxPosition.col.toFloat()
                        rowF = boxPosition.row.toFloat()
                    }
                    BoardSprites.box.draw(this, box, geometry, colF, rowF)
                }

                // --- player: ground-line anchored, animated in grid coords --
                BoardSprites.player.draw(this, player, geometry, playerAnim.value.x, playerAnim.value.y)

                // --- development-only grid overlay (off by default) ---------
                if (DebugGridConfig.ENABLED) {
                    drawDebugGrid(geometry, level.width, level.height, state.player, state.boxes, wallSubset, level.goals, textMeasurer)
                }
            }
        }
    }
}

/** Draws an ImageBitmap region into an integer rect. */
private fun ImageBitmap.drawSubject(
    scope: DrawScope, left: Int, top: Int, width: Int, height: Int,
    srcX: Int = ApprovedSprites.floor.x, srcY: Int = ApprovedSprites.floor.y,
    srcW: Int = ApprovedSprites.floor.width, srcH: Int = ApprovedSprites.floor.height
) {
    scope.drawImage(
        image = this,
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(srcW, srcH),
        dstOffset = IntOffset(left, top),
        dstSize = IntSize(width, height)
    )
}

/** Places one sprite's subject into the logical cell via its footprint. */
private fun SpritePlacement.draw(scope: DrawScope, image: ImageBitmap, geometry: BoardGeometry, col: Float, row: Float) {
    val rect = rectIn(geometry, col, row)
    scope.drawImage(
        image = image,
        srcOffset = IntOffset(subject.x, subject.y),
        srcSize = IntSize(subject.width, subject.height),
        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt())
    )
}

/** Debug overlay: cell boundaries, board outline, semantic tints, coordinates. */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawDebugGrid(
    geometry: BoardGeometry,
    columns: Int,
    rows: Int,
    player: Position,
    boxes: List<Position>,
    walls: Set<Position>,
    goals: Set<Position>,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    fun cellOffset(c: Int, r: Int) = Offset(geometry.cellLeft(c.toFloat()), geometry.cellTop(r.toFloat()))
    fun cellSize() = Size(geometry.cellSize, geometry.cellSize)

    for (w in walls) drawRect(Color(0x33FF9500), cellOffset(w.col, w.row), cellSize())
    for (g in goals) drawRect(Color(0x33FFD60A), cellOffset(g.col, g.row), cellSize())
    for (b in boxes) drawRect(Color(0x3330D158), cellOffset(b.col, b.row), cellSize())
    drawRect(Color(0x330A84FF), cellOffset(player.col, player.row), cellSize())

    val line = Color(0xAAFF3B30)
    for (r in 0..rows) {
        val y = geometry.cellTop(r.toFloat())
        drawLine(line, Offset(0f, y), Offset(geometry.boardWidth, y), strokeWidth = 1f)
    }
    for (c in 0..columns) {
        val x = geometry.cellLeft(c.toFloat())
        drawLine(line, Offset(x, 0f), Offset(x, geometry.boardHeight), strokeWidth = 1f)
    }

    for (r in 0 until rows) {
        for (c in 0 until columns) {
            drawText(
                textMeasurer = textMeasurer,
                text = "$c,$r",
                topLeft = cellOffset(c, r) + Offset(3f, 2f),
                style = TextStyle(color = Color(0xCCFF3B30), fontSize = 9.sp)
            )
        }
    }
}
