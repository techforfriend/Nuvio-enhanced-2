package com.nuvio.app.core.storage

internal expect object AppFileCache {
    fun read(path: String): String?
    fun write(path: String, payload: String)
}
