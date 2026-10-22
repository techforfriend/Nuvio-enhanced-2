package com.nuvio.app.core.network

import android.content.Context
import android.content.SharedPreferences

internal actual object DnsPlatform {
    private const val preferencesName = "nuvio_network"
    private const val providerKey = "dns_provider"
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        NuvioDns.provider = DnsProvider.fromStorage(load())
    }

    actual val isSupported: Boolean = true

    actual fun load(): String? = preferences?.getString(providerKey, null)

    actual fun save(storageId: String) {
        preferences?.edit()?.putString(providerKey, storageId)?.apply()
    }

    actual fun apply(provider: DnsProvider) {
        NuvioDns.provider = provider
    }
}

internal actual fun platformHttpEngine(): io.ktor.client.engine.HttpClientEngine? =
    io.ktor.client.engine.okhttp.OkHttp.create { preconfigured = SharedOkHttpClient.base }
