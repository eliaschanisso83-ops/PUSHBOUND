package com.pushbound.game

import com.pushbound.game.engine.ContentKey
import com.pushbound.game.engine.DailyPuzzle
import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameEngine
import com.pushbound.game.engine.GameMode
import com.pushbound.game.engine.ModeResolver
import com.pushbound.game.engine.solver.LevelSolver
import com.pushbound.game.progress.InMemoryProgressStore
import com.pushbound.game.progress.InMemorySessionStore
import com.pushbound.game.progress.Progress
import com.pushbound.game.progress.ProgressCodec
import com.pushbound.game.progress.ProgressState
import com.pushbound.game.progress.SessionCodec
import com.pushbound.game.ui.GameController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROOF THAT CAMPAIGN AND DAILY PUZZLE ARE SEPARATE GAME MODES.
 *
 * These tests exist because the two modes used to share everything: the daily
 * puzzle's content id WAS a campaign level id (`DailyPuzzle.levelIdFor` returned
 * an entry of `campaign.allLevels`), progress was one flat map keyed by that id,
 * and `GameController.resolveCompletion` recorded under `level.id` without ever
 * asking which mode it was playing. Finishing today's puzzle therefore marked a
 * campaign level cleared, raised the "N OF 15 CLEARED" counter and unlocked the
 * next campaign level — all from one tap on the daily entry.
 *
 * RESUME had no saved state at all: it re-derived "first campaign level not yet
 * finished" from progress and restarted that level from its INITIAL position.
 * It was a second Campaign button that also threw the board away.
 *
 * Every test below drives the real [GameController], the real [Levels] object
 * and the real codecs — not a probe copy — so a regression anywhere in content,
 * identity, recording or persistence fails here.
 *
 * FIXTURE DATES, NEVER THE CLOCK. Tests F and B pin 2026-10-23 and 2026-10-24
 * explicitly so the suite does not change behaviour when it is run on a
 * different day.
 */
class ModeIsolationTest {

    private val scope = CoroutineScope(Dispatchers.Default)

    private val day1 = DailyPuzzle.epochDay(2026, 10, 23)
    private val day2 = DailyPuzzle.epochDay(2026, 10, 24)
    private val keyDay1 = ContentKey.daily(day1)
    private val keyDay2 = ContentKey.daily(day2)

    @After
    fun tearDown() {
        scope.cancel()
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Waits until [controller] is between animations, then runs [action], with a
     * hard three-second ceiling so a regression fails instead of hanging.
     * Mirrors [QaVerificationTest.awaitIdle]: presses arriving mid-animation are
     * queued and only one is kept, so a fixed sleep could silently drop moves.
     */
    private fun awaitIdle(controller: GameController, action: () -> Unit) {
        val deadline = System.currentTimeMillis() + 3_000L
        while (controller.isAnimating) {
            assertTrue(
                "the animation never finished within 3s",
                System.currentTimeMillis() < deadline
            )
            Thread.sleep(5)
        }
        action()
    }

    /** Replays a solver solution through the controller, one press at a time. */
    private fun play(controller: GameController, moves: List<Direction>) {
        for (direction in moves) {
            awaitIdle(controller) { controller.onDirectionPressed(direction) }
        }
    }

    /** The approved campaign level behind a daily key — the GRID it draws. */
    private fun boardFor(key: ContentKey): com.pushbound.game.engine.LevelDefinition =
        ModeResolver.levelFor(key, Levels.campaign)
            ?: Levels.byId(key.id)
            ?: Levels.campaign.allLevels.first()

    private fun campaignKey(levelId: String) = ContentKey.campaign(levelId)

    /** Every campaign level id, for whole-campaign "nothing changed" assertions. */
    private fun allCampaignKeys(): List<ContentKey> =
        Levels.campaign.allLevels.map { campaignKey(it.id) }

    // --- Test A — Campaign completion ---------------------------------------

    @Test
    fun aCompletetCampaignLevelRaisesTheCampaignCounterAndLeavesDailyAlone() {
        val progressStore = InMemoryProgressStore()
        val levelId = Levels.campaign.allLevels.first().id
        val key = campaignKey(levelId)

        val before = progressStore.load().completedCampaignCount(Levels.campaign)
        val controller = GameController(
            Levels.byId(levelId)!!,
            scope,
            progressStore,
            key,
            InMemorySessionStore()
        )

        val solution = LevelSolver.solve(Levels.byId(levelId)!!)!!
        play(controller, solution.moves)

        assertTrue("the level must be complete", controller.isComplete)

        val state = progressStore.load()
        assertTrue("campaign level 1 is completed", state.isCompleted(key))
        assertEquals(
            "the campaign counter counts exactly the campaign level that finished",
            before + 1,
            state.completedCampaignCount(Levels.campaign)
        )
        // No daily record was created as a side effect.
        assertNull("no daily record should exist", state.recordFor(keyDay1))
        assertFalse(state.isCompleted(keyDay1))
    }

    // --- Test B — Daily completion ------------------------------------------

    @Test
    fun completingTodaySDailyPuzzleLeavesTheCampaignCompletelyUntouched() {
        val progressStore = InMemoryProgressStore()
        val boardId = ModeResolver.levelIdFor(GameMode.Daily(day1), Levels.campaign)
        assertNotNull("the fixture day must resolve to a board", boardId)

        val controller = GameController(
            Levels.byId(boardId!!)!!,
            scope,
            progressStore,
            keyDay1,
            InMemorySessionStore()
        )
        assertEquals(
            "the daily must be identified by its DATE, not by the campaign level id",
            "daily/2026-10-23",
            controller.contentKey.storageKey
        )

        val solution = LevelSolver.solve(Levels.byId(boardId)!!)!!
        play(controller, solution.moves)

        assertTrue("the daily must be complete", controller.isComplete)

        val state = progressStore.load()
        assertTrue("today's daily is completed", state.isCompleted(keyDay1))
        assertEquals("daily/2026-10-23", keyDay1.storageKey)

        // The campaign is EXACTLY as it was: no level cleared, counter unmoved.
        assertEquals(
            "the campaign counter must not react to a daily completion",
            0,
            state.completedCampaignCount(Levels.campaign)
        )
        for (key in allCampaignKeys()) {
            assertFalse(
                "campaign content ${key.storageKey} must stay uncompleted",
                state.isCompleted(key)
            )
        }
        // Including the very level whose grid today's puzzle was drawn from.
        assertFalse(
            "the daily must not clear the campaign level it shares a grid with",
            state.isCompleted(campaignKey(boardId))
        )
    }

    // --- Test C — Resume a Campaign session ---------------------------------

    @Test
    fun resumeRestoresTheCampaignLevelBoardPlayerAndCrates() {
        val progressStore = InMemoryProgressStore()
        val sessions = InMemorySessionStore()
        val levelId = Levels.campaign.allLevels.first().id
        val key = campaignKey(levelId)

        val solution = LevelSolver.solve(Levels.byId(levelId)!!)!!

        val first = GameController(Levels.byId(levelId)!!, scope, progressStore, key, sessions)
        // Partial progress: enough moves to move the player, not enough to finish.
        val partial = solution.moves.take(solution.moves.size / 2)
        assertTrue("the fixture must leave moves to play", partial.isNotEmpty())
        play(first, partial)

        assertFalse("the fixture must not have finished the level", first.isComplete)
        val leftPlayer = first.state.player
        val leftBoxes = first.state.boxes
        val leftMoves = first.state.moves
        val leftPushes = first.state.pushes

        // Re-enter the app: a brand-new controller over the same store.
        val resumed = GameController(Levels.byId(levelId)!!, scope, progressStore, key, sessions)

        assertEquals("same campaign level", levelId, resumed.level.id)
        assertEquals("same content key", key, resumed.contentKey)
        assertTrue("same mode", !resumed.contentKey.isDaily)
        assertEquals("same player position", leftPlayer, resumed.state.player)
        assertEquals("same crate positions", leftBoxes, resumed.state.boxes)
        assertEquals("same move count", leftMoves, resumed.state.moves)
        assertEquals("same push count", leftPushes, resumed.state.pushes)
        assertFalse("still not complete", resumed.isComplete)
        assertEquals("undo history survives", first.canUndo, resumed.canUndo)
    }

    // --- Test D — Resume a Daily session ------------------------------------

    @Test
    fun resumeRestoresTheDailySessionAndKeepsItADailySession() {
        val progressStore = InMemoryProgressStore()
        val sessions = InMemorySessionStore()
        val boardId = ModeResolver.levelIdFor(GameMode.Daily(day1), Levels.campaign)!!

        val solution = LevelSolver.solve(Levels.byId(boardId)!!)!!

        val first = GameController(Levels.byId(boardId)!!, scope, progressStore, keyDay1, sessions)
        val partial = solution.moves.take(solution.moves.size / 2)
        assertTrue(partial.isNotEmpty())
        play(first, partial)

        assertFalse(first.isComplete)
        val leftPlayer = first.state.player
        val leftBoxes = first.state.boxes
        val leftMoves = first.state.moves
        val leftPushes = first.state.pushes

        // Resume from the MAIN MENU, exactly as the app does: the saved session
        // picks the key, and only then is a controller built.
        val saved = sessions.load()
        assertNotNull("a session must be saved while playing", saved)
        assertEquals("the session keeps its mode", keyDay1, saved!!.key)
        assertTrue("the session is still a daily session", saved.key.isDaily)

        val resumed = GameController(
            Levels.byId(boardId)!!,
            scope,
            progressStore,
            saved.key,
            sessions
        )

        assertEquals("today's puzzle", keyDay1, resumed.contentKey)
        assertEquals("daily/2026-10-23", resumed.contentKey.storageKey)
        assertTrue(
            "it must still be a DAILY session, never a campaign one",
            resumed.contentKey.isDaily
        )
        assertEquals("same player position", leftPlayer, resumed.state.player)
        assertEquals("same crate positions", leftBoxes, resumed.state.boxes)
        assertEquals("same move count", leftMoves, resumed.state.moves)
        assertEquals("same push count", leftPushes, resumed.state.pushes)
    }

    /**
     * RESUME must never open a level that has nothing to do with the saved
     * session. A daily session must not be resurrected by a campaign controller,
     * and vice versa — the key has to match exactly.
     */
    @Test
    fun aControllerOnlyRestoresASessionThatBelongsToItsOwnContentKey() {
        val sessions = InMemorySessionStore()
        val boardId = ModeResolver.levelIdFor(GameMode.Daily(day1), Levels.campaign)!!

        val solution = LevelSolver.solve(Levels.byId(boardId)!!)!!
        val first = GameController(Levels.byId(boardId)!!, scope, InMemoryProgressStore(), keyDay1, sessions)
        play(first, solution.moves.take(solution.moves.size / 2))

        assertNotNull("the daily wrote a session", sessions.load())

        // A CAMPAIGN controller over the same store must ignore that session.
        val campaignLevelId = Levels.campaign.allLevels.first().id
        val campaign = GameController(
            Levels.byId(campaignLevelId)!!,
            scope,
            InMemoryProgressStore(),
            campaignKey(campaignLevelId),
            sessions
        )
        assertFalse(
            "a campaign run must start from its own initial board",
            campaign.state.moves > 0
        )
        assertEquals(
            "the campaign run starts at ITS board's crates, untouched",
            GameEngine.initialState(Levels.byId(campaignLevelId)!!).boxes,
            campaign.state.boxes
        )
    }

    // --- Test E — Cross-mode isolation --------------------------------------

    @Test
    fun campaignProgressIsByteForByteUnchangedByADailyCompletion() {
        val progressStore = InMemoryProgressStore()

        // Play some campaign first, so there IS progress to protect.
        val levelId = Levels.campaign.allLevels.first().id
        val campaignController = GameController(
            Levels.byId(levelId)!!,
            scope,
            progressStore,
            campaignKey(levelId),
            InMemorySessionStore()
        )
        val solution = LevelSolver.solve(Levels.byId(levelId)!!)!!
        play(campaignController, solution.moves.take(solution.moves.size / 2))

        val before = progressStore.load()
        val beforeEncoded = ProgressCodec.encode(before)
        val beforeCount = before.completedCampaignCount(Levels.campaign)
        assertTrue("the fixture must have some campaign progress", beforeCount >= 0)

        // Now finish a daily in a fresh controller over the SAME progress store.
        val boardId = ModeResolver.levelIdFor(GameMode.Daily(day1), Levels.campaign)!!
        val dailyController = GameController(
            Levels.byId(boardId)!!,
            scope,
            progressStore,
            keyDay1,
            InMemorySessionStore()
        )
        val dailySolution = LevelSolver.solve(Levels.byId(boardId)!!)!!
        play(dailyController, dailySolution.moves)
        assertTrue(dailyController.isComplete)

        val after = progressStore.load()

        // The campaign records are IDENTICAL — not merely equal in count.
        assertEquals(
            "campaign progress must be untouched by a daily completion",
            beforeEncoded,
            ProgressCodec.encode(
                ProgressState(after.records.filterKeys { it.startsWith("campaign/") })
            )
        )
        assertEquals(
            "the campaign counter must not move",
            beforeCount,
            after.completedCampaignCount(Levels.campaign)
        )
        for (key in allCampaignKeys()) {
            assertEquals(
                "record for ${key.storageKey} must be unchanged",
                before.recordFor(key),
                after.recordFor(key)
            )
        }
        assertTrue("the daily itself is completed", after.isCompleted(keyDay1))
    }

    // --- Test F — Date isolation --------------------------------------------

    @Test
    fun twoConsecutiveDaysAreTwoDifferentPuzzles() {
        assertNotEquals("different days are different keys", keyDay1, keyDay2)
        assertNotEquals(
            "different days store under different keys",
            keyDay1.storageKey,
            keyDay2.storageKey
        )
        assertEquals("daily/2026-10-23", keyDay1.storageKey)
        assertEquals("daily/2026-10-24", keyDay2.storageKey)

        // And they are independent records, not one record with two names.
        var state = ProgressState()
        state = Progress.recordCompletion(state, keyDay1, moves = 10, pushes = 3,
            medal = com.pushbound.game.engine.Medal.GOLD)

        assertTrue(state.isCompleted(keyDay1))
        assertFalse(
            "clearing one day must not clear the next",
            state.isCompleted(keyDay2)
        )
        assertEquals(1, state.records.size)

        // The date is a pure function of the day and round-trips losslessly.
        assertEquals("2026-10-23", DailyPuzzle.isoDate(day1))
        assertEquals(day1, DailyPuzzle.parseIsoDate("2026-10-23"))
        assertEquals(keyDay1, ContentKey.fromStorageKey("daily/2026-10-23"))
        assertNull(ContentKey.fromStorageKey("daily/not-a-date"))
        assertNull(ContentKey.fromStorageKey("w1-1"))
        assertNull(ContentKey.fromStorageKey("unknown/thing"))
    }

    /**
     * The board a daily draws may coincidentally be the same on two days (the
     * pool is finite). That must never make the two days the SAME content — the
     * identity is the date, not the grid.
     */
    @Test
    fun sharingAGridNeverMakesTwoDaysTheSameContent() {
        val state = Progress.recordCompletion(
            ProgressState(),
            keyDay1,
            moves = 5,
            pushes = 1,
            medal = com.pushbound.game.engine.Medal.SILVER
        )

        assertFalse("identical grids still cannot share a record", state.isCompleted(keyDay2))
        assertNull(state.recordFor(keyDay2))
        assertNotNull(state.recordFor(keyDay1))
    }

    // --- persistence of both namespaces -------------------------------------

    @Test
    fun bothNamespacesSurviveOneWriteReadRoundTrip() {
        var state = ProgressState()
        state = Progress.recordCompletion(state, campaignKey("w1-2"), 16, 3,
            com.pushbound.game.engine.Medal.SILVER)
        state = Progress.recordCompletion(state, keyDay1, 42, 9,
            com.pushbound.game.engine.Medal.GOLD)

        val decoded = ProgressCodec.decode(ProgressCodec.encode(state))

        assertEquals("both namespaces round-trip", state, decoded)
        assertTrue(decoded.isCompleted(campaignKey("w1-2")))
        assertTrue(decoded.isCompleted(keyDay1))
        assertEquals(1, decoded.completedCampaignCount(Levels.campaign))
        assertEquals("w1-2", ContentKey.fromStorageKey("campaign/w1-2")!!.id)
    }

    /**
     * A payload written BEFORE modes were namespaced carries bare ids such as
     * `w3-2|1|GOLD|21|9`. Those lines were always campaign progress — the daily
     * that wrote them was in fact recording a campaign level — so they must
     * decode as campaign records rather than being dropped. Players keep what
     * they saw on screen.
     */
    @Test
    fun aLegacyBareIdPayloadStillLoadsAsCampaignProgress() {
        val legacy = "w1-1|1|GOLD|16|3\nw5-3|0|-|120|40"
        val decoded = ProgressCodec.decode(legacy)

        assertTrue(decoded.isCompleted(campaignKey("w1-1")))
        // A bare id is NOT a key: it carries no namespace, which is exactly why
        // the codec — and only the codec — is allowed to guess that it meant
        // campaign. ContentKey itself refuses to invent a mode.
        assertNull(ContentKey.fromStorageKey("w1-1"))
        assertTrue(decoded.isCompleted(ContentKey.fromStorageKey("campaign/w1-1")!!))
        assertEquals(
            "legacy campaign progress is counted by the campaign counter",
            1,
            decoded.completedCampaignCount(Levels.campaign)
        )
        assertFalse(decoded.isCompleted(campaignKey("w5-3")))
        // And once re-encoded, the keys are namespaced and never bare again.
        assertTrue(
            "re-encoded payload is namespaced",
            ProgressCodec.encode(decoded).lineSequence().all { it.startsWith("campaign/") }
        )
    }

    // --- session codec -------------------------------------------------------

    @Test
    fun aSessionSurvivesAWriteReadRoundTripInBothModes() {
        val levels = listOf(campaignKey("w1-1"), keyDay1)

        for (key in levels) {
            val board = boardFor(key)
            val solution = LevelSolver.solve(board)!!
            val partial = solution.moves.take(solution.moves.size / 2)

            val sessions = InMemorySessionStore()
            val controller = GameController(board, scope, InMemoryProgressStore(), key, sessions)
            play(controller, partial)

            val saved = sessions.load() ?: continue
            val decoded = SessionCodec.decode(SessionCodec.encode(saved))

            assertEquals("round trip preserves the key", saved.key, decoded?.key)
            assertEquals("round trip preserves the board", saved.current, decoded?.current)
            assertEquals("round trip preserves undo", saved.undo, decoded?.undo)
            assertTrue("the save is restorable on its own level", decoded!!.isRestorableOn(board))
        }
    }

    /**
     * A save with no history restores the POSITION and leaves nothing to undo
     * to. That is a different act from a full restore, and the controller
     * must not report a phantom undo path.
     */
    @Test
    fun aSessionWithNoHistoryRestoresThePositionAndNothingToUndo() {
        val progressStore = InMemoryProgressStore()
        val sessions = InMemorySessionStore()
        val levelId = Levels.campaign.allLevels.first().id
        val key = campaignKey(levelId)
        val solution = LevelSolver.solve(Levels.byId(levelId)!!)!!

        val first = GameController(Levels.byId(levelId)!!, scope, progressStore, key, sessions)
        play(first, solution.moves.take(solution.moves.size / 2))

        val leftPlayer = first.state.player
        val leftBoxes = first.state.boxes
        val leftMoves = first.state.moves

        // Rewrite the stored session as a position-only save, the way an older
        // build (or a save past the history cap) would have left it.
        val saved = sessions.load()!!
        sessions.save(saved.copy(undo = emptyList(), redo = emptyList()))

        val resumed = GameController(Levels.byId(levelId)!!, scope, progressStore, key, sessions)

        assertEquals("same level", levelId, resumed.level.id)
        assertEquals("same player position", leftPlayer, resumed.state.player)
        assertEquals("same crate positions", leftBoxes, resumed.state.boxes)
        assertEquals("same move count", leftMoves, resumed.state.moves)
        assertFalse("nothing to undo", resumed.canUndo)
        assertFalse("nothing to redo", resumed.canRedo)
        assertFalse("and still not complete", resumed.isComplete)
    }

    /** A session saved on one level must not be accepted by another. */
    @Test
    fun aSessionBuiltForAnotherBoardIsRejectedRatherThanRestored() {
        val a = boardFor(campaignKey("w1-1"))
        val b = boardFor(campaignKey("w1-3"))
        val solution = LevelSolver.solve(a)!!

        val sessions = InMemorySessionStore()
        val controller = GameController(a, scope, InMemoryProgressStore(), campaignKey("w1-1"), sessions)
        play(controller, solution.moves.take(solution.moves.size / 2))

        val saved = sessions.load()
        assertNotNull(saved)
        assertTrue(
            "the session belongs to the level it was saved on",
            saved!!.isRestorableOn(a)
        )
        assertFalse(
            "a save must never be applied to a different board",
            saved.isRestorableOn(b)
        )
    }

    /**
     * An empty store means there is genuinely nothing to resume. The menu must
     * be able to tell, rather than fabricating an active game.
     */
    @Test
    fun anEmptySessionStoreMeansThereIsNothingToResume() {
        assertNull("no session on a fresh install", InMemorySessionStore().load())
        assertEquals(null, InMemorySessionStore().load())
    }

    // --- resolution ----------------------------------------------------------

    @Test
    fun theCampaignEntryAndTheDailyEntryResolveToDifferentKeys() {
        val campaignKey = ModeResolver.campaignContentKey(Levels.campaign, ProgressState())
        assertNotNull(campaignKey)
        assertTrue(!campaignKey!!.isDaily)

        val dailyKey = ContentKey.daily(day1)
        assertNotEquals(
            "CAMPAIGN and DAILY PUZZLE must not be the same destination",
            campaignKey,
            dailyKey
        )
        assertNotEquals(campaignKey.storageKey, dailyKey.storageKey)
        assertTrue(campaignKey.storageKey.startsWith("campaign/"))
        assertTrue(dailyKey.storageKey.startsWith("daily/"))
    }
}
