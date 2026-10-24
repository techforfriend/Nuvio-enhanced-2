package com.nuvio.app.features.ratings

import platform.Foundation.NSUserDefaults

internal actual object UserRatingsStorage {
    private fun key(profileId: Int) = "user_ratings_disabled_providers_$profileId"

    actual fun loadDisabledProviders(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(key(profileId))

    actual fun saveDisabledProviders(profileId: Int, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = key(profileId))
    }

    private fun enabledKey(profileId: Int) = "user_ratings_in_app_enabled_$profileId"

    actual fun loadInAppRatingEnabled(profileId: Int): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(enabledKey(profileId)) != null) defaults.boolForKey(enabledKey(profileId)) else null
    }

    actual fun saveInAppRatingEnabled(profileId: Int, enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = enabledKey(profileId))
    }
}
