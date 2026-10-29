package com.nuvio.app.features.tv

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PlayerPlaybackSnapshot

/**
 * Phase 3 TV player controls: bottom overlay with play/pause, -10s/+10s
 * seek buttons and a progress bar. No gestures here — everything is
 * focus + D-pad center, unlike the phone player.
 */
@Composable
fun TvControlButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            ),
    ) {
        Text(label)
    }
}

@Composable
fun TvPlayerControls(
    title: String,
    snapshot: PlayerPlaybackSnapshot,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    playButtonFocusRequester: FocusRequester,
    onOpenAudioTracks: () -> Unit,
    onOpenSubtitleTracks: () -> Unit,
    onCast: (() -> Unit)?,
    castAvailable: Boolean,
    onOpenParty: () -> Unit,
    partyCode: String?,
    modifier: Modifier = Modifier,
) {
    val duration = snapshot.durationMs
    val progress = if (duration > 0L) {
        (snapshot.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 24.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (partyCode != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Party: $partyCode",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
        if (snapshot.castDeviceName != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Casting to ${snapshot.castDeviceName}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTvTime(snapshot.positionMs),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            Text(
                text = formatTvTime(duration),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvControlButton(label = "-10s", onClick = onSeekBack)
            TvControlButton(
                label = if (isPlaying) "Pause" else "Play",
                onClick = onPlayPause,
                focusRequester = playButtonFocusRequester,
            )
            TvControlButton(label = "+10s", onClick = onSeekForward)
        }
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvControlButton(label = "Audio", onClick = onOpenAudioTracks)
            TvControlButton(label = "Subtitles", onClick = onOpenSubtitleTracks)
            if (castAvailable && onCast != null) {
                TvControlButton(label = "Cast", onClick = onCast)
            }
            TvControlButton(label = "Party", onClick = onOpenParty)
        }
    }
}

internal fun formatTvTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
