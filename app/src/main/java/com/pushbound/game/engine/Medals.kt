package com.pushbound.game.engine

/**
 * Three-tier result for a finished level.
 *
 *  - [BRONZE] COMPLETED. The player solved it. This is the progression tier:
 *    finishing is always enough to unlock the next level, so nobody is ever
 *    blocked by a performance requirement.
 *  - [SILVER] EFFICIENT. Comfortably better than simply finishing.
 *  - [GOLD] PERFECT. At or under BOTH pars — the replay incentive.
 *
 * A medal is always computed against the level's own measured pars (the
 * shortest solution the validator found), never against the level number, so a
 * medal means the same thing on level 3 and on level 140.
 */
enum class Medal {
    BRONZE,
    SILVER,
    GOLD;

    /** True when this medal is at least as good as [other]. */
    fun atLeastAsGoodAs(other: Medal): Boolean = ordinal >= other.ordinal
}

/**
 * The silver band, expressed as a multiplier on each par. Silver is
 * deliberately reachable but not trivial: it sits between "finished" and
 * "perfect", so the tier rewards clean play without demanding optimality.
 *
 * Both factors are per-metric on purpose. Pushes are the scarce resource — a
 * player who takes extra steps but still pushes optimally has understood the
 * puzzle better than one who flails at the boxes — so the push allowance is
 * tighter than the move allowance.
 */
data class MedalThresholds(
    /** Multiplier on par moves at or under which silver is still earned. */
    val silverMoveFactor: Float,
    /** Multiplier on par pushes at or under which silver is still earned. */
    val silverPushFactor: Float
) {
    companion object {
        /** The campaign default: silver within ~35% of par moves, ~20% of par pushes. */
        val DEFAULT = MedalThresholds(silverMoveFactor = 1.35f, silverPushFactor = 1.20f)
    }
}

/**
 * Medal rules. Pure arithmetic, no Android, fully unit tested.
 *
 * The tiers are evaluated worst-first so the order is auditable by reading:
 * anything that misses gold entirely still earns bronze, because completion is
 * the only thing that is ever required of the player.
 */
object Medals {

    /**
     * @param moves the moves the player actually made.
     * @param pushes the pushes the player actually made.
     * @param parMoves gold threshold in moves; 0 means the level was never
     *   validated, in which case only bronze can be awarded (an unmeasured
     *   level must never hand out gold by accident).
     * @param parPushes gold threshold in pushes.
     * @param thresholds per-level override, or null for [MedalThresholds.DEFAULT].
     */
    fun evaluate(
        moves: Int,
        pushes: Int,
        parMoves: Int,
        parPushes: Int,
        thresholds: MedalThresholds? = null
    ): Medal {
        require(moves >= 0) { "moves must not be negative" }
        require(pushes >= 0) { "pushes must not be negative" }

        // Unvalidated level: completion is all that can be claimed.
        if (parMoves <= 0 || parPushes <= 0) return Medal.BRONZE

        // Gold: at or under both pars exactly. No tolerance — that is what
        // makes it worth replaying for.
        if (moves <= parMoves && pushes <= parPushes) return Medal.GOLD

        val band = thresholds ?: MedalThresholds.DEFAULT
        val withinMoves = moves <= (parMoves * band.silverMoveFactor).toInt()
        val withinPushes = pushes <= (parPushes * band.silverPushFactor).toInt()
        if (withinMoves && withinPushes) return Medal.SILVER

        return Medal.BRONZE
    }

    /**
     * The better of two medals for the same level. Progress records keep the
     * BEST result a player ever achieved, so replaying worse can never
     * downgrade a medal they already earned.
     */
    fun betterOf(a: Medal?, b: Medal?): Medal? = when {
        a == null -> b
        b == null -> a
        else -> if (a.ordinal >= b.ordinal) a else b
    }
}
