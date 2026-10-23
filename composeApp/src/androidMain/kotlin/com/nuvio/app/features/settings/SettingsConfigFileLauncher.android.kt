package com.nuvio.app.features.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberSettingsConfigFileLauncher(
    onSaved: () -> Unit,
    onSaveFailed: () -> Unit,
    onLoaded: (content: String) -> Unit,
    onLoadFailed: () -> Unit,
): SettingsConfigFileLauncher {
    val context = LocalContext.current
    val currentOnSaved by rememberUpdatedState(onSaved)
    val currentOnSaveFailed by rememberUpdatedState(onSaveFailed)
    val currentOnLoaded by rememberUpdatedState(onLoaded)
    val currentOnLoadFailed by rememberUpdatedState(onLoadFailed)
    val pendingContent = remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val content = pendingContent.value
        pendingContent.value = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (content == null) {
            currentOnSaveFailed()
            return@rememberLauncherForActivityResult
        }
        runCatching {
            val resolver = context.contentResolver
            val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(uri)
                ?: error("Unable to open file")
            stream.use { it.write(content.encodeToByteArray()) }
        }.onSuccess {
            currentOnSaved()
        }.onFailure {
            currentOnSaveFailed()
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val content = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (content.isNullOrBlank()) currentOnLoadFailed() else currentOnLoaded(content)
    }

    return remember(saveLauncher, openLauncher) {
        SettingsConfigFileLauncher(
            launchSaver = { fileName, content ->
                pendingContent.value = content
                runCatching { saveLauncher.launch(fileName) }.onFailure {
                    pendingContent.value = null
                    currentOnSaveFailed()
                }
            },
            launchPicker = {
                runCatching {
                    openLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }.onFailure {
                    currentOnLoadFailed()
                }
            },
        )
    }
}
