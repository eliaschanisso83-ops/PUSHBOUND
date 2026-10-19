package com.pushbound.game

import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameSession
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.MoveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Directional character behaviour (milestone 5, sections 14 and 18):
 * direction change = sprite change, driven by the engine's facing.
 */
class CharacterFacingTest {

    private fun level(vararg rows: String) = LevelDefinition.fromAscii("test", rows.toList())

    private fun room() = level(
        "#######",
        "#     #",
        "#     #",
        "#  P  #",
        "#     #",
        "#     #",
        "#######"
    )

    private fun corridor() = level(
        "#########",
        "#       #",
        "#  PBG  #",
        "#       #",
        "#########"
    )

    @Test
    fun facingStartsDownTowardTheViewer() {
        val session = GameSession(room())
        assertEquals(Direction.DOWN, session.state.facing)
    }

    @Test
    fun sequenceUpDownRightDownLeftLeavesFacingLeft() {
        val session = GameSession(room())
        for (direction in listOf(Direction.UP, Direction.RIGHT, Direction.DOWN, Direction.LEFT)) {
            assertTrue(session.move(direction) is MoveResult.Moved)
        }
        assertEquals(Direction.LEFT, session.state.facing)
    }

    @Test
    fun sequenceLeftRightUpDownLeavesFacingDown() {
        val session = GameSession(room())
        for (direction in listOf(Direction.LEFT, Direction.RIGHT, Direction.UP, Direction.DOWN)) {
            assertTrue(session.move(direction) is MoveResult.Moved)
        }
        assertEquals(Direction.DOWN, session.state.facing)
    }

    @Test
    fun pushRecordsThePushDirectionAsFacing() {
        val session = GameSession(corridor())
        val result = session.move(Direction.RIGHT) // pushes the box onto the goal
        assertTrue(result is MoveResult.Moved)
        assertEquals(Direction.RIGHT, session.state.facing)
    }

    @Test
    fun blockedMoveKeepsThePreviousFacing() {
        val session = GameSession(room())
        session.move(Direction.UP) // success: facing UP
        assertEquals(Direction.UP, session.state.facing)

        session.move(Direction.UP)
        session.move(Direction.UP) // into the top wall: refused
        assertEquals(Direction.UP, session.state.facing)
    }

    @Test
    fun undoRestoresTheFacingTheSnapshotCarried() {
        val session = GameSession(corridor())
        val initial = session.state

        session.move(Direction.RIGHT) // push: facing RIGHT
        assertEquals(Direction.RIGHT, session.state.facing)

        assertTrue(session.undo())
        assertEquals(initial, session.state)
        assertEquals(Direction.DOWN, session.state.facing)
    }

    @Test
    fun restartResetsFacingToDown() {
        val session = GameSession(room())
        session.move(Direction.UP)
        session.move(Direction.LEFT)
        assertEquals(Direction.LEFT, session.state.facing)

        session.restart()
        assertEquals(Direction.DOWN, session.state.facing)
    }
}
