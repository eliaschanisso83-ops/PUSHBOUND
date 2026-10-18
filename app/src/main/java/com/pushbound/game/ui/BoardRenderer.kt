package com.pushbound.game.ui

import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pushbound.game.R
import kotlin.math.roundToInt

/**
 * Stamps the approved assets onto the logical grid. Every sprite is authored on
 * the same 128px master tile with a bottom-center anchor, so drawing each one
 * into exactly its own cell keeps footprints, perspective and contact shadows
 * aligned without any per-asset pixel math.
 */
@Composable
fun BoardView(controller: GameController, modifier: Modifier = Modifier) {
    val level = controller.level
    val context = LocalContext.current
    val floor = remember(context) {
        BitmapFactory.decodeResource(context.resources, R.drawable.environment_floor_base).asImageBitmap()
    }
    val wall = remember(context) {
        BitmapFactory.decodeResource(context.resources, R.drawable.environment_wall_straight).asImageBitmap()
    }
    val goal = remember(context) {
        BitmapFactory.decodeResource(context.resources, R.drawable.object_goal).asImageBitmap()
    }
    val box = remember(context) {
        BitmapFactory.decodeResource(context.resources, R.drawable.object_box).asImageBitmap()
    }
    val player = remember(context) {
        BitmapFactory.decodeResource(context.resources, R.drawable.character_idle).asImageBitmap()
    }

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
        val cell = minOf(maxWidth / level.width, maxHeight / level.height)
        if (cell > 0.dp) {
            Canvas(
                Modifier
                    .align(Alignment.Center)
                    .size(cell * level.width, cell * level.height)
            ) {
                val cellPx = cell.roundToPx()
                val cellSize = IntSize(cellPx, cellPx)

                fun dstOf(col: Float, row: Float) =
                    IntOffset((col * cellPx).roundToInt(), (row * cellPx).roundToInt())

                // Floor under every cell keeps the board visually continuous.
                for (row in 0 until level.height) {
                    for (col in 0 until level.width) {
                        drawImage(
                            image = floor,
                            dstOffset = dstOf(col.toFloat(), row.toFloat()),
                            dstSize = cellSize
                        )
                    }
                }
                for (goalPosition in level.goals) {
                    drawImage(
                        image = goal,
                        dstOffset = dstOf(goalPosition.col.toFloat(), goalPosition.row.toFloat()),
                        dstSize = cellSize
                    )
                }
                for (wallPosition in level.walls) {
                    drawImage(
                        image = wall,
                        dstOffset = dstOf(wallPosition.col.toFloat(), wallPosition.row.toFloat()),
                        dstSize = cellSize
                    )
                }
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
                    drawImage(image = box, dstOffset = dstOf(colF, rowF), dstSize = cellSize)
                }

                val playerOffset = playerAnim.value
                drawImage(
                    image = player,
                    dstOffset = dstOf(playerOffset.x, playerOffset.y),
                    dstSize = cellSize
                )
            }
        }
    }
}
