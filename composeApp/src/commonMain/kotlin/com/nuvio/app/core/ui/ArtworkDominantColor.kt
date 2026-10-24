package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import coil3.SingletonImageLoader
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.kmpalette.extensions.painter.rememberPainterDominantColorState
import com.kmpalette.DominantColorState
import com.kmpalette.rememberDominantColorState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import com.nuvio.app.features.details.components.loadedBackdropImageBitmap

private const val PALETTE_SAMPLE_PX = 96
private const val CACHE_CAPACITY = 256

private val NoTint = Color.Transparent

object ArtworkDominantColorCache {
    private val entries = LinkedHashMap<String, Color>()

    fun get(url: String): Color? {
        val color = entries.remove(url) ?: return null
        entries[url] = color
        return color
    }

    fun put(url: String, color: Color) {
        entries.remove(url)
        entries[url] = color
        if (entries.size > CACHE_CAPACITY) {
            entries.remove(entries.keys.first())
        }
    }
}

sealed interface ArtworkTint {
    data object Pending : ArtworkTint
    data object None : ArtworkTint
    data class Tinted(val value: Color) : ArtworkTint
}

@Composable
fun rememberArtworkDominantColor(url: String?, enabled: Boolean = true): Color? =
    (rememberArtworkTint(url, enabled) as? ArtworkTint.Tinted)?.value

@Composable
fun rememberArtworkTint(url: String?, enabled: Boolean = true): ArtworkTint {
    val key = url?.takeIf { it.isNotBlank() }
    var color by remember(key) { mutableStateOf(key?.let(ArtworkDominantColorCache::get)) }
    var failed by remember(key) { mutableStateOf(false) }
    val platformContext = LocalPlatformContext.current

    val bitmapState = rememberDominantColorState(
        defaultColor = NoTint,
        defaultOnColor = NoTint,
        isSwatchValid = isBrightDominantSwatch,
    )
    val painterState = rememberPainterDominantColorState(
        defaultColor = NoTint,
        defaultOnColor = NoTint,
        isSwatchValid = isBrightDominantSwatch,
    )

    LaunchedEffect(key, enabled) {
        if (!enabled || key == null || color != null) return@LaunchedEffect
        val sampled = sampleArtworkDominantColor(key, platformContext, bitmapState, painterState)
        if (sampled != null) color = sampled else failed = true
    }

    val resolved = color
    return when {
        key == null -> ArtworkTint.None
        resolved == null -> if (failed) ArtworkTint.None else ArtworkTint.Pending
        resolved == NoTint -> ArtworkTint.None
        else -> ArtworkTint.Tinted(resolved)
    }
}

@Composable
fun PrefetchArtworkDominantColors(urls: List<String>) {
    val platformContext = LocalPlatformContext.current
    val bitmapState = rememberDominantColorState(
        defaultColor = NoTint,
        defaultOnColor = NoTint,
        isSwatchValid = isBrightDominantSwatch,
    )
    val painterState = rememberPainterDominantColorState(
        defaultColor = NoTint,
        defaultOnColor = NoTint,
        isSwatchValid = isBrightDominantSwatch,
    )
    LaunchedEffect(urls) {
        urls.filter { it.isNotBlank() }.distinct().forEach { url ->
            if (ArtworkDominantColorCache.get(url) == null) {
                sampleArtworkDominantColor(url, platformContext, bitmapState, painterState)
            }
        }
    }
}

private suspend fun sampleArtworkDominantColor(
    url: String,
    platformContext: PlatformContext,
    bitmapState: DominantColorState<ImageBitmap>,
    painterState: DominantColorState<Painter>,
): Color? {
    val request = ImageRequest.Builder(platformContext)
        .data(url)
        .size(PALETTE_SAMPLE_PX, PALETTE_SAMPLE_PX)
        .build()
    val result = SingletonImageLoader.get(platformContext).execute(request) as? SuccessResult ?: return null
    val bitmap = loadedBackdropImageBitmap(result)
    val extracted = runCatching {
        if (bitmap != null) {
            bitmapState.updateFrom(bitmap)
            bitmapState.color
        } else {
            painterState.updateFrom(result.image.asPainter(platformContext))
            painterState.color
        }
    }.getOrNull() ?: return null
    val resolved = extracted.brightenedDominant(fallback = NoTint)
    ArtworkDominantColorCache.put(url, resolved)
    return resolved
}
