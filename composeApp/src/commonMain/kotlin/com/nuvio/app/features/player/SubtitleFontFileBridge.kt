package com.nuvio.app.features.player

data class SubtitleFontImportResult(
    val fontName: String,
    val filePath: String,
)

internal expect object SubtitleFontFileBridge {
    fun importFont(onResult: (Result<SubtitleFontImportResult>) -> Unit)
}
