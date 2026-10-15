package com.nuvio.app.features.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTex
import io.github.peerless2012.ass.AssTexType
import io.github.peerless2012.ass.AssTrack
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.type.AssRenderType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

@OptIn(UnstableApi::class)
internal class LibassOverlayView(
    context: Context,
    private val player: ExoPlayer,
    private val assHandler: AssHandler,
    private val subtitleDelayUsProvider: () -> Long,
) : FrameLayout(context), Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private val canvasLayer: LibassCanvasLayer?
    private val glLayer: LibassGlLayer?
    private var engine: LibassOverlayEngine? = null
    private var running = false
    private var lastSourceUs = C.TIME_UNSET
    private var clockUs = 0L
    private var clockFrameNanos = 0L
    private var lastIndex = LIBASS_NO_INDEX
    private var lastSubtitleUs = 0L
    private var lastFrameDurationUs = 0.0
    private var lastRender: AssRender? = null
    private var lastTrack: AssTrack? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var shownContentId = LIBASS_UNKNOWN_CONTENT

    init {
        if (assHandler.renderType == AssRenderType.OVERLAY_OPEN_GL) {
            val layer = LibassGlLayer(context)
            glLayer = layer
            canvasLayer = null
            addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            val layer = LibassCanvasLayer(context)
            canvasLayer = layer
            glLayer = null
            addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (running) return
        running = true
        engine = LibassOverlayEngine(assHandler, prepareForHwui = canvasLayer != null).also { it.start() }
        lastSourceUs = C.TIME_UNSET
        lastIndex = LIBASS_NO_INDEX
        lastRender = null
        lastTrack = null
        shownContentId = LIBASS_UNKNOWN_CONTENT
        choreographer.postFrameCallback(this)
    }

    private fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(this)
        engine?.stop()
        engine = null
        canvasLayer?.clear()
        glLayer?.clear()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val activeEngine = engine ?: return
        canvasLayer?.onVsync()
        try {
            drive(activeEngine, frameTimeNanos)
        } catch (error: Exception) {
            Log.w(LIBASS_OVERLAY_TAG, "Overlay frame failed", error)
        }
        choreographer.postFrameCallback(this)
    }

    private fun drive(activeEngine: LibassOverlayEngine, frameTimeNanos: Long) {
        val render = assHandler.render
        val track = assHandler.track
        if (render == null || track == null) {
            lastRender = render
            lastTrack = track
            lastIndex = LIBASS_NO_INDEX
            if (shownContentId != LIBASS_EMPTY_CONTENT) {
                activeEngine.flush()
                shownContentId = LIBASS_EMPTY_CONTENT
                present(null)
            }
            return
        }

        val sourceUs = assHandler.videoTime
        if (sourceUs < 0L) {
            lastSourceUs = C.TIME_UNSET
            return
        }
        val playing = player.isPlaying
        val speed = player.playbackParameters.speed
        val hasClock = lastSourceUs != C.TIME_UNSET
        val predictedUs = if (playing && hasClock) {
            clockUs + ((frameTimeNanos - clockFrameNanos) / 1_000L * speed).toLong()
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

        val subtitleUs = (clockUs - subtitleDelayUsProvider()).coerceAtLeast(0L)
        val frameRate = player.videoFormat?.frameRate ?: Format.NO_VALUE.toFloat()
        val frameDurationUs = if (frameRate >= LIBASS_MIN_FRAME_RATE && frameRate <= LIBASS_MAX_FRAME_RATE) {
            1_000_000.0 / frameRate
        } else {
            LIBASS_FALLBACK_FRAME_DURATION_US
        }
        val index = floor(subtitleUs / frameDurationUs).toLong()
        val viewWidth = width
        val viewHeight = height
        val flush = lastIndex == LIBASS_NO_INDEX ||
            render !== lastRender ||
            track !== lastTrack ||
            frameDurationUs != lastFrameDurationUs ||
            viewWidth != lastWidth ||
            viewHeight != lastHeight ||
            index < lastIndex ||
            subtitleUs - lastSubtitleUs > LIBASS_FLUSH_JUMP_US
        lastIndex = index
        lastSubtitleUs = subtitleUs
        lastFrameDurationUs = frameDurationUs
        lastRender = render
        lastTrack = track
        lastWidth = viewWidth
        lastHeight = viewHeight
        if (flush) {
            activeEngine.flush()
        }

        activeEngine.update(index, frameDurationUs, playing, speed, viewWidth, viewHeight)
        val frame = activeEngine.poll(index) ?: return
        if (frame.contentId == shownContentId) {
            frame.buffer?.release()
            return
        }
        shownContentId = frame.contentId
        present(frame.buffer)
    }

    private fun present(buffer: LibassFrameBuffer?) {
        val canvas = canvasLayer
        val gl = glLayer
        when {
            canvas != null -> canvas.present(buffer)
            gl != null -> gl.present(buffer)
            else -> buffer?.release()
        }
    }
}

private class LibassQueuedFrame(
    val index: Long,
    val buffer: LibassFrameBuffer?,
    val contentId: Long,
)

private class LibassFrameBuffer(private val owner: LibassOverlayEngine) {
    var refs = 0
    var bitmap: Bitmap? = null
        private set
    var left = 0
        private set
    var top = 0
        private set
    var renderWidth = 0
        private set
    var renderHeight = 0
        private set
    var contentId = LIBASS_EMPTY_CONTENT
        private set

    fun retain() = owner.retainBuffer(this)

    fun release() = owner.releaseBuffer(this)

    fun composite(
        images: Array<AssTex>,
        renderWidth: Int,
        renderHeight: Int,
        contentId: Long,
        canvas: Canvas,
        paint: Paint,
        bounds: Rect,
        prepareForHwui: Boolean,
    ): Boolean {
        if (!images.libassBounds(bounds)) return false
        val target = ensureBitmap(bounds.width(), bounds.height(), renderWidth)
        canvas.setBitmap(target)
        images.drawLibassImages(canvas, paint, bounds.left, bounds.top)
        canvas.setBitmap(null)
        left = bounds.left
        top = bounds.top
        this.renderWidth = renderWidth
        this.renderHeight = renderHeight
        this.contentId = contentId
        if (prepareForHwui) {
            target.prepareToDraw()
        }
        return true
    }

    private fun ensureBitmap(width: Int, height: Int, rowWidth: Int): Bitmap {
        val requiredBytes = width.toLong() * height.toLong() * 4L
        val existing = bitmap
        if (existing != null && !existing.isRecycled && existing.allocationByteCount >= requiredBytes) {
            if (existing.width != width || existing.height != height) {
                existing.reconfigure(width, height, Bitmap.Config.ARGB_8888)
            }
            existing.eraseColor(Color.TRANSPARENT)
            return existing
        }
        val allocationWidth = maxOf(width, rowWidth)
        val allocationHeight = ((height + LIBASS_BUFFER_ROW_STEP - 1) / LIBASS_BUFFER_ROW_STEP) * LIBASS_BUFFER_ROW_STEP
        val created = Bitmap.createBitmap(allocationWidth, allocationHeight, Bitmap.Config.ARGB_8888)
        if (created.width != width || created.height != height) {
            created.reconfigure(width, height, Bitmap.Config.ARGB_8888)
        }
        bitmap = created
        return created
    }
}

@OptIn(UnstableApi::class)
private class LibassOverlayEngine(
    private val assHandler: AssHandler,
    private val prepareForHwui: Boolean,
) : Handler.Callback {
    private val lock = Any()
    private val pool = ArrayList<LibassFrameBuffer>(LIBASS_MAX_FRAME_BUFFERS)
    private val queue = ArrayDeque<LibassQueuedFrame>()
    private var thread: HandlerThread? = null

    @Volatile
    private var handler: Handler? = null
    private var running = false
    private var generation = 0
    private var immediateNext = true
    private var frameIndex = LIBASS_NO_INDEX
    private var frameDurationUs = 0.0
    private var playing = false
    private var speed = 1f
    private var viewWidth = 0
    private var viewHeight = 0
    private var lastQueuedIndex = LIBASS_NO_INDEX
    private var lastQueuedContentId = LIBASS_UNKNOWN_CONTENT
    private var waitingForBuffer = false

    private var libassPrevious: LibassFrameBuffer? = null
    private var libassPreviousKnown = false
    private var boundRender: AssRender? = null
    private var renderCostUs = LIBASS_INITIAL_RENDER_COST_US
    private var nextContentId = 1L
    private val compositeCanvas = Canvas()
    private val compositePaint = Paint()
    private val compositeBounds = Rect()

    fun start() {
        val renderThread = HandlerThread(LIBASS_RENDER_THREAD_NAME, Process.THREAD_PRIORITY_DISPLAY)
        renderThread.start()
        synchronized(lock) {
            thread = renderThread
            handler = Handler(renderThread.looper, this)
            running = true
        }
    }

    fun stop() {
        val renderThread: HandlerThread?
        synchronized(lock) {
            running = false
            clearQueueLocked()
            pool.clear()
            renderThread = thread
            thread = null
            handler = null
        }
        renderThread?.quitSafely()
    }

    fun flush() {
        synchronized(lock) {
            generation++
            immediateNext = true
            lastQueuedIndex = LIBASS_NO_INDEX
            lastQueuedContentId = LIBASS_UNKNOWN_CONTENT
            clearQueueLocked()
        }
        kick()
    }

    fun update(index: Long, frameDurationUs: Double, playing: Boolean, speed: Float, width: Int, height: Int) {
        val shouldKick: Boolean
        synchronized(lock) {
            shouldKick = index != frameIndex ||
                playing != this.playing ||
                speed != this.speed ||
                frameDurationUs != this.frameDurationUs ||
                width != viewWidth ||
                height != viewHeight ||
                immediateNext ||
                waitingForBuffer
            frameIndex = index
            this.frameDurationUs = frameDurationUs
            this.playing = playing
            this.speed = speed
            viewWidth = width
            viewHeight = height
        }
        if (shouldKick) kick()
    }

    fun poll(index: Long): LibassQueuedFrame? {
        synchronized(lock) {
            var chosen: LibassQueuedFrame? = null
            while (queue.isNotEmpty() && queue.first().index <= index) {
                val next = queue.removeFirst()
                chosen?.buffer?.let { releaseLocked(it) }
                chosen = next
            }
            return chosen
        }
    }

    fun retainBuffer(buffer: LibassFrameBuffer) {
        synchronized(lock) {
            buffer.refs++
        }
    }

    fun releaseBuffer(buffer: LibassFrameBuffer) {
        val shouldKick: Boolean
        synchronized(lock) {
            shouldKick = releaseLocked(buffer)
        }
        if (shouldKick) kick()
    }

    private fun releaseLocked(buffer: LibassFrameBuffer): Boolean {
        if (buffer.refs > 0) buffer.refs--
        if (buffer.refs == 0 && waitingForBuffer) {
            waitingForBuffer = false
            return running
        }
        return false
    }

    private fun clearQueueLocked() {
        while (queue.isNotEmpty()) {
            queue.removeFirst().buffer?.let { releaseLocked(it) }
        }
    }

    private fun kick() {
        val workHandler = handler ?: return
        if (!workHandler.hasMessages(LIBASS_MSG_WORK)) {
            workHandler.sendEmptyMessage(LIBASS_MSG_WORK)
        }
    }

    private fun acquireBuffer(): LibassFrameBuffer? {
        synchronized(lock) {
            if (!running) return null
            for (buffer in pool) {
                if (buffer.refs == 0) {
                    buffer.refs = 1
                    return buffer
                }
            }
            if (pool.size >= LIBASS_MAX_FRAME_BUFFERS) {
                waitingForBuffer = true
                return null
            }
            val created = LibassFrameBuffer(this)
            created.refs = 1
            pool.add(created)
            return created
        }
    }

    override fun handleMessage(msg: Message): Boolean {
        if (msg.what != LIBASS_MSG_WORK) return false
        var steps = 0
        while (renderStepSafely()) {
            steps++
            if (steps >= LIBASS_MAX_STEPS_PER_MESSAGE) {
                kick()
                break
            }
        }
        return true
    }

    private fun renderStepSafely(): Boolean = try {
        renderStep()
    } catch (error: Exception) {
        Log.w(LIBASS_OVERLAY_TAG, "Overlay render failed", error)
        false
    }

    private fun renderStep(): Boolean {
        val render = assHandler.render
        val track = assHandler.track
        if (render !== boundRender) {
            setLibassPrevious(null, known = false)
            boundRender = render
        }
        if (render == null || track == null) return false

        val stepGeneration: Int
        val index: Long
        val durationUs: Double
        val isPlaying: Boolean
        val playbackSpeed: Float
        val width: Int
        val height: Int
        val immediate: Boolean
        val queuedIndex: Long
        synchronized(lock) {
            if (!running || frameIndex == LIBASS_NO_INDEX || frameDurationUs <= 0.0) return false
            stepGeneration = generation
            index = frameIndex
            durationUs = frameDurationUs
            isPlaying = playing
            playbackSpeed = speed
            width = viewWidth
            height = viewHeight
            immediate = immediateNext
            queuedIndex = lastQueuedIndex
        }
        if (width <= 0 || height <= 0) return false

        val lead = if (!isPlaying || immediate) 0L else leadFrames(playbackSpeed, durationUs)
        var target = index + lead
        if (queuedIndex != LIBASS_NO_INDEX && queuedIndex + 1 > target) {
            target = queuedIndex + 1
        }
        val limit = if (isPlaying) index + lead + LIBASS_LOOKAHEAD_EXTRA_FRAMES else index
        if (target > limit) return false

        val buffer = acquireBuffer() ?: return false
        try {
            val renderSize = assHandler.computeRenderSize(width, height)
            if (!libassPreviousKnown) {
                render.setFrameSize(1, 1)
            }
            render.setFrameSize(renderSize.width, renderSize.height)
            val timeUs = (target * durationUs).toLong()
            val startedNanos = SystemClock.elapsedRealtimeNanos()
            val frame = render.renderFrame(timeUs / 1_000L, AssTexType.BITMAP_ALPHA)
            val result: LibassFrameBuffer?
            if (frame == null) {
                result = null
                setLibassPrevious(null, known = true)
            } else if (frame.changed == 0 && libassPreviousKnown) {
                result = libassPrevious
            } else {
                val images = frame.images
                result = if (
                    images != null &&
                    buffer.composite(
                        images = images,
                        renderWidth = renderSize.width,
                        renderHeight = renderSize.height,
                        contentId = nextContentId++,
                        canvas = compositeCanvas,
                        paint = compositePaint,
                        bounds = compositeBounds,
                        prepareForHwui = prepareForHwui,
                    )
                ) {
                    buffer
                } else {
                    null
                }
                images?.recycleLibassBitmaps()
                setLibassPrevious(result, known = true)
            }
            recordRenderCost((SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000L)
            enqueue(stepGeneration, target, result)
        } finally {
            buffer.release()
        }
        return true
    }

    private fun enqueue(stepGeneration: Int, index: Long, result: LibassFrameBuffer?) {
        val contentId = result?.contentId ?: LIBASS_EMPTY_CONTENT
        synchronized(lock) {
            if (!running || stepGeneration != generation) return
            immediateNext = false
            lastQueuedIndex = index
            if (contentId == lastQueuedContentId) return
            lastQueuedContentId = contentId
            if (result != null) result.refs++
            queue.addLast(LibassQueuedFrame(index, result, contentId))
        }
    }

    private fun setLibassPrevious(buffer: LibassFrameBuffer?, known: Boolean) {
        val previous = libassPrevious
        if (buffer !== previous) {
            buffer?.retain()
            libassPrevious = buffer
            previous?.release()
        }
        libassPreviousKnown = known
    }

    private fun recordRenderCost(costUs: Long) {
        val sample = costUs.toDouble()
        renderCostUs = if (sample > renderCostUs) {
            renderCostUs + (sample - renderCostUs) / 2.0
        } else {
            renderCostUs + (sample - renderCostUs) / 8.0
        }
    }

    private fun leadFrames(playbackSpeed: Float, durationUs: Double): Long {
        val neededUs = renderCostUs * playbackSpeed.coerceAtLeast(0.1f) + LIBASS_PRESENT_MARGIN_US
        return ceil(neededUs / durationUs).toLong().coerceIn(1L, LIBASS_MAX_LEAD_FRAMES)
    }
}

private class LibassCanvasLayer(context: Context) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var current: LibassFrameBuffer? = null
    private var retired: LibassFrameBuffer? = null
    private var retiredTicks = 0

    init {
        setWillNotDraw(false)
    }

    fun present(buffer: LibassFrameBuffer?) {
        retired?.release()
        retired = current
        retiredTicks = LIBASS_RETIRE_TICKS
        current = buffer
        invalidate()
    }

    fun onVsync() {
        if (retired == null) return
        retiredTicks--
        if (retiredTicks <= 0) {
            retired?.release()
            retired = null
        }
    }

    fun clear() {
        current?.release()
        current = null
        retired?.release()
        retired = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = current ?: return
        val bitmap = frame.bitmap ?: return
        if (bitmap.isRecycled || frame.renderWidth <= 0 || frame.renderHeight <= 0) return
        val scaleX = width.toFloat() / frame.renderWidth
        val scaleY = height.toFloat() / frame.renderHeight
        destination.set(
            frame.left * scaleX,
            frame.top * scaleY,
            (frame.left + bitmap.width) * scaleX,
            (frame.top + bitmap.height) * scaleY,
        )
        canvas.drawBitmap(bitmap, null, destination, paint)
    }
}

private class LibassGlLayer(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var renderThread: LibassGlThread? = null
    private var latest: LibassFrameBuffer? = null
    private var hasLatest = false

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun present(buffer: LibassFrameBuffer?) {
        val previous = latest
        latest = buffer
        hasLatest = true
        renderThread?.let { thread ->
            buffer?.retain()
            thread.submit(buffer)
        }
        previous?.release()
    }

    fun clear() {
        latest?.release()
        latest = null
        hasLatest = false
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val thread = LibassGlThread(surface, width, height)
        renderThread = thread
        thread.startRendering()
        if (hasLatest) {
            val buffer = latest
            buffer?.retain()
            thread.submit(buffer)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderThread?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val thread = renderThread ?: return true
        renderThread = null
        thread.shutdown()
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
    }
}

private class LibassGlThread(
    private val surfaceTexture: SurfaceTexture,
    private var surfaceWidth: Int,
    private var surfaceHeight: Int,
) : HandlerThread(LIBASS_GL_THREAD_NAME, Process.THREAD_PRIORITY_DISPLAY), Handler.Callback {
    private val pendingLock = Any()
    private var pendingFrame: LibassFrameBuffer? = null
    private var hasPendingFrame = false
    private var handler: Handler? = null
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var positionLocation = 0
    private var texCoordLocation = 0
    private var textureLocation = 0
    private var textureId = 0
    private var textureWidth = 0
    private var textureHeight = 0
    private var hasContent = false
    private var contentLeft = 0
    private var contentTop = 0
    private var contentWidth = 0
    private var contentHeight = 0
    private var contentRenderWidth = 0
    private var contentRenderHeight = 0
    private var failed = false
    private var released = false
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun startRendering() {
        start()
        val threadHandler = Handler(looper, this)
        handler = threadHandler
        threadHandler.sendEmptyMessage(LIBASS_GL_MSG_INIT)
    }

    fun submit(buffer: LibassFrameBuffer?) {
        synchronized(pendingLock) {
            if (hasPendingFrame) pendingFrame?.release()
            pendingFrame = buffer
            hasPendingFrame = true
        }
        val threadHandler = handler ?: return
        threadHandler.removeMessages(LIBASS_GL_MSG_DRAW)
        threadHandler.sendEmptyMessage(LIBASS_GL_MSG_DRAW)
    }

    fun resize(width: Int, height: Int) {
        handler?.obtainMessage(LIBASS_GL_MSG_RESIZE, width, height)?.sendToTarget()
    }

    fun shutdown() {
        handler?.sendEmptyMessage(LIBASS_GL_MSG_RELEASE)
    }

    override fun handleMessage(msg: Message): Boolean {
        if (released) return true
        try {
            when (msg.what) {
                LIBASS_GL_MSG_INIT -> {
                    initialize()
                    consumePending()
                    draw()
                }
                LIBASS_GL_MSG_DRAW -> {
                    consumePending()
                    draw()
                }
                LIBASS_GL_MSG_RESIZE -> {
                    surfaceWidth = msg.arg1
                    surfaceHeight = msg.arg2
                    draw()
                }
                LIBASS_GL_MSG_RELEASE -> releaseInternal()
            }
        } catch (error: Exception) {
            Log.w(LIBASS_OVERLAY_TAG, "GL overlay failed", error)
            failed = true
            if (msg.what == LIBASS_GL_MSG_RELEASE) {
                surfaceTexture.release()
                quit()
            }
        }
        return true
    }

    private fun initialize() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        eglDisplay = display
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        check(
            EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, configCount, 0) && configCount[0] > 0
        ) { "eglChooseConfig failed" }
        val config = checkNotNull(configs[0])
        val context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglContext = context
        val surface = EGL14.eglCreateWindowSurface(display, config, surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        eglSurface = surface
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, LIBASS_GL_VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, LIBASS_GL_FRAGMENT_SHADER)
        val linked = GLES20.glCreateProgram()
        GLES20.glAttachShader(linked, vertexShader)
        GLES20.glAttachShader(linked, fragmentShader)
        GLES20.glLinkProgram(linked)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        val status = IntArray(1)
        GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(linked)
            GLES20.glDeleteProgram(linked)
            throw IllegalStateException(log)
        }
        program = linked
        positionLocation = GLES20.glGetAttribLocation(linked, "aPosition")
        texCoordLocation = GLES20.glGetAttribLocation(linked, "aTexCoord")
        textureLocation = GLES20.glGetUniformLocation(linked, "uTexture")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException(log)
        }
        return shader
    }

    private fun consumePending() {
        val frame: LibassFrameBuffer?
        val hasFrame: Boolean
        synchronized(pendingLock) {
            frame = pendingFrame
            hasFrame = hasPendingFrame
            pendingFrame = null
            hasPendingFrame = false
        }
        if (!hasFrame) return
        try {
            if (!failed && eglSurface != EGL14.EGL_NO_SURFACE) upload(frame)
        } finally {
            frame?.release()
        }
    }

    private fun upload(frame: LibassFrameBuffer?) {
        val bitmap = frame?.bitmap
        if (frame == null || bitmap == null || bitmap.isRecycled || frame.renderWidth <= 0 || frame.renderHeight <= 0) {
            hasContent = false
            return
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        if (bitmap.width == textureWidth && bitmap.height == textureHeight) {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        } else {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            textureWidth = bitmap.width
            textureHeight = bitmap.height
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        contentLeft = frame.left
        contentTop = frame.top
        contentWidth = bitmap.width
        contentHeight = bitmap.height
        contentRenderWidth = frame.renderWidth
        contentRenderHeight = frame.renderHeight
        hasContent = true
    }

    private fun draw() {
        if (failed || eglSurface == EGL14.EGL_NO_SURFACE) return
        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) return
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (hasContent) {
            val scaleX = width.toFloat() / contentRenderWidth
            val scaleY = height.toFloat() / contentRenderHeight
            val left = contentLeft * scaleX / width * 2f - 1f
            val right = (contentLeft + contentWidth) * scaleX / width * 2f - 1f
            val top = 1f - contentTop * scaleY / height * 2f
            val bottom = 1f - (contentTop + contentHeight) * scaleY / height * 2f
            vertices.position(0)
            vertices.put(left).put(top).put(0f).put(0f)
            vertices.put(right).put(top).put(1f).put(0f)
            vertices.put(left).put(bottom).put(0f).put(1f)
            vertices.put(right).put(bottom).put(1f).put(1f)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(textureLocation, 0)
            vertices.position(0)
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(positionLocation)
            vertices.position(2)
            GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(texCoordLocation)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionLocation)
            GLES20.glDisableVertexAttribArray(texCoordLocation)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun releaseInternal() {
        released = true
        synchronized(pendingLock) {
            if (hasPendingFrame) pendingFrame?.release()
            pendingFrame = null
            hasPendingFrame = false
        }
        val display = eglDisplay
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (eglSurface != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT) {
                if (EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) {
                    if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                    if (program != 0) GLES20.glDeleteProgram(program)
                }
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglReleaseThread()
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        textureId = 0
        program = 0
        surfaceTexture.release()
        quit()
    }
}

internal fun Array<AssTex>.libassBounds(out: Rect): Boolean {
    var left = Int.MAX_VALUE
    var top = Int.MAX_VALUE
    var right = Int.MIN_VALUE
    var bottom = Int.MIN_VALUE
    for (image in this) {
        val bitmap = image.bitmap ?: continue
        if (bitmap.isRecycled) continue
        val imageWidth = bitmap.width
        val imageHeight = bitmap.height
        if (imageWidth <= 0 || imageHeight <= 0) continue
        if (image.x < left) left = image.x
        if (image.y < top) top = image.y
        if (image.x + imageWidth > right) right = image.x + imageWidth
        if (image.y + imageHeight > bottom) bottom = image.y + imageHeight
    }
    if (left >= right || top >= bottom) return false
    out.set(left, top, right, bottom)
    return true
}

internal fun Array<AssTex>.drawLibassImages(canvas: Canvas, paint: Paint, originX: Int, originY: Int) {
    for (image in this) {
        val bitmap = image.bitmap ?: continue
        if (bitmap.isRecycled) continue
        val color = image.color
        val red = (color ushr 24) and 0xFF
        val green = (color ushr 16) and 0xFF
        val blue = (color ushr 8) and 0xFF
        val alpha = 0xFF - (color and 0xFF)
        paint.color = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        canvas.drawBitmap(bitmap, (image.x - originX).toFloat(), (image.y - originY).toFloat(), paint)
    }
}

internal fun Array<AssTex>.recycleLibassBitmaps() {
    for (image in this) {
        image.bitmap?.recycle()
    }
}

private const val LIBASS_OVERLAY_TAG = "LibassOverlay"
private const val LIBASS_RENDER_THREAD_NAME = "LibassOverlayRender"
private const val LIBASS_GL_THREAD_NAME = "LibassGlOverlay"
private const val LIBASS_MAX_EXTRAPOLATION_US = 250_000L
private const val LIBASS_RESYNC_THRESHOLD_US = 200_000L
private const val LIBASS_CLOCK_CORRECTION_DIVISOR = 4L
private const val LIBASS_MIN_FRAME_RATE = 1f
private const val LIBASS_MAX_FRAME_RATE = 240f
private const val LIBASS_FALLBACK_FRAME_DURATION_US = 1_000_000.0 / 60.0
private const val LIBASS_FLUSH_JUMP_US = 500_000L
private const val LIBASS_MAX_FRAME_BUFFERS = 6
private const val LIBASS_MAX_LEAD_FRAMES = 6L
private const val LIBASS_LOOKAHEAD_EXTRA_FRAMES = 1L
private const val LIBASS_PRESENT_MARGIN_US = 8_000.0
private const val LIBASS_INITIAL_RENDER_COST_US = 16_000.0
private const val LIBASS_MAX_STEPS_PER_MESSAGE = 8
private const val LIBASS_RETIRE_TICKS = 2
private const val LIBASS_BUFFER_ROW_STEP = 64
private const val LIBASS_NO_INDEX = Long.MIN_VALUE
private const val LIBASS_EMPTY_CONTENT = 0L
private const val LIBASS_UNKNOWN_CONTENT = -1L
private const val LIBASS_MSG_WORK = 1
private const val LIBASS_GL_MSG_INIT = 1
private const val LIBASS_GL_MSG_DRAW = 2
private const val LIBASS_GL_MSG_RESIZE = 3
private const val LIBASS_GL_MSG_RELEASE = 4

private const val LIBASS_GL_VERTEX_SHADER = """
attribute vec2 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vTexCoord = aTexCoord;
}
"""

private const val LIBASS_GL_FRAGMENT_SHADER = """
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uTexture;
void main() {
    gl_FragColor = texture2D(uTexture, vTexCoord);
}
"""
