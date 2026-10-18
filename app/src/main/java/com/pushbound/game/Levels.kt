package com.pushbound.game

import com.pushbound.game.engine.LevelDefinition

/**
 * Level data is plain text, so Levels 2-15 can be appended later without
 * touching the engine, the renderer, or the controls.
 */
object Levels {

    /**
     * Milestone 3 prototype level (9x8). Designed to exercise the whole loop:
     * walk around, approach the box from the left, push it right, reposition
     * below, push it up, reposition right, push it left onto the goal.
     * The wall stub under the box blocks the "cheap" straight-up push.
     */
    val first: LevelDefinition = LevelDefinition.fromAscii(
        name = "Level 1",
        rows = listOf(
            "#########",
            "#       #",
            "# ##    #",
            "#  G    #",
            "#  B    #",
            "#  #    #",
            "#   P   #",
            "#########"
        )
    )

    val all: List<LevelDefinition> = listOf(first)
}
