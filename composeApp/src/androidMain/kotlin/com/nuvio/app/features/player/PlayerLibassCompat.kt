package com.nuvio.app.features.player

import android.app.ActivityManager
import android.content.Context
import android.view.Choreographer
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleRender
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

private val assHandlersByPlayer = Collections.synchronizedMap(WeakHashMap<ExoPlayer, AssHandler>())

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
    val assSubtitleParserFactory = CompatAssSubtitleParserFactory(assHandler)
    val assExtractorsFactory = extractorsFactory.withAssMkvSupportCompat(
        subtitleParserFactory = assSubtitleParserFactory,
        assHandler = assHandler
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

@OptIn(UnstableApi::class)
internal fun AssSubtitleView.attachVsyncRenderDriver(
    player: ExoPlayer,
    assHandler: AssHandler,
    subtitleDelayUsProvider: () -> Long,
) {
    val render = (0 until childCount)
        .map { index -> getChildAt(index) }
        .firstOrNull { it is AssSubtitleRender } as? AssSubtitleRender
        ?: return
    val driver = LibassVsyncRenderDriver(
        player = player,
        assHandler = assHandler,
        render = render,
        subtitleDelayUsProvider = subtitleDelayUsProvider,
    )
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            driver.start()
        }

        override fun onViewDetachedFromWindow(v: View) {
            driver.stop()
        }
    })
    if (isAttachedToWindow) {
        driver.start()
    }
}

@OptIn(UnstableApi::class)
private class LibassVsyncRenderDriver(
    private val player: ExoPlayer,
    private val assHandler: AssHandler,
    private val render: AssSubtitleRender,
    private val subtitleDelayUsProvider: () -> Long,
) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var lastSourceUs = C.TIME_UNSET
    private var clockUs = 0L
    private var clockFrameNanos = 0L
    private var lastRequestedUs = C.TIME_UNSET
    private var lastRequestFrameNanos = 0L

    fun start() {
        if (running) return
        running = true
        assHandler.videoTimeCallback = null
        lastSourceUs = C.TIME_UNSET
        lastRequestedUs = C.TIME_UNSET
        lastRequestFrameNanos = 0L
        choreographer.postFrameCallback(this)
    }

    fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (assHandler.videoTimeCallback != null) {
            assHandler.videoTimeCallback = null
        }
        val sourceUs = assHandler.videoTime
        if (sourceUs < 0L) {
            lastSourceUs = C.TIME_UNSET
        } else {
            val playing = player.isPlaying
            val hasClock = lastSourceUs != C.TIME_UNSET
            val predictedUs = if (playing && hasClock) {
                clockUs + ((frameTimeNanos - clockFrameNanos) / 1_000L * player.playbackParameters.speed).toLong()
            } else {
                sourceUs
            }
            clockUs = when {
                !playing || !hasClock -> sourceUs
                sourceUs == lastSourceUs -> minOf(predictedUs, sourceUs + LIBASS_MAX_EXTRAPOLATION_US)
                abs(sourceUs - predictedUs) > LIBASS_RESYNC_THRESHOLD_US -> sourceUs
                else -> maxOf(clockUs, predictedUs + (sourceUs - predictedUs) / LIBASS_CLOCK_CORRECTION_DIVISOR)
            }
            lastSourceUs = sourceUs
            clockFrameNanos = frameTimeNanos
            val targetUs = (clockUs - subtitleDelayUsProvider()).coerceAtLeast(0L)
            if (
                targetUs != lastRequestedUs &&
                frameTimeNanos - lastRequestFrameNanos >= LIBASS_MIN_REQUEST_INTERVAL_NANOS
            ) {
                lastRequestedUs = targetUs
                lastRequestFrameNanos = frameTimeNanos
                render.requestRender(targetUs)
            }
        }
        choreographer.postFrameCallback(this)
    }
}

private const val LIBASS_MAX_RENDER_PIXELS = 1920 * 1080
private const val LIBASS_LARGE_CACHE_SIZE_MB = 256
private const val LIBASS_LARGE_CACHE_MIN_TOTAL_MEM_BYTES = 5L * 1024L * 1024L * 1024L
private const val LIBASS_MAX_EXTRAPOLATION_US = 250_000L
private const val LIBASS_RESYNC_THRESHOLD_US = 200_000L
private const val LIBASS_CLOCK_CORRECTION_DIVISOR = 4L
private const val LIBASS_MIN_REQUEST_INTERVAL_NANOS = 14_000_000L

@OptIn(UnstableApi::class)
internal fun ExoPlayer.releaseWithAssSupportCompat() {
    val assHandler = assHandlersByPlayer.remove(this)
    try {
        release()
    } finally {
        assHandler?.release()
    }
}

@OptIn(UnstableApi::class)
private class CompatAssSubtitleParserFactory(
    private val assHandler: AssHandler
) : SubtitleParser.Factory {
    private val delegate = AssSubtitleParserFactory(assHandler)

    override fun supportsFormat(format: Format): Boolean {
        return delegate.supportsFormat(normalizeSsaFormat(format))
    }

    override fun getCueReplacementBehavior(format: Format): Int {
        return delegate.getCueReplacementBehavior(normalizeSsaFormat(format))
    }

    override fun create(format: Format): SubtitleParser {
        return delegate.create(normalizeSsaFormat(format))
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
private fun ExtractorsFactory.withAssMkvSupportCompat(
    subtitleParserFactory: SubtitleParser.Factory,
    assHandler: AssHandler
): ExtractorsFactory {
    return ExtractorsFactory {
        val extractors = createExtractors()
        extractors.forEachIndexed { index, extractor ->
            if (extractor is MatroskaExtractor) {
                extractors[index] = AssMatroskaExtractor(subtitleParserFactory, assHandler)
            }
        }
        extractors
    }
}
