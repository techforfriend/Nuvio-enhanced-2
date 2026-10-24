package com.nuvio.app.features.discord

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.network.createApiHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

internal sealed class DiscordRpcImage {
    abstract suspend fun resolveImage(token: String, applicationId: String): String?

    data class DiscordImage(val image: String) : DiscordRpcImage() {
        override suspend fun resolveImage(token: String, applicationId: String): String =
            "mp:$image"
    }

    data class ExternalImage(val image: String) : DiscordRpcImage() {
        override suspend fun resolveImage(token: String, applicationId: String): String? =
            DiscordExternalAssets.resolve(image, token, applicationId)
    }
}

internal object DiscordExternalAssets {
    private const val TAG = "DiscordRPC"
    private const val MAX_CACHE_ENTRIES = 64

    private val client: HttpClient by lazy { createApiHttpClient() }
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, String>()

    suspend fun resolve(url: String, token: String, applicationId: String): String? {
        val normalized = url.trim()
        if (normalized.isEmpty()) return null
        if (normalized.startsWith("mp:")) return normalized
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return null

        mutex.withLock { cache[normalized] }?.let { return it }

        val resolved = fromDiscord(normalized, token, applicationId) ?: fromKizzyApi(normalized)
        if (resolved != null) {
            mutex.withLock {
                cache.remove(normalized)
                cache[normalized] = resolved
                while (cache.size > MAX_CACHE_ENTRIES) {
                    val oldest = cache.keys.firstOrNull() ?: break
                    cache.remove(oldest)
                }
            }
        }
        return resolved
    }

    fun clear() {
        if (mutex.tryLock()) {
            try {
                cache.clear()
            } finally {
                mutex.unlock()
            }
        }
    }

    private suspend fun fromDiscord(url: String, token: String, applicationId: String): String? =
        try {
            val body = buildJsonObject {
                put("urls", buildJsonArray { add(JsonPrimitive(url)) })
            }.toString()
            val response = client.post("${DiscordRpcConfig.DISCORD_API_URL}/applications/$applicationId/external-assets") {
                header(HttpHeaders.Authorization, token)
                setBody(TextContent(body, ContentType.Application.Json))
            }
            if (response.status.value !in 200..299) {
                InAppLogger.warn(TAG, "External asset request failed with ${response.status.value}")
                null
            } else {
                val root = json.parseToJsonElement(response.bodyAsText()) as? JsonArray
                val path = (root?.firstOrNull() as? JsonObject)
                    ?.get("external_asset_path")
                    ?.let { (it as? JsonPrimitive)?.contentOrNull }
                    ?.takeIf { it.isNotBlank() }
                path?.let { "mp:$it" }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            InAppLogger.warn(TAG, "External asset request failed: ${error.message.orEmpty()}")
            null
        }

    private suspend fun fromKizzyApi(url: String): String? =
        try {
            val response = client.get("${DiscordRpcConfig.KIZZY_API_URL}/image") {
                parameter("url", url)
            }
            if (response.status.value !in 200..299) {
                null
            } else {
                val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                val id = (root?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                id?.let { if (it.startsWith("mp:")) it else "mp:$it" }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            InAppLogger.warn(TAG, "Image proxy request failed: ${error.message.orEmpty()}")
            null
        }
}
