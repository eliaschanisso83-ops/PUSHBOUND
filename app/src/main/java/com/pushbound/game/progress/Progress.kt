package com.pushbound.game.progress

import com.pushbound.game.engine.ContentKey
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.Medal
import com.pushbound.game.levels.Campaign

/**
 * The best result ever recorded on one piece of content.
 *
 * [storageKey] is a [ContentKey]'s namespaced key (`campaign/w3-2` or
 * `daily/2026-10-23`), NOT a bare level id. That is the isolation the mode
 * architecture needs: a campaign level and a daily puzzle can be built from the
 * same grid, and they still cannot land in the same record, because they never
 * write the same key.
 *
 * [bestMedal], [bestMoves] and [bestPushes] are each stored INDEPENDENTLY
 * rather than as one snapshot, because they are not achieved together: a player
 * can post their fewest moves on one attempt and their best medal on another.
 * Each field therefore keeps its own optimum, and [Medals.betterOf] guards the
 * medal so a later, worse run can never downgrade an earned tier.
 *
 * [moves] and [pushes] are nullable and 0 is a legal value, so "never played"
 * and "played but recorded nothing" stay distinguishable.
 */
data class LevelRecord(
    val storageKey: String,
    val completed: Boolean = false,
    val bestMedal: Medal? = null,
    val bestMoves: Int? = null,
    val bestPushes: Int? = null
)

/**
 * The complete persisted progress of one player, across every mode.
 *
 * A plain map of content key -> record, so the structure survives adding worlds
 * or modes without a migration: unknown keys in a stored payload are simply
 * carried along, and new content is absent from the map until it is first
 * finished.
 *
 * CAMPAIGN AND DAILY LIVE IN THE SAME MAP BUT NEVER UNDER THE SAME KEY — the
 * key is namespaced (see [ContentKey]), which is what makes the two modes
 * independently readable while still costing one pass to persist.
 */
data class ProgressState(val records: Map<String, LevelRecord> = emptyMap()) {

    /** The record for one piece of content, addressed by its namespaced key. */
    fun recordFor(key: ContentKey): LevelRecord? = records[key.storageKey]

    /** True when this exact content has been finished. */
    fun isCompleted(key: ContentKey): Boolean = records[key.storageKey]?.completed == true

    /**
     * CAMPAIGN shorthand: a bare level id always means campaign content, because
     * the campaign owns the `wN-M` id space. Daily callers must never reach for
     * this overload — they have no bare id, only a date.
     */
    fun recordFor(campaignLevelId: String): LevelRecord? =
        recordFor(ContentKey.campaign(campaignLevelId))

    /** CAMPAIGN shorthand — see [recordFor]. */
    fun isCompleted(campaignLevelId: String): Boolean =
        isCompleted(ContentKey.campaign(campaignLevelId))

    /**
     * How many levels of the CAMPAIGN this player has finished.
     *
     * Counts the campaign's own levels and nothing else, by walking the
     * campaign and asking about each one. It deliberately does not count
     * completed records, because daily puzzles also write records: a player who
     * clears six dailies has still cleared zero campaign levels, and the
     * "12 OF 15 CLEARED" line on the menu has to say so.
     */
    fun completedCampaignCount(campaign: Campaign): Int =
        campaign.allLevels.count { isCompleted(ContentKey.campaign(it.id)) }
}

/**
 * Pure progression rules. No storage, no Android — everything here is a
 * function of (campaign, state), which is what makes progression testable and
 * what keeps cloud sync a matter of swapping the store, not the rules.
 *
 * UNLOCK RULE — a level is open when it is the first in play order, or when the
 * level immediately before it has been COMPLETED. Completion is finishing at
 * all; no medal is ever required. That is deliberate: a player who just wants
 * to move on is never gated by performance, while the medals remain an
 * optional pursuit for replay.
 */
object Progress {

    /** True when [levelId] may be played right now. */
    fun isUnlocked(campaign: Campaign, state: ProgressState, levelId: String): Boolean {
        val position = campaign.positionOf(levelId)
        if (position < 0) return false
        if (position == 0) return true
        val previous = campaign.allLevels[position - 1]
        // Only a CAMPAIGN completion unlocks. A daily puzzle that happens to be
        // built from `previous`'s grid is recorded under `daily/<date>` and is
        // invisible here, which is precisely what stops "cleared today's puzzle"
        // from opening a campaign level the player never played.
        return state.isCompleted(ContentKey.campaign(previous.id))
    }

    /** Every level id the player may enter, in play order. */
    fun unlockedIds(campaign: Campaign, state: ProgressState): List<String> =
        campaign.allLevels.map { it.id }.filter { isUnlocked(campaign, state, it) }

    /**
     * The first level the player has opened but not finished — where a "continue"
     * button should take them. Null when the campaign is cleared.
     */
    fun nextLevelId(campaign: Campaign, state: ProgressState): String? =
        campaign.allLevels
            .firstOrNull { !state.isCompleted(it.id) && isUnlocked(campaign, state, it.id) }
            ?.id

    /**
     * Applies one finished attempt, returning the NEW state.
     *
     * The input state is never mutated, so history and previews stay cheap.
     * An attempt can only improve a record: medals take the better of old and
     * new, counts take the lower.
     *
     * THE [key] IS THE ONLY THING THAT DECIDES WHICH RECORD MOVES. Callers pass
     * the [ContentKey] of the run they just finished, so a daily completion
     * writes `daily/<date>` and a campaign completion writes `campaign/<id>`,
     * with no branch on mode anywhere in these rules.
     */
    fun recordCompletion(
        state: ProgressState,
        key: ContentKey,
        moves: Int,
        pushes: Int,
        medal: Medal
    ): ProgressState {
        require(moves >= 0) { "moves must not be negative" }
        require(pushes >= 0) { "pushes must not be negative" }

        val storageKey = key.storageKey
        val previous = state.records[storageKey]
        val merged = LevelRecord(
            storageKey = storageKey,
            completed = true,
            bestMedal = MedalsBetterOf(previous?.bestMedal, medal),
            bestMoves = minOfNotNull(previous?.bestMoves, moves),
            bestPushes = minOfNotNull(previous?.bestPushes, pushes)
        )
        return state.copy(records = state.records + (storageKey to merged))
    }

    /**
     * CAMPAIGN shorthand for [recordCompletion]. A bare level id is always
     * campaign content; daily completions have no bare id and must pass a key.
     */
    fun recordCompletion(
        state: ProgressState,
        levelId: String,
        moves: Int,
        pushes: Int,
        medal: Medal
    ): ProgressState = recordCompletion(state, ContentKey.campaign(levelId), moves, pushes, medal)

    /**
     * CAMPAIGN shorthand for [withBestCounts]. A bare level id is always
     * campaign content; a daily has no bare id and must pass a [ContentKey].
     */
    fun withBestCounts(
        state: ProgressState,
        levelId: String,
        moves: Int,
        pushes: Int
    ): ProgressState = withBestCounts(state, ContentKey.campaign(levelId), moves, pushes)

    /** Awards only an improvement to an existing record; used for partial saves. */
    fun withBestCounts(
        state: ProgressState,
        key: ContentKey,
        moves: Int,
        pushes: Int
    ): ProgressState {
        val storageKey = key.storageKey
        val previous = state.records[storageKey] ?: LevelRecord(storageKey = storageKey)
        return state.copy(
            records = state.records + (storageKey to previous.copy(
                bestMoves = minOfNotNull(previous.bestMoves, moves),
                bestPushes = minOfNotNull(previous.bestPushes, pushes)
            ))
        )
    }

    private fun MedalsBetterOf(a: Medal?, b: Medal?): Medal? =
        if (a == null) b else if (b == null) a else if (a.ordinal >= b.ordinal) a else b

    private fun minOfNotNull(a: Int?, b: Int): Int = if (a == null) b else minOf(a, b)

    /**
     * Progress that has finished exactly [count] levels of the campaign in play
     * order. Used by previews and by tests that need a mid-campaign state
     * without replaying every level.
     */
    fun stateAfter(campaign: Campaign, count: Int): ProgressState {
        var state = ProgressState()
        campaign.allLevels.take(count.coerceIn(0, campaign.levelCount)).forEach { level ->
            state = recordCompletion(
                state = state,
                levelId = level.id,
                moves = level.parMoves,
                pushes = level.parPushes,
                medal = Medal.GOLD
            )
        }
        return state
    }

    /** Convenience: does this level's definition describe an unlocked entry? */
    fun isUnlocked(campaign: Campaign, state: ProgressState, level: LevelDefinition): Boolean =
        isUnlocked(campaign, state, level.id)
}
