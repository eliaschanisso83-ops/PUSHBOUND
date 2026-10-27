package com.pushbound.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the Undo control and the restoration dialog actually say and wire.
 *
 * WHY SOURCE, NOT SCREEN — there is no emulator, no instrumentation runner and
 * no Compose UI-test artifact in this project, so a rendered assertion cannot
 * run here and would not run on CI either. Rather than skip the layer entirely,
 * these tests pin the part that a rendered test would really be checking: that
 * the control is ALWAYS present, that it reads the live balance, that the two
 * dialog buttons are bound to the right two controller methods, and that the
 * only line in the whole app which can reach the advertising SDK is the one
 * labelled "Watch Ad".
 *
 * They are deliberately about wiring and copy, never about colours or spacing —
 * those are the parts that legitimately change without anything being broken.
 */
class UndoUiPresentationTest {

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

    /**
     * The body of the top-level function declared with [signature].
     *
     * Good enough for this codebase because every top-level function in the UI
     * files ends with a `}` at column zero, and it keeps the assertions below
     * about ONE function instead of accidentally passing because a string
     * appears somewhere else in the file.
     */
    private fun functionBody(file: String, signature: String): String {
        val text = source(file)
        val start = text.indexOf(signature)
        assertTrue("$signature not found in $file", start >= 0)
        val rest = text.substring(start)
        val end = rest.indexOf("\n}\n")
        return if (end >= 0) rest.substring(0, end) else rest
    }

    // ===================================================================
    // 1. THE CONTROL — always there, always live
    // ===================================================================

    @Test
    fun theUndoControlIsAlwaysRenderedAndNeverRemovesItself() {
        val controls = source("ui/Controls.kt")

        assertTrue(
            "the control must be rendered unconditionally, not hidden at zero",
            controls.contains("enabled = controller.canUndo || controller.canRequestUndoRestoration")
        )
        assertTrue(
            "and it must still be tappable when there is nothing to undo, or the " +
                "player whose balance just reached zero would have no way back to five",
            controls.contains("controller.canRequestUndoRestoration")
        )
        // The control bar renders Undo as one of its three secondaries; there is
        // no `if` around it anywhere in the formation.
        val bar = functionBody("ui/Controls.kt", "fun ControlsBar(")
        assertEquals(
            "the control bar must render exactly one Undo control",
            1,
            Regex("label = \"Undo\"").findAll(bar).count()
        )
        assertFalse("the Undo control must not be conditional", bar.contains("if (controller.canUndo)"))
    }

    @Test
    fun theUndoControlShowsTheLiveCreditCountRatherThanAStoredOne() {
        val bar = functionBody("ui/Controls.kt", "fun ControlsBar(")

        assertTrue(
            "the chip must read the balance the rules enforce, so it can never drift",
            bar.contains("badge = controller.undoCreditsRemaining.toString()")
        )
        assertTrue(
            "and the emphasis must be derived from the same balance",
            bar.contains("badgeEmphasised = controller.undoCreditsRemaining == 0")
        )
        assertTrue(
            "the number must be on screen at every value from five to zero",
            bar.contains("controller.undoCreditsRemaining.toString()")
        )
    }

    @Test
    fun theUndoControlAnnouncesItsStateRatherThanItsGlyph() {
        val bar = functionBody("ui/Controls.kt", "fun ControlsBar(")

        assertTrue(
            "the screen reader must get the stateful label, not the word Undo",
            bar.contains("accessibilityLabel = controller.undoAccessibilityDescription")
        )
        assertTrue(
            "the label must be built from the same balance as the chip",
            source("ui/Controls.kt").contains("accessibilityLabel: String = label")
        )
        assertTrue(
            "and it must be the gate's wording, so 'unavailable' is reachable",
            source("ui/GameController.kt").contains("UndoGate.accessibilityLabel(creditState)")
        )
    }

    @Test
    fun theCreditChipIsNotAnnouncedTwiceByTheScreenReader() {
        val controls = source("ui/Controls.kt")

        assertTrue(
            "the bare digit must be stripped from the accessibility tree, or the " +
                "count is read once on its own and once inside the label",
            controls.contains("clearAndSetSemantics")
        )
        val badge = functionBody("ui/Controls.kt", "private fun CreditBadge(")
        assertTrue(
            "the chip must carry its own background so it stays legible over artwork",
            badge.contains(".background(background, RoundedCornerShape(9.dp))")
        )
        assertTrue(
            "and it must be marked so zero reads as the state that needs attention",
            badge.contains("0xFFD6462B")
        )
    }

    @Test
    fun theNoticeLineIsRenderedInTheSamePlaceAsTheHintNotice() {
        val screen = source("ui/GameScreen.kt")

        assertTrue(
            "the status line must be rendered, not merely stored",
            screen.contains("val notice = controller.hintNotice ?: controller.undoNotice")
        )
        assertTrue(
            "and it must share the hint notice's slot so the two can never stack",
            screen.contains("controller.undoNotice")
        )
    }

    // ===================================================================
    // 2. THE DIALOG — two choices, one of which reaches the SDK
    // ===================================================================

    @Test
    fun theRestorationDialogIsWiredIntoTheScreen() {
        val screen = source("ui/GameScreen.kt")

        assertTrue(
            "the offer must actually be shown when it is raised",
            screen.contains("UndoRestorationDialog(controller)")
        )
        val dialog = functionBody("ui/GameScreen.kt", "private fun UndoRestorationDialog(")
        assertTrue(
            "and it must be drawn from the offer the controller holds",
            dialog.contains("controller.undoRestorationOffer")
        )
        assertTrue(
            "an offer that is not open must not cover the board",
            dialog.contains("return")
        )
    }

    @Test
    fun theRestorationDialogOffersExactlyTheTwoChoicesTheRulesRequire() {
        val dialog = functionBody("ui/GameScreen.kt", "private fun UndoRestorationDialog(")

        assertTrue("the title is required verbatim", dialog.contains("\"Out of Undo moves\""))
        assertTrue("Watch Ad is required verbatim", dialog.contains("\"Watch Ad\""))
        assertTrue("Not now is required verbatim", dialog.contains("\"Not now\""))
        assertTrue(
            "the reward must be stated before the choice",
            dialog.contains("restore")
        )
        assertTrue(
            "and declining must be offered as an equal exit, not a grudging one",
            dialog.contains("optional")
        )
        assertEquals(
            "exactly two buttons — a third would be a choice the rules never made",
            2,
            Regex("Button\\s*\\(").findAll(dialog).count()
        )
    }

    @Test
    fun theDialogStatesTheRewardFromTheRuleNotAFrozenLiteral() {
        val dialog = functionBody("ui/GameScreen.kt", "private fun UndoRestorationDialog(")

        assertTrue(
            "the count in the copy must come from the rule, so it cannot drift from it",
            dialog.contains("UndoGate.MAX_UNDO_CREDITS")
        )
        assertFalse(
            "a literal five in the copy would lie the day the balance changed",
            dialog.contains("\"5 Undo moves\"")
        )
        assertFalse(
            "nor may the copy hardcode the balance in any form",
            dialog.contains("\"all 5\"")
        )
    }

    @Test
    fun onlyTheWatchAdButtonCanReachTheAdvertisingSdk() {
        val dialog = functionBody("ui/GameScreen.kt", "private fun UndoRestorationDialog(")

        assertEquals(
            "the confirmation must be bound to exactly one button — the one that " +
                "says Watch Ad",
            1,
            Regex("controller::confirmUndoRestoration").findAll(dialog).count()
        )
        assertEquals(
            "and the dismissal to exactly one other button",
            1,
            Regex("controller::dismissUndoRestoration").findAll(dialog).count()
        )

        // Which button is which, read out of the source rather than assumed:
        // the first bound action must be the one whose label is Watch Ad, and
        // the second the one whose label is Not now. Binding them the other way
        // round would be a working dialog that asks for an ad when the player
        // declined, and declines when they agreed.
        val confirmAt = dialog.indexOf("controller::confirmUndoRestoration")
        val dismissAt = dialog.indexOf("controller::dismissUndoRestoration")
        assertTrue("both bindings must exist", confirmAt >= 0 && dismissAt > confirmAt)
        assertTrue(
            "the confirmed action must belong to the Watch Ad button",
            dialog.substring(confirmAt, dismissAt).contains("\"Watch Ad\"")
        )
        assertTrue(
            "the declining action must belong to the Not now button",
            dialog.substring(dismissAt).contains("\"Not now\"")
        )

        assertFalse(
            "the dialog itself must never ask for an ad — only a confirmed choice may",
            dialog.contains("showRewarded")
        )
        assertFalse(
            "nor preload one",
            dialog.contains("preloadRewarded")
        )
    }

    @Test
    fun noScreenAsksForAnAdOutsideTheTwoConfirmationsThatAlreadyExist() {
        // The hint offer and the Undo restoration are the only two places in
        // the whole app where a player can start an ad. Anything else that
        // reaches for one would be an ad the player never asked for, at a
        // moment the rules forbid.
        val files = filesNaming("showRewarded")
        val unexpected = files.filterNot {
            it.startsWith("ads/") || it == "ui/GameController.kt"
        }
        assertTrue(
            "only the advertising seam and the controller may request an ad: $unexpected",
            unexpected.isEmpty()
        )
    }

    @Test
    fun bothConfirmationsAreBoundInExactlyOnePlaceEach() {
        assertEquals(
            "the restoration confirmation must be reachable from the dialog and " +
                "declared in the controller — nowhere else",
            listOf("ui/GameController.kt", "ui/GameScreen.kt"),
            filesNaming("confirmUndoRestoration")
        )
        assertEquals(
            "likewise the dismissal",
            listOf("ui/GameController.kt", "ui/GameScreen.kt"),
            filesNaming("dismissUndoRestoration")
        )
    }

    @Test
    fun theBalanceIsNeverRenderedFromASourceOtherThanTheController() {
        // A second reader of the same number is how a UI ends up showing five
        // while the rules enforce four.
        val badgeReaders = filesNaming("undoCreditsRemaining")
        val unexpected = badgeReaders.filterNot {
            it == "ui/GameController.kt" || it == "ui/Controls.kt"
        }
        assertTrue(
            "only the controller may own the balance and the control may read it: $unexpected",
            unexpected.isEmpty()
        )
    }

    // ===================================================================
    // 3. THE SHELL — billing is present, and inert
    // ===================================================================

    @Test
    fun theBillingSeamIsConstructedByTheShellAndReleasedWithIt() {
        val main = source("MainActivity.kt")

        assertTrue(
            "the gateway must be built by the activity that owns its lifetime",
            main.contains("billing = GooglePlayBillingGateway(this)")
        )
        assertTrue(
            "and closed when that activity is destroyed",
            main.contains("billing?.close()")
        )
        assertTrue(
            "with the seam typed as the interface, not the implementation",
            main.contains("private var billing: BillingGateway? = null")
        )
        assertFalse(
            "the shell must not connect at launch — nothing queries it",
            main.contains("billing?.connect")
        )
    }
}
