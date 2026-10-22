package com.nuvio.app.features.streams

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

actual object StreamLinkCacheStorage {
    private const val preferencesName = "nuvio_stream_link_cache"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadEntry(hashedKey: String): String? {
        val prefs = preferences ?: return null
        val scoped = prefs.getString(ProfileScopedKey.of(hashedKey), null)?.trim()
        if (!scoped.isNullOrEmpty()) return scoped
        val profile1 = prefs.getString(ProfileScopedKey.of(hashedKey, 1), null)?.trim()
        if (!profile1.isNullOrEmpty()) return profile1
        return prefs.getString(hashedKey, null)?.trim()
    }

    actual fun saveEntry(hashedKey: String, payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(hashedKey), payload)
            ?.putString(hashedKey, payload)
            ?.apply()
    }

    actual fun removeEntry(hashedKey: String) {
        preferences
            ?.edit()
            ?.remove(ProfileScopedKey.of(hashedKey))
            ?.remove(hashedKey)
            ?.apply()
    }
}
