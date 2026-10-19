package com.pushbound.game

import com.pushbound.game.ui.BoardGeometry
import com.pushbound.game.ui.ControlsLayout
import com.pushbound.game.ui.GameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rotation support invariants (LAYOUT ONLY): the portrait composition stays
 * the reference, landscape re-arranges the same parts beside the board, the
 * board keeps square cells and every control stays reachable and unclipped.
 * Pure geometry — no Android/Compose dependencies.
 */
class GameLayoutTest {

    /** A window and the same physical device after a 90° rotation. */
    private data class Device(val width: Float, val height: Float) {
        /** The same device in landscape. */
        val rotated: Device get() = Device(height, width)
    }

    // Phones from small (5", 720p) up to a modern tall 20:9 device
    // (portrait dimensions; [rotated] is their landscape window).
    private val phones = listOf(
        Device(320f, 640f),   // 2:1  (older 5")
        Device(360f, 800f),   // 20:9
        Device(393f, 873f),   // the player's own device
        Device(412f, 915f),   // 20:9 Pixel-class
        Device(480f, 1024f)   // 2.13:1 tablet-class
    )

    // Devices whose LANDSCAPE window is not at least 2:1: the board becomes
    // width-limited there, so the control panel must yield to it.
    private val squareish = listOf(
        Device(360f, 640f),   // 16:9 phone
        Device(480f, 640f),   // 4:3
        Device(600f, 800f)    // 4:3 window (tablet / split screen)
    )

    /** The app's unchanged button sizing rule, as ControlsBar applies it. */
    private fun dpadSizeFor(contentWidth: Float): Float =
        minOf(
            220f,
            contentWidth * 0.52f,
            ControlsLayout.fittingDpadSize(contentWidth)
        ).coerceAtLeast(1f)

    // ------------------------------------------------------- orientation --

    @Test
    fun orientationIsReadFromTheWindowShape() {
        assertTrue(GameLayout.isLandscape(873f, 393f))
        assertFalse(GameLayout.isLandscape(393f, 873f))
        assertFalse(GameLayout.isLandscape(600f, 600f))
    }

    // ------------------------------------------------- button size parity --

    @Test
    fun landscapePanelReproducesThePortraitControlContentSizeOnPhones() {
        phones.forEach { device ->
            val portraitContent =
                device.width - 2f * GameLayout.CONTROL_PADDING_H_DP
            val landscapeContent = GameLayout.landscapeControlContentWidth(
                device.rotated.width, device.rotated.height
            )
            assertEquals(
                "control content width must survive rotation unchanged " +
                    "(${device.width}x${device.height})",
                portraitContent, landscapeContent, 0.0001f
            )
        }
    }

    @Test
    fun everyControlButtonKeepsItsExactPortraitSizeAfterRotation() {
        phones.forEach { device ->
            val portrait = dpadSizeFor(device.width - 2f * GameLayout.CONTROL_PADDING_H_DP)
            val landscape = dpadSizeFor(
                GameLayout.landscapeControlContentWidth(
                    device.rotated.width, device.rotated.height
                )
            )
            assertEquals(
                "movement buttons must not resize on rotation " +
                    "(${device.width}x${device.height})",
                portrait, landscape, 0.0001f
            )
        }
    }

    @Test
    fun theLandscapeFormationIsTheSameAsThePortraitOneJustMovedAside() {
        phones.forEach { device ->
            val portraitContent = device.width - 2f * GameLayout.CONTROL_PADDING_H_DP
            val landscapeContent = GameLayout.landscapeControlContentWidth(
                device.rotated.width, device.rotated.height
            )
            val portrait = ControlsLayout.compute(
                portraitContent, dpadSizeFor(portraitContent)
            )
            val landscape = ControlsLayout.compute(
                landscapeContent, dpadSizeFor(landscapeContent)
            )
            assertEquals(portrait.dpad.width, landscape.dpad.width, 0.0001f)
            assertEquals(portrait.dpad.height, landscape.dpad.height, 0.0001f)
            assertEquals(portrait.dpad.up.centerX, landscape.dpad.up.centerX, 0.0001f)
            assertEquals(portrait.undo.left, landscape.undo.left, 0.0001f)
            assertEquals(portrait.restart.left, landscape.restart.left, 0.0001f)
            assertEquals(portrait.width, landscape.width, 0.0001f)
            assertEquals(portrait.height, landscape.height, 0.0001f)
            // And the identical formation still fits its identical panel.
            assertTrue(landscape.width <= landscapeContent + 0.0001f)
        }
    }

    // --------------------------------------------- board keeps its room --

    @Test
    fun theBoardAlwaysKeepsAtLeastHalfTheWindowInLandscape() {
        (phones + squareish).forEach { device ->
            val w = device.rotated.width
            val h = device.rotated.height
            assertTrue(
                "board band must keep >= 50% of ${w}x${h}",
                GameLayout.landscapeBoardWidth(w, h) >= w * 0.5f - 0.0001f
            )
            assertTrue("board band must stay positive", GameLayout.landscapeBoardWidth(w, h) > 0f)
        }
    }

    @Test
    fun theControlPanelYieldsToTheBoardOnNearSquareWindows() {
        squareish.forEach { device ->
            val w = device.rotated.width
            val h = device.rotated.height
            assertTrue(
                "panel must not exceed half of ${w}x${h}",
                GameLayout.landscapeControlPanelWidth(w, h) <= w * 0.5f + 0.0001f
            )
        }
    }

    @Test
    fun theControlFormationAlwaysFitsItsPanelInLandscape() {
        (phones + squareish).forEach { device ->
            val w = device.rotated.width
            val h = device.rotated.height
            val content = GameLayout.landscapeControlContentWidth(w, h)
            val formation = ControlsLayout.compute(content, dpadSizeFor(content))
            assertTrue(
                "formation must fit ${w}x${h} without clipping",
                formation.width <= content + 0.0001f
            )
            assertTrue("no negative placement", formation.dpadLeft >= -0.0001f)
            assertTrue(formation.restart.bottom <= formation.height + 0.0001f)
        }
    }

    // ------------------------------------------------------- board cells --

    @Test
    fun boardCellsStaySquareAndCenteredInBothOrientations() {
        val level = Levels.first
        val regions = listOf(
            336f to 605f,   // portrait band on the player's device
            480f to 296f,   // landscape band on the player's device
            280f to 263f,   // narrow landscape band (16:9 phone)
            256f to 479f    // near-square window
        )
        regions.forEach { (width, height) ->
            val g = BoardGeometry.fit(width, height, level.width, level.height)
            // One cell is one square unit: the cell is the limiting division,
            // so nothing is ever stretched horizontally or vertically.
            assertTrue("cell must stay square in ${width}x${height}", g.cellSize > 0f)
            assertTrue(
                "board must stay inside its region (${width}x${height})",
                g.boardWidth <= width + 0.0001f && g.boardHeight <= height + 0.0001f
            )
            assertEquals("board must stay centered horizontally",
                width / 2f, g.boardWidth / 2f + g.originX, 0.0001f)
            assertEquals("board must stay centered vertically",
                height / 2f, g.boardHeight / 2f + g.originY, 0.0001f)
            // The board uses the largest square cell that fits (never wasted).
            assertEquals(
                minOf(width / level.width, height / level.height), g.cellSize, 0.0001f
            )
        }
    }
}
