package com.nuvio.app.features.player.swdecoder

import android.os.Handler
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.TraceUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.DecoderVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.nuvio.app.core.logging.InAppLogger

@UnstableApi
internal class SoftwareVideoRenderer(
    allowedJoiningTimeMs: Long,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
) : DecoderVideoRenderer(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify) {
    private var decoder: SoftwareVideoDecoder? = null

    override fun getName(): String = TAG

    override fun supportsFormat(format: Format): Int {
        if (SoftwareVideoDecoder.codecNameFor(format.sampleMimeType) == null) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        }
        if (!SoftwareVideoDecoder.isAvailable) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        val formatSupport = if (SoftwareVideoDecoder.needsSoftwareDecoding(format)) {
            C.FORMAT_HANDLED
        } else {
            C.FORMAT_EXCEEDS_CAPABILITIES
        }
        return RendererCapabilities.create(
            formatSupport,
            RendererCapabilities.ADAPTIVE_NOT_SEAMLESS,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED,
        )
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): SoftwareVideoDecoder {
        TraceUtil.beginSection("createSoftwareVideoDecoder")
        try {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)
            InAppLogger.info(
                "ExoPlayer/Android",
                "Software video decoder mime=${format.sampleMimeType} codecs=${format.codecs} " +
                    "size=${format.width}x${format.height} threads=$threads",
            )
            return SoftwareVideoDecoder(format, threads).also { decoder = it }
        } finally {
            TraceUtil.endSection()
        }
    }

    override fun renderOutputBufferToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        val current = decoder ?: run {
            outputBuffer.release()
            throw SoftwareVideoDecoderException("Software video decoder is not initialized")
        }
        try {
            current.renderToSurface(outputBuffer, surface)
        } finally {
            outputBuffer.release()
        }
    }

    override fun setDecoderOutputMode(outputMode: Int) {
        decoder?.outputMode = outputMode
    }

    private companion object {
        const val TAG = "SoftwareVideoRenderer"
        const val MAX_THREADS = 8
    }
}
