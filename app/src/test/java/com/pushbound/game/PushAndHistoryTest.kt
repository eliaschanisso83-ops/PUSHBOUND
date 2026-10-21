package com.pushbound.game

import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameEngine
import com.pushbound.game.engine.GameSession
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.MoveResult
import com.pushbound.game.engine.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two counters and the undo/redo history contract.
 *
 * Moves and pushes are tracked SEPARATELY because they measure different
 * skills. Redo is the mirror of undo and must obey the standard contract: a
 * new action after undo abandons the abandoned branch.
 */
class PushAndHistoryTest {

    private fun level(vararg rows: String) = LevelDefinition.fromAscii("test", rows.toList())

    /**
     * A corridor: player, box, goal in a line — one push solves it.
     * Columns 0 and 8 are walls; row 2 is `P` at col 3, `B` at col 4, `G` at col 5,
     * so pushing RIGHT takes the box from (2,4) onto the goal at (2,5).
     */
    private fun corridor() = level(
        "#########",
        "#       #",
        "#  PBG  #",
        "#       #",
        "#########"
    )

    /** Open room used to walk around without pushing anything. */
    private fun room() = level(
        "#######",
        "#     #",
        "#     #",
        "#  P  #",
        "#     #",
        "#     #",
        "#######"
    )

    // ------------------------------------------------------------- counters --

    @Test
    fun bothCountersStartAtZero() {
        val session = GameSession(corridor())
        assertEquals(0, session.state.moves)
        assertEquals(0, session.state.pushes)
    }

    @Test
    fun walkingIncreasesMovesButNeverPushes() {
        val session = GameSession(room())

        session.move(Direction.LEFT)
        session.move(Direction.UP)
        session.move(Direction.RIGHT)

        assertEquals(3, session.state.moves)
        assertEquals("walking must never count as a push", 0, session.state.pushes)
    }

    @Test
    fun aPushCountsExactlyOneMoveAndOnePush() {
        val session = GameSession(corridor())

        // RIGHT pushes the box onto the goal in a single action.
        val result = session.move(Direction.RIGHT)
        assertTrue(result is MoveResult.Moved)

        assertEquals(1, session.state.moves)
        assertEquals(1, session.state.pushes)
    }

    @Test
    fun aRefusedPushCountsNothing() {
        val lvl = level(
            "#######",
            "#     #",
            "# PB# #",
            "#     #",
            "#######"
        )
        val session = GameSession(lvl)

        // The wall behind the box refuses the push.
        assertEquals(MoveResult.Blocked, session.move(Direction.RIGHT))

        assertEquals("a blocked move must not count as a move", 0, session.state.moves)
        assertEquals("a blocked push must not count as a push", 0, session.state.pushes)
    }

    @Test
    fun pushingIntoASecondBoxCountsNothing() {
        val lvl = level(
            "#######",
            "#     #",
            "# PBB #",
            "#     #",
            "#######"
        )
        val session = GameSession(lvl)

        assertEquals(MoveResult.Blocked, session.move(Direction.RIGHT))

        assertEquals(0, session.state.moves)
        assertEquals(0, session.state.pushes)
    }

    @Test
    fun pushesAccumulateAcrossSeveralBoxes() {
        // Two boxes, each solved by exactly one push, in opposite directions:
        //
        //   #####
        //   #G  #   goal  <- box 1 pushed UP
        //   #B  #
        //   # P #   player starts here
        //   #B  #   box 2 pushed DOWN
        //   #G  #   goal
        //   #####
        val lvl = level(
            "#####",
            "#G  #",
            "#B  #",
            "# P #",
            "#B  #",
            "#G  #",
            "#####"
        )
        val session = GameSession(lvl)

        // Walk beside the upper box.
        assertTrue(session.move(Direction.LEFT) is MoveResult.Moved)
        assertEquals(1, session.state.moves)
        assertEquals(0, session.state.pushes)

        // Push the upper box onto its goal.
        assertTrue(session.move(Direction.UP) is MoveResult.Moved)
        assertEquals(2, session.state.moves)
        assertEquals(1, session.state.pushes)

        // Walk back down beside the lower box.
        assertTrue(session.move(Direction.DOWN) is MoveResult.Moved)
        assertEquals(3, session.state.moves)
        assertEquals(1, session.state.pushes)

        // Push the lower box onto its goal — the level completes.
        assertTrue(session.move(Direction.DOWN) is MoveResult.Moved)
        assertEquals(4, session.state.moves)
        assertEquals(2, session.state.pushes)
        assertTrue(session.isComplete)
    }

    @Test
    fun theEngineReportsThePushedBoxAndTheSessionCountsIt() {
        val session = GameSession(corridor())
        val result = session.move(Direction.RIGHT) as MoveResult.Moved

        assertEquals(Position(2, 4) to Position(2, 5), result.pushedBox)
        assertEquals(setOf(Position(2, 5)), session.state.boxes)
        assertEquals(1, session.state.pushes)
    }

    // ----------------------------------------------------------------- redo --

    @Test
    fun redoIsUnavailableAtTheStartOfALevel() {
        val session = GameSession(corridor())
        assertFalse(session.canRedo)
        assertFalse("redoing with nothing to redo must be safe", session.redo())
    }

    @Test
    fun redoReplaysTheUndoneMove() {
        val session = GameSession(corridor())
        session.move(Direction.LEFT)
        val afterFirstMove = session.state

        assertTrue(session.undo())
        assertTrue(session.canRedo)

        assertTrue(session.redo())
        assertEquals(afterFirstMove, session.state)
        assertEquals(1, session.state.moves)
        assertFalse("the redo branch is consumed", session.canRedo)
    }

    @Test
    fun undoAndRedoRoundTripToTheExactSameState() {
        val session = GameSession(corridor())
        session.move(Direction.LEFT)
        session.move(Direction.UP)
        session.move(Direction.LEFT)
        val visited = session.state

        assertTrue(session.undo())
        assertTrue(session.redo())

        assertEquals(visited, session.state)
    }

    @Test
    fun redoRestoresBothCounters() {
        val session = GameSession(corridor())

        session.move(Direction.RIGHT) // push onto the goal
        val solved = session.state
        assertEquals(1, session.state.moves)
        assertEquals(1, session.state.pushes)

        assertTrue(session.undo())
        assertEquals(0, session.state.moves)
        assertEquals("undo must restore the push counter too", 0, session.state.pushes)

        assertTrue(session.redo())
        assertEquals(solved, session.state)
        assertEquals(1, session.state.moves)
        assertEquals(1, session.state.pushes)
    }

    @Test
    fun redoRestoresTheBoxPosition() {
        val session = GameSession(corridor())
        val startBoxes = session.state.boxes

        session.move(Direction.RIGHT) // push
        assertTrue(session.state.boxes != startBoxes)

        assertTrue(session.undo())
        assertEquals(startBoxes, session.state.boxes)

        assertTrue(session.redo())
        assertEquals(setOf(Position(2, 5)), session.state.boxes)
    }

    @Test
    fun aNewActionAfterUndoClearsTheRedoHistory() {
        val session = GameSession(corridor())
        session.move(Direction.LEFT)
        session.move(Direction.LEFT)
        assertTrue(session.undo())
        assertTrue(session.canRedo)

        // A different action abandons the forward branch.
        session.move(Direction.UP)
        assertFalse("a new action must clear redo", session.canRedo)
        assertFalse(session.redo())
    }

    @Test
    fun redoSurvivesMultipleUndos() {
        val session = GameSession(corridor())
        session.move(Direction.LEFT)
        session.move(Direction.LEFT)
        session.move(Direction.UP)
        val target = session.state

        assertTrue(session.undo())
        assertTrue(session.undo())
        assertTrue(session.canRedo)

        assertTrue(session.redo())
        assertTrue(session.redo())
        assertEquals(target, session.state)
        assertFalse(session.canRedo)
    }

    @Test
    fun undoFromACompletedStateThenRedoRestoresCompletion() {
        val session = GameSession(corridor())

        session.move(Direction.RIGHT)
        assertTrue(session.isComplete)

        assertTrue(session.undo())
        assertFalse("undoing out of a solved level must unsolve it", session.isComplete)

        assertTrue(session.redo())
        assertTrue("redoing back into the solve must restore it", session.isComplete)
    }

    @Test
    fun restartClearsBothHistories() {
        val session = GameSession(corridor())
        session.move(Direction.RIGHT)
        session.restart()
        assertFalse("restart must clear the redo history", session.canRedo)
        assertFalse(session.redo())

        session.move(Direction.LEFT)
        assertTrue(session.undo())
        session.restart()

        assertFalse(session.canUndo)
        assertFalse(session.canRedo)
        assertEquals(0, session.state.moves)
        assertEquals(0, session.state.pushes)
        assertEquals(GameEngine.initialState(corridor()), session.state)
    }

    @Test
    fun historyNeverGrowsWithoutABound() {
        // Unlimited undo: walk far past the level start and still get back.
        val lvl = room()
        val session = GameSession(lvl)
        val initial = session.state

        val steps = listOf(
            Direction.UP, Direction.UP, Direction.LEFT, Direction.LEFT,
            Direction.DOWN, Direction.DOWN, Direction.RIGHT, Direction.RIGHT
        )
        repeat(3) { steps.forEach { session.move(it) } }
        assertEquals(24, session.state.moves)

        var guard = 0
        while (session.canUndo) {
            assertTrue(session.undo())
            guard++
            assertTrue("undo must not loop forever", guard < 1000)
        }
        assertEquals(24, guard)
        assertEquals(initial, session.state)
    }
}
