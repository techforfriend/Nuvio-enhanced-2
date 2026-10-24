package com.nuvio.app.core.storage

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
internal actual object AppFileCache {
    private val root = "${NSHomeDirectory()}/Library/Caches/NuvioFileCache"

    actual fun read(path: String): String? =
        NSString.stringWithContentsOfFile("$root/$path", NSUTF8StringEncoding, null)

    actual fun write(path: String, payload: String) {
        val target = "$root/$path"
        NSFileManager.defaultManager.createDirectoryAtPath(target.substringBeforeLast('/'), true, null, null)
        NSString.create(string = payload).writeToFile(target, true, NSUTF8StringEncoding, null)
    }
}
