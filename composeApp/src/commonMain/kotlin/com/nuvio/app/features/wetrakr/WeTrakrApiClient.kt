package com.nuvio.app.features.wetrakr

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal sealed interface WeTrakrDevicePoll {
    data class Approved(val tokens: WeTrakrTokens) : WeTrakrDevicePoll
    data object Pending : WeTrakrDevicePoll
    data object SlowDown : WeTrakrDevicePoll
    data class Finished(val error: WeTrakrAuthError) : WeTrakrDevicePoll
}

internal data class WeTrakrResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
) {
    fun json(): JsonElement? = runCatching { weTrakrJson.parseToJsonElement(body) }.getOrNull()

    fun header(name: String): String? = headers[name.lowercase()]?.trim()?.takeIf(String::isNotEmpty)

    fun errorCode(): String? {
        val root = json() as? JsonObject ?: return null
        return root.weTrakrString("error")
            ?: root["error"].weTrakrObject()?.weTrakrString("code")
            ?: root.weTrakrString("code")
    }

    fun errorMessage(): String? {
        val root = json() as? JsonObject ?: return null
        return root.weTrakrString("message")
            ?: root["error"].weTrakrObject()?.weTrakrString("message")
            ?: root.weTrakrString("error_description")
    }

    fun items(): List<JsonObject> {
        val root = json()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> listOf("items", "results", "data").firstNotNullOfOrNull { key -> root[key] as? JsonArray }
            else -> null
        }
        return array.orEmpty().mapNotNull { it as? JsonObject }
    }
}

internal class WeTrakrApiClient(
    private val store: WeTrakrAuthStore,
    private val client: HttpClient = createApiHttpClient(),
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val throttle = Mutex()
    private val refreshMutex = Mutex()
    private var lastRequestAtEpochMs = 0L
    private var blockedUntilEpochMs = 0L

    suspend fun requestDeviceCode(): WeTrakrDeviceAuthorization {
        val response = try {
            send(
                HttpMethod.Post,
                "/oauth/device/code",
                body = buildJsonObject { put("client_id", WeTrakrConfig.CLIENT_ID) }.toString(),
                token = null,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw WeTrakrAuthException(WeTrakrAuthError.REQUEST_FAILED)
        }
        when (response.status) {
            in 200..299 -> Unit
            423 -> throw WeTrakrAuthException(WeTrakrAuthError.APP_SUSPENDED)
            429 -> throw WeTrakrAuthException(WeTrakrAuthError.RATE_LIMITED)
            else -> throw WeTrakrAuthException(WeTrakrAuthError.REQUEST_FAILED)
        }
        val root = response.json() as? JsonObject ?: throw WeTrakrAuthException(WeTrakrAuthError.REQUEST_FAILED)
        val deviceCode = root.weTrakrString("device_code") ?: throw WeTrakrAuthException(WeTrakrAuthError.REQUEST_FAILED)
        val userCode = root.weTrakrString("user_code") ?: throw WeTrakrAuthException(WeTrakrAuthError.REQUEST_FAILED)
        val verificationUrl = root.weTrakrString("verification_url")
            ?: root.weTrakrString("verification_uri")
            ?: WeTrakrConfig.DEFAULT_VERIFICATION_URL
        val interval = (root.weTrakrLong("interval") ?: 5L).coerceIn(1L, 60L)
        val expiresIn = (root.weTrakrLong("expires_in") ?: 600L).coerceIn(60L, 3_600L)
        return WeTrakrDeviceAuthorization(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUrl = verificationUrl,
            intervalSeconds = interval,
            expiresAtEpochMs = now() + expiresIn * 1_000L,
        )
    }

    suspend fun pollDeviceToken(deviceCode: String): WeTrakrDevicePoll {
        val body = buildJsonObject {
            put("code", deviceCode)
            put("device_code", deviceCode)
            put("client_id", WeTrakrConfig.CLIENT_ID)
        }.toString()
        val response = send(HttpMethod.Post, "/oauth/device/token", body = body, token = null)
        return when (response.status) {
            in 200..299 -> parseTokens(response.json() as? JsonObject)?.let { WeTrakrDevicePoll.Approved(it) }
                ?: WeTrakrDevicePoll.Finished(WeTrakrAuthError.REQUEST_FAILED)
            400 -> {
                val code = response.errorCode().orEmpty()
                when {
                    code.contains("pending", ignoreCase = true) -> WeTrakrDevicePoll.Pending
                    code.contains("slow", ignoreCase = true) -> WeTrakrDevicePoll.SlowDown
                    code.contains("expired", ignoreCase = true) -> WeTrakrDevicePoll.Finished(WeTrakrAuthError.CODE_EXPIRED)
                    code.contains("denied", ignoreCase = true) -> WeTrakrDevicePoll.Finished(WeTrakrAuthError.ACCESS_DENIED)
                    else -> WeTrakrDevicePoll.Finished(WeTrakrAuthError.REQUEST_FAILED)
                }
            }
            429 -> WeTrakrDevicePoll.SlowDown
            410 -> WeTrakrDevicePoll.Finished(WeTrakrAuthError.CODE_EXPIRED)
            418 -> WeTrakrDevicePoll.Finished(WeTrakrAuthError.ACCESS_DENIED)
            423 -> WeTrakrDevicePoll.Finished(WeTrakrAuthError.APP_SUSPENDED)
            in 500..599 -> WeTrakrDevicePoll.Pending
            else -> WeTrakrDevicePoll.Finished(WeTrakrAuthError.REQUEST_FAILED)
        }
    }

    suspend fun account(scope: WeTrakrAuthScope): WeTrakrUser {
        val root = authorized(scope, HttpMethod.Get, "/account/settings").json() as? JsonObject
            ?: throw WeTrakrApiException(200, message = "Missing WeTrakr account")
        val info = root["info"].weTrakrObject()
        val user = root["user"].weTrakrObject()
        return WeTrakrUser(
            id = root.weTrakrLong("id") ?: user?.weTrakrLong("id") ?: info?.weTrakrLong("id") ?: 0L,
            username = info?.weTrakrString("username") ?: root.weTrakrString("username") ?: user?.weTrakrString("username"),
            displayName = info?.weTrakrString("name")
                ?: info?.weTrakrString("display_name")
                ?: root.weTrakrString("name")
                ?: user?.weTrakrString("name"),
            plan = root.weTrakrString("plan") ?: user?.weTrakrString("plan"),
        )
    }

    suspend fun logout(accessToken: String?, refreshToken: String?) {
        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) return
        try {
            send(
                HttpMethod.Post,
                "/oauth/logout",
                body = buildJsonObject { put("refresh_token", refreshToken) }.toString(),
                token = accessToken,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    suspend fun lastActivitiesFingerprint(scope: WeTrakrAuthScope): String? = try {
        val root = authorized(scope, HttpMethod.Get, "/sync/last_activities", allowNotFound = true)
            .takeIf { it.status in 200..299 }
            ?.json()
        root?.let { stripVolatile(it).toString() }
    } catch (error: CancellationException) {
        throw error
    } catch (error: WeTrakrAuthException) {
        throw error
    } catch (_: Exception) {
        null
    }

    suspend fun pagedList(
        scope: WeTrakrAuthScope,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        var page = 1
        while (page <= MAX_PAGES) {
            val response = authorized(
                scope,
                HttpMethod.Get,
                path,
                query + listOf("page" to page.toString(), "limit" to PAGE_LIMIT.toString()),
                allowNotFound = true,
            )
            if (response.status == 404) break
            val items = response.items()
            results += items
            val pageCount = response.header("x-pagination-page-count")?.toIntOrNull()
            val hasMore = if (pageCount != null) page < pageCount else items.size >= PAGE_LIMIT
            if (items.isEmpty() || !hasMore) break
            page += 1
        }
        return results
    }

    suspend fun compactList(
        scope: WeTrakrAuthScope,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val parameters = buildList {
                addAll(query)
                add("compact" to "true")
                add("limit" to COMPACT_LIMIT.toString())
                after?.let { add("after" to it) }
            }
            val response = authorized(scope, HttpMethod.Get, path, parameters, allowNotFound = true)
            if (response.status == 404) return results
            val items = response.items()
            results += items
            val next = response.header("x-pagination-next")
            if (items.isEmpty() || next == null || next == after) return results
            after = next
        }
        return results
    }

    suspend fun post(scope: WeTrakrAuthScope, path: String, body: JsonElement, allowNotFound: Boolean = false): WeTrakrResponse =
        authorized(scope, HttpMethod.Post, path, body = body, allowNotFound = allowNotFound)

    suspend fun delete(scope: WeTrakrAuthScope, path: String, body: JsonElement?, allowNotFound: Boolean = true): WeTrakrResponse =
        authorized(scope, HttpMethod.Delete, path, body = body, allowNotFound = allowNotFound)

    private suspend fun authorized(
        scope: WeTrakrAuthScope,
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonElement? = null,
        allowNotFound: Boolean = false,
    ): WeTrakrResponse {
        var attempt = 0
        var refreshed = false
        while (true) {
            attempt += 1
            val token = validAccessToken(scope)
            val response = send(method, path, query, body?.toString(), token)
            store.checkScope(scope)
            when {
                response.status == 401 && !refreshed -> {
                    refreshed = true
                    refreshAccessToken(scope, token)
                }
                response.status == 401 -> {
                    store.clearAuth(scope, WeTrakrAuthError.AUTHORIZATION_REVOKED, store.refreshToken())
                    throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
                }
                response.status == 429 -> {
                    val code = response.errorCode()
                    val waitSeconds = (
                        response.header("retry-after")?.toLongOrNull()
                            ?: response.header("ratelimit-reset")?.toDoubleOrNull()?.toLong()
                            ?: 30L
                        ).coerceIn(1L, 120L)
                    blockedUntilEpochMs = now() + waitSeconds * 1_000L
                    if (code.equals("QUOTA_EXCEEDED", ignoreCase = true) || attempt >= MAX_ATTEMPTS) {
                        throw WeTrakrApiException(429, code, blockedUntilEpochMs, response.errorMessage())
                    }
                    delay(waitSeconds * 1_000L)
                }
                response.status >= 500 && attempt < MAX_ATTEMPTS -> delay(1_500L * attempt)
                response.status == 404 -> {
                    if (allowNotFound) return response
                    throw WeTrakrNotFoundException(response.errorMessage())
                }
                response.status !in 200..299 -> throw WeTrakrApiException(
                    response.status,
                    response.errorCode(),
                    message = response.errorMessage(),
                )
                else -> return response
            }
        }
    }

    private suspend fun validAccessToken(scope: WeTrakrAuthScope): String {
        store.checkScope(scope)
        if (!store.needsRefresh()) store.accessToken()?.let { return it }
        return refreshAccessToken(scope, null)
    }

    private suspend fun refreshAccessToken(scope: WeTrakrAuthScope, failedToken: String?): String = refreshMutex.withLock {
        store.checkScope(scope)
        val current = store.accessToken()
        if (current != null && current != failedToken && !store.needsRefresh()) return@withLock current
        val refreshToken = store.refreshToken() ?: run {
            store.clearAuth(scope, WeTrakrAuthError.AUTHORIZATION_REVOKED)
            throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
        }
        val response = send(
            HttpMethod.Post,
            "/oauth/token/refresh",
            body = buildJsonObject { put("refresh_token", refreshToken) }.toString(),
            token = null,
        )
        store.checkScope(scope)
        when (response.status) {
            in 200..299 -> {
                val tokens = parseTokens(response.json() as? JsonObject)
                    ?: throw WeTrakrApiException(response.status, message = "Invalid WeTrakr token response")
                store.updateTokens(tokens, scope, refreshToken)
                tokens.accessToken
            }
            400, 401 -> {
                store.clearAuth(scope, WeTrakrAuthError.AUTHORIZATION_REVOKED, refreshToken)
                throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
            }
            else -> {
                if (current != null && current != failedToken) return@withLock current
                throw WeTrakrApiException(response.status, response.errorCode(), message = response.errorMessage())
            }
        }
    }

    private fun parseTokens(root: JsonObject?): WeTrakrTokens? {
        root ?: return null
        val accessToken = root.weTrakrString("access_token") ?: return null
        val refreshToken = root.weTrakrString("refresh_token") ?: root.weTrakrString("new_refresh_token") ?: return null
        val expiresAt = root.weTrakrLong("expires_in")?.takeIf { it > 0L }?.let { now() + it * 1_000L }
            ?: root.weTrakrEpochMs("expires_at")
        val username = root.weTrakrString("username") ?: root["user"].weTrakrObject()?.weTrakrString("username")
        return WeTrakrTokens(accessToken, refreshToken, expiresAt, username)
    }

    private fun stripVolatile(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.filterKeys { key -> key !in VOLATILE_KEYS }.mapValues { (_, value) -> stripVolatile(value) },
        )
        is JsonArray -> JsonArray(element.map(::stripVolatile))
        else -> element
    }

    private suspend fun send(
        requestMethod: HttpMethod,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: String? = null,
        token: String?,
    ): WeTrakrResponse {
        awaitSlot()
        val url = buildString {
            append(WeTrakrConfig.API_BASE_URL)
            append(path)
            if (query.isNotEmpty()) {
                append('?')
                append(query.joinToString("&") { (key, value) -> "${key.encodeURLParameter()}=${value.encodeURLParameter()}" })
            }
        }
        return client.prepareRequest(url) {
            method = requestMethod
            header("Accept", "application/json")
            header("User-Agent", WeTrakrConfig.USER_AGENT)
            header("wetrakr-api-key", WeTrakrConfig.CLIENT_ID)
            header("wetrakr-api-version", WeTrakrConfig.API_VERSION)
            token?.let { header("Authorization", "Bearer $it") }
            body?.let { setBody(TextContent(it, ContentType.Application.Json)) }
        }.execute { response ->
            WeTrakrResponse(
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
        private const val MIN_REQUEST_SPACING_MS = 320L
        private const val PAGE_LIMIT = 100
        private const val COMPACT_LIMIT = 1_000
        private const val MAX_PAGES = 60
        private val VOLATILE_KEYS = setOf("journal_visible_until", "now", "server_time", "generated_at")
        private val RESPONSE_HEADERS = listOf(
            "X-Pagination-Page-Count",
            "X-Pagination-Next",
            "Retry-After",
            "RateLimit-Reset",
        )
    }
}
