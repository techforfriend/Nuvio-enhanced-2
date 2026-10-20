package com.nuvio.app.features.player

import android.content.Context
import java.io.File

internal actual object SubtitleFontFiles {
    private var directory: File? = null

    fun initialize(context: Context) {
        directory = File(context.applicationContext.filesDir, "subtitle_fonts")
    }

    private fun dir(): File? = directory?.apply { mkdirs() }

    actual fun directoryPath(): String? = dir()?.absolutePath

    actual fun listFileNames(): List<String> =
        dir()?.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()

    actual fun readBytes(fileName: String): ByteArray? =
        runCatching { dir()?.resolve(fileName)?.takeIf { it.isFile }?.readBytes() }.getOrNull()

    actual fun write(fileName: String, bytes: ByteArray): Boolean = runCatching {
        val target = dir()?.resolve(fileName) ?: return false
        val temp = File(target.parentFile, "$fileName.tmp")
        temp.writeBytes(bytes)
        temp.renameTo(target) || run { temp.delete(); false }
    }.getOrDefault(false)

    actual fun delete(fileName: String) {
        runCatching { dir()?.resolve(fileName)?.delete() }
    }
}

internal object SubtitleTypefaces {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Typeface>()

    fun forStyle(style: SubtitleStyleState): android.graphics.Typeface {
        val custom = SubtitleFontRepository.pathOf(style.customFontFile)?.let { path ->
            cache[path] ?: runCatching { android.graphics.Typeface.createFromFile(path) }
                .getOrNull()
                ?.also { cache[path] = it }
        }
        return when {
            custom == null -> if (style.bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            style.bold -> android.graphics.Typeface.create(custom, android.graphics.Typeface.BOLD)
            else -> custom
        }
    }
}
