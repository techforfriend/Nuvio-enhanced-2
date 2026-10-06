package com.nuvio.app.features.player.seekpreview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.nuvio.app.core.logging.InAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

private const val FrameMaxWidthPx = 320

private const val MaxCachedFrames = 40

private const val MinBucketMs = 2_000L
private const val MaxBucketMs = 10_000L
private const val BucketsPerTitle = 500L

private const val MaxInitialFailures = 2

internal class SeekPreviewFrame(val positionMs: Long, val bitmap: ImageBitmap)

@Stable
internal class SeekPreviewController(
    private val url: String,
    private val headers: Map<String, String>,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val requests = MutableStateFlow<Long?>(null)

    private val cache = LinkedHashMap<Long, ImageBitmap>()
    private var worker: Job? = null
    private var consecutiveFailures = 0
    private var hasSucceeded = false

    @Volatile
    private var source: SeekPreviewFrameSource? = null
    private var sourceOpened = false

    var frame by mutableStateOf<SeekPreviewFrame?>(null)
        private set

    var isUnavailable by mutableStateOf(false)
        private set

    fun request(positionMs: Long, durationMs: Long) {
        if (isUnavailable || durationMs <= 0L) return
        val bucket = bucketFor(positionMs.coerceIn(0L, durationMs), durationMs)
        val cached = cache.remove(bucket)?.also { cache[bucket] = it }
        if (cached != null) {
            frame = SeekPreviewFrame(bucket, cached)
        } else {
            nearestCached(bucket)?.let { frame = it }
        }
        requests.value = bucket
        if (worker == null) worker = scope.launch { decodeRequests() }
    }

    fun dispose() {
        scope.cancel()
        source?.cancel()
        CoroutineScope(decodeDispatcher).launch {
            runCatching { source?.close() }
            source = null
        }
    }

    private suspend fun decodeRequests() {
        requests.filterNotNull().collect { bucket ->
            if (cache.containsKey(bucket)) return@collect
            val bitmap = try {
                withContext(decodeDispatcher) { decodeFrame(bucket) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                onFailure(error)
                return@collect
            }
            if (bitmap == null) {
                onFailure(null)
                return@collect
            }
            hasSucceeded = true
            consecutiveFailures = 0
            cache[bucket] = bitmap
            while (cache.size > MaxCachedFrames) {
                cache.remove(cache.keys.first())
            }
            val latest = requests.value
            val shown = frame
            if (latest == bucket || shown == null || shown.positionMs != latest) {
                frame = if (latest == bucket) {
                    SeekPreviewFrame(bucket, bitmap)
                } else {
                    latest?.let(::nearestCached) ?: SeekPreviewFrame(bucket, bitmap)
                }
            }
        }
    }

    private fun decodeFrame(positionMs: Long): ImageBitmap? {
        if (!sourceOpened) {
            sourceOpened = true
            source = openSeekPreviewFrameSource(url, headers)
        }
        val activeSource = source ?: throw UnsupportedSeekPreviewSource
        return activeSource.frameAt(positionMs, FrameMaxWidthPx)
    }

    private fun onFailure(error: Throwable?) {
        consecutiveFailures++
        if (error === UnsupportedSeekPreviewSource) {
            isUnavailable = true
        } else if (!hasSucceeded && consecutiveFailures >= MaxInitialFailures) {
            InAppLogger.info(
                "Player/SeekPreview",
                "disabled for this stream after $consecutiveFailures failures: ${error?.message ?: "no frame"}",
            )
            isUnavailable = true
        }
        if (isUnavailable) {
            frame = null
            worker?.cancel()
        }
    }

    private fun nearestCached(bucket: Long): SeekPreviewFrame? {
        var bestKey: Long? = null
        var bestDistance = Long.MAX_VALUE
        for (key in cache.keys) {
            val distance = abs(key - bucket)
            if (distance < bestDistance) {
                bestDistance = distance
                bestKey = key
            }
        }
        val key = bestKey ?: return null
        val bitmap = cache[key] ?: return null
        return SeekPreviewFrame(key, bitmap)
    }

    private object UnsupportedSeekPreviewSource : RuntimeException("no frame source for this stream")
}

internal fun bucketFor(positionMs: Long, durationMs: Long): Long {
    val bucketMs = (durationMs / BucketsPerTitle).coerceIn(MinBucketMs, MaxBucketMs)
    return (positionMs / bucketMs) * bucketMs
}

@Composable
internal fun rememberSeekPreviewController(
    url: String?,
    headers: Map<String, String>,
): SeekPreviewController? {
    val controller = remember(url, headers) {
        url?.let { SeekPreviewController(it, headers) }
    }
    DisposableEffect(controller) {
        onDispose { controller?.dispose() }
    }
    return controller
}
