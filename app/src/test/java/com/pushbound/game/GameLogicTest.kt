package com.pushbound.game

import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameEngine
import com.pushbound.game.engine.GameState
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.MoveResult
import com.pushbound.game.engine.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core movement and push rules (milestone 3, section 22). */
class GameLogicTest {

    private fun level(vararg rows: String) = LevelDefinition.fromAscii("test", rows.toList())

    @Test
    fun playerMovesOntoEmptyFloor() {
        val lvl = level(
            "#####",
            "#   #",
            "# P #",
            "#   #",
            "#####"
        )
        val result = GameEngine.move(lvl, GameEngine.initialState(lvl), Direction.LEFT)

        assertTrue(result is MoveResult.Moved)
        result as MoveResult.Moved
        assertEquals(Position(2, 1), result.state.player)
        assertEquals(1, result.state.moves)
        assertNull(result.pushedBox)
        assertTrue(result.state.boxes.isEmpty())
    }

    @Test
    fun playerCannotMoveThroughWall() {
        val lvl = level(
            "#####",
            "# # #",
            "# P #",
            "#####"
        )
        val start = GameEngine.initialState(lvl)
        val result = GameEngine.move(lvl, start, Direction.UP)

        assertEquals(MoveResult.Blocked, result)
        assertEquals(0, start.moves)
    }

    @Test
    fun playerCannotMoveOutsideTheBoard() {
        val lvl = level("P")
        val start = GameEngine.initialState(lvl)
        for (direction in Direction.entries) {
            assertEquals(
                "Moving $direction off a 1x1 board must be blocked",
                MoveResult.Blocked,
                GameEngine.move(lvl, start, direction)
            )
        }
        assertEquals(0, start.moves)
    }

    @Test
    fun playerPushesBoxOneCell() {
        val lvl = level(
            "#######",
            "#     #",
            "# PB  #",
            "#     #",
            "#######"
        )
        val result = GameEngine.move(lvl, GameEngine.initialState(lvl), Direction.RIGHT)

        assertTrue(result is MoveResult.Moved)
        result as MoveResult.Moved
        assertEquals(Position(2, 3), result.state.player)
        assertEquals(setOf(Position(2, 4)), result.state.boxes)
        assertEquals(Position(2, 3) to Position(2, 4), result.pushedBox)
        assertEquals(1, result.state.moves)
    }

    @Test
    fun boxMovesOntoGoalAndCompletesTheLevel() {
        val lvl = level(
            "#######",
            "#     #",
            "# PBG #",
            "#     #",
            "#######"
        )
        val result = GameEngine.move(lvl, GameEngine.initialState(lvl), Direction.RIGHT)

        assertTrue(result is MoveResult.Moved)
        result as MoveResult.Moved
        assertEquals(setOf(Position(2, 4)), result.state.boxes)
        assertTrue(lvl.goals.contains(Position(2, 4)))
        assertTrue(GameEngine.isComplete(lvl, result.state))
    }

    @Test
    fun playerCannotPushBoxThroughWall() {
        val lvl = level(
            "#######",
            "#     #",
            "# PB# #",
            "#     #",
            "#######"
        )
        val start = GameEngine.initialState(lvl)
        val result = GameEngine.move(lvl, start, Direction.RIGHT)

        assertEquals(MoveResult.Blocked, result)
        assertEquals(setOf(Position(2, 3)), start.boxes)
        assertEquals(0, start.moves)
    }

    @Test
    fun playerCannotPushBoxOffTheBoard() {
        val lvl = level("PB")
        val start = GameEngine.initialState(lvl)
        val result = GameEngine.move(lvl, start, Direction.RIGHT)

        assertEquals(MoveResult.Blocked, result)
        assertEquals(setOf(Position(0, 1)), start.boxes)
        assertEquals(0, start.moves)
    }

    @Test
    fun playerCannotPushTwoBoxesAtOnce() {
        val lvl = level(
            "#######",
            "#     #",
            "# PBB #",
            "#     #",
            "#######"
        )
        val start = GameEngine.initialState(lvl)
        val result = GameEngine.move(lvl, start, Direction.RIGHT)

        assertEquals(MoveResult.Blocked, result)
        assertEquals(setOf(Position(2, 3), Position(2, 4)), start.boxes)
        assertEquals(0, start.moves)
    }

    @Test
    fun completionRequiresEveryBoxOnAGoal() {
        val lvl = level(
            "#########",
            "#   P   #",
            "# B G B #",
            "#   G   #",
            "#########"
        )
        val goals = setOf(Position(2, 4), Position(3, 4))

        // One box on a goal, the other loose: not solved yet.
        val oneLeft = GameState(
            player = Position(4, 4),
            boxes = setOf(Position(2, 4), Position(2, 6)),
            moves = 9
        )
        assertFalse(GameEngine.isComplete(lvl, oneLeft))

        val bothOnGoals = GameState(
            player = Position(4, 4),
            boxes = goals,
            moves = 9
        )
        assertTrue(GameEngine.isComplete(lvl, bothOnGoals))

        val noBoxes = GameState(player = Position(4, 4), boxes = emptySet(), moves = 0)
        assertFalse(GameEngine.isComplete(lvl, noBoxes))
    }

    @Test
    fun completedLevelIgnoresFurtherMovement() {
        val lvl = level(
            "#######",
            "#     #",
            "# PBG #",
            "#     #",
            "#######"
        )
        val session = com.pushbound.game.engine.GameSession(lvl)
        session.move(Direction.RIGHT)
        assertTrue(session.isComplete)

        val solved = session.state
        assertEquals(MoveResult.Blocked, session.move(Direction.LEFT))
        assertEquals(MoveResult.Blocked, session.move(Direction.UP))
        assertEquals(solved, session.state)
        assertEquals(1, session.state.moves)
    }
}
