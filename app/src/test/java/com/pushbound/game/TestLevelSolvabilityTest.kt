package com.pushbound.game

import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameEngine
import com.pushbound.game.engine.GameState
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.MoveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guarantees the prototype level is a real, solvable puzzle. */
class TestLevelSolvabilityTest {

    @Test
    fun prototypeLevelIsSolvable() {
        val shortest = shortestSolution(Levels.first)
        assertNotNull("The prototype level must be solvable", shortest)
        val moves = shortest!!
        assertTrue("Expected real navigation, needed only $moves moves", moves >= 8)
        assertTrue("Prototype should stay small, needed $moves moves", moves <= 30)
    }

    @Test
    fun documentedSolutionCompletesTheLevel() {
        val session = com.pushbound.game.engine.GameSession(Levels.first)

        // Walk to the left of the box, push it right, drop below it,
        // push it up, step to its right, push it left onto the goal.
        val documented = listOf(
            Direction.LEFT, Direction.LEFT, Direction.UP, Direction.UP,
            Direction.RIGHT,                                     // push 1
            Direction.LEFT, Direction.DOWN, Direction.DOWN, Direction.RIGHT,
            Direction.RIGHT, Direction.UP,
            Direction.UP,                                        // push 2
            Direction.RIGHT, Direction.UP,
            Direction.LEFT                                       // push 3 -> goal
        )
        for (direction in documented) {
            val result = session.move(direction)
            assertTrue("Move $direction in the documented solution must be valid", result is MoveResult.Moved)
        }

        assertTrue(session.isComplete)
        assertEquals(documented.size, session.state.moves)
    }

    /** Breadth-first search over immutable states; tiny board, so this is instant. */
    private fun shortestSolution(level: LevelDefinition): Int? {
        val start = GameEngine.initialState(level)
        data class Node(val state: GameState, val depth: Int)

        val queue = ArrayDeque<Node>()
        queue.add(Node(start, 0))
        val seen = mutableSetOf(start)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (GameEngine.isComplete(level, node.state)) return node.depth
            for (direction in Direction.entries) {
                val result = GameEngine.move(level, node.state, direction)
                if (result is MoveResult.Moved && seen.add(result.state)) {
                    queue.add(Node(result.state, node.depth + 1))
                }
            }
        }
        return null
    }
}
