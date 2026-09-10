package com.nuvio.app.core.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PosterOpenMotionTest {
    @Test
    fun `expansion follows the reference curve at the shorter duration`() {
        val start = Rect(738f, 923f, 1068f, 1382f)
        val end = Rect(0f, 177f, 1180f, 2556f)
        val samples = listOf(
            16.3f to 704f, 33f to 642f, 51.3f to 602f, 66.3f to 567f,
            83f to 507f, 99.7f to 441f, 116.3f to 381f, 133f to 326f,
            149.7f to 275f, 166.3f to 232f, 183f to 195f, 199.7f to 162f,
            216.3f to 134f, 233f to 111f, 249.7f to 91f, 266.3f to 73f,
            283f to 60f, 316.3f to 40f, 349.7f to 26f, 383f to 17f,
            416.3f to 11f, 449.7f to 7f, 483f to 4f,
        )
        val errors = samples.map { (millis, left) ->
            val elapsed = millis * PosterOpenMotion.DurationMillis / 550f
            abs(PosterOpenMotion.bounds(start, end, elapsed).left - left)
        }
        assertTrue(errors.max() < 16f)
        assertTrue(sqrt(errors.sumOf { (it * it).toDouble() } / errors.size) < 5.5)
    }

    @Test
    fun `portrait landscape and edge posters start and finish at exact bounds`() {
        val end = Rect(0f, 24f, 1080f, 2340f)
        for (start in listOf(Rect(30f, 650f, 340f, 1100f), Rect(-80f, 850f, 620f, 1240f), Rect(800f, 1850f, 1110f, 2300f))) {
            assertEquals(start, PosterOpenMotion.bounds(start, end, 0f))
            assertEquals(end, PosterOpenMotion.bounds(start, end, PosterOpenMotion.DurationMillis.toFloat()))
            var previousWidth = start.width
            for (millis in 1..PosterOpenMotion.DurationMillis) {
                val bounds = PosterOpenMotion.bounds(start, end, millis.toFloat())
                assertTrue(bounds.width >= previousWidth)
                assertTrue(bounds.width <= end.width)
                previousWidth = bounds.width
            }
        }
    }

    @Test
    fun `poster sized blur preserves the screen space blur at every expansion size`() {
        val viewport = Size(1180f, 2379f)
        for (artwork in listOf(Size(330f, 459f), Size(720f, 405f), Size(300f, 300f))) {
            for (millis in 0..260) {
                val elapsed = millis.toFloat()
                val radius = PosterOpenMotion.artworkBlurRadius(artwork, viewport, elapsed)
                val viewportRadius = viewport.width * PosterOpenMotion.artworkBlurFraction(elapsed)
                assertEquals(viewportRadius, radius.width * viewport.width / artwork.width, 0.0001f)
                assertEquals(viewportRadius, radius.height * viewport.height / artwork.height, 0.0001f)
            }
        }
    }

    @Test
    fun `artwork and background dissolve before expansion settles`() {
        assertEquals(1f, PosterOpenMotion.artworkAlpha(0f))
        assertTrue(PosterOpenMotion.artworkAlpha(150f) in 0.1f..0.4f)
        assertEquals(0f, PosterOpenMotion.artworkAlpha(260f))
        assertEquals(0f, PosterOpenMotion.backgroundAlpha(240f))
    }
}
