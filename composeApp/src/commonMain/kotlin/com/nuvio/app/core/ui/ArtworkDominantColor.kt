package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.kmpalette.extensions.painter.rememberPainterDominantColorState
import com.kmpalette.rememberDominantColorState
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

@Composable
fun rememberArtworkDominantColor(url: String?, enabled: Boolean = true): Color? {
    val key = url?.takeIf { it.isNotBlank() }
    var color by remember(key) { mutableStateOf(key?.let(ArtworkDominantColorCache::get)) }
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
        val request = ImageRequest.Builder(platformContext)
            .data(key)
            .size(PALETTE_SAMPLE_PX, PALETTE_SAMPLE_PX)
            .build()
        val result = SingletonImageLoader.get(platformContext).execute(request) as? SuccessResult
            ?: return@LaunchedEffect
        val bitmap = loadedBackdropImageBitmap(result)
        val extracted = runCatching {
            if (bitmap != null) {
                bitmapState.updateFrom(bitmap)
                bitmapState.color
            } else {
                painterState.updateFrom(result.image.asPainter(platformContext))
                painterState.color
            }
        }.getOrNull() ?: return@LaunchedEffect
        val resolved = extracted.brightenedDominant(fallback = NoTint)
        ArtworkDominantColorCache.put(key, resolved)
        color = resolved
    }

    return color?.takeIf { it != NoTint }
}
