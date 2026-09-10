package com.nuvio.app.core.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp

internal object PosterOpenMotion {
    const val DurationMillis = 350
    private val expansion = CubicBezierEasing(0.35f, 0.625f, 0.192f, 1f)
    private val dissolve = CubicBezierEasing(0.22f, 0.05f, 0.56f, 1f)

    fun bounds(start: Rect, end: Rect, elapsedMillis: Float): Rect =
        lerp(start, end, expansion.transform((elapsedMillis / DurationMillis).coerceIn(0f, 1f)))

    fun artworkAlpha(elapsedMillis: Float): Float =
        1f - dissolve.transform((elapsedMillis / 260f).coerceIn(0f, 1f))

    fun backgroundAlpha(elapsedMillis: Float): Float =
        1f - dissolve.transform((elapsedMillis / 240f).coerceIn(0f, 1f))

    fun artworkBlurFraction(elapsedMillis: Float): Float {
        val progress = (elapsedMillis / 180f).coerceIn(0f, 1f)
        return 0.18f * progress * progress
    }

    fun artworkBlurRadius(artwork: Size, viewport: Size, elapsedMillis: Float): Size {
        val fraction = artworkBlurFraction(elapsedMillis)
        return Size(
            artwork.width * fraction,
            artwork.height * viewport.width / viewport.height * fraction,
        )
    }

    fun backgroundBlurDp(elapsedMillis: Float): Float =
        24f * (elapsedMillis / 200f).coerceIn(0f, 1f)
}
