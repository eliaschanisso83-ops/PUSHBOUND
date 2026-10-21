package com.pushbound.game.engine.solver

import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameEngine
import com.pushbound.game.engine.GameState
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.MoveResult
import com.pushbound.game.engine.Position
import java.util.HashMap
import java.util.PriorityQueue

/**
 * A shortest solution to a level.
 *
 * [moves] is the exact ordered move list: replaying it through [GameEngine]
 * from the level's initial state completes the level, and no shorter sequence
 * does. [moveCount] and [pushCount] are the two separate counters that
 * movement produces — the medal thresholds are read from these, not from the
 * raw level number.
 */
data class Solution(
    val moves: List<Direction>,
    val moveCount: Int,
    val pushCount: Int
)

/**
 * Shortest-path solver over PUSH states rather than over raw player steps.
 *
 * SCOPE — development, validation, hints and background warm-up. Never part of
 * the real-time gameplay loop; movement never calls it.
 *
 * WHY PUSHES RATHER THAN MOVES — measured on the shipped content before this
 * rewrite (binary-searched `maxNodes`, so these are the exact state counts the
 * step-based search had to visit):
 *
 * ```
 *   1 box   (w1-1, 39 floor cells)      411 states     4 ms
 *   2 boxes (w1-3, 30 floor cells)      721 states     3 ms
 *   2 boxes (w5-2, 42 floor cells)      825 states     3 ms
 *   3 boxes (w5-3, 56 floor cells)  246,790 states  4,045 ms
 * ```
 *
 * A step-based breadth-first search turns every single step of every walk into
 * its own state, so the player's position multiplies every box arrangement —
 * and that multiplication is what exploded: adding one box to a slightly larger
 * board cost ~300x, not the ~10x the box arrangements alone would suggest.
 *
 * Pushing is what actually changes the puzzle. Between pushes the player only
 * walks, and walking never changes the boxes, so a whole walk collapses into
 * ONE edge whose weight is that walk's length. The state becomes
 * (boxes, player) where the player only ever occupies cells it pushed from.
 *
 * WHY THIS STILL RETURNS THE FEWEST MOVES — a push solution costs
 * `sum over pushes of (walk length + 1)`. Each walk segment can be shortened to
 * the shortest path within the reachable region that exists at that moment,
 * because walking neither moves a box nor changes which cells are reachable
 * afterwards. So no solution is cheaper than that sum, and a shortest path over
 * this graph attains it: **Dijkstra** over non-negative weights yields the true
 * move minimum, not merely the push minimum. The push count falls out of the
 * same path and is reported as-is.
 *
 * Ties are broken by insertion index and the direction order is fixed, so the
 * result is deterministic across runs and devices.
 *
 * PRUNING — three dead-end tests, none of which can reject a solvable state:
 *
 *  1. DEAD SQUARE ([DeadlockDetector.deadSquares]) — a cell no box can reach a
 *     goal from. Computed once per solve and consulted as a bitset. It subsumes
 *     the corner test, so during the sweep only the box that just moved is
 *     checked, and the root checks all of them once.
 *  2. CORNER DEADLOCK ([DeadlockDetector.isCornerDeadlockAt]) — kept explicit
 *     because it is the rule the level validator reports against, and because
 *     it documents the reasoning behind the dead-square sweep.
 *  3. FROZEN 2×2 BLOCK ([hasFrozenBlockAt]) — a configuration deadlock the
 *     per-cell sweep cannot see. See the derivation there for why a full block
 *     of walls and boxes provably cannot move.
 *
 * A NODE BUDGET bounds the work, reported as [Outcome.BUDGET_EXHAUSTED] —
 * never as unsolvable, because wrongly calling a level unsolvable would fail
 * validation of perfectly good content.
 *
 * EXACT STATES — states are keyed on the actual board with its real walls,
 * never on a translation-normalised box cluster. That normalisation is a
 * classic speed-up and it is UNSOUND on a bounded board: the walls do not
 * translate with the boxes, so two identical relative arrangements can have
 * entirely different futures, and merging them prunes real solutions — which is
 * exactly how a solvable level gets reported as unsolvable.
 *
 * SELF-VERIFICATION — before returning, the candidate move list is replayed
 * through the real [GameEngine] and must complete the level while reproducing
 * both counters as deltas. The solver cannot hand back a solution the game
 * itself would reject.
 */
object LevelSolver {

    /** Default search budget in finalised states. Generous for these boards. */
    const val DEFAULT_MAX_NODES = 400_000

    /** Why a solve attempt did not produce a [Solution]. */
    enum class Outcome {
        SOLVED,
        /** The budget ran out. The level may still be solvable. */
        BUDGET_EXHAUSTED,
        /** Every reachable state was explored and none was complete. */
        UNSOLVABLE
    }

    data class Result(val outcome: Outcome, val solution: Solution?) {
        val solved: Boolean get() = outcome == Outcome.SOLVED && solution != null
    }

    // ---------------------------------------------------------------- search --

    /** Board indices for every cell a player or a box can occupy. */
    private class Board(level: LevelDefinition) {
        val height = level.height
        val width = level.width
        val cellCount: Int

        /** -1 on walls and off-board cells. */
        private val indexOf: IntArray

        /** cell -> row * width + col. */
        private val packedOf: IntArray

        init {
            val index = IntArray(height * width) { -1 }
            var next = 0
            for (row in 0 until height) {
                for (col in 0 until width) {
                    if (!level.isBlocked(Position(row, col))) {
                        index[row * width + col] = next++
                    }
                }
            }
            indexOf = index
            packedOf = IntArray(next)
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val cell = index[row * width + col]
                    if (cell >= 0) packedOf[cell] = row * width + col
                }
            }
            cellCount = next
        }

        fun index(row: Int, col: Int): Int =
            if (row in 0 until height && col in 0 until width) indexOf[row * width + col] else -1

        fun index(position: Position): Int = index(position.row, position.col)

        fun position(cell: Int): Position {
            val packed = packedOf[cell]
            return Position(packed / width, packed % width)
        }

        fun row(cell: Int): Int = packedOf[cell] / width

        fun col(cell: Int): Int = packedOf[cell] % width
    }

    /**
     * Boxes as a bitset over [Board] cells: fixed size, no hashing, and no
     * per-state `Position` allocation inside the hot loop.
     */
    private class Boxes(val bits: LongArray) {
        fun has(cell: Int): Boolean = (bits[cell ushr 6] and (1L shl (cell and 63))) != 0L

        fun with(cell: Int): Boxes {
            val copy = bits.copyOf()
            copy[cell ushr 6] = copy[cell ushr 6] or (1L shl (cell and 63))
            return Boxes(copy)
        }

        fun minus(cell: Int): Boxes {
            val copy = bits.copyOf()
            copy[cell ushr 6] = copy[cell ushr 6] and (1L shl (cell and 63)).inv()
            return Boxes(copy)
        }

        fun count(): Int {
            var total = 0
            for (word in bits) total += java.lang.Long.bitCount(word)
            return total
        }
    }

    /**
     * States are identified by the box bitset AND the player's cell.
     *
     * The player must be part of the key: two states with identical boxes but a
     * player standing somewhere different have different walk costs from here,
     * so merging them would keep the cheaper cost and discard the other path's
     * geometry — which does not merely slow the search down, it changes the
     * answer. `player` is a `var` only so one probe instance can be reused for
     * lookups without allocating a key per candidate.
     */
    private class StateKey(val boxes: LongArray, var player: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is StateKey) return false
            if (player != other.player) return false
            return boxes.contentEquals(other.boxes)
        }

        override fun hashCode(): Int {
            var hash = player
            for (word in boxes) hash = 31 * hash + (word xor (word ushr 32)).toInt()
            return hash
        }
    }

    /**
     * One search state plus how it was reached.
     *
     * [pushCell] / [pushDir] describe the PUSH that produced it and are null
     * only for the root. [cell] is where the PLAYER stands in this state, i.e.
     * the cell the box vacated — not the box's new cell, which is what made the
     * first version of this search walk from the wrong place.
     *
     * The walk that got the player behind the box is deliberately NOT stored:
     * it is recomputed once during reconstruction, keeping the hot loop free of
     * path bookkeeping.
     */
    private class Node(
        val key: StateKey,
        val boxes: Boxes,
        val cell: Int,
        val parent: Int,
        val pushCell: Int,
        val pushDir: Direction?,
        val cost: Int
    )

    /** Solved with the fewest moves from the level start, or null. */
    fun solve(level: LevelDefinition, maxNodes: Int = DEFAULT_MAX_NODES): Solution? =
        solveDetailed(level, maxNodes).solution

    fun solveDetailed(level: LevelDefinition, maxNodes: Int = DEFAULT_MAX_NODES): Result =
        solveFrom(level, GameEngine.initialState(level), maxNodes)

    /**
     * Solves from an arbitrary mid-level state, not just the level start.
     *
     * This is what the hint system needs: a player who has wandered off the
     * optimal line still deserves an honest next step, and the cached solution
     * for the level start no longer applies to where they actually are. The
     * returned [Solution] is relative to THIS state — replaying it from here
     * completes the level, with counters compared as deltas.
     *
     * Because the player may have painted themselves into a corner, an
     * unsolvable answer is a legitimate result here, not a bug.
     */
    fun solveFrom(
        level: LevelDefinition,
        start: GameState,
        maxNodes: Int = DEFAULT_MAX_NODES
    ): Result {
        val board = Board(level)
        val startCell = board.index(start.player)
        if (startCell < 0) return Result(Outcome.UNSOLVABLE, null)

        val startBoxes = encode(board, start.boxes) ?: return Result(Outcome.UNSOLVABLE, null)
        val goalBits = encode(board, level.goals)
        // Computed once per solve: the set of cells a box can never reach a goal
        // from. It subsumes the corner test (a fatal corner is a dead cell) and
        // is consulted as a bitset, so it costs no allocation per state.
        val deadBits = encode(board, DeadlockDetector.deadSquares(level))
        val boxCount = startBoxes.count()

        // A state with no boxes has nothing left to push.
        if (boxCount == 0) return Result(Outcome.SOLVED, Solution(emptyList(), 0, 0))

        // Every box counts, because a mid-level state may already have boxes
        // resting on their goals — that is a normal state, not an empty level.
        if (onAllGoals(startBoxes, goalBits, boxCount)) {
            return Result(Outcome.SOLVED, Solution(emptyList(), 0, 0))
        }
        // The root is the one state where no box has been "just moved", so every
        // box needs the corner test here.
        if (DeadlockDetector.hasCornerDeadlock(level, start.boxes)) {
            return Result(Outcome.UNSOLVABLE, null)
        }
        // And likewise the dead-square test, which subsumes the corner one — the
        // per-state sweep can only test the box that just moved, so the boxes
        // that STARTED on a dead cell are only ever caught here.
        if (deadBits != null && overlaps(startBoxes, deadBits)) {
            return Result(Outcome.UNSOLVABLE, null)
        }

        val words = (board.cellCount + 63) / 64
        val rootNodeKey = StateKey(startBoxes.bits.copyOf(), startCell)
        val nodes = ArrayList<Node>(1024)
        nodes.add(Node(rootNodeKey, startBoxes, startCell, -1, -1, null, 0))

        // Best cost known per state, so a second route to it is discarded
        // instead of growing the queue.
        val best = HashMap<StateKey, Int>(2048)
        best[rootNodeKey] = 0

        // (cost << 32) | index — natural Long ordering makes cost the primary
        // key and insertion index the tie-breaker, which is what makes repeated
        // runs reproduce the same solution.
        val queue = PriorityQueue<Long>(1024)
        queue.add(pack(0, 0))

        // Scratch for the per-state reachability sweep, claimed by epoch rather
        // than re-cleared so a board-sized fill never lands in the inner loop.
        val distance = IntArray(board.cellCount)
        val epoch = IntArray(board.cellCount)
        val walkQueue = IntArray(board.cellCount)
        val probeBoxes = LongArray(words)
        val probe = StateKey(probeBoxes, -1)
        var clock = 0
        var explored = 0

        while (queue.isNotEmpty()) {
            val index = unpackIndex(queue.poll())
            val node = nodes[index]

            // A stale entry: the same state was re-inserted at a better cost
            // and only the latest one is worth expanding.
            if (best[node.key] != node.cost) continue

            explored++
            if (explored > maxNodes) return Result(Outcome.BUDGET_EXHAUSTED, null)

            if (onAllGoals(node.boxes, goalBits, boxCount)) {
                val candidate = reconstruct(board, start.player, startBoxes, nodes, index)
                // Trust nothing the engine has not confirmed.
                return if (replaysToCompletion(level, start, candidate)) {
                    Result(Outcome.SOLVED, candidate)
                } else {
                    Result(Outcome.UNSOLVABLE, null)
                }
            }

            reachable(board, node.cell, node.boxes, distance, epoch, ++clock, walkQueue)

            var boxCell = 0
            while (boxCell < board.cellCount) {
                if (!node.boxes.has(boxCell)) {
                    boxCell++
                    continue
                }
                val boxRow = board.row(boxCell)
                val boxCol = board.col(boxCell)

                for (direction in Direction.entries) {
                    // The square the player must occupy to push this box.
                    val behind = board.index(
                        boxRow - direction.rowDelta,
                        boxCol - direction.colDelta
                    )
                    if (behind < 0 || epoch[behind] != clock) continue
                    val beyond = board.index(
                        boxRow + direction.rowDelta,
                        boxCol + direction.colDelta
                    )
                    if (beyond < 0 || node.boxes.has(beyond)) continue

                    // Only the box that moved can have become newly stuck, and
                    // only its new cell needs testing: every other box was
                    // already certified when it landed, and the root certified
                    // the ones that started there.
                    if (deadBits != null && deadBits.has(beyond)) continue

                    val nextBoxes = node.boxes.minus(boxCell).with(beyond)
                    // Evaluated AFTER the push: a block that only fills up once
                    // the box lands there is precisely the one that matters.
                    if (hasFrozenBlockAt(board, goalBits, nextBoxes, beyond)) continue

                    val cost = node.cost + distance[behind] + 1

                    System.arraycopy(nextBoxes.bits, 0, probeBoxes, 0, words)
                    probe.player = boxCell
                    val knownCost = best[probe]
                    if (knownCost != null && knownCost <= cost) continue

                    val key = StateKey(nextBoxes.bits, boxCell)
                    best[key] = cost
                    val child = nodes.size
                    nodes.add(Node(key, nextBoxes, boxCell, index, boxCell, direction, cost))
                    queue.add(pack(cost, child))
                }
                boxCell++
            }
        }

        return Result(Outcome.UNSOLVABLE, null)
    }

    // ----------------------------------------------------------- primitives --

    private fun pack(cost: Int, index: Int): Long =
        (cost.toLong() shl 32) or (index.toLong() and 0xFFFFFFFFL)

    private fun unpackIndex(packed: Long): Int = (packed and 0xFFFFFFFFL).toInt()

    private fun encode(board: Board, positions: Set<Position>): Boxes? {
        val bits = LongArray((board.cellCount + 63) / 64)
        for (position in positions) {
            val cell = board.index(position)
            if (cell < 0) return null
            bits[cell ushr 6] = bits[cell ushr 6] or (1L shl (cell and 63))
        }
        return Boxes(bits)
    }

    /**
     * True when every box sits on a goal.
     *
     * Counted as "boxes on goals == box count" rather than "boxes == goals",
     * because a mid-level state may have more goals than the level has boxes,
     * and assuming the two are equal would call an unfinished level complete.
     */
    private fun onAllGoals(boxes: Boxes, goalBits: Boxes?, boxCount: Int): Boolean {
        if (goalBits == null) return false
        var onGoal = 0
        for (word in goalBits.bits.indices) {
            onGoal += java.lang.Long.bitCount(boxes.bits[word] and goalBits.bits[word])
        }
        return onGoal == boxCount
    }

    /** True when [a] and [b] share at least one cell. */
    private fun overlaps(a: Boxes, b: Boxes): Boolean {
        for (word in a.bits.indices) {
            if (a.bits[word] and b.bits[word] != 0L) return true
        }
        return false
    }

    /**
     * Breadth-first walk distances from [start] for every cell reachable
     * without crossing a box.
     *
     * [stamp] / [clock] let each call claim the array without clearing it, so a
     * board-sized zeroing pass never lands in the inner loop.
     */
    private fun reachable(
        board: Board,
        start: Int,
        boxes: Boxes,
        distance: IntArray,
        stamp: IntArray,
        clock: Int,
        queue: IntArray
    ) {
        var head = 0
        var tail = 0

        stamp[start] = clock
        distance[start] = 0
        queue[tail++] = start

        while (head < tail) {
            val cell = queue[head++]
            val nextDistance = distance[cell] + 1
            val row = board.row(cell)
            val col = board.col(cell)

            for (direction in Direction.entries) {
                val next = board.index(row + direction.rowDelta, col + direction.colDelta)
                if (next < 0) continue
                if (stamp[next] == clock) continue
                if (boxes.has(next)) continue
                stamp[next] = clock
                distance[next] = nextDistance
                queue[tail++] = next
            }
        }
    }

    /**
     * True when [box] sits in a 2×2 block made entirely of walls and boxes,
     * with some box in that block still off its goal.
     *
     * WHY IT IS SOUND — take any box in such a block and any push direction.
     * If the destination cell lies inside the block it is a wall or a box, so
     * the push is refused. If the destination lies outside, the box is on the
     * block's edge pushing outward, which puts the cell the player would have
     * to stand on the OPPOSITE side: one step deeper into the block, and
     * therefore a wall or a box. The player can never stand there. So no box in
     * a full block can ever move, by any push, from any angle.
     *
     * A box resting on its goal is finished rather than stuck, so a block only
     * kills the state when a box in it still has somewhere to go.
     *
     * Only blocks containing the box that just moved can change this way —
     * every other block lost its occupant if anything.
     */
    private fun hasFrozenBlockAt(
        board: Board,
        goalBits: Boxes?,
        boxes: Boxes,
        box: Int
    ): Boolean {
        if (goalBits == null) return false

        val boxRow = board.row(box)
        val boxCol = board.col(box)
        for (top in (boxRow - 1)..boxRow) {
            for (left in (boxCol - 1)..boxCol) {
                var full = true
                var boxesInBlock = 0
                var offGoalBoxes = 0

                for (dr in 0..1) {
                    for (dc in 0..1) {
                        val cell = board.index(top + dr, left + dc)
                        if (cell < 0) continue // wall or off-board: block material
                        if (!boxes.has(cell)) {
                            full = false
                            break
                        }
                        boxesInBlock++
                        if (!goalBits.has(cell)) offGoalBoxes++
                    }
                    if (!full) break
                }

                if (full && boxesInBlock > 0 && offGoalBoxes > 0) return true
            }
        }
        return false
    }

    // ------------------------------------------------------- reconstruction --

    /**
     * Walks the parent chain back to the root, then replays it forward,
     * recomputing each walk segment, so the result is a plain move list the
     * engine can play directly.
     */
    private fun reconstruct(
        board: Board,
        startPlayer: Position,
        startBoxes: Boxes,
        nodes: List<Node>,
        leaf: Int
    ): Solution {
        val chain = ArrayList<Int>()
        var cursor = leaf
        while (cursor > 0) {
            chain.add(cursor)
            cursor = nodes[cursor].parent
        }
        chain.reverse()

        val moves = ArrayList<Direction>(64)
        var boxes = startBoxes
        var player = startPlayer
        var pushes = 0

        for (index in chain) {
            val node = nodes[index]
            val direction = node.pushDir ?: continue
            val standCell = board.index(
                board.row(node.pushCell) - direction.rowDelta,
                board.col(node.pushCell) - direction.colDelta
            )
            walk(board, board.index(player), standCell, boxes, moves)
            moves.add(direction)
            pushes++
            // Pushing leaves the player on the cell the box came from.
            player = board.position(node.pushCell)
            boxes = node.boxes
        }

        return Solution(moves = moves, moveCount = moves.size, pushCount = pushes)
    }

    /** Shortest legal walk from [from] to [to], appending its directions. */
    private fun walk(board: Board, from: Int, to: Int, boxes: Boxes, out: MutableList<Direction>) {
        if (from == to || from < 0 || to < 0) return

        val parent = IntArray(board.cellCount) { -1 }
        val parentDirection = arrayOfNulls<Direction>(board.cellCount)
        val queue = IntArray(board.cellCount)
        var head = 0
        var tail = 0

        parent[from] = from
        queue[tail++] = from

        while (head < tail) {
            val cell = queue[head++]
            if (cell == to) break
            val row = board.row(cell)
            val col = board.col(cell)
            for (direction in Direction.entries) {
                val next = board.index(row + direction.rowDelta, col + direction.colDelta)
                if (next < 0 || parent[next] != -1 || boxes.has(next)) continue
                parent[next] = cell
                parentDirection[next] = direction
                queue[tail++] = next
            }
        }

        if (parent[to] == -1) return // unreachable; the verifier will reject it

        val reversed = ArrayList<Direction>()
        var cursor = to
        while (cursor != from) {
            reversed.add(parentDirection[cursor]!!)
            cursor = parent[cursor]
        }
        reversed.reverse()
        out.addAll(reversed)
    }

    // ---------------------------------------------------------- verification --

    /**
     * Replays [solution] from the level's own starting position and confirms the
     * engine both completes the level and reproduces both counters exactly.
     *
     * This is the validator's check: solutions stored against a level are always
     * relative to its start.
     */
    fun replaysToCompletion(level: LevelDefinition, solution: Solution): Boolean =
        replaysToCompletion(level, GameEngine.initialState(level), solution)

    /**
     * Replays [solution] from an arbitrary state — the form needed when solving
     * MID-LEVEL, where the engine's counters already carry the moves the player
     * has made. Counters are therefore compared as DELTAS from [from]: a
     * continuation's moveCount describes only the steps it adds, and comparing
     * it against the absolute total would reject every perfectly correct
     * mid-level solution.
     *
     * The solver only ever returns a solution this accepts, so its internal
     * step logic can never disagree with gameplay.
     */
    fun replaysToCompletion(level: LevelDefinition, from: GameState, solution: Solution): Boolean {
        var state = from
        val movesBefore = state.moves
        val pushesBefore = state.pushes

        for (direction in solution.moves) {
            val result = GameEngine.move(level, state, direction)
            if (result !is MoveResult.Moved) return false
            state = result.state
        }

        return GameEngine.isComplete(level, state) &&
            state.moves - movesBefore == solution.moveCount &&
            state.pushes - pushesBefore == solution.pushCount
    }
}
