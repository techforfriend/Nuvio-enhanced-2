package com.nuvio.app.features.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.features.livetv.LiveTvChannel
import com.nuvio.app.features.livetv.LiveTvRepository
import kotlinx.coroutines.launch

/**
 * Reshaped-style TV Live TV: channel list from the shared [LiveTvRepository]
 * (the same playlists, favorites and EPG the phone app uses), grouped by
 * playlist group with a Favorites filter. Playlists themselves are still
 * managed on the phone — typing playlist URLs on a remote is painful —
 * so an empty state here points back to phone Settings. Playing a channel
 * hands a plain [TvPlayRequest] to the TV player; Stalker portal channels
 * resolve first through [LiveTvRepository.prepareForPlayback].
 */
@Composable
fun TvLiveScreen(
    onBack: () -> Unit,
    onPlay: (TvPlayRequest) -> Unit,
) {
    val liveState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("All") }
    var resolvingId by remember { mutableStateOf<String?>(null) }
    val playScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        LiveTvRepository.ensureLoaded()
        LiveTvRepository.ensureChannelsLoaded()
    }

    BackHandler {
        onBack()
    }

    val groups = liveState.channels.mapNotNull { it.group }.distinct().sorted()
    val filters = listOf("All", "Favorites") + groups
    val visibleChannels = when (filter) {
        "All" -> liveState.channels
        "Favorites" -> liveState.channels.filter { it.id in liveState.favoriteChannelIds }
        else -> liveState.channels.filter { it.group == filter }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020404)),
    ) {
        when {
            liveState.isLoading && liveState.channels.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Loading channels…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
            !liveState.hasPlaylist -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(48.dp))
                    Text(
                        text = "No Live TV playlist yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Add a playlist in phone Settings, then come back here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(16.dp))
                    TvControlButton(label = "Back", onClick = onBack)
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    item {
                        Text(
                            text = "Live TV",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 16.dp),
                        )
                    }
                    item {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(filters) { name ->
                                TvLiveChip(
                                    label = name,
                                    selected = filter == name,
                                    onClick = { filter = name },
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    if (resolvingId != null) {
                        item {
                            Text(
                                text = "Loading channel…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 16.dp),
                            )
                        }
                    }
                    if (visibleChannels.isEmpty()) {
                        item {
                            Text(
                                text = "No channels in this view.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 48.dp),
                            )
                        }
                    } else {
                        items(visibleChannels, key = { it.id }) { channel ->
                            TvLiveChannelRow(
                                channel = channel,
                                isFavorite = channel.id in liveState.favoriteChannelIds,
                                nowNextLine = liveNowNextLine(
                                    programs = liveState.epgPrograms[channel.id].orEmpty(),
                                ),
                                onToggleFavorite = {
                                    LiveTvRepository.toggleFavoriteChannel(channel.id)
                                },
                                onPlay = {
                                    if (resolvingId != null) return@TvLiveChannelRow
                                    resolvingId = channel.id
                                    playScope.launch {
                                        try {
                                            val prepared = LiveTvRepository.prepareForPlayback(channel)
                                            LiveTvRepository.markChannelWatched(channel)
                                            onPlay(
                                                TvPlayRequest(
                                                    title = channel.name,
                                                    sourceUrl = prepared.streamUrl,
                                                    sourceHeaders = prepared.headers,
                                                    streamType = prepared.streamType,
                                                ),
                                            )
                                        } finally {
                                            resolvingId = null
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun liveNowNextLine(
    programs: List<com.nuvio.app.features.livetv.LiveTvEpgProgram>,
): String {
    if (programs.isEmpty()) return ""
    val nowMs = System.currentTimeMillis()
    val nowProg = programs.firstOrNull { nowMs in it.startMillis until it.endMillis }
    val nextProg = programs.firstOrNull { it.startMillis > nowMs }
    return listOfNotNull(
        nowProg?.let { "Now: ${it.title}" },
        nextProg?.let { "Next: ${it.title}" },
    ).joinToString(" • ")
}

@Composable
private fun TvLiveChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .background(
                color = when {
                    focused -> Color.White
                    selected -> Color.White.copy(alpha = 0.15f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(20.dp),
            )
            .border(
                width = 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(20.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) Color.Black else Color.White,
        )
    }
}

@Composable
private fun TvLiveChannelRow(
    channel: LiveTvChannel,
    isFavorite: Boolean,
    nowNextLine: String,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
) {
    var rowFocused by remember { mutableStateOf(false) }
    var starFocused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .background(
                    if (rowFocused) Color(0xFF1A1A1A) else Color.Transparent,
                    RoundedCornerShape(8.dp),
                )
                .border(
                    width = 2.dp,
                    color = if (rowFocused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                )
                .onFocusChanged { rowFocused = it.isFocused }
                .clickable { onPlay() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (channel.logoUrl != null) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = channel.name,
                    modifier = Modifier.size(48.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = channel.name.firstOrNull()?.toString() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subLine = nowNextLine.takeIf { it.isNotBlank() }
                    ?: channel.group?.takeIf { it.isNotBlank() }
                if (subLine != null) {
                    Text(
                        text = subLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    if (starFocused) Color.White else Color.Transparent,
                    RoundedCornerShape(22.dp),
                )
                .border(
                    width = 1.dp,
                    color = if (starFocused) Color.White else Color.White.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(22.dp),
                )
                .onFocusChanged { starFocused = it.isFocused }
                .clickable { onToggleFavorite() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isFavorite) "★" else "☆",
                style = MaterialTheme.typography.titleMedium,
                color = if (starFocused) Color.Black else Color.White,
            )
        }
    }
}
