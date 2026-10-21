package com.pushbound.game

import com.pushbound.game.engine.Difficulty
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.Medal
import com.pushbound.game.engine.Medals
import com.pushbound.game.engine.MedalThresholds
import com.pushbound.game.levels.Campaign
import com.pushbound.game.levels.World

/**
 * The shipped campaign.
 *
 * LEVEL DATA IS PLAIN ASCII, so worlds and levels are appended without
 * touching the engine, the solver, the renderer or the controls.
 *
 * PAR VALUES AND DIFFICULTY ARE NOT AUTHORED BY HAND — they are never written
 * into this file. [validatedCampaign] runs the whole set through the level
 * validation pipeline, which solves each level for its shortest solution and
 * stamps parMoves / parPushes / difficulty from the result. The
 * CampaignValidationTest refuses to let the game ship content that is
 * unsolvable or unmeasured, so the numbers here can never drift from reality.
 */
object Levels {

    /**
     * The original milestone prototype (9x8). Designed to exercise the whole
     * loop: walk around, approach the box from the left, push it right,
     * reposition below, push it up, reposition right, push it left onto the
     * goal. The wall stub under the box blocks the "cheap" straight-up push.
     *
     * It stays the FIRST level of the campaign, so progression, the daily
     * puzzle and every existing test that references [first] keep working.
     */
    val first: LevelDefinition = LevelDefinition.fromAscii(
        name = "First Steps",
        rows = listOf(
            "#########",
            "#       #",
            "# ##    #",
            "#  G    #",
            "#  B    #",
            "#  #    #",
            "#   P   #",
            "#########"
        ),
        id = "w1-1",
        world = 1,
        number = 1
    )

    // ------------------------------------------------------------- World 1 --
    /** World 1 — PUSH. Learn the basic rules. */
    private val world1 = World(
        index = 1,
        name = "World 1",
        theme = "PUSH",
        blurb = "Learn the basic rules.",
        levels = listOf(
            first,
            /**
             * MECHANIC — a walled-off goal. The goal sits *inside* the wall row,
             * so the box can never be pushed straight down into it: the wall at
             * (4,3) is directly under the box's only descent line.
             *
             * CRITICAL DECISION — which way round the wall stub. Left is a dead
             * end (the stub seals it); right is the only route that leaves a
             * standing cell for the final push.
             *
             * REQUIRED ORDER — box right along row 3, then down the right-hand
             * channel into the goal.
             *
             * MAJOR DEADLOCK — none, deliberately. World 1 teaches the rule that
             * a wrong direction wastes moves; the fatal corner arrives in World 4.
             *
             * INTENDED SOLUTION — 7 moves / 3 pushes.
             *
             * WHY NOT TRIVIAL — no straight shot (`str = 0/1`), and the goal has
             * exactly ONE valid approach (from above), so the final push
             * direction is forced by geometry rather than chosen.
             */
            LevelDefinition.fromAscii(
                name = "Around The Back",
                rows = listOf(
                    "########",
                    "#      #",
                    "#  P   #",
                    "#  B   #",
                    "# ###G #",
                    "#      #",
                    "########"
                ),
                id = "w1-2", world = 1, number = 2
            ),
            /**
             * MECHANIC — forced order with two crates. The wall row across the
             * middle has exactly one gap, at (4,3), which is also the only cell
             * that can feed the lower goal.
             *
             * CRITICAL DECISION — which box moves first. The left box at (3,2)
             * has ZERO legal pushes at the start: up and down are sealed by the
             * wall row, and left and right are blocked by the border and by its
             * neighbour. It is immobile until the right box clears away.
             *
             * REQUIRED ORDER — right box first, left box second. The order is
             * forced, not chosen.
             *
             * MAJOR DEADLOCK — none fatal, but parking a box on the gap (4,3)
             * leaves no route to the lower goal, so the gap must stay clear.
             *
             * INTENDED SOLUTION — 12 moves / 5 pushes.
             *
             * WHY NOT TRIVIAL — an immobile crate (`IMM`) means the opening move
             * is dictated by the board, and the wall row splits the puzzle into
             * two halves that must be solved through a single cell.
             */
            LevelDefinition.fromAscii(
                name = "Two Of Them",
                rows = listOf(
                    "#######",
                    "#  G  #",
                    "#     #",
                    "# BB  #",
                    "### ###",
                    "#  G  #",
                    "#  P  #",
                    "#######"
                ),
                id = "w1-3", world = 1, number = 3
            )
        )
    )

    // ------------------------------------------------------------- World 2 --
    /** World 2 — POSITION. Learn positioning and planning. */
    private val world2 = World(
        index = 2,
        name = "World 2",
        theme = "POSITION",
        blurb = "Learn positioning and planning.",
        levels = listOf(
            /**
             * MECHANIC — the goal is not on the box's line. The crate at (3,3)
             * sits directly above the wall stub that starts at (4,3), so it can
             * never be pushed straight down; it has to be walked around the stub
             * and lined up again from the side.
             *
             * CRITICAL DECISION — which side of the stub to route around. The
             * right-hand channel is wider but needs a step back before the final
             * approach; the left is shorter and forces a turn against the border.
             *
             * REQUIRED ORDER — clear the corridor beside the stub before the
             * final approach, or the pusher has nowhere to stand.
             *
             * MAJOR DEADLOCK — pushing the crate into column 1 seals it: the
             * border is on its left, so it can never be turned again.
             *
             * INTENDED SOLUTION — 11 moves / 5 pushes.
             *
             * WHY NOT TRIVIAL — `str = 0/1`: there is no unobstructed straight
             * run from the crate to its goal, so the crate must change direction
             * at least once.
             */
            LevelDefinition.fromAscii(
                name = "Line Up",
                rows = listOf(
                    "#########",
                    "#       #",
                    "#  P    #",
                    "#  B    #",
                    "#  ######",
                    "#     # #",
                    "#  G    #",
                    "#       #",
                    "#########"
                ),
                id = "w2-1", world = 2, number = 1
            ),
            /**
             * MECHANIC — a bottleneck crossed twice. The wall row at (4,0)-(4,8)
             * has a single gap at (4,6), and a second stub at (6,3)-(6,4) blocks
             * the direct descent to the goal.
             *
             * CRITICAL DECISION — descend through the one gap and then find the
             * only standing cell that can push the crate left into the goal.
             * Column 1 and column 8 are traps: the border removes the cell the
             * pusher would need to stand on, so a crate there can never turn.
             *
             * REQUIRED ORDER — cross the wall row first; the final push is last
             * and can only be made from (7,4).
             *
             * MAJOR DEADLOCK — the border-adjacent channels, which are legal but
             * unrecoverable.
             *
             * INTENDED SOLUTION — 12 moves / 6 pushes.
             *
             * WHY NOT TRIVIAL — eight internal walls leave a single usable
             * crossing, and `P/b = 6.0` means the crate spends most of the puzzle
             * being walked around rather than delivered.
             */
            LevelDefinition.fromAscii(
                name = "The Long Way",
                rows = listOf(
                    "#########",
                    "#       #",
                    "#  P    #",
                    "#    B  #",
                    "###### ##",
                    "#       #",
                    "#  ##   #",
                    "#    G  #",
                    "#########"
                ),
                id = "w2-2", world = 2, number = 2
            ),
            /**
             * MECHANIC — a single-approach goal. The goal at (6,7) is boxed in by
             * the wall pillar at (6,5) and by the border on its right and bottom,
             * leaving exactly one cell it can be pushed from.
             *
             * CRITICAL DECISION — bring the crate down the right-hand channel.
             * Any other route ends with the crate beside the goal and no cell to
             * stand in for the last push.
             *
             * REQUIRED ORDER — reach the corridor right of the pillar before
             * committing the crate to the lower half.
             *
             * MAJOR DEADLOCK — the pocket at (6,7) itself: a crate parked at
             * (6,6) or (5,7) cannot be pushed into the goal from the only cell
             * that remains.
             *
             * INTENDED SOLUTION — 9 moves / 6 pushes.
             *
             * WHY NOT TRIVIAL — `ONE-APPROACH`: the geometry removes the choice
             * of push direction, so the whole puzzle is really one long setup for
             * a single forced push.
             */
            LevelDefinition.fromAscii(
                name = "Corner Approach",
                rows = listOf(
                    "#########",
                    "#       #",
                    "#  P    #",
                    "#   B   #",
                    "#  ###  #",
                    "#  # #  #",
                    "#  # # G#",
                    "#       #",
                    "#########"
                ),
                id = "w2-3", world = 2, number = 3
            )
        )
    )

    // ------------------------------------------------------------- World 3 --
    /** World 3 — ORDER. Learn sequencing. */
    private val world3 = World(
        index = 3,
        name = "World 3",
        theme = "ORDER",
        blurb = "Learn sequencing.",
        levels = listOf(
            /**
             * MECHANIC — two boxes, two goals, and a wall row between them with
             * no gap under either box. Both crates must be moved sideways before
             * either can descend, so the puzzle is a sequencing problem, not a
             * delivery problem.
             *
             * CRITICAL DECISION — which box is pushed first. Both opening orders
             * are legal, but only one keeps the shared corridor clear.
             *
             * REQUIRED ORDER — one of the two boxes must be parked out of the
             * corridor before the other crosses; the wrong order leaves the
             * second crate with no standing cell.
             *
             * MAJOR DEADLOCK — an L-corner at (7,4): the stub and the border give
             * it two blocked sides, and it is one push away from either box.
             * Two of the legal opening pushes are provably fatal (`f1 = 2`).
             *
             * INTENDED SOLUTION — 18 moves / 8 pushes.
             *
             * WHY NOT TRIVIAL — the wrong first push loses the puzzle outright
             * (`f1 = 2`, `f2 = 5`), so the player must reason about the second
             * box before touching the first.
             */
            LevelDefinition.fromAscii(
                name = "Fill The Row",
                rows = listOf(
                    "#########",
                    "#       #",
                    "#  P    #",
                    "# B B   #",
                    "#  ###  #",
                    "#  G G  #",
                    "#       #",
                    "#    #  #",
                    "#########"
                ),
                id = "w3-1", world = 3, number = 1
            ),
            /**
             * MECHANIC — forced order across a bottleneck. The crate at (3,3) has
             * zero legal pushes at the start: its only descent is the gap at
             * (4,4), and its left and right neighbours block the row. It is
             * immovable until its partner clears away.
             *
             * CRITICAL DECISION — push the right-hand crate through the gap first.
             *
             * REQUIRED ORDER — right crate, then left crate. The order is dictated
             * by the board, not chosen.
             *
             * MAJOR DEADLOCK — the gap at (4,4). It is the only crossing of the
             * wall row, so a crate parked there can never reach either goal.
             *
             * INTENDED SOLUTION — 21 moves / 9 pushes.
             *
             * WHY NOT TRIVIAL — an immobile crate (`IMM`), six internal walls and
             * no straight shot for either crate (`str = 0/2`): both goals must be
             * approached around the wall row.
             */
            LevelDefinition.fromAscii(
                name = "Choose The Order",
                rows = listOf(
                    "#########",
                    "#       #",
                    "#  P    #",
                    "#  BB   #",
                    "#### ####",
                    "#       #",
                    "#  G    #",
                    "#   G   #",
                    "#########"
                ),
                id = "w3-2", world = 3, number = 2
            ),
            /**
             * MECHANIC — forced order plus a staggered delivery. The two goals sit
             * in different rows AND different columns, so no crate can be delivered
             * on a straight run, and the crate at (3,3) is immobile until its
             * partner leaves the row.
             *
             * CRITICAL DECISION — which crate takes the near goal (5,3) and which
             * crosses the whole board to (7,6). Committing the wrong one strands
             * the other against the wall row.
             *
             * REQUIRED ORDER — free the immobile crate first, near goal second,
             * far goal last.
             *
             * MAJOR DEADLOCK — the dead-end columns of the wall row: a crate
             * pushed into column 1 or column 8 beside the border can never be
             * turned again.
             *
             * INTENDED SOLUTION — 28 moves / 12 pushes.
             *
             * WHY NOT TRIVIAL — `IMM`, `str = 0/2` and `P/b = 6.0`: the puzzle
             * is mostly repositioning, and one wrong commitment loses it.
             */
            LevelDefinition.fromAscii(
                name = "Staggered",
                rows = listOf(
                    "#########",
                    "#       #",
                    "#  P    #",
                    "#  BB   #",
                    "#### ####",
                    "#  G    #",
                    "#       #",
                    "#     G #",
                    "#########"
                ),
                id = "w3-3", world = 3, number = 3
            )
        )
    )

    // ------------------------------------------------------------- World 4 --
    /** World 4 — DEADLOCK. Learn to recognize dangerous positions. */
    private val world4 = World(
        index = 4,
        name = "World 4",
        theme = "DEADLOCK",
        blurb = "Learn to recognize dangerous positions.",
        levels = listOf(
            /**
             * MECHANIC — an L-corner one push away. The stub at (6,3) and the wall
             * row above give the lower pocket two blocked sides, and the corner
             * cell beside the stub is a non-goal: one push and a crate is gone.
             *
             * CRITICAL DECISION — never push either crate toward (6,2) or the
             * cells sealed by the stub. Both goals sit inside the pocket, so the
             * crates have to be fed in from the correct side.
             *
             * REQUIRED ORDER — right crate first, left crate second. The left
             * crate's route runs through the cell the right crate needs.
             *
             * MAJOR DEADLOCK — the L-corner trap itself. Two legal opening pushes
             * are provably fatal (`f1 = 2`), and five two-push sequences lose the
             * puzzle (`f2 = 5`).
             *
             * INTENDED SOLUTION — 26 moves / 10 pushes.
             *
             * WHY NOT TRIVIAL — the traps are reachable in a single legal push,
             * so the player must look one move ahead of every push, not just at
             * the goal.
             */
            LevelDefinition.fromAscii(
                name = "Mind The Corners",
                rows = listOf(
                    "#########",
                    "#       #",
                    "#  P    #",
                    "# B B   #",
                    "#  #### #",
                    "#  G G  #",
                    "#  #    #",
                    "#       #",
                    "#########"
                ),
                id = "w4-1", world = 4, number = 1
            ),
            /**
             * MECHANIC — forced order with a split lower half. The crate at (3,3)
             * is immobile until its partner clears the row, and the two stubs at
             * (5,4) and (5,6) divide the lower area into pockets that only connect
             * around the ends.
             *
             * CRITICAL DECISION — which crate descends first. The first crate's
             * final parking spot decides whether the second one still has a route
             * to the lower-left goal.
             *
             * REQUIRED ORDER — left crate first (it is the only one that can move),
             * lower goal before upper.
             *
             * MAJOR DEADLOCK — L-corners formed by each stub against the wall row.
             * Two two-push sequences are fatal (`f2 = 2`).
             *
             * INTENDED SOLUTION — 25 moves / 11 pushes.
             *
             * WHY NOT TRIVIAL — seven internal walls, an immobile crate and
             * `P/b = 5.5`: the solution spends more moves repositioning than
             * delivering.
             */
            LevelDefinition.fromAscii(
                name = "Blocked Path",
                rows = listOf(
                    "#########",
                    "#       #",
                    "#  P    #",
                    "# BB    #",
                    "#  ######",
                    "#   # # #",
                    "#  G    #",
                    "#   G   #",
                    "#########"
                ),
                id = "w4-2", world = 4, number = 2
            ),
            /**
             * MECHANIC — a single-approach goal under deadlock pressure. The lower
             * goal at (7,6) can be entered from exactly one direction, and the two
             * stubs at (2,4) and (3,5) plus the wall row force the crates on a wide
             * detour before they can line up.
             *
             * CRITICAL DECISION — bring the crates down the left-hand channel. The
             * right-hand channel dead-ends against the border, where a crate can
             * never be turned.
             *
             * REQUIRED ORDER — clear the upper stub before committing a crate to
             * the lower corridor, or the corridor is sealed behind it.
             *
             * MAJOR DEADLOCK — eight two-push sequences are fatal (`f2 = 8`), the
             * densest trap set in the campaign before World 5.
             *
             * INTENDED SOLUTION — 26 moves / 13 pushes.
             *
             * WHY NOT TRIVIAL — `P/b = 6.5` with `f2 = 8`: almost every early
             * push is survivable, but a specific few are not, and finding them
             * requires reasoning about the far end of the board.
             */
            LevelDefinition.fromAscii(
                name = "Wide Detour",
                rows = listOf(
                    "#########",
                    "#  P    #",
                    "#   #   #",
                    "# BB #  #",
                    "#       #",
                    "#  #### #",
                    "#  G    #",
                    "#     G #",
                    "#########"
                ),
                id = "w4-3", world = 4, number = 3
            )
        )
    )

    // ------------------------------------------------------------- World 5 --
    /** World 5 — COMBINATION. Combine previously learned concepts. */
    private val world5 = World(
        index = 5,
        name = "World 5",
        theme = "COMBINATION",
        blurb = "Combine previously learned concepts.",
        levels = listOf(
            /**
             * MECHANIC — three crates through two usable channels. The wall row at
             * (4,3)-(4,5) leaves only columns 2 and 6 able to turn (columns 1 and 7
             * are border traps), and the stubs at (6,2) and (6,6) seal the outer
             * goal columns into L-corners.
             *
             * CRITICAL DECISION — which crate descends first. Four of the opening
             * pushes are immediately fatal (`f1 = 4`), and the middle goal at (5,4)
             * can only be fed from above.
             *
             * REQUIRED ORDER — outer crates first, middle crate last, because the
             * middle crate's only route runs through a cell the outer crates need.
             *
             * MAJOR DEADLOCK — the L-corners at (6,2) and (6,6). Eighteen two-push
             * sequences lose the puzzle (`f2 = 18`), the highest in the campaign.
             *
             * INTENDED SOLUTION — 25 moves / 10 pushes.
             *
             * WHY NOT TRIVIAL — 10 legal opening pushes, of which 4 are fatal, on
             * a board with a single usable descent per side: the first decision is
             * already the hard one.
             */
            LevelDefinition.fromAscii(
                name = "Everything Together",
                rows = listOf(
                    "#########",
                    "#       #",
                    "#  P    #",
                    "# B B B #",
                    "#  ###  #",
                    "# G G G #",
                    "# #   # #",
                    "#########"
                ),
                id = "w5-1", world = 5, number = 1
            ),
            /**
             * MECHANIC — forced order plus corner traps under two of the three
             * goals. The crate at (3,3) has no legal push at all until its partner
             * is moved, and the stubs at (7,3) and (7,5) make the outer goal
             * columns single-approach pockets.
             *
             * CRITICAL DECISION — the opening push is forced (the immobile crate
             * cannot move), so the real decision is where to park the first crate
             * so the second one still has a corridor.
             *
             * REQUIRED ORDER — crate at (3,2) first, then the freed crate, then the
             * right-hand crate last.
             *
             * MAJOR DEADLOCK — the two stubs against the bottom border. Eight
             * two-push sequences are fatal (`f2 = 8`).
             *
             * INTENDED SOLUTION — 32 moves / 13 pushes.
             *
             * WHY NOT TRIVIAL — an immobile crate locks the opening, `str = 1/3`
             * so one crate must change direction, and the shortest solution needs
             * a search budget of over five thousand nodes.
             */
            LevelDefinition.fromAscii(
                name = "Two Corners",
                rows = listOf(
                    "#########",
                    "#       #",
                    "#  P    #",
                    "# BB B  #",
                    "#  ###  #",
                    "#       #",
                    "# G G G #",
                    "#  # #  #",
                    "#########"
                ),
                id = "w5-2", world = 5, number = 2
            ),
            /**
             * MECHANIC — the finale. Three crates and three goals staggered across
             * the largest board in the campaign, with a wall stub at (5,5)-(5,6)
             * splitting the lower half so the last goal sits in a one-way pocket.
             *
             * CRITICAL DECISION — the two crates at (2,2) and (2,3) are locked
             * together: neither can move until the other is pushed clear, and the
             * single legal first move (up) is the only one the board allows.
             *
             * REQUIRED ORDER — lower crate first, then the pair, near goals before
             * the far goal.
             *
             * MAJOR DEADLOCK — the stub against the border turns (6,4) into a
             * pocket that can only be entered from above; park a crate beside it
             * and the level is lost. Sixteen two-push sequences are fatal
             * (`f2 = 16`).
             *
             * INTENDED SOLUTION — 25 moves / 12 pushes.
             *
             * WHY NOT TRIVIAL — the shortest solution needs a search budget above
             * eight thousand nodes, so a 64-node hint budget cannot solve it, and
             * the hint system is expected to say so rather than guess.
             */
            LevelDefinition.fromAscii(
                name = "The Set",
                rows = listOf(
                    "##########",
                    "#        #",
                    "# BB     #",
                    "#   GG   #",
                    "#        #",
                    "#  B ##  #",
                    "#   G    #",
                    "#    P   #",
                    "##########"
                ),
                id = "w5-3", world = 5, number = 3
            )
        )
    )

    /**
     * The campaign as authored: structure and grids only, par and difficulty
     * still unset.
     */
    val campaign: Campaign = Campaign(listOf(world1, world2, world3, world4, world5))

    /**
     * Per-level approval cache.
     *
     * MEASURED COST — approving the whole 15-level campaign takes **4.9 seconds**
     * on a mid-range ARM device (the solver does a full breadth-first search per
     * level). Doing that inside the `validatedCampaign` lazy and touching it from
     * the first frame froze the UI for that entire period, which is a startup
     * freeze, not "a fraction of a second".
     *
     * Approving ONE level at a time, on demand and cached, keeps that cost off
     * the startup path: the app only ever pays for the level it is actually
     * about to play or has just finished.
     *
     * Concurrent because a level may be approved from the UI thread and, later,
     * from a warm-up or a validation run.
     */
    private val approvedById =
        java.util.concurrent.ConcurrentHashMap<String, LevelDefinition>()

    /**
     * The approved form of a single [level]: parMoves, parPushes, difficulty and
     * medal thresholds stamped by the validation pipeline.
     *
     * Answers in microseconds after the first call for that id. This is the
     * only entry point the game needs — a level is approved exactly when it is
     * about to be played, never for the whole campaign at once.
     *
     * ONLY CAMPAIGN LEVELS ARE CACHED BY ID. A level outside the authored
     * campaign (a test fixture, and later a generated or challenge level) may
     * legitimately reuse an id for a DIFFERENT board, and returning a stale
     * approval for it would silently play the wrong puzzle — so those are
     * approved directly and never stored.
     */
    fun approved(level: LevelDefinition): LevelDefinition {
        val approve = {
            com.pushbound.game.engine.solver.LevelValidator.approve(
                level,
                thresholds = level.medalThresholds ?: MedalThresholds.DEFAULT
            )
        }
        if (campaign.level(level.id) == null) return approve()
        return approvedById.getOrPut(level.id) { approve() }
    }

    /**
     * The campaign with par, difficulty and medal thresholds stamped on by the
     * validation pipeline.
     *
     * FOR VALIDATION AND TESTS — it approves every level up front, which costs
     * seconds. The running game never touches it; it plays through [approved],
     * one level at a time.
     */
    val validatedCampaign: Campaign by lazy {
        val allApproved = campaign.allLevels.map { approved(it) }
        val byWorld = allApproved.groupBy { it.world }
        Campaign(
            byWorld.keys.sorted().map { index ->
                val source = campaign.world(index)!!
                source.copy(levels = byWorld.getValue(index).sortedBy { it.number })
            }
        )
    }

    /** Every validated level, in play order. Approves the whole campaign. */
    val all: List<LevelDefinition> get() = validatedCampaign.allLevels

    /** Approved lookup by id — approves only that one level. */
    fun byId(id: String): LevelDefinition? = campaign.level(id)?.let { approved(it) }

    /**
     * The level after the given id in play order.
     *
     * Order comes from the authored campaign (identical ids and sequence), and
     * only the ONE returned level is approved, so asking "what is next" never
     * drags the whole campaign through the solver.
     */
    fun nextAfter(id: String): LevelDefinition? =
        campaign.nextAfter(id)?.let { approved(it) }

    /**
     * The best medal achievable on a level with the given result, exposed for
     * previews. The real award lives in the progress store.
     */
    fun medalFor(level: LevelDefinition, moves: Int, pushes: Int): Medal =
        Medals.evaluate(
            moves = moves,
            pushes = pushes,
            parMoves = level.parMoves,
            parPushes = level.parPushes,
            thresholds = level.medalThresholds ?: MedalThresholds.DEFAULT
        )

    /** Difficulty band of a level, from the validated campaign. */
    fun difficultyOf(id: String): Difficulty? = byId(id)?.difficulty
}
