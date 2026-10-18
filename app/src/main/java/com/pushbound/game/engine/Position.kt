package com.pushbound.game.engine

/** A logical grid cell. Independent of screen resolution. */
data class Position(val row: Int, val col: Int) {

    fun moved(direction: Direction): Position =
        Position(row + direction.rowDelta, col + direction.colDelta)
}
