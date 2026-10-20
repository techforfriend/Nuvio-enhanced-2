package com.nuvio.app.features.player

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal actual object SubtitleFontFiles {
    private val directory: String? by lazy {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return@lazy null
        "$base/subtitle_fonts"
    }

    private fun dir(): String? {
        val path = directory ?: return null
        NSFileManager.defaultManager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return path
    }

    actual fun directoryPath(): String? = dir()

    actual fun listFileNames(): List<String> {
        val path = dir() ?: return emptyList()
        return NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, error = null)
            ?.filterIsInstance<String>()
            .orEmpty()
    }

    actual fun readBytes(fileName: String): ByteArray? {
        val path = dir() ?: return null
        val data = NSData.dataWithContentsOfFile("$path/$fileName") ?: return null
        val size = data.length.toInt()
        if (size == 0) return ByteArray(0)
        return ByteArray(size).apply {
            usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        }
    }

    actual fun write(fileName: String, bytes: ByteArray): Boolean {
        val path = dir() ?: return false
        if (bytes.isEmpty()) return false
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        return data.writeToFile("$path/$fileName", atomically = true)
    }

    actual fun delete(fileName: String) {
        val path = dir() ?: return
        NSFileManager.defaultManager.removeItemAtPath("$path/$fileName", error = null)
    }
}
