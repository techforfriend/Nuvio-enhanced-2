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
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import kotlinx.coroutines.CancellationException
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
    val externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList(),
    val initialPositionMs: Long = 0L,
    /**
     * Torrent playback: when [torrentInfoHash] is set, [sourceUrl] is ignored
     * and the player resolves a local URL via the P2P streaming engine,
     * exactly like the phone runtime does. Direct streams leave these null/empty.
     */
    val torrentInfoHash: String? = null,
    val torrentFileIdx: Int? = null,
    val torrentFilename: String? = null,
    val torrentTrackers: List<String> = emptyList(),
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

    // Torrent resolution mirrors the phone runtime: the P2P engine turns an
    // info hash into a local HTTP URL that the surface can play. The engine
    // is already initialized by MainActivity, which TvMainActivity extends.
    val p2pSettings by P2pSettingsRepository.uiState.collectAsStateWithLifecycle()
    val p2pState by P2pStreamingEngine.state.collectAsStateWithLifecycle()
    var resolvedTorrentUrl by remember(request) { mutableStateOf<String?>(null) }
    var resolvingTorrent by remember(request) { mutableStateOf(request.torrentInfoHash != null) }
    val effectiveSourceUrl = if (request.torrentInfoHash != null) resolvedTorrentUrl else request.sourceUrl

    LaunchedEffect(request) {
        P2pSettingsRepository.ensureLoaded()
    }
    LaunchedEffect(request.torrentInfoHash, p2pSettings.p2pEnabled) {
        val infoHash = request.torrentInfoHash ?: return@LaunchedEffect
        if (!P2pSettingsRepository.isVisible || !p2pSettings.p2pEnabled) {
            resolvingTorrent = false
            errorMessage = "P2P off hai — TV Settings me P2P on karo, phir dobara play karo."
            return@LaunchedEffect
        }
        resolvingTorrent = true
        errorMessage = null
        try {
            val localUrl = P2pStreamingEngine.startStream(
                P2pStreamRequest(
                    infoHash = infoHash,
                    fileIdx = request.torrentFileIdx,
                    filename = request.torrentFilename,
                    trackers = request.torrentTrackers,
                ),
            )
            resolvedTorrentUrl = localUrl
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = "Torrent start nahi hua: ${error.message ?: "unknown error"}"
        }
        resolvingTorrent = false
    }
    LaunchedEffect(p2pState) {
        if (request.torrentInfoHash != null && p2pState is P2pStreamingState.Error) {
            errorMessage = "Torrent error: ${(p2pState as P2pStreamingState.Error).message}"
        }
    }
    DisposableEffect(request) {
        onDispose {
            if (request.torrentInfoHash != null) P2pStreamingEngine.stopStream()
        }
    }

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
        if (effectiveSourceUrl != null) {
            PlatformPlayerSurface(
                sourceUrl = effectiveSourceUrl,
            sourceAudioUrl = request.sourceAudioUrl,
            sourceHeaders = request.sourceHeaders,
            sourceResponseHeaders = emptyMap(),
            externalSubtitles = request.externalSubtitles,
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
        }

        if (resolvingTorrent && errorMessage == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(48.dp))
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Torrent se connect ho raha hai…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                )
                val connecting = p2pState as? P2pStreamingState.Connecting
                if (connecting != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Peers: ${connecting.peers} • Seeds: ${connecting.seeds}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        } else if (snapshot.isLoading && errorMessage == null) {
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
