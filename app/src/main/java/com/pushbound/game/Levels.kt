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
            LevelDefinition.fromAscii(
                name = "Around The Back",
                rows = listOf(
                    "######",
                    "#    #",
                    "# B  #",
                    "# GP #",
                    "#    #",
                    "######"
                ),
                id = "w1-2", world = 1, number = 2
            ),
            LevelDefinition.fromAscii(
                name = "Two Of Them",
                rows = listOf(
                    "#######",
                    "#     #",
                    "# PBG #",
                    "#     #",
                    "#  G  #",
                    "# B   #",
                    "#     #",
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
            LevelDefinition.fromAscii(
                name = "Line Up",
                rows = listOf(
                    "#######",
                    "#     #",
                    "#  B  #",
                    "# G P #",
                    "#     #",
                    "#######"
                ),
                id = "w2-1", world = 2, number = 1
            ),
            LevelDefinition.fromAscii(
                name = "The Long Way",
                rows = listOf(
                    "########",
                    "#      #",
                    "#  B   #",
                    "#   G  #",
                    "# P    #",
                    "#      #",
                    "########"
                ),
                id = "w2-2", world = 2, number = 2
            ),
            LevelDefinition.fromAscii(
                name = "Corner Approach",
                rows = listOf(
                    "########",
                    "#      #",
                    "# B    #",
                    "#  G   #",
                    "#   P  #",
                    "#      #",
                    "########"
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
            LevelDefinition.fromAscii(
                name = "Fill The Row",
                rows = listOf(
                    "#######",
                    "#     #",
                    "# BB  #",
                    "# GG  #",
                    "#  P  #",
                    "#     #",
                    "#######"
                ),
                id = "w3-1", world = 3, number = 1
            ),
            LevelDefinition.fromAscii(
                name = "Choose The Order",
                rows = listOf(
                    "#######",
                    "#     #",
                    "#  BB #",
                    "#  GG #",
                    "#   P #",
                    "#     #",
                    "#######"
                ),
                id = "w3-2", world = 3, number = 2
            ),
            LevelDefinition.fromAscii(
                name = "Staggered",
                rows = listOf(
                    "########",
                    "#      #",
                    "#  B   #",
                    "#  G   #",
                    "#     B#",
                    "#     G#",
                    "#   P  #",
                    "########"
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
            LevelDefinition.fromAscii(
                name = "Mind The Corners",
                rows = listOf(
                    "########",
                    "#      #",
                    "# B    #",
                    "#  ##  #",
                    "#  G   #",
                    "#   P  #",
                    "########"
                ),
                id = "w4-1", world = 4, number = 1
            ),
            LevelDefinition.fromAscii(
                name = "Blocked Path",
                rows = listOf(
                    "########",
                    "#      #",
                    "#  B   #",
                    "#      #",
                    "#  #   #",
                    "#  # G #",
                    "#   P  #",
                    "########"
                ),
                id = "w4-2", world = 4, number = 2
            ),
            LevelDefinition.fromAscii(
                name = "Wide Detour",
                rows = listOf(
                    "#########",
                    "#       #",
                    "#  B    #",
                    "#    G  #",
                    "#  ##   #",
                    "# P     #",
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
            LevelDefinition.fromAscii(
                name = "Everything Together",
                rows = listOf(
                    "#########",
                    "#       #",
                    "#  BB   #",
                    "#  GG   #",
                    "#       #",
                    "#   P   #",
                    "#########"
                ),
                id = "w5-1", world = 5, number = 1
            ),
            LevelDefinition.fromAscii(
                name = "Two Corners",
                rows = listOf(
                    "#########",
                    "#       #",
                    "#  B    #",
                    "#  G    #",
                    "#     B #",
                    "#     G #",
                    "#    P  #",
                    "#########"
                ),
                id = "w5-2", world = 5, number = 2
            ),
            LevelDefinition.fromAscii(
                name = "The Set",
                rows = listOf(
                    "##########",
                    "#        #",
                    "# BB     #",
                    "#   GG   #",
                    "#        #",
                    "#  B     #",
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
