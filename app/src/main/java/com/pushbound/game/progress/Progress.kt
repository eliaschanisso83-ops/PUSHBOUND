package com.pushbound.game.progress

import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.Medal
import com.pushbound.game.levels.Campaign

/**
 * The best result ever recorded on one level.
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
    val levelId: String,
    val completed: Boolean = false,
    val bestMedal: Medal? = null,
    val bestMoves: Int? = null,
    val bestPushes: Int? = null
)

/**
 * The complete persisted progress of one player.
 *
 * A plain map of level id -> record, so the structure survives adding worlds
 * without a migration: unknown ids in a stored payload are simply carried
 * along, and a new level is absent from the map until it is first completed.
 */
data class ProgressState(val records: Map<String, LevelRecord> = emptyMap()) {

    fun recordFor(levelId: String): LevelRecord? = records[levelId]

    fun isCompleted(levelId: String): Boolean = records[levelId]?.completed == true

    fun completedCount(): Int = records.values.count { it.completed }
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
        return state.isCompleted(previous.id)
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
     */
    fun recordCompletion(
        state: ProgressState,
        levelId: String,
        moves: Int,
        pushes: Int,
        medal: Medal
    ): ProgressState {
        require(moves >= 0) { "moves must not be negative" }
        require(pushes >= 0) { "pushes must not be negative" }

        val previous = state.records[levelId]
        val merged = LevelRecord(
            levelId = levelId,
            completed = true,
            bestMedal = MedalsBetterOf(previous?.bestMedal, medal),
            bestMoves = minOfNotNull(previous?.bestMoves, moves),
            bestPushes = minOfNotNull(previous?.bestPushes, pushes)
        )
        return state.copy(records = state.records + (levelId to merged))
    }

    /** Awards only an improvement to an existing record; used for partial saves. */
    fun withBestCounts(
        state: ProgressState,
        levelId: String,
        moves: Int,
        pushes: Int
    ): ProgressState {
        val previous = state.records[levelId] ?: LevelRecord(levelId = levelId)
        return state.copy(
            records = state.records + (levelId to previous.copy(
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
