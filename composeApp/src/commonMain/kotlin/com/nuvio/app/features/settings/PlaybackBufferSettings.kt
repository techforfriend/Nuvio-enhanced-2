package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.PlaybackMemoryInfo
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.safeBufferLimitMb
import kotlin.math.ceil
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

// Fork: keeps its own buffer model (PlaybackBuffering.android.kt) instead of
// upstream's buffer engine / VOD cache, so this page drives the fork settings.
@Composable
internal fun PlaybackBufferSettings(
    isTablet: Boolean,
    settings: PlayerSettingsUiState,
) {
    val exoOptionsEnabled = !settings.externalPlayerEnabled && settings.androidPlaybackEngine != AndroidPlaybackEngine.Libmpv
    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 12.dp)) {
        SettingsSection(
            title = stringResource(Res.string.settings_playback_page_buffer),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_exo_native_memory),
                    description = stringResource(Res.string.settings_playback_exo_native_memory_description),
                    checked = settings.exoNativeMemoryEnabled,
                    enabled = exoOptionsEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setExoNativeMemoryEnabled,
                )
                val safeBufferLimitMb = remember { PlaybackMemoryInfo.safeBufferLimitMb() }
                val targetBufferLimitMb = remember(settings.exoNativeMemoryEnabled) {
                    val limit = if (settings.exoNativeMemoryEnabled) {
                        safeBufferLimitMb
                    } else {
                        minOf(safeBufferLimitMb, PlaybackMemoryInfo.javaHeapBufferLimitMb())
                    }
                    (limit / 50 * 50).coerceAtLeast(100)
                }
                PlaybackSettingsNote(
                    text = stringResource(
                        Res.string.settings_playback_exo_native_memory_info,
                        ceil(PlaybackMemoryInfo.deviceMemoryMb() / 1024.0).toInt().toString(),
                        safeBufferLimitMb.toString(),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    isTablet = isTablet,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_custom_buffers),
                    description = stringResource(Res.string.settings_playback_custom_buffers_description),
                    checked = settings.customPlaybackBuffersEnabled,
                    enabled = exoOptionsEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setCustomPlaybackBuffersEnabled,
                )
                if (settings.customPlaybackBuffersEnabled) {
                    PlaybackSettingsNote(
                        text = stringResource(Res.string.settings_playback_custom_buffers_warning),
                        color = MaterialTheme.colorScheme.error,
                        isTablet = isTablet,
                    )
                    SettingsSliderRow(
                        title = stringResource(Res.string.settings_playback_min_buffer),
                        value = settings.playbackMinBufferSeconds,
                        formatValue = { "${it}s" },
                        valueRange = 5..1200,
                        step = 5,
                        isTablet = isTablet,
                        enabled = exoOptionsEnabled,
                        onValueChange = PlayerSettingsRepository::setPlaybackMinBufferSeconds,
                    )
                    SettingsSliderRow(
                        title = stringResource(Res.string.settings_playback_max_buffer),
                        value = settings.playbackMaxBufferSeconds,
                        formatValue = { "${it}s" },
                        valueRange = 5..1200,
                        step = 5,
                        isTablet = isTablet,
                        enabled = exoOptionsEnabled,
                        onValueChange = PlayerSettingsRepository::setPlaybackMaxBufferSeconds,
                    )
                    SettingsSliderRow(
                        title = stringResource(Res.string.settings_playback_start_buffer),
                        value = settings.playbackStartBufferSeconds,
                        formatValue = { "${it}s" },
                        valueRange = 1..30,
                        step = 1,
                        isTablet = isTablet,
                        enabled = exoOptionsEnabled,
                        onValueChange = PlayerSettingsRepository::setPlaybackStartBufferSeconds,
                    )
                    SettingsSliderRow(
                        title = stringResource(Res.string.settings_playback_buffer_after_stall),
                        value = settings.playbackRebufferSeconds,
                        formatValue = { "${it}s" },
                        valueRange = 1..60,
                        step = 1,
                        isTablet = isTablet,
                        enabled = exoOptionsEnabled,
                        onValueChange = PlayerSettingsRepository::setPlaybackRebufferSeconds,
                    )
                    SettingsSliderRow(
                        title = stringResource(Res.string.settings_playback_back_buffer),
                        value = settings.playbackBackBufferSeconds,
                        formatValue = { "${it}s" },
                        valueRange = 0..120,
                        step = 5,
                        isTablet = isTablet,
                        enabled = exoOptionsEnabled,
                        onValueChange = PlayerSettingsRepository::setPlaybackBackBufferSeconds,
                    )
                    SettingsSliderRow(
                        title = stringResource(Res.string.settings_playback_target_buffer_size),
                        value = settings.playbackTargetBufferMb.coerceIn(50, targetBufferLimitMb),
                        formatValue = { "$it MB" },
                        valueRange = 50..targetBufferLimitMb,
                        step = 50,
                        isTablet = isTablet,
                        enabled = exoOptionsEnabled,
                        onValueChange = PlayerSettingsRepository::setPlaybackTargetBufferMb,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_vod_disk_cache),
                    description = stringResource(Res.string.settings_playback_vod_disk_cache_description),
                    checked = settings.vodDiskCacheEnabled,
                    enabled = exoOptionsEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setVodDiskCacheEnabled,
                )
                if (settings.vodDiskCacheEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_vod_disk_cache_auto_size),
                        description = stringResource(Res.string.settings_playback_vod_disk_cache_auto_size_description),
                        checked = settings.vodDiskCacheAutoSize,
                        enabled = exoOptionsEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setVodDiskCacheAutoSize,
                    )
                    if (!settings.vodDiskCacheAutoSize) {
                        SettingsSliderRow(
                            title = stringResource(Res.string.settings_playback_vod_disk_cache_size),
                            value = settings.vodDiskCacheSizeMb,
                            formatValue = { "$it MB" },
                            valueRange = 256..20480,
                            step = 256,
                            isTablet = isTablet,
                            enabled = exoOptionsEnabled,
                            onValueChange = PlayerSettingsRepository::setVodDiskCacheSizeMb,
                        )
                    }
                    PlaybackSettingsNote(
                        text = stringResource(Res.string.settings_playback_vod_disk_cache_note),
                        color = MaterialTheme.colorScheme.error,
                        isTablet = isTablet,
                    )
                }
            }
        }
    }
}
