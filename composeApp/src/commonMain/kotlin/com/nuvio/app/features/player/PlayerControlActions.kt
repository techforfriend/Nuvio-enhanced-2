package com.nuvio.app.features.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.StayCurrentLandscape
import androidx.compose.material.icons.rounded.StayCurrentPortrait
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapHoriz
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.AppIconResource
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.appIconPainter
import com.nuvio.app.core.ui.nuvioTypeScale
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.settings.NuvioEnhancedSettingsRepository

internal fun playerOrientationToggleIcon(isPortraitPlayback: Boolean): ImageVector =
    if (isPortraitPlayback) Icons.Rounded.StayCurrentLandscape else Icons.Rounded.StayCurrentPortrait

internal fun playerOrientationToggleLabel(isPortraitPlayback: Boolean): StringResource =
    if (isPortraitPlayback) Res.string.player_action_rotate_landscape else Res.string.player_action_rotate_portrait

@Composable
internal fun PlayerToolbar(
    isLocked: Boolean,
    onLockToggle: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Top actions moved to the bottom actions pill, except lock, which is
    // reverted to its original top position, in its glass capsule, before back.
    // hidePlayerLock hides only this capsule; back stays, and the centre
    // locked overlay still unlocks, so hiding it can never trap the player
    // in a locked state.
    val enhancedSettings by remember {
        NuvioEnhancedSettingsRepository.ensureLoaded()
        NuvioEnhancedSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        if (!enhancedSettings.hidePlayerLock) {
            Row(
                modifier = Modifier.playerLiquidGlass(
                    shape = PlayerGlassCapsule,
                    refractionHeight = 8.dp,
                    refractionAmount = 12.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerAction(
                    description = stringResource(
                        if (isLocked) Res.string.compose_player_unlock_controls else Res.string.compose_player_lock_controls,
                    ),
                    icon = if (isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    onClick = onLockToggle,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        NuvioBackButton(
            onClick = onBack,
            modifier = Modifier.playerLiquidGlass(CircleShape, refractionHeight = 8.dp, refractionAmount = 12.dp),
            shape = CircleShape,
            containerColor = Color.Transparent,
            contentColor = Color.White,
            buttonSize = 48.dp,
            iconSize = 24.dp,
            contentDescription = stringResource(Res.string.compose_player_close),
        )
    }
}

private const val ShowUpstreamStreamInfoAction = false

@Composable
internal fun PlayerControlActions(
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    showRemainingTime: Boolean,
    onRuntimeClick: () -> Unit,
    metrics: PlayerLayoutMetrics,
    resizeMode: PlayerResizeMode,
    onSubtitleClick: () -> Unit,
    onAudioClick: () -> Unit,
    onSourcesClick: (() -> Unit)?,
    onEpisodesClick: (() -> Unit)?,
    onChaptersClick: (() -> Unit)? = null,
    onNextEpisodeClick: (() -> Unit)?,
    onSpeedClick: () -> Unit,
    speedPickerVisible: Boolean = false,
    onSpeedSelected: (Float) -> Unit = {},
    onSpeedPickerDismiss: () -> Unit = {},
    onResizeModeClick: () -> Unit,
    onVideoSettingsClick: (() -> Unit)?,
    onOpenInExternalPlayer: (() -> Unit)?,
    onStreamInfoClick: () -> Unit,
    onSubmitIntroClick: (() -> Unit)?,
    qualityLabel: String? = null,
    onQualityClick: (() -> Unit)? = null,
    onSwitchEngineClick: (() -> Unit)? = null,
    onInfoClick: (() -> Unit)? = null,
    onRateClick: (() -> Unit)? = null,
    userRating: Int? = null,
    onPartyClick: (() -> Unit)? = null,
    partyActive: Boolean = false,
    onPictureInPictureClick: (() -> Unit)? = null,
    onCastClick: (() -> Unit)? = null,
    isCasting: Boolean = false,
    onOrientationToggleClick: (() -> Unit)? = null,
    isPortraitPlayback: Boolean = false,
    randomNextEpisodeMode: Boolean = false,
    onRandomNextEpisodeModeToggle: (() -> Unit)? = null,
    onInteraction: () -> Unit,
) {
    val enhancedSettings by remember {
        NuvioEnhancedSettingsRepository.ensureLoaded()
        NuvioEnhancedSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    val playerSettings by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    // Keyed by toolbar button so the user's "Player buttons" layout (Settings
    // -> Playback -> Player, default player only) decides the bar order, which
    // buttons stay on the bar, and which sit behind the arrow. Enhanced
    // "Hide player buttons" toggles still filter first: a toggled-off button
    // never appears, whatever the layout says.
    val availableActions = listOfNotNull(
        onPartyClick?.takeIf { !enhancedSettings.hidePlayerWatchParty }?.let {
            PlayerToolbarButton.WATCH_PARTY to PlayerControlAction(
                stringResource(Res.string.party_title), it,
                icon = Icons.Rounded.Groups,
                tint = if (partyActive) MaterialTheme.colorScheme.primary else Color.White,
            )
        },
        onPictureInPictureClick?.takeIf { !enhancedSettings.hidePlayerPip }?.let {
            PlayerToolbarButton.PICTURE_IN_PICTURE to PlayerControlAction(
                stringResource(Res.string.compose_player_picture_in_picture), it,
                icon = Icons.Rounded.PictureInPictureAlt,
            )
        },
        onQualityClick?.let {
            PlayerToolbarButton.QUALITY to PlayerControlAction(
                qualityLabel?.takeIf { label -> label.isNotBlank() } ?: "Quality", it,
                icon = Icons.Rounded.HighQuality,
            )
        },
        PlayerToolbarButton.SUBTITLES to PlayerControlAction(
            stringResource(Res.string.compose_player_subtitles), onSubtitleClick,
            painter = appIconPainter(AppIconResource.PlayerSubtitles),
        ),
        PlayerToolbarButton.AUDIO to PlayerControlAction(
            stringResource(Res.string.compose_player_audio), onAudioClick,
            painter = appIconPainter(AppIconResource.PlayerAudioFilled),
        ),
        onNextEpisodeClick?.let {
            PlayerToolbarButton.NEXT_EPISODE to PlayerControlAction(
                stringResource(Res.string.player_next_episode), it,
                icon = Icons.Rounded.SkipNext, iconSize = 40.dp,
            )
        },
        if (!enhancedSettings.hidePlayerSpeed) {
            PlayerToolbarButton.SPEED to PlayerControlAction(
                "${stringResource(Res.string.compose_player_speed)} ${formatPlaybackSpeedLabel(playbackSnapshot.playbackSpeed)}",
                onSpeedClick, icon = Icons.Rounded.Speed,
                popup = {
                    PlayerSpeedPopup(
                        visible = speedPickerVisible,
                        currentSpeed = playbackSnapshot.playbackSpeed,
                        onSpeedSelected = onSpeedSelected,
                        onDismiss = onSpeedPickerDismiss,
                    )
                },
            )
        } else null,
        if (!enhancedSettings.hidePlayerResizeMode) {
            PlayerToolbarButton.RESIZE to PlayerControlAction(
                stringResource(resizeMode.labelRes), onResizeModeClick,
                painter = appIconPainter(AppIconResource.PlayerAspectRatio),
            )
        } else null,
        onChaptersClick?.let {
            PlayerToolbarButton.CHAPTERS to PlayerControlAction(
                stringResource(Res.string.player_chapters), it,
                icon = Icons.AutoMirrored.Rounded.ListAlt,
            )
        },
        onSwitchEngineClick?.takeIf { !enhancedSettings.hidePlayerEngineSwitch }?.let {
            PlayerToolbarButton.SWITCH_ENGINE to PlayerControlAction(
                stringResource(Res.string.cd_switch_player_engine), it,
                icon = Icons.Rounded.SwapHoriz,
            )
        },
        onRandomNextEpisodeModeToggle?.takeIf { !enhancedSettings.hidePlayerShuffle }?.let {
            PlayerToolbarButton.SHUFFLE to PlayerControlAction(
                if (randomNextEpisodeMode) stringResource(Res.string.player_random_next_enabled)
                else stringResource(Res.string.player_random_next_disabled), it,
                icon = Icons.Rounded.Shuffle,
                tint = if (randomNextEpisodeMode) MaterialTheme.colorScheme.primary else Color.White,
            )
        },
        onOrientationToggleClick?.takeIf { !enhancedSettings.hidePlayerOrientation }?.let {
            PlayerToolbarButton.ORIENTATION to PlayerControlAction(
                stringResource(playerOrientationToggleLabel(isPortraitPlayback)), it,
                icon = playerOrientationToggleIcon(isPortraitPlayback),
            )
        },
        onCastClick?.takeIf { !enhancedSettings.hidePlayerCast }?.let {
            PlayerToolbarButton.CAST to PlayerControlAction(
                stringResource(Res.string.player_cast), it,
                icon = if (isCasting) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
            )
        },
        onSourcesClick?.let {
            PlayerToolbarButton.SOURCES to PlayerControlAction(
                stringResource(Res.string.compose_player_sources), it,
                painter = appIconPainter(AppIconResource.PlayerSource),
            )
        },
        onEpisodesClick?.let {
            PlayerToolbarButton.EPISODES to PlayerControlAction(
                stringResource(Res.string.compose_player_episodes), it,
                painter = appIconPainter(AppIconResource.PlayerEpisodes),
            )
        },
        onRateClick?.takeIf { !enhancedSettings.hidePlayerRating }?.let {
            PlayerToolbarButton.RATE to PlayerControlAction(
                userRating?.let { rating -> stringResource(Res.string.user_rating_action_rated, rating) }
                    ?: stringResource(Res.string.user_rating_action_rate),
                it,
                icon = if (userRating != null) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            )
        },
        onOpenInExternalPlayer?.takeIf { !enhancedSettings.hidePlayerExternalPlayer }?.let {
            PlayerToolbarButton.EXTERNAL_PLAYER to PlayerControlAction(
                stringResource(Res.string.streams_open_external_player), it,
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
            )
        },
        onInfoClick?.takeIf { !enhancedSettings.hidePlayerInfo }?.let {
            PlayerToolbarButton.PLAYBACK_INFO to PlayerControlAction(
                stringResource(Res.string.compose_player_playback_info), it,
                icon = Icons.Rounded.Info,
            )
        },
        onVideoSettingsClick?.takeIf { !enhancedSettings.hidePlayerVideoSettings }?.let {
            PlayerToolbarButton.VIDEO_SETTINGS to PlayerControlAction(
                stringResource(Res.string.player_action_video_settings), it,
                icon = Icons.Rounded.Build,
            )
        },
        onSubmitIntroClick?.takeIf { !enhancedSettings.hidePlayerSubmitIntro }?.let {
            PlayerToolbarButton.SUBMIT_INTRO to PlayerControlAction(
                stringResource(Res.string.submit_intro_action), it,
                icon = Icons.Rounded.Flag,
            )
        },
    ).toMap()
    val toolbarLayout = playerSettings.toolbarLayout
    val primaryActions = toolbarLayout.visibleButtons.mapNotNull { availableActions[it] }
    val secondaryActions = toolbarLayout.hiddenButtons.mapNotNull { availableActions[it] }
    // The old first-4 cap is gone: the layout itself decides what stays on
    // the bar (primary) and what sits behind the arrow (secondary). The bar
    // scrolls horizontally, so a long primary list still fits.
    val visibleActions = primaryActions
    val hiddenActions = secondaryActions
    val hasOverflow = hiddenActions.isNotEmpty()
    var expanded by remember(hasOverflow) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    LaunchedEffect(expanded, scrollState.maxValue) {
        if (expanded) scrollState.animateScrollTo(scrollState.maxValue) else scrollState.scrollTo(0)
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PlayerTimelineContentInset),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .playerLiquidGlass(
                            shape = PlayerGlassCapsule,
                            refractionHeight = 8.dp,
                            refractionAmount = 12.dp,
                        )
                        .horizontalScroll(scrollState),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (if (expanded) primaryActions + secondaryActions else visibleActions).forEach { action ->
                        Box {
                            PlayerAction(
                                description = action.description,
                                onClick = {
                                    onInteraction()
                                    action.onClick()
                                },
                                icon = action.icon,
                                painter = action.painter,
                                iconSize = action.iconSize,
                                tint = action.tint,
                            )
                            action.popup?.invoke()
                        }
                    }
                    if (hasOverflow) {
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
                }
            }
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .widthIn(min = 48.dp)
                    .playerLiquidGlass(
                        shape = PlayerGlassCapsule,
                        refractionHeight = 6.dp,
                        refractionAmount = 8.dp,
                    )
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(
                            if (showRemainingTime) Res.string.compose_player_show_elapsed_time else Res.string.compose_player_show_remaining_time,
                        ),
                        onClick = {
                            onRuntimeClick()
                            onInteraction()
                        },
                    )
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formatPlaybackRuntime(displayedPositionMs, playbackSnapshot.durationMs, showRemainingTime),
                    style = MaterialTheme.nuvioTypeScale.bodyMd.copy(fontSize = (metrics.timeSize.value + 2).sp),
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                )
            }
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
    tint: Color = Color.White,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        if (painter != null) {
            Icon(painter, description, tint = tint, modifier = Modifier.size(iconSize))
        } else if (icon != null) {
            Icon(icon, description, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

internal val PlayerGlassCapsule = RoundedCornerShape(percent = 50)

private data class PlayerControlAction(
    val description: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val painter: Painter? = null,
    val iconSize: Dp = 24.dp,
    val popup: (@Composable () -> Unit)? = null,
    val tint: Color = Color.White,
)
