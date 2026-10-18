package com.nuvio.app.features.streams

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object StreamSourcePreferencesStorage {
    private const val preferencesName = "nuvio_stream_source_preferences"
    private const val pinnedSourcesKey = "pinned_sources"
    private const val pinnedSourceIdKey = "pinned_source_id"
    private const val pinnedSourceNameKey = "pinned_source_name"

    @Volatile
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    private fun getPrefs(): SharedPreferences? {
        if (preferences == null) {
            runCatching {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val method = activityThreadClass.getMethod("currentApplication")
                val app = method.invoke(null) as? Context
                if (app != null) {
                    preferences = app.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                }
            }
        }
        return preferences
    }

    actual fun loadPinnedSourcesPayload(): String? {
        val prefs = getPrefs() ?: return null
        return prefs.getString(ProfileScopedKey.of(pinnedSourcesKey), null)
    }

    actual fun savePinnedSourcesPayload(payload: String) {
        val prefs = getPrefs() ?: return
        prefs
            .edit()
            .putString(ProfileScopedKey.of(pinnedSourcesKey), payload)
            .remove(ProfileScopedKey.of(pinnedSourceIdKey))
            .remove(ProfileScopedKey.of(pinnedSourceNameKey))
            .apply()
    }

    actual fun loadLegacyPinnedSourceId(): String? {
        val prefs = getPrefs() ?: return null
        return prefs.getString(ProfileScopedKey.of(pinnedSourceIdKey), null)
    }

    actual fun loadLegacyPinnedSourceName(): String? {
        val prefs = getPrefs() ?: return null
        return prefs.getString(ProfileScopedKey.of(pinnedSourceNameKey), null)
    }

    actual fun clearPinnedSources() {
        val prefs = getPrefs() ?: return
        prefs
            .edit()
            .remove(ProfileScopedKey.of(pinnedSourcesKey))
            .remove(ProfileScopedKey.of(pinnedSourceIdKey))
            .remove(ProfileScopedKey.of(pinnedSourceNameKey))
            .apply()
    }
}
