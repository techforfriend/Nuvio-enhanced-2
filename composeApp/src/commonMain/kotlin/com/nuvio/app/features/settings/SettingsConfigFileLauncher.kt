package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable

internal class SettingsConfigFileLauncher internal constructor(
    private val launchSaver: (fileName: String, content: String) -> Unit,
    private val launchPicker: () -> Unit,
) {
    fun save(fileName: String, content: String) = launchSaver(fileName, content)

    fun pick() = launchPicker()
}

@Composable
internal expect fun rememberSettingsConfigFileLauncher(
    onSaved: () -> Unit,
    onSaveFailed: () -> Unit,
    onLoaded: (content: String) -> Unit,
    onLoadFailed: () -> Unit,
): SettingsConfigFileLauncher
