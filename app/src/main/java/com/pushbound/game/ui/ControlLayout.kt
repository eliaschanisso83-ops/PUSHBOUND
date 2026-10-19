package com.pushbound.game.ui

/**
 * Pure layout math for the bottom control area. No Android/Compose imports,
 * so the geometry is unit-testable on the JVM (ControlLayoutTest) exactly
 * like the board grid math in BoardLayout.kt.
 *
 * This file only rearranges WHERE the existing approved controls sit: the
 * artwork slices are measurements of the shipped sheets (never modified),
 * and every rendered pixel still comes from those approved assets at the
 * same scale the app already used.
 */

/** Alpha bbox of one button inside its approved 1024² sheet, in sheet pixels. */
data class ArtworkSlice(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Measured with tools/png_profile.c (alpha > 16 transitions) on the approved
 * sheets: four independent button visuals, ~290px each, already separated by
 * ~8-9px of transparent gap. The pressed sheet was rendered ~1.9% larger, so
 * DOWN's pressed artwork carries its own slice to stay pixel-faithful.
 */
object ControlArtwork {
    const val SHEET = 1024

    /** Reference button width (UP slice) — gaps are a fraction of this. */
    const val NOMINAL_BUTTON = 293

    val up = ArtworkSlice(358, 188, 293, 292)
    val left = ArtworkSlice(60, 488, 290, 293)
    val down = ArtworkSlice(359, 488, 292, 292)
    val right = ArtworkSlice(660, 487, 292, 293)
    val downPressed = ArtworkSlice(357, 490, 295, 294)

    /**
     * Uniform neighbour gap as a fraction of the nominal button width: a
     * clearly visible but modest separation (~6.3dp on a 220dp reference
     * pad) — buttons read as one intentional cluster, never as touching.
     */
    const val GAP_FRACTION = 0.10f
}

/** A placed control rectangle in layout units (dp). */
data class PlacedRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

/**
 * The centered cross arrangement of the four movement buttons:
 *
 *            [ UP ]
 *   [ LEFT ]       [ RIGHT ]
 *          [ DOWN ]
 *
 * One common center axis: centerX(UP) = centerX(DOWN) = the midpoint of
 * LEFT/RIGHT = width/2 exactly. Side buttons sit in equal slots, so LEFT and
 * RIGHT are equidistant from the axis. Every neighbouring pair is separated
 * by exactly the same gap (rects never touch or overlap).
 *
 * Undo and Restart are NOT part of this cluster — they live outside it on
 * the left and right of the control container (see ControlsBar).
 */
class DPadLayout private constructor(
    val up: PlacedRect,
    val left: PlacedRect,
    val right: PlacedRect,
    val down: PlacedRect,
    val width: Float,
    val height: Float
) {
    companion object {
        /**
         * @param scale sheet pixels → dp. The caller derives it from the SAME
         *   reference size the app used before (min(220dp, width × 0.52)),
         *   so every button keeps its exact existing on-screen dimensions.
         * @param gapFrac uniform neighbour gap as a fraction of the nominal
         *   button width.
         */
        fun compute(scale: Float, gapFrac: Float = ControlArtwork.GAP_FRACTION): DPadLayout {
            require(scale > 0f) { "scale must be positive" }
            val a = ControlArtwork
            val uw = a.up.width * scale
            val uh = a.up.height * scale
            val lw = a.left.width * scale
            val lh = a.left.height * scale
            val dw = a.down.width * scale
            val dh = a.down.height * scale
            val rw = a.right.width * scale
            val rh = a.right.height * scale
            val gap = gapFrac * a.NOMINAL_BUTTON * scale

            val slotW = maxOf(lw, rw)   // equal side slots => exact symmetry
            val midH = maxOf(lh, rh)    // one middle-row band
            val width = slotW * 2f + gap
            val height = uh + gap + midH + gap + dh

            val midTop = uh + gap
            return DPadLayout(
                up = PlacedRect((width - uw) / 2f, 0f, uw, uh),
                left = PlacedRect((slotW - lw) / 2f, midTop + (midH - lh) / 2f, lw, lh),
                right = PlacedRect(width - slotW + (slotW - rw) / 2f, midTop + (midH - rh) / 2f, rw, rh),
                down = PlacedRect((width - dw) / 2f, midTop + midH + gap, dw, dh),
                width = width,
                height = height
            )
        }
    }
}
