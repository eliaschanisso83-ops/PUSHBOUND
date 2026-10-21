package com.pushbound.game.engine.solver

import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.Position

/**
 * Static impossibility checks used BOTH by the solver (to prune branches) and
 * by the level validator (to reject bad content before it ships).
 *
 * CORNER DEADLOCK
 *
 * A box pushed into a corner it does not belong in can never leave: if the two
 * cells diagonally adjacent to it are walls in an L, the box has at most two
 * free sides, and pushing it back out is impossible because the player cannot
 * stand behind it. Any state containing such a box is permanently unsolvable
 * unless the box is already resting on a goal (the goal it sits on is the one it
 * was meant for, so it is finished, not stuck).
 *
 * This is the cheapest sound deadlock test. It deliberately catches only the
 * obvious cases: a false negative costs search time, a false positive would
 * reject a solvable level, so the test is written to never claim more than it
 * can prove.
 */
object DeadlockDetector {

    /** True when [position] is a wall or off the board — the two equivalent blockers. */
    fun isWallOrBeyond(level: LevelDefinition, position: Position): Boolean = level.isBlocked(position)

    /**
     * True when [box] sits in a corner it is not meant to occupy: two blocked
     * sides meeting at a right angle, and no goal under it.
     */
    fun isCornerDeadlockAt(level: LevelDefinition, box: Position): Boolean {
        if (box in level.goals) return false

        val up = isWallOrBeyond(level, Position(box.row - 1, box.col))
        val down = isWallOrBeyond(level, Position(box.row + 1, box.col))
        val left = isWallOrBeyond(level, Position(box.row, box.col - 1))
        val right = isWallOrBeyond(level, Position(box.row, box.col + 1))

        return (up && left) || (up && right) || (down && left) || (down && right)
    }

    /** True when ANY box in [boxes] is stuck in a corner it does not belong in. */
    fun hasCornerDeadlock(level: LevelDefinition, boxes: Set<Position>): Boolean =
        boxes.any { isCornerDeadlockAt(level, it) }

    /**
     * The floor cells of the board that sit in a fatal corner: places a box can
     * never return from. Used by the validator to report where a level is
     * fragile.
     */
    fun fatalCorners(level: LevelDefinition): List<Position> =
        (0 until level.height).flatMap { row ->
            (0 until level.width).map { col -> Position(row, col) }
        }.filter { it !in level.walls && isCornerDeadlockAt(level, it) }

    /**
     * Every floor cell a box can never reach a goal from, ignoring the other
     * boxes entirely.
     *
     * This subsumes [isCornerDeadlockAt] — a box in a fatal corner is by
     * definition on a dead cell — and it costs one pass over the board instead
     * of one test per box per state, which is why the search consults this set
     * and uses the corner test only for the root.
     *
     * HOW IT IS COMPUTED — run the pushes BACKWARDS. Start from every goal and
     * ask, for each direction, whether a box could have arrived here: it would
     * have started one step back and been pushed by a player standing one step
     * behind that. Both of those cells must be floor. Anything the backward
     * sweep cannot reach could never have reached a goal going forwards.
     *
     * WHY IT IS SOUND — the sweep assumes the player can stand wherever a push
     * needs it, so it OVER-approximates which cells a box can reach. Over-
     * approximating reachability can only leave genuinely-dead cells out of the
     * set, never the reverse, so a cell reported dead really is unreachable.
     * Other boxes are ignored, and ignoring them only makes a box MORE able to
     * reach a goal, so a cell that is dead alone is dead in the full puzzle too.
     */
    fun deadSquares(level: LevelDefinition): Set<Position> {
        val reachable = HashSet<Position>()
        val queue = ArrayDeque<Position>()

        for (goal in level.goals) {
            if (!level.isBlocked(goal) && reachable.add(goal)) queue.add(goal)
        }

        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            for (direction in Direction.entries) {
                val from = Position(
                    cell.row - direction.rowDelta,
                    cell.col - direction.colDelta
                )
                val stand = Position(
                    cell.row - 2 * direction.rowDelta,
                    cell.col - 2 * direction.colDelta
                )
                if (level.isBlocked(from) || level.isBlocked(stand)) continue
                if (reachable.add(from)) queue.add(from)
            }
        }

        val dead = HashSet<Position>()
        for (row in 0 until level.height) {
            for (col in 0 until level.width) {
                val cell = Position(row, col)
                if (!level.isBlocked(cell) && cell !in reachable) dead.add(cell)
            }
        }
        return dead
    }
}
