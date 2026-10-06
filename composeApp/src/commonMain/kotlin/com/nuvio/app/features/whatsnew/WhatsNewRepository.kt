package com.nuvio.app.features.whatsnew

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.updater.AppUpdaterPlatform
import com.nuvio.app.features.updater.GITHUB_API_BASE
import com.nuvio.app.features.updater.GITHUB_OWNER
import com.nuvio.app.features.updater.GITHUB_REPO
import com.nuvio.app.features.updater.GitHubReleaseDto
import com.nuvio.app.features.updater.ReleaseSelector
import com.nuvio.app.features.updater.UpdateChannel
import com.nuvio.app.features.updater.UpdatePreferences
import com.nuvio.app.features.updater.VersionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

private const val RELEASE_PAGE_SIZE = 100
private const val CACHE_TTL_MILLIS = 24L * 60L * 60L * 1000L
private const val FAILED_REFRESH_RETRY_MILLIS = 6L * 60L * 60L * 1000L
private const val MAX_RESPONSE_BODY_BYTES = 2 * 1024 * 1024

@Serializable
private data class WhatsNewCacheEnvelope(
    val channel: String,
    val fetchedAtMillis: Long,
    val lastFailedAttemptAtMillis: Long = 0L,
    val etag: String? = null,
    val body: String,
)

internal object WhatsNewRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun load(
        channel: UpdateChannel = UpdatePreferences.shared.channel.value,
        currentVersion: String = AppVersionConfig.VERSION_NAME,
        forceRefresh: Boolean = false,
        matchBaseVersion: Boolean = false,
    ): Result<WhatsNewContent> {
        return try {
            Result.success(
                loadInternal(
                    channel = channel,
                    currentVersion = currentVersion,
                    matchBaseVersion = matchBaseVersion,
                    forceRefresh = forceRefresh,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun loadInternal(
        channel: UpdateChannel,
        currentVersion: String,
        forceRefresh: Boolean,
        matchBaseVersion: Boolean,
    ): WhatsNewContent {
        val now = Clock.System.now().toEpochMilliseconds()
        val cached = readCache(channel)
        val cachedContent = cached?.let {
            decodeContent(
                body = it.body,
                channel = channel,
                currentVersion = currentVersion,
                matchBaseVersion = matchBaseVersion,
                fromCache = true,
                isStale = false,
                fetchedAtMillis = it.fetchedAtMillis,
            )
        }
        if (
            !forceRefresh &&
            cachedContent != null &&
            now - cached.fetchedAtMillis < CACHE_TTL_MILLIS
        ) {
            return cachedContent
        }

        if (
            !forceRefresh &&
            cachedContent != null &&
            cached.lastFailedAttemptAtMillis > 0L &&
            now - cached.lastFailedAttemptAtMillis < FAILED_REFRESH_RETRY_MILLIS
        ) {
            return cachedContent.copy(isStale = true)
        }

        val response = try {
            httpRequestRaw(
                method = "GET",
                url = "$GITHUB_API_BASE/repos/$GITHUB_OWNER/$GITHUB_REPO/releases?per_page=$RELEASE_PAGE_SIZE&page=1",
                headers = buildMap {
                    put("Accept", "application/vnd.github+json")
                    put("User-Agent", "NuvioMobile")
                    cachedContent?.let {
                        cached?.etag?.takeIf { it.isNotBlank() }?.let { put("If-None-Match", it) }
                    }
                },
                body = "",
                maxResponseBodyBytes = MAX_RESPONSE_BODY_BYTES,
            ).also { response ->
                check(response.status == 304 || response.status in 200..299) {
                    "GitHub release history request failed: " + response.status
                }
                check(isTrustedApiResponseUrl(response.url)) {
                    "GitHub release history response came from an unexpected URL"
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            cached?.let {
                persistCache(it.copy(lastFailedAttemptAtMillis = now))
            }
            if (cachedContent != null) {
                return cachedContent.copy(isStale = true)
            }
            throw error
        }

        return when (response.status) {
            304 -> {
                val existing = cached ?: error("GitHub returned 304 without cached release history")
                val updated = existing.copy(
                    fetchedAtMillis = now,
                    lastFailedAttemptAtMillis = 0L,
                )
                persistCache(updated)
                cachedContent?.copy(
                    fromCache = true,
                    isStale = false,
                    fetchedAtMillis = now,
                ) ?: error("Cached release history is invalid")
            }

            else -> {
                val etag = response.headers.entries
                    .firstOrNull { it.key.equals("ETag", ignoreCase = true) }
                    ?.value
                val envelope = WhatsNewCacheEnvelope(
                    channel = channel.name,
                    fetchedAtMillis = now,
                    lastFailedAttemptAtMillis = 0L,
                    etag = etag,
                    body = compactReleaseBody(response.body) ?: response.body,
                )
                val content = decodeContent(
                    body = response.body,
                    channel = channel,
                    currentVersion = currentVersion,
                    matchBaseVersion = matchBaseVersion,
                    fromCache = false,
                    isStale = false,
                    fetchedAtMillis = now,
                ) ?: error("GitHub returned release history without a valid release")
                persistCache(envelope)
                content
            }
        }
    }

    private fun readCache(channel: UpdateChannel): WhatsNewCacheEnvelope? {
        val raw = AppUpdaterPlatform.getWhatsNewCache() ?: return null
        return runCatching {
            json.decodeFromString<WhatsNewCacheEnvelope>(raw)
                .takeIf { it.channel == channel.name && it.body.isNotBlank() }
        }.getOrNull()
    }

    private fun compactReleaseBody(body: String): String? = runCatching {
        val releases = json.decodeFromString<List<GitHubReleaseDto>>(body)
            .filterNot(GitHubReleaseDto::draft)
            .map { it.copy(assets = emptyList()) }
        json.encodeToString(releases)
    }.getOrNull()

    private fun encodeCache(cache: WhatsNewCacheEnvelope): String =
        json.encodeToString(cache)

    private fun persistCache(cache: WhatsNewCacheEnvelope) {
        runCatching {
            AppUpdaterPlatform.setWhatsNewCache(encodeCache(cache))
        }
    }

    private fun decodeContent(
        body: String,
        channel: UpdateChannel,
        currentVersion: String,
        matchBaseVersion: Boolean,
        fromCache: Boolean,
        isStale: Boolean,
        fetchedAtMillis: Long,
    ): WhatsNewContent? {
        val releases = runCatching {
            json.decodeFromString<List<GitHubReleaseDto>>(body)
        }.getOrNull() ?: return null

        val eligible = ReleaseSelector.eligibleReleases(releases, channel)
        val mapped = eligible.mapNotNull { release ->
            val rawVersion = release.tagName?.takeIf { VersionUtils.parse(it) != null }
                ?: release.name?.takeIf { VersionUtils.parse(it) != null }
                ?: return@mapNotNull null
            val version = VersionUtils.normalize(rawVersion)
            WhatsNewRelease(
                version = version,
                title = release.name?.takeIf { it.isNotBlank() } ?: version,
                notes = WhatsNewSnapshotBuilder.cleanReleaseNotes(release.body.orEmpty()),
                publishedAt = release.publishedAt,
                releaseUrl = release.htmlUrl?.takeIf(::isTrustedReleaseUrl),
            )
        }
        return WhatsNewContent(
            snapshot = WhatsNewSnapshotBuilder.build(mapped, currentVersion, matchBaseVersion),
            fromCache = fromCache,
            isStale = isStale,
            fetchedAtMillis = fetchedAtMillis,
        )
    }

    private fun isTrustedApiResponseUrl(url: String): Boolean {
        val normalized = url.trim()
        val expectedPath =
            "$GITHUB_API_BASE/repos/$GITHUB_OWNER/$GITHUB_REPO/releases"
        return (
            normalized == expectedPath ||
                normalized.startsWith("$expectedPath?") ||
                normalized.startsWith("$expectedPath/")
            ) &&
            !normalized.any(Char::isWhitespace) &&
            !normalized.any(Char::isISOControl)
    }

    private fun isTrustedReleaseUrl(url: String): Boolean {
        val normalized = url.trim()
        val expectedPrefix =
            "https://github.com/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/"
        return normalized.startsWith(expectedPrefix, ignoreCase = true) &&
            !normalized.any(Char::isWhitespace) &&
            !normalized.any(Char::isISOControl)
    }
}