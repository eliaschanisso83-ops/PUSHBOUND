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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
                // Tappable whenever it could DO something: walk back a move,
                // or — with no credits left — open the way to earn more.
                // Gating on history alone would strand a player whose balance
                // reached zero on the very last undo, with no history left to
                // arm the button and no route back to five moves.
                enabled = controller.canUndo || controller.canRequestUndoRestoration,
                mirrored = false,
                onClick = controller::undo,
                modifier = Modifier.offset(formation.undo.left.dp, formation.undo.top.dp),
                // ALWAYS PRESENT, from five down to zero — never hidden when
                // empty, because a control that vanishes at zero teaches the
                // player nothing about where their moves went or how to get
                // them back. Red marks zero as the state worth noticing while
                // the balance itself is the number already on screen.
                badge = controller.undoCreditsRemaining.toString(),
                badgeEmphasised = controller.undoCreditsRemaining == 0,
                accessibilityLabel = controller.undoAccessibilityDescription
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
 *
 * [badge] is the live Undo credit count, drawn as a small chip in the top-end
 * corner — the ONE control that shows a number, because it is the only control
 * with a resource the player can run out of. It never replaces the artwork
 * beneath it and never hides the control, so the layout art direction approved
 * is unchanged; the chip is additive type, in the same typography as the HUD.
 *
 * [accessibilityLabel] replaces [label] for screen readers. Both are separate
 * parameters on purpose: the label is what a sighted player reads off the
 * artwork, while the description has to carry the state a sighted player gets
 * from the chip — how many moves remain, and whether Undo is usable at all.
 * The badge itself is stripped from the accessibility tree (see below) so the
 * count is not announced twice, once as bare text and once in the label.
 *
 * NAMED `accessibilityLabel` RATHER THAN `contentDescription` because inside a
 * `semantics { }` block the receiver's own `contentDescription` is the nearer
 * candidate: naming the parameter the same thing would quietly resolve the
 * right-hand side to the semantics property and self-assign it, leaving the
 * control silent instead of announcing its state.
 */
@Composable
private fun SecondaryButton(
    resId: Int,
    label: String,
    enabled: Boolean,
    mirrored: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    badgeEmphasised: Boolean = false,
    accessibilityLabel: String = label
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
            .semantics { contentDescription = accessibilityLabel }
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

        if (badge != null) {
            CreditBadge(
                value = badge,
                emphasised = badgeEmphasised,
                modifier = Modifier
                    // Top-end corner: clear of the glyph in the middle of the
                    // slice, inside the button's own bounds, so it can never
                    // grow the control or collide with its neighbour.
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            )
        }
    }
}

/**
 * The Undo credit chip: the number the player is entitled to see at all times.
 *
 * WHY A CHIP AND NOT A HOLE IN THE ARTWORK — the approved buttons cannot be
 * altered, so the count is laid OVER the artwork rather than drawn into it,
 * and it carries its own opaque background so it stays legible against
 * whatever is underneath instead of depending on it.
 *
 * WHY RED AT ZERO — the balance is still shown, still visible, and the control
 * may still be tappable (to earn more), so dimming it as if it were disabled
 * would lie. Kiln Red marks the state that needs attention while everything
 * else stays in the normal palette; it is the same accent already used for a
 * gold-medal achievement, so it introduces no new hue.
 *
 * `clearAndSetSemantics` strips the bare number from the accessibility tree.
 * The control already announces "Undo, 5 moves remaining" through its own
 * description, and leaving the digit as a separate node would make a screen
 * reader say the count twice, out of context, before the word Undo.
 */
@Composable
private fun CreditBadge(
    value: String,
    emphasised: Boolean,
    modifier: Modifier = Modifier
) {
    val foreground = if (emphasised) Color(0xFFF3EDE2)
    else MaterialTheme.colorScheme.onBackground
    val background = if (emphasised) Color(0xFFD6462B)
    else MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)

    Box(
        modifier
            .background(background, RoundedCornerShape(9.dp))
            .border(1.dp, outline, RoundedCornerShape(9.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = value,
            color = foreground,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
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
