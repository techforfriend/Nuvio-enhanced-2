package com.nuvio.app.features.ratings

import android.content.Context
import android.content.SharedPreferences

internal actual object UserRatingsStorage {
    private const val preferencesName = "nuvio_user_ratings"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    private fun key(profileId: Int) = "disabled_providers_$profileId"

    actual fun loadDisabledProviders(profileId: Int): String? =
        preferences?.getString(key(profileId), null)

    actual fun saveDisabledProviders(profileId: Int, value: String) {
        preferences?.edit()?.putString(key(profileId), value)?.apply()
    }
}
