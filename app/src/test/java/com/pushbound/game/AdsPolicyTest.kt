package com.pushbound.game

import com.pushbound.game.ads.AdPlacement
import com.pushbound.game.ads.HintAccess
import com.pushbound.game.ads.HintAllowance
import com.pushbound.game.ads.HintGate
import com.pushbound.game.ads.InterstitialPolicy
import com.pushbound.game.ads.InterstitialState
import com.pushbound.game.ads.NoOpAdsGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Monetization rules, verified before any ad SDK exists.
 *
 * These are the guarantees the game must keep once real ads are wired in, so
 * they are pinned as tests now: conservative interstitial frequency, nothing in
 * a disruptive moment, and hints that never gate progression.
 */
class AdsPolicyTest {

    private val never = InterstitialPolicy.NEVER

    private fun state(
        lastShownAtMs: Long = never,
        rewardedShownAtMs: Long = never,
        completionsSinceLast: Int = 0
    ) = InterstitialState(lastShownAtMs, rewardedShownAtMs, completionsSinceLast)

    // ------------------------------------------------- where ads may appear --

    @Test
    fun adsNeverAppearInADisruptiveMoment() {
        // Regardless of timing, state or history: these three placements are
        // refused outright. This is the rule that keeps ads out of gameplay,
        // out of a failed attempt and out of the way of a restart.
        listOf(
            AdPlacement.DURING_GAMEPLAY,
            AdPlacement.AFTER_FAILURE,
            AdPlacement.AFTER_RESTART
        ).forEach { placement ->
            val generousState = state(
                lastShownAtMs = 0L,
                rewardedShownAtMs = 0L,
                completionsSinceLast = 99
            )
            assertFalse(
                "$placement must never show an interstitial",
                InterstitialPolicy.shouldShow(placement, generousState, nowMs = 10_000_000L)
            )
            assertTrue(
                "$placement must be reported as ineligible",
                !InterstitialPolicy.isEligible(placement)
            )
            assertNotNull(InterstitialPolicy.blockingReason(placement, generousState, 10_000_000L))
        }
    }

    @Test
    fun everyDisruptivePlacementIsEnumeratedSoNoCallSiteCanSlipThrough() {
        assertEquals(
            "exactly these three moments are permanently blocked",
            setOf(
                AdPlacement.DURING_GAMEPLAY,
                AdPlacement.AFTER_FAILURE,
                AdPlacement.AFTER_RESTART
            ),
            InterstitialPolicy.ALWAYS_BLOCKED
        )
        assertTrue(InterstitialPolicy.isEligible(AdPlacement.LEVEL_COMPLETE))
        assertTrue(InterstitialPolicy.isEligible(AdPlacement.LEVEL_SELECT))
        assertTrue(InterstitialPolicy.isEligible(AdPlacement.MENU))
    }

    @Test
    fun anInterstitialNeedsHistoryBeforeItCanShow() {
        // First ever run: no completions recorded yet, so nothing may show.
        val fresh = state()
        assertFalse(InterstitialPolicy.shouldShow(AdPlacement.LEVEL_COMPLETE, fresh, nowMs = 0L))
        assertTrue(
            InterstitialPolicy.blockingReason(AdPlacement.LEVEL_COMPLETE, fresh, 0L)!!
                .contains("completion")
        )
    }

    @Test
    fun theMinimumIntervalIsEnforced() {
        val ready = state(completionsSinceLast = 99, lastShownAtMs = 1_000_000L)
        val interval = InterstitialPolicy.DEFAULT_MIN_INTERVAL_MS

        // Just short of the floor.
        assertFalse(
            InterstitialPolicy.shouldShow(AdPlacement.LEVEL_COMPLETE, ready, 1_000_000L + interval - 1)
        )
        // Exactly at the floor and beyond.
        assertTrue(
            InterstitialPolicy.shouldShow(AdPlacement.LEVEL_COMPLETE, ready, 1_000_000L + interval)
        )
        assertTrue(
            InterstitialPolicy.shouldShow(AdPlacement.LEVEL_COMPLETE, ready, 1_000_000L + interval * 4)
        )
    }

    @Test
    fun aRewardedAdStartsAMandatoryQuietPeriod() {
        // Even with every other condition satisfied, nothing may follow a
        // rewarded ad until the gap has passed — an ad after an ad the player
        // chose to watch is exactly the pattern that makes rewards feel like a trap.
        val rewardedJustNow = state(
            rewardedShownAtMs = 5_000_000L,
            completionsSinceLast = 99,
            lastShownAtMs = 0L
        )
        val gap = InterstitialPolicy.MIN_GAP_AFTER_REWARDED_MS

        assertFalse(
            InterstitialPolicy.shouldShow(AdPlacement.MENU, rewardedJustNow, 5_000_000L + gap - 1)
        )
        assertTrue(
            InterstitialPolicy.blockingReason(AdPlacement.MENU, rewardedJustNow, 5_000_000L)!!
                .contains("rewarded")
        )
        assertTrue(
            InterstitialPolicy.shouldShow(AdPlacement.MENU, rewardedJustNow, 5_000_000L + gap)
        )
    }

    @Test
    fun completingALevelAloneIsNotEnoughToShowAnAd() {
        // "Not after every completion" — one finish moves the counter but does
        // not reach the threshold.
        var policyState = state()
        policyState = InterstitialPolicy.onLevelCompleted(policyState)

        assertEquals(1, policyState.completionsSinceLast)
        assertFalse(
            "a single completion must not trigger an ad",
            InterstitialPolicy.shouldShow(AdPlacement.LEVEL_COMPLETE, policyState, Long.MAX_VALUE / 8)
        )

        policyState = InterstitialPolicy.onLevelCompleted(policyState)
        assertTrue(
            InterstitialPolicy.shouldShow(AdPlacement.LEVEL_COMPLETE, policyState, Long.MAX_VALUE / 8)
        )
    }

    @Test
    fun showingAnInterstitialResetsItsOwnThrottle() {
        var policyState = state(completionsSinceLast = 5, lastShownAtMs = 0L)
        val now = 50_000_000L

        assertTrue(InterstitialPolicy.shouldShow(AdPlacement.MENU, policyState, now))

        policyState = InterstitialPolicy.onInterstitialShown(policyState, now)

        assertEquals(0, policyState.completionsSinceLast)
        assertEquals(now, policyState.lastShownAtMs)
        assertFalse(
            "the counter must restart the moment an ad is shown",
            InterstitialPolicy.shouldShow(AdPlacement.MENU, policyState, now + 1)
        )
    }

    @Test
    fun historyBeforeTheSentinelNeverLeaksThrough() {
        // `NEVER` means never: no interval check fires, no wrap-around artefact.
        val withNeverHistory = state(
            lastShownAtMs = never,
            rewardedShownAtMs = never,
            completionsSinceLast = 99
        )
        assertTrue(
            InterstitialPolicy.shouldShow(AdPlacement.MENU, withNeverHistory, nowMs = 0L)
        )
        assertNull(InterstitialPolicy.blockingReason(AdPlacement.MENU, withNeverHistory, 0L))
    }

    @Test
    fun theThreeRulesComposeRatherThanOverrideEachOther() {
        // All conditions violated at once: the FIRST applicable rule is the one
        // reported, so diagnostics point at a single cause instead of a list.
        val bad = state(
            lastShownAtMs = 100L,
            rewardedShownAtMs = 100L,
            completionsSinceLast = 0
        )
        val reason = InterstitialPolicy.blockingReason(AdPlacement.MENU, bad, 200L)
        assertNotNull(reason)
        assertTrue(
            "the rewarded-ad gap is checked first: $reason",
            reason!!.contains("rewarded")
        )
    }

    // ------------------------------------------------------------ hint access --

    @Test
    fun hintsAreFreeForTheFirstAllowance() {
        (0 until HintGate.FREE_HINTS_PER_LEVEL).forEach { used ->
            val state = HintAllowance(freeUsed = used)
            assertEquals(
                "hint ${used + 1} must be free",
                HintAccess.GRANTED_FREE,
                HintGate.access(state, rewardedAvailable = false)
            )
            // Also free when an ad IS available — the free path is always preferred.
            assertEquals(
                HintAccess.GRANTED_FREE,
                HintGate.access(state, rewardedAvailable = true)
            )
        }
    }

    @Test
    fun pastTheAllowanceAnAdIsOfferedWhenOneExists() {
        val spent = HintAllowance(freeUsed = HintGate.FREE_HINTS_PER_LEVEL)

        assertEquals(
            HintAccess.OFFER_REWARDED_AD,
            HintGate.access(spent, rewardedAvailable = true)
        )
    }

    @Test
    fun pastTheAllowanceWithNoAdTheHintIsSimplyWithheld() {
        val spent = HintAllowance(freeUsed = HintGate.FREE_HINTS_PER_LEVEL)

        assertEquals(
            HintAccess.DENIED,
            HintGate.access(spent, rewardedAvailable = false)
        )
    }

    @Test
    fun aRewardGrantsExactlyOneExtraHint() {
        var allowance = HintGate.reset()
        repeat(HintGate.FREE_HINTS_PER_LEVEL) { allowance = HintGate.onFreeHintUsed(allowance) }
        assertEquals(HintGate.FREE_HINTS_PER_LEVEL, allowance.freeUsed)
        assertEquals(0, allowance.adGranted)

        allowance = HintGate.onRewardEarned(allowance)

        assertEquals("one reward grants exactly one hint", 1, allowance.adGranted)
        assertEquals(
            "free plus the rewarded hint",
            HintGate.FREE_HINTS_PER_LEVEL + 1,
            allowance.totalGranted
        )
        assertEquals(
            "a reward does not hand back free hints",
            0,
            allowance.remaining
        )
    }

    @Test
    fun everyHintCountedMakesTheNextRequestHarder() {
        var allowance = HintGate.reset()
        assertEquals(HintGate.FREE_HINTS_PER_LEVEL, allowance.remaining)

        repeat(HintGate.FREE_HINTS_PER_LEVEL) {
            assertEquals(HintAccess.GRANTED_FREE, HintGate.access(allowance, false))
            allowance = HintGate.onFreeHintUsed(allowance)
            assertEquals(HintGate.FREE_HINTS_PER_LEVEL - it - 1, allowance.remaining)
        }

        assertEquals(HintAccess.DENIED, HintGate.access(allowance, false))
        assertEquals(0, allowance.remaining)
    }

    @Test
    fun restartingAnAttemptRestoresTheFreeAllowance() {
        var allowance = HintGate.reset()
        repeat(HintGate.FREE_HINTS_PER_LEVEL) { allowance = HintGate.onFreeHintUsed(allowance) }
        assertEquals(HintAccess.DENIED, HintGate.access(allowance, rewardedAvailable = false))

        allowance = HintGate.reset()

        assertEquals(0, allowance.freeUsed)
        assertEquals(0, allowance.adGranted)
        assertEquals(HintAccess.GRANTED_FREE, HintGate.access(allowance, rewardedAvailable = false))
    }

    @Test
    fun hintAccessNeverTouchesProgression() {
        // The whole point: a player who has spent every hint they will ever get
        // still has the identical game — same unlocking, same medals, same rules.
        // Verified structurally here by the gate's shape (it returns an access
        // decision and nothing else) plus the campaign tests that pin
        // progression independently.
        val denied = HintGate.access(HintAllowance(freeUsed = Int.MAX_VALUE), rewardedAvailable = false)
        assertEquals(HintAccess.DENIED, denied)
        // Denial is a value like any other — it never throws, so the UI can bind
        // it without a guard and gameplay continues.
        assertTrue(denied.name.isNotBlank())
    }

    // ------------------------------------------------------- the default SDK --

    @Test
    fun withNoAdSdkConfiguredNothingIsOfferedAndFlowsStillComplete() {
        assertFalse(NoOpAdsGateway.isRewardedAvailable())

        // The reward path must NOT fire when there was nothing to watch...
        var rewarded = false
        NoOpAdsGateway.showRewarded(onReward = { rewarded = true }, onSkipped = { rewarded = false })
        assertFalse("no ad means no reward", rewarded)

        // ...but the completion callback must still run, or the caller hangs.
        var closed = false
        NoOpAdsGateway.showInterstitial(onClosed = { closed = true })
        assertTrue("the flow must never stall waiting on an absent ad", closed)
    }

    private fun assertNotNull(value: Any?) = assertTrue("expected a reason", value != null)
}
