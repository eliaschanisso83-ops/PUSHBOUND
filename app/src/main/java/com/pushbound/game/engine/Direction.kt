package com.pushbound.game.engine

/** The four grid moves. Row/column deltas are in logical cells. */
enum class Direction(val rowDelta: Int, val colDelta: Int) {
    UP(-1, 0),
    DOWN(1, 0),
    LEFT(0, -1),
    RIGHT(0, 1)
}
