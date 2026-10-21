package com.pushbound.game.engine

import com.pushbound.game.levels.Campaign
import com.pushbound.game.progress.Progress
import com.pushbound.game.progress.ProgressState

/**
 * Which context a level is being played in.
 *
 * The game resolves a mode to a LEVEL ID and then plays exactly the same
 * engine, the same counters, the same undo/redo and the same completion rules.
 * Nothing about movement, history or medals is mode-specific — modes only ever
 * decide *which puzzle* is on the board and *what is recorded* afterwards.
 *
 * That separation is the whole architecture: adding a mode means adding an entry
 * here plus a way to pick its level. The engine, the renderer, the controls and
 * the progress rules do not change.
 *
 * SHIPPED NOW:
 *  - [Campaign] — the ordered world/level progression.
 *  - [Daily] — one shared puzzle per calendar day, offline and deterministic.
 *
 * RESERVED FOR LATER, NOT IMPLEMENTED IN THIS RELEASE (the shape already fits):
 *  - Challenge — a fixed, curated variant of a level; it would carry its own
 *    level id and simply reuse the same session.
 *  - Generator — a level built from a seed; it would resolve to a synthetic
 *    [LevelDefinition] the same way every other mode does, so no engine change
 *    would be required when it arrives.
 */
sealed interface GameMode {

    /** The ordered campaign, gated by player progress. */
    data object CampaignMode : GameMode {
        override fun toString(): String = "Campaign"
    }

    /**
     * The shared daily puzzle for a specific day.
     *
     * Carries its epoch day rather than a resolved id so the mode is a value
     * that can be tested, persisted and compared without a clock attached.
     */
    data class Daily(val epochDay: Long) : GameMode
}

/**
 * Turns a [GameMode] into the level that should actually be loaded.
 *
 * All selection policy lives here, in one place, so "what does this mode play"
 * has a single answer that unit tests can pin directly.
 */
object ModeResolver {

    /**
     * The level id a mode points at.
     *
     * @param state progress, used only by [GameMode.CampaignMode] to decide how
     *   far the player has advanced. The daily ignores it by design — see
     *   [DailyPuzzle].
     * @return null when no level can be resolved (empty campaign, or an unknown
     *   day recorded against a level id that no longer exists).
     */
    fun levelIdFor(mode: GameMode, campaign: Campaign, state: ProgressState): String? =
        when (mode) {
            is GameMode.CampaignMode ->
                // Resume at the first level opened but not finished; fall back to
                // the very first level when progress is empty or complete.
                Progress.nextLevelId(campaign, state)
                    ?: campaign.allLevels.firstOrNull()?.id

            is GameMode.Daily ->
                DailyPuzzle.levelIdFor(mode.epochDay, campaign.allLevels.map { it.id })
        }

    /** The level definition a mode points at, or null when unresolvable. */
    fun levelFor(mode: GameMode, campaign: Campaign, state: ProgressState): LevelDefinition? =
        levelIdFor(mode, campaign, state)?.let { campaign.level(it) }
}
