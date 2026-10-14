package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import com.kmpalette.palette.graphics.Palette
import kotlin.math.abs

private const val MIN_SWATCH_LIGHTNESS = 0.22f
private const val MAX_SWATCH_LIGHTNESS = 0.92f
private const val MIN_SWATCH_SATURATION = 0.08f
private const val TARGET_MIN_LIGHTNESS = 0.45f

val isBrightDominantSwatch: (Palette.Swatch) -> Boolean = { swatch ->
    val hsl = swatch.hsl
    hsl[2] in MIN_SWATCH_LIGHTNESS..MAX_SWATCH_LIGHTNESS && hsl[1] >= MIN_SWATCH_SATURATION
}

fun Color.brightenedDominant(fallback: Color): Color {
    if (this == fallback) return this
    val r = red; val g = green; val b = blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    if (d == 0f) return this
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> ((g - b) / d + (if (g < b) 6f else 0f))
        g -> ((b - r) / d + 2f)
        else -> ((r - g) / d + 4f)
    } * 60f
    val nl = maxOf(l, TARGET_MIN_LIGHTNESS).coerceAtMost(0.8f)
    val ns = s
    val c = (1f - abs(2f * nl - 1f)) * ns
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = nl - c / 2f
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r1 + m, g1 + m, b1 + m, alpha)
}
