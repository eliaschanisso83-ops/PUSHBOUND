package com.pushbound.game

import com.pushbound.game.ads.AdsGateway
import com.pushbound.game.ads.NoOpAdsGateway
import com.pushbound.game.ads.RewardedAvailability
import com.pushbound.game.ads.RewardedOutcome
import com.pushbound.game.engine.DailyPuzzle
import com.pushbound.game.engine.Direction
import com.pushbound.game.engine.LevelDefinition
import com.pushbound.game.progress.InMemoryProgressStore
import com.pushbound.game.ui.GameController
import com.pushbound.game.undo.InMemoryUndoCreditStore
import com.pushbound.game.undo.UndoAccess
import com.pushbound.game.undo.UndoCreditStore
import com.pushbound.game.undo.UndoGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The whole Undo-credit contract as the PLAYER meets it: what a tap costs, what
 * a tap earns, and — the part that matters most — what a tap must never do.
 *
 * WHY THIS LAYER AND NOT A SCREEN TEST — every guarantee below is a statement
 * about state the control bar merely renders: a balance, an offer, a pending
 * flag, a status line. Testing them here means they are proven on a plain JVM,
 * on every commit, without a device, an emulator or a network. What a screen
 * test would add is that the numbers are DRAWN — and the drawing is a
 * two-argument pass-through of exactly these getters, pinned separately by
 * [UndoUiPresentationTest].
 *
 * THE ADVERTISING GATEWAY IS SCRIPTED, never mocked into silence. Every test
 * that can reach it counts the calls that reached it, because "the offer did
 * not load an ad" is not a detail — it is the difference between a
 * confirmation and an interruption.
 */
class UndoRestorationFlowTest {

    private val scope = CoroutineScope(Dispatchers.Default)
    private lateinit var ads: ScriptedAdsGateway

    @Before
    fun setUp() {
        ads = ScriptedAdsGateway()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ===================================================================
    // HELPERS
    // ===================================================================

    /**
     * A room with no boxes and no goals, so it can never be completed and the
     * player can walk for as long as a test needs.
     *
     * The player starts in the middle of a three-wide cell of open floor, which
     * makes one step to the right legal from the starting cell every single
     * time: an undo puts the player back where they began, so the next step
     * succeeds again. That is what lets a test drain a whole balance without
     * ever having to reason about where the character is standing.
     */
    private fun room(id: String = "undo-restoration-room") = LevelDefinition.fromAscii(
        name = "restoration room",
        rows = listOf(
            "#####",
            "#   #",
            "# P #",
            "#   #",
            "#####"
        ),
        id = id
    )

    private fun controller(
        store: UndoCreditStore = InMemoryUndoCreditStore(),
        gateway: AdsGateway = ads
    ) = GameController(
        level = room(),
        scope = scope,
        progressStore = InMemoryProgressStore(),
        ads = gateway,
        undoCreditStore = store
    )

    /**
     * Runs [action] with the board settled, then waits out whatever it started.
     *
     * Waiting on BOTH sides is deliberate. Acting first would let a press land
     * while the 110ms animation is still live, where the controller queues it
     * instead of performing it — and a queued press is indistinguishable from a
     * lost one a moment later, which would turn "this cost a credit" into an
     * assertion about nothing at all. Waiting only afterwards would let the
     * second action run against a board the first had not finished moving.
     */
    private fun act(controller: GameController, action: () -> Unit) {
        awaitIdle(controller)
        action()
        awaitIdle(controller)
    }

    /** Waits until no move animation is running — hard-ceilinged so a hang fails. */
    private fun awaitIdle(controller: GameController) {
        val deadline = System.currentTimeMillis() + 3_000L
        while (controller.isAnimating) {
            assertTrue(
                "the animation never finished within 3s",
                System.currentTimeMillis() < deadline
            )
            Thread.sleep(5)
        }
    }

    /** One accepted step to the right. Legal from the starting cell, always. */
    private fun step(controller: GameController) =
        act(controller) { controller.onDirectionPressed(Direction.RIGHT) }

    /** One successful undo — which is exactly one credit, if there was one. */
    private fun undo(controller: GameController) =
        act(controller) { controller.undo() }

    /**
     * Spends the whole balance, one credit per accepted undo.
     *
     * Fails loudly rather than looping forever if an undo ever stops costing a
     * credit, because a silently stuck loop would look like a hang instead of
     * the regression it is.
     */
    private fun drainCredits(controller: GameController) {
        var iterations = 0
        while (controller.undoCreditsRemaining > 0) {
            val before = controller.undoCreditsRemaining
            step(controller)
            undo(controller)
            assertTrue(
                "an accepted undo must cost exactly one credit",
                controller.undoCreditsRemaining == before - 1
            )
            assertTrue(
                "draining five credits must take five steps, not more",
                ++iterations <= UndoGate.MAX_UNDO_CREDITS
            )
        }
        assertEquals(0, controller.undoCreditsRemaining)
    }

    /**
     * Drains to zero, opens the offer, and watches an ad that pays out.
     *
     * Returns the number of ads actually requested, so a caller can assert that
     * the whole round trip cost exactly one.
     */
    private fun earnRestoration(controller: GameController): Int {
        drainCredits(controller)
        undo(controller)
        assertNotNull("a zero balance must raise the offer", controller.undoRestorationOffer)
        val before = ads.showRewardedCalls
        ads.outcomes = listOf(RewardedOutcome.REWARDED)
        act(controller) { controller.confirmUndoRestoration() }
        assertNull("a delivered reward closes the offer", controller.undoRestorationOffer)
        assertFalse(controller.undoRestorationPending)
        return ads.showRewardedCalls - before
    }

    /**
     * Refuses the test rather than letting it pass for the wrong reason if the
     * local calendar day turned over underneath it.
     *
     * The controller reads the real clock, because that is the only way the
     * midnight rollover is genuine. The cost is a one-in-a-hundred-million
     * flake window at exactly 00:00 — which is paid by ASSUMING the day held,
     * not by pretending it did.
     */
    private fun assumeSameLocalDay(day: Long) {
        assumeTrue(
            "the local day changed mid-test, so the daily limit cannot be asserted",
            DailyPuzzle.todayEpochDay() == day
        )
    }

    // ===================================================================
    // 1. THE STARTING BALANCE AND WHAT NEVER SPENDS IT
    // ===================================================================

    @Test
    fun aFreshControllerStartsWithFiveCreditsAndAsksTheSdkForNothing() {
        val controller = controller()

        assertEquals(5, controller.undoCreditsRemaining)
        assertEquals(0, controller.undoRestorationsToday)
        assertEquals(UndoAccess.AVAILABLE, controller.undoAccess)
        assertEquals("Undo, 5 moves remaining", controller.undoAccessibilityDescription)
        assertFalse("there is nothing to offer yet", controller.canRequestUndoRestoration)
        assertNull(controller.undoRestorationOffer)
        assertFalse(controller.undoRestorationPending)
        assertNull(controller.undoNotice)

        // Construction is not an advertisement: initialising the controller
        // reads the SDK's availability at most, and must never load one.
        assertEquals("no ad may be preloaded by simply starting the game", 0, ads.preloads)
        assertEquals("no ad may be shown by simply starting the game", 0, ads.showRewardedCalls)
    }

    @Test
    fun oneAcceptedUndoCostsExactlyOneCredit() {
        val controller = controller()

        step(controller)
        assertTrue(controller.canUndo)
        assertEquals(5, controller.undoCreditsRemaining)

        undo(controller)

        assertEquals("5 → 4, immediately and visibly", 4, controller.undoCreditsRemaining)
        assertEquals(0, controller.undoRestorationsToday)
        assertNull("a successful undo raises no offer", controller.undoRestorationOffer)
        assertNull(controller.undoNotice)
        assertEquals("Undo, 4 moves remaining", controller.undoAccessibilityDescription)
        assertEquals(0, ads.showRewardedCalls)
    }

    @Test
    fun anUndoWithNoHistoryToWalkBackCostsNothing() {
        val controller = controller()

        // No move has been made, so the engine has nothing to return to. The
        // tap must fall through untouched: this is the "failed Undo" case.
        undo(controller)

        assertEquals(
            "a refused undo must not charge for its own refusal",
            5,
            controller.undoCreditsRemaining
        )
        assertFalse(controller.canUndo)
        assertNull(controller.undoRestorationOffer)
        assertNull(controller.undoNotice)
        assertEquals(0, ads.showRewardedCalls)
    }

    @Test
    fun fiveAcceptedUndosReachZeroAndTheBalanceNeverGoesBelowIt() {
        val controller = controller()
        val observed = mutableListOf<Int>()

        repeat(UndoGate.MAX_UNDO_CREDITS) {
            step(controller)
            undo(controller)
            observed += controller.undoCreditsRemaining
        }

        assertEquals(listOf(4, 3, 2, 1, 0), observed)
        assertEquals(0, controller.undoCreditsRemaining)
        assertEquals("the label follows the number down", "Undo unavailable, no moves remaining", controller.undoAccessibilityDescription)
        assertEquals(0, ads.showRewardedCalls)
    }

    // ===================================================================
    // 2. AT ZERO — the offer, and the tap that must not reach the SDK
    // ===================================================================

    @Test
    fun aTapAtZeroRaisesTheOfferAndRequestsNoAdAtAll() {
        val controller = controller()
        drainCredits(controller)

        undo(controller)

        assertNotNull("the player must be asked, not acted for", controller.undoRestorationOffer)
        assertFalse("nothing is in flight: the player has not chosen yet", controller.undoRestorationPending)
        assertEquals(0, controller.undoCreditsRemaining)
        assertTrue("the control must stay tappable so earning more stays reachable", controller.canRequestUndoRestoration)
        assertEquals(UndoAccess.OFFER_REWARDED, controller.undoAccess)
        assertNull("an offer is not a complaint: no stale status line", controller.undoNotice)

        assertEquals("opening the offer must not fetch an ad", 0, ads.showRewardedCalls)
        assertEquals("opening the offer must not preload one either", 0, ads.preloads)

        // Asking again changes nothing — the offer is already on screen.
        undo(controller)
        assertEquals(0, controller.undoCreditsRemaining)
        assertEquals(0, ads.showRewardedCalls)
    }

    @Test
    fun decliningTheOfferCostsNothingAndClosesItCleanly() {
        val controller = controller()
        drainCredits(controller)
        undo(controller)
        assertNotNull(controller.undoRestorationOffer)

        act(controller) { controller.dismissUndoRestoration() }

        assertNull("Not now must actually close the offer", controller.undoRestorationOffer)
        assertFalse(controller.undoRestorationPending)
        assertEquals("and must leave the balance exactly where it was", 0, controller.undoCreditsRemaining)
        assertNull("declining is not an error and needs no explanation", controller.undoNotice)
        assertEquals("declining must never request an ad", 0, ads.showRewardedCalls)

        // The offer can be raised again afterwards: declining is not a refusal
        // of the player, only of this particular question.
        undo(controller)
        assertNotNull(controller.undoRestorationOffer)
        assertEquals(0, ads.showRewardedCalls)
    }

    @Test
    fun confirmingTheOfferIsTheOnlyPathThatReachesTheSdk() {
        val controller = controller()
        drainCredits(controller)
        undo(controller)
        assertNotNull(controller.undoRestorationOffer)

        // Every other way of touching the controller at zero leaves the count alone.
        act(controller) { controller.onDirectionPressed(Direction.RIGHT) }
        act(controller) { controller.redo() }
        act(controller) { controller.restart() }
        assertEquals(0, ads.showRewardedCalls)
        assertEquals(0, controller.undoCreditsRemaining)

        // Only now — and only because the player said so — does an ad appear.
        ads.outcomes = listOf(RewardedOutcome.REWARDED)
        act(controller) { controller.confirmUndoRestoration() }

        assertEquals("exactly one ad for exactly one confirmation", 1, ads.showRewardedCalls)
        assertEquals("and it paid the full five", 5, controller.undoCreditsRemaining)
    }

    // ===================================================================
    // 3. THE REWARD — 0 → 5, and every way it can fail
    // ===================================================================

    @Test
    fun aConfirmedRewardFillsTheBalanceFromZeroToFive() {
        val controller = controller()
        val adsRequested = earnRestoration(controller)

        assertEquals("one watch, one grant", 1, adsRequested)
        assertEquals(5, controller.undoCreditsRemaining)
        assertEquals("and it is counted against today exactly once", 1, controller.undoRestorationsToday)
        assertEquals("Undo, 5 moves remaining", controller.undoAccessibilityDescription)
        assertNull("a success needs no status line", controller.undoNotice)
        assertFalse(controller.undoRestorationPending)
        assertNull(controller.undoRestorationOffer)
        assertTrue("and the control is a plain Undo again", controller.undoAccess == UndoAccess.AVAILABLE)
    }

    @Test
    fun everyFailureOutcomeLeavesTheBalanceAtZeroAndSaysSo() {
        // SKIPPED — the player closed it early and made a decision.
        // DISMISSED — no ad was ever shown to decide about.
        // UNAVAILABLE — nothing could be shown, which is not the player's doing.
        val cases = mapOf(
            RewardedOutcome.SKIPPED to "wasn't finished",
            RewardedOutcome.DISMISSED to "No ad was shown",
            RewardedOutcome.UNAVAILABLE to "No ad available"
        )

        for ((outcome, expectedWords) in cases) {
            val controller = controller()
            drainCredits(controller)
            undo(controller)
            assertNotNull(controller.undoRestorationOffer)

            ads.outcomes = listOf(outcome)
            act(controller) { controller.confirmUndoRestoration() }

            assertEquals("$outcome must grant nothing", 0, controller.undoCreditsRemaining)
            assertEquals("$outcome must not consume the daily allowance", 0, controller.undoRestorationsToday)
            assertFalse(controller.undoRestorationPending)
            assertNull(controller.undoRestorationOffer)
            val notice = controller.undoNotice
            assertNotNull("$outcome must explain itself rather than fail silently", notice)
            assertTrue(
                "'$notice' should explain the outcome in the words it promises",
                notice!!.contains(expectedWords)
            )
            assertEquals("Undo unavailable, no moves remaining", controller.undoAccessibilityDescription)
        }
    }

    @Test
    fun aDuplicateRewardCallbackGrantsFiveAndNeverTen() {
        val controller = controller()
        drainCredits(controller)
        undo(controller)
        assertNotNull(controller.undoRestorationOffer)

        // The callback arrives twice — the exact defect the exactly-once guard
        // exists to absorb. The gateway is not trusted to deliver once.
        ads.holdDelivery = true
        act(controller) { controller.confirmUndoRestoration() }
        assertTrue("the first delivery leaves the offer in flight", controller.undoRestorationPending)
        assertEquals(1, ads.showRewardedCalls)

        ads.releaseHeld(RewardedOutcome.REWARDED, duplicate = true)

        assertEquals(
            "two callbacks for one watch must pay five, not ten",
            5,
            controller.undoCreditsRemaining
        )
        assertEquals(1, controller.undoRestorationsToday)
        assertFalse(controller.undoRestorationPending)
        assertNull(controller.undoRestorationOffer)
        assertEquals("and no second ad was opened to earn the second five", 1, ads.showRewardedCalls)
    }

    @Test
    fun aSecondTapWhileAnAdIsInFlightOpensNoSecondAdAndDecliningIsRefused() {
        val controller = controller()
        drainCredits(controller)
        undo(controller)
        assertNotNull(controller.undoRestorationOffer)

        ads.holdDelivery = true
        act(controller) { controller.confirmUndoRestoration() }
        assertTrue(controller.undoRestorationPending)

        // A double tap on Watch Ad while the first is still loading.
        act(controller) { controller.confirmUndoRestoration() }
        assertEquals("one in-flight ad, however many taps", 1, ads.showRewardedCalls)

        // And "Not now" cannot abandon an ad that is already running: dropping
        // the pending flag here would make the real reward look like a stale
        // one and be thrown away, leaving the player having watched for nothing.
        act(controller) { controller.dismissUndoRestoration() }
        assertTrue("an in-flight offer cannot be declined out from under it", controller.undoRestorationPending)
        assertNotNull(controller.undoRestorationOffer)

        ads.releaseHeld(RewardedOutcome.REWARDED)
        assertEquals("the reward the player earned still lands", 5, controller.undoCreditsRemaining)
        assertFalse(controller.undoRestorationPending)
        assertNull(controller.undoRestorationOffer)
        assertEquals(1, ads.showRewardedCalls)
    }

    @Test
    fun anOfferRaisedAtZeroStaysAtZeroUntilARewardActuallyPays() {
        val controller = controller()
        drainCredits(controller)

        // Several attempts that all come to nothing.
        for (outcome in listOf(RewardedOutcome.UNAVAILABLE, RewardedOutcome.SKIPPED, RewardedOutcome.DISMISSED)) {
            undo(controller)
            ads.outcomes = listOf(outcome)
            act(controller) { controller.confirmUndoRestoration() }
            assertEquals("a failed attempt must never move the balance", 0, controller.undoCreditsRemaining)
        }
        assertEquals(0, controller.undoRestorationsToday)
        assertEquals("Undo unavailable, no moves remaining", controller.undoAccessibilityDescription)

        // And then one that works — the balance is still earnable.
        undo(controller)
        ads.outcomes = listOf(RewardedOutcome.REWARDED)
        act(controller) { controller.confirmUndoRestoration() }
        assertEquals(5, controller.undoCreditsRemaining)
        assertEquals(1, controller.undoRestorationsToday)
    }

    // ===================================================================
    // 4. THE DAILY ALLOWANCE — three, then refused
    // ===================================================================

    @Test
    fun threeRestorationsAreAllowedOnOneLocalDayAndTheFourthOpensNoOffer() {
        val day = DailyPuzzle.todayEpochDay()
        val controller = controller()

        repeat(UndoGate.DAILY_REWARDED_RESTORATIONS) { attempt ->
            val requested = earnRestoration(controller)
            assertEquals("attempt ${attempt + 1} must cost exactly one ad", 1, requested)
            // The day is checked before the count is read: if midnight passed
            // mid-attempt, the count legitimately started over and this test
            // has nothing honest left to assert.
            assumeSameLocalDay(day)
            assertEquals(attempt + 1, controller.undoRestorationsToday)
        }

        assertEquals(3, controller.undoRestorationsToday)
        assertEquals(5, controller.undoCreditsRemaining)

        // Spend them again and come back empty, as the fourth attempt would.
        drainCredits(controller)
        val adsBefore = ads.showRewardedCalls
        undo(controller)

        assertNull(
            "the fourth offer of the day must never be raised — refusing it here " +
                "is what stops an ad being requested for a restoration that cannot happen",
            controller.undoRestorationOffer
        )
        assertEquals(0, ads.showRewardedCalls - adsBefore)
        assertEquals(0, controller.undoCreditsRemaining)
        assertEquals(3, controller.undoRestorationsToday)
        assertEquals(UndoAccess.EXHAUSTED, controller.undoAccess)
        assertFalse(controller.canRequestUndoRestoration)
        val notice = controller.undoNotice
        assertNotNull("the reason must be stated rather than left to inference", notice)
        assertTrue("'$notice' should say the day's restorations are used up", notice!!.contains("today"))
        assertTrue(
            "and must not promise a reward that would be refused",
            notice.contains("tomorrow")
        )
        assertEquals("Undo unavailable, no moves remaining", controller.undoAccessibilityDescription)
        assumeSameLocalDay(day)
    }

    @Test
    fun onlyConfirmedRewardsCountAgainstTheDailyAllowance() {
        val day = DailyPuzzle.todayEpochDay()
        val controller = controller()

        // Three offers that were opened and then declined.
        repeat(3) {
            drainCredits(controller)
            undo(controller)
            assertNotNull(controller.undoRestorationOffer)
            act(controller) { controller.dismissUndoRestoration() }
            assertEquals(0, ads.showRewardedCalls)
        }
        assertEquals("asking costs nothing, so nothing is counted", 0, controller.undoRestorationsToday)
        assumeSameLocalDay(day)

        // Three that failed instead of being declined.
        repeat(UndoGate.DAILY_REWARDED_RESTORATIONS) {
            drainCredits(controller)
            undo(controller)
            ads.outcomes = listOf(RewardedOutcome.UNAVAILABLE)
            act(controller) { controller.confirmUndoRestoration() }
            assertEquals(0, controller.undoRestorationsToday)
        }
        assumeSameLocalDay(day)

        // And only now does one land — the very first of the day.
        val requested = earnRestoration(controller)
        assertEquals(1, requested)
        assertEquals(1, controller.undoRestorationsToday)
        assumeSameLocalDay(day)
    }

    // ===================================================================
    // 5. SURVIVING THE THINGS A GAME DOES AROUND ITS RULES
    // ===================================================================

    @Test
    fun aBalanceSurvivesBeingThrownAwayAndBuiltAgain() {
        // The shape of a process death: the controller goes, the store does not.
        val store = InMemoryUndoCreditStore()
        val first = controller(store = store)
        step(first)
        undo(first)
        step(first)
        undo(first)
        assertEquals(3, first.undoCreditsRemaining)

        val second = controller(store = store)

        assertEquals(
            "three moves held when the process ended are held when it comes back",
            3,
            second.undoCreditsRemaining
        )
        assertEquals(0, second.undoRestorationsToday)
        assertFalse("the board, unlike the balance, starts over", second.canUndo)
        assertEquals(0, ads.showRewardedCalls)
    }

    @Test
    fun restartingOrChangingLevelNeitherSpendsNorGrantsACredit() {
        val store = InMemoryUndoCreditStore()
        val controller = controller(store = store)

        step(controller)
        undo(controller)
        val before = controller.undoCreditsRemaining
        assertEquals(4, before)

        act(controller) { controller.restart() }
        assertEquals("a restart must not touch the player's balance", before, controller.undoCreditsRemaining)

        act(controller) { controller.loadLevel(room("a-different-room")) }
        assertEquals("a level change must not touch it either", before, controller.undoCreditsRemaining)

        // And a brand-new controller over the same store still sees it — so no
        // level-boundary path has silently written a different number back.
        assertEquals(before, controller(store = store).undoCreditsRemaining)
        assertEquals(0, ads.showRewardedCalls)
        assertEquals(0, ads.preloads)
    }

    @Test
    fun anOfferIsNeverRaisedDuringLevelInitialisationOrAConfigurationChange() {
        val store = InMemoryUndoCreditStore()
        val controller = controller(store = store)

        // Whatever the controller did while it was built, it did not ask.
        assertNull(controller.undoRestorationOffer)
        assertNull(controller.undoNotice)
        assertEquals(5, controller.undoCreditsRemaining)

        // A configuration change is the same code path as any other rebuild:
        // a fresh controller over the same durable store.
        val rebuilt = controller(store = store)
        assertNull(rebuilt.undoRestorationOffer)
        assertNull(rebuilt.undoNotice)
        assertEquals(5, rebuilt.undoCreditsRemaining)
        assertEquals("still nothing asked of the SDK", 0, ads.showRewardedCalls)
    }

    @Test
    fun theStatusLineClearsOnTheNextGenuineAction() {
        val controller = controller()
        drainCredits(controller)
        undo(controller)
        ads.outcomes = listOf(RewardedOutcome.SKIPPED)
        act(controller) { controller.confirmUndoRestoration() }
        assertNotNull("the failure is explained while it is current", controller.undoNotice)

        // A real move ends the previous statement, exactly as it does for the
        // hint notice — an explanation about yesterday's attempt must not
        // linger over unrelated play.
        step(controller)

        assertNull(controller.undoNotice)
        assertEquals(0, controller.undoCreditsRemaining)
        assertEquals("the one ad this test asked for, and no more", 1, ads.showRewardedCalls)
    }

    @Test
    fun withNoAdvertisingConfiguredTheBalanceStillWorksAndNothingBreaks() {
        // The real no-SDK path: no AdMob, no consent, no network. The rules
        // must not depend on an ad existing, and the failure must be a sentence
        // rather than a crash or a dead button.
        val controller = controller(gateway = NoOpAdsGateway)

        assertEquals(5, controller.undoCreditsRemaining)
        step(controller)
        undo(controller)
        assertEquals(4, controller.undoCreditsRemaining)

        drainCredits(controller)
        undo(controller)
        assertNotNull("the offer is still raised — the SDK's absence is not the offer's business", controller.undoRestorationOffer)

        act(controller) { controller.confirmUndoRestoration() }
        assertEquals("the no-op gateway answers once and grants nothing", 0, controller.undoCreditsRemaining)
        assertNull(controller.undoRestorationOffer)
        assertFalse(controller.undoRestorationPending)
        assertNotNull(
            "an unavailable ad is reported, never swallowed",
            controller.undoNotice
        )
        assertEquals(0, controller.undoRestorationsToday)
        assertEquals("and the control still announces its real state", "Undo unavailable, no moves remaining", controller.undoAccessibilityDescription)
    }

    @Test
    fun theRestorationOfferIsRaisedByUndoAloneAndAnsweredByTheTwoButtons() {
        val controller = controller()
        drainCredits(controller)

        // Raised by: a tap on Undo at zero.
        undo(controller)
        val offer = controller.undoRestorationOffer
        assertNotNull(offer)

        // Answered by: confirm (reaches the SDK) or dismiss (does not).
        // Nothing else in the controller may answer it — no move, no restart,
        // no completion, no level change.
        act(controller) { controller.onDirectionPressed(Direction.RIGHT) }
        act(controller) { controller.restart() }
        assertEquals(
            "a move and a restart must not silently answer an open offer",
            0,
            ads.showRewardedCalls
        )

        // Dismissing is the second and last answer.
        act(controller) { controller.dismissUndoRestoration() }
        assertNull(controller.undoRestorationOffer)
        assertEquals(0, ads.showRewardedCalls)
        assertEquals(0, controller.undoCreditsRemaining)
    }
}

/**
 * An [AdsGateway] that does exactly what it is told, and counts.
 *
 * COUNTING IS THE POINT. Every assertion about "no ad was requested" in the
 * suite above is an assertion about this object's two counters, which makes
 * them real claims about the controller rather than hopes about it. The held
 * callback exists because the only way to test the exactly-once guard is to be
 * able to break the contract the gateway is supposed to honour.
 */
private class ScriptedAdsGateway : AdsGateway {

    /** Outcomes the next [showRewarded] delivers, in order. */
    var outcomes: List<RewardedOutcome> = listOf(RewardedOutcome.REWARDED)

    /**
     * When true, the callback is held instead of run, so a test can drive the
     * "the ad is still in flight" window by hand — the only way to observe the
     * pending flag and the exactly-once guard rather than merely their result.
     */
    var holdDelivery = false
    private val held = mutableListOf<(RewardedOutcome) -> Unit>()

    var showRewardedCalls = 0
    var preloads = 0

    override fun isRewardedAvailable(): Boolean = true

    override fun rewardedAvailability(): RewardedAvailability = RewardedAvailability.READY

    override fun preloadRewarded() {
        preloads += 1
    }

    override fun showRewarded(onOutcome: (RewardedOutcome) -> Unit) {
        showRewardedCalls += 1
        if (holdDelivery) {
            held += onOutcome
            return
        }
        val planned = if (outcomes.isEmpty()) listOf(RewardedOutcome.UNAVAILABLE) else outcomes
        planned.forEach(onOutcome)
    }

    /**
     * Runs every held callback with [outcome] — twice for the same callback
     * when [duplicate] is set, which is precisely the misbehaviour the
     * controller's guard is there to absorb.
     */
    fun releaseHeld(outcome: RewardedOutcome, duplicate: Boolean = false) {
        val callbacks = held.toList()
        held.clear()
        callbacks.forEach { callback ->
            callback(outcome)
            if (duplicate) callback(outcome)
        }
    }

    override fun showInterstitial(onClosed: () -> Unit) = onClosed()
}
