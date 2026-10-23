package com.pushbound.game.engine

/**
 * Namespaced identity of one piece of playable content.
 *
 * WHY THIS EXISTS — a Campaign level and a Daily Puzzle can legitimately be
 * built from the very same board, and until this type existed they also shared
 * one key: `DailyPuzzle` returned a CAMPAIGN LEVEL ID, so finishing "today's
 * puzzle" wrote into exactly the same progress record as finishing that campaign
 * level. The completion counter rose, the level was marked cleared, and the next
 * campaign level unlocked — all from one tap on the daily entry.
 *
 * [ContentKey] makes that impossible by construction. A key is a MODE plus an
 * id, and the two modes never share a namespace:
 *
 *     ContentKey.campaign("w3-2").storageKey  ==  "campaign/w3-2"
 *     ContentKey.daily(epochDay).storageKey   ==  "daily/2026-10-23"
 *
 * Progress records, the resumable session and every completion write are keyed
 * by [storageKey], so two pieces of content collide only if their mode AND their
 * id collide. Two entries that merely happen to show the same grid cannot.
 *
 * IDs ARE NOT INTERCHANGEABLE:
 *  - a CAMPAIGN id is the authored level id (`w3-2`) — stable forever, owned by
 *    the campaign, and already world/number scoped;
 *  - a DAILY id is the calendar date in ISO-8601 (`2026-10-23`) — one per day,
 *    readable by a human, and derived from a clock the caller supplies so the
 *    mapping stays a pure function under test.
 */
data class ContentKey(val mode: GameMode, val id: String) {

    /**
     * The text this key is stored and looked up under. This — not [id] — is what
     * [com.pushbound.game.progress.ProgressState] maps, because [id] alone is
     * only unique within one mode.
     */
    val storageKey: String
        get() = when (mode) {
            is GameMode.CampaignMode -> "$CAMPAIGN/$id"
            is GameMode.Daily -> "$DAILY/$id"
        }

    /** True when this key addresses the shared daily puzzle. */
    val isDaily: Boolean get() = mode is GameMode.Daily

    /** The calendar day a daily key points at, or null for campaign keys. */
    val epochDay: Long? get() = (mode as? GameMode.Daily)?.epochDay

    override fun toString(): String = storageKey

    companion object {
        /** Namespace owned by the ordered campaign. */
        const val CAMPAIGN = "campaign"

        /** Namespace owned by the daily puzzle. */
        const val DAILY = "daily"

        /** A campaign level, addressed by its authored level id (`w3-2`). */
        fun campaign(levelId: String): ContentKey =
            ContentKey(GameMode.CampaignMode, levelId)

        /** The daily puzzle of one calendar day, addressed by its ISO date. */
        fun daily(epochDay: Long): ContentKey =
            ContentKey(GameMode.Daily(epochDay), DailyPuzzle.contentIdFor(epochDay))

        /**
         * Inverse of [storageKey], for reading a persisted key back.
         *
         * @return null when the text carries no namespace, an unknown namespace
         *   or a daily date this build cannot parse. Callers treat that as "no
         *   such content" rather than as a crash, which is what lets an old or
         *   corrupted payload degrade instead of taking the title screen down.
         */
        fun fromStorageKey(text: String): ContentKey? {
            val slash = text.indexOf('/')
            if (slash <= 0 || slash == text.lastIndex) return null

            val namespace = text.substring(0, slash)
            val id = text.substring(slash + 1)
            if (id.any { it == '/' }) return null

            return when (namespace) {
                CAMPAIGN -> campaign(id)
                DAILY -> DailyPuzzle.parseIsoDate(id)?.let { daily(it) }
                else -> null
            }
        }
    }
}
