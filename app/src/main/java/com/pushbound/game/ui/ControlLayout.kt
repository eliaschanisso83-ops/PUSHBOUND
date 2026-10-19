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
     * Vertical gap between UP and the single row beneath it, as a fraction
     * of the nominal button width (~6.3dp on a 220dp reference pad) — a
     * clearly visible but modest separation, so UP floats above the row
     * without touching it.
     */
    const val GAP_FRACTION = 0.10f

    /**
     * Gap between the adjacent buttons of the single middle row
     * (LEFT↔DOWN and DOWN↔RIGHT), as a fraction of the nominal button width
     * (~18.9dp on the reference pad — three times the vertical gap). The
     * player asked for the movement buttons to be clearly SPREAD APART and
     * for "considerable space" between the three in the row, so this gap is
     * deliberately generous: LEFT sits far left, RIGHT far right, DOWN
     * between them, none reading as glued.
     */
    const val SIDE_GAP_FRACTION = 0.30f
}

/** A placed control rectangle in layout units (dp). */
data class PlacedRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

/**
 * The movement arrangement: UP floats on top, and the other three form ONE
 * horizontal row beneath it — a shallow rectangle rather than a diamond:
 *
 *              [ UP ]
 *   [ LEFT ]  [ DOWN ]  [ RIGHT ]
 *
 * One common center axis: centerX(UP) = centerX(DOWN) = the midpoint of
 * LEFT/RIGHT = width/2 exactly. The three row buttons sit in equal slots
 * separated by the same generous horizontal gap, so LEFT is pushed far
 * left, RIGHT far right and DOWN stays on the axis between them, all
 * visibly separated (never touching). UP keeps the axis and is separated
 * from the row by the uniform vertical gap. Rects never touch or overlap.
 *
 * Undo and Restart join the formation via [ControlsLayout] as a vertical
 * stack immediately outside RIGHT.
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
         * @param gapFrac vertical UP↔row gap as a fraction of the nominal
         *   button width.
         * @param sideGapFrac gap between adjacent row buttons (LEFT↔DOWN,
         *   DOWN↔RIGHT) as a fraction of the nominal button width
         *   (deliberately generous; layout only).
         */
        fun compute(
            scale: Float,
            gapFrac: Float = ControlArtwork.GAP_FRACTION,
            sideGapFrac: Float = ControlArtwork.SIDE_GAP_FRACTION
        ): DPadLayout {
            require(scale > 0f) { "scale must be positive" }
            require(sideGapFrac >= gapFrac) { "row gap must not be smaller than the vertical gap" }
            val a = ControlArtwork
            val uw = a.up.width * scale
            val uh = a.up.height * scale
            val lw = a.left.width * scale
            val lh = a.left.height * scale
            val dw = a.down.width * scale
            val dh = a.down.height * scale
            val rw = a.right.width * scale
            val rh = a.right.height * scale
            val vgap = gapFrac * a.NOMINAL_BUTTON * scale       // UP <-> row
            val hgap = sideGapFrac * a.NOMINAL_BUTTON * scale   // row gaps

            // One row of three equal slots: [ LEFT ][ DOWN ][ RIGHT ]. Equal
            // slots put DOWN exactly on the shared center axis and LEFT /
            // RIGHT exactly symmetric around it.
            val slotW = maxOf(lw, dw, rw)   // one slot size => exact symmetry
            val rowH = maxOf(lh, dh, rh)    // one row band
            val width = slotW * 3f + hgap * 2f
            val height = uh + vgap + rowH   // UP on top, one shallow row below

            val rowTop = uh + vgap
            return DPadLayout(
                up = PlacedRect((width - uw) / 2f, 0f, uw, uh),
                left = PlacedRect((slotW - lw) / 2f, rowTop + (rowH - lh) / 2f, lw, lh),
                down = PlacedRect(
                    slotW + hgap + (slotW - dw) / 2f, rowTop + (rowH - dh) / 2f, dw, dh
                ),
                right = PlacedRect(
                    slotW * 2f + hgap * 2f + (slotW - rw) / 2f, rowTop + (rowH - rh) / 2f, rw, rh
                ),
                width = width,
                height = height
            )
        }
    }
}

/**
 * The complete bottom control formation (layout only, no appearance
 * changes). The movement block is a shallow single-row rectangle (UP on
 * top, LEFT / DOWN / RIGHT in one row) that stays dead-center on the bar
 * content — UP keeps its exact axis and vertical position — while Undo and
 * Restart become a vertical stack hugging the block's right side:
 *
 *              [ UP ]  [UNDO]
 *   [ LEFT ]  [ DOWN ]  [ RIGHT ]  [RESTART]
 *
 *  - The movement block keeps the exact screen-centered axis it always had
 *    (dpadLeft puts centerX(UP) = content/2 for every width), so UP does
 *    not move at all;
 *  - the stack sits immediately outside RIGHT with the uniform gap (never
 *    at the screen edges, never touching the movement buttons), Undo above
 *    Restart, both to the right of the block — the "good corner" the
 *    player marked in red/orange;
 *  - the stack rises [STACK_RISE_FRACTION] × block-height above UP's top,
 *    the height at which BOTH of the player's orange target spots fall
 *    inside their buttons (undo covers the upper spot, restart covers the
 *    lower one) on the reference device;
 *  - the formation box keeps the movement block's bottom edge as its
 *    bottom edge, so the bar's growth happens upward only and UP's
 *    on-screen position is preserved exactly.
 *
 * All coordinates are dp from the formation box's top-left corner.
 */
class ControlsLayout(
    val dpad: DPadLayout,
    val dpadLeft: Float,
    val dpadTop: Float,
    val undo: PlacedRect,
    val restart: PlacedRect,
    val width: Float,
    val height: Float
) {
    companion object {

        /** Fixed secondary button size (unchanged appearance), in dp. */
        const val SECONDARY_BUTTON = 72f

        /**
         * How far above UP's top the Undo/Restart stack starts, as a fraction
         * of the cross height (~58dp / 160px on the player's reference
         * device). Derived from the player's own annotated targets so both
         * orange spots fall inside their buttons: with two 72dp boxes stacked
         * (needs 213px of separation) the spots sit only 130px apart, so the
         * even-error solution puts the pair across UP's top band, rising
         * above it. A second term guarantees the stack can never hang below
         * the cross on very small pads, keeping the box bottom — and thus
         * UP's position — exact on every screen.
         */
        const val STACK_RISE_FRACTION = 0.34f

        /**
         * Largest cross reference size ([ControlsBar]'s `dpadSize`) whose
         * full formation still fits into [containerWidth] dp of bar content.
         * The formation width is exactly linear in the reference size, so
         * this closed form needs no magic constants. The app's existing
         * min(220dp, width x 0.52) rule stays binding on every real phone;
         * this term only guarantees the stack can never be clipped.
         */
        fun fittingDpadSize(containerWidth: Float): Float {
            val unitWidth = 2f * ControlArtwork.GAP_FRACTION * ControlArtwork.NOMINAL_BUTTON +
                DPadLayout.compute(1f).width
            return (containerWidth - 2f * SECONDARY_BUTTON) / unitWidth * ControlArtwork.SHEET
        }

        /**
         * @param contentWidth the bar's content width in dp (inside the
         *   16dp horizontal padding); the cross is centered on it.
         * @param dpadSize the app's unchanged cross reference size.
         */
        fun compute(
            contentWidth: Float,
            dpadSize: Float,
            secondarySize: Float = SECONDARY_BUTTON
        ): ControlsLayout {
            require(dpadSize > 0f) { "dpadSize must be positive" }
            require(secondarySize > 0f) { "secondarySize must be positive" }
            val scale = dpadSize / ControlArtwork.SHEET
            val dpad = DPadLayout.compute(scale)
            val gap = ControlArtwork.GAP_FRACTION * ControlArtwork.NOMINAL_BUTTON * scale

            // The cross stays dead-center on the bar content: UP keeps its
            // exact horizontal axis at every screen width.
            val dpadLeft = (contentWidth - dpad.width) / 2f

            // Vertical stack immediately outside RIGHT with the uniform gap.
            val stackLeft = dpadLeft + dpad.width + gap
            val stackHeight = 2f * secondarySize + gap

            // Rise above UP's top (never so little that the stack would hang
            // below the cross — that would shift UP upward).
            val rise = maxOf(
                STACK_RISE_FRACTION * dpad.height,
                stackHeight - dpad.height
            )

            // Coordinates are rebased so the box's top edge is the stack's
            // top (all offsets >= 0) and the box's bottom edge stays the
            // cross's bottom edge (UP's position preserved exactly).
            val dpadTop = rise
            val height = dpadTop + dpad.height
            val width = stackLeft + secondarySize

            return ControlsLayout(
                dpad = dpad,
                dpadLeft = dpadLeft,
                dpadTop = dpadTop,
                undo = PlacedRect(stackLeft, 0f, secondarySize, secondarySize),
                restart = PlacedRect(stackLeft, secondarySize + gap, secondarySize, secondarySize),
                width = width,
                height = height
            )
        }
    }
}
