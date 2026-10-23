package com.pushbound.game.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushbound.game.Levels
import com.pushbound.game.R
import com.pushbound.game.data.SharedPreferencesProgressStore
import com.pushbound.game.data.SharedPreferencesSessionStore
import com.pushbound.game.engine.ContentKey
import com.pushbound.game.engine.DailyPuzzle
import com.pushbound.game.engine.Medal
import com.pushbound.game.engine.ModeResolver
import com.pushbound.game.levels.Campaign
import com.pushbound.game.levels.World
import com.pushbound.game.progress.Progress
import com.pushbound.game.progress.ProgressState
import com.pushbound.game.progress.SavedSession

/**
 * Where the player currently is.
 *
 * Navigation is pure data: a screen is a value that names a [ContentKey] or a
 * world index, never a callback chain. That is what keeps it driven by the
 * level system rather than by a hard-coded route — adding a world or a level
 * changes no code here, and the list a player taps through is exactly
 * [Levels.campaign].
 *
 * PLAY CARRIES A [ContentKey], NOT A LEVEL ID. A level id says which grid to
 * draw but not which mode it is being played in, and the mode decides which
 * record a completion writes and which session it saves. Passing only the id is
 * how a daily puzzle once became indistinguishable from a campaign level.
 */
sealed interface Destination {
    /** Title screen: resume, campaign, daily puzzle. */
    data object MainMenu : Destination

    /** World select — the chapters of the campaign. */
    data object Campaign : Destination

    /** Level select inside one world. */
    data class World(val index: Int) : Destination

    /** Playing one piece of content, by its mode-qualified identity. */
    data class Play(val key: ContentKey) : Destination
}

/**
 * The root of the app: Main Menu → Campaign → World → Level → Game, plus the
 * Daily Puzzle entry off the main menu.
 *
 * PROGRESS IS THE SINGLE SOURCE OF TRUTH for locking, completion and medals,
 * and it is re-read from the store every time a screen becomes visible again.
 * That is what makes "complete a level, come back, see the medal and the next
 * level unlocked" work across restarts and across the back-navigation, rather
 * than only inside one screen's lifetime.
 *
 * THE THREE ENTRIES ARE NOT THREE DOORS ONTO THE SAME ROOM:
 *
 *  - CAMPAIGN opens the world list — the campaign's own navigation.
 *  - DAILY PUZZLE opens today's puzzle under its own `daily/<date>` identity.
 *  - RESUME re-enters the session the player was last in, whatever mode it
 *    belonged to, with the board exactly as they left it. It owns no content of
 *    its own, and when there is nothing to resume it is DISABLED rather than
 *    quietly opening a campaign level.
 *
 * Nothing here resolves or approves a level — approving a level runs the
 * solver. Navigation reads the AUTHORED campaign, which is structure only, and
 * the one level actually played is approved by [GameScreen] when it opens.
 */
@Composable
fun PushboundApp() {
    val context = LocalContext.current
    val progressStore = remember(context) { SharedPreferencesProgressStore(context) }
    val sessionStore = remember(context) { SharedPreferencesSessionStore(context) }
    val campaign = Levels.campaign

    var destination by remember { mutableStateOf<Destination>(Destination.MainMenu) }
    var progress by remember { mutableStateOf(progressStore.load()) }
    // Re-read whenever the menu is shown: leaving a level writes a session, and
    // finishing one clears it, so RESUME's availability is live state rather
    // than something decided once at startup.
    var savedSession by remember { mutableStateOf(sessionStore.load()) }

    // Coming back out of a level must show what that level changed: the medal
    // it just earned, the level it just unlocked, and whether anything is now
    // resumable.
    val reloadProgress: () -> Unit = {
        progress = progressStore.load()
        savedSession = sessionStore.load()
    }

    when (val current = destination) {
        Destination.MainMenu -> MainMenuScreen(
            campaign = campaign,
            progress = progress,
            savedSession = savedSession,
            onResume = {
                // Resume restores the mode it was saved under. A daily session
                // comes back as a daily session and a campaign one as campaign;
                // neither is re-pointed at anything else. With no session the
                // entry is disabled, so this branch is defence in depth.
                val session = savedSession
                if (session != null && ModeResolver.levelFor(session.key, campaign) != null) {
                    destination = Destination.Play(session.key)
                }
            },
            onCampaign = { destination = Destination.Campaign },
            onDailyPuzzle = {
                destination = Destination.Play(ContentKey.daily(DailyPuzzle.todayEpochDay()))
            }
        )

        Destination.Campaign -> WorldListScreen(
            campaign = campaign,
            progress = progress,
            onBack = {
                reloadProgress()
                destination = Destination.MainMenu
            },
            onWorld = { index -> destination = Destination.World(index) }
        )

        is Destination.World -> LevelListScreen(
            campaign = campaign,
            worldIndex = current.index,
            progress = progress,
            onBack = { destination = Destination.Campaign },
            onLevel = { levelId ->
                destination = Destination.Play(ContentKey.campaign(levelId))
            }
        )

        is Destination.Play -> GameScreen(
            startKey = current.key,
            sessionStore = sessionStore,
            onExit = {
                reloadProgress()
                // Back out to where this MODE lives: a campaign level returns to
                // its own world, and a daily returns to the menu, because the
                // daily has no list of its own to return to.
                val worldIndex = if (current.key.isDaily) {
                    null
                } else {
                    campaign.level(current.key.id)?.world
                }
                destination = if (worldIndex == null) {
                    Destination.MainMenu
                } else {
                    Destination.World(worldIndex)
                }
            }
        )
    }
}

/**
 * Title screen. The three entries have three DIFFERENT jobs and are wired to
 * three different sources:
 *
 *  - RESUME re-enters [savedSession], whatever mode it belongs to. It is drawn
 *    DISABLED when there is nothing to resume, because pretending a game is in
 *    progress when none is would send the player somewhere they did not ask for.
 *  - CAMPAIGN opens the campaign's own world list.
 *  - DAILY PUZZLE opens today's puzzle under `daily/<date>`, and is labelled
 *    with the date so it reads as a daily rather than as a campaign level.
 */
@Composable
private fun MainMenuScreen(
    campaign: Campaign,
    progress: ProgressState,
    savedSession: SavedSession?,
    onResume: () -> Unit,
    onCampaign: () -> Unit,
    onDailyPuzzle: () -> Unit
) {
    val today = DailyPuzzle.todayEpochDay()
    // Shown as a plain date so a player can tell which day's puzzle they are
    // looking at — the requirement is that the puzzle carries an identity.
    val dailyIdentity = DailyPuzzle.contentIdFor(today)

    // RESUME offers exactly one thing: the session that was saved last. Its
    // subtitle names the MODE, so a player can see whether they are going back
    // to a campaign level or to today's puzzle before they tap.
    val resumable = savedSession != null &&
        ModeResolver.levelFor(savedSession.key, campaign) != null
    val resumeSubtitle = when {
        savedSession == null -> "NOTHING IN PROGRESS"
        !resumable -> "NOTHING IN PROGRESS"
        savedSession.key.isDaily -> "TODAY'S PUZZLE · ${savedSession.key.id}"
        else -> "CAMPAIGN · ${savedSession.key.id.uppercase()}"
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The approved horizontal lockup, not typed text. The Pushbound
        // wordmark is drawn from geometric primitives rather than set in a
        // font, so no system typeface can reproduce it — rendering the name
        // as a string was always a different logo. ContentScale.FillWidth
        // derives the height from the artwork's own ratio, so the mark is
        // scaled and never distorted.
        Image(
            painter = painterResource(R.drawable.brand_logo_horizontal),
            contentDescription = "Pushbound",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                // Capped so the lockup keeps its minimum legible size on a
                // phone and does not balloon on a tablet or in landscape.
                .widthIn(max = 280.dp)
                .fillMaxWidth()
        )
        // CAMPAIGN progress only. Daily puzzles write their own records under
        // `daily/<date>` and are invisible here by construction, so clearing
        // six dailies still reads "0 OF 15 CLEARED".
        Text(
            text = "${progress.completedCampaignCount(campaign)} OF ${campaign.levelCount} CLEARED",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            fontSize = 12.sp,
            letterSpacing = 2.5.sp,
            maxLines = 1
        )

        Spacer(Modifier.height(38.dp))

        MenuEntry(
            title = "RESUME",
            subtitle = resumeSubtitle,
            enabled = resumable,
            onClick = onResume
        )
        Spacer(Modifier.height(16.dp))
        MenuEntry(
            title = "CAMPAIGN",
            subtitle = "CHOOSE A WORLD",
            onClick = onCampaign
        )
        Spacer(Modifier.height(16.dp))
        MenuEntry(
            title = "DAILY PUZZLE",
            subtitle = "TODAY · $dailyIdentity",
            onClick = onDailyPuzzle
        )
    }
}

/** World select, built entirely from the authored campaign. */
@Composable
private fun WorldListScreen(
    campaign: Campaign,
    progress: ProgressState,
    onBack: () -> Unit,
    onWorld: (Int) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp, vertical = 22.dp)
            .verticalScroll(rememberScrollState())
    ) {
        BackRow("‹ MENU", onBack)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "CAMPAIGN",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 5.sp,
            maxLines = 1
        )
        Spacer(Modifier.height(20.dp))

        for (world in campaign.worlds) {
            // A world opens as soon as ANY of its levels is unlocked, and the
            // first world is always open. Derived rather than hard-coded, so
            // inserting a world between two others needs no special case.
            val open = world.index == campaign.worlds.first().index ||
                world.levels.any { Progress.isUnlocked(campaign, progress, it.id) }
            val cleared = world.levels.count { progress.isCompleted(it.id) }

            WorldRow(
                world = world,
                open = open,
                cleared = cleared,
                total = world.levels.size,
                onClick = { if (open) onWorld(world.index) }
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}

/** Level select for one world: locked, cleared and replayable states. */
@Composable
private fun LevelListScreen(
    campaign: Campaign,
    worldIndex: Int,
    progress: ProgressState,
    onBack: () -> Unit,
    onLevel: (String) -> Unit
) {
    val world = campaign.world(worldIndex) ?: return

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp, vertical = 22.dp)
            .verticalScroll(rememberScrollState())
    ) {
        BackRow("‹ WORLDS", onBack)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "W${world.index}  ${world.name.uppercase()}",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
            maxLines = 1
        )
        Text(
            text = world.blurb.uppercase(),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            fontSize = 11.sp,
            letterSpacing = 1.5.sp
        )
        Spacer(Modifier.height(20.dp))

        for (level in world.levels) {
            val unlocked = Progress.isUnlocked(campaign, progress, level.id)
            val record = progress.recordFor(level.id)
            LevelRow(
                number = level.number,
                name = level.name,
                unlocked = unlocked,
                completed = record?.completed == true,
                medal = record?.bestMedal,
                onClick = {
                    // A completed level stays tappable forever: replaying is a
                    // requirement, not a leftover.
                    if (unlocked) onLevel(level.id)
                }
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ------------------------------------------------------------------ pieces --

/**
 * One main-menu row.
 *
 * [enabled] exists for RESUME, which must be visibly inert when the player has
 * nothing in progress. An entry that is drawn but does nothing reads as a bug;
 * an entry that is drawn greyed out reads as "not yet", which is the truth.
 */
@Composable
private fun MenuEntry(
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val contentAlpha = if (enabled) 1f else 0.35f
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .semantics {
                contentDescription = if (enabled) title else "$title, unavailable"
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = contentAlpha),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 3.sp,
            maxLines = 1
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f * contentAlpha),
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun BackRow(label: String, onBack: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        fontSize = 13.sp,
        letterSpacing = 2.5.sp,
        maxLines = 1,
        modifier = Modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onBack
            )
            .semantics { contentDescription = label }
            .padding(bottom = 14.dp)
    )
}

@Composable
private fun WorldRow(
    world: World,
    open: Boolean,
    cleared: Int,
    total: Int,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = open,
                role = Role.Button,
                onClick = onClick
            )
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            )
            // A locked world reads as inert: dimmed, unlabelled and untappable.
            .alpha(if (open) 1f else 0.4f)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .semantics { contentDescription = "World ${world.index} ${world.name}" }
    ) {
        Text(
            text = "W${world.index}  ${world.name.uppercase()}",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.5.sp,
            maxLines = 1
        )
        Text(
            text = if (open) clearedLabel(cleared, total) else LOCKED_LABEL,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            maxLines = 1
        )
        if (open) {
            Text(
                text = world.theme.uppercase(),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun LevelRow(
    number: Int,
    name: String,
    unlocked: Boolean,
    completed: Boolean,
    medal: Medal?,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = unlocked,
                role = Role.Button,
                onClick = onClick
            )
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp)
            )
            // Locked levels are dimmed AND labelled, so the distinction is not
            // carried by colour alone.
            .alpha(if (unlocked) 1f else 0.4f)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .semantics { contentDescription = "Level $number $name" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "%02d".format(number),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            maxLines = 1
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (unlocked) name.uppercase() else LOCKED_LABEL,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
                maxLines = 1
            )
            if (unlocked && completed) {
                Text(
                    text = "BEST $medal",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    maxLines = 1
                )
            }
        }
        if (unlocked && completed && medal != null) {
            MedalDot(medal)
        }
    }
}

@Composable
private fun MedalDot(medal: Medal) {
    Box(
        Modifier
            .size(14.dp)
            .background(color = medalColor(medal), shape = RoundedCornerShape(7.dp))
    )
}

/** Medal colours taken from the project palette rather than invented. */
private fun medalColor(medal: Medal): Color = when (medal) {
    Medal.BRONZE -> Color(0xFFD6462B) // Kiln Red
    Medal.SILVER -> Color(0xFFEFE8DA) // Bone
    Medal.GOLD -> Color(0xFFF4EFE6) // Paper
}

private fun clearedLabel(cleared: Int, total: Int): String = "$cleared OF $total CLEARED"

private const val LOCKED_LABEL = "LOCKED"
