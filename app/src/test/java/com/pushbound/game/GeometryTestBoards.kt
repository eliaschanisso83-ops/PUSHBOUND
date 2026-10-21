package com.pushbound.game

import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.Position

/**
 * Geometry test boards — TEST layouts, not production levels.
 *
 * These exist to prove that internal walls are a first-class part of the
 * level format end to end: they are authored in the same ASCII the campaign
 * uses, they land in [LevelDefinition.walls], and from there they drive
 * player movement, crate movement, the solver, deadlock detection and the
 * renderer with no second representation anywhere.
 *
 * They are deliberately NOT registered in [Levels.campaign], so they cannot
 * be shipped by accident, cannot shift the campaign's difficulty distribution
 * and cannot appear in Level Select. Each one isolates a single structural
 * idea:
 *
 *  - [CORRIDOR]   one internal wall blocking the diagonal, forcing a route
 *  - [CHAMBER]    a U-shaped pocket whose goal is reachable from one side only
 *  - [TWO_ROUTES] two lanes joined by two gaps, so a crate can close a route
 *  - [DEADLOCK]   a genuine non-goal corner built from internal walls
 *
 * Every board was verified against the real solver: all four are solvable,
 * and their solutions are asserted in [LevelGeometryTest].
 */
object GeometryTestBoards {

    /** One board, authored exactly as the campaign authors one. */
    class Board(val id: String, val name: String, val rows: List<String>) {
        val level: LevelDefinition by lazy {
            LevelDefinition.fromAscii(name = name, rows = rows, id = id, world = 0, number = 0)
        }

        /**
         * Internal walls: wall cells that are NOT part of the outer rectangle.
         *
         * A border wall only makes the board a box, which is what made the
         * old boards read as empty rooms. An internal wall is the structure
         * that makes a board read as a maze and behave like a puzzle.
         */
        fun internalWalls(): Set<Position> = level.walls.filterTo(mutableSetOf()) { p ->
            p.row > 0 && p.row < level.height - 1 && p.col > 0 && p.col < level.width - 1
        }
    }

    /**
     * CORRIDOR — the internal wall at row 3 blocks the diagonal, so the crate
     * cannot simply be walked into place. It has to be pushed along the top
     * lane and then dropped down the right-hand column. 4 internal walls.
     */
    val CORRIDOR = Board(
        id = "test-a",
        name = "Corridor",
        rows = listOf(
            "########",
            "#      #",
            "#  B   #",
            "#  ##  #",
            "#  #   #",
            "# P# G #",
            "########"
        )
    )

    /**
     * CHAMBER — the goal sits in a U open only from above, so the crate can
     * only be delivered by coming straight down the goal's own column. Any
     * side approach is walled off. 4 internal walls.
     */
    val CHAMBER = Board(
        id = "test-b",
        name = "Chamber",
        rows = listOf(
            "########",
            "#      #",
            "#  B   #",
            "# P    #",
            "#  # # #",
            "#  #G# #",
            "########"
        )
    )

    /**
     * TWO_ROUTES — two lanes joined by TWO gaps. The player has to cross
     * between lanes to get behind each crate, so where a crate ends up
     * decides which of the two routes is still useful. 5 internal walls.
     */
    val TWO_ROUTES = Board(
        id = "test-c",
        name = "Two Routes",
        rows = listOf(
            "#########",
            "#       #",
            "#  B G  #",
            "#       #",
            "### # ###",
            "#       #",
            "# B   G #",
            "#   P   #",
            "#########"
        )
    )

    /**
     * DEADLOCK — the L-shaped wall makes (5,4) a fatal non-goal corner, and
     * that corner sits exactly one push left of (5,5): the cell the crate
     * must occupy for the only legal delivery into the goal at (6,5). A
     * single careless left push strands the crate with no way back.
     * 4 internal walls.
     */
    val DEADLOCK = Board(
        id = "test-d",
        name = "Deadlock",
        rows = listOf(
            "########",
            "#      #",
            "#  B   #",
            "#      #",
            "#  ##  #",
            "#  #   #",
            "#  # G #",
            "#  P   #",
            "########"
        )
    )

    /** The trap cell of [DEADLOCK], named so the test states its intent. */
    val DEADLOCK_TRAP: Position = Position(5, 4)

    /** The cell the crate must occupy to deliver into [DEADLOCK]'s goal. */
    val DEADLOCK_DELIVERY: Position = Position(5, 5)

    /** [DEADLOCK]'s goal. */
    val DEADLOCK_GOAL: Position = Position(6, 5)

    /** All four boards, in the order the tests walk them. */
    val ALL: List<Board> = listOf(CORRIDOR, CHAMBER, TWO_ROUTES, DEADLOCK)
}
