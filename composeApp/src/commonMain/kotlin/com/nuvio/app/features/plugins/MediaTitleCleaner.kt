package com.nuvio.app.features.plugins

object MediaTitleCleaner {
    private val BRACKETED_QUALITY_REGEX = Regex(
        """\[[^\]]*\]|\((?i:[^\)]*(?:2160p|1080p|720p|480p|360p|4k|uhd|fhd|hd|hevc|x264|x265|web|bluray|hindi|dub|audio|sub|season|complete)[^\)]*)\)"""
    )
    private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val QUALITY_AND_TAGS_REGEX = Regex(
        """\b(?i:(?:2160p|1080p|720p|480p|360p|4k|uhd|fhd|hd|hevc|x264|x265|h264|h265|10bit|8bit|60fps|bluray|blu-ray|bdrip|web-dl|webdl|webrip|web|dvdrip|hdtv|hdrip|camrip|cam|telesync|ts|aac|ddp|dd5\.1|atmos|hindi|english|eng|hin|tamil|telugu|kannada|malayalam|bengali|marathi|dual\s*audio|multi\s*audio|esub|sub|dub|dubbed))\b"""
    )
    private val EXTRA_DELIMITERS_REGEX = Regex("""[\.\_\-\|\:]+""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    data class CleanResult(
        val cleanTitle: String,
        val year: Int?,
        val dedupeKey: String,
    )

    fun clean(rawTitle: String): CleanResult {
        val trimmed = rawTitle.trim()
        if (trimmed.isBlank()) return CleanResult("", null, "")

        // 1. Extract year if present
        val year = YEAR_REGEX.findAll(trimmed).lastOrNull()?.value?.toIntOrNull()

        // 2. Strip bracketed quality/audio chunks
        var cleaned = BRACKETED_QUALITY_REGEX.replace(trimmed, " ")

        // 3. Strip quality, audio, codec, format tags
        cleaned = QUALITY_AND_TAGS_REGEX.replace(cleaned, " ")

        // 4. Strip release delimiters like dots, dashes, pipes, underscores
        cleaned = EXTRA_DELIMITERS_REGEX.replace(cleaned, " ")

        // 5. If year was present, remove it from the title name itself
        if (year != null) {
            cleaned = cleaned.replace(year.toString(), " ")
        }

        cleaned = MULTI_SPACE_REGEX.replace(cleaned, " ").trim()
        val finalTitle = cleaned.ifBlank { trimmed }
        val dedupeKey = "${finalTitle.lowercase()}:${year ?: ""}".trim(':')

        return CleanResult(
            cleanTitle = finalTitle,
            year = year,
            dedupeKey = dedupeKey,
        )
    }
}
