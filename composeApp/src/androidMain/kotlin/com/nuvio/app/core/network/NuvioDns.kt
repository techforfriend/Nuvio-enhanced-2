package com.nuvio.app.core.network

import android.os.SystemClock
import android.util.Base64
import co.touchlab.kermit.Logger
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object NuvioDns : Dns {
    private val log = Logger.withTag("NuvioDns")

    @Volatile
    var provider: DnsProvider = DnsProvider.SYSTEM
        set(value) {
            field = value
            cache.clear()
        }

    private class CachedLookup(val addresses: List<InetAddress>, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, CachedLookup>()

    override fun lookup(hostname: String): List<InetAddress> {
        val current = provider
        val endpoint = DohEndpoint.forProvider(current)
        if (endpoint == null || hostname.isIpLiteral() || hostname.equals("localhost", ignoreCase = true)) {
            return Dns.SYSTEM.lookup(hostname)
        }
        val key = "${current.storageId}|${hostname.lowercase()}"
        val now = SystemClock.elapsedRealtime()
        cache[key]?.takeIf { it.expiresAtMs > now }?.let { return it.addresses }

        val answer = try {
            DohClient.resolve(endpoint, hostname)
        } catch (error: IOException) {
            log.w { "DoH lookup via ${current.displayName} failed for $hostname, using system DNS: ${error.message}" }
            return Dns.SYSTEM.lookup(hostname)
        }
        if (answer.addresses.isEmpty()) {
            throw UnknownHostException("$hostname has no address (DNS over HTTPS via ${current.displayName})")
        }
        cache[key] = CachedLookup(answer.addresses, now + answer.ttlSeconds.coerceIn(60L, 3_600L) * 1_000L)
        return answer.addresses
    }

    private val ipv4Literal = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    private fun String.isIpLiteral(): Boolean = contains(':') || ipv4Literal.matches(this)
}

internal class DohEndpoint private constructor(val host: String, val bootstrapAddresses: List<String>) {
    val url: String get() = "https://$host/dns-query"

    companion object {
        private val cloudflare = DohEndpoint("cloudflare-dns.com", listOf("1.1.1.1", "1.0.0.1"))
        private val google = DohEndpoint("dns.google", listOf("8.8.8.8", "8.8.4.4"))
        private val adguard = DohEndpoint("dns.adguard-dns.com", listOf("94.140.14.14", "94.140.15.15"))
        private val quad9 = DohEndpoint("dns.quad9.net", listOf("9.9.9.9", "149.112.112.112"))
        private val all = listOf(cloudflare, google, adguard, quad9)

        fun forProvider(provider: DnsProvider): DohEndpoint? = when (provider) {
            DnsProvider.SYSTEM -> null
            DnsProvider.CLOUDFLARE -> cloudflare
            DnsProvider.GOOGLE -> google
            DnsProvider.ADGUARD -> adguard
            DnsProvider.QUAD9 -> quad9
        }

        fun forHost(host: String): DohEndpoint? = all.firstOrNull { it.host.equals(host, ignoreCase = true) }
    }
}

internal class DohAnswer(val addresses: List<InetAddress>, val ttlSeconds: Long)

internal object DohClient {
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns { host ->
                DohEndpoint.forHost(host)?.bootstrapAddresses?.map { InetAddress.getByName(it) }
                    ?: Dns.SYSTEM.lookup(host)
            }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun resolve(endpoint: DohEndpoint, hostname: String): DohAnswer {
        val v4 = query(endpoint, hostname, TYPE_A)
        val v6 = runCatching { query(endpoint, hostname, TYPE_AAAA) }.getOrNull()
        val addresses = v4.addresses + v6?.addresses.orEmpty()
        val ttl = listOfNotNull(v4.ttlSeconds.takeIf { v4.addresses.isNotEmpty() }, v6?.ttlSeconds?.takeIf { v6.addresses.isNotEmpty() })
            .minOrNull() ?: 60L
        return DohAnswer(addresses, ttl)
    }

    private fun query(endpoint: DohEndpoint, hostname: String, type: Int): DohAnswer {
        val message = Base64.encodeToString(
            DnsMessage.query(hostname, type),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val request = Request.Builder()
            .url("${endpoint.url}?dns=$message")
            .header("Accept", "application/dns-message")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${endpoint.host}")
            val body = response.body?.bytes() ?: throw IOException("Empty response from ${endpoint.host}")
            return DnsMessage.parseAnswer(hostname, body, type)
        }
    }
}

internal object DnsMessage {
    private const val RCODE_NXDOMAIN = 3

    fun query(hostname: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        hostname.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "Invalid host name: $hostname" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf((type shr 8).toByte(), type.toByte(), 0, 1))
        return out.toByteArray()
    }

    fun parseAnswer(hostname: String, data: ByteArray, type: Int): DohAnswer {
        fun u8(at: Int): Int {
            if (at !in data.indices) throw IOException("Truncated DNS answer")
            return data[at].toInt() and 0xFF
        }
        fun u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
        fun u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        fun skipName(start: Int): Int {
            var at = start
            while (true) {
                val length = u8(at)
                when {
                    length == 0 -> return at + 1
                    length and 0xC0 == 0xC0 -> return at + 2
                    else -> at += length + 1
                }
            }
        }

        val rcode = u16(2) and 0x0F
        if (rcode == RCODE_NXDOMAIN) return DohAnswer(emptyList(), 60L)
        if (rcode != 0) throw IOException("DNS error code $rcode")
        val questions = u16(4)
        val answers = u16(6)
        var at = 12
        repeat(questions) { at = skipName(at) + 4 }
        val addresses = mutableListOf<InetAddress>()
        var ttl = Long.MAX_VALUE
        repeat(answers) {
            at = skipName(at)
            val recordType = u16(at)
            val recordTtl = u32(at + 4)
            val length = u16(at + 8)
            val dataStart = at + 10
            if (dataStart + length > data.size) throw IOException("Truncated DNS answer")
            val expected = if (type == 1) 4 else 16
            if (recordType == type && length == expected) {
                addresses += InetAddress.getByAddress(hostname, data.copyOfRange(dataStart, dataStart + length))
                ttl = minOf(ttl, recordTtl)
            }
            at = dataStart + length
        }
        return DohAnswer(addresses, if (ttl == Long.MAX_VALUE) 60L else ttl)
    }
}
