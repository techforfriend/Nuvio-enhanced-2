package com.nuvio.app.core.network

internal actual object DnsPlatform {
    actual val isSupported: Boolean = false
    actual fun load(): String? = null
    actual fun save(storageId: String) = Unit
    actual fun apply(provider: DnsProvider) = Unit
}

internal actual fun platformHttpEngine(): io.ktor.client.engine.HttpClientEngine? = null
