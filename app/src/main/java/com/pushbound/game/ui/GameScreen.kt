package com.pushbound.game.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushbound.game.Levels
import com.pushbound.game.ads.AdPlacement
import com.pushbound.game.data.SharedPreferencesProgressStore
import com.pushbound.game.engine.GameMode
import com.pushbound.game.engine.Medal
import com.pushbound.game.engine.ModeResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
/**
 * The playable screen.
 *
 * [startLevelId] opens a SPECIFIC level instead of resuming the campaign, which
 * is what the level-select screens need: a completed level must be replayable
 * on demand, not only resumed from where progress happens to point. Leaving it
 * null preserves the original behaviour exactly — resume where the player left
 * off, or level one on a fresh profile.
 *
 * [onExit] is the way back to the menus. When it is null the affordance is not
 * drawn at all, so a caller that does not want navigation is unaffected.
 */
@Composable
fun GameScreen(
    startLevelId: String? = null,
    onExit: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val progressStore = remember(context) { SharedPreferencesProgressStore(context) }
    // Resume where this player left off rather than always at level one; with a
    // fresh profile Progress.nextLevelId falls back to the first level.
    //
    // The AUTHORED campaign is used for this lookup: deciding which id to open
    // is a structural question and must not drag the solver onto the startup
    // path. The controller approves the single level it plays.
    val controller = remember(scope, progressStore, startLevelId) {
        // Resolve the STRUCTURE from the authored campaign (no solver), then
        // approve exactly the one level that will be played via Levels.byId.
        val resumedId = startLevelId ?: ModeResolver.levelIdFor(
            mode = GameMode.CampaignMode,
            campaign = Levels.campaign,
            state = progressStore.load()
        ) ?: Levels.campaign.allLevels.first().id
        GameController(Levels.byId(resumedId)!!, scope, progressStore)
    }

    // Warm the NEXT level's validation off the main thread.
    //
    // Approving a level means solving it. Every shipped level is milliseconds
    // except the largest three-box board, which measured 4.6s on a mid-range
    // ARM device — long enough to stall a tap on "Continue". Solving the
    // successor in the background while the player is still on the current
    // level means that cost has already been paid by the time they advance.
    LaunchedEffect(controller.level.id) {
        val next = Levels.campaign.nextAfter(controller.level.id) ?: return@LaunchedEffect
        withContext(Dispatchers.Default) { Levels.approved(next) }
    }

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
            LevelHeader(controller, onExit, Modifier.fillMaxWidth())

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
private fun LevelHeader(
    controller: GameController,
    onExit: (() -> Unit)?,
    modifier: Modifier
) {
    Row(
        modifier
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Only drawn when the caller supplied an exit: with a null [onExit]
            // this screen is pixel-for-pixel what it was before navigation
            // existed. The style matches the HUD's own letterspaced text.
            if (onExit != null) {
                Text(
                    text = "‹ LEVELS",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    letterSpacing = 2.5.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onExit
                        )
                        .semantics { contentDescription = "Back to levels" }
                        .padding(end = 18.dp)
                )
            }
            // "W1·2  FIRST STEPS" — where the player is, in the existing
            // letterspaced display style. The position matters once a campaign
            // is more than a handful of levels long, so it rides along with the
            // name instead of taking a row of its own.
            Text(
                text = "W${controller.level.world}·${controller.level.number}  " +
                    controller.level.name.uppercase(),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                fontSize = 13.sp,
                letterSpacing = 2.5.sp,
                maxLines = 1
            )
        }
        // Both counters, always visible, exactly as the engine tracks them:
        // moves and pushes are separate because the medal is judged on both.
        Text(
            text = "MOVES ${controller.state.moves} · PUSHES ${controller.state.pushes}",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.5.sp,
            maxLines = 1
        )
    }
}

/**
 * The finish state: the medal that was earned, the two final counters, and the
 * path onward.
 *
 * Everything shown here is read from [GameController.lastResult] — captured the
 * instant the level completed — so the numbers stay exactly what the player
 * achieved even if they undo out of the solved state afterwards. Progress and
 * the unlock were already written to storage at that same moment.
 */
@Composable
private fun CompletionOverlay(controller: GameController) {
    if (!controller.isComplete) return

    // The ad opportunity sits behind the policy: the caller only names a
    // placement, and the policy refuses gameplay, failure, restart, a short
    // interval and the two-completions-in-a-row rule. With no ad SDK wired in
    // this is inert — it never blocks, never delays and never gates completion.
    LaunchedEffect(controller.isComplete, controller.level.id) {
        controller.maybeShowInterstitial(AdPlacement.LEVEL_COMPLETE)
    }

    val hasNext = controller.nextLevelId != null

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

                controller.lastResult?.let { run ->
                    Spacer(Modifier.height(12.dp))
                    // Kiln Red is the brand's own signal hue, so the top medal
                    // is stamped in it rather than inventing a metallic gold the
                    // palette does not contain. Silver and bronze stay in Ink —
                    // the word carries the tier, colour only marks the best.
                    Text(
                        text = run.medal.name,
                        color = if (run.medal == Medal.GOLD) {
                            Color(0xFFD6462B)
                        } else {
                            Color(0xFF2B2622)
                        },
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    // The two numbers the medal was actually judged on —
                    // shown as a pair, never merged into a single count.
                    Text(
                        text = "Moves: ${run.moves}   Pushes: ${run.pushes}",
                        color = Color(0xFF2B2622).copy(alpha = 0.80f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp
                    )
                    if (controller.level.parMoves > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Par: ${controller.level.parMoves} moves / " +
                                "${controller.level.parPushes} pushes",
                            color = Color(0xFF2B2622).copy(alpha = 0.55f),
                            fontSize = 13.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                Button(
                    // Advance when there is somewhere to advance to; the final
                    // level offers a replay instead of a dead end.
                    onClick = {
                        if (!controller.continueToNextLevel()) controller.restart()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2B2622),
                        contentColor = Color(0xFFF3EDE2)
                    )
                ) {
                    Text(
                        text = if (hasNext) "Continue" else "Play Again",
                        fontSize = 15.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}
