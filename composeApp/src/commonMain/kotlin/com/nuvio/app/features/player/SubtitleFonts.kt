package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A font the user imported for subtitles. [fileName] is the file inside the app's font folder. */
data class SubtitleFont(
    val fileName: String,
    val familyName: String,
)

/** The app-private folder imported subtitle fonts are copied into. Kept on this device only. */
internal expect object SubtitleFontFiles {
    fun directoryPath(): String?
    fun listFileNames(): List<String>
    fun readBytes(fileName: String): ByteArray?
    fun write(fileName: String, bytes: ByteArray): Boolean
    fun delete(fileName: String)
}

sealed interface SubtitleFontImportResult {
    data class Imported(val font: SubtitleFont) : SubtitleFontImportResult
    data object NotAFont : SubtitleFontImportResult
    data object TooLarge : SubtitleFontImportResult
    data object Failed : SubtitleFontImportResult
}

object SubtitleFontRepository {
    /** Large enough for full CJK fonts (Noto Sans CJK is ~16 MB). */
    const val MAX_FONT_BYTES = 30L * 1024L * 1024L

    private val _fonts = MutableStateFlow<List<SubtitleFont>>(emptyList())
    val fonts: StateFlow<List<SubtitleFont>> = _fonts.asStateFlow()

    private var loaded = false
    // Replaced as a whole (never mutated) because the mpv thread reads it too.
    private var familyCache: Map<String, String> = emptyMap()

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        refresh()
    }

    fun refresh() {
        _fonts.value = SubtitleFontFiles.listFileNames()
            .filter { it.hasFontExtension() }
            .mapNotNull { fileName -> familyNameOf(fileName)?.let { SubtitleFont(fileName, it) } }
            .sortedBy { it.familyName.lowercase() }
    }

    fun find(fileName: String?): SubtitleFont? {
        if (fileName.isNullOrBlank()) return null
        ensureLoaded()
        return _fonts.value.firstOrNull { it.fileName == fileName }
    }

    /**
     * Absolute path of an imported font, or null when it is missing. Only checks the folder
     * listing, so it is cheap enough for the player to call on every style change.
     */
    fun pathOf(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val directory = SubtitleFontFiles.directoryPath() ?: return null
        if (fileName !in SubtitleFontFiles.listFileNames()) return null
        return "$directory/$fileName"
    }

    /** Family name libass/mpv should be given for an imported font. Reads the file once. */
    fun familyNameFor(fileName: String?): String? {
        if (pathOf(fileName) == null) return null
        return familyNameOf(fileName!!)
    }

    fun import(originalName: String?, bytes: ByteArray): SubtitleFontImportResult {
        ensureLoaded()
        if (bytes.size > MAX_FONT_BYTES) return SubtitleFontImportResult.TooLarge
        val family = OpenTypeNameReader.familyName(bytes) ?: return SubtitleFontImportResult.NotAFont
        val extension = OpenTypeNameReader.fileExtension(bytes)
        val baseName = (originalName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: family)
            .sanitizedFileStem()
        val fileName = uniqueFileName(baseName, extension)
        if (!SubtitleFontFiles.write(fileName, bytes)) return SubtitleFontImportResult.Failed
        familyCache = familyCache + (fileName to family)
        refresh()
        val font = _fonts.value.firstOrNull { it.fileName == fileName } ?: return SubtitleFontImportResult.Failed
        return SubtitleFontImportResult.Imported(font)
    }

    fun delete(font: SubtitleFont) {
        SubtitleFontFiles.delete(font.fileName)
        familyCache = familyCache - font.fileName
        refresh()
        val style = PlayerSettingsRepository.uiState.value.subtitleStyle
        if (style.customFontFile == font.fileName) {
            PlayerSettingsRepository.setSubtitleStyle(style.copy(customFontFile = null))
        }
    }

    private fun familyNameOf(fileName: String): String? =
        familyCache[fileName] ?: SubtitleFontFiles.readBytes(fileName)
            ?.let(OpenTypeNameReader::familyName)
            ?.also { familyCache = familyCache + (fileName to it) }

    private fun uniqueFileName(stem: String, extension: String): String {
        val existing = SubtitleFontFiles.listFileNames().toSet()
        var candidate = "$stem.$extension"
        var index = 2
        while (candidate in existing) {
            candidate = "$stem-$index.$extension"
            index++
        }
        return candidate
    }

    private fun String.hasFontExtension(): Boolean {
        val extension = substringAfterLast('.', "").lowercase()
        return extension == "ttf" || extension == "otf" || extension == "ttc" || extension == "otc"
    }

    private fun String.sanitizedFileStem(): String =
        map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .take(64)
            .ifBlank { "font" }
}

/**
 * Reads the family name from an OpenType/TrueType file (or the first face of a collection)
 * so the font can be listed and handed to libass/mpv, which match fonts by family name.
 */
internal object OpenTypeNameReader {
    private const val TAG_TTCF = 0x74746366
    private const val TAG_OTTO = 0x4F54544F
    private const val TAG_TRUE = 0x74727565
    private const val VERSION_TRUETYPE = 0x00010000
    private const val TAG_NAME = 0x6E616D65

    fun fileExtension(bytes: ByteArray): String = when (bytes.u32(0)) {
        TAG_TTCF -> "ttc"
        TAG_OTTO -> "otf"
        else -> "ttf"
    }

    fun familyName(bytes: ByteArray): String? = runCatching { readFamilyName(bytes) }.getOrNull()

    private fun readFamilyName(bytes: ByteArray): String? {
        var offset = 0
        if (bytes.u32(0) == TAG_TTCF) {
            if (bytes.u32(8) < 1) return null
            offset = bytes.u32(12)
        }
        val version = bytes.u32(offset)
        if (version != VERSION_TRUETYPE && version != TAG_OTTO && version != TAG_TRUE) return null
        val tableCount = bytes.u16(offset + 4)
        var nameOffset = -1
        for (index in 0 until tableCount) {
            val record = offset + 12 + index * 16
            if (bytes.u32(record) == TAG_NAME) {
                nameOffset = bytes.u32(record + 8)
                break
            }
        }
        if (nameOffset < 0) return null
        val count = bytes.u16(nameOffset + 2)
        val stringsStart = nameOffset + bytes.u16(nameOffset + 4)
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        for (index in 0 until count) {
            val record = nameOffset + 6 + index * 12
            val platformId = bytes.u16(record)
            val languageId = bytes.u16(record + 4)
            val nameId = bytes.u16(record + 6)
            if (nameId != 1 && nameId != 16) continue
            val length = bytes.u16(record + 8)
            val start = stringsStart + bytes.u16(record + 10)
            if (start < 0 || start + length > bytes.size) continue
            val value = when (platformId) {
                0, 3 -> bytes.decodeUtf16Be(start, length)
                1 -> bytes.decodeToString(start, start + length)
                else -> continue
            }.trim()
            if (value.isEmpty()) continue
            // Prefer the legacy family (1), which libass matches on, then Windows/Unicode
            // records, then English.
            val rank = (if (nameId == 1) 0 else 4) +
                (if (platformId == 1) 2 else 0) +
                (if (languageId == 0x0409 || languageId == 0) 0 else 1)
            if (rank < bestRank) {
                bestRank = rank
                best = value
            }
        }
        return best
    }

    private fun ByteArray.u16(at: Int): Int =
        ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

    private fun ByteArray.u32(at: Int): Int =
        (u16(at) shl 16) or u16(at + 2)

    private fun ByteArray.decodeUtf16Be(start: Int, length: Int): String {
        val chars = CharArray(length / 2) { index -> Char(u16(start + index * 2)) }
        return chars.concatToString()
    }
}
