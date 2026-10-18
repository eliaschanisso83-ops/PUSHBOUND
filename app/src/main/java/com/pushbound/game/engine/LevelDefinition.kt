package com.pushbound.game.engine

/**
 * Immutable description of one level. The rendering derives from this data only,
 * and new levels (2-15 later) can be added without touching engine or UI code.
 */
data class LevelDefinition(
    val name: String,
    val width: Int,
    val height: Int,
    val walls: Set<Position>,
    val goals: Set<Position>,
    val playerStart: Position,
    val boxStarts: List<Position>
) {

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
         */
        fun fromAscii(name: String, rows: List<String>): LevelDefinition {
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
                name = name,
                width = width,
                height = height,
                walls = walls,
                goals = goals,
                playerStart = player
                    ?: throw IllegalArgumentException("Level must have a player start 'P'"),
                boxStarts = boxes
            )
        }
    }
}
