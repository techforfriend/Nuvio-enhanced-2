package com.nuvio.app.features.updater

import android.content.Context

object AndroidAppUpdaterPlatform {
    private const val preferencesName = "nuvio_updater"
    private const val whatsNewCacheKey = "whats_new_cache"

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun getWhatsNewCache(): String? =
        appContext?.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            ?.getString(whatsNewCacheKey, null)

    fun setWhatsNewCache(payload: String?) {
        appContext?.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)?.edit()?.apply {
            if (payload == null) remove(whatsNewCacheKey) else putString(whatsNewCacheKey, payload)
        }?.apply()
    }
}
