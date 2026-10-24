package com.nuvio.app.core.storage

import android.content.Context
import java.io.File

internal actual object AppFileCache {
    private const val directoryName = "nuvio_file_cache"

    @Volatile
    private var root: File? = null

    fun initialize(context: Context) {
        root = context.cacheDir.resolve(directoryName)
    }

    actual fun read(path: String): String? =
        root
            ?.resolve(path)
            ?.takeIf { it.isFile && it.length() > 0L }
            ?.let { runCatching { it.readText() }.getOrNull() }

    actual fun write(path: String, payload: String) {
        val target = root?.resolve(path) ?: return
        val directory = target.parentFile ?: return
        directory.mkdirs()
        val temporary = directory.resolve(".${target.name}.tmp")
        temporary.writeText(payload)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }
}
