package com.pushbound.game.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushbound.game.Levels

/**
 * Mobile-first, ORIENTATION-AWARE composition. The window itself rotates
 * (no fake canvas rotation) and this screen re-arranges the same parts for
 * the measured window size — see [GameLayout]:
 *
 *  - PORTRAIT (unchanged, the primary presentation):
 *
 *        [ level / moves HUD ]
 *        [      board       ]
 *        [ control bar       ]
 *
 *  - LANDSCAPE (responsive, the window is wider than it is tall):
 *
 *        [ level / moves HUD ]
 *        [  board  | controls ]
 *
 *    The board keeps the largest practical SQUARE cell size because the
 *    controls sit BESIDE it instead of below it (landscape height, not
 *    width, is what would otherwise crush a stacked layout). The control
 *    bar keeps its exact portrait button sizes and relative geometry; only
 *    its position on screen changes.
 *
 * System bars / gesture area are respected ONCE, for both orientations, via
 * a single safeDrawing inset pass. The [GameController] lives above the
 * orientation branch, so rotating is a pure UI/configuration change and the
 * level, player, boxes, goals, move counter and undo history all survive.
 */
@Composable
fun GameScreen() {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { GameController(Levels.first, scope) }

    // Reading the measured window makes the branch re-compose on the very
    // first layout pass after the physical rotation, for any aspect ratio.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val windowWidth = maxWidth.value
        val windowHeight = maxHeight.value
        val landscape = GameLayout.isLandscape(windowWidth, windowHeight)
        // Hoisted out of the Row/Column scopes below: the panel width is pure
        // window arithmetic, computed once from the measured window.
        val controlPanelWidth =
            GameLayout.landscapeControlPanelWidth(windowWidth, windowHeight).dp

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            LevelHeader(controller, Modifier.fillMaxWidth())

            if (landscape) {
                // Board left / controls right, both vertically centred on the
                // same axis so the screen reads as one balanced arrangement.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BoardView(
                        controller,
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(
                                horizontal = GameLayout.BOARD_PADDING_H_DP.dp,
                                vertical = GameLayout.BOARD_PADDING_V_DP.dp
                            )
                    )
                    ControlsBar(controller, Modifier.width(controlPanelWidth))
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    BoardView(
                        controller,
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(
                                horizontal = GameLayout.BOARD_PADDING_H_DP.dp,
                                vertical = GameLayout.BOARD_PADDING_V_DP.dp
                            )
                    )
                }
                ControlsBar(controller)
            }
        }
        CompletionOverlay(controller)
    }
}

@Composable
private fun LevelHeader(controller: GameController, modifier: Modifier) {
    Row(
        modifier
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = controller.level.name.uppercase(),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            fontSize = 13.sp,
            letterSpacing = 2.5.sp
        )
        Text(
            text = "MOVES ${controller.state.moves}",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.5.sp
        )
    }
}

/**
 * Minimal completion state. Continue simply restarts the level until a real
 * level sequence exists (milestone 3, section 10).
 */
@Composable
private fun CompletionOverlay(controller: GameController) {
    if (!controller.isComplete) return

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x992B2622))
            .pointerInput(Unit) { detectTapGestures { } }, // keep controls under the scrim inert
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFFF1EADD),
            shadowElevation = 10.dp
        ) {
            Column(
                Modifier.padding(horizontal = 36.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Level Complete",
                    color = Color(0xFF2B2622),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = controller::restart,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2B2622),
                        contentColor = Color(0xFFF3EDE2)
                    )
                ) {
                    Text(text = "Continue", fontSize = 15.sp, letterSpacing = 1.sp)
                }
            }
        }
    }
}
