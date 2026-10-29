package com.nuvio.app.features.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import kotlinx.coroutines.delay

/**
 * A fully resolved stream to play on TV. Stream resolution (which addon
 * stream, which episode, which quality) is NOT done here — Details (Phase 4)
 * resolves it and hands a [TvPlayRequest] to [TvScreen.Player].
 */
data class TvPlayRequest(
    val title: String,
    val sourceUrl: String,
    val sourceAudioUrl: String? = null,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val streamType: String? = null,
    val initialPositionMs: Long = 0L,
)

/**
 * Phase 3 TV player: the phone player's Android surface ([PlatformPlayerSurface],
 * ExoPlayer with libmpv fallback) with a TV-only controls overlay. No phone
 * gestures are reused — only runtime actions (play/pause/seek) via
 * [PlayerEngineController] and [PlayerPlaybackSnapshot] progress.
 *
 * Focus model: controls start visible with focus on Play/Pause. While
 * playing, controls auto-hide after 4s and focus returns to the surface,
 * where D-pad left/right seek and center toggles play/pause. BACK first
 * hides visible controls, then exits.
 */
@Composable
fun TvPlayerScreen(
    request: TvPlayRequest,
    onBack: () -> Unit,
) {
    var controller by remember { mutableStateOf<PlayerEngineController?>(null) }
    var snapshot by remember { mutableStateOf(PlayerPlaybackSnapshot()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableStateOf(0) }
    val rootFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }
    val retryButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(controlsVisible, snapshot.isPlaying, interactionTick) {
        if (controlsVisible && snapshot.isPlaying && errorMessage == null) {
            delay(4000L)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible, errorMessage) {
        when {
            errorMessage != null -> retryButtonFocusRequester.requestFocus()
            controlsVisible -> playButtonFocusRequester.requestFocus()
            else -> rootFocusRequester.requestFocus()
        }
    }

    fun showControls() {
        controlsVisible = true
        interactionTick++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (mapTvPlayerKey(event.key.nativeKeyCode, controlsVisible)) {
                    TvPlayerKeyAction.TogglePlayPause -> {
                        if (snapshot.isEnded) {
                            controller?.seekTo(0L)
                            controller?.play()
                        } else if (snapshot.isPlaying) {
                            controller?.pause()
                        } else {
                            controller?.play()
                        }
                        showControls()
                        true
                    }
                    TvPlayerKeyAction.SeekBack -> {
                        controller?.seekBy(-10_000L)
                        showControls()
                        true
                    }
                    TvPlayerKeyAction.SeekForward -> {
                        controller?.seekBy(10_000L)
                        showControls()
                        true
                    }
                    TvPlayerKeyAction.ShowControls -> {
                        showControls()
                        true
                    }
                    TvPlayerKeyAction.HideControlsThenBack -> {
                        controlsVisible = false
                        true
                    }
                    TvPlayerKeyAction.Back -> {
                        onBack()
                        true
                    }
                    null -> false
                }
            },
    ) {
        PlatformPlayerSurface(
            sourceUrl = request.sourceUrl,
            sourceAudioUrl = request.sourceAudioUrl,
            sourceHeaders = request.sourceHeaders,
            sourceResponseHeaders = emptyMap(),
            externalSubtitles = emptyList(),
            streamType = request.streamType,
            useYoutubeChunkedPlayback = false,
            modifier = Modifier.fillMaxSize(),
            playWhenReady = true,
            initialPositionMs = request.initialPositionMs,
            initialPositionRequestKey = null,
            resizeMode = PlayerResizeMode.Fit,
            playbackEngine = null,
            useNativeController = false,
            onInitialPositionHandled = { _, _ -> },
            onControllerReady = { controller = it },
            onSnapshot = { snapshot = it },
            onError = { message ->
                if (message != null) errorMessage = message
            },
        )

        if (snapshot.isLoading && errorMessage == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(48.dp))
                Text(
                    text = "Playback error",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = errorMessage ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(24.dp))
                TvControlButton(
                    label = "Retry",
                    onClick = {
                        errorMessage = null
                        controller?.retry()
                    },
                    focusRequester = retryButtonFocusRequester,
                )
                Spacer(Modifier.height(12.dp))
                TvControlButton(label = "Back", onClick = onBack)
            }
        } else if (controlsVisible) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                TvPlayerControls(
                    title = request.title,
                    snapshot = snapshot,
                    isPlaying = snapshot.isPlaying,
                    onPlayPause = {
                        if (snapshot.isEnded) {
                            controller?.seekTo(0L)
                            controller?.play()
                        } else if (snapshot.isPlaying) {
                            controller?.pause()
                        } else {
                            controller?.play()
                        }
                        showControls()
                    },
                    onSeekBack = {
                        controller?.seekBy(-10_000L)
                        showControls()
                    },
                    onSeekForward = {
                        controller?.seekBy(10_000L)
                        showControls()
                    },
                    playButtonFocusRequester = playButtonFocusRequester,
                )
            }
        }
    }
}
