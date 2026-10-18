package com.nuvio.app.features.settings

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object NuvioEnhancedSettingsStorage {
    private const val preferencesName = "nuvio_enhanced_settings"
    private const val payloadKey = "enhanced_settings_payload"

    @Volatile
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        NuvioEnhancedSettingsRepository.reload()
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

    actual fun loadPayload(): String? {
        val prefs = getPrefs() ?: return null
        val scopedKey = ProfileScopedKey.of(payloadKey)
        return prefs.getString(scopedKey, null) ?: prefs.getString(payloadKey, null)
    }

    actual fun savePayload(payload: String) {
        val prefs = getPrefs() ?: return
        val scopedKey = ProfileScopedKey.of(payloadKey)
        prefs
            .edit()
            .putString(scopedKey, payload)
            .putString(payloadKey, payload)
            .commit()
    }
}
