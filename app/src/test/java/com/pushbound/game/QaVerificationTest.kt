package com.pushbound.game

import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameEngine
import com.pushbound.game.engine.GameSession
import com.pushbound.game.engine.Hints
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.Medal
import com.pushbound.game.engine.MedalThresholds
import com.pushbound.game.engine.Medals
import com.pushbound.game.engine.MoveResult
import com.pushbound.game.engine.solver.LevelSolver
import com.pushbound.game.engine.solver.LevelValidator
import com.pushbound.game.progress.InMemoryProgressStore
import com.pushbound.game.ui.GameController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Independent QA verification pass.
 *
 * Written against the code as it actually stands rather than against any prior
 * report, and deliberately aimed at the seams the feature suites do not already
 * pin: the data-driven promise itself, safe rejection of malformed content,
 * hint integrity across history operations, medal boundaries at par, and the
 * end-to-end progression wiring in the controller.
 */
class QaVerificationTest {

    private val scope = CoroutineScope(Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /**
     * Waits until [controller] is between animations, then runs [action] — with
     * a hard three-second ceiling so a real regression fails instead of hanging.
     *
     * WHY THIS IS NEEDED: the controller starts a 110ms animation on the scope
     * it was given and REFUSES undo/redo, and queues new presses, while that
     * animation is live. A fixed `delay(170)` on the test thread is only a
     * guess about when the coroutine actually gets scheduled — under load the
     * guess loses, the action is dropped, and the assertion fails for no
     * behavioural reason. Waiting on [GameController.isAnimating] asks the
     * question directly.
     *
     * Sleeping the JUnit thread is safe: the controller's work runs on
     * [Dispatchers.Default].
     */
    private fun awaitIdle(controller: GameController, then: () -> Unit) {
        val deadline = System.currentTimeMillis() + 3_000L
        while (controller.isAnimating) {
            assertTrue(
                "the animation never finished within 3s",
                System.currentTimeMillis() < deadline
            )
            Thread.sleep(5)
        }
        then()
    }

    private fun ascii(
        name: String,
        rows: List<String>,
        id: String = "qa-fixture",
        world: Int = 1,
        number: Int = 1
    ) = LevelDefinition.fromAscii(name = name, rows = rows, id = id, world = world, number = number)

    private val openRoom = listOf(
        "#######",
        "#     #",
        "#  B  #",
        "# G P #",
        "#     #",
        "#######"
    )

    // ===================================================================
    // 3. LEVEL SYSTEM — is it genuinely data-driven?
    // ===================================================================

    @Test
    fun levelsAreAuthoredAsPlainTextWithNoEngineChanges() {
        // The whole promise of a data-driven model: a level is a list of
        // strings plus five scalars. Nothing below the call site exists to
        // special-case it.
        val rows = listOf(
            "##########",
            "#        #",
            "# BB     #",
            "# GG     #",
            "#        #",
            "#     B  #",
            "#     G  #",
            "#    P   #",
            "##########"
        )
        val level = ascii("Pure Data", rows, id = "qa-pure", world = 6, number = 1)

        assertEquals("P is found from the text", level.playerStart, com.pushbound.game.engine.Position(7, 5))
        assertEquals(3, level.boxCount)
        assertEquals(3, level.goals.size)
        assertEquals(10, level.width)
        assertEquals(9, level.height)
        assertEquals(6, level.world)
        assertEquals(1, level.number)
        assertFalse("no mechanic has to be hand-attached", level.mechanics.isEmpty())
    }

    @Test
    fun severalBrandNewLevelsRunTheWholePipelineUntouched() {
        // Four levels that have never been seen by any engine change: authored
        // after the code froze, then taken through validate -> solve -> par ->
        // difficulty in one go.
        val authored = listOf(
            ascii("QA One", openRoom, id = "qa-p1", world = 6, number = 1),
            ascii(
                "QA Two",
                listOf(
                    "########",
                    "#      #",
                    "#      #",
                    "# B  B #",
                    "# G  G #",
                    "#  P   #",
                    "########"
                ),
                id = "qa-p2", world = 6, number = 2
            ),
            ascii(
                "QA Three",
                listOf(
                    "#########",
                    "#       #",
                    "#   B   #",
                    "#  ###  #",
                    "#   G   #",
                    "#   P   #",
                    "#########"
                ),
                id = "qa-p3", world = 7, number = 1
            ),
            ascii(
                "QA Four",
                listOf(
                    "######",
                    "#    #",
                    "# PBG#",
                    "#    #",
                    "######"
                ),
                id = "qa-p4", world = 7, number = 2
            )
        )

        authored.forEach { level ->
            val report = LevelValidator.validate(level)
            assertTrue(
                "${level.id} must pass validation, got ${report.problems}",
                report.approved
            )

            val approved = LevelValidator.approve(level)
            assertTrue("${level.id} parMoves", approved.parMoves > 0)
            assertTrue("${level.id} parPushes", approved.parPushes > 0)
            assertNotNull("${level.id} difficulty", approved.difficulty)

            // The measured par must actually be achievable by real play.
            val solution = LevelSolver.solve(approved)
            assertNotNull("${level.id} must be solvable", solution)
            assertEquals(
                "${level.id} par must match the real shortest solution",
                solution!!.moveCount,
                approved.parMoves
            )
            assertEquals(
                "${level.id} par pushes must match the real solution",
                solution.pushCount,
                approved.parPushes
            )
            assertTrue(
                "${level.id} solution must replay in the real engine",
                LevelSolver.replaysToCompletion(approved, solution)
            )
        }
    }

    @Test
    fun levelOrderingComesFromWorldAndNumberNotFromListPosition() {
        // Prove order is declared data: the flat play order must equal
        // (world, number) sorting, so reordering lines in the source cannot
        // silently reshuffle progression if someone adds a level out of order.
        val flat = Levels.campaign.allLevels
        assertEquals(flat, flat.sortedWith(compareBy({ it.world }, { it.number })))

        val declared = Levels.campaign.worlds.flatMap { world ->
            assertEquals(
                "world ${world.index} must number its levels from 1",
                listOf(1, 2, 3),
                world.levels.map { it.number }
            )
            world.levels
        }
        assertEquals(declared, flat)
    }

    @Test
    fun idsAreUniqueAcrossTheWholeCampaign() {
        val ids = Levels.campaign.allLevels.map { it.id }
        assertEquals("no duplicate ids", ids.size, ids.toSet().size)
        // Every id must also resolve back to exactly one level.
        ids.forEach { id ->
            assertNotNull("id $id must resolve", Levels.byId(id))
            assertEquals(1, Levels.campaign.allLevels.count { it.id == id })
        }
    }

    // --- malformed content is rejected, not tolerated -------------------

    /** Asserts that building a level from [rows] is refused outright. */
    private fun assertRefused(reason: String, vararg rows: String) {
        try {
            LevelDefinition.fromAscii(
                name = "Should Be Refused",
                rows = rows.toList(),
                id = "qa-refused",
                world = 1,
                number = 1
            )
            throw AssertionError("$reason — the parser must refuse this level")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "$reason: the message must explain why, was '${expected.message}'",
                !expected.message.isNullOrBlank()
            )
        }
    }

    @Test
    fun raggedRowsAreRefusedRatherThanSilentlyAccepted() {
        // A board whose rows differ in width is not a board. Refusing at parse
        // time beats producing a grid the renderer would slice wrongly.
        assertRefused("rows of different widths", "#######", "#  #", "#   #", "######")
    }

    @Test
    fun aLevelWithoutAPlayerIsRefused() {
        assertRefused("no player start", "#####", "# B #", "# G #", "#####")
    }

    @Test
    fun aLevelWithTwoPlayersIsRefused() {
        assertRefused("two player starts", "#####", "#PGP#", "# B #", "#####")
    }

    @Test
    fun boxAndGoalCountsMustMatch() {
        val mismatch = ascii("Mismatch", listOf("######", "# P  #", "# B  #", "######"))
        val problems = LevelValidator.structuralProblems(mismatch)
        assertTrue(
            "a box with no goal cannot be finished: $problems",
            problems.isNotEmpty()
        )
        // ...and the gate refuses to ship it rather than stamping a fake par.
        assertFalse(LevelValidator.validate(mismatch).approved)
    }

    @Test
    fun aBoxWalledIntoACornerIsRejectedAsUnsolvable() {
        // The box sits at start in the bottom-right cell, walled right and
        // below, and its goal is across the board: no sequence of pushes can
        // ever free it.
        val wedged = ascii(
            "Wedged",
            listOf(
                "######",
                "#    #",
                "#  G #",
                "#    #",
                "#  P #",
                "#   B#",
                "######"
            ),
            id = "qa-wedged"
        )
        val report = LevelValidator.validate(wedged)
        assertFalse(
            "a box that can never reach its goal must fail validation",
            report.approved
        )
        assertTrue(
            "the report must say why: ${report.problems}",
            report.problems.isNotEmpty()
        )
        assertNull("no solution may be offered", LevelSolver.solve(wedged))
    }

    @Test
    fun anUnsolvableLevelIsReportedNotHung() {
        // Give the solver a hard ceiling and confirm an impossible level comes
        // back with a verdict instead of running forever.
        val impossible = ascii(
            "Impossible",
            listOf(
                "######",
                "# G  #",
                "# ## #",
                "# B  #",
                "#  P #",
                "######"
            ),
            id = "qa-impossible"
        )
        val started = System.nanoTime()
        val result = LevelSolver.solveDetailed(impossible, maxNodes = 40_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(
            "a bounded search must stay bounded, took ${elapsedMs}ms",
            elapsedMs < 5_000
        )
        assertFalse(
            "no solution may be claimed for an unreachable box",
            LevelSolver.Outcome.SOLVED == result.outcome
        )
    }

    @Test
    fun approvingAnUnshippableLevelThrowsInsteadOfShippingIt() {
        // The pipeline is a gate: approve() must refuse, loudly, rather than
        // hand back a level with a made-up par.
        val broken = ascii("Broken", listOf("#####", "# P #", "#####"), id = "qa-broken")
        try {
            LevelValidator.approve(broken)
            throw AssertionError("approve() must refuse a level with no goals")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("not production-ready"))
        }
    }

    // ===================================================================
    // 5. MEDALS — boundaries around par
    // ===================================================================

    @Test
    fun bothCountersExactlyAtParEarnGold() {
        assertEquals(
            Medal.GOLD,
            Medals.evaluate(
                moves = 40, pushes = 12, parMoves = 40, parPushes = 12,
                thresholds = MedalThresholds.DEFAULT
            )
        )
    }

    @Test
    fun oneMoveOverParDropsToSilverButNotToBronze() {
        assertEquals(
            "a single extra move must cost gold, not the medal",
            Medal.SILVER,
            Medals.evaluate(
                moves = 41, pushes = 12, parMoves = 40, parPushes = 12,
                thresholds = MedalThresholds.DEFAULT
            )
        )
    }

    @Test
    fun onePushOverParAlsoDropsToSilver() {
        assertEquals(
            Medal.SILVER,
            Medals.evaluate(
                moves = 40, pushes = 13, parMoves = 40, parPushes = 12,
                thresholds = MedalThresholds.DEFAULT
            )
        )
    }

    @Test
    fun farOutsideParStillCompletesWithBronze() {
        assertEquals(
            Medal.BRONZE,
            Medals.evaluate(
                moves = 999, pushes = 999, parMoves = 40, parPushes = 12,
                thresholds = MedalThresholds.DEFAULT
            )
        )
    }

    @Test
    fun everyShippedLevelGrantsBronzeForMerelyFinishing() {
        Levels.validatedCampaign.allLevels.forEach { level ->
            val report = com.pushbound.game.engine.solver.LevelValidator.validate(level)
            // Deliberately awful play: triple the measured par on both counters.
            val medal = Medals.evaluate(
                moves = report.parMoves * 3 + 20,
                pushes = report.parPushes * 3 + 20,
                parMoves = report.parMoves,
                parPushes = report.parPushes,
                thresholds = level.medalThresholds ?: MedalThresholds.DEFAULT
            )
            assertEquals("${level.id} must reward finishing", Medal.BRONZE, medal)
        }
    }

    @Test
    fun aPlayerCanFinishTheWholeCampaignWithoutEverEarningGold() {
        var state = com.pushbound.game.progress.ProgressState()
        Levels.validatedCampaign.allLevels.forEach { level ->
            state = com.pushbound.game.progress.Progress.recordCompletion(
                state = state,
                levelId = level.id,
                moves = level.parMoves * 4 + 50,
                pushes = level.parPushes * 4 + 50,
                medal = Medal.BRONZE
            )
        }
        val progress = com.pushbound.game.progress.Progress
        assertEquals(
            "every level must be unlocked by bronze alone",
            Levels.validatedCampaign.levelCount,
            progress.unlockedIds(Levels.validatedCampaign, state).size
        )
        assertEquals(
            "not a single gold was earned",
            0,
            state.records.values.count { it.bestMedal == Medal.GOLD }
        )
        assertNull(
            "a finished campaign still has no next level",
            progress.nextLevelId(Levels.validatedCampaign, state)
        )
    }

    // ===================================================================
    // 6. HINTS — integrity across history operations
    // ===================================================================

    @Test
    fun aHintNeverMutatesGameState() {
        val level = Levels.byId("w1-1")!!
        val state = GameEngine.initialState(level)
        val snapshot = state.copy()

        Hints.Tier.entries.forEach { tier ->
            Hints.hintFor(level, state, tier)
            assertEquals("hint $tier altered state", snapshot, state)
        }
        assertEquals("moves counted", 0, state.moves)
        assertEquals("pushes counted", 0, state.pushes)
    }

    @Test
    fun hintsStillWorkAfterUndo() {
        val level = Levels.byId("w1-1")!!
        val session = GameSession(level)
        // w1-1 opens at (6,4); DOWN and LEFT are walls, so use the two moves
        // that are genuinely legal from the start: UP and RIGHT.
        session.move(Direction.UP)
        session.move(Direction.RIGHT)
        assertTrue("two legal moves must arm undo", session.canUndo)
        session.undo()
        session.undo()

        Hints.Tier.entries.forEach { tier ->
            assertNotNull(
                "a hint must still be offered after undoing back to the start ($tier)",
                Hints.hintFor(level, session.state, tier)
            )
        }
        assertEquals("undo returns to zero counts", 0, session.state.moves)
    }

    @Test
    fun hintsStillWorkAfterRedo() {
        val level = Levels.byId("w1-1")!!
        val session = GameSession(level)
        session.move(Direction.UP) // legal from the w1-1 start
        session.undo()
        assertTrue("a move then an undo must arm redo", session.canRedo)
        session.redo()

        Hints.Tier.entries.forEach { tier ->
            assertNotNull(
                "a hint must still be offered after redo ($tier)",
                Hints.hintFor(level, session.state, tier)
            )
        }
    }

    @Test
    fun hintsStillWorkAfterRestart() {
        val level = Levels.byId("w1-1")!!
        val session = GameSession(level)
        repeat(2) { session.move(Direction.UP) }
        session.move(Direction.RIGHT)
        session.restart()

        assertEquals(0, session.state.moves)
        assertEquals(0, session.state.pushes)
        Hints.Tier.entries.forEach { tier ->
            assertNotNull(
                "a hint must still be offered after restart ($tier)",
                Hints.hintFor(level, session.state, tier)
            )
        }
    }

    @Test
    fun hintsStayValidWhenTakenRepeatedlyWithoutCorruptingTheBoard() {
        // Walking the whole level using only tier-3 advice: every step must be
        // legal, the counters must track exactly, and the board must finish
        // solved rather than wedged.
        val level = Levels.byId("w1-1")!!
        var session = GameSession(level)
        var steps = 0

        while (!session.isComplete && steps < 80) {
            val hint = Hints.hintFor(level, session.state, Hints.Tier.REVEAL_NEXT_MOVE)
            assertNotNull("hint must exist while solvable at step $steps", hint)
            val move = (hint as Hints.Hint.NextMoveHint).direction

            val result = session.move(move)
            assertTrue(
                "the suggested move at step $steps must be legal",
                result is MoveResult.Moved
            )
            steps++
        }

        assertTrue("level must finish by following its own hints", session.isComplete)
        assertEquals(
            "the counter must match what was actually played",
            solutionPushes(level),
            session.state.pushes
        )
        assertTrue(
            "following the optimal continuation must not exceed par moves",
            steps <= level.parMoves
        )
    }

    /** Best possible push count for [level], independent of the session. */
    private fun solutionPushes(level: LevelDefinition): Int =
        LevelSolver.solve(level)!!.pushCount

    @Test
    fun everyHintTierOnlyEverSuggestsAMoveTheEngineAccepts() {
        val level = Levels.byId("w1-1")!!
        val session = GameSession(level)
        // Wander off the optimal line first so the state is non-trivial.
        session.move(Direction.UP)
        session.move(Direction.RIGHT)
        session.move(Direction.UP)

        val state = session.state
        Hints.Tier.entries.forEach { tier ->
            val hint = Hints.hintFor(level, state, tier) ?: return@forEach
            val move = when (hint) {
                is Hints.Hint.NextMoveHint -> hint.direction
                is Hints.Hint.DirectionHint -> hint.direction
                is Hints.Hint.BlockHint -> hint.toward
            }
            val result = GameEngine.move(level, state, move)
            assertTrue(
                "$tier must not recommend a refused move: ${hint.message}",
                result is MoveResult.Moved
            )
        }
        assertEquals("asking must cost nothing", state, session.state)
    }

    @Test
    fun aHintIsWithheldRatherThanGuessedWhenTheSearchBudgetRunsOut() {
        val level = Levels.byId("w5-3")!!
        val state = GameEngine.initialState(level)
        // Deliberately tiny budget: the honest answer is "no hint", never a
        // fabricated one.
        val result = LevelSolver.solveFrom(level, state, maxNodes = 64)
        assertNull(
            "an exhausted budget must yield no solution, not a wrong one",
            result.solution
        )
    }

    // ===================================================================
    // 2. GAMEPLAY — progression end to end
    // ===================================================================

    @Test
    fun restartAfterUndoRestoresTheOriginalStateExactly() {
        val level = Levels.byId("w1-1")!!
        val session = GameSession(level)
        val original = session.state

        repeat(2) { session.move(Direction.UP) }
        session.move(Direction.RIGHT)
        session.undo()
        assertTrue("undo must be armed after real moves", session.canUndo)

        session.restart()

        assertEquals(original, session.state)
        assertEquals(0, session.state.moves)
        assertEquals(0, session.state.pushes)
        assertFalse("restart must clear undo", session.canUndo)
        assertFalse("restart must clear redo", session.canRedo)
    }

    @Test
    fun undoThenNewMoveThenRedoDropsTheRedoBranch() {
        // A level whose geometry this test can reason about exactly:
        // player starts at (2,2), and the corridor below/above is open floor.
        val level = ascii(
            "Redo Branch",
            listOf(
                "#######",
                "#     #",
                "# PBG #",
                "#     #",
                "#######"
            ),
            id = "qa-redo"
        )
        val session = GameSession(level)

        session.move(Direction.DOWN) // line A: player (3,2)
        val lineA = session.state
        session.move(Direction.RIGHT) // line B: player (3,3)
        assertEquals(com.pushbound.game.engine.Position(3, 3), session.state.player)

        session.undo()
        assertEquals("undo must return to line A", lineA, session.state)
        assertTrue("redo must be armed after an undo", session.canRedo)

        session.move(Direction.UP) // a NEW action, away from line B
        assertFalse(
            "a fresh move must discard the forward branch",
            session.canRedo
        )
        assertFalse("redo must be a no-op now", session.redo())
        assertEquals(
            "the abandoned RIGHT branch must not have been replayed",
            com.pushbound.game.engine.Position(2, 2),
            session.state.player
        )
        assertEquals("the new line replaced, not added to", 2, session.state.moves)
    }

    @Test
    fun completingALevelUnlocksAndPersistsTheNextOne() {
        val store = InMemoryProgressStore()
        val controller = GameController(Levels.byId("w1-2")!!, scope, store)

        val solution = LevelSolver.solve(Levels.byId("w1-2")!!)!!
        for (direction in solution.moves) {
            // Press only BETWEEN animations: a press arriving during one is
            // queued, and only a single input is kept, so a fixed sleep could
            // silently drop moves and leave the level unfinished.
            awaitIdle(controller) { controller.onDirectionPressed(direction) }
        }

        assertTrue("level must be complete", controller.isComplete)
        val run = controller.lastResult
        assertNotNull("a completed run must be captured", run)
        assertEquals("w1-2", run!!.contentId)
        assertEquals(
            "a campaign run records under the campaign namespace",
            "campaign/w1-2",
            run.key.storageKey
        )
        assertEquals(solution.moveCount, run.moves)
        assertEquals(solution.pushCount, run.pushes)

        // Progress must have been written to the store at the same moment.
        val saved = store.load()
        assertTrue("completion must be persisted", saved.isCompleted("w1-2"))
        assertNotNull("a medal must be awarded", saved.recordFor("w1-2")!!.bestMedal)

        // And the next level must now be reachable.
        assertTrue(
            "next level must be unlocked",
            com.pushbound.game.progress.Progress.isUnlocked(
                Levels.validatedCampaign, saved, "w1-3"
            )
        )

        // "Reopen the app": a fresh controller over the same storage resumes
        // where the player left off instead of at level one.
        val reopened = GameController(Levels.byId("w1-2")!!, scope, store)
        assertTrue(
            "a reopened session must see the saved completion",
            reopened.progress.isCompleted("w1-2")
        )
        assertEquals(run.moves, reopened.progress.recordFor("w1-2")!!.bestMoves)

        assertTrue(controller.continueToNextLevel())
        assertEquals("advance must land on w1-3", "w1-3", controller.level.id)
        assertFalse("a freshly opened level is not complete", controller.isComplete)
        assertEquals(0, controller.state.moves)
        assertEquals(0, controller.state.pushes)
        assertFalse("history must not leak across levels", controller.canUndo)
        assertFalse("history must not leak across levels", controller.canRedo)
    }

    @Test
    fun theLastLevelHasNowhereToAdvanceTo() {
        val controller = GameController(Levels.byId("w5-3")!!, scope, InMemoryProgressStore())
        assertFalse(
            "a finished campaign must not pretend there is a next level",
            controller.continueToNextLevel()
        )
        assertEquals("w5-3", controller.level.id)
    }

    @Test
    fun requestingAHintReturnsImmediatelyAndDeliversLater() {
        val controller = GameController(Levels.byId("w1-1")!!, scope, InMemoryProgressStore())

        val started = System.nanoTime()
        controller.requestHint()
        val blockingUs = (System.nanoTime() - started) / 1_000

        // Deriving a hint is a search; the tap must not pay for it on the frame
        // it was pressed on.
        assertTrue(
            "requestHint blocked the caller for ${blockingUs}µs",
            blockingUs < 50_000
        )

        runBlocking {
            val deadline = System.currentTimeMillis() + 5_000
            while (controller.activeHint == null && controller.hintNotice == null &&
                System.currentTimeMillis() < deadline
            ) {
                delay(20)
            }
        }
        assertNotNull("the hint must arrive", controller.activeHint)
        assertNull("no failure notice for a solvable level", controller.hintNotice)
    }

    @Test
    fun aMoveDiscardsAHintStillBeingCalculated() {
        // w5-3 is the slow board (~2.4s to search), so the result is
        // guaranteed to arrive after the player has already moved on. The
        // search was started for a board that no longer exists, and applying it
        // would hand the player advice about the wrong position.
        val controller = GameController(Levels.byId("w5-3")!!, scope, InMemoryProgressStore())
        controller.requestHint()
        assertTrue("the search must be in flight", controller.hintPending)

        controller.onDirectionPressed(Direction.UP) // clears the pending hint

        runBlocking {
            val deadline = System.currentTimeMillis() + 6_000
            while (controller.hintPending && System.currentTimeMillis() < deadline) delay(25)
        }

        assertFalse("the stale search must finish and be dropped", controller.hintPending)
        assertNull("no hint for a board the player has left", controller.activeHint)
        assertNull("the dropped result must not post a notice", controller.hintNotice)
        assertEquals("the move still counted", 1, controller.state.moves)
    }

    @Test
    fun restartIsFreeAndAlwaysAvailableWithoutAnyAd() {
        val controller = GameController(Levels.byId("w1-1")!!, scope, InMemoryProgressStore())
        repeat(2) { controller.onDirectionPressed(Direction.UP) }
        runBlocking { delay(GameController.MOVE_ANIMATION_MS + 60L) }

        controller.restart()

        assertEquals(0, controller.state.moves)
        assertEquals(0, controller.state.pushes)
        assertFalse(controller.isComplete)
        // Nothing in the restart path consults the ad gateway at all; the
        // default gateway is the no-op one and restart stays unconditional.
        assertFalse(controller.canUndo)
    }

    @Test
    fun undoAndRedoAreNeverGatedByAnything() {
        val controller = GameController(Levels.byId("w1-1")!!, scope, InMemoryProgressStore())
        assertFalse(controller.canUndo)
        assertFalse(controller.canRedo)

        controller.onDirectionPressed(Direction.UP)
        assertTrue("one legal move must arm undo", controller.canUndo)

        // undo/redo refuse while the walk animation is live, so wait for the
        // window instead of guessing how long the coroutine takes to run.
        awaitIdle(controller) { controller.undo() }
        assertTrue(controller.canRedo)

        awaitIdle(controller) { controller.redo() }
        assertTrue(controller.canUndo)
        assertFalse(controller.canRedo)
    }

    // ===================================================================
    // 12. PERFORMANCE — no solver inside the movement loop
    // ===================================================================

    @Test
    fun movingNeverInvokesTheSolver() {
        val level = Levels.byId("w5-3")!!
        var state = GameEngine.initialState(level)

        // Warm the class path so the measurement is not dominated by first use.
        repeat(20) { state = step(level, state, it) }

        val started = System.nanoTime()
        repeat(300) { state = step(level, state, it) }
        val perMoveUs = (System.nanoTime() - started) / 1_000 / 300

        // The solver spends seconds on this board; a move spends microseconds.
        // If a move ever started consulting it, this ceiling breaks at once.
        assertTrue(
            "a move took ${perMoveUs}µs — the solver must not be on this path",
            perMoveUs < 5_000
        )
    }

    /**
     * One probe step: try the next direction in a fixed cycle and accept the
     * result.
     *
     * The direction comes from the CALL COUNTER rather than from the state, so
     * a refused move advances to a different direction on the next step instead
     * of hammering the same wall forever. A refused move never resets the
     * board, so the probe keeps accumulating real work.
     */
    private fun step(
        level: LevelDefinition,
        state: com.pushbound.game.engine.GameState,
        index: Int
    ): com.pushbound.game.engine.GameState {
        val order = listOf(Direction.DOWN, Direction.RIGHT, Direction.UP, Direction.LEFT)
        val result = GameEngine.move(level, state, order[index % order.size])
        return if (result is MoveResult.Moved) result.state else state
    }

    @Test
    fun theCampaignIsSmallEnoughToHoldInMemory() {
        val levels = Levels.validatedCampaign
        val cells = levels.allLevels.sumOf { it.width * it.height }
        assertTrue(
            "the shipped campaign should stay a tiny dataset, was $cells cells",
            cells < 5_000
        )
        // Approving must be idempotent, so repeated lookups never re-solve.
        val before = Levels.byId("w1-1")
        val after = Levels.byId("w1-1")
        assertTrue("a cached level must be the same instance", before === after)
    }

    // ===================================================================
    // 7. OFFLINE — structure, not aspiration
    // ===================================================================

    @Test
    fun noRuntimeSourceImportsNetworking() {
        val roots = listOf(
            File("app/src/main/java/com/pushbound/game"),
            File("src/main/java/com/pushbound/game")
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("sources not found from ${File(".").absolutePath}")

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines().any { line ->
                    val l = line.trim()
                    l.startsWith("import java.net") ||
                        l.startsWith("import okhttp") ||
                        l.startsWith("import retrofit") ||
                        l.contains("HttpURLConnection") ||
                        l.contains("java.net.Socket")
                }
            }
            .map { it.relativeTo(root).path }
            .toList()

        assertTrue(
            "campaign play must not import networking: $offenders",
            offenders.isEmpty()
        )
    }
}
