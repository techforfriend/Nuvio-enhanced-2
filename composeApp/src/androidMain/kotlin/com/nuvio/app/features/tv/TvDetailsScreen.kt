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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import coil3.compose.AsyncImage
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.streams.StreamSubtitle
import com.nuvio.app.features.streams.StreamsRepository

/**
 * Phase 4 TV Details: hero + Play + episodes + More like this, all from
 * [MetaDetailsRepository]. Play opens a TV stream picker (directly playable
 * streams only — torrent/magnet streams are filtered out, they need the
 * phone torrent flow); picking one hands a resolved [TvPlayRequest] to the
 * player. BACK closes the stream picker first, then exits.
 */
@Composable
fun TvDetailsScreen(
    item: MetaPreview,
    onBack: () -> Unit,
    onOpenDetails: (MetaPreview) -> Unit,
    onPlay: (TvPlayRequest) -> Unit,
) {
    val detailsState by MetaDetailsRepository.uiState.collectAsStateWithLifecycle()
    val streamsState by StreamsRepository.uiState.collectAsStateWithLifecycle()
    val p2pSettings by P2pSettingsRepository.uiState.collectAsStateWithLifecycle()
    val meta = detailsState.meta
    var selectedVideo by remember(item) { mutableStateOf<MetaVideo?>(null) }
    var showStreams by remember(item) { mutableStateOf(false) }
    var showP2pOffNotice by remember(item) { mutableStateOf(false) }
    val playFocusRequester = remember { FocusRequester() }
    val firstStreamFocusRequester = remember { FocusRequester() }

    val streamChoices = remember(streamsState.groups) {
        streamsState.groups.flatMap { group ->
            group.streams.mapNotNull { stream ->
                val directUrl = stream.playableDirectUrl
                val infoHash = stream.p2pInfoHash
                if (directUrl == null && infoHash == null) {
                    null
                } else {
                    TvStreamChoice(
                        addonName = group.addonName,
                        title = stream.streamLabel,
                        subtitle = stream.streamSubtitle,
                        directUrl = directUrl,
                        torrentInfoHash = infoHash,
                        torrentFileIdx = stream.p2pFileIdx,
                        torrentFilename = stream.behaviorHints.filename,
                        torrentTrackers = stream.p2pTrackers,
                        streamType = stream.streamType,
                        externalSubtitles = stream.externalSubtitles,
                        key = "${stream.addonId}:${stream.url ?: infoHash}",
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        P2pSettingsRepository.ensureLoaded()
    }

    LaunchedEffect(item) {
        MetaDetailsRepository.load(item.type, item.id)
    }
    LaunchedEffect(meta?.id, meta?.videos) {
        if (selectedVideo == null && meta != null) {
            selectedVideo = meta.videos.firstOrNull()
        }
    }
    LaunchedEffect(showStreams, selectedVideo?.id, meta?.id) {
        val m = meta ?: return@LaunchedEffect
        if (!showStreams) return@LaunchedEffect
        val video = selectedVideo
        StreamsRepository.load(
            type = m.type,
            videoId = video?.id ?: m.defaultVideoId ?: m.id,
            parentMetaId = if (m.type == "series") m.id else null,
            parentMetaType = if (m.type == "series") m.type else null,
            season = video?.season,
            episode = video?.episode,
        )
    }
    LaunchedEffect(meta != null, showStreams) {
        if (meta != null && !showStreams) playFocusRequester.requestFocus()
    }
    var streamFocusRequested by remember(item) { mutableStateOf(false) }
    LaunchedEffect(showStreams, streamChoices.isNotEmpty()) {
        if (showStreams && streamChoices.isNotEmpty() && !streamFocusRequested) {
            streamFocusRequested = true
            firstStreamFocusRequester.requestFocus()
        }
    }

    BackHandler {
        if (showStreams) {
            showStreams = false
        } else {
            onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020404)),
    ) {
        when {
            detailsState.isLoading && meta == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Loading…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
            detailsState.errorMessage != null && meta == null -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(48.dp))
                    Text(
                        text = detailsState.errorMessage ?: "Kuch galat hua.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(16.dp))
                    TvControlButton(
                        label = "Retry",
                        onClick = { MetaDetailsRepository.load(item.type, item.id) },
                    )
                    Spacer(Modifier.height(12.dp))
                    TvControlButton(label = "Back", onClick = onBack)
                }
            }
            showStreams -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Text(
                            text = "Streams — ${meta?.name ?: item.name}",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 24.dp),
                        )
                    }
                    if (showP2pOffNotice) {
                        item {
                            Text(
                                text = "P2P off hai — TV Settings me P2P on karo, phir dobara stream chuno.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White,
                                modifier = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 16.dp),
                            )
                        }
                    }
                    if (streamsState.isAnyLoading && streamChoices.isEmpty()) {
                        item {
                            Text(
                                text = "Loading streams…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 48.dp),
                            )
                        }
                    } else if (streamChoices.isEmpty()) {
                        item {
                            Text(
                                text = "No playable streams found.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 48.dp),
                            )
                        }
                    } else {
                        items(streamChoices, key = { it.key }) { choice ->
                            val isTorrentOnly = choice.directUrl == null
                            TvStreamRow(
                                addonName = choice.addonName,
                                title = choice.title,
                                subtitle = listOfNotNull(
                                    choice.subtitle,
                                    if (isTorrentOnly) "Torrent" else null,
                                ).joinToString(" • ").takeIf { it.isNotBlank() },
                                onClick = {
                                    val video = selectedVideo
                                    val playTitle = if (video != null && meta?.type == "series") {
                                        "${meta.name} — ${video.title}"
                                    } else {
                                        meta?.name ?: item.name
                                    }
                                    if (choice.directUrl != null) {
                                        onPlay(
                                            TvPlayRequest(
                                                title = playTitle,
                                                sourceUrl = choice.directUrl,
                                                streamType = choice.streamType,
                                                externalSubtitles = choice.externalSubtitles,
                                            ),
                                        )
                                    } else if (choice.torrentInfoHash != null) {
                                        if (!P2pSettingsRepository.isVisible || !p2pSettings.p2pEnabled) {
                                            showP2pOffNotice = true
                                        } else {
                                            onPlay(
                                                TvPlayRequest(
                                                    title = playTitle,
                                                    sourceUrl = "",
                                                    streamType = choice.streamType,
                                                    externalSubtitles = choice.externalSubtitles,
                                                    torrentInfoHash = choice.torrentInfoHash,
                                                    torrentFileIdx = choice.torrentFileIdx,
                                                    torrentFilename = choice.torrentFilename,
                                                    torrentTrackers = choice.torrentTrackers,
                                                ),
                                            )
                                        }
                                    }
                                },
                                focusRequester = if (choice == streamChoices.firstOrNull()) {
                                    firstStreamFocusRequester
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
            meta != null -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Row(
                            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp),
                        ) {
                            val posterUrl = meta.poster ?: item.poster
                            Box(
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(200.dp)
                                    .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (posterUrl != null) {
                                    AsyncImage(
                                        model = posterUrl,
                                        contentDescription = meta.name,
                                        modifier = Modifier.matchParentSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 16.dp),
                            ) {
                                Text(
                                    text = meta.name,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Color.White,
                                )
                                Spacer(Modifier.height(8.dp))
                                val infoLine = listOfNotNull(
                                    meta.releaseInfo,
                                    meta.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                                    meta.runtime,
                                    meta.imdbRating?.let { "IMDb $it" },
                                ).joinToString(" • ")
                                if (infoLine.isNotBlank()) {
                                    Text(
                                        text = infoLine,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.7f),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                                if (!meta.description.isNullOrBlank()) {
                                    Text(
                                        text = meta.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.85f),
                                        maxLines = 6,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.padding(start = 48.dp, top = 24.dp),
                        ) {
                            TvControlButton(
                                label = "Play",
                                onClick = { showStreams = true },
                                focusRequester = playFocusRequester,
                            )
                            val video = selectedVideo
                            if (video != null && meta.type == "series") {
                                Text(
                                    text = "S${video.season ?: "?"} E${video.episode ?: "?"} — ${video.title}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .padding(start = 16.dp)
                                        .align(Alignment.CenterVertically),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (meta.videos.isNotEmpty()) {
                        item {
                            Text(
                                text = "Episodes",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                modifier = Modifier.padding(start = 48.dp, top = 24.dp, bottom = 8.dp),
                            )
                        }
                        items(meta.videos, key = { it.id }) { video ->
                            TvEpisodeRow(
                                video = video,
                                selected = video == selectedVideo,
                                onClick = { selectedVideo = video },
                            )
                        }
                    }
                    if (meta.moreLikeThis.isNotEmpty()) {
                        item {
                            TvHomeRail(
                                title = "More like this",
                                subtitle = "",
                                items = meta.moreLikeThis,
                                onItemClick = onOpenDetails,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvEpisodeRow(
    video: MetaVideo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val seasonEpisode = if (video.season != null && video.episode != null) {
        "S${video.season} E${video.episode} — "
    } else {
        ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 4.dp)
            .background(
                if (selected) Color(0xFF222222) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = seasonEpisode + video.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused || selected) Color.White else Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TvStreamRow(
    addonName: String,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 4.dp)
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = "$addonName — $title",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One row of the TV stream picker. Either [directUrl] plays immediately,
 * or [torrentInfoHash] resolves through the P2P engine in the player.
 * Rows with neither are dropped before they reach the picker.
 */
private data class TvStreamChoice(
    val addonName: String,
    val title: String,
    val subtitle: String?,
    val directUrl: String?,
    val torrentInfoHash: String?,
    val torrentFileIdx: Int?,
    val torrentFilename: String?,
    val torrentTrackers: List<String>,
    val streamType: String?,
    val externalSubtitles: List<StreamSubtitle>,
    val key: String,
)
