package com.nuvio.app.features.player.seekpreview

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.transformer.ExperimentalFrameExtractor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private const val FrameTimeoutSeconds = 20L

internal object SeekPreviewAndroid {
    @Volatile
    internal var appContext: Context? = null
        private set

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }
}

internal actual fun openSeekPreviewFrameSource(
    url: String,
    headers: Map<String, String>,
): SeekPreviewFrameSource? {
    val scheme = Uri.parse(url).scheme?.lowercase()
    if (scheme !in setOf("http", "https", "file", "content", null)) return null
    val context = SeekPreviewAndroid.appContext
    return if (context != null && headers.isEmpty()) {
        ExoSeekPreviewFrameSource(context, url)
    } else {
        RetrieverSeekPreviewFrameSource(url, headers)
    }
}

@OptIn(UnstableApi::class)
private class ExoSeekPreviewFrameSource(
    private val context: Context,
    private val url: String,
) : SeekPreviewFrameSource {
    private var extractor: ExperimentalFrameExtractor? = null

    override fun frameAt(positionMs: Long, maxWidthPx: Int): ImageBitmap? {
        val targetHeight = (maxWidthPx * 9 / 16).coerceAtLeast(16)
        val activeExtractor = extractor ?: onMainThread {
            ExperimentalFrameExtractor(
                context,
                ExperimentalFrameExtractor.Configuration.Builder()
                    .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                    .build(),
            ).also { created ->
                created.setMediaItem(
                    MediaItem.fromUri(url),
                    listOf(Presentation.createForHeight(targetHeight)),
                )
            }
        }.also { extractor = it }
        val future = activeExtractor.getFrame(positionMs.coerceAtLeast(0L))
        return try {
            future.get(FrameTimeoutSeconds, TimeUnit.SECONDS).bitmap.asImageBitmap()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: Throwable) {
            future.cancel(true)
            throw error
        }
    }

    override fun close() {
        runCatching { extractor?.release() }
        extractor = null
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
            when {
                uri.scheme.equals("content", ignoreCase = true) -> {
                    val context = SeekPreviewAndroid.appContext
                        ?: error("no context for content:// stream")
                    created.setDataSource(context, uri)
                }
                uri.scheme.equals("file", ignoreCase = true) || uri.scheme == null ->
                    created.setDataSource(uri.path ?: url)
                else -> created.setDataSource(url, headers)
            }
        } catch (error: Throwable) {
            runCatching { created.release() }
            throw error
        }
        return created
    }
}

private fun <T> onMainThread(block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val result = AtomicReference<Result<T>>()
    val done = CountDownLatch(1)
    Handler(Looper.getMainLooper()).post {
        result.set(runCatching(block))
        done.countDown()
    }
    check(done.await(FrameTimeoutSeconds, TimeUnit.SECONDS)) { "main thread busy" }
    return result.get().getOrThrow()
}

private fun Bitmap.scaledToWidth(maxWidthPx: Int): Bitmap {
    if (width <= maxWidthPx) return this
    val height = (height * (maxWidthPx.toFloat() / width)).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, maxWidthPx, height, true).also { scaled ->
        if (scaled !== this) recycle()
    }
}
