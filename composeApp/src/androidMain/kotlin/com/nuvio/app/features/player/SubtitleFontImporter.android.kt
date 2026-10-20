package com.nuvio.app.features.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberSubtitleFontImporter(
    onPicked: (fileName: String?, bytes: ByteArray) -> Unit,
    onError: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val currentOnPicked by rememberUpdatedState(onPicked)
    val currentOnError by rememberUpdatedState(onError)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val limit = SubtitleFontRepository.MAX_FONT_BYTES + 1
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (total < limit) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    total += read
                }
                buffer.toByteArray()
            } ?: error("Unable to open font file")
            currentOnPicked(uri.displayName(context), bytes)
        }.onFailure { currentOnError() }
    }
    return remember(launcher) {
        {
            launcher.launch(
                arrayOf(
                    "font/ttf",
                    "font/otf",
                    "font/collection",
                    "font/sfnt",
                    "application/x-font-ttf",
                    "application/x-font-otf",
                    "application/font-sfnt",
                    "application/vnd.ms-opentype",
                    "application/octet-stream",
                ),
            )
        }
    }
}

private fun Uri.displayName(context: Context): String? {
    val cursor = context.contentResolver.query(this, null, null, null, null) ?: return lastPathSegment
    return cursor.use {
        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && it.moveToFirst()) it.getString(nameIndex) else lastPathSegment
    }
}
