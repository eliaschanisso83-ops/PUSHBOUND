package com.pushbound.game

import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameEngine
import com.pushbound.game.engine.GameSession
import com.pushbound.game.engine.LevelDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Undo and restart behaviour (milestone 3, sections 8-9 and 22). */
class GameSessionTest {

    private fun level(vararg rows: String) = LevelDefinition.fromAscii("test", rows.toList())

    private fun corridor() = level(
        "#########",
        "#       #",
        "#  PBG  #",
        "#       #",
        "#########"
    )

    @Test
    fun undoRestoresPreviousStateIncludingPushes() {
        val lvl = corridor()
        val session = GameSession(lvl)
        val beforePush = session.state

        session.move(Direction.RIGHT) // pushes the box onto the goal
        assertTrue(session.isComplete)

        assertTrue(session.undo())
        assertEquals(beforePush, session.state)
        assertFalse(session.isComplete)
        assertEquals(0, session.state.moves)
    }

    @Test
    fun undoWorksRepeatedlyUntilTheLevelStart() {
        val lvl = corridor()
        val session = GameSession(lvl)
        val initial = session.state

        session.move(Direction.LEFT)
        session.move(Direction.LEFT)
        session.move(Direction.UP)
        session.move(Direction.RIGHT)
        assertEquals(4, session.state.moves)
        assertTrue(session.canUndo)

        while (session.canUndo) {
            assertTrue(session.undo())
        }
        assertEquals(initial, session.state)
        assertEquals(0, session.state.moves)
    }

    @Test
    fun undoWithNothingToUndoIsSafe() {
        val lvl = corridor()
        val session = GameSession(lvl)
        assertFalse(session.undo())
        assertFalse(session.undo())
        assertEquals(GameEngine.initialState(lvl), session.state)
    }

    @Test
    fun restartRestoresTheExactInitialState() {
        val lvl = corridor()
        val session = GameSession(lvl)
        val initial = session.state

        session.move(Direction.RIGHT) // solved in one push
        assertTrue(session.isComplete)

        session.restart()
        assertEquals(initial, session.state)
        assertEquals(0, session.state.moves)
        assertFalse(session.isComplete)
        assertFalse(session.canUndo)
    }

    @Test
    fun theLevelIsPlayableAgainAfterRestart() {
        val session = GameSession(corridor())
        session.move(Direction.RIGHT)
        session.restart()

        val result = session.move(Direction.RIGHT)
        assertTrue(result is com.pushbound.game.engine.MoveResult.Moved)
        assertEquals(1, session.state.moves)
        assertTrue(session.canUndo)
    }
}
