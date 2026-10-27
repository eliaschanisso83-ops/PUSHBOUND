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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushbound.game.Levels
import com.pushbound.game.ads.AdPlacement
import com.pushbound.game.ads.AdsGateway
import com.pushbound.game.ads.NoOpAdsGateway
import com.pushbound.game.data.SharedPreferencesProgressStore
import com.pushbound.game.data.SharedPreferencesSessionStore
import com.pushbound.game.data.SharedPreferencesUndoCreditStore
import com.pushbound.game.engine.ContentKey
import com.pushbound.game.engine.Medal
import com.pushbound.game.engine.ModeResolver
import com.pushbound.game.progress.SessionStore
import com.pushbound.game.undo.UndoGate
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
 * [startKey] opens a SPECIFIC piece of content instead of resuming the campaign,
 * which is what the level-select screens and the daily entry need: a completed
 * level must be replayable on demand, and today's puzzle must be playable under
 * its own `daily/<date>` identity. Leaving it null preserves the original
 * behaviour — resume where campaign progress points, or level one on a fresh
 * profile.
 *
 * THE KEY, NOT AN ID, IS WHAT OPENS A BOARD. It carries the mode, so the run
 * that follows records and saves under the right namespace. Opening the same
 * grid as a campaign level and as a daily puzzle therefore produces two
 * independent runs with two independent records.
 *
 * [sessionStore] is where the resumable session is read from and written to.
 * Passing the SAME store the menus use is what makes RESUME work across an app
 * restart rather than only within one composition.
 *
 * [onExit] is the way back to the menus. When it is null the affordance is not
 * drawn at all, so a caller that does not want navigation is unaffected.
 */
@Composable
fun GameScreen(
    startKey: ContentKey? = null,
    sessionStore: SessionStore? = null,
    /**
     * The advertising seam, threaded down to the controller. Defaults to
     * [NoOpAdsGateway] so tests, previews and any caller that does not care
     * get the no-ad behaviour with no extra argument — and so the controller
     * never has to know whether a real SDK is present.
     */
    ads: AdsGateway = NoOpAdsGateway,
    onExit: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val progressStore = remember(context) { SharedPreferencesProgressStore(context) }
    // The Undo balance lives in its own file, but for the same reason and
    // created the same way: one small local resource, remembered alongside the
    // stores it must never be confused with, and reconstructed whenever the
    // context does so a configuration change re-reads a durable value rather
    // than a stale one.
    val undoCreditStore = remember(context) { SharedPreferencesUndoCreditStore(context) }
    // Defaults to the platform store so a caller that only needs gameplay (the
    // animation tests, a preview) still gets a working, isolated session.
    val sessions = sessionStore ?: remember(context) { SharedPreferencesSessionStore(context) }
    // The KEY decides which board opens. When the caller does not name one —
    // no level was picked and no session was resumed — the campaign's continue
    // point is used, which is what a bare "play" affordance has always meant.
    //
    // The AUTHORED campaign is used for these lookups: deciding which id to open
    // is a structural question and must not drag the solver onto the startup
    // path. Exactly one level is then approved, by [Levels.byId].
    val controller = remember(scope, progressStore, undoCreditStore, sessions, startKey, ads) {
        // Resolve the STRUCTURE from the authored campaign (no solver), then
        // approve exactly the one level that will be played via Levels.byId.
        //
        // The KEY decides both the board and the mode. Falling back to the
        // campaign's continue point only happens when nothing asked for a
        // specific piece of content, so a caller that names a daily always
        // gets that daily — never a campaign level that happens to share its
        // grid.
        val key = startKey ?: ModeResolver.campaignContentKey(
            campaign = Levels.campaign,
            state = progressStore.load()
        ) ?: ContentKey.campaign(Levels.campaign.allLevels.first().id)

        // A campaign key names its level directly; a daily key names a DATE and
        // the board has to be derived from it. `Levels.byId` is what actually
        // APPROVES the level (approval runs the solver and is cached), so it is
        // the single call that pays that cost — resolving through
        // ModeResolver.levelFor alone would hand back the authored, unapproved
        // form and the board would ship without its par times.
        val boardId = if (key.isDaily) {
            ModeResolver.levelIdFor(key.mode, Levels.campaign) ?: key.id
        } else {
            key.id
        }
        val level = Levels.byId(boardId) ?: Levels.campaign.allLevels.first()

        GameController(
            level = level,
            startKey = key,
            scope = scope,
            progressStore = progressStore,
            sessionStore = sessions,
            ads = ads,
            undoCreditStore = undoCreditStore
        )
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

        // The notice is a status line, never a gate: it explains why nothing
        // happened and leaves the board fully playable underneath. One line
        // for both systems — a hint explanation and an Undo explanation are
        // never posted at the same moment, and giving them a shared slot means
        // they can never overlap or stack on top of each other.
        val notice = controller.hintNotice ?: controller.undoNotice
        if (notice != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 96.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = notice,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.70f),
                    fontSize = 13.sp,
                    letterSpacing = 0.4.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        CompletionOverlay(controller)
        RewardedHintDialog(controller)
        UndoRestorationDialog(controller)
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
                val backLabel = if (controller.contentKey.isDaily) "‹ MENU" else "‹ LEVELS"
                Text(
                    text = backLabel,
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
            // THE MODE IS NAMED, not implied. A campaign level shows its
            // position in the campaign — "W1·2  FIRST STEPS" — because that is
            // what a player needs to know when picking from a world. A daily
            // shows its DATE instead, because a daily has no position in the
            // campaign at all: it is one board for one day, and printing a
            // world/number on it would tell the player they are somewhere they
            // are not.
            val title = if (controller.contentKey.isDaily) {
                "DAILY PUZZLE · ${controller.contentKey.id}"
            } else {
                "W${controller.level.world}·${controller.level.number}  " +
                    controller.level.name.uppercase()
            }
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                fontSize = 13.sp,
                letterSpacing = 2.5.sp,
                maxLines = 1,
                modifier = Modifier
                    .semantics { contentDescription = "Playing $title" }
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
 * The rewarded-hint confirmation — the whole "optional value exchange", made
 * visible.
 *
 * WHAT MAKES THIS AN OFFER RATHER THAN AN INTERRUPTION:
 *
 *  - It appears ONLY after the player has tapped Hint with the free allowance
 *    spent. Nothing here is scheduled, automatic, or shown at a moment the
 *    player did not choose.
 *  - It states the reward BEFORE the choice ("one more hint"), states that an
 *    ad will be shown, and states that it is optional.
 *  - "Not now" is a first-class, equally reachable exit that costs nothing:
 *    dismissing leaves the board, the counters and the free allowance exactly
 *    as they were.
 *  - The ad is only requested by [GameController.confirmRewardedHint], of which
 *    this dialog is the sole caller. Tapping Hint can never open an ad.
 *
 * WHY THE COPY DIFFERS BY [RewardedHintOffer.willFetch] — promising an ad that
 * is already loaded and promising to try to fetch one are different promises,
 * and consent should be informed. When an ad must be fetched the wording says
 * so, and a fetch that then fails is reported back as unavailability rather
 * than as something the player did wrong.
 *
 * Same tint and shape family as [CompletionOverlay], so it reads as part of the
 * game rather than as something that arrived from outside it.
 */
@Composable
private fun RewardedHintDialog(controller: GameController) {
    val offer = controller.rewardedHintOffer ?: return

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x992B2622))
            // The board under the scrim is inert, but the two buttons inside
            // the dialog stay live — this gesture detector only swallows the
            // touches that actually reach it.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFFF1EADD),
            shadowElevation = 10.dp
        ) {
            Column(
                Modifier.padding(horizontal = 30.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Need a hand?",
                    color = Color(0xFF2B2622),
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    // The reward, stated first and stated plainly. A player has
                    // to know what they are getting before they can decide.
                    text = "Watch a short ad to get one more hint for this puzzle.",
                    color = Color(0xFF2B2622).copy(alpha = 0.85f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 21.sp
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = if (offer.willFetch) {
                        "A short ad will be loaded, then shown. It's optional — you can " +
                            "keep playing without it, and nothing about your progress changes."
                    } else {
                        "A short ad will be shown. It's optional — you can keep playing " +
                            "without it, and nothing about your progress changes."
                    },
                    color = Color(0xFF2B2622).copy(alpha = 0.62f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = controller::confirmRewardedHint,
                    enabled = !controller.rewardedHintPending,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2B2622),
                        contentColor = Color(0xFFF3EDE2)
                    )
                ) {
                    Text(
                        text = if (controller.rewardedHintPending) {
                            "Loading…"
                        } else {
                            "Watch ad, get hint"
                        },
                        fontSize = 15.sp,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(Modifier.height(10.dp))

                // DECLINING COSTS NOTHING. This is the exit the rules require, so
                // it is always present and never de-emphasised into an
                // afterthought: one equally sized tap, directly beneath.
                Button(
                    onClick = controller::dismissRewardedHint,
                    enabled = !controller.rewardedHintPending,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2B2622).copy(alpha = 0.08f),
                        contentColor = Color(0xFF2B2622)
                    )
                ) {
                    Text(
                        text = "Not now",
                        fontSize = 15.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

/**
 * The "out of Undo moves" offer.
 *
 * WHAT THIS DIALOG IS ALLOWED TO DO — ask, and nothing else. It is raised ONLY
 * by a tap on Undo while the balance is zero, it states the cost and the
 * reward before the choice, it gives "Not now" equal visual weight, and it
 * reaches the advertising SDK through exactly ONE line: the button that says
 * "Watch Ad". Merely opening it loads nothing, shows nothing, starts no timer
 * and changes no counter, and dismissing it leaves the game byte-for-byte as
 * it was — those are the properties that make it a confirmation rather than an
 * interruption, and they are pinned by tests, not by intent.
 *
 * THE FAILURE IT DOES NOT PRESENT — there is no error state here by design. If
 * the ad cannot be fetched or is closed early, the dialog closes and a single
 * status line says the balance is unchanged; play is never blocked, never
 * gated behind a retry, and never told that something is wrong with the game.
 *
 * SAME TINT AND SHAPE FAMILY AS [RewardedHintDialog] and the completion
 * overlay, so it reads as part of the game rather than as something that
 * arrived from outside it — which matters most here, because this is the one
 * dialog in Pushbound that mentions an ad.
 */
@Composable
private fun UndoRestorationDialog(controller: GameController) {
    if (controller.undoRestorationOffer == null) return

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x992B2622))
            // The board under the scrim is inert, but the two buttons inside
            // the dialog stay live — this gesture detector only swallows the
            // touches that actually reach it.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFFF1EADD),
            shadowElevation = 10.dp
        ) {
            Column(
                Modifier.padding(horizontal = 30.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Out of Undo moves",
                    color = Color(0xFF2B2622),
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    // WHERE THEY STAND, BEFORE WHAT IS ON OFFER. A player who
                    // has just watched their balance reach zero deserves the
                    // explanation first, not a sales line.
                    text = "You've used all ${UndoGate.MAX_UNDO_CREDITS} Undo moves.",
                    color = Color(0xFF2B2622).copy(alpha = 0.85f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 21.sp
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    // The reward, stated plainly: the number they get back and
                    // the ad they watch for it, before either choice.
                    text = "Watch a short rewarded ad to restore " +
                        "${UndoGate.MAX_UNDO_CREDITS} Undo moves.",
                    color = Color(0xFF2B2622).copy(alpha = 0.85f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 21.sp
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    // DECLINING IS AN EQUAL EXIT, said in words rather than
                    // implied by a dimmed button. This is the promise the rules
                    // require: nothing changes if the player says no.
                    text = "It's optional — you can keep playing without it, and " +
                        "nothing about your progress or your balance changes.",
                    color = Color(0xFF2B2622).copy(alpha = 0.62f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(20.dp))

                // THE ONLY LINE THAT REACHES THE AD SDK. Everything above it is
                // text, and everything below it declines.
                Button(
                    onClick = controller::confirmUndoRestoration,
                    enabled = !controller.undoRestorationPending,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2B2622),
                        contentColor = Color(0xFFF3EDE2)
                    )
                ) {
                    Text(
                        text = if (controller.undoRestorationPending) {
                            "Loading…"
                        } else {
                            "Watch Ad"
                        },
                        fontSize = 15.sp,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(Modifier.height(10.dp))

                // DECLINING COSTS NOTHING. Present at once, never de-emphasised
                // into an afterthought: one equally sized tap, directly
                // beneath, which requests no ad and moves no credit.
                Button(
                    onClick = controller::dismissUndoRestoration,
                    enabled = !controller.undoRestorationPending,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2B2622).copy(alpha = 0.08f),
                        contentColor = Color(0xFF2B2622)
                    )
                ) {
                    Text(
                        text = "Not now",
                        fontSize = 15.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
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
