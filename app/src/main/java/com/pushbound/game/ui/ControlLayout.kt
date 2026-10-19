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
 * Undo and Restart join the cross via [ControlsLayout], immediately outside
 * LEFT and RIGHT on the middle-row band.
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

/**
 * The complete bottom control formation — the centered cross PLUS Undo and
 * Restart hugging it (layout only, no appearance changes):
 *
 *                       [ UP ]
 *        [UNDO]  [ LEFT ] [ RIGHT ]  [RESTART]
 *                       [ DOWN ]
 *
 * One cluster box, centered horizontally by the caller:
 *
 *  - Undo sits immediately outside LEFT with the uniform gap; Restart is
 *    its mirror immediately outside RIGHT (never near the screen edges,
 *    never touching the movement buttons);
 *  - both secondaries are vertically centered on the LEFT/RIGHT row band;
 *  - the side spans (secondary + gap) are equal, so the cross — and
 *    therefore UP, the layout's anchor — keeps the exact horizontal center
 *    and top position it had before, whatever the screen width.
 *
 * All coordinates are dp from the cluster's top-left corner.
 */
class ControlsLayout(
    val dpad: DPadLayout,
    val dpadLeft: Float,
    val undo: PlacedRect,
    val restart: PlacedRect,
    val width: Float,
    val height: Float
) {
    companion object {

        /** Fixed secondary button size (unchanged appearance), in dp. */
        const val SECONDARY_BUTTON = 72f

        /**
         * Largest cross reference size ([ControlsBar]'s `dpadSize`) whose
         * full formation still fits into [containerWidth] dp of bar content.
         * The formation width is exactly linear in the reference size, so
         * this closed form needs no magic constants. The app's existing
         * min(220dp, width x 0.52) rule stays binding on every real phone;
         * this term only guarantees the cluster can never be clipped.
         */
        fun fittingDpadSize(containerWidth: Float): Float {
            val unitWidth = 2f * ControlArtwork.GAP_FRACTION * ControlArtwork.NOMINAL_BUTTON +
                DPadLayout.compute(1f).width
            return (containerWidth - 2f * SECONDARY_BUTTON) / unitWidth * ControlArtwork.SHEET
        }

        fun compute(dpadSize: Float, secondarySize: Float = SECONDARY_BUTTON): ControlsLayout {
            require(dpadSize > 0f) { "dpadSize must be positive" }
            require(secondarySize > 0f) { "secondarySize must be positive" }
            val scale = dpadSize / ControlArtwork.SHEET
            val dpad = DPadLayout.compute(scale)
            val gap = ControlArtwork.GAP_FRACTION * ControlArtwork.NOMINAL_BUTTON * scale

            // Secondaries share the vertical center of the LEFT/RIGHT band
            // (clamped inside the cluster on tiny reference sizes).
            val rowCenterY = dpad.left.centerY
            val undoTop = maxOf(0f, rowCenterY - secondarySize / 2f)

            // Equal side spans: secondary + uniform gap on each side of the
            // cross box, which keeps the cross dead-center in the cluster.
            val dpadLeft = secondarySize + gap
            val width = dpadLeft + dpad.width + gap + secondarySize
            val height = maxOf(dpad.height, undoTop + secondarySize)

            return ControlsLayout(
                dpad = dpad,
                dpadLeft = dpadLeft,
                undo = PlacedRect(0f, undoTop, secondarySize, secondarySize),
                restart = PlacedRect(width - secondarySize, undoTop, secondarySize, secondarySize),
                width = width,
                height = height
            )
        }
    }
}
