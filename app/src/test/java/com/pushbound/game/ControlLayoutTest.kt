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
 * Control-layout invariants (layout only): the centered cross, the visible
 * LEFT/RIGHT separation, exact symmetry on one center axis, preserved button
 * dimensions, and the compact formation — cross dead-center with the
 * Undo/Restart stack hugging its right side without moving UP. Pure
 * geometry — no Android/Compose dependencies.
 */
class ControlLayoutTest {

    // App's existing button sizing formula: min(220dp, width * 0.52).
    private val maxScale = 220f / ControlArtwork.SHEET
    private val layout = DPadLayout.compute(maxScale)
    private val gap = ControlArtwork.GAP_FRACTION * ControlArtwork.NOMINAL_BUTTON * maxScale
    private val secondary = ControlsLayout.SECONDARY_BUTTON
    private val content = 360f                                     // bar content width (dp)
    private val formation = ControlsLayout.compute(content, 220f)

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
        // LEFT <-> RIGHT: the wider horizontal separation the player asked
        // for — at least twice the vertical gap, exact by construction
        // (plus the equal slots' sub-1% padding).
        val sideGap = layout.right.left - layout.left.right
        val sideExpected = ControlArtwork.SIDE_GAP_FRACTION *
            ControlArtwork.NOMINAL_BUTTON * maxScale
        assertTrue("LEFT/RIGHT must not touch", sideGap >= expected - 0.0001f)
        assertTrue("LEFT/RIGHT must be clearly separated", sideGap >= 2f * expected)
        // Exact: the wide gap plus RIGHT's slot padding ((rw - lw)/2 px).
        val slotPad = (ControlArtwork.right.width - ControlArtwork.left.width) / 2f * maxScale
        assertEquals("side gap is the exact SIDE_GAP_FRACTION",
            sideExpected + slotPad, sideGap, 0.0001f)
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
    fun undoAndRestartStackImmediatelyOutsideTheCross() {
        // Both stack buttons hug the cross box's right edge with the exact
        // uniform gap — attached to the cross, never drifting to the edges.
        val crossRight = formation.dpadLeft + formation.dpad.width
        assertEquals("Restart hugs RIGHT at the uniform gap",
            gap, formation.restart.left - crossRight, 0.0001f)
        assertEquals("Undo hugs RIGHT at the same uniform gap",
            gap, formation.undo.left - crossRight, 0.0001f)
        assertEquals("stack buttons share one left edge",
            formation.undo.left, formation.restart.left, 0.0001f)
        // Still one compact formation: cross + gap + stack, nothing spread.
        assertEquals(crossRight + gap + secondary, formation.width, 0.0001f)
        assertTrue("no clipping", formation.width <= content + 0.0001f)
    }

    @Test
    fun undoSitsDirectlyAboveRestartInTheRightSideStack() {
        // The player's red/orange marks: Undo on top, Restart below it,
        // both to the right of the movement cross.
        assertEquals(0f, formation.undo.top, 0.0001f)
        assertEquals(secondary + gap, formation.restart.top, 0.0001f)
        assertTrue("stack buttons never touch", formation.undo.bottom < formation.restart.top)
        assertEquals(formation.undo.centerX, formation.restart.centerX, 0.0001f)
        assertTrue("Undo is inside the box", formation.undo.bottom <= formation.height + 0.0001f)
        assertTrue("Restart is inside the box", formation.restart.bottom <= formation.height + 0.0001f)
        // The stack rises above UP's top so both annotated target spots fall
        // inside the buttons instead of being squeezed at one height.
        assertTrue("stack rises above UP's row", formation.dpadTop > 0f)
    }

    @Test
    fun crossKeepsItsExactScreenAxisAndVerticalPosition() {
        // UP is the layout's anchor: dead-center on the bar content at every
        // screen width — the secondaries join on the right only.
        assertEquals(content / 2f, formation.dpadLeft + formation.dpad.up.centerX, 0.0001f)
        assertEquals(content / 2f, formation.dpadLeft + formation.dpad.width / 2f, 0.0001f)
        // The formation box's bottom edge stays the cross's bottom edge: the
        // box only grows UPWARD (the stack's rise), so UP's on-screen
        // vertical position is preserved exactly.
        assertEquals(formation.dpadTop + formation.dpad.height, formation.height, 0.0001f)
        // The rise is exactly the annotated fraction, never so little that
        // the stack would hang below the cross.
        val stackHeight = 2f * secondary + gap
        val rise = maxOf(
            ControlsLayout.STACK_RISE_FRACTION * formation.dpad.height,
            stackHeight - formation.dpad.height
        )
        assertEquals(rise, formation.dpadTop, 0.0001f)
    }

    @Test
    fun secondaryButtonsNeverTouchOrOverlapTheMovementButtons() {
        val crossRight = formation.dpadLeft + formation.dpad.width
        assertTrue("Undo stays outside RIGHT",
            formation.undo.left >= crossRight - 0.0001f)
        assertTrue("Restart stays outside RIGHT",
            formation.restart.left >= crossRight - 0.0001f)
        for (rect in listOf(formation.undo, formation.restart)) {
            assertTrue(rect.left >= 0f && rect.top >= 0f)
            assertTrue(rect.right <= formation.width + 0.0001f)
            assertTrue(rect.bottom <= formation.height + 0.0001f)
        }
        // No overlap with any movement rect (stack is strictly right of the
        // cross box; the box contains every movement rect).
        for (m in listOf(formation.dpad.up, formation.dpad.left, formation.dpad.right, formation.dpad.down)) {
            for (s in listOf(formation.undo, formation.restart)) {
                val separate = s.left >= formation.dpadLeft + m.right - 0.0001f
                assertTrue("stack must not overlap movement rect", separate)
            }
        }
    }

    @Test
    fun formationFitsEveryCommonScreenAndNeverDisplacesTheCross() {
        for (screen in listOf(320f, 360f, 393f, 411f, 480f)) {
            val w = screen - 32f // the bar's 16dp horizontal padding
            val dpadSize = minOf(220f, w * 0.52f, ControlsLayout.fittingDpadSize(w))
                .coerceAtLeast(1f)
            val c = ControlsLayout.compute(w, dpadSize)
            assertTrue("formation fits ${screen}dp", c.width <= w + 0.0001f)
            assertTrue("cross left edge inside content", c.dpadLeft >= -0.0001f)
            // On every real phone the pre-existing sizing rule stays binding,
            // so the cross and UP keep their exact previous size/position.
            assertEquals("existing sizing rule stays binding",
                minOf(220f, w * 0.52f), dpadSize, 0.0001f)
            assertEquals("UP stays on the content's exact center axis",
                w / 2f, c.dpadLeft + c.dpad.up.centerX, 0.0001f)
            // Bottom edge = cross bottom on every screen: no vertical shift.
            assertEquals(c.dpadTop + c.dpad.height, c.height, 0.0001f)
            assertTrue(c.restart.bottom <= c.height + 0.0001f)
        }
    }
}
