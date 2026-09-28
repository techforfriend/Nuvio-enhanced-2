package com.nuvio.app.features.ratings

import platform.Foundation.NSUserDefaults

internal actual object UserRatingsStorage {
    private fun key(profileId: Int) = "user_ratings_disabled_providers_$profileId"

    actual fun loadDisabledProviders(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(key(profileId))

    actual fun saveDisabledProviders(profileId: Int, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = key(profileId))
    }
}
