package com.nuvio.app.features.watchprogress

import platform.Foundation.NSUserDefaults

actual object WatchProgressStorage {
    private const val payloadKey = "watch_progress_payload"

    actual fun loadPayload(profileId: Int): String? {
        val userDefaults = NSUserDefaults.standardUserDefaults
        val direct = userDefaults.stringForKey("${payloadKey}_$profileId")?.trim()
        if (!direct.isNullOrEmpty()) return direct
        if (profileId != 1) {
            val fallback = userDefaults.stringForKey("${payloadKey}_1")?.trim()
            if (!fallback.isNullOrEmpty()) return fallback
        }
        val unscoped = userDefaults.stringForKey(payloadKey)?.trim()
        if (!unscoped.isNullOrEmpty()) return unscoped
        return null
    }

    actual fun savePayload(profileId: Int, payload: String) {
        val userDefaults = NSUserDefaults.standardUserDefaults
        userDefaults.setObject(payload, forKey = "${payloadKey}_$profileId")
        userDefaults.setObject(payload, forKey = payloadKey)
    }
}
