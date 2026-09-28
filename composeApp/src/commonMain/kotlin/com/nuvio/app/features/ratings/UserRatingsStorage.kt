package com.nuvio.app.features.ratings

/**
 * Device-local rating preferences. Kept out of the profile sync payload on purpose so the
 * shared sync server never receives a field Nuvio Official does not understand.
 */
internal expect object UserRatingsStorage {
    fun loadDisabledProviders(profileId: Int): String?
    fun saveDisabledProviders(profileId: Int, value: String)
}
