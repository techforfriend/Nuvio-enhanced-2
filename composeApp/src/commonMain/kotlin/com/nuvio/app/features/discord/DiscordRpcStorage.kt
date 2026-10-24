package com.nuvio.app.features.discord

internal expect object DiscordRpcStorage {
    fun loadSettingsPayload(): String?
    fun saveSettingsPayload(payload: String)
    fun loadUserPayload(): String?
    fun saveUserPayload(payload: String?)
    fun loadToken(): String?
    fun saveToken(token: String?)
}
