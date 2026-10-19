package com.pushbound.game

import com.pushbound.game.ui.ArtworkSlice
import com.pushbound.game.ui.ControlArtwork
import com.pushbound.game.ui.ControlsLayout
import com.pushbound.game.ui.DPadLayout
import com.pushbound.game.ui.PlacedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Control-layout invariants (layout only): the centered cross, uniform
 * separation, exact symmetry on one center axis, and preserved button
 * dimensions. Pure geometry — no Android/Compose dependencies.
 */
class ControlLayoutTest {

    // App's existing button sizing formula: min(220dp, width * 0.52).
    private val maxScale = 220f / ControlArtwork.SHEET
    private val layout = DPadLayout.compute(maxScale)
    private val gap = ControlArtwork.GAP_FRACTION * ControlArtwork.NOMINAL_BUTTON * maxScale
    private val secondary = ControlsLayout.SECONDARY_BUTTON
    private val cluster = ControlsLayout.compute(220f)

    // ------------------------------------------------------- center axis --

    @Test
    fun upAndDownShareExactlyTheSameHorizontalCenter() {
        assertEquals(layout.width / 2f, layout.up.centerX, 0.0001f)
        assertEquals(layout.width / 2f, layout.down.centerX, 0.0001f)
        assertEquals(layout.up.centerX, layout.down.centerX, 0.0001f)
    }

    @Test
    fun leftAndRightAreSymmetricAroundTheCenterAxis() {
        val mid = (layout.left.centerX + layout.right.centerX) / 2f
        assertEquals(layout.width / 2f, mid, 0.0001f)
        val leftDist = layout.width / 2f - layout.left.centerX
        val rightDist = layout.right.centerX - layout.width / 2f
        assertEquals(leftDist, rightDist, 0.0001f)
        assertTrue("side buttons sit on both sides of the axis", leftDist > 0f && rightDist > 0f)
    }

    @Test
    fun everyButtonLiesInsideTheClusterBox() {
        for (rect in listOf(layout.up, layout.left, layout.right, layout.down)) {
            assertTrue(rect.left >= -0.0001f && rect.top >= -0.0001f)
            assertTrue(rect.right <= layout.width + 0.0001f)
            assertTrue(rect.bottom <= layout.height + 0.0001f)
        }
    }

    // ------------------------------------------------------------- gaps --

    @Test
    fun neighbouringButtonsNeverTouchAndShareOneUniformGap() {
        val expected = gap
        assertTrue("gap must be visible", expected > 0f)
        // UP bottom -> middle row top
        assertEquals(expected, layout.left.top - layout.up.bottom, 0.0001f)
        assertEquals(expected, layout.right.top - layout.up.bottom, 0.0001f)
        // middle row bottom -> DOWN top
        val midBottom = maxOf(layout.left.bottom, layout.right.bottom)
        assertEquals(expected, layout.down.top - midBottom, 0.0001f)
        // LEFT <-> RIGHT horizontal separation (equal slots keep it >= gap)
        val sideGap = layout.right.left - layout.left.right
        assertTrue("LEFT/RIGHT must not touch", sideGap >= expected - 0.0001f)
    }

    @Test
    fun noMovementRectsOverlap() {
        val rects = listOf(layout.up, layout.left, layout.right, layout.down)
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val a = rects[i]; val b = rects[j]
            val separate =
                a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top
            assertTrue("rects $i and $j must be disjoint", separate)
        }
    }

    // ------------------------------------------------- dimensions / fit --

    @Test
    fun buttonDimensionsAreExactlyTheApprovedSlicesAtExistingScale() {
        fun assertSameSlice(slice: ArtworkSlice, rect: PlacedRect, name: String) {
            assertEquals("$name width", slice.width * maxScale, rect.width, 0.0001f)
            assertEquals("$name height", slice.height * maxScale, rect.height, 0.0001f)
        }
        assertSameSlice(ControlArtwork.up, layout.up, "up")
        assertSameSlice(ControlArtwork.left, layout.left, "left")
        assertSameSlice(ControlArtwork.right, layout.right, "right")
        assertSameSlice(ControlArtwork.down, layout.down, "down")
    }

    @Test
    fun clusterFitsInsideTheExistingReferenceFootprint() {
        // The cross is narrower than the reference pad box the app already
        // reserved for the D-pad, so the zones never crowd each other.
        assertTrue(layout.width < 220f)
        assertTrue(layout.height > layout.width)   // cross is taller than wide
    }

    @Test
    fun artworkSlicesStayInsideTheApprovedSheet() {
        for (slice in listOf(
            ControlArtwork.up, ControlArtwork.left, ControlArtwork.down,
            ControlArtwork.right, ControlArtwork.downPressed
        )) {
            assertTrue(slice.width > 0 && slice.height > 0)
            assertTrue(slice.x >= 0 && slice.y >= 0)
            assertTrue(slice.x + slice.width <= ControlArtwork.SHEET)
            assertTrue(slice.y + slice.height <= ControlArtwork.SHEET)
        }
    }

    @Test
    fun smallerScreensShrinkProportionallyWithoutRearranging() {
        val small = DPadLayout.compute(160f / ControlArtwork.SHEET)
        val big = layout
        // Same topology: ratios preserved, center axis still exact.
        assertEquals(big.width / big.height, small.width / small.height, 0.001f)
        assertEquals(small.width / 2f, small.up.centerX, 0.0001f)
        assertEquals(small.width / 2f, small.down.centerX, 0.0001f)
    }

    // --------------------------------------- compact formation (2) --

    @Test
    fun undoAndRestartSitImmediatelyOutsideTheCross() {
        // Gap measured against the visible LEFT button edge: the uniform gap
        // plus the cross box's own sub-dp left margin (never more than 1dp).
        val leftGap = (cluster.dpadLeft + cluster.dpad.left.left) - cluster.undo.right
        val rightGap = cluster.restart.left - (cluster.dpadLeft + cluster.dpad.right.right)
        assertTrue("Undo must not touch LEFT", leftGap >= gap - 0.0001f)
        assertTrue("Undo must hug LEFT — no huge void", leftGap <= gap + 1f)
        assertEquals("Restart hugs RIGHT at the uniform gap", gap, rightGap, 0.0001f)
        // Secondary buttons frame the cross, they do not drift to the edges
        // of the formation box.
        assertEquals(0f, cluster.undo.left, 0.0001f)
        assertEquals(cluster.width, cluster.restart.right, 0.0001f)
    }

    @Test
    fun secondariesAlignWithTheLeftRightRow() {
        assertEquals("Undo shares the LEFT/RIGHT band center",
            cluster.dpad.left.centerY, cluster.undo.centerY, 0.0001f)
        assertEquals("Restart shares the LEFT/RIGHT band center",
            cluster.dpad.right.centerY, cluster.restart.centerY, 0.0001f)
        assertEquals(cluster.undo.centerY, cluster.restart.centerY, 0.0001f)
    }

    @Test
    fun crossKeepsItsExactCenterAxisAndTopWhenSecondariesJoin() {
        // UP is the layout's anchor: the equal side spans keep the cross —
        // and therefore UP — on the cluster's exact center axis and top
        // edge, so its screen position never changes with this correction.
        assertEquals(cluster.width / 2f, cluster.dpadLeft + cluster.dpad.width / 2f, 0.0001f)
        assertEquals(cluster.width / 2f, cluster.dpadLeft + cluster.dpad.up.centerX, 0.0001f)
        assertEquals(0f, cluster.dpad.up.top, 0.0001f)
        // Bar height still equals the cross height: no layout shift above.
        assertEquals(cluster.dpad.height, cluster.height, 0.0001f)
    }

    @Test
    fun secondaryButtonsNeverTouchOrOverlapTheMovementButtons() {
        assertTrue("Undo must stay outside LEFT",
            cluster.undo.right <= cluster.dpadLeft + cluster.dpad.left.left + 0.0001f)
        assertTrue("Restart must stay outside RIGHT",
            cluster.restart.left >= cluster.dpadLeft + cluster.dpad.right.right - 0.0001f)
        for (rect in listOf(cluster.undo, cluster.restart)) {
            assertTrue(rect.left >= 0f && rect.top >= 0f)
            assertTrue(rect.right <= cluster.width + 0.0001f)
            assertTrue(rect.bottom <= cluster.height + 0.0001f)
        }
        // The whole formation stays compact: cross + one button + one gap
        // on each side, nothing distributed across the screen width.
        assertTrue(cluster.width <= cluster.dpad.width + 2f * secondary + 2f * gap + 0.0001f)
    }

    @Test
    fun formationFitsEveryCommonScreenAndNeverDisplacesTheCross() {
        for (screen in listOf(320f, 360f, 393f, 411f, 480f)) {
            val content = screen - 32f // the bar's 16dp horizontal padding
            val dpadSize = minOf(220f, content * 0.52f, ControlsLayout.fittingDpadSize(content))
                .coerceAtLeast(1f)
            val c = ControlsLayout.compute(dpadSize)
            assertTrue("formation fits ${screen}dp", c.width <= content + 0.0001f)
            // On every real phone the pre-existing sizing rule stays binding,
            // so the cross and UP keep their exact previous size/position.
            assertEquals("existing sizing rule stays binding",
                minOf(220f, content * 0.52f), dpadSize, 0.0001f)
            assertEquals(c.width / 2f, c.dpadLeft + c.dpad.up.centerX, 0.0001f)
        }
    }
}
