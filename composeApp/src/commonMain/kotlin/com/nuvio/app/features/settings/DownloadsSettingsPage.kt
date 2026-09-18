package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.downloads.DownloadsSettingsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_root_downloads_title
import nuvio.composeapp.generated.resources.settings_downloads_location_private
import nuvio.composeapp.generated.resources.settings_downloads_location_reset
import nuvio.composeapp.generated.resources.downloads_allow_mobile_data_description
import nuvio.composeapp.generated.resources.downloads_allow_mobile_data_title
import nuvio.composeapp.generated.resources.downloads_show_download_button_description
import nuvio.composeapp.generated.resources.downloads_show_download_button_title
import nuvio.composeapp.generated.resources.settings_downloads_location_title
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.downloadsSettingsContent(
    isTablet: Boolean,
) {
    item {
        val downloadLocationUri by DownloadsSettingsRepository.downloadLocationUri.collectAsStateWithLifecycle()
        val allowMobileData by DownloadsSettingsRepository.allowMobileDataDownloads.collectAsStateWithLifecycle()
        val showDownloadButton by DownloadsSettingsRepository.showDownloadButton.collectAsStateWithLifecycle()
        var showPicker by remember { mutableStateOf(false) }

        SettingsSection(
            title = stringResource(Res.string.compose_settings_root_downloads_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.downloads_allow_mobile_data_title),
                    description = stringResource(Res.string.downloads_allow_mobile_data_description),
                    checked = allowMobileData,
                    isTablet = isTablet,
                    onCheckedChange = DownloadsSettingsRepository::setAllowMobileDataDownloads,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.downloads_show_download_button_title),
                    description = stringResource(Res.string.downloads_show_download_button_description),
                    checked = showDownloadButton,
                    isTablet = isTablet,
                    onCheckedChange = DownloadsSettingsRepository::setShowDownloadButton,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_downloads_location_title),
                    description = downloadLocationUri?.let { formatUriForDisplay(it) } ?: stringResource(Res.string.settings_downloads_location_private),
                    isTablet = isTablet,
                    onClick = { showPicker = true },
                )
                if (downloadLocationUri != null) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsClickableRow(
                        title = stringResource(Res.string.settings_downloads_location_reset),
                        isTablet = isTablet,
                        onClick = { DownloadsSettingsRepository.setDownloadLocationUri(null) },
                    )
                }
            }
        }

        if (showPicker) {
            DownloadLocationPicker(
                onLocationSelected = { uri ->
                    DownloadsSettingsRepository.setDownloadLocationUri(uri)
                    showPicker = false
                },
                onDismiss = { showPicker = false },
            )
        }
    }
}

@Composable
internal expect fun DownloadLocationPicker(
    onLocationSelected: (uri: String) -> Unit,
    onDismiss: () -> Unit,
)

internal expect fun formatUriForDisplay(uri: String): String
