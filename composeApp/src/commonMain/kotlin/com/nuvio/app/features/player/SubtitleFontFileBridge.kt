package com.nuvio.app.features.player

data class SubtitleFontImportResult(
    val displayName: String,
    val path: String,
)

internal expect object SubtitleFontFileBridge {
    fun importFont(onResult: (Result<SubtitleFontImportResult>) -> Unit)
}
