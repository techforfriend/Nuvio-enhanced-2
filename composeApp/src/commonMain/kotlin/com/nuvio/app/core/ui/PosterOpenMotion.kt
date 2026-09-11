package com.nuvio.app.core.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp

internal object PosterOpenMotion {
    const val DurationMillis = 550
    const val ContentDelayMillis = 400
    private val expansion = CubicBezierEasing(0.35f, 0.625f, 0.192f, 1f)
    private val dissolve = CubicBezierEasing(0.22f, 0.05f, 0.56f, 1f)
    private val backgroundDissolve = CubicBezierEasing(0.17f, 0.21f, 0.43f, 0.97f)
    private val blur = CubicBezierEasing(0.2f, 0f, 0.8f, 1f)

    fun bounds(start: Rect, end: Rect, elapsedMillis: Float): Rect =
        lerp(start, end, expansionProgress(elapsedMillis))

    fun backgroundScale(elapsedMillis: Float): Float =
        1f - 0.1f * expansionProgress(elapsedMillis)

    fun artworkAlpha(elapsedMillis: Float): Float =
        1f - dissolve.transform((elapsedMillis / 260f).coerceIn(0f, 1f))

    fun backgroundAlpha(elapsedMillis: Float): Float =
        1f - backgroundDissolve.transform((elapsedMillis / 300f).coerceIn(0f, 1f))

    fun artworkBlurFraction(elapsedMillis: Float): Float =
        0.14f * blur.transform((elapsedMillis / 200f).coerceIn(0f, 1f))

    fun artworkBlurRadius(artwork: Size, bounds: Size, elapsedMillis: Float): Size {
        val fraction = artworkBlurFraction(elapsedMillis)
        return Size(
            artwork.width * fraction,
            artwork.height * bounds.width / bounds.height * fraction,
        )
    }

    fun backgroundBlurDp(elapsedMillis: Float): Float {
        val progress = (elapsedMillis / 200f).coerceIn(0f, 1f)
        return 16f * progress * progress
    }

    private fun expansionProgress(elapsedMillis: Float): Float =
        expansion.transform((elapsedMillis / DurationMillis).coerceIn(0f, 1f))
}
