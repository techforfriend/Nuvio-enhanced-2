package com.nuvio.app.features.player.seekpreview

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt

internal actual fun openSeekPreviewFrameSource(
    url: String,
    headers: Map<String, String>,
): SeekPreviewFrameSource? {
    val scheme = Uri.parse(url).scheme?.lowercase()
    return when (scheme) {
        "http", "https", "file", null -> RetrieverSeekPreviewFrameSource(url, headers)
        else -> null
    }
}

private class RetrieverSeekPreviewFrameSource(
    private val url: String,
    private val headers: Map<String, String>,
) : SeekPreviewFrameSource {
    private var retriever: MediaMetadataRetriever? = null

    override fun frameAt(positionMs: Long, maxWidthPx: Int): ImageBitmap? {
        val activeRetriever = retriever ?: openRetriever().also { retriever = it }
        val timeUs = positionMs.coerceAtLeast(0L) * 1_000L
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activeRetriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                maxWidthPx,
                maxWidthPx,
            )
        } else {
            activeRetriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.scaledToWidth(maxWidthPx)
        }
        return bitmap?.asImageBitmap()
    }

    override fun close() {
        runCatching { retriever?.release() }
        retriever = null
    }

    private fun openRetriever(): MediaMetadataRetriever {
        val created = MediaMetadataRetriever()
        try {
            val uri = Uri.parse(url)
            if (uri.scheme.equals("file", ignoreCase = true) || uri.scheme == null) {
                created.setDataSource(uri.path ?: url)
            } else {
                created.setDataSource(url, headers)
            }
        } catch (error: Throwable) {
            runCatching { created.release() }
            throw error
        }
        return created
    }
}

private fun Bitmap.scaledToWidth(maxWidthPx: Int): Bitmap {
    if (width <= maxWidthPx) return this
    val height = (height * (maxWidthPx.toFloat() / width)).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, maxWidthPx, height, true).also { scaled ->
        if (scaled !== this) recycle()
    }
}
