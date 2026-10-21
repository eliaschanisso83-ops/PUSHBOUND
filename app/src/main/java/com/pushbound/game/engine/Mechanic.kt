package com.pushbound.game.engine

/**
 * A rule feature a level relies on. Levels declare what they use so the
 * difficulty model, the campaign ordering and the future tutorial system can
 * reason about content without parsing grids.
 *
 * Deliberately small: the campaign ships only [PUSH]. The enum is the
 * extension point — a future mechanic is one new entry plus engine support,
 * and no existing level or engine branch has to change.
 */
enum class Mechanic {
    /** Plain pushing. Every level uses it. */
    PUSH
}
