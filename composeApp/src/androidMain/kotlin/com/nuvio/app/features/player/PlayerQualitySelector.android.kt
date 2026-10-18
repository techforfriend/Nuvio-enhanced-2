package com.nuvio.app.features.player

import java.io.File

internal actual fun writeTemporaryHlsPlaylist(playlistText: String): String? = runCatching {
    val tempFile = File.createTempFile("playlist_", ".m3u8")
    tempFile.deleteOnExit()
    tempFile.writeText(playlistText)
    tempFile.toURI().toString()
}.getOrNull()
