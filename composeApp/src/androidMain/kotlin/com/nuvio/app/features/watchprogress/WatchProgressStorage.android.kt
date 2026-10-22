package com.nuvio.app.features.watchprogress

import android.content.Context
import android.content.SharedPreferences

actual object WatchProgressStorage {
    private const val preferencesName = "nuvio_watch_progress"
    private const val payloadKey = "watch_progress_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(profileId: Int): String? {
        val prefs = preferences ?: return null
        val direct = prefs.getString("${payloadKey}_$profileId", null)?.trim()
        if (!direct.isNullOrEmpty()) return direct
        if (profileId != 1) {
            val fallback = prefs.getString("${payloadKey}_1", null)?.trim()
            if (!fallback.isNullOrEmpty()) return fallback
        }
        val unscoped = prefs.getString(payloadKey, null)?.trim()
        if (!unscoped.isNullOrEmpty()) return unscoped
        return null
    }

    actual fun savePayload(profileId: Int, payload: String) {
        preferences
            ?.edit()
            ?.putString("${payloadKey}_$profileId", payload)
            ?.putString(payloadKey, payload)
            ?.apply()
    }
}
