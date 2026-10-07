@file:OptIn(UnstableApi::class)

package com.nuvio.app.features.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

internal object PlayerChapterReader {
    private const val WINDOW_SIZE = 128 * 1024
    private const val MAX_REQUESTS = 24
    private const val MAX_SCAN_STEPS = 64
    private const val MAX_CHAPTERS_BYTES = 4 * 1024 * 1024
    private const val MAX_SEEK_HEAD_BYTES = 64 * 1024
    private const val MAX_UDTA_BYTES = 2 * 1024 * 1024
    private const val FIRST_CHAPTER_SNAP_MS = 1_000L

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CHAPTERS = 0x1043A770L
    private const val ID_EDITION_ENTRY = 0x45B9L
    private const val ID_EDITION_FLAG_HIDDEN = 0x45BDL
    private const val ID_EDITION_FLAG_DEFAULT = 0x45DBL
    private const val ID_CHAPTER_ATOM = 0xB6L
    private const val ID_CHAPTER_TIME_START = 0x91L
    private const val ID_CHAPTER_FLAG_HIDDEN = 0x98L
    private const val ID_CHAPTER_FLAG_ENABLED = 0x4598L
    private const val ID_CHAPTER_DISPLAY = 0x80L
    private const val ID_CHAP_STRING = 0x85L
    private const val ID_CHAP_LANGUAGE = 0x437CL
    private const val ID_CHAP_LANGUAGE_BCP47 = 0x437DL

    suspend fun read(
        sourceUrl: String,
        dataSourceFactory: DataSource.Factory,
    ): List<PlayerChapter> = withContext(Dispatchers.IO) {
        runCatching {
            val reader = ChapterByteReader(Uri.parse(sourceUrl), dataSourceFactory) { isActive }
            val head = reader.read(0L, 12) ?: return@runCatching emptyList<PlayerChapter>()
            val chapters = when {
                head.isMatroska() -> readMatroskaChapters(reader)
                head.isMp4() -> readMp4Chapters(reader)
                else -> emptyList()
            }
            chapters.normalized()
        }.getOrDefault(emptyList())
    }

    private fun List<PlayerChapter>.normalized(): List<PlayerChapter> {
        val sorted = map { it.copy(title = it.title.trim()) }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
        if (sorted.size < 2) return emptyList()
        val first = sorted.first()
        return if (first.startMs in 1..FIRST_CHAPTER_SNAP_MS) {
            listOf(first.copy(startMs = 0L)) + sorted.drop(1)
        } else {
            sorted
        }
    }

    private fun ByteArray.isMatroska(): Boolean =
        size >= 4 &&
            this[0] == 0x1A.toByte() &&
            this[1] == 0x45.toByte() &&
            this[2] == 0xDF.toByte() &&
            this[3] == 0xA3.toByte()

    private fun ByteArray.isMp4(): Boolean {
        if (size < 8) return false
        return when (String(this, 4, 4, Charsets.ISO_8859_1)) {
            "ftyp", "moov", "mdat", "free", "wide" -> true
            else -> false
        }
    }

    private class ChapterByteReader(
        private val uri: Uri,
        private val factory: DataSource.Factory,
        private val isActive: () -> Boolean,
    ) {
        private var requests = 0
        private var windowStart = 0L
        private var window = ByteArray(0)

        fun read(position: Long, length: Int): ByteArray? {
            if (length <= 0 || position < 0L) return null
            if (position >= windowStart && position + length <= windowStart + window.size) {
                val offset = (position - windowStart).toInt()
                return window.copyOfRange(offset, offset + length)
            }
            val fetched = fetch(position, maxOf(length, WINDOW_SIZE)) ?: return null
            window = fetched
            windowStart = position
            if (fetched.size < length) return null
            return fetched.copyOfRange(0, length)
        }

        private fun fetch(position: Long, length: Int): ByteArray? {
            if (requests >= MAX_REQUESTS || !isActive()) return null
            requests += 1
            val dataSource = factory.createDataSource()
            return try {
                dataSource.open(
                    DataSpec.Builder()
                        .setUri(uri)
                        .setPosition(position)
                        .setLength(length.toLong())
                        .build(),
                )
                val buffer = ByteArray(length)
                var total = 0
                while (total < length) {
                    val read = dataSource.read(buffer, total, length - total)
                    if (read == C.RESULT_END_OF_INPUT) break
                    total += read
                }
                if (total == length) buffer else buffer.copyOf(total)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { dataSource.close() }
            }
        }
    }

    private class ElementHeader(val id: Long, val bodyStart: Long, val size: Long)

    private fun ebmlIdLength(first: Int): Int = when {
        first >= 0x80 -> 1
        first >= 0x40 -> 2
        first >= 0x20 -> 3
        first >= 0x10 -> 4
        else -> 0
    }

    private fun ebmlSizeLength(first: Int): Int =
        if (first == 0) 0 else Integer.numberOfLeadingZeros(first) - 24 + 1

    private fun decodeEbmlSize(buffer: ByteArray, position: Int, length: Int): Long {
        val mask = (1L shl (8 - length)) - 1L
        var value = (buffer[position].toLong() and 0xFFL) and mask
        var unknown = value == mask
        for (i in 1 until length) {
            val b = buffer[position + i].toLong() and 0xFFL
            value = (value shl 8) or b
            if (b != 0xFFL) unknown = false
        }
        return if (unknown) -1L else value
    }

    private fun ChapterByteReader.readElementHeader(position: Long): ElementHeader? {
        val header = read(position, 12) ?: return null
        val idLength = ebmlIdLength(header[0].toInt() and 0xFF)
        if (idLength == 0) return null
        var id = 0L
        for (i in 0 until idLength) id = (id shl 8) or (header[i].toLong() and 0xFFL)
        val sizeLength = ebmlSizeLength(header[idLength].toInt() and 0xFF)
        if (sizeLength == 0 || idLength + sizeLength > header.size) return null
        val size = decodeEbmlSize(header, idLength, sizeLength)
        return ElementHeader(id, position + idLength + sizeLength, size)
    }

    private inline fun ByteArray.forEachEbmlElement(
        start: Int,
        end: Int,
        block: (id: Long, bodyStart: Int, bodyEnd: Int) -> Unit,
    ) {
        var pos = start
        while (pos < end) {
            val idLength = ebmlIdLength(this[pos].toInt() and 0xFF)
            if (idLength == 0 || pos + idLength >= end) return
            var id = 0L
            for (i in 0 until idLength) id = (id shl 8) or (this[pos + i].toLong() and 0xFFL)
            pos += idLength
            val sizeLength = ebmlSizeLength(this[pos].toInt() and 0xFF)
            if (sizeLength == 0 || pos + sizeLength > end) return
            val size = decodeEbmlSize(this, pos, sizeLength)
            pos += sizeLength
            if (size < 0L || size > (end - pos).toLong()) return
            val bodyEnd = pos + size.toInt()
            block(id, pos, bodyEnd)
            pos = bodyEnd
        }
    }

    private fun ByteArray.readUnsigned(start: Int, end: Int): Long {
        var value = 0L
        for (i in start until minOf(end, start + 8)) {
            value = (value shl 8) or (this[i].toLong() and 0xFFL)
        }
        return value
    }

    private fun readMatroskaChapters(reader: ChapterByteReader): List<PlayerChapter> {
        val ebml = reader.readElementHeader(0L) ?: return emptyList()
        if (ebml.id != ID_EBML || ebml.size < 0L) return emptyList()
        val segment = reader.readElementHeader(ebml.bodyStart + ebml.size) ?: return emptyList()
        if (segment.id != ID_SEGMENT) return emptyList()
        val segmentStart = segment.bodyStart
        val segmentEnd = if (segment.size < 0L) Long.MAX_VALUE else segmentStart + segment.size
        var cursor = segmentStart
        var seekHeadChaptersPosition: Long? = null
        var steps = 0
        while (cursor < segmentEnd && steps < MAX_SCAN_STEPS) {
            steps += 1
            val header = reader.readElementHeader(cursor) ?: break
            if (header.id == ID_CHAPTERS) return loadMatroskaChapters(reader, header)
            if (header.id == ID_SEEK_HEAD &&
                seekHeadChaptersPosition == null &&
                header.size in 1..MAX_SEEK_HEAD_BYTES.toLong()
            ) {
                val body = reader.read(header.bodyStart, header.size.toInt())
                if (body != null) {
                    seekHeadChaptersPosition = findChaptersSeekPosition(body)?.let { segmentStart + it }
                }
            }
            if (header.id == ID_CLUSTER || header.size < 0L) break
            cursor = header.bodyStart + header.size
        }
        val target = seekHeadChaptersPosition ?: return emptyList()
        val header = reader.readElementHeader(target) ?: return emptyList()
        return if (header.id == ID_CHAPTERS) loadMatroskaChapters(reader, header) else emptyList()
    }

    private fun findChaptersSeekPosition(seekHead: ByteArray): Long? {
        var result: Long? = null
        seekHead.forEachEbmlElement(0, seekHead.size) { id, bodyStart, bodyEnd ->
            if (id == ID_SEEK) {
                var seekId = -1L
                var seekPosition = -1L
                seekHead.forEachEbmlElement(bodyStart, bodyEnd) { childId, childStart, childEnd ->
                    when (childId) {
                        ID_SEEK_ID -> seekId = seekHead.readUnsigned(childStart, childEnd)
                        ID_SEEK_POSITION -> seekPosition = seekHead.readUnsigned(childStart, childEnd)
                        else -> Unit
                    }
                }
                if (seekId == ID_CHAPTERS && seekPosition >= 0L && result == null) {
                    result = seekPosition
                }
            }
        }
        return result
    }

    private fun loadMatroskaChapters(reader: ChapterByteReader, header: ElementHeader): List<PlayerChapter> {
        if (header.size <= 0L || header.size > MAX_CHAPTERS_BYTES) return emptyList()
        val body = reader.read(header.bodyStart, header.size.toInt()) ?: return emptyList()
        return parseMatroskaChapters(body)
    }

    private fun parseMatroskaChapters(data: ByteArray): List<PlayerChapter> {
        var best: List<PlayerChapter>? = null
        var bestIsDefault = false
        data.forEachEbmlElement(0, data.size) { id, bodyStart, bodyEnd ->
            if (id == ID_EDITION_ENTRY) {
                var hidden = false
                var default = false
                data.forEachEbmlElement(bodyStart, bodyEnd) { childId, childStart, childEnd ->
                    when (childId) {
                        ID_EDITION_FLAG_HIDDEN -> hidden = data.readUnsigned(childStart, childEnd) != 0L
                        ID_EDITION_FLAG_DEFAULT -> default = data.readUnsigned(childStart, childEnd) != 0L
                        else -> Unit
                    }
                }
                if (!hidden) {
                    val atoms = ArrayList<PlayerChapter>()
                    data.collectChapterAtoms(bodyStart, bodyEnd, atoms)
                    if (atoms.isNotEmpty() && (best == null || (default && !bestIsDefault))) {
                        best = atoms
                        bestIsDefault = default
                    }
                }
            }
        }
        return best.orEmpty()
    }

    private class ChapterDisplay(val title: String, val languages: List<String>)

    private fun ByteArray.collectChapterAtoms(start: Int, end: Int, out: MutableList<PlayerChapter>) {
        forEachEbmlElement(start, end) { id, bodyStart, bodyEnd ->
            if (id == ID_CHAPTER_ATOM) {
                var startNs = -1L
                var hidden = false
                var enabled = true
                val displays = ArrayList<ChapterDisplay>(1)
                forEachEbmlElement(bodyStart, bodyEnd) { childId, childStart, childEnd ->
                    when (childId) {
                        ID_CHAPTER_TIME_START -> startNs = readUnsigned(childStart, childEnd)
                        ID_CHAPTER_FLAG_HIDDEN -> hidden = readUnsigned(childStart, childEnd) != 0L
                        ID_CHAPTER_FLAG_ENABLED -> enabled = readUnsigned(childStart, childEnd) != 0L
                        ID_CHAPTER_DISPLAY -> {
                            val display = readChapterDisplay(childStart, childEnd)
                            if (display != null) displays.add(display)
                        }
                        else -> Unit
                    }
                }
                if (startNs >= 0L && enabled && !hidden) {
                    out.add(PlayerChapter(title = pickChapterTitle(displays), startMs = startNs / 1_000_000L))
                }
                collectChapterAtoms(bodyStart, bodyEnd, out)
            }
        }
    }

    private fun ByteArray.readChapterDisplay(start: Int, end: Int): ChapterDisplay? {
        var title: String? = null
        val languages = ArrayList<String>(1)
        forEachEbmlElement(start, end) { id, bodyStart, bodyEnd ->
            when (id) {
                ID_CHAP_STRING -> title = String(this, bodyStart, bodyEnd - bodyStart, Charsets.UTF_8).trimEnd('\u0000')
                ID_CHAP_LANGUAGE, ID_CHAP_LANGUAGE_BCP47 ->
                    languages.add(String(this, bodyStart, bodyEnd - bodyStart, Charsets.US_ASCII).trimEnd('\u0000'))
                else -> Unit
            }
        }
        return title?.let { ChapterDisplay(it, languages) }
    }

    private fun pickChapterTitle(displays: List<ChapterDisplay>): String {
        if (displays.isEmpty()) return ""
        val locale = Locale.getDefault()
        val deviceLanguages = setOfNotNull(
            locale.language.takeIf { it.isNotBlank() }?.lowercase(),
            runCatching { locale.isO3Language }.getOrNull()?.takeIf { it.isNotBlank() }?.lowercase(),
        )
        displays.firstOrNull { display ->
            display.languages.any { language ->
                val normalized = language.lowercase()
                deviceLanguages.any { normalized == it || normalized.startsWith("$it-") }
            }
        }?.let { return it.title }
        val defaultLanguages = setOf("eng", "en", "und")
        displays.firstOrNull { display ->
            display.languages.isEmpty() || display.languages.any { it.lowercase() in defaultLanguages }
        }?.let { return it.title }
        return displays.first().title
    }

    private class BoxHeader(val type: String, val start: Long, val headerSize: Int, val size: Long)

    private fun ByteArray.readUInt32(at: Int): Long =
        ((this[at].toLong() and 0xFFL) shl 24) or
            ((this[at + 1].toLong() and 0xFFL) shl 16) or
            ((this[at + 2].toLong() and 0xFFL) shl 8) or
            (this[at + 3].toLong() and 0xFFL)

    private fun ByteArray.readUInt64(at: Int): Long =
        (readUInt32(at) shl 32) or readUInt32(at + 4)

    private fun ChapterByteReader.readBoxHeader(position: Long): BoxHeader? {
        val bytes = read(position, 16) ?: read(position, 8) ?: return null
        var size = bytes.readUInt32(0)
        val type = String(bytes, 4, 4, Charsets.ISO_8859_1)
        var headerSize = 8
        if (size == 1L) {
            if (bytes.size < 16) return null
            size = bytes.readUInt64(8)
            headerSize = 16
        } else if (size == 0L) {
            return null
        }
        if (size < headerSize) return null
        return BoxHeader(type, position, headerSize, size)
    }

    private fun readMp4Chapters(reader: ChapterByteReader): List<PlayerChapter> {
        var position = 0L
        var steps = 0
        while (steps < MAX_SCAN_STEPS) {
            steps += 1
            val box = reader.readBoxHeader(position) ?: return emptyList()
            if (box.type == "moov") return readMoovChapters(reader, box)
            position = box.start + box.size
        }
        return emptyList()
    }

    private fun readMoovChapters(reader: ChapterByteReader, moov: BoxHeader): List<PlayerChapter> {
        val end = moov.start + moov.size
        var position = moov.start + moov.headerSize
        var steps = 0
        while (position < end && steps < MAX_SCAN_STEPS) {
            steps += 1
            val child = reader.readBoxHeader(position) ?: return emptyList()
            if (child.type == "udta") {
                val bodyStart = child.start + child.headerSize
                val bodyLength = child.size - child.headerSize
                if (bodyLength <= 0L || bodyLength > MAX_UDTA_BYTES) return emptyList()
                val body = reader.read(bodyStart, bodyLength.toInt()) ?: return emptyList()
                return findChpl(body)
            }
            position = child.start + child.size
        }
        return emptyList()
    }

    private fun findChpl(udta: ByteArray): List<PlayerChapter> {
        var offset = 0
        while (offset + 8 <= udta.size) {
            val size = udta.readUInt32(offset)
            if (size < 8L || offset + size > udta.size) break
            val type = String(udta, offset + 4, 4, Charsets.ISO_8859_1)
            if (type == "chpl") {
                return parseChpl(udta, offset + 8, offset + size.toInt())
            }
            offset += size.toInt()
        }
        return emptyList()
    }

    private fun parseChpl(data: ByteArray, start: Int, end: Int): List<PlayerChapter> {
        if (end - start < 5) return emptyList()
        val version = data[start].toInt() and 0xFF
        var pos = start + 4
        if (version == 1) pos += 4
        if (pos >= end) return emptyList()
        val count = data[pos].toInt() and 0xFF
        pos += 1
        val chapters = ArrayList<PlayerChapter>(count)
        repeat(count) {
            if (pos + 9 > end) return chapters
            val startUnits = data.readUInt64(pos)
            pos += 8
            val titleLength = data[pos].toInt() and 0xFF
            pos += 1
            if (pos + titleLength > end) return chapters
            val title = String(data, pos, titleLength, Charsets.UTF_8)
            pos += titleLength
            chapters.add(PlayerChapter(title = title, startMs = startUnits / 10_000L))
        }
        return chapters
    }
}
