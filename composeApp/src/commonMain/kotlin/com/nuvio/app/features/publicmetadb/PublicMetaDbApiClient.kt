package com.nuvio.app.features.publicmetadb

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.contentLength
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal enum class PublicMetaDbKeyCheck { VALID, INVALID, RATE_LIMITED, BLOCKED, FAILED }

internal data class PublicMetaDbResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
) {
    fun json(): JsonElement? = runCatching { publicMetaDbJson.parseToJsonElement(body) }.getOrNull()

    fun header(name: String): String? = headers[name.lowercase()]?.trim()?.takeIf(String::isNotEmpty)

    val isChallenge: Boolean
        get() = status == 403 && (header("cf-mitigated") != null || body.contains("Just a moment", ignoreCase = true))

    fun errorMessage(): String? {
        val root = json() as? JsonObject ?: return null
        return root.publicMetaDbString("message")
            ?: root["error"].publicMetaDbObject()?.publicMetaDbString("message")
            ?: root.publicMetaDbString("error")
    }

    fun items(): List<JsonObject> {
        val root = json()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> ITEM_KEYS.firstNotNullOfOrNull { key -> root[key] as? JsonArray }
            else -> null
        }
        return array.orEmpty().mapNotNull { it as? JsonObject }
    }

    fun totalPages(): Int? {
        val root = json() as? JsonObject ?: return null
        return root.publicMetaDbInt("totalPages") ?: root.publicMetaDbInt("total_pages")
    }

    fun item(): JsonObject? {
        val root = json() as? JsonObject ?: return null
        return SINGLE_KEYS.firstNotNullOfOrNull { key -> root[key] as? JsonObject } ?: root
    }

    fun itemId(): String? {
        val root = json() as? JsonObject ?: return null
        return SINGLE_KEYS.firstNotNullOfOrNull { key -> (root[key] as? JsonObject)?.publicMetaDbString("id") }
            ?: root.publicMetaDbString("id")
            ?: root.publicMetaDbString("list_id")
            ?: root.publicMetaDbString("rating_id")
    }

    private companion object {
        val ITEM_KEYS = listOf("items", "results", "data", "watched_history", "lists", "ratings")
        val SINGLE_KEYS = listOf("item", "watch_entry", "rating_entry", "list", "data")
    }
}

internal class PublicMetaDbApiClient(
    private val store: PublicMetaDbAuthStore,
    private val client: HttpClient = createApiHttpClient(),
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val throttle = Mutex()
    private var lastRequestAtEpochMs = 0L
    private var blockedUntilEpochMs = 0L

    suspend fun verify(apiKey: String): PublicMetaDbKeyCheck {
        val key = apiKey.trim()
        if (key.isEmpty()) return PublicMetaDbKeyCheck.INVALID
        val response = try {
            send(HttpMethod.Get, "/api/external/lists", listOf("page" to "1", "perPage" to "1"), null, key)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return PublicMetaDbKeyCheck.FAILED
        }
        return when {
            response.status in 200..299 -> PublicMetaDbKeyCheck.VALID
            response.status == 401 -> PublicMetaDbKeyCheck.INVALID
            response.status == 429 -> PublicMetaDbKeyCheck.RATE_LIMITED
            response.isChallenge -> PublicMetaDbKeyCheck.BLOCKED
            response.status == 403 -> PublicMetaDbKeyCheck.INVALID
            else -> PublicMetaDbKeyCheck.FAILED
        }
    }

    suspend fun get(
        scope: PublicMetaDbAuthScope,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        allowNotFound: Boolean = false,
    ): PublicMetaDbResponse = authorized(scope, HttpMethod.Get, path, query, null, allowNotFound, allowConflict = false)

    suspend fun post(
        scope: PublicMetaDbAuthScope,
        path: String,
        body: JsonElement,
        query: List<Pair<String, String>> = emptyList(),
        allowNotFound: Boolean = false,
        allowConflict: Boolean = false,
    ): PublicMetaDbResponse = authorized(scope, HttpMethod.Post, path, query, body, allowNotFound, allowConflict)

    suspend fun request(
        scope: PublicMetaDbAuthScope,
        method: HttpMethod,
        path: String,
        body: JsonElement?,
        allowNotFound: Boolean = false,
    ): PublicMetaDbResponse = authorized(scope, method, path, emptyList(), body, allowNotFound, allowConflict = false)

    suspend fun delete(
        scope: PublicMetaDbAuthScope,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        allowNotFound: Boolean = true,
    ): PublicMetaDbResponse = authorized(scope, HttpMethod.Delete, path, query, null, allowNotFound, allowConflict = false)

    suspend fun paged(
        scope: PublicMetaDbAuthScope,
        path: String,
        perPage: Int,
        query: List<Pair<String, String>> = emptyList(),
        maxPages: Int = MAX_PAGES,
    ): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        var page = 1
        while (page <= maxPages) {
            val response = get(
                scope,
                path,
                query + listOf("page" to page.toString(), "perPage" to perPage.toString()),
                allowNotFound = true,
            )
            if (response.status == 404) break
            val items = response.items()
            results += items
            val totalPages = response.totalPages()
            val hasMore = if (totalPages != null) page < totalPages else items.size >= perPage
            if (items.isEmpty() || !hasMore) break
            page += 1
        }
        return results
    }

    private suspend fun authorized(
        scope: PublicMetaDbAuthScope,
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>>,
        body: JsonElement?,
        allowNotFound: Boolean,
        allowConflict: Boolean,
    ): PublicMetaDbResponse {
        var attempt = 0
        while (true) {
            attempt += 1
            store.checkScope(scope)
            val apiKey = store.apiKey() ?: throw PublicMetaDbAuthException(PublicMetaDbAuthError.AUTHORIZATION_REVOKED)
            val response = send(method, path, query, body?.toString(), apiKey)
            store.checkScope(scope)
            when {
                response.status == 401 -> {
                    store.clearAuth(scope, PublicMetaDbAuthError.AUTHORIZATION_REVOKED, apiKey)
                    throw PublicMetaDbAuthException(PublicMetaDbAuthError.AUTHORIZATION_REVOKED)
                }
                response.status == 429 -> {
                    val waitSeconds = (
                        response.header("retry-after")?.toLongOrNull()
                            ?: response.header("x-ratelimit-reset-after")?.toDoubleOrNull()?.toLong()
                            ?: 10L
                        ).coerceIn(1L, 60L)
                    blockedUntilEpochMs = now() + waitSeconds * 1_000L
                    if (attempt >= MAX_ATTEMPTS) {
                        throw PublicMetaDbApiException(429, blockedUntilEpochMs, response.errorMessage())
                    }
                    delay(waitSeconds * 1_000L)
                }
                response.status >= 500 && method == HttpMethod.Get && attempt < MAX_ATTEMPTS -> delay(1_500L * attempt)
                response.status == 404 -> {
                    if (allowNotFound) return response
                    throw PublicMetaDbNotFoundException(response.errorMessage())
                }
                response.status == 409 && allowConflict -> return response
                response.status !in 200..299 -> throw PublicMetaDbApiException(
                    response.status,
                    message = response.errorMessage(),
                )
                else -> return response
            }
        }
    }

    private suspend fun send(
        requestMethod: HttpMethod,
        path: String,
        query: List<Pair<String, String>>,
        body: String?,
        apiKey: String,
    ): PublicMetaDbResponse {
        awaitSlot()
        val url = buildString {
            append(PublicMetaDbConfig.API_BASE_URL)
            append(path)
            if (query.isNotEmpty()) {
                append(if ('?' in path) '&' else '?')
                append(query.joinToString("&") { (key, value) -> "${key.encodeURLParameter()}=${value.encodeURLParameter()}" })
            }
        }
        return client.prepareRequest(url) {
            method = requestMethod
            header("Accept", "application/json")
            header("User-Agent", PublicMetaDbConfig.USER_AGENT)
            header("Authorization", "Bearer $apiKey")
            body?.let { setBody(TextContent(it, ContentType.Application.Json)) }
        }.execute { response ->
            PublicMetaDbResponse(
                status = response.status.value,
                body = readBoundedResponseBody(response.bodyAsChannel(), response.contentLength()),
                headers = RESPONSE_HEADERS.mapNotNull { name ->
                    response.headers[name]?.let { value -> name.lowercase() to value }
                }.toMap(),
            )
        }
    }

    private suspend fun awaitSlot() {
        throttle.withLock {
            val current = now()
            val waitUntil = maxOf(lastRequestAtEpochMs + MIN_REQUEST_SPACING_MS, blockedUntilEpochMs)
            if (waitUntil > current) delay(waitUntil - current)
            lastRequestAtEpochMs = now()
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val MIN_REQUEST_SPACING_MS = 60L
        private const val MAX_PAGES = 200
        private val RESPONSE_HEADERS = listOf(
            "Retry-After",
            "X-RateLimit-Reset-After",
            "cf-mitigated",
        )

        internal fun pathSegment(value: String): String = value.encodeURLParameter()
    }
}
