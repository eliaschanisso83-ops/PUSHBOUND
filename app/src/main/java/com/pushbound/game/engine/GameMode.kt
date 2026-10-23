package com.pushbound.game.engine

import com.pushbound.game.levels.Campaign
import com.pushbound.game.progress.Progress
import com.pushbound.game.progress.ProgressState

/**
 * Which context a level is being played in.
 *
 * The game resolves a mode to a [ContentKey] and then plays exactly the same
 * engine, the same counters, the same undo/redo and the same completion rules.
 * Nothing about movement, history or medals is mode-specific — modes only ever
 * decide *which puzzle* is on the board and *what is recorded* afterwards.
 *
 * The second half is the important one. A mode is not just a label: it is the
 * NAMESPACE a run's progress and saved session live under. Campaign runs are
 * recorded under `campaign/<levelId>` and daily runs under `daily/<date>`, so
 * two modes can share a grid and still never share a record. See [ContentKey].
 *
 * SHIPPED NOW:
 *  - [CampaignMode] — the ordered world/level progression.
 *  - [Daily] — one shared puzzle per calendar day, offline and deterministic.
 *
 * RESERVED FOR LATER, NOT IMPLEMENTED IN THIS RELEASE (the shape already fits):
 *  - Challenge — a fixed, curated variant of a level; it would carry its own
 *    content id and simply reuse the same session.
 *  - Generator — a level built from a seed; it would resolve to a synthetic
 *    [LevelDefinition] the same way every other mode does, so no engine change
 *    would be required when it arrives. A new mode is one entry here plus its
 *    own namespace in [ContentKey].
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
 * Turns a mode (or a [ContentKey]) into the board that should be loaded.
 *
 * All selection policy lives here, in one place, so "what does this mode play"
 * has a single answer that unit tests can pin directly.
 *
 * THE SPLIT THAT MATTERS: [levelFor] answers "which GRID is on screen", while
 * [ContentKey] answers "which RECORD gets written". A daily puzzle resolves to
 * a grid that happens to belong to the campaign, but it plays under its own
 * `daily/<date>` key, so completing it can never touch a campaign record. That
 * distinction is the whole point of the mode architecture.
 */
object ModeResolver {

    /**
     * The board a content key plays.
     *
     * @return null when the key cannot be resolved (unknown campaign id, an
     *   empty campaign, or a day whose derived board no longer exists).
     */
    fun levelFor(key: ContentKey, campaign: Campaign): LevelDefinition? =
        when (val mode = key.mode) {
            is GameMode.CampaignMode -> campaign.level(key.id)
            is GameMode.Daily -> levelIdFor(mode, campaign)?.let { campaign.level(it) }
        }

    /**
     * The campaign level id a day's puzzle is DRAWN from.
     *
     * This is only the board source. Callers must keep the day's own
     * [ContentKey] for anything that identifies, records or resumes the run —
     * treating this id as the content id is exactly the bug this architecture
     * exists to prevent.
     */
    fun levelIdFor(mode: GameMode, campaign: Campaign): String? =
        when (mode) {
            is GameMode.Daily ->
                DailyPuzzle.levelIdFor(mode.epochDay, campaign.allLevels.map { it.id })

            is GameMode.CampaignMode -> campaign.allLevels.firstOrNull()?.id
        }

    /**
     * The level id a mode points at, when progress decides the answer.
     *
     * Only the campaign consults progress: it resumes at the first level opened
     * but finished, falling back to the very first level. The daily ignores
     * progress entirely — see [DailyPuzzle].
     *
     * @return null when no level can be resolved (empty campaign).
     */
    fun levelIdFor(mode: GameMode, campaign: Campaign, state: ProgressState): String? =
        when (mode) {
            is GameMode.CampaignMode -> campaignContinueLevelId(campaign, state)
            is GameMode.Daily -> levelIdFor(mode, campaign)
        }

    /**
     * Where the CAMPAIGN entry takes the player: the level progression points
     * at, i.e. "continue the campaign".
     *
     * RESUME deliberately has no resolver here, because RESUME is not a mode
     * and owns no content. It re-enters the [com.pushbound.game.progress.SavedSession]
     * the player left behind, which already carries its own [ContentKey] — so
     * asking "what level does RESUME open" would be asking the wrong question.
     * With no session on offer the menu disables the entry instead of
     * pretending a game is in progress.
     */
    fun campaignContentKey(campaign: Campaign, state: ProgressState): ContentKey? =
        campaignContinueLevelId(campaign, state)?.let { ContentKey.campaign(it) }

    /** The first campaign level opened but not finished, else the first level. */
    fun campaignContinueLevelId(campaign: Campaign, state: ProgressState): String? =
        Progress.nextLevelId(campaign, state)
            ?: campaign.allLevels.firstOrNull()?.id

    /** The level definition a mode points at, or null when unresolvable. */
    fun levelFor(mode: GameMode, campaign: Campaign, state: ProgressState): LevelDefinition? =
        levelIdFor(mode, campaign, state)?.let { campaign.level(it) }
}
