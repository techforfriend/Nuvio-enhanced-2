package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object NuvioEnhancedSettingsStorage {
    private const val payloadKey = "enhanced_settings_payload"

    // Fall back to the unscoped key for payloads written before profile
    // scoping, mirroring the Android actual.
    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))
            ?: NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }
}
