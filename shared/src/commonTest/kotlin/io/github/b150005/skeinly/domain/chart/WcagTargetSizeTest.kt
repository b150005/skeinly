package io.github.b150005.skeinly.domain.chart

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the WCAG 2.5.8 (Target Size Minimum, AA) initial-zoom contract for
 * the rect chart editor. The function is the single shared source of truth
 * both the Compose and SwiftUI editors call so a grid cell never renders
 * below the 24-unit minimum touch target by default.
 */
class WcagTargetSizeTest {
    private val tol = 1e-9

    @Test
    fun `default 8x8 grid on a phone canvas needs no zoom`() {
        // base cell = min(1080/8, 1920/8) = 135 px, well above 24dp*3 = 72 px.
        val scale =
            WcagTargetSize.minimumScale(
                gridWidth = 8,
                gridHeight = 8,
                canvasWidthPx = 1080.0,
                canvasHeightPx = 1920.0,
                minTargetPx = 72.0,
                maxScale = 8.0,
            )
        assertEquals(1.0, scale, tol)
    }

    @Test
    fun `large grid below the minimum target returns the exact needed scale`() {
        // base cell = min(360/20, 640/20) = 18 px < 24 ⇒ need 24/18.
        val scale =
            WcagTargetSize.minimumScale(
                gridWidth = 20,
                gridHeight = 20,
                canvasWidthPx = 360.0,
                canvasHeightPx = 640.0,
                minTargetPx = 24.0,
                maxScale = 8.0,
            )
        assertEquals(24.0 / 18.0, scale, tol)
    }

    @Test
    fun `extreme grid clamps the needed scale to maxScale`() {
        // base cell = min(360/200, 640/200) = 1.8 px ⇒ need 24/1.8 = 13.33 > 8.
        val scale =
            WcagTargetSize.minimumScale(
                gridWidth = 200,
                gridHeight = 200,
                canvasWidthPx = 360.0,
                canvasHeightPx = 640.0,
                minTargetPx = 24.0,
                maxScale = 8.0,
            )
        assertEquals(8.0, scale, tol)
    }

    @Test
    fun `raw sub-pixel cell is floored to 1px before computing the needed scale`() {
        // raw base = min(100/1000, 100/1000) = 0.1 px; platforms draw at the
        // 1px floor, so the WCAG math must use 1.0 ⇒ need 24/1 = 24, clamp 8.
        val scale =
            WcagTargetSize.minimumScale(
                gridWidth = 1000,
                gridHeight = 1000,
                canvasWidthPx = 100.0,
                canvasHeightPx = 100.0,
                minTargetPx = 24.0,
                maxScale = 8.0,
            )
        assertEquals(8.0, scale, tol)
    }

    @Test
    fun `cell exactly equal to the minimum target needs no zoom`() {
        // base cell = min(240/10, 240/10) = 24 == minTarget ⇒ 1.0 (not >1).
        val scale =
            WcagTargetSize.minimumScale(
                gridWidth = 10,
                gridHeight = 10,
                canvasWidthPx = 240.0,
                canvasHeightPx = 240.0,
                minTargetPx = 24.0,
                maxScale = 8.0,
            )
        assertEquals(1.0, scale, tol)
    }

    @Test
    fun `cell just below the minimum target returns just above 1`() {
        // base cell = min(239/10, 239/10) = 23.9 px ⇒ need 24/23.9.
        val scale =
            WcagTargetSize.minimumScale(
                gridWidth = 10,
                gridHeight = 10,
                canvasWidthPx = 239.0,
                canvasHeightPx = 239.0,
                minTargetPx = 24.0,
                maxScale = 8.0,
            )
        assertEquals(24.0 / 23.9, scale, tol)
    }

    @Test
    fun `non-square grid uses the tighter dimension for the base cell`() {
        // base cell = min(400/40, 400/10) = min(10, 40) = 10 px ⇒ need 24/10.
        val scale =
            WcagTargetSize.minimumScale(
                gridWidth = 40,
                gridHeight = 10,
                canvasWidthPx = 400.0,
                canvasHeightPx = 400.0,
                minTargetPx = 24.0,
                maxScale = 8.0,
            )
        assertEquals(24.0 / 10.0, scale, tol)
    }

    @Test
    fun `zero or negative grid dimensions return 1`() {
        assertEquals(
            1.0,
            WcagTargetSize.minimumScale(0, 8, 360.0, 640.0, 24.0, 8.0),
            tol,
        )
        assertEquals(
            1.0,
            WcagTargetSize.minimumScale(8, -3, 360.0, 640.0, 24.0, 8.0),
            tol,
        )
    }

    @Test
    fun `zero or negative canvas dimensions return 1`() {
        assertEquals(
            1.0,
            WcagTargetSize.minimumScale(8, 8, 0.0, 640.0, 24.0, 8.0),
            tol,
        )
        assertEquals(
            1.0,
            WcagTargetSize.minimumScale(8, 8, 360.0, -1.0, 24.0, 8.0),
            tol,
        )
    }

    @Test
    fun `non-positive minimum target returns 1`() {
        assertEquals(
            1.0,
            WcagTargetSize.minimumScale(20, 20, 360.0, 640.0, 0.0, 8.0),
            tol,
        )
        assertEquals(
            1.0,
            WcagTargetSize.minimumScale(20, 20, 360.0, 640.0, -24.0, 8.0),
            tol,
        )
    }

    @Test
    fun `maxScale below 1 is treated as no-zoom even when cells are tiny`() {
        // Defensive: a misconfigured cap must never produce a scale < 1.
        val scale =
            WcagTargetSize.minimumScale(
                gridWidth = 200,
                gridHeight = 200,
                canvasWidthPx = 360.0,
                canvasHeightPx = 640.0,
                minTargetPx = 24.0,
                maxScale = 0.5,
            )
        assertEquals(1.0, scale, tol)
    }

    @Test
    fun `non-finite inputs return 1`() {
        assertEquals(
            1.0,
            WcagTargetSize.minimumScale(
                20,
                20,
                Double.POSITIVE_INFINITY,
                640.0,
                24.0,
                8.0,
            ),
            tol,
        )
        assertEquals(
            1.0,
            WcagTargetSize.minimumScale(20, 20, 360.0, Double.NaN, 24.0, 8.0),
            tol,
        )
    }

    @Test
    fun `MIN_TARGET_DP constant is the WCAG 2_5_8 minimum`() {
        assertEquals(24.0, WcagTargetSize.MIN_TARGET_DP, tol)
    }
}
