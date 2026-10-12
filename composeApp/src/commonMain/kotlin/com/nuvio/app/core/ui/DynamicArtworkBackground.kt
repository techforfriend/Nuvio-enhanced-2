package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.kmpalette.extensions.painter.rememberPainterDominantColorState
import com.kmpalette.rememberDominantColorState
import com.nuvio.app.features.details.components.loadedBackdropImageBitmap
import com.nuvio.app.features.settings.ThemeSettingsRepository

val LocalDynamicArtworkBackgroundActive = staticCompositionLocalOf { false }

val LocalDynamicArtworkBackgroundColor = compositionLocalOf<Color?> { null }

private const val PALETTE_SAMPLE_PX = 96

private const val DOMINANT_BLEND_FRACTION = 0.42f

private const val COLOR_TRANSITION_MS = 600

@Composable
fun rememberDynamicArtworkBackgroundEnabled(): Boolean {
    val enabled by ThemeSettingsRepository.dynamicArtworkBackgroundEnabled.collectAsState()
    return enabled
}

@Composable
fun DynamicArtworkBackground(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val active = rememberDynamicArtworkBackgroundEnabled()
    if (!active) {
        CompositionLocalProvider(LocalDynamicArtworkBackgroundActive provides false) {
            content()
        }
        return
    }

    val colorScheme = MaterialTheme.colorScheme
    val baseColor = colorScheme.background
    val platformContext = LocalPlatformContext.current
    val normalizedUrl = artworkUrl?.takeIf { it.isNotBlank() }

    var samplePainter by remember(normalizedUrl) { mutableStateOf<Painter?>(null) }
    var sampleImageBitmap by remember(normalizedUrl) { mutableStateOf<ImageBitmap?>(null) }

    val imageBitmapColorState = rememberDominantColorState(
        defaultColor = baseColor,
        defaultOnColor = colorScheme.onBackground,
    )
    val painterColorState = rememberPainterDominantColorState(
        defaultColor = baseColor,
        defaultOnColor = colorScheme.onBackground,
    )

    LaunchedEffect(sampleImageBitmap, samplePainter) {
        val bitmap = sampleImageBitmap
        val painter = samplePainter
        when {
            bitmap != null -> runCatching { imageBitmapColorState.updateFrom(bitmap) }
            painter != null -> runCatching { painterColorState.updateFrom(painter) }
        }
    }

    val extractedColor = if (sampleImageBitmap != null) {
        imageBitmapColorState.color
    } else {
        painterColorState.color
    }

    val targetColor = if (normalizedUrl != null) {
        baseColor.blendTowards(extractedColor, DOMINANT_BLEND_FRACTION)
    } else {
        baseColor
    }

    val washColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = COLOR_TRANSITION_MS, easing = LinearOutSlowInEasing),
        label = "dynamic_artwork_wash_color",
    )

    Box(modifier = modifier.fillMaxSize().background(washColor)) {
        if (normalizedUrl != null) {
            val paletteRequest = remember(platformContext, normalizedUrl) {
                ImageRequest.Builder(platformContext)
                    .data(normalizedUrl)
                    .size(PALETTE_SAMPLE_PX, PALETTE_SAMPLE_PX)
                    .build()
            }
            AsyncImage(
                model = paletteRequest,
                contentDescription = null,
                modifier = Modifier
                    .size(1.dp)
                    .graphicsLayer { alpha = 0f },
                onSuccess = { state ->
                    samplePainter = state.painter
                    sampleImageBitmap = loadedBackdropImageBitmap(state.result)
                },
            )
        }

        CompositionLocalProvider(
            LocalDynamicArtworkBackgroundActive provides true,
            LocalDynamicArtworkBackgroundColor provides washColor,
        ) {
            content()
        }
    }
}

private fun Color.blendTowards(target: Color, fraction: Float): Color {
    val clamped = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (target.red - red) * clamped,
        green = green + (target.green - green) * clamped,
        blue = blue + (target.blue - blue) * clamped,
        alpha = alpha + (target.alpha - alpha) * clamped,
    )
}
