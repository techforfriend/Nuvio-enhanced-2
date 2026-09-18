package com.nuvio.app.features.player

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.AppIconResource
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.appIconPainter
import com.nuvio.app.core.ui.nuvioTypeScale
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerToolbar(
    isLocked: Boolean,
    onLockToggle: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        PlayerAction(
            description = stringResource(
                if (isLocked) Res.string.compose_player_unlock_controls else Res.string.compose_player_lock_controls,
            ),
            icon = if (isLocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
            onClick = onLockToggle,
        )
        NuvioBackButton(
            onClick = onBack,
            containerColor = Color.Transparent,
            contentColor = Color.White,
            buttonSize = 48.dp,
            iconSize = 24.dp,
            contentDescription = stringResource(Res.string.compose_player_close),
        )
    }
}

@Composable
internal fun PlayerControlActions(
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    metrics: PlayerLayoutMetrics,
    resizeMode: PlayerResizeMode,
    onSubtitleClick: () -> Unit,
    onAudioClick: () -> Unit,
    onSourcesClick: (() -> Unit)?,
    onEpisodesClick: (() -> Unit)?,
    onNextEpisodeClick: (() -> Unit)?,
    onSpeedClick: () -> Unit,
    onResizeModeClick: () -> Unit,
    onVideoSettingsClick: (() -> Unit)?,
    onOpenInExternalPlayer: (() -> Unit)?,
    onSubmitIntroClick: (() -> Unit)?,
    onInteraction: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val startOffset = if (onNextEpisodeClick != null) (-13).dp else (-12).dp
    LaunchedEffect(expanded, scrollState.maxValue) {
        if (expanded) scrollState.animateScrollTo(scrollState.maxValue) else scrollState.scrollTo(0)
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PlayerTimelineContentInset),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).offset(x = startOffset).horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onNextEpisodeClick != null) {
                    PlayerAction(
                        stringResource(Res.string.player_next_episode), onNextEpisodeClick,
                        icon = Icons.Rounded.SkipNext, iconSize = 40.dp,
                    )
                }
                PlayerAction(
                    stringResource(Res.string.compose_player_subtitles), onSubtitleClick,
                    painter = appIconPainter(AppIconResource.PlayerSubtitles),
                )
                PlayerAction(
                    stringResource(Res.string.compose_player_audio), onAudioClick,
                    painter = appIconPainter(AppIconResource.PlayerAudioFilled),
                )
                if (onSourcesClick != null) {
                    PlayerAction(
                        stringResource(Res.string.compose_player_sources), onSourcesClick,
                        painter = appIconPainter(AppIconResource.PlayerSource),
                    )
                }
                if (onEpisodesClick != null) {
                    PlayerAction(
                        stringResource(Res.string.compose_player_episodes), onEpisodesClick,
                        painter = appIconPainter(AppIconResource.PlayerEpisodes),
                    )
                }
                if (expanded) {
                    PlayerAction(
                        "${stringResource(Res.string.compose_player_speed)} ${formatPlaybackSpeedLabel(playbackSnapshot.playbackSpeed)}",
                        onSpeedClick, icon = Icons.Rounded.Speed,
                    )
                    PlayerAction(
                        stringResource(resizeMode.labelRes), onResizeModeClick,
                        painter = appIconPainter(AppIconResource.PlayerAspectRatio),
                    )
                    if (onOpenInExternalPlayer != null) {
                        PlayerAction(
                            stringResource(Res.string.streams_open_external_player), onOpenInExternalPlayer,
                            icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        )
                    }
                    if (onVideoSettingsClick != null) {
                        PlayerAction(
                            stringResource(Res.string.player_action_video_settings), onVideoSettingsClick,
                            icon = Icons.Rounded.Build,
                        )
                    }
                    if (onSubmitIntroClick != null) {
                        PlayerAction(
                            stringResource(Res.string.submit_intro_action), onSubmitIntroClick,
                            icon = Icons.Rounded.Flag,
                        )
                    }
                }
                PlayerAction(
                    description = stringResource(
                        if (expanded) Res.string.compose_player_fewer_actions else Res.string.compose_player_more_actions,
                    ),
                    onClick = {
                        expanded = !expanded
                        onInteraction()
                    },
                    icon = if (expanded) Icons.AutoMirrored.Rounded.KeyboardArrowLeft else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                )
            }
            Text(
                text = "${formatPlaybackTime(displayedPositionMs)} / ${formatPlaybackTime(playbackSnapshot.durationMs)}",
                style = MaterialTheme.nuvioTypeScale.bodyMd.copy(fontSize = (metrics.timeSize.value + 2).sp),
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PlayerAction(
    description: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    painter: Painter? = null,
    iconSize: Dp = 24.dp,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        if (painter != null) {
            Icon(painter, description, tint = Color.White, modifier = Modifier.size(iconSize))
        } else if (icon != null) {
            Icon(icon, description, tint = Color.White, modifier = Modifier.size(iconSize))
        }
    }
}
