package com.pushbound.game

import com.pushbound.game.billing.BillingGateway
import com.pushbound.game.billing.NoOpBillingGateway
import com.pushbound.game.billing.RemoveAdsBilling
import com.pushbound.game.billing.RemoveAdsProduct
import com.pushbound.game.billing.RemoveAdsPurchaseOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Billing as Pushbound actually ships it: PREPARED, INACTIVE, and unable to
 * sell anything.
 *
 * THE CLAIM THIS FILE EXISTS TO KEEP HONEST — the app contains a complete,
 * current Google Play Billing client, a gateway with the same shape as the
 * advertising one, and a single constant where a product id would go. None of
 * that is a product. There is no id, no buy button, no entitlement and no
 * path from any screen to Play, and every one of those four absences is
 * asserted below rather than described.
 *
 * WHY THE ABSENCE IS TESTED AS HARD AS BEHAVIOUR — "prepared but inactive" is
 * the kind of claim that quietly stops being true the moment somebody adds a
 * convenient button. A test that only checks the seam still exists would pass
 * after that change; these check that nothing has been wired to it.
 */
class RemoveAdsBillingTest {

    private fun source(relative: String): String {
        val file = File("app/src/main/java/com/pushbound/game/$relative")
        assertTrue(
            "$relative not found from ${File(".").absolutePath} — run from the repository root",
            file.isFile
        )
        return file.readText()
    }

    /** Every main-source file that names [symbol], as paths relative to the game package. */
    private fun filesNaming(symbol: String): List<String> {
        val root = File("app/src/main/java/com/pushbound/game")
        assertTrue("sources not found from ${File(".").absolutePath}", root.isDirectory)
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains(symbol) }
            .map { it.relativeTo(root).path }
            .sorted()
            .toList()
    }

    // ===================================================================
    // 1. THERE IS NO PRODUCT
    // ===================================================================

    @Test
    fun theRemoveAdsProductIdIsDeliberatelyUnconfigured() {
        assertEquals(
            "the seam must be empty until a human creates the product in Play Console",
            "",
            RemoveAdsProduct.REMOVE_ADS_PRODUCT_ID
        )
        assertFalse(
            "isConfigured is the one flag any future buy affordance must gate on",
            RemoveAdsProduct.isConfigured
        )
    }

    @Test
    fun noProductIdHasBeenInventedAnywhereInTheShippedSource() {
        // A plausible-looking id would be worse than none: the billing client
        // would happily send it to Play and every reader of the source would
        // believe a product exists. Scanning for quoted literals that LOOK like
        // one is how that accident is caught before it ships.
        val root = File("app/src/main/java/com/pushbound/game")
        val quotedProductIds = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("\"[^\"]*\"").findAll(file.readText())
                    .map { it.value }
                    .filter { literal ->
                        val inner = literal.trim('"').lowercase()
                        inner.contains("ads") &&
                            (inner.contains("remove") || inner.contains("strip") ||
                                inner.contains("no_ads") || inner.contains("ad_free")) &&
                            inner.all { it.isLetterOrDigit() || it == '_' || it == '.' }
                    }
                    .map { "${file.name}: $it" }
                    .toList()
            }
            .toList()

        assertTrue(
            "no shipped string may read as a Remove Ads product id: $quotedProductIds",
            quotedProductIds.isEmpty()
        )

        // And the constant itself is declared exactly once, in the one file
        // that documents why it is empty — while the seam that READS it may
        // mention it, since that is what keeps a future id to one line.
        val homes = filesNaming("REMOVE_ADS_PRODUCT_ID")
        assertTrue(
            "the product id must live only inside the billing seam, plus the shell " +
                "comment that explains why it is empty: $homes",
            homes.all { it.startsWith("billing/") || it == "MainActivity.kt" }
        )
        assertTrue(
            "and its home must still declare it empty",
            source("billing/RemoveAds.kt").contains("REMOVE_ADS_PRODUCT_ID: String = \"\"")
        )
    }

    // ===================================================================
    // 2. THE TWO REFUSALS THAT NEED NO SERVICE
    // ===================================================================

    @Test
    fun anAttemptWithoutAProductIsRefusedBeforeAnythingIsContacted() {
        val unconfigured = RemoveAdsProduct.isConfigured

        assertEquals(
            "nothing configured means nothing attempted — this is the ONLY " +
                "outcome Pushbound can produce today",
            RemoveAdsPurchaseOutcome.NOT_CONFIGURED,
            RemoveAdsBilling.refusePurchase(configured = unconfigured, owned = false)
        )
        assertNull("a configured, unowned product is the only attempt allowed to proceed", RemoveAdsBilling.refusePurchase(configured = true, owned = false))
    }

    @Test
    fun configurationIsCheckedBeforeOwnership() {
        // Asking Play about a product that does not exist because the player
        // "already owns it" would be a lie in a direction that still costs a
        // service connection and still reports a reason nobody can act on.
        assertEquals(
            RemoveAdsPurchaseOutcome.NOT_CONFIGURED,
            RemoveAdsBilling.refusePurchase(configured = false, owned = true)
        )
        assertEquals(
            "and once configured, an existing entitlement is the next refusal",
            RemoveAdsPurchaseOutcome.ALREADY_OWNED,
            RemoveAdsBilling.refusePurchase(configured = true, owned = true)
        )
    }

    // ===================================================================
    // 3. THE DEFAULT GATEWAY — answers always, grants never
    // ===================================================================

    @Test
    fun theNoOpGatewayInitialisesWithNoProductAndNoService() {
        val gateway: BillingGateway = NoOpBillingGateway

        assertFalse(gateway.isRemoveAdsConfigured)
        assertFalse(gateway.isRemoveAdsOwned)
        assertFalse(
            "an unconfigured build must never present itself as owning the product",
            RemoveAdsProduct.isConfigured && gateway.isRemoveAdsOwned
        )
    }

    @Test
    fun theNoOpGatewayAlwaysAnswersExactlyOnceAndNeverHangs() {
        val outcomes = mutableListOf<RemoveAdsPurchaseOutcome>()
        NoOpBillingGateway.purchaseRemoveAds { outcomes += it }
        assertEquals("a caller must never be left waiting", 1, outcomes.size)
        assertEquals(RemoveAdsPurchaseOutcome.NOT_CONFIGURED, outcomes.single())

        val answers = mutableListOf<Boolean>()
        NoOpBillingGateway.refreshRemoveAdsOwnership { answers += it }
        assertEquals("the refresh must always report, even with nothing to ask", 1, answers.size)
        assertFalse(answers.single())
    }

    @Test
    fun closingTheBillingSeamIsSafeWhenItWasNeverOpened() {
        // Safe at any time, including before anything was ever connected —
        // this is the path a configuration change and a normal exit both take.
        NoOpBillingGateway.close()
        NoOpBillingGateway.close()
        assertNotNull(NoOpBillingGateway)
    }

    @Test
    fun aBillingGatewayThatCannotReachPlayStillAnswersAndStillOwnsNothing() {
        // The failure mode, not the happy path: billing unavailable, offline,
        // or the service never connecting. It must behave like billing that
        // refused to start rather than throwing — because gameplay is handed
        // no billing object at all, and must therefore be unaffected either way.
        val unreachable = object : BillingGateway {
            override val isRemoveAdsConfigured: Boolean = RemoveAdsProduct.isConfigured
            override val isRemoveAdsOwned: Boolean = false
            override fun refreshRemoveAdsOwnership(onOwned: (Boolean) -> Unit) = onOwned(false)
            override fun purchaseRemoveAds(onOutcome: (RemoveAdsPurchaseOutcome) -> Unit) =
                onOutcome(RemoveAdsPurchaseOutcome.UNAVAILABLE)
            override fun close() = Unit
        }

        val outcomes = mutableListOf<RemoveAdsPurchaseOutcome>()
        unreachable.purchaseRemoveAds { outcomes += it }
        assertEquals(listOf(RemoveAdsPurchaseOutcome.UNAVAILABLE), outcomes)
        assertFalse(unreachable.isRemoveAdsOwned)

        val answers = mutableListOf<Boolean>()
        unreachable.refreshRemoveAdsOwnership { answers += it }
        assertEquals(
            "an unreachable service still reports the cached value rather than silence",
            listOf(false),
            answers
        )
        unreachable.close()
    }

    // ===================================================================
    // 4. THE ENTITLEMENT IS PURCHASE STATE, NEVER A LOCAL FLAG
    // ===================================================================

    @Test
    fun noResponseListCanGrantAnEntitlementWhileTheProductDoesNotExist() {
        val candidates = listOf(
            emptyList<String>(),
            listOf(""),
            listOf("remove_ads"),
            listOf("ads_free", "remove.ads"),
            listOf(RemoveAdsProduct.REMOVE_ADS_PRODUCT_ID)
        )

        for (purchased in candidates) {
            assertFalse(
                "with no configured product nothing may be owned, whatever Play reports: $purchased",
                RemoveAdsBilling.ownsRemoveAds(RemoveAdsProduct.isConfigured, purchased)
            )
            assertFalse(
                "and the default gateway must agree with the rule",
                NoOpBillingGateway.isRemoveAdsOwned
            )
        }
    }

    @Test
    fun theOwnershipRuleRequiresBothAProductAndAnExactMatch() {
        // With the real constant empty, configured is false, so the first guard
        // is the one doing the work — and that is the guard that must hold
        // until Play Console has something to match against. There is no
        // positive case to assert here, because there is no product: writing
        // one would mean inventing the id this repository refuses to invent.
        assertFalse(RemoveAdsProduct.isConfigured)
        assertFalse(RemoveAdsBilling.ownsRemoveAds(false, emptyList()))

        // The second guard is still pinned by construction: an unconfigured
        // build short-circuits, so no substring, no near-miss and no empty
        // string in a response can reach the comparison.
        assertFalse(
            RemoveAdsBilling.ownsRemoveAds(RemoveAdsProduct.isConfigured, listOf("REMOVE_ADS"))
        )
    }

    @Test
    fun theEntitlementIsRefreshableRatherThanMerelyRemembered() {
        // Every implementation must answer the refresh — a caller waiting on a
        // service that is not there is a caller waiting forever, and the cached
        // value is explicitly labelled a cache rather than the truth.
        val answers = mutableListOf<Boolean>()
        NoOpBillingGateway.refreshRemoveAdsOwnership { answers += it }
        assertEquals(1, answers.size)
        assertFalse("the cache starts false and starts honestly", answers.single())

        assertTrue(
            "the interface must expose the refresh, not just the flag",
            source("billing/BillingGateway.kt").contains("fun refreshRemoveAdsOwnership")
        )
        assertTrue(
            "and must label the local value as a cache of Play's answer",
            source("billing/BillingGateway.kt").contains("A local cache")
        )
    }

    // ===================================================================
    // 5. NOTHING IS WIRED TO IT
    // ===================================================================

    @Test
    fun noScreenAndNoGameplayClassKnowsThatBillingExists() {
        val touching = filesNaming("BillingGateway")
            .filterNot { it.startsWith("billing/") || it == "MainActivity.kt" }

        assertTrue(
            "billing must be invisible above the shell, so it can never break a " +
                "puzzle, a level or an ad: $touching",
            touching.isEmpty()
        )

        for (screen in listOf("ui/GameController.kt", "ui/GameScreen.kt", "ui/Controls.kt")) {
            val text = source(screen)
            assertFalse(
                "$screen must not know about billing",
                text.contains("Billing") || text.contains("purchase") || text.contains("entitlement")
            )
        }
    }

    @Test
    fun theGooglePlayBillingClientIsImportedOnlyByItsOwnGateway() {
        val root = File("app/src/main/java/com/pushbound/game")
        val importers = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("import com.android.billingclient") }
            .map { it.relativeTo(root).path }
            .sorted()
            .toList()

        assertTrue(
            "every Google type must stay behind the gateway: $importers",
            importers.all { it.startsWith("billing/play/") }
        )
        assertTrue(
            "and the gateway must exist, or the rule above proves nothing",
            importers.isNotEmpty()
        )
    }

    @Test
    fun noPurchaseAffordanceIsExposedToThePlayer() {
        // The four shapes a buy button takes in this codebase: an onClick bound
        // to a purchase, a purchase call from a screen, a product id read by a
        // screen, or a string asking the player to buy something. None exist.
        val root = File("app/src/main/java/com/pushbound/game/ui")
        assertTrue("UI sources not found", root.isDirectory)

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                listOf(
                    "purchaseRemoveAds" to "a purchase call from a screen",
                    "isRemoveAdsOwned" to "an entitlement read by a screen",
                    "REMOVE_ADS_PRODUCT_ID" to "a product id read by a screen",
                    "Buy Remove Ads" to "a buy label",
                    "Remove Ads for" to "a buy label",
                    "Remove Ads —" to "a buy label"
                ).filter { (needle, _) -> text.contains(needle) }
                    .map { (needle, why) -> "${file.name} offers $why ('$needle')" }
                    .toList()
            }
            .toList()

        assertTrue("a purchase affordance has appeared: $offenders", offenders.isEmpty())
    }

    @Test
    fun theBillingDependencyIsPinnedAndTheToolchainAroundItIsNot() {
        val gradle = File("app/build.gradle.kts")
        assertTrue("app/build.gradle.kts not found", gradle.isFile)
        val text = gradle.readText()

        assertTrue(
            "the billing client must be declared, at the version chosen for this " +
                "toolchain",
            text.contains("implementation(\"com.android.billingclient:billing:8.3.0\")")
        )

        // The versions the billing upgrade was explicitly forbidden to move.
        val root = File("build.gradle.kts")
        assertTrue("build.gradle.kts not found", root.isFile)
        val rootText = root.readText()
        assertTrue("AGP must stay at 8.7.0", rootText.contains("version \"8.7.0\""))
        assertTrue("Kotlin must stay at 2.0.21", rootText.contains("version \"2.0.21\""))

        assertTrue(
            "compileSdk and targetSdk stay at 36, together",
            text.contains("compileSdk = 36") && text.contains("targetSdk = 36")
        )
        assertTrue("minSdk stays at 24", text.contains("minSdk = 24"))
        assertTrue(
            "minification stays off — turning it on with a new SDK was not asked for",
            text.contains("isMinifyEnabled = false")
        )
    }

    @Test
    fun theBillingPermissionArrivesFromTheLibraryRatherThanTheManifest() {
        // The library's own manifest contributes com.android.vending.BILLING
        // through normal merging, which is why the source manifest must NOT
        // declare it: a hand-written copy would be a second source of truth for
        // a permission that already arrives, and would survive removing the
        // dependency as a permission nobody needs.
        val manifest = File("app/src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml not found", manifest.isFile)
        // Comments are stripped first: the manifest's own explanation says the
        // word "uses-permission" while explaining that none is declared, and
        // asserting over prose would be testing the comment rather than the file.
        val text = manifest.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

        assertFalse(
            "the BILLING permission must come from the library, not from here",
            text.contains("com.android.vending.BILLING")
        )
        assertFalse(
            "no permission of any kind may be declared by hand — the manifest " +
                "must contain no <uses-permission> element at all",
            text.contains("<uses-permission")
        )
        assertTrue(
            "the manifest still declares the application",
            text.contains("<application")
        )
    }

    @Test
    fun noPurchaseOutcomeCanBeMistakenForAnEntitlement() {
        // Ownership is answered by READING Play, never by an outcome code, so
        // the enum must contain no "granted" case. The one that sounds like
        // ownership — ALREADY_OWNED — is a refusal: it is the reason not to
        // buy, and it is what [RemoveAdsBilling.refusePurchase] returns.
        assertEquals(
            "the outcomes a refused, cancelled or unreachable purchase can produce",
            setOf(
                RemoveAdsPurchaseOutcome.NOT_CONFIGURED,
                RemoveAdsPurchaseOutcome.ALREADY_OWNED,
                RemoveAdsPurchaseOutcome.USER_CANCELLED,
                RemoveAdsPurchaseOutcome.UNAVAILABLE,
                RemoveAdsPurchaseOutcome.FAILED
            ),
            RemoveAdsPurchaseOutcome.entries.toSet()
        )
        assertTrue(
            "no outcome may be named as a grant",
            RemoveAdsPurchaseOutcome.entries.none {
                it.name.startsWith("GRANT") || it.name == "PURCHASED"
            }
        )
        assertEquals(
            "an existing entitlement refuses the purchase rather than granting anything",
            RemoveAdsPurchaseOutcome.ALREADY_OWNED,
            RemoveAdsBilling.refusePurchase(configured = true, owned = true)
        )
        assertEquals(
            "with no product the attempt is refused outright — null, which is what " +
                "'may proceed', is never the answer",
            RemoveAdsPurchaseOutcome.NOT_CONFIGURED,
            RemoveAdsBilling.refusePurchase(RemoveAdsProduct.isConfigured, owned = false)
        )
    }
}
