package com.pushbound.game.ui

import kotlin.math.min

/**
 * Orientation-aware composition geometry (LAYOUT ONLY — no appearance,
 * gameplay or asset changes). Pure arithmetic in dp, driven exclusively by
 * the window's own measured size (never hardcoded or device-specific
 * pixels), so the responsive portrait/landscape arrangement is unit
 * testable without Android.
 *
 * The contract:
 *
 *  - PORTRAIT stays exactly as it always was: HUD on top, board centered in
 *    the middle band, control bar under the board.
 *  - LANDSCAPE re-arranges the SAME parts side by side: HUD still across the
 *    top, board centered in the remaining area on the left, control bar as a
 *    panel on the right — because the window's height (short side) is what
 *    limits a row-based game, putting the controls BESIDE the board is what
 *    keeps the board at its largest practical square size.
 *  - Rotation keeps the window's short side constant, so the landscape
 *    panel can reuse that short side to keep every button at exactly its
 *    portrait size (see the cap in [landscapeControlPanelWidth]): only the
 *    position on screen changes, never the art, scale, or relative geometry
 *    of the controls.
 */
object GameLayout {

    /** The control bar's own horizontal content padding, in dp. */
    const val CONTROL_PADDING_H_DP = 16f

    /** Board area padding, in dp (unchanged from the portrait layout). */
    const val BOARD_PADDING_H_DP = 12f
    const val BOARD_PADDING_V_DP = 4f

    /** A window is landscape exactly when it is wider than it is tall. */
    fun isLandscape(width: Float, height: Float): Boolean = width > height

    /**
     * Total width of the right-hand control panel in landscape, in dp.
     *
     * The portrait control bar spans the window's SHORT side and then takes
     * [CONTROL_PADDING_H_DP] of padding on each side. Rotating the device
     * turns that same short side into the landscape HEIGHT, so
     * `min(landscapeHeight, ...)` reproduces the portrait content width bit
     * for bit — which is exactly what makes every movement and secondary
     * button keep its EXACT portrait size in landscape (the control sizing
     * rule in [ControlsLayout] depends only on that content width). That
     * holds for every window at least 2:1 (every phone in landscape).
     *
     * The second term caps the panel at half the window width: on
     * near-square windows (4:3 tablets, split screen) the board — not the
     * height — becomes the limiting dimension, so the panel yields first and
     * the board is never squeezed out of the screen. A wider window can only
     * ever give the board MORE room, never less.
     */
    fun landscapeControlPanelWidth(landscapeWidth: Float, landscapeHeight: Float): Float {
        require(landscapeWidth > 0f) { "Window width must be positive" }
        require(landscapeHeight > 0f) { "Window height must be positive" }
        return min(landscapeHeight, landscapeWidth * 0.5f)
    }

    /**
     * The control bar's CONTENT width in landscape (its panel width minus
     * the bar's own horizontal padding). On a phone this equals the portrait
     * content width exactly, so feeding it to
     * [ControlsLayout.fittingDpadSize] / [ControlsLayout.compute] yields the
     * same formation as portrait — only moved to the side of the board.
     */
    fun landscapeControlContentWidth(landscapeWidth: Float, landscapeHeight: Float): Float =
        landscapeControlPanelWidth(landscapeWidth, landscapeHeight) - 2f * CONTROL_PADDING_H_DP

    /**
     * The band left for the board in landscape: everything the control panel
     * does not take, in dp. Always at least half the window width, so the
     * board can never be squeezed out of the screen.
     */
    fun landscapeBoardWidth(landscapeWidth: Float, landscapeHeight: Float): Float =
        landscapeWidth - landscapeControlPanelWidth(landscapeWidth, landscapeHeight)
}
