package com.nuvio.app.features.settings

import android.content.Context
import java.io.File

internal actual object ProfileTitleFactsStorage {
    private const val directoryName = "profile_insight"
    private const val fileName = "title_facts.json"

    private var file: File? = null

    fun initialize(context: Context) {
        file = context.cacheDir.resolve(directoryName).resolve(fileName)
    }

    actual fun load(): String? =
        file
            ?.takeIf { it.isFile && it.length() > 0L }
            ?.let { runCatching { it.readText() }.getOrNull() }

    actual fun save(payload: String) {
        val target = file ?: return
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
