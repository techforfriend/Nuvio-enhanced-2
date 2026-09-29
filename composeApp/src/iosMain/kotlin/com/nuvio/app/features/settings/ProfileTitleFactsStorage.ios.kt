package com.nuvio.app.features.settings

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
internal actual object ProfileTitleFactsStorage {
    private val directory = "${NSHomeDirectory()}/Library/Caches/NuvioProfileInsight"
    private val path = "$directory/title_facts.json"

    actual fun load(): String? =
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

    actual fun save(payload: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        // atomically = true writes to a temporary file and renames it into place.
        NSString.create(string = payload).writeToFile(path, true, NSUTF8StringEncoding, null)
    }
}
