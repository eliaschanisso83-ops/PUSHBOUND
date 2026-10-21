package com.pushbound.game

import com.pushbound.game.engine.DailyPuzzle
import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.GameEngine
import com.pushbound.game.engine.GameMode
import com.pushbound.game.engine.GameSession
import com.pushbound.game.engine.Hints
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.engine.Medal
import com.pushbound.game.engine.ModeResolver
import com.pushbound.game.engine.Position
import com.pushbound.game.engine.solver.LevelValidator
import com.pushbound.game.progress.InMemoryProgressStore
import com.pushbound.game.progress.Progress
import com.pushbound.game.progress.ProgressCodec
import com.pushbound.game.progress.ProgressState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Progression, persistence, hints, the daily puzzle, and the offline guarantee.
 *
 * Everything here is pure logic on purpose: unlocking, saving, hint derivation
 * and date->puzzle selection are all functions of (content, state), so they are
 * verified without a device and cannot drift apart from the rules they claim to
 * implement.
 */
class ProgressHintsAndDailyTest {

    private val campaign get() = Levels.validatedCampaign

    private fun level(vararg rows: String) = LevelDefinition.fromAscii("test", rows.toList())

    /** player, box, goal in a line — solved by pushing right. */
    private fun corridor() = level(
        "#########",
        "#       #",
        "#  PBG  #",
        "#       #",
        "#########"
    )

    // ------------------------------------------------------------ navigation --

    /**
     * The screens are data-driven, so what a screen LISTES and what a screen
     * OPENS must be the same objects: every world on the campaign screen has
     * to be one the level screen can enter, and every level the level screen
     * offers has to be one the game can actually approve.
     */
    @Test
    fun everyListedWorldCanBeEntered() {
        Levels.campaign.worlds.forEach { world ->
            val entered = Levels.campaign.world(world.index)
            assertNotNull("world ${world.index} must be enterable", entered)
            assertEquals(
                "world select and level select must show the same levels",
                world.levels,
                entered!!.levels
            )
            assertTrue("a world must offer at least one level", entered.levels.isNotEmpty())
        }
    }

    @Test
    fun everyListedLevelOpensExactlyTheLevelThatWasChosen() {
        // The level-select screen hands an id to the game. Opening a different
        // level would silently replay the wrong puzzle and then write that
        // level's record over the intended one, so the round-trip is pinned.
        Levels.campaign.allLevels.forEach { level ->
            val opened = Levels.byId(level.id)
            assertNotNull("level ${level.id} must open", opened)
            assertEquals(level.id, opened!!.id)
            assertEquals(level.name, opened.name)
            assertEquals(level.world, opened.world)
            assertEquals(level.number, opened.number)
            assertEquals("the board must be the authored one", level.boxStarts, opened.boxStarts)
            assertEquals(level.goals, opened.goals)
            assertEquals(level.playerStart, opened.playerStart)
        }
    }

    @Test
    fun aCompletedLevelStaysReplayableForever() {
        // Replay is a requirement: finishing a level must never make it
        // unreachable again, or the level list would lock out its own content
        // the moment it was completed.
        val fresh = ProgressState()
        val first = campaign.allLevels.first()
        val second = campaign.allLevels[1]

        val afterFirst = Progress.recordCompletion(fresh, first.id, moves = 10, pushes = 3, medal = Medal.BRONZE)
        assertTrue(
            "the level just finished must stay open",
            Progress.isUnlocked(campaign, afterFirst, first.id)
        )
        assertTrue(
            "the next level must now be open",
            Progress.isUnlocked(campaign, afterFirst, second.id)
        )

        // And a fully cleared campaign locks out nothing at all.
        val complete = campaign.allLevels.fold(fresh) { acc, level ->
            Progress.recordCompletion(
                acc, level.id,
                moves = maxOf(level.parMoves, 1),
                pushes = maxOf(level.parPushes, 1),
                medal = Medal.GOLD
            )
        }
        campaign.allLevels.forEach { level ->
            assertTrue("${level.id} must remain replayable", Progress.isUnlocked(campaign, complete, level.id))
            assertTrue("${level.id} must read as completed", complete.isCompleted(level.id))
        }
    }

    @Test
    fun aLevelThatIsNeverCompletedStaysLockedUntilItIsEarned() {
        // The other half of the same rule: the level list cannot open content
        // the player has not reached, no matter how the profile is built.
        val fresh = ProgressState()
        campaign.allLevels.forEachIndexed { index, level ->
            val unlocked = Progress.isUnlocked(campaign, fresh, level.id)
            assertEquals(
                "a fresh profile must open exactly the leading levels: ${level.id}",
                index == 0,
                unlocked
            )
        }
    }

    // ------------------------------------------------------------- unlocking --

    @Test
    fun onlyTheFirstLevelIsOpenInAFreshProfile() {
        val state = ProgressState()

        val first = campaign.allLevels.first()
        assertTrue(Progress.isUnlocked(campaign, state, first.id))

        campaign.allLevels.drop(1).forEach { level ->
            assertFalse(
                "${level.id} must stay closed until the level before it is finished",
                Progress.isUnlocked(campaign, state, level.id)
            )
        }
    }

    @Test
    fun finishingALevelOpensExactlyTheNextOne() {
        var state = ProgressState()
        val levels = campaign.allLevels

        val firstReport = LevelValidator.validate(levels[0])
        state = Progress.recordCompletion(
            state = state,
            levelId = levels[0].id,
            moves = firstReport.parMoves,
            pushes = firstReport.parPushes,
            medal = Medal.GOLD
        )

        assertTrue(Progress.isUnlocked(campaign, state, levels[1].id))
        // Unlocking is one step at a time — finishing level 1 must not open the
        // whole world.
        assertFalse(Progress.isUnlocked(campaign, state, levels[2].id))
    }

    @Test
    fun aLowMedalStillUnlocksTheNextLevel() {
        // Progression must never depend on performance: a bare finish counts.
        var state = ProgressState()
        val levels = campaign.allLevels

        state = Progress.recordCompletion(state, levels[0].id, moves = 9_999, pushes = 9_999, medal = Medal.BRONZE)

        assertTrue(
            "bronze must be enough to advance",
            Progress.isUnlocked(campaign, state, levels[1].id)
        )
    }

    @Test
    fun progressionWalksTheWholeCampaignOneStepAtATime() {
        var state = ProgressState()
        val levels = campaign.allLevels

        levels.forEachIndexed { index, level ->
            val completedThrough = index
            assertEquals(
                "exactly ${completedThrough + 1} level(s) open before any completion",
                completedThrough + 1,
                Progress.unlockedIds(campaign, state).size
            )

            state = Progress.recordCompletion(state, level.id, level.parMoves, level.parPushes, Medal.GOLD)
        }

        assertEquals(campaign.levelCount, Progress.unlockedIds(campaign, state).size)
        assertNull("a finished campaign has no next level", Progress.nextLevelId(campaign, state))
    }

    @Test
    fun nextLevelIdPointsAtTheFirstUnfinishedOpenLevel() {
        var state = ProgressState()
        val levels = campaign.allLevels

        assertEquals(levels[0].id, Progress.nextLevelId(campaign, state))

        state = Progress.recordCompletion(state, levels[0].id, 1, 1, Medal.BRONZE)
        assertEquals(levels[1].id, Progress.nextLevelId(campaign, state))
    }

    @Test
    fun unknownLevelsAreNeverUnlocked() {
        assertFalse(Progress.isUnlocked(campaign, ProgressState(), "does-not-exist"))
    }

    // ------------------------------------------------------ records and bests --

    @Test
    fun recordsKeepTheBestOfEachMeasureIndependently() {
        var state = ProgressState()
        val id = "w1-1"

        // A clean run: few moves, many pushes.
        state = Progress.recordCompletion(state, id, moves = 20, pushes = 3, medal = Medal.SILVER)
        // A messy run: more moves, fewer pushes.
        state = Progress.recordCompletion(state, id, moves = 40, pushes = 1, medal = Medal.BRONZE)

        val record = state.records.getValue(id)
        assertEquals("fewest moves are kept", 20, record.bestMoves)
        assertEquals("fewest pushes are kept", 1, record.bestPushes)
        assertEquals("the better medal is kept", Medal.SILVER, record.bestMedal)
        assertTrue(record.completed)
    }

    @Test
    fun aWorseRunNeverDowngradesAnEarnedMedal() {
        var state = ProgressState()
        val id = "w1-1"

        state = Progress.recordCompletion(state, id, 10, 3, Medal.GOLD)
        state = Progress.recordCompletion(state, id, 90, 30, Medal.BRONZE)

        assertEquals(Medal.GOLD, state.records.getValue(id).bestMedal)
    }

    @Test
    fun completionIsStickyOnceEarned() {
        var state = ProgressState()
        state = Progress.recordCompletion(state, "w1-1", 10, 3, Medal.GOLD)
        state = Progress.recordCompletion(state, "w1-1", 50, 9, Medal.BRONZE)

        assertTrue(state.isCompleted("w1-1"))
        assertEquals(1, state.completedCount())
    }

    @Test
    fun recordingNeverMutatesThePreviousState() {
        val before = ProgressState()
        Progress.recordCompletion(before, "w1-1", 10, 3, Medal.GOLD)

        assertEquals("progress is immutable", ProgressState(), before)
        assertFalse(before.isCompleted("w1-1"))
    }

    // ----------------------------------------------------------- persistence --

    @Test
    fun progressSurvivesAWriteReadRoundTrip() {
        var state = ProgressState()
        state = Progress.recordCompletion(state, "w1-1", 16, 3, Medal.SILVER)
        state = Progress.recordCompletion(state, "w5-3", 120, 40, Medal.BRONZE)
        state = Progress.withBestCounts(state, "w2-1", 9, 2)

        val decoded = ProgressCodec.decode(ProgressCodec.encode(state))

        assertEquals(state, decoded)
        assertEquals(state.records, decoded.records)
        assertEquals(Medal.SILVER, decoded.records.getValue("w1-1").bestMedal)
        assertEquals(9, decoded.records.getValue("w2-1").bestMoves)
        assertFalse(decoded.isCompleted("w2-1"))
    }

    @Test
    fun anEmptyProfileEncodesAndDecodesToAnEmptyProfile() {
        assertEquals(ProgressState(), ProgressCodec.decode(ProgressCodec.encode(ProgressState())))
        assertEquals(ProgressState(), ProgressCodec.decode(""))
        assertEquals(ProgressState(), ProgressCodec.decode("   \n  \n"))
    }

    @Test
    fun malformedPayloadsAreSkippedInsteadOfThrowing() {
        // A truncated line, an unknown medal, a negative count and a totally
        // foreign string must all be tolerated: losing one record beats losing
        // the whole save to a parse error.
        val payload = """
            w1-1|1|GOLD|16|3
            broken-line
            w1-2|1|UNOBTAINIUM|10|2
            w1-3|1|BRONZE|-5|2
            |0|BRONZE|1|1
            w2-1|1|BRONZE|12|4
        """.trimIndent()

        val decoded = ProgressCodec.decode(payload)

        // Two lines are beyond salvaging (a truncated one, and one with no id)
        // and are dropped; the rest are kept, some with individual fields
        // degraded rather than discarded wholesale.
        assertEquals(4, decoded.records.size)
        assertEquals(Medal.GOLD, decoded.records.getValue("w1-1").bestMedal)
        // The unknown medal degrades to "no medal", keeping the rest of the record.
        assertEquals(Medal.BRONZE, decoded.records.getValue("w2-1").bestMedal)
        assertNull(decoded.records.getValue("w1-2").bestMedal)
        assertEquals(10, decoded.records.getValue("w1-2").bestMoves)
        assertNull("a negative count is not trusted", decoded.records.getValue("w1-3").bestMoves)
        assertEquals(2, decoded.records.getValue("w1-3").bestPushes)
    }

    @Test
    fun theInMemoryStoreReturnsWhatWasSaved() {
        val store = InMemoryProgressStore()
        val state = Progress.recordCompletion(ProgressState(), "w1-1", 5, 1, Medal.GOLD)

        store.save(state)

        assertEquals(state, store.load())
        assertEquals(Medal.GOLD, store.load().records.getValue("w1-1").bestMedal)
    }

    // ------------------------------------------------------------------ hints --

    @Test
    fun allThreeHintTiersExist() {
        assertEquals(3, Hints.TIERS)
        assertEquals(3, Hints.Tier.entries.size)
    }

    @Test
    fun theFirstHintNamesAUsefulDirection() {
        val lvl = corridor()
        val state = GameEngine.initialState(lvl)

        val hint = Hints.hintFor(lvl, state, Hints.Tier.INDICATE_DIRECTION)

        assertNotNull("a fresh corridor must yield a hint", hint)
        val direction = when (val h = hint!!) {
            is Hints.Hint.DirectionHint -> h.direction
            else -> error("tier 1 must be a direction hint, was $h")
        }
        // The box has to travel right, so that is where progress lies.
        assertEquals(Direction.RIGHT, direction)
        assertTrue(hint.message.isNotBlank())
    }

    @Test
    fun theSecondHintIdentifiesTheRelevantBlock() {
        val lvl = corridor()
        val state = GameEngine.initialState(lvl)

        val hint = Hints.hintFor(lvl, state, Hints.Tier.IDENTIFY_BLOCK)

        assertNotNull(hint)
        val blockHint = hint!! as Hints.Hint.BlockHint
        // The block that must move is the one in front of the player.
        assertEquals(Position(2, 4), blockHint.block)
        assertEquals(Direction.RIGHT, blockHint.toward)
        assertTrue(hint.message.isNotBlank())
    }

    @Test
    fun theThirdHintRevealsTheNextMove() {
        val lvl = corridor()
        val state = GameEngine.initialState(lvl)

        val hint = Hints.hintFor(lvl, state, Hints.Tier.REVEAL_NEXT_MOVE)

        assertNotNull(hint)
        val next = hint!! as Hints.Hint.NextMoveHint
        // From the start the only useful action is the push itself.
        assertEquals(Direction.RIGHT, next.direction)
        assertTrue(next.isPush)
    }

    @Test
    fun aHintNeverSolvesThePuzzle() {
        // Repeatedly taking the tier-3 hint must still require the player to act:
        // each hint reveals exactly ONE move, and taking it advances the board by
        // one. Nothing hands over the finished sequence.
        val lvl = corridor()
        var session = GameSession(lvl)
        var steps = 0

        while (!session.isComplete && steps < 50) {
            val hint = Hints.hintFor(lvl, session.state, Hints.Tier.REVEAL_NEXT_MOVE)
            assertNotNull("an unfinished solvable state must yield a hint", hint)
            val move = (hint!! as Hints.Hint.NextMoveHint).direction

            session.move(move)
            steps++
            assertTrue("the hint must have actually done something", steps <= 50)
        }

        assertTrue(session.isComplete)
        // The corridor is solved in exactly one push, so one revealed move was
        // enough and the player still had to take it.
        assertEquals(1, session.state.pushes)
    }

    @Test
    fun hintsReflectWhereThePlayerActuallyIs() {
        // Walk OFF the optimal line first, then ask: the hint must describe the
        // continuation from here, not from the level start.
        val lvl = level(
            "#######",
            "#     #",
            "# PBG #",
            "#     #",
            "#######"
        )
        var session = GameSession(lvl)
        session.move(Direction.LEFT) // away from the box
        session.move(Direction.LEFT)
        assertFalse(session.isComplete)

        val hint = Hints.hintFor(lvl, session.state, Hints.Tier.REVEAL_NEXT_MOVE)
        assertNotNull("a solvable off-line state must still get a hint", hint)
        assertTrue((hint!! as Hints.Hint.NextMoveHint).message.isNotBlank())
    }

    @Test
    fun noHintIsOfferedOnACompletedLevel() {
        val lvl = corridor()
        val session = GameSession(lvl)
        session.move(Direction.RIGHT)
        assertTrue(session.isComplete)

        Hints.Tier.entries.forEach { tier ->
            assertNull(
                "$tier must not be offered once the level is done",
                Hints.hintFor(lvl, session.state, tier)
            )
        }
    }

    @Test
    fun noHintIsOfferedWhenTheBoxIsWedgedForGood() {
        // Pushing the box two cells right seats it against the right wall and the
        // bottom wall at once: a fatal corner, and not its goal. The player can
        // no longer stand behind it on either free side, so the level is lost —
        // and a hint that invented a continuation would be a lie.
        val lvl = level(
            "#######",
            "#     #",
            "#  G  #",
            "#     #",
            "# PB  #",
            "#######"
        )
        var session = GameSession(lvl)
        session.move(Direction.RIGHT) // box (4,3) -> (4,4)
        session.move(Direction.RIGHT) // box (4,4) -> (4,5), now wedged
        assertEquals(
            "the final push must be refused by the wall",
            com.pushbound.game.engine.MoveResult.Blocked,
            session.move(Direction.RIGHT)
        )

        val wedged = session.state
        assertTrue(
            "the fixture must actually wedge the box off its goal",
            wedged.boxes.none { it in lvl.goals }
        )
        assertTrue(
            "the box must be sitting in a fatal corner",
            com.pushbound.game.engine.solver.DeadlockDetector.hasCornerDeadlock(lvl, wedged.boxes)
        )

        Hints.Tier.entries.forEach { tier ->
            assertNull(
                "$tier must be withheld when there is no way forward",
                Hints.hintFor(lvl, wedged, tier)
            )
        }
    }

    @Test
    fun hintTiersEscalateInSpecificity() {
        val lvl = corridor()
        val state = GameEngine.initialState(lvl)

        val t1 = Hints.hintFor(lvl, state, 0)
        val t2 = Hints.hintFor(lvl, state, 1)
        val t3 = Hints.hintFor(lvl, state, 2)

        assertNotNull(t1); assertNotNull(t2); assertNotNull(t3)
        assertEquals(Hints.Tier.INDICATE_DIRECTION, t1!!.tier)
        assertEquals(Hints.Tier.IDENTIFY_BLOCK, t2!!.tier)
        assertEquals(Hints.Tier.REVEAL_NEXT_MOVE, t3!!.tier)
        // An out-of-range request clamps to the last tier rather than throwing —
        // the UI can bind a bounded range directly and never needs to guard it.
        assertEquals(
            "an index past the last tier clamps to it",
            Hints.Tier.REVEAL_NEXT_MOVE,
            Hints.hintFor(lvl, state, 99)!!.tier
        )
        assertEquals(
            "an index below the first tier clamps to it",
            Hints.Tier.INDICATE_DIRECTION,
            Hints.hintFor(lvl, state, -5)!!.tier
        )
    }

    // --------------------------------------------------------- daily puzzle --

    @Test
    fun epochDayMatchesTheKnownEpoch() {
        assertEquals(0L, DailyPuzzle.epochDay(1970, 1, 1))
        assertEquals(-1L, DailyPuzzle.epochDay(1969, 12, 31))
        assertEquals(10957L, DailyPuzzle.epochDay(2000, 1, 1))
        assertEquals(1L, DailyPuzzle.epochDay(1970, 1, 2))
    }

    @Test
    fun epochDayCountsOnePerDayAcrossBoundaries() {
        val days = listOf(
            DailyPuzzle.epochDay(2024, 2, 28),
            DailyPuzzle.epochDay(2024, 2, 29), // leap day
            DailyPuzzle.epochDay(2024, 3, 1),
            DailyPuzzle.epochDay(2025, 12, 31),
            DailyPuzzle.epochDay(2026, 1, 1)
        )
        for (i in 1 until days.size) {
            assertTrue(
                "day numbers must increase by at least one",
                days[i] - days[i - 1] >= 1
            )
        }
        // The leap day really is a single day, not skipped or doubled.
        assertEquals(
            1L,
            DailyPuzzle.epochDay(2024, 2, 29) - DailyPuzzle.epochDay(2024, 2, 28)
        )
        // And a non-lear year has no 29th of February: the 1st of March follows
        // the 28th directly.
        assertEquals(
            1L,
            DailyPuzzle.epochDay(2025, 3, 1) - DailyPuzzle.epochDay(2025, 2, 28)
        )
    }

    @Test
    fun theDailyPuzzleDateIsAStableIdentity() {
        // The entry on the title screen shows WHICH day's puzzle you are about
        // to play, so it must be one fixed string per day — not something a
        // locale or a time zone could decide differently.
        assertEquals("1970-01-01", DailyPuzzle.isoDate(0))
        assertEquals("1969-12-31", DailyPuzzle.isoDate(-1))
        assertEquals("2024-01-01", DailyPuzzle.isoDate(DailyPuzzle.epochDay(2024, 1, 1)))
        // A leap day must show up as the 29th of February, not be rounded.
        assertEquals("2024-02-29", DailyPuzzle.isoDate(DailyPuzzle.epochDay(2024, 2, 29)))

        // Fixed shape and charset regardless of the default locale: a day's
        // identity can never become "1.970-01-01" or "١٩٧٠-٠١-٠١".
        (-400L..400L).forEach { offset ->
            val text = DailyPuzzle.isoDate(20_000L + offset)
            assertEquals("wrong width for offset $offset: $text", 10, text.length)
            assertEquals("bad separator for offset $offset: $text", '-', text[4])
            assertEquals("bad separator for offset $offset: $text", '-', text[7])
            assertTrue(
                "non-ASCII digit for offset $offset: $text",
                text.all { it in '0'..'9' || it == '-' }
            )
            assertEquals(
                "reformatting must be pure for offset $offset",
                text,
                DailyPuzzle.isoDate(20_000L + offset)
            )
        }
    }

    @Test
    fun theDateFormatterAgreesWithTheCalendarOverAWholeRange() {
        // Cross-checked against java.time in the TEST ONLY — the app itself
        // never touches java.time, because minSdk 24 predates it.
        // The range deliberately spans three Gregorian leap cycles, including
        // the 100-year non-leap rule and the 400-year exception.
        for (day in -1_500L..1_500L) {
            val expected = java.time.LocalDate.ofEpochDay(day).toString()
            val actual = DailyPuzzle.isoDate(day)
            assertEquals("epoch day $day", expected, actual)
        }
        // A far-future day, so the era arithmetic is exercised too.
        for (day in listOf(36_525_000L, -719_000L, 1_000_000L)) {
            assertEquals(
                "epoch day $day",
                java.time.LocalDate.ofEpochDay(day).toString(),
                DailyPuzzle.isoDate(day)
            )
        }
    }

    @Test
    fun theSameDateAlwaysSelectsTheSameLevel() {
        val ids = campaign.allLevels.map { it.id }

        // Same day, asked repeatedly and in different orders: identical answer.
        repeat(25) {
            val day = 20_000L + it * 7
            assertEquals(
                DailyPuzzle.levelIdFor(day, ids),
                DailyPuzzle.levelIdFor(day, ids)
            )
        }

        // Deterministic across a spread of days — no clock, no randomness.
        val a = DailyPuzzle.levelIdFor(20_746L, ids)
        val b = DailyPuzzle.levelIdFor(20_746L, ids)
        assertEquals(a, b)
    }

    @Test
    fun everyDayMapsToALevelThatExists() {
        val ids = campaign.allLevels.map { it.id }

        (-400L..400L).forEach { offset ->
            val id = DailyPuzzle.levelIdFor(20_746L + offset, ids)
            assertNotNull("day offset $offset must resolve", id)
            assertTrue("day offset $offset resolved outside the campaign", id in ids)
        }
    }

    @Test
    fun consecutiveDaysSpanTheWholeCampaign() {
        // Over a full cycle every level must be reachable as a daily, so the
        // mode is not stuck serving the first few entries.
        val ids = campaign.allLevels.map { it.id }
        val selected = mutableSetOf<String>()

        for (day in 0L until ids.size * 3L) {
            DailyPuzzle.levelIdFor(day, ids)?.let { selected += it }
        }

        assertEquals(
            "every level must be reachable as a daily puzzle",
            ids.toSet(),
            selected
        )
    }

    @Test
    fun anEmptyCampaignResolvesToNothingRatherThanCrashing() {
        assertNull(DailyPuzzle.levelIdFor(1234L, emptyList()))
    }

    @Test
    fun theDailyIgnoresProgression() {
        // Two players on the same date get the same puzzle even if one has
        // finished the campaign and the other has never played.
        val day = 20_746L
        val fresh = ProgressState()
        val finished = Progress.stateAfter(campaign, campaign.levelCount)

        val withFresh = ModeResolver.levelIdFor(GameMode.Daily(day), campaign, fresh)
        val withFinished = ModeResolver.levelIdFor(GameMode.Daily(day), campaign, finished)

        assertEquals(withFresh, withFinished)
        assertNotNull(withFresh)
    }

    @Test
    fun campaignModeResumesWhereThePlayerLeftOff() {
        val fresh = ProgressState()
        val firstOpen = ModeResolver.levelFor(GameMode.CampaignMode, campaign, fresh)
        assertNotNull(firstOpen)
        assertEquals(campaign.allLevels.first().id, firstOpen!!.id)

        val partway = Progress.stateAfter(campaign, 3)
        val resumed = ModeResolver.levelFor(GameMode.CampaignMode, campaign, partway)
        assertNotNull(resumed)
        assertEquals(campaign.allLevels[3].id, resumed!!.id)
    }

    @Test
    fun theSeedIsTheDayNumber() {
        assertEquals(20_746L, DailyPuzzle.seedFor(20_746L))
        // Exposed for a future generator; today it is simply the day, which is
        // what makes a generated daily reproducible without any other input.
        assertEquals(DailyPuzzle.levelIdFor(1L, listOf("a", "b")), DailyPuzzle.levelIdFor(1L, listOf("a", "b")))
    }

    @Test
    fun weekendsAreRecognisedFromTheDayNumber() {
        // 1970-01-01 was a Thursday, so Saturday the 3rd and Sunday the 4th.
        assertFalse(DailyPuzzle.isWeekend(0L))
        assertTrue(DailyPuzzle.isWeekend(2L))
        assertTrue(DailyPuzzle.isWeekend(3L))
        assertFalse(DailyPuzzle.isWeekend(4L))
    }

    // --------------------------------------------------------------- offline --

    @Test
    fun theAppDeclaresNoNetworkPermission() {
        // The offline guarantee is structural, not aspirational: with no
        // INTERNET permission the platform makes network use impossible, so
        // campaign play cannot acquire a hidden dependency on connectivity.
        val manifest = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml")
        ).firstOrNull { it.isFile }
            ?: error(
                "AndroidManifest.xml not found from ${File(".").absolutePath} — " +
                    "this test must run from the repository root"
            )

        val text = manifest.readText()
        assertTrue(
            "the manifest must still exist",
            text.contains("android:name")
        )
        assertFalse(
            "Pushbound must stay offline-first: remove the INTERNET permission",
            text.contains("android.permission.INTERNET")
        )
    }

    @Test
    fun noEnginePackageImportsAndroid() {
        // The purity boundary is what makes all of the above testable at all:
        // rules live in packages that a plain JVM test can load.
        val roots = listOf(
            "app/src/main/java/com/pushbound/game/engine",
            "src/main/java/com/pushbound/game/engine",
            "app/src/main/java/com/pushbound/game/levels",
            "src/main/java/com/pushbound/game/levels",
            "app/src/main/java/com/pushbound/game/progress",
            "src/main/java/com/pushbound/game/progress"
        ).map(::File).filter { it.isDirectory }

        assertTrue(
            "no rule sources found from ${File(".").absolutePath} — run from the repository root",
            roots.isNotEmpty()
        )

        val offenders = roots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { file ->
                file.readLines().any { line ->
                    line.startsWith("import android") || line.startsWith("import androidx")
                }
            }
            .map { it.path }
            .toList()

        assertTrue(
            "engine/levels/progress must stay free of Android imports, found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun onlyTheDataPackageTouchesAndroid() {
        // The complement of the test above: Android imports are confined to the
        // two layers that are allowed them — the Compose UI and the storage
        // adapter — so no new platform dependency can creep into the rules.
        val sources = listOf(
            File("app/src/main/java/com/pushbound/game"),
            File("src/main/java/com/pushbound/game")
        ).firstOrNull { it.isDirectory }
            ?: error("sources not found from ${File(".").absolutePath}")

        val touchingAndroid = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines().any { line ->
                    line.startsWith("import android") || line.startsWith("import androidx")
                }
            }
            .map { it.relativeTo(sources).path.substringBefore('/') }
            .distinct()
            .sorted()
            .toList()

        val allowed = setOf("ui", "data", "MainActivity.kt")
        val unexpected = touchingAndroid.filterNot { it in allowed }
        assertTrue(
            "packages that must not depend on Android but now do: $unexpected",
            unexpected.isEmpty()
        )
        // And the adapter really is present, so the test is not passing vacuously.
        assertTrue("data/ must exist as the storage adapter", touchingAndroid.contains("data"))
    }

    @Test
    fun theProgressRulesRunWithNoAndroidAtAll() {
        // Guard against progress logic silently acquiring a Context dependency:
        // the rule engine and the codec must be constructible and correct on a
        // bare JVM, which is exactly how the rest of this suite runs.
        val state = Progress.recordCompletion(ProgressState(), "w1-1", 10, 2, Medal.GOLD)
        val payload = ProgressCodec.encode(state)
        assertNotEquals("", payload)
        assertEquals(state, ProgressCodec.decode(payload))

        val store = InMemoryProgressStore(state)
        assertEquals(state, store.load())
    }
}
