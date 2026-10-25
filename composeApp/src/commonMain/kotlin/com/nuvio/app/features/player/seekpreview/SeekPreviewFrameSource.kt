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

internal interface SeekPreviewFeed {
    fun want(positionMs: Long, showingMs: Long?)

    fun prepare()

    fun close()
}

internal expect fun openSeekPreviewFeed(
    url: String,
    headers: Map<String, String>,
    onThumbnail: (positionMs: Long, bitmap: ImageBitmap?, error: String?) -> Unit,
): SeekPreviewFeed?
