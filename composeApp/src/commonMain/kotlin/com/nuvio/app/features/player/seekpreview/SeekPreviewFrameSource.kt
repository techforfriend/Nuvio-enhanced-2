package com.nuvio.app.features.player.seekpreview

import androidx.compose.ui.graphics.ImageBitmap

internal interface SeekPreviewFrameSource {
    fun frameAt(positionMs: Long, maxWidthPx: Int): ImageBitmap?

    fun cancel() = Unit

    fun close()
}

internal expect fun openSeekPreviewFrameSource(
    url: String,
    headers: Map<String, String>,
): SeekPreviewFrameSource?
