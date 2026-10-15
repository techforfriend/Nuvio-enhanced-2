package com.nuvio.app.features.player

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTexType
import io.github.peerless2012.ass.AssTrack
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.concurrent.withLock

private val assHandlersByPlayer = Collections.synchronizedMap(WeakHashMap<ExoPlayer, AssHandler>())
private val libassFeedersByPlayer = Collections.synchronizedMap(WeakHashMap<ExoPlayer, LibassDialogueFeeder>())

@OptIn(UnstableApi::class)
internal fun ExoPlayer.Builder.buildWithAssSupportCompat(
    context: Context,
    renderType: AssRenderType = AssRenderType.CUES,
    config: AssHandlerConfig = AssHandlerConfig(),
    dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context),
    extractorsFactory: ExtractorsFactory = DefaultExtractorsFactory(),
    renderersFactory: RenderersFactory = DefaultRenderersFactory(context)
): ExoPlayer {
    val assHandler = AssHandler(renderType, config)
    val dialogueFeeder = LibassDialogueFeeder(assHandler)
    val assSubtitleParserFactory = CompatAssSubtitleParserFactory(assHandler)
    val assExtractorsFactory = extractorsFactory.withAssMkvSupportCompat(
        subtitleParserFactory = assSubtitleParserFactory,
        assHandler = assHandler,
        dialogueFeeder = dialogueFeeder,
    )

    val mediaSourceFactory = DefaultMediaSourceFactory(
        dataSourceFactory,
        assExtractorsFactory
    )
    mediaSourceFactory.setSubtitleParserFactory(assSubtitleParserFactory)

    val player = this
        .setMediaSourceFactory(mediaSourceFactory)
        .setRenderersFactory(renderersFactory.withAssSupport(assHandler))
        .build()

    assHandlersByPlayer[player] = assHandler
    libassFeedersByPlayer[player] = dialogueFeeder

    assHandler.init(player)
    return player
}

internal fun ExoPlayer.getAssHandlerCompat(): AssHandler? = assHandlersByPlayer[this]

internal fun createLibassHandlerConfig(context: Context): AssHandlerConfig {
    val defaults = AssHandlerConfig()
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val totalMemBytes = activityManager?.let { manager ->
        ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem
    } ?: 0L
    val cacheSizeMb = if (activityManager?.isLowRamDevice != true && totalMemBytes >= LIBASS_LARGE_CACHE_MIN_TOTAL_MEM_BYTES) {
        LIBASS_LARGE_CACHE_SIZE_MB
    } else {
        defaults.cacheSize
    }
    return defaults.copy(
        cacheSize = cacheSizeMb,
        maxRenderPixels = LIBASS_MAX_RENDER_PIXELS,
    )
}

private const val LIBASS_MAX_RENDER_PIXELS = 1920 * 1080
private const val LIBASS_LARGE_CACHE_SIZE_MB = 256
private const val LIBASS_LARGE_CACHE_MIN_TOTAL_MEM_BYTES = 5L * 1024L * 1024L * 1024L

@OptIn(UnstableApi::class)
internal fun ExoPlayer.releaseWithAssSupportCompat() {
    val assHandler = assHandlersByPlayer.remove(this)
    val dialogueFeeder = libassFeedersByPlayer.remove(this)
    try {
        release()
    } finally {
        dialogueFeeder?.release()
        assHandler?.release()
    }
}

@OptIn(UnstableApi::class)
private class CompatAssSubtitleParserFactory(
    private val assHandler: AssHandler
) : SubtitleParser.Factory {
    private val delegate = AssSubtitleParserFactory(assHandler)
    private val cueCompositor = LibassCueCompositor(assHandler)

    override fun supportsFormat(format: Format): Boolean {
        return delegate.supportsFormat(normalizeSsaFormat(format))
    }

    override fun getCueReplacementBehavior(format: Format): Int {
        return delegate.getCueReplacementBehavior(normalizeSsaFormat(format))
    }

    override fun create(format: Format): SubtitleParser {
        val normalized = normalizeSsaFormat(format)
        if (normalized.sampleMimeType == MimeTypes.TEXT_SSA && assHandler.renderType == AssRenderType.CUES) {
            val embeddedSubtitles = MimeTypes.VIDEO_MATROSKA.contentEquals(normalized.containerMimeType)
            val track = assHandler.createTrack(normalized)
            return LibassCompositingCueParser(
                assHandler = assHandler,
                track = track,
                segment = embeddedSubtitles,
                compositor = cueCompositor,
            )
        }
        return delegate.create(normalized)
    }

    private fun normalizeSsaFormat(format: Format): Format {
        val isSsaByCodecs = format.codecs == MimeTypes.TEXT_SSA
        val isSsaByMime = format.sampleMimeType == MimeTypes.TEXT_SSA
        if (isSsaByCodecs && !isSsaByMime) {
            return format.buildUpon()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .build()
        }
        return format
    }
}

@OptIn(UnstableApi::class)
private class LibassCompositingCueParser(
    private val assHandler: AssHandler,
    private val track: AssTrack,
    private val segment: Boolean,
    private val compositor: LibassCueCompositor,
) : SubtitleParser {
    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>
    ) {
        if (segment) {
            assHandler.render?.setTrack(track)
            val text = String(data, offset, length, Charsets.UTF_8)
            val fixed = LIBASS_SEGMENT_TIMESTAMP_PATTERN.replace(text) { match ->
                "${match.groupValues[1]}.${match.groupValues[2]}"
            }
            track.readBuffer(fixed.toByteArray())
        } else {
            track.readBuffer(data, offset, length)
        }
        val events = track.getEvents()
        events?.forEach { event ->
            var fadeIn = 0
            var fadeOut = 0
            LIBASS_FADE_PATTERN.find(event.text)?.destructured?.let { (newFadeIn, newFadeOut) ->
                fadeIn = newFadeIn.trim().toInt()
                fadeOut = newFadeOut.trim().toInt()
            }
            val startMs = event.start + fadeIn
            val cues = compositor.render(startMs, event.layer) ?: return@forEach
            output.accept(
                CuesWithTiming(cues, startMs * 1000, (event.duration - fadeIn - fadeOut) * 1000)
            )
        }
        if (segment) {
            track.clearEvent()
        }
    }

    override fun getCueReplacementBehavior(): Int {
        return Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
    }
}

@OptIn(UnstableApi::class)
private class LibassCueCompositor(private val assHandler: AssHandler) {
    private val canvas = Canvas()
    private val paint = Paint()
    private val bounds = Rect()
    private var boundRender: AssRender? = null
    private var hasLast = false
    private var lastBitmap: Bitmap? = null
    private var lastLeft = 0
    private var lastTop = 0

    fun render(timeMs: Long, layer: Int): List<Cue>? {
        val render = assHandler.render ?: return null
        return assHandler.ass.lock.withLock {
            if (render !== boundRender) {
                boundRender = render
                hasLast = false
                lastBitmap = null
            }
            val frame = render.renderFrame(timeMs, AssTexType.BITMAP_ALPHA)
            if (frame == null) {
                hasLast = true
                lastBitmap = null
                return@withLock null
            }
            if (frame.changed == 0) {
                if (!hasLast) return@withLock null
                return@withLock buildCues(lastBitmap, lastLeft, lastTop, layer)
            }
            val images = frame.images ?: return@withLock null
            var composed: Bitmap? = null
            if (images.libassBounds(bounds)) {
                val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
                canvas.setBitmap(bitmap)
                images.drawLibassImages(canvas, paint, bounds.left, bounds.top)
                canvas.setBitmap(null)
                composed = bitmap
            }
            images.recycleLibassBitmaps()
            hasLast = true
            lastBitmap = composed
            lastLeft = bounds.left
            lastTop = bounds.top
            buildCues(composed, lastLeft, lastTop, layer)
        }
    }

    private fun buildCues(bitmap: Bitmap?, left: Int, top: Int, layer: Int): List<Cue> {
        if (bitmap == null) return emptyList()
        val videoWidth = assHandler.videoSize.width.toFloat()
        val videoHeight = assHandler.videoSize.height.toFloat()
        return listOf(
            Cue.Builder()
                .setBitmap(bitmap)
                .setZIndex(layer)
                .setPosition(left / videoWidth)
                .setPositionAnchor(Cue.ANCHOR_TYPE_START)
                .setLine(top / videoHeight, Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(Cue.ANCHOR_TYPE_START)
                .setSize(bitmap.width / videoWidth)
                .setBitmapHeight(bitmap.height / videoHeight)
                .build()
        )
    }
}

@OptIn(UnstableApi::class)
private class LibassDialogueFeeder(private val assHandler: AssHandler) {
    private class Chunk(
        val trackId: String?,
        val startMs: Long,
        val durationMs: Long,
        val data: ByteArray,
    )

    private val pending = ConcurrentLinkedQueue<Chunk>()
    private val scheduled = AtomicBoolean(false)
    private val executorDelegate = lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, LIBASS_FEEDER_THREAD_NAME).apply { isDaemon = true }
        }
    }
    private val executor: ExecutorService by executorDelegate

    @Volatile
    private var released = false

    fun enqueue(trackId: String?, startMs: Long, durationMs: Long, data: ByteArray) {
        if (released) return
        pending.add(Chunk(trackId, startMs, durationMs, data))
        schedule()
    }

    @Synchronized
    fun release() {
        released = true
        pending.clear()
        if (executorDelegate.isInitialized()) {
            executor.shutdown()
        }
    }

    @Synchronized
    private fun schedule() {
        if (released || !scheduled.compareAndSet(false, true)) return
        try {
            executor.execute { drain() }
        } catch (_: RejectedExecutionException) {
            scheduled.set(false)
        }
    }

    private fun drain() {
        scheduled.set(false)
        if (released) {
            pending.clear()
            return
        }
        synchronized(assHandler) {
            val lock = assHandler.ass.lock
            lock.withLock {
                var processed = 0
                while (!released) {
                    val chunk = pending.poll() ?: break
                    assHandler.readTrackDialogue(chunk.trackId, chunk.startMs, chunk.durationMs, chunk.data)
                    processed++
                    if (processed >= LIBASS_FEEDER_BATCH && lock.hasQueuedThreads()) break
                }
            }
        }
        if (!released && pending.isNotEmpty()) {
            schedule()
        }
    }
}

@OptIn(UnstableApi::class)
private class LibassAsyncMatroskaExtractor(
    subtitleParserFactory: SubtitleParser.Factory,
    private val assHandler: AssHandler,
    private val dialogueFeeder: LibassDialogueFeeder,
) : AssMatroskaExtractor(subtitleParserFactory, assHandler) {
    private var asyncOutput: LibassAsyncExtractorOutput? = null

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        if (id != LIBASS_EBML_ELEMENT_ID || assHandler.renderType == AssRenderType.CUES) {
            super.startMasterElement(id, contentPosition, contentSize)
            return
        }
        val current = LIBASS_MKV_OUTPUT_FIELD.get(this) as ExtractorOutput
        val output = asyncOutput?.takeIf { it === current }
            ?: LibassAsyncExtractorOutput(current, this, dialogueFeeder).also { asyncOutput = it }
        LIBASS_MKV_OUTPUT_FIELD.set(this, output)
        super.startMasterElement(id, contentPosition, contentSize)
        LIBASS_MKV_OUTPUT_FIELD.set(this, output)
    }

    fun currentSubtitleSample(): ParsableByteArray =
        LIBASS_MKV_SUBTITLE_SAMPLE_FIELD.get(this) as ParsableByteArray
}

@OptIn(UnstableApi::class)
private class LibassAsyncExtractorOutput(
    private val delegate: ExtractorOutput,
    private val extractor: LibassAsyncMatroskaExtractor,
    private val dialogueFeeder: LibassDialogueFeeder,
) : ExtractorOutput by delegate {
    override fun track(id: Int, type: Int): TrackOutput {
        val output = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_TEXT) {
            LibassAsyncTrackOutput(output, extractor, dialogueFeeder)
        } else {
            output
        }
    }
}

@OptIn(UnstableApi::class)
private class LibassAsyncTrackOutput(
    private val delegate: TrackOutput,
    private val extractor: LibassAsyncMatroskaExtractor,
    private val dialogueFeeder: LibassDialogueFeeder,
) : TrackOutput by delegate {
    private var isAss = false
    private var trackId: String? = null

    override fun format(format: Format) {
        if (format.sampleMimeType == MimeTypes.TEXT_SSA || format.codecs == MimeTypes.TEXT_SSA) {
            isAss = true
            trackId = format.id
        }
        delegate.format(format)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (isAss && timeUs != C.TIME_UNSET) {
            feedDialogue(timeUs)
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun feedDialogue(timeUs: Long) {
        val sample = extractor.currentSubtitleSample()
        val data = sample.data
        val limit = minOf(sample.limit(), data.size)
        val endIndex = findTokenIndex(data, limit, 1)
        val lineIndex = findTokenIndex(data, limit, 2)
        if (endIndex <= 0 || lineIndex <= endIndex) return
        val durationUs = parseTimecodeUs(data.decodeToString(endIndex, lineIndex - 1))
        dialogueFeeder.enqueue(
            trackId = trackId,
            startMs = timeUs / 1000,
            durationMs = durationUs / 1000,
            data = data.copyOfRange(lineIndex, limit),
        )
    }

    private fun findTokenIndex(array: ByteArray, limit: Int, tokenNumber: Int): Int {
        var tokensFound = 0
        for (index in 0 until limit) {
            if (array[index] == LIBASS_COMMA && ++tokensFound == tokenNumber) {
                return index + 1
            }
        }
        return 0
    }

    private fun parseTimecodeUs(timeString: String): Long {
        val matcher = LIBASS_SSA_TIMECODE_PATTERN.matcher(timeString.trim { it <= ' ' })
        if (!matcher.matches()) {
            return C.TIME_UNSET
        }
        var timestampUs =
            Util.castNonNull(matcher.group(1)).toLong() * 60 * 60 * C.MICROS_PER_SECOND
        timestampUs += Util.castNonNull(matcher.group(2)).toLong() * 60 * C.MICROS_PER_SECOND
        timestampUs += Util.castNonNull(matcher.group(3)).toLong() * C.MICROS_PER_SECOND
        timestampUs += Util.castNonNull(matcher.group(4)).toLong() * 10000
        return timestampUs
    }
}

@OptIn(UnstableApi::class)
private fun ExtractorsFactory.withAssMkvSupportCompat(
    subtitleParserFactory: SubtitleParser.Factory,
    assHandler: AssHandler,
    dialogueFeeder: LibassDialogueFeeder,
): ExtractorsFactory {
    return ExtractorsFactory {
        val extractors = createExtractors()
        extractors.forEachIndexed { index, extractor ->
            if (extractor.underlyingImplementation is MatroskaExtractor) {
                extractors[index] = extractor.replaceInnermostExtractor(
                    LibassAsyncMatroskaExtractor(subtitleParserFactory, assHandler, dialogueFeeder),
                )
            }
        }
        extractors
    }
}

private val LIBASS_MKV_OUTPUT_FIELD: Field by lazy {
    MatroskaExtractor::class.java.getDeclaredField("extractorOutput").apply { isAccessible = true }
}

private val LIBASS_MKV_SUBTITLE_SAMPLE_FIELD: Field by lazy {
    MatroskaExtractor::class.java.getDeclaredField("subtitleSample").apply { isAccessible = true }
}

private val LIBASS_SEGMENT_TIMESTAMP_PATTERN = "(\\d+:\\d{2}:\\d{2}):(\\d{2})".toRegex()
private val LIBASS_FADE_PATTERN = """\\fad\((\d+),(\d+)\)""".toRegex()
private val LIBASS_SSA_TIMECODE_PATTERN: Pattern = Pattern.compile("""(?:(\d+):)?(\d+):(\d+)[:.](\d+)""")
private const val LIBASS_COMMA = ','.code.toByte()
private const val LIBASS_EBML_ELEMENT_ID = 0x1A45DFA3
private const val LIBASS_FEEDER_BATCH = 32
private const val LIBASS_FEEDER_THREAD_NAME = "LibassDialogueFeeder"
