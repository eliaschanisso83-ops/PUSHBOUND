package com.pushbound.game

import com.pushbound.game.undo.InMemoryUndoCreditStore
import com.pushbound.game.undo.UndoAccess
import com.pushbound.game.undo.UndoCreditCodec
import com.pushbound.game.undo.UndoCreditState
import com.pushbound.game.undo.UndoGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Undo credit rules, pinned before any ad exists.
 *
 * Every guarantee here is arithmetic with the clock handed in, which is the
 * whole reason the rules were written Android-free: the midnight rollover, the
 * daily allowance and the "never negative" clamp are the three things that are
 * impossible to test honestly on a device without waiting for the day to end.
 *
 * These tests deliberately describe PLAYER-visible promises rather than
 * implementation — a starting balance of five, a balance that stops at zero,
 * an allowance of three that refuses a fourth, and a rollover that resets only
 * the allowance. If an implementation detail has to change to keep all of
 * them true, that is exactly the kind of change this suite exists to allow.
 */
class UndoCreditsTest {

    /** Far enough from any real date that the clock can never move under it. */
    private val monday = 20_000L
    private val tuesday = 20_001L
    private val wednesday = 20_002L

    // ===================================================================
    // 1. THE STARTING BALANCE
    // ===================================================================

    @Test
    fun aNewPlayerStartsWithTheFullBalanceAndNothingUsedToday() {
        val state = UndoGate.initial(monday)

        assertEquals(UndoGate.MAX_UNDO_CREDITS, state.credits)
        assertEquals("five is the number the rules promise", 5, state.credits)
        assertEquals("nothing has been restored yet", 0, state.restorationsToday)
        assertEquals(monday, state.lastResetEpochDay)
        assertEquals("a full balance means a plain undo", UndoAccess.AVAILABLE, UndoGate.access(state))
        assertEquals("the control must announce the full balance", "Undo, 5 moves remaining", UndoGate.accessibilityLabel(state))
    }

    @Test
    fun theAllowanceStartsEmptySoTheFirstRewardIsTheFirstOfTheDay() {
        val state = UndoGate.initial(monday)

        assertEquals(0, state.restorationsToday)
        assertTrue("the first restore of a day must be allowed", state.restorationsToday < UndoGate.DAILY_REWARDED_RESTORATIONS)
        assertEquals(3, UndoGate.DAILY_REWARDED_RESTORATIONS)
    }

    // ===================================================================
    // 2. SPENDING — 5 → 4 → … → 0, and stopping there
    // ===================================================================

    @Test
    fun fiveSuccessfulSpendsTakeTheBalanceFromFiveToZeroOneAtATime() {
        var state = UndoGate.initial(monday)
        val observed = mutableListOf<Int>()

        repeat(UndoGate.MAX_UNDO_CREDITS) {
            state = requireNotNull(UndoGate.spend(state)) { "a credit must still be available" }
            observed += state.credits
        }

        assertEquals(listOf(4, 3, 2, 1, 0), observed)
        assertEquals(0, state.credits)
        assertEquals("the allowance is untouched by spending", 0, state.restorationsToday)
        assertEquals(monday, state.lastResetEpochDay)
    }

    @Test
    fun atZeroTheBalanceStaysAtZeroRatherThanGoingNegative() {
        val empty = UndoGate.initial(monday).copy(credits = 0)

        assertNull("there is no next state to spend into", UndoGate.spend(empty))
        assertEquals("and the state handed in is never mutated", 0, empty.credits)

        // Repeatedly asking must never produce a negative number, no matter
        // how many times a stuck button or a queued tap asks.
        repeat(10) { assertNull(UndoGate.spend(empty)) }
    }

    @Test
    fun aZeroBalanceIsOnlyAvailableForARewardedRestorationWhileTheDayHasRoom() {
        val empty = UndoGate.initial(monday).copy(credits = 0)

        assertEquals(UndoAccess.OFFER_REWARDED, UndoGate.access(empty))

        val spent = empty.copy(restorationsToday = UndoGate.DAILY_REWARDED_RESTORATIONS)
        assertEquals(
            "three out of three is spent, not offerable",
            UndoAccess.EXHAUSTED,
            UndoGate.access(spent)
        )
    }

    // ===================================================================
    // 3. A CONFIRMED REWARD — 0 → 5
    // ===================================================================

    @Test
    fun aConfirmedRestorationFillsTheBalanceAndCountsExactlyOneUse() {
        val empty = UndoGate.initial(monday).copy(credits = 0)

        val restored = requireNotNull(UndoGate.restore(empty, monday))

        assertEquals("0 → 5, the full balance, never more", 5, restored.credits)
        assertEquals("and one counted against today", 1, restored.restorationsToday)
        assertEquals(monday, restored.lastResetEpochDay)
        assertEquals(UndoAccess.AVAILABLE, UndoGate.access(restored))
    }

    @Test
    fun aRestorationFillsFromAPartialBalanceTooSoItIsNeverAPartialTopUp() {
        // A player can hold two moves and still choose to take the reward; the
        // promise is the full five either way, not "two plus five".
        val holdingTwo = UndoGate.initial(monday).copy(credits = 2)

        val restored = requireNotNull(UndoGate.restore(holdingTwo, monday))

        assertEquals(5, restored.credits)
        assertEquals(1, restored.restorationsToday)
    }

    @Test
    fun theBalanceNeverExceedsTheMaximumWhereverARefreshComesFrom() {
        val full = UndoGate.initial(monday)
        val alreadyFull = UndoGate.restore(full, monday)

        assertEquals(
            "five is the ceiling — a refresh can top a player up but never top them over it",
            UndoGate.MAX_UNDO_CREDITS,
            requireNotNull(alreadyFull).credits
        )
        assertEquals(5, requireNotNull(alreadyFull).credits)
    }

    // ===================================================================
    // 4. THE DAILY ALLOWANCE — exactly three, then refused
    // ===================================================================

    @Test
    fun theDailyAllowanceCountsThreeSuccessfulRestorationsAndNoMore() {
        var state = UndoGate.initial(monday).copy(credits = 0)
        val counts = mutableListOf<Int>()

        repeat(UndoGate.DAILY_REWARDED_RESTORATIONS) {
            state = requireNotNull(UndoGate.restore(state, monday)) {
                "restore ${it + 1} of the day must be allowed"
            }
            counts += state.restorationsToday
        }

        assertEquals(listOf(1, 2, 3), counts)
        assertEquals("the balance is full after every one of them", 5, state.credits)
        assertEquals(UndoAccess.AVAILABLE, UndoGate.access(state))
    }

    @Test
    fun theFourthAttemptOfTheDayRefusesToRestoreAnything() {
        var state = UndoGate.initial(monday).copy(credits = 0)
        repeat(UndoGate.DAILY_REWARDED_RESTORATIONS) {
            state = requireNotNull(UndoGate.restore(state, monday))
        }

        // The player has spent the full balance again and is back at zero.
        repeat(UndoGate.MAX_UNDO_CREDITS) {
            state = requireNotNull(UndoGate.spend(state))
        }
        assertEquals(0, state.credits)

        assertNull(
            "the fourth restore of one local day must be refused outright",
            UndoGate.restore(state, monday)
        )
        assertEquals("the count must not creep past three", 3, state.restorationsToday)
        assertEquals(
            "and with no balance and no allowance, nothing may be offered",
            UndoAccess.EXHAUSTED,
            UndoGate.access(state)
        )
        assertEquals(
            "the control must say so rather than show a stale number",
            "Undo unavailable, no moves remaining",
            UndoGate.accessibilityLabel(state)
        )
    }

    @Test
    fun theFourthAttemptIsRefusedRegardlessOfTheBalanceStillHeld() {
        // The limit is on RESTORATIONS, not on the balance: even holding a full
        // five, a fourth top-up of the same day must not be granted — otherwise
        // the day's cap would only bind players who happened to be empty at the
        // moment they asked.
        var state = UndoGate.initial(monday).copy(credits = 0)
        repeat(UndoGate.DAILY_REWARDED_RESTORATIONS) { state = requireNotNull(UndoGate.restore(state, monday)) }

        assertNull(UndoGate.restore(state, monday))
        assertEquals(3, state.restorationsToday)
        assertEquals("and nothing about the refusal touches the balance", 5, state.credits)
    }

    // ===================================================================
    // 5. MIDNIGHT — the count resets, the balance does not
    // ===================================================================

    @Test
    fun theCalendarRolloverResetsTheCountAndOnlyTheCount() {
        // Three of three taken, with two moves still in hand at midnight.
        val atMidnight = UndoCreditState(
            credits = 2,
            restorationsToday = UndoGate.DAILY_REWARDED_RESTORATIONS,
            lastResetEpochDay = monday
        )

        val morning = UndoGate.rollForward(atMidnight, tuesday)

        assertEquals("the ad allowance belongs to the day it was taken on", 0, morning.restorationsToday)
        assertEquals(
            "unused moves carry over — a rollover must never take what the player holds",
            2,
            morning.credits
        )
        assertEquals(tuesday, morning.lastResetEpochDay)
        // Two moves are still held, so Undo itself is a plain undo — the
        // allowance that was refreshed is not visible until they run out.
        assertEquals(UndoAccess.AVAILABLE, UndoGate.access(morning))

        // And once they do, yesterday's three-of-three is gone: the offer is
        // open again, which is the half of the rollover that matters to a
        // player who spent their moves before midnight.
        var spent = morning
        repeat(morning.credits) { spent = requireNotNull(UndoGate.spend(spent)) }
        assertEquals(0, spent.credits)
        assertEquals(
            "the fresh day's allowance is offerable the moment the balance is empty",
            UndoAccess.OFFER_REWARDED,
            UndoGate.access(spent)
        )
    }

    @Test
    fun aRolloverWhileTheBalanceIsFullStillLeavesItFull() {
        val full = UndoGate.initial(monday).copy(restorationsToday = 3)

        val morning = UndoGate.rollForward(full, tuesday)

        assertEquals(5, morning.credits)
        assertEquals(0, morning.restorationsToday)
        assertEquals(UndoAccess.AVAILABLE, UndoGate.access(morning))
    }

    @Test
    fun aBalanceOfZeroAtMidnightStaysZeroUntilARewardEarnsItBack() {
        val empty = UndoCreditState(credits = 0, restorationsToday = 3, lastResetEpochDay = monday)

        val morning = UndoGate.rollForward(empty, tuesday)

        assertEquals(
            "the daily reset refills the AD allowance, never the Undo balance",
            0,
            morning.credits
        )
        assertEquals(0, morning.restorationsToday)
        assertEquals(
            "the player must earn the five back, and may now do so again",
            UndoAccess.OFFER_REWARDED,
            UndoGate.access(morning)
        )
    }

    @Test
    fun rollingForwardOnTheSameDayChangesNothing() {
        val state = UndoGate.initial(monday).copy(credits = 3, restorationsToday = 2)

        assertEquals(
            "idempotent, so every entry point may call it without bookkeeping",
            state,
            UndoGate.rollForward(state, monday)
        )
    }

    @Test
    fun aRewardTakenOnTheNewDayCountsAgainstTheNewDayOnly() {
        // Two taken yesterday, app not opened since, player back at zero today.
        val yesterday = UndoCreditState(credits = 0, restorationsToday = 2, lastResetEpochDay = monday)

        val restored = requireNotNull(UndoGate.restore(yesterday, tuesday))

        assertEquals(5, restored.credits)
        assertEquals("ONE today — yesterday's two do not carry into today's count", 1, restored.restorationsToday)
        assertEquals(tuesday, restored.lastResetEpochDay)
        assertEquals(UndoAccess.AVAILABLE, UndoGate.access(restored))
    }

    @Test
    fun yesterdaysSpentAllowanceDoesNotShrinkTodaysAllowance() {
        // Yesterday ended at three of three. Today the player gets a whole
        // new three of their own: a rollover is a reset, never a carry-over
        // of the previous day's deficit — and equally never a carry-over of
        // its surplus, which is what stops a stack of unused days becoming a
        // stockpile of top-ups.
        val yesterday = UndoCreditState(credits = 0, restorationsToday = 3, lastResetEpochDay = monday)

        var state = UndoGate.rollForward(yesterday, tuesday)
        assertEquals(0, state.restorationsToday)

        repeat(3) { state = requireNotNull(UndoGate.restore(state, tuesday)) }
        assertEquals(3, state.restorationsToday)
        assertNull(UndoGate.restore(state, tuesday))
    }

    // ===================================================================
    // 6. PERSISTENCE — survives a process that is no longer running
    // ===================================================================

    @Test
    fun theCodecRoundTripsEveryField() {
        val state = UndoCreditState(credits = 3, restorationsToday = 1, lastResetEpochDay = monday)

        assertEquals(state, UndoCreditCodec.decode(UndoCreditCodec.encode(state)))
    }

    @Test
    fun aSavedBalanceAndDaySurviveBeingWrittenOutAndReadBack() {
        // The shape of "destroy and recreate the application": write the state
        // somewhere durable, then read it with a brand-new object that shares
        // only the payload — exactly what happens when the process dies.
        val store = InMemoryUndoCreditStore()
        store.save(UndoCreditState(credits = 2, restorationsToday = 3, lastResetEpochDay = monday))

        val payload = UndoCreditCodec.encode(requireNotNull(store.load()))
        val reopenedStore = InMemoryUndoCreditStore(UndoCreditCodec.decode(payload))

        val restored = requireNotNull(reopenedStore.load())
        assertEquals("the balance the player was holding", 2, restored.credits)
        assertEquals("and today's used allowance, so the limit still bites", 3, restored.restorationsToday)
        assertEquals("and the day it belongs to, so a rollover still applies", monday, restored.lastResetEpochDay)

        // Recreating on the SAME day is the real persistence promise: the
        // count survives, so once the held moves are spent the limit is still
        // three-of-three rather than a fresh set.
        var drained = restored
        repeat(restored.credits) { drained = requireNotNull(UndoGate.spend(drained)) }
        assertEquals(0, drained.credits)
        assertEquals(UndoAccess.EXHAUSTED, UndoGate.access(drained))
        assertNull(UndoGate.restore(drained, monday))
    }

    @Test
    fun aBalanceWrittenOnOneDayIsRolledForwardWhenItIsReadOnTheNext() {
        val store = InMemoryUndoCreditStore(
            UndoCreditState(credits = 2, restorationsToday = 3, lastResetEpochDay = monday)
        )

        // What a fresh controller does with whatever it finds.
        val loaded = requireNotNull(store.load())
        val today = UndoGate.rollForward(loaded, tuesday)

        assertEquals("two moves were held at midnight and are held now", 2, today.credits)
        assertEquals("and today's allowance starts at zero again", 0, today.restorationsToday)
        assertEquals(tuesday, today.lastResetEpochDay)
    }

    @Test
    fun nothingSavedIsTheSameAsAFullFiveRatherThanAnError() {
        assertNull(InMemoryUndoCreditStore().load())

        val state = UndoGate.initial(monday)
        assertEquals(5, state.credits)
        assertEquals(UndoAccess.AVAILABLE, UndoGate.access(state))
    }

    @Test
    fun aMalformedSaveIsRefusedRatherThanThrown() {
        // Whatever this may be, it is not three integers. Refusing hands the
        // player their five back; guessing would risk handing them a number
        // nobody chose.
        val junk = listOf(
            "",
            "   ",
            "five|five|five",
            "5|3",
            "abc|def|ghi",
            "5|3|notanumber",
            "5|three|20000",
            "-1|20000",
            "5|-2|20000"
        )

        for (payload in junk) {
            assertNull("must be refused, not guessed: '$payload'", UndoCreditCodec.decode(payload))
        }
        assertNull(UndoCreditCodec.decode(null))
        assertNull(UndoCreditCodec.decode("|||||"))
    }

    @Test
    fun aBalanceOutsideItsOwnRangeIsClampedRatherThanTrusted() {
        // A write claiming more than the maximum is a corrupt write, not a
        // windfall: the ceiling is a property of the rules, not of the file.
        val tooMany = UndoCreditCodec.decode("99|3|$monday")
        requireNotNull(tooMany)
        assertEquals(UndoGate.MAX_UNDO_CREDITS, tooMany.credits)
        assertEquals(5, tooMany.credits)

        val negative = UndoCreditCodec.decode("-4|3|$monday")
        requireNotNull(negative)
        assertEquals("a negative balance is clamped to zero, never honoured", 0, negative.credits)

        val absurdCount = UndoCreditCodec.decode("2|47|$monday")
        requireNotNull(absurdCount)
        assertEquals(2, absurdCount.credits)
        assertTrue(
            "an oversized count can only ever make the limit stricter, never looser",
            absurdCount.restorationsToday >= UndoGate.DAILY_REWARDED_RESTORATIONS
        )
    }

    @Test
    fun fieldsAppendedByAFutureBuildDoNotResetAReturningPlayer() {
        // The format may grow. Reading only what it understands means a player
        // who updates comes back to their own two moves instead of five fresh
        // ones they never earned back.
        val future = UndoCreditCodec.decode("2|1|$monday|some-future-field|another")

        requireNotNull(future)
        assertEquals(2, future.credits)
        assertEquals(1, future.restorationsToday)
        assertEquals(monday, future.lastResetEpochDay)
    }

    @Test
    fun theStoreHoldsExactlyWhatItWasGivenUntilItIsReplaced() {
        val store = InMemoryUndoCreditStore()
        assertNull(store.load())

        val first = UndoGate.initial(monday).copy(credits = 4)
        store.save(first)
        assertEquals(first, store.load())

        val second = first.copy(credits = 0)
        store.save(second)
        assertEquals(second, store.load())
        assertNotSameContent(first, second)
    }

    private fun assertNotSameContent(a: UndoCreditState, b: UndoCreditState) {
        assertFalse("the second write must actually replace the first", a == b)
    }

    // ===================================================================
    // 7. WHAT THE CONTROL SAYS OUT LOUD
    // ===================================================================

    @Test
    fun theAccessibilityLabelCountsDownAndAnnouncesUnavailableAtZero() {
        assertEquals("Undo, 5 moves remaining", UndoGate.accessibilityLabel(UndoGate.initial(monday)))
        assertEquals(
            "Undo, 1 move remaining",
            UndoGate.accessibilityLabel(UndoGate.initial(monday).copy(credits = 1))
        )
        assertEquals(
            "Undo unavailable, no moves remaining",
            UndoGate.accessibilityLabel(UndoGate.initial(monday).copy(credits = 0))
        )
    }

    @Test
    fun theLabelNeverDescribesAnImpossibleBalance() {
        // Whatever produced it, the announcement has to be one a player can
        // actually act on: a positive count means Undo, zero means unavailable.
        listOf(5, 4, 3, 2, 1).forEach { credits ->
            val label = UndoGate.accessibilityLabel(UndoGate.initial(monday).copy(credits = credits))
            assertTrue("'$label' must announce the count", label.contains("$credits move"))
            assertFalse("'$label' must not claim Undo is unavailable", label.contains("unavailable"))
        }

        val empty = UndoGate.accessibilityLabel(UndoGate.initial(monday).copy(credits = 0))
        assertEquals(
            "zero must be announced as unavailable, with no phantom move attached",
            "Undo unavailable, no moves remaining",
            empty
        )
    }

    @Test
    fun accessIsDerivedFromTheBalanceSoItCannotDisagreeWithIt() {
        // The control reads one and taps through the other; if they could ever
        // disagree, a button would light up for a state that cannot happen.
        for (credits in 0..5) {
            for (used in 0..3) {
                val state = UndoCreditState(credits, used, monday)
                val access = UndoGate.access(state)

                if (credits > 0) {
                    assertEquals("a balance above zero always allows an undo", UndoAccess.AVAILABLE, access)
                } else if (used < 3) {
                    assertEquals("zero with room left is the offer", UndoAccess.OFFER_REWARDED, access)
                } else {
                    assertEquals("zero with no room is spent", UndoAccess.EXHAUSTED, access)
                }
            }
        }
        assertNotNull(UndoGate.initial(monday))
    }
}
