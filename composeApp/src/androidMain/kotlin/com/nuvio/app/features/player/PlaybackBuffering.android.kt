@file:OptIn(UnstableApi::class)

package com.nuvio.app.features.player

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SharedMemory
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

private const val MB = 1024L * 1024L
private const val READ_AHEAD_BLOCK_BYTES = 16 shl 20

internal fun isProgressivePlaybackSource(
    url: String,
    responseHeaders: Map<String, String>,
    streamType: String?,
): Boolean = Util.inferContentTypeForUriAndMimeType(
    Uri.parse(url),
    playbackMediaItemFromUrl(url, responseHeaders, streamType).localConfiguration?.mimeType,
) == C.CONTENT_TYPE_OTHER

internal fun PlayerSettingsUiState.buildPlaybackLoadControl(): DefaultLoadControl {
    val heapLimitBytes = PlaybackMemoryInfo.javaHeapBufferLimitMb() * MB
    val builder = DefaultLoadControl.Builder()
    when {
        customPlaybackBuffersEnabled -> {
            val startMs = playbackStartBufferSeconds * 1000
            val rebufferMs = playbackRebufferSeconds * 1000
            val minMs = maxOf(playbackMinBufferSeconds * 1000, startMs, rebufferMs)
            val maxMs = maxOf(playbackMaxBufferSeconds * 1000, minMs)
            builder
                .setBackBuffer(playbackBackBufferSeconds * 1000, true)
                .setBufferDurationsMs(minMs, maxMs, startMs, rebufferMs)
                .setTargetBufferBytes(minOf(playbackTargetBufferMb * MB, heapLimitBytes).toInt())
        }
        exoNativeMemoryEnabled -> builder
            .setBackBuffer(10_000, true)
            .setBufferDurationsMs(
                120_000,
                600_000,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setTargetBufferBytes(heapLimitBytes.toInt())
        else -> builder
            .setBackBuffer(10_000, true)
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                50_000,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
    }
    return builder.build()
}

private fun PlayerSettingsUiState.nativeReadAheadBytes(): Long = when {
    !exoNativeMemoryEnabled -> 0L
    customPlaybackBuffersEnabled ->
        playbackTargetBufferMb.coerceAtMost(PlaybackMemoryInfo.safeBufferLimitMb()) * MB
    else -> PlaybackMemoryInfo.safeBufferLimitMb() / 2 * MB
}

internal fun DataSource.Factory.withPlaybackBuffering(
    context: Context,
    settings: PlayerSettingsUiState,
    bufferedUrls: Set<String>,
): DataSource.Factory {
    if (bufferedUrls.isEmpty() || (!settings.vodDiskCacheEnabled && !settings.exoNativeMemoryEnabled)) return this
    val plainFactory = this
    val cacheDirectory = context.cacheDir
    var bufferedFactory: DataSource.Factory = plainFactory
    if (settings.vodDiskCacheEnabled) {
        bufferedFactory = CacheDataSource.Factory()
            .setCache(PlaybackDiskCache.get(context, settings))
            .setUpstreamDataSourceFactory(plainFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
    val readAheadBytes = settings.nativeReadAheadBytes()
    if (readAheadBytes > 0L) {
        val upstreamFactory = bufferedFactory
        bufferedFactory = DataSource.Factory {
            NativeReadAheadDataSource(upstreamFactory.createDataSource(), readAheadBytes, cacheDirectory)
        }
    }
    val finalBufferedFactory = bufferedFactory
    return DataSource.Factory {
        SelectiveBufferedDataSource(
            plain = plainFactory.createDataSource(),
            buffered = finalBufferedFactory.createDataSource(),
            bufferedUrls = bufferedUrls,
        )
    }
}

private object PlaybackDiskCache {
    private var cache: SimpleCache? = null

    @Synchronized
    fun get(context: Context, settings: PlayerSettingsUiState): SimpleCache =
        cache ?: run {
            val directory = File(context.cacheDir, "vod_playback_cache").apply { mkdirs() }
            val maxBytes = if (settings.vodDiskCacheAutoSize) {
                (directory.usableSpace / 5L).coerceIn(256L * MB, 10_240L * MB)
            } else {
                settings.vodDiskCacheSizeMb * MB
            }
            SimpleCache(
                directory,
                LeastRecentlyUsedCacheEvictor(maxBytes),
                StandaloneDatabaseProvider(context.applicationContext),
            )
        }.also { cache = it }
}

private class SelectiveBufferedDataSource(
    private val plain: DataSource,
    private val buffered: DataSource,
    private val bufferedUrls: Set<String>,
) : DataSource {
    private var active: DataSource = plain

    override fun addTransferListener(transferListener: TransferListener) {
        plain.addTransferListener(transferListener)
        buffered.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        active = if (dataSpec.uri.toString() in bufferedUrls) buffered else plain
        return active.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active.read(buffer, offset, length)

    override fun getUri(): Uri? = active.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders

    override fun close() {
        active.close()
    }
}

private class NativeBlock(val buffer: ByteBuffer, private val onFree: () -> Unit) {
    fun free() = onFree()
}

private fun allocateNativeBlock(directory: File): NativeBlock {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        val memory = SharedMemory.create("nuvio_read_ahead", READ_AHEAD_BLOCK_BYTES)
        val mapped = memory.mapReadWrite()
        return NativeBlock(mapped) {
            SharedMemory.unmap(mapped)
            memory.close()
        }
    }
    val file = File.createTempFile("read_ahead", ".bin", directory)
    val randomAccessFile = RandomAccessFile(file, "rw")
    val mapped = randomAccessFile.channel.map(FileChannel.MapMode.READ_WRITE, 0, READ_AHEAD_BLOCK_BYTES.toLong())
    file.delete()
    return NativeBlock(mapped) { runCatching { randomAccessFile.close() } }
}

private class NativeReadAheadDataSource(
    private val upstream: DataSource,
    private val capacityBytes: Long,
    private val directory: File,
) : DataSource {
    private class Chunk(val block: NativeBlock) {
        var written = 0
        var consumed = 0
    }

    private val lock = Object()
    private val chunks = ArrayDeque<Chunk>()
    private val freeBlocks = ArrayDeque<NativeBlock>()
    private var queuedBytes = 0L
    private var ended = false
    private var failure: IOException? = null
    private var readerActive = false

    @Volatile
    private var closed = false
    private var reader: Thread? = null
    private var openedUri: Uri? = null
    private var openedHeaders: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        reader?.join(5_000L)
        val length = upstream.open(dataSpec)
        openedUri = upstream.uri
        openedHeaders = upstream.responseHeaders
        synchronized(lock) {
            closed = false
            ended = false
            failure = null
            queuedBytes = 0L
            readerActive = true
        }
        reader = Thread({ fill() }, "nuvio-native-read-ahead").apply {
            isDaemon = true
            start()
        }
        return length
    }

    private fun obtainBlock(): NativeBlock {
        synchronized(lock) { freeBlocks.removeLastOrNull() }?.let { return it }
        return try {
            allocateNativeBlock(directory)
        } catch (error: Throwable) {
            throw IOException(error)
        }
    }

    private fun fill() {
        val scratch = ByteArray(32 * 1024)
        var tail: Chunk? = null
        try {
            while (!closed) {
                synchronized(lock) {
                    while (!closed && queuedBytes >= capacityBytes) lock.wait()
                }
                if (closed) break
                val read = upstream.read(scratch, 0, scratch.size)
                if (read == C.RESULT_END_OF_INPUT) {
                    synchronized(lock) {
                        ended = true
                        lock.notifyAll()
                    }
                    break
                }
                var offset = 0
                while (offset < read) {
                    var current = tail
                    if (current == null || current.written == READ_AHEAD_BLOCK_BYTES) {
                        current = Chunk(obtainBlock())
                        synchronized(lock) { chunks.addLast(current) }
                        tail = current
                    }
                    val chunk: Chunk = current
                    val count = minOf(read - offset, READ_AHEAD_BLOCK_BYTES - chunk.written)
                    chunk.block.buffer.duplicate().apply { position(chunk.written) }.put(scratch, offset, count)
                    synchronized(lock) {
                        chunk.written += count
                        queuedBytes += count
                        lock.notifyAll()
                    }
                    offset += count
                }
            }
        } catch (_: InterruptedException) {
        } catch (error: Throwable) {
            synchronized(lock) {
                if (!closed) failure = error as? IOException ?: IOException(error)
                lock.notifyAll()
            }
        } finally {
            synchronized(lock) {
                readerActive = false
                if (closed) releaseBlocks()
            }
        }
    }

    private fun releaseBlocks() {
        chunks.forEach { it.block.free() }
        chunks.clear()
        freeBlocks.forEach { it.free() }
        freeBlocks.clear()
        queuedBytes = 0L
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        return synchronized(lock) {
            var result = Int.MIN_VALUE
            while (result == Int.MIN_VALUE) {
                val head = chunks.firstOrNull()
                if (head != null && head.consumed < head.written) {
                    val count = minOf(length, head.written - head.consumed)
                    head.block.buffer.duplicate().apply { position(head.consumed) }.get(buffer, offset, count)
                    head.consumed += count
                    queuedBytes -= count
                    if (head.consumed == READ_AHEAD_BLOCK_BYTES) {
                        chunks.removeFirst()
                        freeBlocks.addLast(head.block)
                    }
                    lock.notifyAll()
                    result = count
                    continue
                }
                failure?.let { throw it }
                if (ended) {
                    result = C.RESULT_END_OF_INPUT
                    continue
                }
                try {
                    lock.wait()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException()
                }
            }
            result
        }
    }

    override fun getUri(): Uri? = openedUri

    override fun getResponseHeaders(): Map<String, List<String>> = openedHeaders

    override fun close() {
        synchronized(lock) {
            closed = true
            if (!readerActive) releaseBlocks()
            lock.notifyAll()
        }
        runCatching { upstream.close() }
    }
}
