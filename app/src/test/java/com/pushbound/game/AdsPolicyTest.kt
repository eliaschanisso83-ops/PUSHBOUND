package com.pushbound.game

import com.pushbound.game.ads.AdsGateway
import com.pushbound.game.ads.AdPlacement
import com.pushbound.game.ads.HintAccess
import com.pushbound.game.ads.HintAllowance
import com.pushbound.game.ads.HintGate
import com.pushbound.game.ads.InterstitialPolicy
import com.pushbound.game.ads.InterstitialState
import com.pushbound.game.ads.NoOpAdsGateway
import com.pushbound.game.ads.RewardedAvailability
import com.pushbound.game.ads.RewardedOutcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // ------------------------------------------------ the consent contract --

    /**
     * PAST THE FREE ALLOWANCE NOTHING IS EVER GRANTED WITHOUT A DECISION.
     *
     * [HintGate.access] is what decides whether the game may even SHOW an offer,
     * so if it ever returned GRANTED_FREE for an exhausted allowance, a tap on
     * Hint would go straight past consent and hand out free hints forever.
     * Exhausting the allowance must always land on one of the two ad offers or
     * on DENIED — never on GRANTED_FREE.
     */
    @Test
    fun anExhaustedAllowanceNeverGrantsAFreeHint() {
        val exhausted = HintAllowance(freeUsed = HintGate.FREE_HINTS_PER_LEVEL)

        for (availability in RewardedAvailability.entries) {
            val access = HintGate.access(exhausted, availability)
            assertNotEquals(
                "an exhausted allowance must never be free, at $availability",
                HintAccess.GRANTED_FREE,
                access
            )
        }
    }

    /**
     * AN AD IS NEVER OPENED BY ASKING FOR A HINT — only by confirming.
     *
     * [HintAccess.OFFER_REWARDED_AD] and [OFFER_REWARDED_AD_FETCH] are OFFERS,
     * not grants. Both must be classified as offered so the UI presents a
     * confirmation with a working Cancel, and neither may ever be mistaken for
     * a granted hint.
     */
    @Test
    fun bothAdStatesAreOffersAndNeitherIsAGrant() {
        val exhausted = HintAllowance(freeUsed = HintGate.FREE_HINTS_PER_LEVEL)

        val ready = HintGate.access(exhausted, RewardedAvailability.READY)
        val loading = HintGate.access(exhausted, RewardedAvailability.LOADING)

        assertEquals(HintAccess.OFFER_REWARDED_AD, ready)
        assertEquals(HintAccess.OFFER_REWARDED_AD_FETCH, loading)
        assertTrue(HintGate.isOfferedRewarded(ready))
        assertTrue(HintGate.isOfferedRewarded(loading))
        assertFalse(HintGate.isOfferedRewarded(HintAccess.GRANTED_FREE))
        assertFalse(HintGate.isOfferedRewarded(HintAccess.DENIED))
    }

    /**
     * "An ad will be attempted" is a different promise from "an ad is ready",
     * and the confirmation copy has to say which. Collapsing LOADING into READY
     * would let the UI promise an ad it may never manage to fetch.
     */
    @Test
    fun aLoadedAdAndAStillFetchingAdAreOfferedDifferently() {
        val exhausted = HintAllowance(freeUsed = HintGate.FREE_HINTS_PER_LEVEL)

        val ready = HintGate.access(exhausted, RewardedAvailability.READY)
        val fetching = HintGate.access(exhausted, RewardedAvailability.LOADING)

        assertNotEquals(
            "the player must be told which of the two they are agreeing to",
            ready,
            fetching
        )
    }

    /** An ad that cannot exist at all is never an offer. */
    @Test
    fun anUnavailableAdIsNeverOffered() {
        val exhausted = HintAllowance(freeUsed = HintGate.FREE_HINTS_PER_LEVEL)

        val denied = HintGate.access(exhausted, RewardedAvailability.UNAVAILABLE)
        assertEquals(HintAccess.DENIED, denied)
        assertFalse(HintGate.isOfferedRewarded(denied))
    }

    /**
     * WATCHING AN AD MUST NOT EAT THE FREE ALLOWANCE.
     *
     * The two allowances are counted in separate columns on purpose. If an ad
     * hint were charged to the free column, a player who chose to watch an ad
     * would come out WORSE than one who did not — their remaining free hints
     * would have been silently spent on their behalf.
     */
    @Test
    fun anAdBackedHintLeavesTheFreeAllowanceIntact() {
        val almost = HintAllowance(freeUsed = HintGate.FREE_HINTS_PER_LEVEL - 1)

        // They spend the last free hint...
        val spent = HintGate.onFreeHintUsed(almost)
        assertEquals(HintGate.FREE_HINTS_PER_LEVEL, spent.freeUsed)
        assertEquals(HintAccess.OFFER_REWARDED_AD, HintGate.access(spent, RewardedAvailability.READY))

        // ...then choose to watch an ad for one more.
        val afterAd = HintGate.onRewardEarned(spent)

        assertEquals(
            "the free allowance must be untouched by an ad-backed hint",
            HintGate.FREE_HINTS_PER_LEVEL,
            afterAd.freeUsed
        )
        assertEquals("the ad hint is counted in its own column", 1, afterAd.adGranted)
        assertEquals(
            "an ad hint must not be reported as a remaining FREE hint",
            0,
            afterAd.remaining
        )
        // totalGranted is the SUM of both columns: the 3 free hints they had
        // already taken, plus the 1 the ad bought. Stating it as a sum is the
        // point — neither column may be silently folded into the other.
        assertEquals(
            "both allowances add up without either being overwritten",
            afterAd.freeUsed + afterAd.adGranted,
            afterAd.totalGranted
        )
        assertEquals(HintGate.FREE_HINTS_PER_LEVEL + 1, afterAd.totalGranted)
    }

    /** The reverse must hold too: a free hint never inflates the ad column. */
    @Test
    fun aFreeHintNeverTouchesTheAdColumn() {
        val state = HintGate.onFreeHintUsed(HintAllowance())
        assertEquals(0, state.adGranted)
        assertEquals(1, state.freeUsed)
    }

    /**
     * THE PLAYER CAN ALWAYS DECLINE. Offering a rewarded ad must never have a
     * cost attached: turning it down leaves the allowance exactly as it was, so
     * a player who never watches an ad is never worse off than one who does.
     */
    @Test
    fun decliningAnOfferCostsThePlayerNothing() {
        val exhausted = HintAllowance(freeUsed = HintGate.FREE_HINTS_PER_LEVEL)

        // DECLINING IS MODELLED BY DOING NOTHING — and that is the point. There
        // is no "declined" transition to apply, no counter to decrement, no
        // latch to set. The offer itself is pure: raising it cannot change the
        // allowance, so a player who never watches an ad is never worse off
        // than one who does.
        val unchanged = HintGate.access(exhausted, RewardedAvailability.READY)
        assertEquals(HintGate.FREE_HINTS_PER_LEVEL, exhausted.freeUsed)
        assertEquals(0, exhausted.adGranted)
        assertEquals(0, exhausted.remaining)

        // Asking again after declining behaves identically — the offer was
        // never consumed.
        assertEquals(
            unchanged,
            HintGate.access(exhausted, RewardedAvailability.READY)
        )
    }

    /**
     * A PLAYER WHO DECLINES CAN ASK AGAIN. The gate re-derives from current
     * state, so there is no one-shot latch that punishes saying no.
     */
    @Test
    fun decliningDoesNotLatchShutTheOffer() {
        val exhausted = HintAllowance(freeUsed = HintGate.FREE_HINTS_PER_LEVEL)
        val ready = RewardedAvailability.READY

        // Ask, decline, ask again — the offer is still there.
        repeat(3) {
            assertEquals(HintAccess.OFFER_REWARDED_AD, HintGate.access(exhausted, ready))
        }
    }

    /**
     * THE REWARD IS EARNED, NOT PURCHASED. Watched through means granted, which
     * is the only state in which Google fires the reward callback.
     */
    @Test
    fun watchingAnAdThroughGrantsExactlyOneHint() {
        val state = HintGate.onRewardEarned(HintAllowance())
        assertEquals(1, state.adGranted)
        assertEquals(0, state.freeUsed)
        assertEquals(1, state.totalGranted)
    }

    /**
     * An allowance is only ever a number of hints. It never reaches into
     * unlocking, medals or completion — those are Progress's business and are
     * pinned separately in the campaign tests.
     */
    @Test
    fun theAllowanceNeverOutlivesTheAttemptItBelongsTo() {
        // A restart begins a fresh attempt, so the whole allowance — free AND
        // ad-backed — starts over. Assistance is never a permanent resource.
        val used = HintGate.onRewardEarned(
            HintGate.onFreeHintUsed(HintGate.onFreeHintUsed(HintGate.reset()))
        )
        assertEquals(2, used.freeUsed)
        assertEquals(1, used.adGranted)

        val fresh = HintGate.reset()
        assertEquals(HintAllowance(), fresh)
        assertEquals(HintGate.FREE_HINTS_PER_LEVEL, fresh.remaining)
    }

    // ------------------------------------------------------- the default SDK --

    @Test
    fun withNoAdSdkConfiguredNothingIsOfferedAndFlowsStillComplete() {
        assertFalse(NoOpAdsGateway.isRewardedAvailable())
        assertEquals(RewardedAvailability.UNAVAILABLE, NoOpAdsGateway.rewardedAvailability())

        // THE CENTRAL MONETIZATION INVARIANT: the outcome must be delivered
        // exactly once, and it must NOT be a reward when there was nothing to
        // watch. UNAVAILABLE rather than SKIPPED, because the player made no
        // decision — nothing was ever on screen to close.
        var outcome: RewardedOutcome? = null
        NoOpAdsGateway.showRewarded { outcome = it }
        assertEquals(
            "no ad means no reward",
            RewardedOutcome.UNAVAILABLE,
            outcome
        )

        // ...but the completion callback must still run, or the caller hangs.
        var closed = false
        NoOpAdsGateway.showInterstitial(onClosed = { closed = true })
        assertTrue("the flow must never stall waiting on an absent ad", closed)
    }

    /**
     * Every outcome is delivered EXACTLY ONCE and the reward is reserved for
     * one of them. A gateway that fired twice would double-grant a hint; one
     * that fired zero times would leave the confirmation spinner up forever.
     *
     * Driven through a recording gateway so the contract is pinned against the
     * shape the AdMob implementation has to satisfy, independent of any SDK.
     */
    @Test
    fun everyRewardedOutcomeIsDeliveredExactlyOnce() {
        for (outcome in RewardedOutcome.entries) {
            var deliveries = 0
            val seen = mutableListOf<RewardedOutcome>()

            RecordingAdsGateway(outcome).showRewarded {
                deliveries++
                seen += it
            }

            assertEquals("exactly one outcome for $outcome", 1, deliveries)
            assertEquals("the outcome must not be altered in flight", listOf(outcome), seen)
        }
    }

    @Test
    fun onlyRewardedGrantsAHintAndNothingElseDoes() {
        // The reward is granted for REWARDED and for no other outcome. This is
        // Google's own reward contract, restated here so a future gateway cannot
        // quietly widen it — a skipped or dismissed ad must cost the player
        // nothing and grant them nothing.
        val granting = RewardedOutcome.REWARDED
        val nonGranting = RewardedOutcome.entries.filter { it != granting }

        assertTrue(nonGranting.isNotEmpty())
        for (outcome in nonGranting) {
            var hintAllowed = false
            RecordingAdsGateway(outcome).showRewarded { hintAllowed = it == granting }
            assertFalse("only REWARDED may grant, not $outcome", hintAllowed)
        }
    }

    // ------------------------------------------- shipped ad configuration --

    // The identifiers below are duplicated from app/build.gradle.kts ON
    // PURPOSE. They are publishable values compiled into every APK, not
    // secrets, and restating them here means a silent edit to either file fails
    // this suite instead of quietly re-pointing the product at the wrong
    // inventory.

    @Test
    fun developmentBuildsNeverPointAtProductionAdInventory() {
        val gradle = repoFile("app/build.gradle.kts").readText()

        // 1. The identifiers resolve to the right literals, wherever they are
        //    declared. This is what pins the actual values.
        assertTrue(
            "the production rewarded unit constant must be Pushbound_Rewarded_Hint",
            gradle.contains("val ADMOB_PRODUCTION_REWARDED_UNIT = \"$PRODUCTION_REWARDED_UNIT\"")
        )
        assertTrue(
            "the production app id constant must be Pushbound's AdMob app id",
            gradle.contains("val ADMOB_PRODUCTION_APP_ID = \"$PRODUCTION_APP_ID\"")
        )
        assertTrue(
            "the demo rewarded unit constant must be Google's demo unit",
            gradle.contains("val ADMOB_DEMO_REWARDED_UNIT = \"$DEMO_REWARDED_UNIT\"")
        )

        // 2. Each build type must REFERENCE the right constant. The literals
        //    live in the declarations above, so the binding — not the string —
        //    is what decides which inventory a build ships with.
        val releaseBlock = blockAfter(gradle, "release")
        val defaultBlock = blockAfter(gradle, "defaultConfig")

        assertTrue(
            "release must bind the production rewarded unit",
            releaseBlock.contains("ADMOB_PRODUCTION_REWARDED_UNIT")
        )
        assertTrue(
            "release must bind the production app id",
            releaseBlock.contains("ADMOB_PRODUCTION_APP_ID")
        )
        assertFalse(
            "release must not fall back to demo inventory",
            releaseBlock.contains("ADMOB_DEMO_")
        )

        // defaultConfig is what debug inherits, so it is the value that
        // development and QA actually ship with.
        assertTrue(
            "defaultConfig must default to Google's demo rewarded unit",
            defaultBlock.contains("ADMOB_DEMO_REWARDED_UNIT")
        )
        assertTrue(
            "defaultConfig must default to Google's demo app id",
            defaultBlock.contains("ADMOB_DEMO_APP_ID")
        )
        assertFalse(
            "defaultConfig must never bind production inventory",
            defaultBlock.contains("ADMOB_PRODUCTION_")
        )

        // 3. Each build type binds by NAME, never by writing the identifier
        //    inline — so the declaration above is the single place a value can
        //    be changed, and changing it cannot be masked by a stray literal
        //    sitting in a build type. (A comment mentioning the identifier is
        //    harmless and is not what this checks.)
        assertFalse(
            "release must reference the production unit by constant, not inline",
            releaseBlock.contains(PRODUCTION_REWARDED_UNIT)
        )
        assertFalse(
            "defaultConfig must reference demo inventory by constant, not inline",
            defaultBlock.contains(DEMO_REWARDED_UNIT)
        )
    }

    @Test
    fun theOnlyAdUnitsInTheProductAreTheRewardedPair() {
        // "Do not create another ad unit" is a product decision, and the only
        // way to keep it true over time is to check every place an identifier
        // could be written down: source, Gradle, the manifest, the docs and CI.
        //
        // App IDs use `~` rather than `/`, so this pattern matches ad UNITS only
        // and cannot be satisfied by the app id alone.
        val found = repoTextFiles()
            .flatMap { file ->
                UNIT_PATTERN.findAll(file.readText()).map { it.value }.toList()
            }
            .distinct()
            .sorted()
            .toList()

        assertEquals(
            "the product must contain exactly the demo and production REWARDED " +
                "units — any third value means another ad unit was created: $found",
            listOf(DEMO_REWARDED_UNIT, PRODUCTION_REWARDED_UNIT).sorted(),
            found
        )
    }

    @Test
    fun noOtherAdFormatIsImplementedAnywhere() {
        // Rewarded is the only format Pushbound implements. The seam for an
        // interstitial exists as policy logic with no unit behind it, so what
        // must not appear is any SDK call that could actually SHOW another
        // format.
        val banned = listOf(
            "com.google.android.gms.ads.interstitial.InterstitialAd",
            "com.google.android.gms.ads.appopen.AppOpenAd",
            "com.google.android.gms.ads.AdView",
            "com.google.android.gms.ads.nativead.NativeAd",
            "com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd"
        )

        val offenders = implementationTextFiles()
            .flatMap { file ->
                val text = file.readText()
                banned.filter { token -> text.contains(token) }
                    .map { token -> "${file.path} -> $token" }
            }

        assertTrue(
            "only the Rewarded format may be integrated, found: $offenders",
            offenders.isEmpty()
        )
    }

    /** Reads a file relative to the repository root, which is where tests run. */
    private fun repoFile(path: String): File =
        File(path).takeIf { it.isFile }
            ?: error("$path not found from ${File(".").absolutePath} — run from the repository root")

    /**
     * The files that could actually INTEGRATE an ad format: shipped source,
     * resources, manifest and Gradle.
     *
     * Deliberately excludes `app/src/test`, because this test's own list of
     * banned class names lives there — scanning it would make the assertion
     * fail on its own description of what it forbids.
     */
    private fun implementationTextFiles(): List<File> {
        val extensions = setOf("kt", "xml", "kts")
        val roots = listOf(
            File("app/src/main"),
            File("src/main"),
            File("app/build.gradle.kts")
        )
        return roots
            .filter { it.exists() }
            .flatMap { root ->
                if (root.isFile) listOf(root)
                else root.walkTopDown().filter { it.isFile && it.extension in extensions }.toList()
            }
            .toList()
    }

    /**
     * Every text file that could carry an ad identifier, excluding vendored
     * tooling and build output (which merely copies what is already asserted
     * above).
     */
    private fun repoTextFiles(): List<File> {
        val extensions = setOf("kt", "kts", "xml", "md", "yml", "yaml", "properties", "pro")
        return File(".").walkTopDown()
            .onEnter { it.name !in setOf(".git", "tooling", ".gradle", "build") }
            .filter { it.isFile && it.extension in extensions }
            .toList()
    }

    /**
     * The body of the first `marker { … }` block, brace-matched.
     *
     * Written as a matcher rather than a substring split so that the assertion
     * is about the BUILD TYPE and not about wherever the identifier happens to
     * appear in the file.
     */
    private fun blockAfter(text: String, marker: String): String {
        val start = text.indexOf("$marker {")
        assertTrue("expected a `$marker {` block in build.gradle.kts", start >= 0)
        var depth = 0
        for (i in text.indexOf('{', start) until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        error("unterminated `$marker` block in build.gradle.kts")
    }

    /** A gateway that reports one canned outcome, to pin the contract. */
    private class RecordingAdsGateway(private val outcome: RewardedOutcome) : AdsGateway {
        override fun isRewardedAvailable(): Boolean = true
        override fun rewardedAvailability(): RewardedAvailability = RewardedAvailability.READY
        override fun showRewarded(onOutcome: (RewardedOutcome) -> Unit) = onOutcome(outcome)
        override fun showInterstitial(onClosed: () -> Unit) = onClosed()
    }

    private fun assertNotNull(value: Any?) = assertTrue("expected a reason", value != null)

    private companion object {
        /** Google's demo App ID, used by every build except release. */
        const val DEMO_APP_ID = "ca-app-pub-3940256099942544~3347511713"

        /** Google's demo REWARD unit, used by every build except release. */
        const val DEMO_REWARDED_UNIT = "ca-app-pub-3940256099942544/5224354917"

        /** Pushbound's production AdMob App ID. */
        const val PRODUCTION_APP_ID = "ca-app-pub-5147549053838908~5350410819"

        /**
         * Pushbound_Rewarded_Hint — the ONE production ad unit, and the only
         * rewarded unit in the product.
         */
        const val PRODUCTION_REWARDED_UNIT = "ca-app-pub-5147549053838908/7178497807"

        /** Matches ad UNITS only: app ids separate their halves with `~`. */
        val UNIT_PATTERN = Regex("ca-app-pub-[0-9]+/[0-9]+")
    }
}
