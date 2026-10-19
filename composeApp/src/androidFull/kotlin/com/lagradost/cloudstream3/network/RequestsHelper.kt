package com.lagradost.cloudstream3.network

import android.content.Context
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.nicehttp.Requests
import okhttp3.Cache
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security

private val defaultHeaders = mapOf("user-agent" to USER_AGENT)

/**
 * Initializes the NiceHttp global client expected by CloudStream extensions.
 *
 * CloudStream performs this from its Application/Activity lifecycle. Mew hosts
 * the CloudStream runtime inside its own process, so the same initialization
 * must be performed explicitly here.
 */
fun Requests.initClient(context: Context) {
    this.baseClient = buildDefaultClient(context)
}

fun buildDefaultClient(context: Context): OkHttpClient {
    // Match CloudStream's Android networking setup. Conscrypt is important for
    // modern TLS interoperability on Android and is installed before OkHttp
    // creates connections.
    runCatching { Security.insertProviderAt(Conscrypt.newProvider(), 1) }

    return OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .cache(
            Cache(
                directory = File(context.cacheDir, "cloudstream_http_cache"),
                maxSize = 50L * 1024L * 1024L,
            ),
        )
        .build()
}

fun getHeaders(headers: Map<String, String>, cookie: Map<String, String>): Headers {
    val cookieHeader = if (cookie.isEmpty()) {
        emptyMap()
    } else {
        mapOf("Cookie" to cookie.entries.joinToString(" ") { "${it.key}=${it.value};" })
    }
    return (defaultHeaders + headers + cookieHeader).toHeaders()
}
