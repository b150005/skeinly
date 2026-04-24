package io.github.b150005.knitnote.domain.chart

import io.github.b150005.knitnote.domain.model.ChartExtents
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class PolarCellLayoutTest {
    private val extents = ChartExtents.Polar(rings = 3, stitchesPerRing = listOf(4, 8, 12))
    private val layout =
        PolarCellLayout.Layout(
            cx = 100.0,
            cy = 100.0,
            innerRadius = 20.0,
            ringThickness = 10.0,
        )

    // --- wedgeFor ---

    @Test
    fun `wedgeFor stitch 0 ring 0 spans innermost band`() {
        val w = PolarCellLayout.wedgeFor(0, 0, extents, layout)
        assertApproxEquals(20.0, w.innerRadius)
        assertApproxEquals(30.0, w.outerRadius)
        assertApproxEquals(0.0, w.startAngle)
        assertApproxEquals(PI / 2.0, w.sweepAngle) // 4 stitches then PI over 2 each
    }

    @Test
    fun `wedgeFor stitch 1 ring 0 starts at first boundary`() {
        val w = PolarCellLayout.wedgeFor(1, 0, extents, layout)
        assertApproxEquals(PI / 2.0, w.startAngle)
    }

    @Test
    fun `wedgeFor last stitch of ring starts at N minus 1 times sweep`() {
        val w = PolarCellLayout.wedgeFor(3, 0, extents, layout)
        assertApproxEquals(3.0 * PI / 2.0, w.startAngle)
    }

    @Test
    fun `wedgeFor ring 1 uses radial band offset by ringThickness`() {
        val w = PolarCellLayout.wedgeFor(0, 1, extents, layout)
        assertApproxEquals(30.0, w.innerRadius)
        assertApproxEquals(40.0, w.outerRadius)
        assertApproxEquals(PI / 4.0, w.sweepAngle)
    }

    @Test
    fun `wedgeFor different stitchesPerRing produces different sweeps`() {
        val r0 = PolarCellLayout.wedgeFor(0, 0, extents, layout).sweepAngle
        val r1 = PolarCellLayout.wedgeFor(0, 1, extents, layout).sweepAngle
        val r2 = PolarCellLayout.wedgeFor(0, 2, extents, layout).sweepAngle
        assertApproxEquals(PI / 2.0, r0)
        assertApproxEquals(PI / 4.0, r1)
        assertApproxEquals(PI / 6.0, r2)
    }

    @Test
    fun `wedgeFor last ring uses outermost band`() {
        val w = PolarCellLayout.wedgeFor(0, 2, extents, layout)
        assertApproxEquals(40.0, w.innerRadius)
        assertApproxEquals(50.0, w.outerRadius)
    }

    // --- cellCenter ---

    @Test
    fun `cellCenter ring 0 stitch 0 lies in NE quadrant`() {
        // Stitch 0 center is at theta = PI over 4 CW from 12 o clock.
        // Radius = innerRadius plus half the ringThickness = 25.
        val (x, y) = PolarCellLayout.cellCenter(0, 0, extents, layout)
        assertApproxEquals(100.0 + 25.0 * cos(-PI / 4.0), x)
        assertApproxEquals(100.0 + 25.0 * sin(-PI / 4.0), y)
        assertTrue(x > 100.0)
        assertTrue(y < 100.0)
    }

    @Test
    fun `cellCenter ring 0 stitch 1 lies in SE quadrant`() {
        val (x, y) = PolarCellLayout.cellCenter(1, 0, extents, layout)
        assertTrue(x > 100.0)
        assertTrue(y > 100.0)
    }

    @Test
    fun `cellCenter ring 0 stitch 2 lies in SW quadrant`() {
        val (x, y) = PolarCellLayout.cellCenter(2, 0, extents, layout)
        assertTrue(x < 100.0)
        assertTrue(y > 100.0)
    }

    @Test
    fun `cellCenter ring 0 stitch 3 lies in NW quadrant`() {
        val (x, y) = PolarCellLayout.cellCenter(3, 0, extents, layout)
        assertTrue(x < 100.0)
        assertTrue(y < 100.0)
    }

    @Test
    fun `cellCenter ring 1 has larger radius than ring 0`() {
        val (x0, y0) = PolarCellLayout.cellCenter(0, 0, extents, layout)
        val r0 = distance(x0, y0, layout.cx, layout.cy)
        val (x1, y1) = PolarCellLayout.cellCenter(0, 1, extents, layout)
        val r1 = distance(x1, y1, layout.cx, layout.cy)
        assertTrue(r1 > r0)
        assertApproxEquals(25.0, r0)
        assertApproxEquals(35.0, r1)
    }

    // --- cellRadialUpRotation ---

    @Test
    fun `cellRadialUpRotation returns half-sweep for stitch 0`() {
        val rot = PolarCellLayout.cellRadialUpRotation(0, 0, extents)
        assertApproxEquals(PI / 4.0, rot)
    }

    @Test
    fun `cellRadialUpRotation grows by one full sweep per stitch`() {
        val r0 = PolarCellLayout.cellRadialUpRotation(0, 0, extents)
        val r1 = PolarCellLayout.cellRadialUpRotation(1, 0, extents)
        assertApproxEquals(PI / 2.0, r1 - r0)
    }

    @Test
    fun `cellRadialUpRotation last stitch equals N minus half times sweep`() {
        val rot = PolarCellLayout.cellRadialUpRotation(3, 0, extents)
        assertApproxEquals(3.5 * PI / 2.0, rot)
    }

    @Test
    fun `cellRadialUpRotation is a function of extents only not layout`() {
        val rotRing2 = PolarCellLayout.cellRadialUpRotation(0, 2, extents)
        assertApproxEquals(PI / 12.0, rotRing2)
    }
}

internal const val POLAR_TOL: Double = 1e-9

internal fun assertApproxEquals(
    expected: Double,
    actual: Double,
) {
    assertTrue(
        abs(expected - actual) < POLAR_TOL,
        "expected=$expected actual=$actual diff=${abs(expected - actual)}",
    )
}

internal fun distance(
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
): Double {
    val dx = x1 - x2
    val dy = y1 - y2
    return sqrt(dx * dx + dy * dy)
}
