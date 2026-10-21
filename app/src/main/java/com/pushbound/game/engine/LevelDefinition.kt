package com.pushbound.game.engine

/**
 * Immutable description of one level. The rendering derives from this data only,
 * and new levels can be added without touching engine or UI code.
 *
 * The class carries two kinds of data:
 *
 *  - THE PUZZLE (width, height, walls, goals, playerStart, boxStarts). These
 *    fully determine gameplay; the engine reads nothing else.
 *  - ITS PLACE IN THE CAMPAIGN (id, world, number) and its MEASURES
 *    (mechanics, difficulty, parMoves, parPushes, metadata). These are inert to
 *    the engine and exist so progression, medals, hints and difficulty never
 *    have to be derived from a level's position in a list.
 *
 * [parMoves] and [parPushes] are the GOLD thresholds: the shortest solution the
 * validator found. They are filled by the level validation pipeline
 * (see LevelValidator), never guessed, so a medal always means something.
 */
data class LevelDefinition(
    /** Stable identifier, unique across the campaign. Used for progress keys. */
    val id: String,
    /** Display name, e.g. "First Steps". */
    val name: String,
    /** World/chapter index, 1-based. Content is configurable, not fixed at 20 levels per world. */
    val world: Int,
    /** Level number WITHIN its world, 1-based. */
    val number: Int,
    val width: Int,
    val height: Int,
    val walls: Set<Position>,
    val goals: Set<Position>,
    val playerStart: Position,
    val boxStarts: List<Position>,

    /** Rule features this level relies on. */
    val mechanics: Set<Mechanic> = setOf(Mechanic.PUSH),

    /** Derived band; see the difficulty model. */
    val difficulty: Difficulty = Difficulty.NORMAL,

    /** Shortest solution length in moves (gold threshold). 0 means "not yet validated". */
    val parMoves: Int = 0,

    /** Shortest solution length in pushes (gold threshold). 0 means "not yet validated". */
    val parPushes: Int = 0,

    /**
     * Per-level silver band override, or null to use
     * [MedalThresholds.DEFAULT]. Present so a deliberately generous or strict
     * level can be tuned without touching the global rule.
     */
    val medalThresholds: MedalThresholds? = null,

    /** Optional free-form data (author, notes, tutorial hint, ...). */
    val metadata: Map<String, String> = emptyMap()
) {

    /** Number of boxes. A first-order difficulty input. */
    val boxCount: Int get() = boxStarts.size

    /** Floor cells the player may occupy (board minus walls). */
    val floorCount: Int get() = width * height - walls.size

    fun inBounds(position: Position): Boolean =
        position.row in 0 until height && position.col in 0 until width

    /** Walls and everything outside the board block movement. */
    fun isBlocked(position: Position): Boolean =
        !inBounds(position) || position in walls

    companion object {

        /**
         * Builds a level from an ASCII grid:
         *  '#' wall, ' ' open floor, 'G' goal, 'P' player start,
         *  'B' box, '+' box starting on a goal.
         *
         * The grid is the authoring format for the whole campaign; the optional
         * trailing parameters only describe where the level sits in the campaign
         * and how hard it turned out. Keeping the two-argument form working
         * means existing tests and one-off puzzles need no ceremony.
         *
         * @param id stable identifier; defaults to the name, slugified.
         */
        fun fromAscii(
            name: String,
            rows: List<String>,
            id: String = slug(name),
            world: Int = 1,
            number: Int = 1,
            mechanics: Set<Mechanic> = setOf(Mechanic.PUSH),
            difficulty: Difficulty = Difficulty.NORMAL,
            parMoves: Int = 0,
            parPushes: Int = 0,
            medalThresholds: MedalThresholds? = null,
            metadata: Map<String, String> = emptyMap()
        ): LevelDefinition {
            require(rows.isNotEmpty()) { "Level grid must not be empty" }
            val height = rows.size
            val width = rows.first().length
            require(rows.all { it.length == width }) { "All rows must have the same width" }

            val walls = mutableSetOf<Position>()
            val goals = mutableSetOf<Position>()
            val boxes = mutableListOf<Position>()
            var player: Position? = null

            for ((rowIndex, row) in rows.withIndex()) {
                for ((colIndex, symbol) in row.withIndex()) {
                    val position = Position(rowIndex, colIndex)
                    when (symbol) {
                        '#' -> walls += position
                        'G' -> goals += position
                        'B' -> boxes += position
                        '+' -> {
                            goals += position
                            boxes += position
                        }

                        'P' -> {
                            require(player == null) { "Level must have exactly one player start" }
                            player = position
                        }

                        ' ' -> Unit
                        else -> throw IllegalArgumentException("Unknown level symbol: '$symbol'")
                    }
                }
            }

            return LevelDefinition(
                id = id,
                name = name,
                world = world,
                number = number,
                width = width,
                height = height,
                walls = walls,
                goals = goals,
                playerStart = player
                    ?: throw IllegalArgumentException("Level must have a player start 'P'"),
                boxStarts = boxes,
                mechanics = mechanics,
                difficulty = difficulty,
                parMoves = parMoves,
                parPushes = parPushes,
                medalThresholds = medalThresholds,
                metadata = metadata
            )
        }

        /**
         * Lowercase, hyphen-separated identifier derived from a display name.
         * Pure string handling: stable across devices, locales and time, which
         * is what lets progress keys survive a rename-free upgrade.
         */
        fun slug(name: String): String =
            name.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifEmpty { "level" }
    }
}
