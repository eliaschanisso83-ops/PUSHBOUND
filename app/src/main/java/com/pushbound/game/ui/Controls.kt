package com.pushbound.game.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Bottom control area (layout only):
 *
 *   [ Redo ]   [ UP ]   [ Undo ]
 *   [ Hint ] [ LEFT ] [ DOWN ] [ RIGHT ] [ Restart ]
 *
 * The movement buttons form a shallow single-row rectangle — UP on top,
 * and LEFT / DOWN / RIGHT in one generously spaced horizontal row — centered
 * on the bar content, so UP keeps its exact screen axis and vertical
 * position. The secondary buttons flank the cross as two mirrored stacks:
 * Undo above Restart on the right (the spots the player marked in
 * red/orange), Redo above Hint on the left, level with their counterparts.
 *
 * REDO is the approved Undo artwork drawn mirrored, so it is unmistakably the
 * opposite of Undo and costs no new asset. HINT is typographic — the app's
 * own text style and palette — because art-direction forbids generating new
 * UI artwork until assets/approved/style_reference_v1.png exists; it occupies
 * the control area only and can never obstruct the board.
 *
 * Orientation only changes WHERE this bar is placed: the portrait column
 * gives it the full window width, landscape gives it a fixed-width panel on
 * the right (see [GameLayout]). Either way it is sized purely from its own
 * measured width by [ControlsLayout], so the buttons never change. System
 * bar / gesture insets are applied once by [GameScreen], not here.
 */
@Composable
fun ControlsBar(controller: GameController, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // Reference size: EXACTLY the pre-existing button sizing formula, so
        // the reorganization never changes button dimensions — plus a fit
        // cap so the full formation can never be clipped on narrow screens.
        val dpadSize: Dp = minOf(
            220.dp,
            maxWidth * 0.52f,
            ControlsLayout.fittingDpadSize(maxWidth.value).dp
        ).coerceAtLeast(1.dp)
        val contentWidth = maxWidth.value
        val formation = remember(dpadSize, contentWidth) {
            ControlsLayout.compute(contentWidth, dpadSize.value)
        }

        // One full-width formation box: the cross is centered on the content
        // and the secondaries stack beside it, all placed by the pure layout
        // math, so the gaps and alignments stay exact.
        Box(
            Modifier
                .fillMaxWidth()
                .height(formation.height.dp)
        ) {
            DPad(
                controller = controller,
                referenceSize = dpadSize,
                layout = formation.dpad,
                modifier = Modifier.offset(formation.dpadLeft.dp, formation.dpadTop.dp)
            )
            SecondaryButton(
                resId = R.drawable.ui_button_undo,
                label = "Undo",
                enabled = controller.canUndo,
                mirrored = false,
                onClick = controller::undo,
                modifier = Modifier.offset(formation.undo.left.dp, formation.undo.top.dp)
            )
            SecondaryButton(
                resId = R.drawable.ui_button_undo,
                label = "Redo",
                enabled = controller.canRedo,
                mirrored = true,
                onClick = controller::redo,
                modifier = Modifier.offset(formation.redo.left.dp, formation.redo.top.dp)
            )
            SecondaryButton(
                resId = R.drawable.ui_button_restart,
                label = "Restart",
                enabled = true,
                mirrored = false,
                onClick = controller::restart,
                modifier = Modifier.offset(formation.restart.left.dp, formation.restart.top.dp)
            )
            HintButton(
                enabled = true,
                onClick = controller::requestHint,
                modifier = Modifier.offset(formation.hint.left.dp, formation.hint.top.dp)
            )
        }
    }
}

/**
 * Four independent movement buttons arranged as a cross. Each button is a
 * slice of the shared approved artwork drawn at the app's existing sheet
 * scale, with its own touch target exactly on the visible button. Touch
 * feedback is unchanged: the approved pressed artwork for DOWN, and the
 * documented darken+sink treatment for UP/LEFT/RIGHT.
 */
@Composable
private fun DPad(
    controller: GameController,
    referenceSize: Dp,
    layout: DPadLayout,
    modifier: Modifier = Modifier
) {
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

    Box(
        modifier
            .size(layout.width.dp, layout.height.dp)
    ) {
        DirectionButton(
            direction = Direction.UP,
            normal = normal,
            pressedArtwork = pressedArtwork,
            slice = ControlArtwork.up,
            pressed = upPressed,
            usePressedArtwork = false,
            pressedSlice = null,
            referenceSize = referenceSize,
            rect = layout.up,
            interactionSource = upInteraction,
            controller = controller
        )
        DirectionButton(
            direction = Direction.LEFT,
            normal = normal,
            pressedArtwork = pressedArtwork,
            slice = ControlArtwork.left,
            pressed = leftPressed,
            usePressedArtwork = false,
            pressedSlice = null,
            referenceSize = referenceSize,
            rect = layout.left,
            interactionSource = leftInteraction,
            controller = controller
        )
        DirectionButton(
            direction = Direction.RIGHT,
            normal = normal,
            pressedArtwork = pressedArtwork,
            slice = ControlArtwork.right,
            pressed = rightPressed,
            usePressedArtwork = false,
            pressedSlice = null,
            referenceSize = referenceSize,
            rect = layout.right,
            interactionSource = rightInteraction,
            controller = controller
        )
        DirectionButton(
            direction = Direction.DOWN,
            normal = normal,
            pressedArtwork = pressedArtwork,
            slice = ControlArtwork.down,
            pressed = downPressed,
            usePressedArtwork = true,
            pressedSlice = ControlArtwork.downPressed,
            referenceSize = referenceSize,
            rect = layout.down,
            interactionSource = downInteraction,
            controller = controller
        )
    }
}

/**
 * One movement button: approved artwork slice → its positioned rect.
 * With [usePressedArtwork] (DOWN) the button swaps to the approved pressed
 * sheet, drawn at the same sheet scale and offset by the pressed sheet's
 * own art delta — this reproduces the previous whole-sheet swap
 * pixel-for-pixel. The other directions apply [PressedDarken] plus the same
 * sink the old region draw used (2% of the reference pad size).
 */
@Composable
private fun DirectionButton(
    direction: Direction,
    normal: ImageBitmap,
    pressedArtwork: ImageBitmap,
    slice: ArtworkSlice,
    pressed: Boolean,
    usePressedArtwork: Boolean,
    pressedSlice: ArtworkSlice?,
    referenceSize: Dp,
    rect: PlacedRect,
    interactionSource: MutableInteractionSource,
    controller: GameController
) {
    val showPressedArtwork = pressed && usePressedArtwork && pressedSlice != null
    val darken = pressed && !usePressedArtwork

    Box(
        Modifier
            .offset(rect.left.dp, rect.top.dp)
            .size(rect.width.dp, rect.height.dp)
            .clickable(interactionSource = interactionSource, indication = null) {
                controller.onDirectionPressed(direction)
            }
            .semantics { contentDescription = "Move ${direction.name.lowercase()}" }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (showPressedArtwork) {
                // Approved pressed artwork, same px-per-sheet scale as the
                // normal sheet (as when both sheets were drawn full-pad).
                val ps = pressedSlice!!
                val sx = size.width / slice.width.toFloat()
                drawImage(
                    image = pressedArtwork,
                    srcOffset = IntOffset(ps.x, ps.y),
                    srcSize = IntSize(ps.width, ps.height),
                    dstOffset = IntOffset(
                        ((ps.x - slice.x) * sx).roundToInt(),
                        ((ps.y - slice.y) * sx).roundToInt()
                    ),
                    dstSize = IntSize((ps.width * sx).roundToInt(), (ps.height * sx).roundToInt())
                )
            } else {
                val sink = if (darken) {
                    (referenceSize.toPx() * SINK_FRACTION).roundToInt()
                } else {
                    0
                }
                drawImage(
                    image = normal,
                    srcOffset = IntOffset(slice.x, slice.y),
                    srcSize = IntSize(slice.width, slice.height),
                    dstOffset = IntOffset(0, sink),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    colorFilter = if (darken) PressedDarken else null
                )
            }
        }
    }
}

/**
 * One artwork-backed secondary control.
 *
 * [mirrored] flips the drawn slice horizontally without moving the touch
 * target, which is how Redo is rendered: the approved Undo artwork reversed,
 * so the pair reads as "backwards" and "forwards" without inventing an asset
 * that art-direction currently forbids generating.
 */
@Composable
private fun SecondaryButton(
    resId: Int,
    label: String,
    enabled: Boolean,
    mirrored: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bitmap = rememberGameBitmap(resId)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val active = pressed && enabled

    Box(
        modifier
            .size(ControlsLayout.SECONDARY_BUTTON.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = label }
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Mirroring happens here, not on the Box, so the click
                    // bounds and the accessibility node stay exactly where the
                    // button is drawn.
                    scaleX = if (mirrored) -1f else 1f
                }
        ) {
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

/**
 * The Hint control, typographic rather than illustrated.
 *
 * Art-direction forbids generating UI artwork until
 * assets/approved/style_reference_v1.png exists, so this button is built from
 * the app's own visual language instead: the same material colours, the same
 * small uppercase letter-spaced text used by the HUD, and the same press
 * treatment (sink plus a luminance drop) as the artwork buttons beside it. It
 * lives entirely inside the control bar, so it can never cover the board.
 */
@Composable
private fun HintButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val active = pressed && enabled

    Box(
        modifier
            .size(ControlsLayout.SECONDARY_BUTTON.dp)
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.45f
                // The same 2% sink the artwork buttons use when pressed.
                translationY = if (active) size.height * SINK_FRACTION else 0f
            }
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = if (active) 0.82f else 1f),
                shape = RoundedCornerShape(14.dp)
            )
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = "Hint" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "HINT",
            color = MaterialTheme.colorScheme.onBackground.copy(
                alpha = if (active) 0.75f else 1f
            ),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.5.sp,
            maxLines = 1
        )
    }
}
