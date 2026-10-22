package com.nuvio.app.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DnsProvider(val storageId: String, val displayName: String) {
    SYSTEM("system", "System"),
    CLOUDFLARE("cloudflare", "Cloudflare"),
    GOOGLE("google", "Google"),
    ADGUARD("adguard", "AdGuard"),
    QUAD9("quad9", "Quad9"),
    ;

    companion object {
        fun fromStorage(id: String?): DnsProvider = entries.firstOrNull { it.storageId == id } ?: SYSTEM
    }
}

internal expect object DnsPlatform {
    val isSupported: Boolean
    fun load(): String?
    fun save(storageId: String)
    fun apply(provider: DnsProvider)
}

object DnsSettingsRepository {
    val isSupported: Boolean get() = DnsPlatform.isSupported

    private val _provider = MutableStateFlow(DnsProvider.SYSTEM)
    val provider: StateFlow<DnsProvider> = _provider.asStateFlow()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _provider.value = DnsProvider.fromStorage(DnsPlatform.load())
    }

    fun setProvider(provider: DnsProvider) {
        ensureLoaded()
        if (_provider.value == provider) return
        _provider.value = provider
        DnsPlatform.save(provider.storageId)
        DnsPlatform.apply(provider)
    }
}

internal expect fun platformHttpEngine(): io.ktor.client.engine.HttpClientEngine?
