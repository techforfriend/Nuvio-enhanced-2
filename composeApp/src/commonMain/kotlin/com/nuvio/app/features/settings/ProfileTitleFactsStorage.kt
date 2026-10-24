package com.nuvio.app.features.settings

// Disk cache for Profile Insight title facts. Public catalog metadata shared across profiles,
// stored in the platform cache directory. Blocking I/O: call off the main thread.
internal expect object ProfileTitleFactsStorage {
    fun load(): String?
    fun save(payload: String)
}
