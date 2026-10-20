package com.nuvio.app.features.discord

import platform.Foundation.NSUserDefaults

internal actual object DiscordRpcStorage {
    private const val settingsKey = "discord_rpc_settings_payload"
    private const val userKey = "discord_rpc_user_payload"

    actual fun loadSettingsPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(settingsKey)

    actual fun saveSettingsPayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = settingsKey)
    }

    actual fun loadUserPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(userKey)

    actual fun saveUserPayload(payload: String?) {
        if (payload == null) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(userKey)
        } else {
            NSUserDefaults.standardUserDefaults.setObject(payload, forKey = userKey)
        }
    }

    actual fun loadToken(): String? = null

    actual fun saveToken(token: String?) = Unit
}
