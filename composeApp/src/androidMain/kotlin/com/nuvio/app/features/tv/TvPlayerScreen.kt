package com.nuvio.app.features.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.party.PartyMedia
import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.party.PartyStream
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.ManagePlayerPictureInPicture
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.SubtitleTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.abs

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
    /**
     * The content this request came from, for Watch Party media and for
     * debrid season/episode resolve. Null when played from a download or
     * a shared party stream that carries no meta.
     */
    val metaType: String? = null,
    val metaId: String? = null,
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
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
    onOpenParty: () -> Unit,
) {
    var controller by remember { mutableStateOf<PlayerEngineController?>(null) }
    var snapshot by remember { mutableStateOf(PlayerPlaybackSnapshot()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableStateOf(0) }
    val rootFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }
    val retryButtonFocusRequester = remember { FocusRequester() }
    val firstTrackFocusRequester = remember { FocusRequester() }

    // Track selection panels: the surface's controller already exposes the
    // phone engine's track lists, so TV only needs a D-pad list on top.
    var trackPanel by remember { mutableStateOf<TvTrackPanel?>(null) }
    var audioTracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<SubtitleTrack>>(emptyList()) }

    // Watch Party: publish local play/pause/seek, follow the host when a
    // member, and share the host stream while the party is sharing.
    val partyState by PartyRepository.state.collectAsStateWithLifecycle()
    val partyPlayback by PartyRepository.playback.collectAsStateWithLifecycle()

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

    // PiP: feeding the session is what makes HOME auto-enter PiP on TV
    // (the manager + MainActivity.onUserLeaveHint already do the rest).
    // Without this call the session stays inactive and PiP never engages.
    ManagePlayerPictureInPicture(
        isPlaying = snapshot.isPlaying,
        videoSize = IntSize(snapshot.videoWidth, snapshot.videoHeight),
    )

    fun publishPartyPlayback() {
        if (partyState.party == null) return
        PartyRepository.publishPlayback(
            paused = !snapshot.isPlaying,
            positionMs = snapshot.positionMs,
            rate = 1f,
        )
    }

    // Host shares the playing stream so members can pick it up from the
    // party screen. Fires again when a torrent resolves to its local URL.
    LaunchedEffect(partyState.party?.sharing, partyState.party?.isHost, effectiveSourceUrl) {
        val party = partyState.party ?: return@LaunchedEffect
        if (!party.isHost || !party.sharing) return@LaunchedEffect
        val url = effectiveSourceUrl ?: return@LaunchedEffect
        PartyRepository.shareStream(
            PartyStream(
                mediaKey = party.media.key,
                url = url,
                headers = request.sourceHeaders,
                name = request.title,
            ),
        )
    }

    // Members follow the host: seek on large drift, mirror play/pause.
    // Own echoes (same member id) and other-media updates are ignored.
    // Publish happens only from local actions below, so applying never loops.
    LaunchedEffect(partyPlayback) {
        val playback = partyPlayback ?: return@LaunchedEffect
        val party = partyState.party ?: return@LaunchedEffect
        if (party.isHost) return@LaunchedEffect
        if (playback.memberId == party.memberId) return@LaunchedEffect
        val currentKey = if (request.metaType != null && request.metaId != null) {
            PartyMedia(
                type = request.metaType,
                metaId = request.metaId,
                metaType = request.metaType,
                videoId = request.videoId,
                season = request.season,
                episode = request.episode,
                title = request.title,
            ).key
        } else {
            null
        }
        if (currentKey != null && playback.mediaKey != null && playback.mediaKey != currentKey) {
            return@LaunchedEffect
        }
        if (abs(playback.positionMs - snapshot.positionMs) > 3000L) {
            controller?.seekTo(playback.positionMs)
        }
        if (playback.paused != !snapshot.isPlaying) {
            if (playback.paused) controller?.pause() else controller?.play()
        }
    }

    LaunchedEffect(controlsVisible, snapshot.isPlaying, interactionTick, trackPanel) {
        if (controlsVisible && snapshot.isPlaying && errorMessage == null && trackPanel == null) {
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
    LaunchedEffect(trackPanel) {
        if (trackPanel != null) firstTrackFocusRequester.requestFocus()
    }

    fun showControls() {
        controlsVisible = true
        interactionTick++
    }

    fun togglePlayPause() {
        if (snapshot.isEnded) {
            controller?.seekTo(0L)
            controller?.play()
        } else if (snapshot.isPlaying) {
            controller?.pause()
        } else {
            controller?.play()
        }
        publishPartyPlayback()
        showControls()
    }

    fun seekBack() {
        controller?.seekBy(-10_000L)
        publishPartyPlayback()
        showControls()
    }

    fun seekForward() {
        controller?.seekBy(10_000L)
        publishPartyPlayback()
        showControls()
    }

    fun openAudioTracks() {
        audioTracks = controller?.getAudioTracks().orEmpty()
        trackPanel = TvTrackPanel.Audio
    }

    fun openSubtitleTracks() {
        subtitleTracks = controller?.getSubtitleTracks().orEmpty()
        trackPanel = TvTrackPanel.Subtitles
    }

    fun closeTrackPanel() {
        trackPanel = null
        playButtonFocusRequester.requestFocus()
    }

    BackHandler {
        if (controlsVisible) {
            controlsVisible = false
        } else {
            onBack()
        }
    }

    // Declared after the main BackHandler so it wins while a panel is open.
    BackHandler(enabled = trackPanel != null) {
        closeTrackPanel()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (trackPanel != null) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (mapTvPlayerKey(event.key)) {
                    TvPlayerKeyAction.TogglePlayPause -> {
                        togglePlayPause()
                        true
                    }
                    TvPlayerKeyAction.SeekBack -> {
                        seekBack()
                        true
                    }
                    TvPlayerKeyAction.SeekForward -> {
                        seekForward()
                        true
                    }
                    TvPlayerKeyAction.ShowControls -> {
                        showControls()
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
                    onPlayPause = { togglePlayPause() },
                    onSeekBack = { seekBack() },
                    onSeekForward = { seekForward() },
                    playButtonFocusRequester = playButtonFocusRequester,
                    onOpenAudioTracks = { openAudioTracks() },
                    onOpenSubtitleTracks = { openSubtitleTracks() },
                    onCast = { controller?.showCastDialog() },
                    castAvailable = snapshot.castAvailable,
                    onOpenParty = onOpenParty,
                    partyCode = partyState.party?.code,
                )
            }
        }

        if (trackPanel != null) {
            val panel = trackPanel
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(0.7f),
                ) {
                    Text(
                        text = if (panel == TvTrackPanel.Audio) "Audio tracks" else "Subtitle tracks",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (panel == TvTrackPanel.Audio) {
                        if (audioTracks.isEmpty()) {
                            Text(
                                text = "Koi audio track nahi mila.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        } else {
                            LazyColumn {
                                items(audioTracks) { track ->
                                    TvTrackRow(
                                        label = listOfNotNull(
                                            track.label,
                                            track.language,
                                        ).joinToString(" • ").takeIf { it.isNotBlank() } ?: "Track ${track.index}",
                                        selected = track.isSelected,
                                        onClick = {
                                            controller?.selectAudioTrack(track.index)
                                            closeTrackPanel()
                                        },
                                        focusRequester = if (track == audioTracks.firstOrNull()) {
                                            firstTrackFocusRequester
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn {
                            item {
                                TvTrackRow(
                                    label = "Off",
                                    selected = subtitleTracks.none { it.isSelected },
                                    onClick = {
                                        controller?.selectSubtitleTrack(-1)
                                        closeTrackPanel()
                                    },
                                    focusRequester = firstTrackFocusRequester,
                                )
                            }
                            items(subtitleTracks) { track ->
                                TvTrackRow(
                                    label = listOfNotNull(
                                        track.label,
                                        track.language,
                                        if (track.isForced) "Forced" else null,
                                    ).joinToString(" • ").takeIf { it.isNotBlank() } ?: "Track ${track.index}",
                                    selected = track.isSelected,
                                    onClick = {
                                        controller?.selectSubtitleTrack(track.index)
                                        closeTrackPanel()
                                    },
                                    focusRequester = null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class TvTrackPanel {
    Audio,
    Subtitles,
}

@Composable
private fun TvTrackRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                if (selected) Color(0xFF222222) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (selected) "✓ $label" else label,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
