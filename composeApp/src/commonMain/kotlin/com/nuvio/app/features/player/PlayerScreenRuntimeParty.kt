package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import com.nuvio.app.features.party.ActiveParty
import com.nuvio.app.features.party.PartyAction
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.party.PartyCorrection
import com.nuvio.app.features.party.PartyEvent
import com.nuvio.app.features.party.PartyMedia
import com.nuvio.app.features.party.PartyMemberStatus
import com.nuvio.app.features.party.PartyNotice
import com.nuvio.app.features.party.PartyPanel
import com.nuvio.app.features.party.PartyPlayback
import com.nuvio.app.features.party.PartyPrompt
import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.party.PartySource
import com.nuvio.app.features.party.PartySync
import com.nuvio.app.features.party.messageRes
import com.nuvio.app.features.party.partyMediaKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.party_action_paused
import nuvio.composeapp.generated.resources.party_action_resumed
import nuvio.composeapp.generated.resources.party_action_seeked
import nuvio.composeapp.generated.resources.party_action_speed
import nuvio.composeapp.generated.resources.party_reconnecting
import nuvio.composeapp.generated.resources.party_title
import nuvio.composeapp.generated.resources.party_waiting
import nuvio.composeapp.generated.resources.party_waiting_for
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal val PlayerScreenRuntime.partyKey: String
    get() = partyMediaKey(parentMetaType, parentMetaId, activeSeasonNumber, activeEpisodeNumber)

internal val PlayerScreenRuntime.isPartySynced: Boolean
    get() = PartyRepository.state.value.party?.media?.key == partyKey

internal fun PlayerScreenRuntime.currentPartyMedia(): PartyMedia = PartyMedia(
    type = contentType ?: parentMetaType,
    metaId = parentMetaId,
    metaType = parentMetaType,
    videoId = activeVideoId,
    season = activeSeasonNumber,
    episode = activeEpisodeNumber,
    title = title,
    episodeTitle = activeEpisodeTitle,
    poster = poster,
    background = background,
    logo = logo,
    source = PartySource(
        infoHash = activeTorrentInfoHash,
        fileIdx = activeTorrentFileIdx,
        filename = activeSourceFilename ?: activeTorrentFilename,
        bingeGroup = currentStreamBingeGroup,
        addonId = activeProviderAddonId,
        name = activeStreamTitle,
    ),
)

internal fun PlayerScreenRuntime.partyBaseRate(): Float =
    PartyRepository.playback.value?.rate ?: playbackSnapshot.playbackSpeed

internal fun PlayerScreenRuntime.currentPlaybackPositionMs(): Long {
    val snapshot = playbackSnapshot
    if (!snapshot.isPlaying || snapshot.isLoading) return snapshot.positionMs
    val elapsedMs = playbackSnapshotMark?.elapsedNow()?.inWholeMilliseconds?.coerceIn(0L, 1_000L) ?: 0L
    return snapshot.positionMs + (elapsedMs * snapshot.playbackSpeed).toLong()
}

internal fun PlayerScreenRuntime.publishPartyPlayback(
    positionMs: Long = currentPlaybackPositionMs(),
    rate: Float = partyBaseRate(),
) {
    if (!isPartySynced) return
    PartyRepository.publishPlayback(paused = !shouldPlay, positionMs = positionMs, rate = rate)
}

internal fun PlayerScreenRuntime.startParty() {
    scope.launch {
        val error = PartyRepository.create(currentPartyMedia()) ?: return@launch
        NuvioToastController.show(getString(error.messageRes))
    }
}

private fun PlayerScreenRuntime.applyPartyPaused(paused: Boolean, force: Boolean) {
    if (!force && shouldPlay == !paused) return
    shouldPlay = !paused
    if (paused) playerController?.pause() else playerController?.play()
}

private fun PlayerScreenRuntime.handlePartyMediaChange(media: PartyMedia) {
    if (media.key == partyKey) return
    val season = media.season
    val episode = media.episode
    if (media.metaId == parentMetaId && isSeries && season != null && episode != null) {
        val next = nextEpisodeInfo
        if (next != null && next.hasAired && next.season == season && next.episode == episode) {
            playNextEpisode()
            return
        }
        val video = playerMetaVideos.firstOrNull { it.season == season && it.episode == episode }
        if (video != null) {
            episodeStreamsPanelState = EpisodeStreamsPanelState(showStreams = true, selectedEpisode = video)
            showEpisodesPanel = true
            showPartyPanel = false
            return
        }
    }
    showPartyPanel = true
}

@Composable
internal fun PlayerScreenRuntime.BindPartyEffects() {
    val partyState by PartyRepository.state.collectAsStateWithLifecycle()
    val partyId = partyState.party?.id
    val key = partyKey

    LaunchedEffect(key) {
        val previous = partyPreviousKey
        partyPreviousKey = key
        val party = PartyRepository.state.value.party ?: return@LaunchedEffect
        if (previous != null && previous != key && party.media.key == previous) {
            PartyRepository.publishMedia(currentPartyMedia(), positionMs = 0L)
        }
    }

    LaunchedEffect(Unit) {
        PartyRepository.events.collect { event ->
            if (event is PartyEvent.MediaChanged) handlePartyMediaChange(event.media)
        }
    }

    LaunchedEffect(Unit) {
        AppForegroundMonitor.events().collect { visibility ->
            partyAppInBackground = visibility == AppVisibility.Background
        }
    }

    LaunchedEffect(partyId) {
        if (partyId == null) {
            clearPartyNudge()
            return@LaunchedEffect
        }
        val clock = TimeSource.Monotonic
        var bufferingSince: TimeMark? = null
        var mismatchSince: TimeMark? = null
        var lastSeek: TimeMark? = null
        var lastCorrection: TimeMark? = null
        var lastSeq: Pair<Long, String?>? = null
        var lastStatus: Pair<PartyMemberStatus, String>? = null
        var wasInBackground = false
        var publishedInitial = false
        var seekLeadMs = 0L
        var measureSeekLead = false
        while (isActive) {
            delay(PARTY_TICK)
            val party = PartyRepository.state.value.party ?: break
            val snapshot = playbackSnapshot
            val resumedFromBackground = wasInBackground && !partyAppInBackground
            wasInBackground = partyAppInBackground
            if (resumedFromBackground) lastSeq = null
            val status = when {
                errorMessage != null || partyAppInBackground -> PartyMemberStatus.Browsing
                !initialLoadCompleted -> PartyMemberStatus.Loading
                snapshot.isLoading && !snapshot.isEnded -> {
                    val since = bufferingSince ?: clock.markNow().also { bufferingSince = it }
                    if (since.elapsedNow() > BUFFERING_REPORT_DELAY) PartyMemberStatus.Buffering else PartyMemberStatus.Ready
                }
                else -> {
                    bufferingSince = null
                    PartyMemberStatus.Ready
                }
            }
            val reportedStatus = status to partyKey
            if (reportedStatus != lastStatus) {
                lastStatus = reportedStatus
                PartyRepository.setLocalStatus(status, partyKey)
            }

            val controller = playerController
            if (party.media.key != partyKey || controller == null || errorMessage != null || partyAppInBackground) {
                clearPartyNudge()
                continue
            }
            val playback = PartyRepository.playback.value
            if (playback == null) {
                if (initialLoadCompleted && !publishedInitial && party.isHost) {
                    publishedInitial = true
                    PartyRepository.publishPlayback(
                        paused = !shouldPlay,
                        positionMs = snapshot.positionMs,
                        rate = snapshot.playbackSpeed,
                    )
                }
                continue
            }
            publishedInitial = true
            if (!initialLoadCompleted) continue
            val expected = PartySync.expectedPositionMs(playback, PartyRepository.nowMs(), snapshot.durationMs)
            val identity = playback.seq to playback.memberId
            if (identity != lastSeq) {
                val forced = lastSeq == null
                lastSeq = identity
                applyPartyPaused(playback.paused, force = forced)
                val own = playback.memberId == party.memberId && !forced
                if ((!own || playback.paused) && abs(snapshot.positionMs - expected) > PartySync.PAUSED_TOLERANCE_MS) {
                    controller.seekTo(if (playback.paused) expected else expected + seekLeadMs)
                    measureSeekLead = !playback.paused
                }
                lastSeek = clock.markNow()
                if (partyRateNudge == null && abs(snapshot.playbackSpeed - playback.rate) > 0.01f) {
                    controller.setPlaybackSpeed(playback.rate)
                }
                mismatchSince = null
                continue
            }

            if (snapshot.isLoading || isScrubbingTimeline || snapshot.isEnded) {
                mismatchSince = null
                continue
            }

            if (snapshot.isPlaying != shouldPlay) {
                val since = mismatchSince ?: clock.markNow().also { mismatchSince = it }
                if (since.elapsedNow() > OUTSIDE_TOGGLE_DELAY) {
                    mismatchSince = null
                    shouldPlay = snapshot.isPlaying
                    PartyRepository.publishPlayback(
                        paused = !snapshot.isPlaying,
                        positionMs = snapshot.positionMs,
                        rate = playback.rate,
                    )
                }
                continue
            }
            mismatchSince = null

            val settled = lastSeek?.let { it.elapsedNow() > SEEK_SETTLE } ?: true
            val due = lastCorrection?.let { it.elapsedNow() >= CORRECTION_INTERVAL } ?: true
            if (!settled || !due) continue
            lastCorrection = clock.markNow()
            val drift = snapshot.positionMs - expected
            if (measureSeekLead && !playback.paused) {
                measureSeekLead = false
                if (abs(drift) < MAX_SEEK_LEAD_MS) seekLeadMs = (seekLeadMs - drift).coerceIn(0L, MAX_SEEK_LEAD_MS)
            }
            when (val correction = PartySync.correction(snapshot.positionMs, expected, playback, partyRateNudge != null)) {
                is PartyCorrection.Seek -> {
                    partyLog.i { "Seeking $drift ms back into sync with ${seekLeadMs} ms lead" }
                    clearPartyNudge(playback)
                    val target = if (playback.paused) correction.positionMs else correction.positionMs + seekLeadMs
                    controller.seekTo(if (snapshot.durationMs > 0L) target.coerceAtMost(snapshot.durationMs) else target)
                    lastSeek = clock.markNow()
                    measureSeekLead = !playback.paused
                }
                is PartyCorrection.Nudge -> {
                    if (partyRateNudge != correction.rate) {
                        partyLog.i { "Nudging speed to ${correction.rate} for ${snapshot.positionMs - expected} ms drift" }
                        partyRateNudge = correction.rate
                        controller.setPlaybackSpeed(correction.rate)
                    }
                }
                PartyCorrection.InSync -> clearPartyNudge(playback)
            }
        }
        clearPartyNudge()
    }

    DisposableEffect(Unit) {
        onDispose {
            val party = PartyRepository.state.value.party ?: return@onDispose
            val playback = PartyRepository.playback.value
            val othersWatching = party.presence.values.any {
                it.memberId != party.memberId && it.mediaKey == party.media.key && it.status != PartyMemberStatus.Browsing
            }
            if (party.media.key == partyKey && playback != null && !playback.paused && !othersWatching) {
                PartyRepository.publishPlayback(paused = true, positionMs = playbackSnapshot.positionMs, rate = playback.rate)
            }
            PartyRepository.setLocalStatus(PartyMemberStatus.Browsing, null)
        }
    }
}

private fun PlayerScreenRuntime.clearPartyNudge(playback: PartyPlayback? = PartyRepository.playback.value) {
    if (partyRateNudge == null) return
    partyRateNudge = null
    playerController?.setPlaybackSpeed(playback?.rate ?: 1f)
}

@Composable
internal fun BoxScope.RenderParty(runtime: PlayerScreenRuntime) {
    val partyState by PartyRepository.state.collectAsStateWithLifecycle()
    val playback by PartyRepository.playback.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<PartyNotice?>(null) }
    LaunchedEffect(Unit) {
        PartyRepository.notices.collectLatest { next ->
            notice = next
            delay(NOTICE_DURATION)
            notice = null
        }
    }
    val party = partyState.party
    runtime.run {
        if (party != null) {
            val waitingNames = if (playback?.paused == true && playback?.waiting == true) {
                party.waitingNames()
            } else {
                emptyList()
            }
            val noticeText = notice?.text()
            PartyStatusPill(
                visible = (controlsVisible || waitingNames.isNotEmpty() || !party.connected || noticeText != null) &&
                    !playerControlsLocked && !showPartyPanel,
                memberCount = party.members.size,
                message = when {
                    !party.connected -> stringResource(Res.string.party_reconnecting)
                    waitingNames.isNotEmpty() -> stringResource(Res.string.party_waiting_for, waitingNames.joinToString())
                    playback?.waiting == true -> stringResource(Res.string.party_waiting)
                    noticeText != null -> noticeText
                    else -> null
                },
                onClick = { showPartyPanel = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                    .padding(top = metrics.verticalPadding / 4 + 8.dp),
            )
        }
        PartyPanel(
            visible = showPartyPanel,
            state = partyState,
            playerMediaKey = partyKey,
            isSupported = PartyRepository.available.collectAsStateWithLifecycle().value,
            canStart = PartyRepository.canUse,
            onStart = { startParty() },
            onPlayForEveryone = {
                PartyRepository.publishMedia(currentPartyMedia(), positionMs = playbackSnapshot.positionMs)
            },
            onWatchWithParty = {
                party?.media?.let { media ->
                    showPartyPanel = false
                    flushWatchProgress()
                    PartyPrompt.openPartyMedia(media)
                }
            },
            onLeave = {
                showPartyPanel = false
                PartyRepository.leave()
            },
            onEnd = {
                showPartyPanel = false
                PartyRepository.end()
            },
            onRemove = { member -> PartyRepository.removeMember(member.id) },
            onDismiss = { showPartyPanel = false },
        )
    }
}

@Composable
private fun PartyNotice.text(): String = when (action) {
    PartyAction.Paused -> stringResource(Res.string.party_action_paused, name)
    PartyAction.Resumed -> stringResource(Res.string.party_action_resumed, name)
    PartyAction.Seeked -> stringResource(Res.string.party_action_seeked, name, formatPlaybackTime(positionMs))
    PartyAction.Speed -> stringResource(Res.string.party_action_speed, name, formatPlaybackSpeedLabel(rate))
}

private fun ActiveParty.waitingNames(): List<String> =
    PartySync.waitingMemberIds(this, emptySet())
        .filter { it != memberId }
        .mapNotNull { id -> member(id)?.displayName }

@Composable
private fun PartyStatusPill(
    visible: Boolean,
    memberCount: Int,
    message: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Groups,
                contentDescription = stringResource(Res.string.party_title),
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = memberCount.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val partyLog = Logger.withTag("Party")
private val PARTY_TICK = 250.milliseconds
private val NOTICE_DURATION = 3_000.milliseconds
private val BUFFERING_REPORT_DELAY = 1_000.milliseconds
private val OUTSIDE_TOGGLE_DELAY = 1_200.milliseconds
private val SEEK_SETTLE = 2_500.milliseconds
private val CORRECTION_INTERVAL = 500.milliseconds
private const val MAX_SEEK_LEAD_MS = 3_000L
