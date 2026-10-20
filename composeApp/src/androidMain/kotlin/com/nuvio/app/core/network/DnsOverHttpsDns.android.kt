package com.nuvio.app.core.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

fun DnsOverHttpsProvider.toOkHttpDns(): Dns {
    val url = endpointUrl ?: return IPv4FirstDns()
    return DnsOverHttps.Builder()
        .client(
            OkHttpClient.Builder()
                .dns(IPv4FirstDns())
                .build(),
        )
        .url(url.toHttpUrl())
        .bootstrapDnsHosts(bootstrapHosts())
        .build()
}

private fun DnsOverHttpsProvider.bootstrapHosts(): List<InetAddress> =
    when (this) {
        DnsOverHttpsProvider.Google -> listOf("8.8.8.8", "8.8.4.4")
        DnsOverHttpsProvider.Cloudflare -> listOf("1.1.1.1", "1.0.0.1")
        DnsOverHttpsProvider.AdGuard -> listOf("94.140.14.14", "94.140.15.15")
        DnsOverHttpsProvider.DnsWatch -> listOf("84.200.69.80", "84.200.70.40")
        DnsOverHttpsProvider.Quad9 -> listOf("9.9.9.9", "149.112.112.112")
        DnsOverHttpsProvider.DnsSb -> listOf("185.222.222.222", "45.11.45.11")
        DnsOverHttpsProvider.CanadianShield -> listOf("149.112.121.10", "149.112.122.10")
        DnsOverHttpsProvider.None -> emptyList()
    }.map { address ->
        InetAddress.getByName(address)
    }

private val dohCache = java.util.concurrent.ConcurrentHashMap<DnsOverHttpsProvider, Dns>()

class DynamicDnsOverHttps(private val fallbackDns: Dns = IPv4FirstDns()) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val provider = DnsOverHttpsSettingsRepository.snapshot().provider
        if (provider == DnsOverHttpsProvider.None) {
            return fallbackDns.lookup(hostname)
        }
        val dns = dohCache.getOrPut(provider) {
            provider.toOkHttpDns()
        }
        return try {
            val results = dns.lookup(hostname)
            if (results.isEmpty()) fallbackDns.lookup(hostname) else results
        } catch (_: Throwable) {
            fallbackDns.lookup(hostname)
        }
    }
}
