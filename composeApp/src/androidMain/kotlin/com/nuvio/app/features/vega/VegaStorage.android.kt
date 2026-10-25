package com.nuvio.app.features.vega

import android.content.Context
import android.content.SharedPreferences

internal actual object VegaStorage {
    private const val preferencesName = "nuvio_vega_providers"
    private const val payloadKey = "vega_providers_payload"
    private const val scriptPrefix = "vega_script_"

    @Volatile
    private var preferences: SharedPreferences? = null

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

    actual fun loadPayload(): String? = getPrefs()?.getString(payloadKey, null)

    actual fun savePayload(payload: String) {
        getPrefs()?.edit()?.putString(payloadKey, payload)?.apply()
    }

    actual fun loadScript(key: String): String? = getPrefs()?.getString("$scriptPrefix$key", null)

    actual fun saveScript(key: String, script: String) {
        getPrefs()?.edit()?.putString("$scriptPrefix$key", script)?.apply()
    }
}
