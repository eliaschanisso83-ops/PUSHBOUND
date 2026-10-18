package com.pushbound.game.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pushbound.game.R
import com.pushbound.game.engine.Direction
import kotlin.math.roundToInt

/**
 * About ~6% luminance drop, no hue shift. This is the documented runtime
 * counterpart of the approved pressed state, used only while a finger is down.
 */
private val PressedDarken = ColorFilter.colorMatrix(
    ColorMatrix().apply { setToScale(0.94f, 0.94f, 0.94f, 1f) }
)

private const val SINK_FRACTION = 0.02f

@Composable
fun ControlsBar(controller: GameController, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        val dpadSize: Dp = minOf(220.dp, maxWidth * 0.52f)
        Column(
            modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                DPad(controller, Modifier.size(dpadSize))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(
                        resId = R.drawable.ui_button_undo,
                        label = "Undo",
                        enabled = controller.canUndo,
                        onClick = controller::undo
                    )
                    SecondaryButton(
                        resId = R.drawable.ui_button_restart,
                        label = "Restart",
                        enabled = true,
                        onClick = controller::restart
                    )
                }
            }
        }
    }
}

private data class ImageRegion(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * One shared artwork for all four directions. Touch targets are the generous
 * thirds of the pad (independent of where the arrow pixels are), and pressed
 * feedback is per-direction: the approved pressed artwork for DOWN, and the
 * documented darken+sink treatment for UP/LEFT/RIGHT.
 */
@Composable
private fun DPad(controller: GameController, modifier: Modifier = Modifier) {
    val normal = rememberGameBitmap(R.drawable.ui_dpad_normal)
    val pressedArtwork = rememberGameBitmap(R.drawable.ui_dpad_pressed)

    val upInteraction = remember { MutableInteractionSource() }
    val leftInteraction = remember { MutableInteractionSource() }
    val downInteraction = remember { MutableInteractionSource() }
    val rightInteraction = remember { MutableInteractionSource() }
    val upPressed by upInteraction.collectIsPressedAsState()
    val leftPressed by leftInteraction.collectIsPressedAsState()
    val downPressed by downInteraction.collectIsPressedAsState()
    val rightPressed by rightInteraction.collectIsPressedAsState()

    val baseImage = if (downPressed) pressedArtwork else normal

    Box(modifier) {
        Image(
            bitmap = baseImage,
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )

        if (upPressed || leftPressed || rightPressed) {
            Canvas(Modifier.fillMaxSize()) {
                val scaleX = size.width / baseImage.width
                val scaleY = size.height / baseImage.height
                val sink = (size.height * SINK_FRACTION).roundToInt()
                val regions = thirdsOf(baseImage)

                fun drawDarkened(direction: Direction) {
                    val region = regions.getValue(direction)
                    drawImage(
                        image = baseImage,
                        srcOffset = IntOffset(region.x, region.y),
                        srcSize = IntSize(region.width, region.height),
                        dstOffset = IntOffset(
                            (region.x * scaleX).roundToInt(),
                            (region.y * scaleY).roundToInt() + sink
                        ),
                        dstSize = IntSize(
                            (region.width * scaleX).roundToInt(),
                            (region.height * scaleY).roundToInt()
                        ),
                        colorFilter = PressedDarken
                    )
                }

                if (upPressed) drawDarkened(Direction.UP)
                if (leftPressed) drawDarkened(Direction.LEFT)
                if (rightPressed) drawDarkened(Direction.RIGHT)
            }
        }

        val regionWidth = 1f / 3f
        val regionHeight = 1f / 2f
        DPadRegion(
            Direction.UP,
            Modifier.align(Alignment.TopCenter).fillMaxWidth(regionWidth).fillMaxHeight(regionHeight),
            upInteraction,
            controller
        )
        DPadRegion(
            Direction.LEFT,
            Modifier.align(Alignment.BottomStart).fillMaxWidth(regionWidth).fillMaxHeight(regionHeight),
            leftInteraction,
            controller
        )
        DPadRegion(
            Direction.DOWN,
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(regionWidth).fillMaxHeight(regionHeight),
            downInteraction,
            controller
        )
        DPadRegion(
            Direction.RIGHT,
            Modifier.align(Alignment.BottomEnd).fillMaxWidth(regionWidth).fillMaxHeight(regionHeight),
            rightInteraction,
            controller
        )
    }
}

private fun thirdsOf(image: ImageBitmap): Map<Direction, ImageRegion> {
    val width = image.width
    val height = image.height
    val third = width / 3
    val half = height / 2
    return mapOf(
        Direction.UP to ImageRegion(third, 0, third, half),
        Direction.LEFT to ImageRegion(0, half, third, height - half),
        Direction.DOWN to ImageRegion(third, half, width - 2 * third, height - half),
        Direction.RIGHT to ImageRegion(width - third, half, third, height - half)
    )
}

@Composable
private fun DPadRegion(
    direction: Direction,
    modifier: Modifier,
    interactionSource: MutableInteractionSource,
    controller: GameController
) {
    Box(
        modifier = modifier
            .clickable(interactionSource = interactionSource, indication = null) {
                controller.onDirectionPressed(direction)
            }
            .semantics { contentDescription = "Move ${direction.name.lowercase()}" }
    )
}

@Composable
private fun SecondaryButton(
    resId: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val bitmap = rememberGameBitmap(resId)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val active = pressed && enabled

    Box(
        Modifier
            .size(72.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = label }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sink = if (active) (size.height * SINK_FRACTION).roundToInt() else 0
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(0, sink),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                alpha = if (enabled) 1f else 0.45f,
                colorFilter = if (active) PressedDarken else null
            )
        }
    }
}
