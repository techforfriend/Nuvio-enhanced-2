package com.nuvio.app.features.player

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.features.livetv.formatLiveTvEpgTime
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_ends_at
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

@Composable
internal fun PlayerEndTimeLabel(
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    isPlaying: Boolean,
    metrics: PlayerLayoutMetrics,
    modifier: Modifier = Modifier,
) {
    if (durationMs <= 0L) return
    val pausedTick by produceState(0, isPlaying) {
        if (!isPlaying) {
            while (true) {
                delay(30_000)
                value += 1
            }
        }
    }
    val remainingMs = (durationMs - positionMs.coerceAtLeast(0L)).coerceAtLeast(0L)
    val speed = playbackSpeed.takeIf { it > 0f } ?: 1f
    val nowMs = pausedTick.let { Clock.System.now().toEpochMilliseconds() }
    val endAtMs = nowMs + (remainingMs / speed).toLong()
    Text(
        text = stringResource(Res.string.compose_player_ends_at, formatLiveTvEpgTime(endAtMs)),
        style = MaterialTheme.nuvioTypeScale.labelSm.copy(
            fontSize = metrics.metadataSize,
            fontWeight = FontWeight.Medium,
        ),
        color = Color.White.copy(alpha = 0.9f),
        maxLines = 1,
        modifier = modifier.padding(horizontal = PlayerTimelineContentInset, vertical = 2.dp),
    )
}
