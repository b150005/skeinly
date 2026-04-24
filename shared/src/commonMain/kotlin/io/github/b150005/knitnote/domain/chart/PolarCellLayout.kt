package io.github.b150005.knitnote.domain.chart

import io.github.b150005.knitnote.domain.model.ChartExtents
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Polar coordinate layout for round charts. Parallel to [GridHitTest] +
 * `cellScreenRect` for rectangular charts.
 *
 * ## Convention (ADR-011 §1)
 *
 * - Angle origin: **12 o'clock**, **clockwise positive**. Stitch 0 of each
 *   ring has its wedge *starting* at 12 o'clock — its center sits at
 *   `theta = π/stitchesPerRing[ring]` (half a stitch clockwise from 12).
 * - Ring 0 = innermost; radius grows outward so `y = ring_index` and
 *   "progress outward" matches the reading of rounds.
 * - `ChartCell.width` is ignored in polar (wedge-spanning cells are a
 *   Phase 35.x follow-up).
 *
 * Rotation output is given in radians as a standard-math angle. A Compose
 * `Modifier.rotate(rot.toDegrees())` or SwiftUI `.rotationEffect(.radians(rot))`
 * renders the glyph with its local "up" pointing radially outward.
 */
object PolarCellLayout {
    data class Layout(
        val cx: Double,
        val cy: Double,
        val innerRadius: Double,
        val ringThickness: Double,
    )

    /** Screen-space wedge bounds for a single polar cell. */
    data class Wedge(
        val innerRadius: Double,
        val outerRadius: Double,
        /**
         * Angle (radians) of the wedge's leading edge in our
         * 12-o'clock-CW-positive convention. Stitch 0 starts at `0.0`.
         */
        val startAngle: Double,
        /** Angular width of the wedge, equal to `2π / stitchesPerRing[ring]`. */
        val sweepAngle: Double,
    )

    fun wedgeFor(
        stitch: Int,
        ring: Int,
        extents: ChartExtents.Polar,
        layout: Layout,
    ): Wedge {
        val stitchesInRing = extents.stitchesPerRing[ring]
        val sweep = TWO_PI / stitchesInRing
        return Wedge(
            innerRadius = layout.innerRadius + ring * layout.ringThickness,
            outerRadius = layout.innerRadius + (ring + 1) * layout.ringThickness,
            startAngle = stitch * sweep,
            sweepAngle = sweep,
        )
    }

    /**
     * Cartesian center of the cell wedge in screen coordinates (y-down).
     *
     * Derivation: our convention is `theta = 0` at 12 o'clock, clockwise positive.
     * Screen cartesian has `atan2` returning 0 at 3 o'clock, increasing clockwise
     * (because screen y is flipped). To map our theta back: `screen_angle = theta - π/2`.
     */
    fun cellCenter(
        stitch: Int,
        ring: Int,
        extents: ChartExtents.Polar,
        layout: Layout,
    ): Pair<Double, Double> {
        val wedge = wedgeFor(stitch, ring, extents, layout)
        val radius = (wedge.innerRadius + wedge.outerRadius) / 2.0
        val thetaCenter = wedge.startAngle + wedge.sweepAngle / 2.0
        val screenAngle = thetaCenter - PI_OVER_2
        return Pair(
            layout.cx + radius * cos(screenAngle),
            layout.cy + radius * sin(screenAngle),
        )
    }

    /**
     * Rotation (radians) such that "local up" for the glyph points radially
     * outward from the chart center. Equals the cell's angular center in our
     * 12-o'clock-CW-positive convention — a Compose `rotate(deg)` or SwiftUI
     * `.rotationEffect(.radians(rot))` applied to an otherwise-unrotated glyph
     * aligns it with the ring's outward-pointing radius.
     *
     * Not a function of [Layout] — rotation is geometry-pure, not pixel-scaled.
     */
    fun cellRadialUpRotation(
        stitch: Int,
        ring: Int,
        extents: ChartExtents.Polar,
    ): Double {
        val stitchesInRing = extents.stitchesPerRing[ring]
        val sweep = TWO_PI / stitchesInRing
        return stitch * sweep + sweep / 2.0
    }

    private const val TWO_PI: Double = 2.0 * PI
    private const val PI_OVER_2: Double = PI / 2.0
}
