package com.pushbound.game.engine

/**
 * Conceptual difficulty bands. A level's band is DERIVED metadata, never the
 * source of its challenge — see the difficulty model, which scores a level from
 * its board size, box count, solution length, push count and mechanics.
 *
 * The bands exist so the campaign can group and gate content, and so the
 * thresholds stay tunable in one place once real level data exists.
 */
enum class Difficulty {
    EASY,
    NORMAL,
    HARD,
    EXPERT,
    MASTER;

    companion object {
        /**
         * Bands in increasing order. The scoring model maps a numeric score onto
         * this ordering, so inserting or renaming a band never renumbers levels.
         */
        val ordered: List<Difficulty> = entries.toList()
    }

    /** True when this band is at least as severe as [other]. */
    fun atLeastAsSevereAs(other: Difficulty): Boolean = ordinal >= other.ordinal
}
