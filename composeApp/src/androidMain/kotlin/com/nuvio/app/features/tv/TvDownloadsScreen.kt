package com.nuvio.app.features.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository

/**
 * TV Downloads: the shared [DownloadsRepository] list (same downloads as
 * the phone — one profile, one download store). Completed downloads play
 * from their local file through the TV player; active ones can be paused,
 * resumed or deleted. Downloads themselves start from the TV stream
 * picker (Download button on direct streams).
 */
@Composable
fun TvDownloadsScreen(
    onBack: () -> Unit,
    onPlay: (TvPlayRequest) -> Unit,
) {
    val downloadsState by DownloadsRepository.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        DownloadsRepository.ensureLoaded()
    }

    BackHandler { onBack() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020404)),
    ) {
        item {
            Text(
                text = "Downloads",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 24.dp),
            )
        }
        if (downloadsState.items.isEmpty()) {
            item {
                Text(
                    text = "No downloads yet. Press Download on a direct stream in Details.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 48.dp),
                )
            }
        } else {
            items(downloadsState.items, key = { it.id }) { download ->
                TvDownloadRow(
                    download = download,
                    onPlay = {
                        val uri = DownloadsRepository.playableLocalFileUri(download) ?: return@TvDownloadRow
                        onPlay(
                            TvPlayRequest(
                                title = download.title,
                                sourceUrl = uri,
                                metaType = download.parentMetaType,
                                metaId = download.parentMetaId,
                                videoId = download.videoId,
                                season = download.seasonNumber,
                                episode = download.episodeNumber,
                            ),
                        )
                    },
                    onPause = { DownloadsRepository.pauseDownload(download.id) },
                    onResume = { DownloadsRepository.resumeDownload(download.id) },
                    onRetry = { DownloadsRepository.retryDownload(download.id) },
                    onDelete = { DownloadsRepository.cancelDownload(download.id) },
                )
            }
        }
    }
}

@Composable
private fun TvDownloadRow(
    download: DownloadItem,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val statusText = when (download.status) {
        DownloadStatus.Downloading -> {
            val pct = (download.progressFraction * 100f).toInt()
            if (download.totalBytes != null) "Downloading — $pct%" else "Downloading…"
        }
        DownloadStatus.Paused -> "Paused — ${(download.progressFraction * 100f).toInt()}%"
        DownloadStatus.Completed -> "Completed"
        DownloadStatus.Failed -> download.errorMessage ?: "Failed"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 4.dp)
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = download.isPlayable) { onPlay() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = download.title,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (download.displaySubtitle.isNotBlank()) {
            Text(
                text = download.displaySubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
        )
        if (download.status == DownloadStatus.Downloading ||
            download.status == DownloadStatus.Paused
        ) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { download.progressFraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (download.isPlayable) {
                TvControlButton(label = "Play", onClick = onPlay)
                Spacer(Modifier.width(12.dp))
            }
            when (download.status) {
                DownloadStatus.Downloading -> {
                    TvControlButton(label = "Pause", onClick = onPause)
                    Spacer(Modifier.width(12.dp))
                }
                DownloadStatus.Paused -> {
                    TvControlButton(label = "Resume", onClick = onResume)
                    Spacer(Modifier.width(12.dp))
                }
                DownloadStatus.Failed -> {
                    TvControlButton(label = "Retry", onClick = onRetry)
                    Spacer(Modifier.width(12.dp))
                }
                DownloadStatus.Completed -> Unit
            }
            TvControlButton(label = "Delete", onClick = onDelete)
        }
    }
}
