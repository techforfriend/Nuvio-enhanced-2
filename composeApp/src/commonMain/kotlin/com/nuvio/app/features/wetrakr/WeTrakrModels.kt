package com.nuvio.app.features.wetrakr

import com.nuvio.app.core.time.parseZonedIsoDateTimeToEpochMs
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val weTrakrJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    isLenient = true
    explicitNulls = false
}

object WeTrakrStatus {
    const val PLANNING = "planning"
    const val WATCHING = "watching"
    const val WAITING = "waiting"
    const val WATCHED = "watched"
    const val PAUSED = "paused"
    const val DROPPED = "dropped"
    const val PLAYING = "playing"

    val movieStatuses = setOf(PLANNING, WATCHED, DROPPED)
    val showStatuses = setOf(PLANNING, WATCHING, WAITING, WATCHED, PAUSED, DROPPED)
    val writableShowStatuses = setOf(PLANNING, WATCHING, WATCHED, PAUSED, DROPPED)
}

object WeTrakrMediaType {
    const val MOVIE = "movie"
    const val SHOW = "show"
    const val SEASON = "season"
    const val EPISODE = "episode"
}

@Serializable
data class WeTrakrIds(
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: Long? = null,
) {
    val hasAny: Boolean
        get() = !imdb.isNullOrBlank() || tmdb != null || tvdb != null
}

@Serializable
data class WeTrakrMedia(
    val id: Long,
    val type: String,
    val title: String? = null,
    val year: Int? = null,
    val ids: WeTrakrIds = WeTrakrIds(),
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
)

@Serializable
data class WeTrakrListEntry(
    val media: WeTrakrMedia,
    val status: String,
    val addedAtEpochMs: Long = 0L,
    val watchedAtEpochMs: Long = 0L,
    val watchedCount: Int = 0,
)

@Serializable
data class WeTrakrWatchedEpisode(
    val showId: Long? = null,
    val showIds: WeTrakrIds = WeTrakrIds(),
    val showTitle: String? = null,
    val season: Int,
    val number: Int,
    val watchedAtEpochMs: Long = 0L,
)

@Serializable
data class WeTrakrPlayback(
    val type: String,
    val media: WeTrakrMedia,
    val season: Int? = null,
    val number: Int? = null,
    val episodeTitle: String? = null,
    val status: String = WeTrakrStatus.PLAYING,
    val progressPercent: Double = 0.0,
    val runtimeSeconds: Long? = null,
    val trackedAtEpochMs: Long = 0L,
)

@Serializable
data class WeTrakrRatingEntry(
    val type: String,
    val ids: WeTrakrIds = WeTrakrIds(),
    val id: Long? = null,
    val season: Int? = null,
    val number: Int? = null,
    val rating: Double,
)

@Serializable
data class WeTrakrUser(
    val id: Long = 0L,
    val username: String? = null,
    val displayName: String? = null,
    val plan: String? = null,
)

@Serializable
data class WeTrakrSyncSnapshot(
    val userId: Long,
    val entries: List<WeTrakrListEntry> = emptyList(),
    val watchedEpisodes: List<WeTrakrWatchedEpisode> = emptyList(),
    val playback: List<WeTrakrPlayback> = emptyList(),
    val ratings: List<WeTrakrRatingEntry> = emptyList(),
    val localPlayback: List<WatchProgressEntry> = emptyList(),
    val dismissed: Map<String, Long> = emptyMap(),
    val activitiesFingerprint: String? = null,
    val checkedAtEpochMs: Long? = null,
)

data class WeTrakrSyncState(
    val scope: WeTrakrAuthScope? = null,
    val snapshot: WeTrakrSyncSnapshot? = null,
    val isLoading: Boolean = false,
    val error: WeTrakrSyncError? = null,
    val retryAtEpochMs: Long? = null,
    val attemptedAtEpochMs: Long? = null,
) {
    val hasLoaded: Boolean
        get() = snapshot?.checkedAtEpochMs != null
}

enum class WeTrakrSyncError { UNAVAILABLE, RATE_LIMIT, AUTHORIZATION_REVOKED, PLAN_LIMIT }

data class WeTrakrAuthScope(val profileId: Int, val generation: Long)

enum class WeTrakrAuthError {
    MISSING_CLIENT_ID,
    REQUEST_FAILED,
    RATE_LIMITED,
    APP_SUSPENDED,
    ACCESS_DENIED,
    CODE_EXPIRED,
    AUTHORIZATION_REVOKED,
}

data class WeTrakrAuthState(
    val scope: WeTrakrAuthScope = WeTrakrAuthScope(1, 0),
    val isAuthenticated: Boolean = false,
    val user: WeTrakrUser? = null,
    val awaitingApproval: Boolean = false,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val isBusy: Boolean = false,
    val error: WeTrakrAuthError? = null,
)

@Serializable
internal data class WeTrakrDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Long = 5L,
    val expiresAtEpochMs: Long,
) {
    override fun toString(): String = "WeTrakrDeviceAuthorization()"
}

@Serializable
internal data class WeTrakrStoredAuth(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long? = null,
    val user: WeTrakrUser? = null,
    val pending: WeTrakrDeviceAuthorization? = null,
) {
    override fun toString(): String = "WeTrakrStoredAuth()"
}

internal data class WeTrakrTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long?,
    val username: String?,
)

class WeTrakrAuthException(val error: WeTrakrAuthError) : Exception(error.name)

open class WeTrakrApiException(
    val status: Int,
    val errorCode: String? = null,
    val retryAtEpochMs: Long? = null,
    message: String? = null,
) : Exception(message ?: "WeTrakr request failed ($status)")

class WeTrakrNotFoundException(message: String? = null) : WeTrakrApiException(404, message = message)

internal fun Throwable.toWeTrakrSyncError(): WeTrakrSyncError = when {
    this is WeTrakrAuthException -> WeTrakrSyncError.AUTHORIZATION_REVOKED
    this is WeTrakrApiException && status == 429 -> WeTrakrSyncError.RATE_LIMIT
    this is WeTrakrApiException && (status == 420 || status == 426) -> WeTrakrSyncError.PLAN_LIMIT
    else -> WeTrakrSyncError.UNAVAILABLE
}

internal val WeTrakrMedia.isMovie: Boolean
    get() = type.equals(WeTrakrMediaType.MOVIE, ignoreCase = true)

internal val WeTrakrMedia.contentType: String
    get() = if (isMovie) "movie" else "series"

internal fun WeTrakrMedia.displayTitle(): String =
    title?.trim()?.takeIf(String::isNotEmpty) ?: "WeTrakr $id"

internal fun WeTrakrMedia.posterUrl(): String? =
    posterPath?.takeIf(String::isNotBlank)?.let { weTrakrImageUrl("w500", it) }

internal fun WeTrakrMedia.backdropUrl(): String? =
    backdropPath?.takeIf(String::isNotBlank)?.let { weTrakrImageUrl("w1280", it) }

internal fun WeTrakrMedia.providerItemId(): String = "wetrakr:${type.lowercase()}:$id"

internal fun weTrakrImageUrl(size: String, path: String): String =
    if (path.startsWith("http", ignoreCase = true)) path else "${WeTrakrConfig.IMAGE_BASE_URL}/$size${if (path.startsWith('/')) path else "/$path"}"

internal fun WeTrakrIds.contentId(): String? =
    imdb?.takeIf(String::isNotBlank)
        ?: tmdb?.let { "tmdb:$it" }
        ?: tvdb?.let { "tvdb:$it" }

internal fun WeTrakrIds.aliases(): List<String> = listOfNotNull(
    imdb?.takeIf(String::isNotBlank),
    tmdb?.let { "tmdb:$it" },
    tvdb?.let { "tvdb:$it" },
)

internal fun WeTrakrIds.toTrackingExternalIds(): TrackingExternalIds = TrackingExternalIds(
    imdb = imdb,
    tmdb = tmdb,
    tvdb = tvdb?.toString(),
)

internal fun TrackingExternalIds.toWeTrakrIds(): WeTrakrIds = WeTrakrIds(
    imdb = imdb?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) },
    tmdb = tmdb,
    tvdb = tvdb?.trim()?.toLongOrNull(),
)

internal fun normalizeWeTrakrContentId(value: String): String {
    val trimmed = value.trim().lowercase()
    if (trimmed.startsWith("tt")) return trimmed.substringBefore(':')
    val prefix = trimmed.substringBefore(':', "")
    if (prefix in setOf("tmdb", "tvdb", "imdb")) {
        val id = trimmed.substringAfter(':').substringBefore(':')
        return if (prefix == "imdb") id else "$prefix:$id"
    }
    return trimmed
}

internal fun JsonElement?.weTrakrObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.weTrakrArray(): List<JsonElement> = (this as? JsonArray).orEmpty()

internal fun JsonObject.weTrakrString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

internal fun JsonObject.weTrakrLong(key: String): Long? = weTrakrString(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

internal fun JsonObject.weTrakrInt(key: String): Int? = weTrakrLong(key)?.toInt()

internal fun JsonObject.weTrakrDouble(key: String): Double? = weTrakrString(key)?.toDoubleOrNull()

internal fun JsonObject.weTrakrEpochMs(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
    val raw = weTrakrString(key) ?: return@firstNotNullOfOrNull null
    raw.toLongOrNull()?.let { if (it in 1..99_999_999_999L) it * 1_000L else it }
        ?: parseZonedIsoDateTimeToEpochMs(raw)
        ?: parseWeTrakrDateOnly(raw)
}?.takeIf { it > 86_400_000L }

private fun parseWeTrakrDateOnly(value: String): Long? {
    if (value.length < 10) return null
    return parseZonedIsoDateTimeToEpochMs("${value.take(10)}T00:00:00Z")
}

internal fun JsonElement?.toWeTrakrIds(): WeTrakrIds {
    val obj = this as? JsonObject ?: return WeTrakrIds()
    return WeTrakrIds(
        imdb = obj.weTrakrString("imdb")?.takeIf { it.startsWith("tt", ignoreCase = true) },
        tmdb = obj.weTrakrLong("tmdb")?.takeIf { it > 0L },
        tvdb = obj.weTrakrLong("tvdb")?.takeIf { it > 0L },
    )
}

internal fun parseWeTrakrMedia(element: JsonElement?, fallbackType: String? = null): WeTrakrMedia? {
    val obj = element as? JsonObject ?: return null
    val id = obj.weTrakrLong("id") ?: return null
    val type = obj.weTrakrString("type")?.lowercase() ?: fallbackType ?: return null
    val year = obj.weTrakrInt("year")
        ?: listOf("release_date", "first_air_date", "air_date", "released")
            .firstNotNullOfOrNull { key -> obj.weTrakrString(key)?.take(4)?.toIntOrNull() }
    val genres = obj["genres"].weTrakrArray().mapNotNull { genre ->
        when (genre) {
            is JsonPrimitive -> genre.content.trim().takeIf(String::isNotEmpty)
            is JsonObject -> genre.weTrakrString("name")
            else -> null
        }
    }
    return WeTrakrMedia(
        id = id,
        type = type,
        title = obj.weTrakrString("title") ?: obj.weTrakrString("name") ?: obj.weTrakrString("original_title"),
        year = year?.takeIf { it in 1870..2200 },
        ids = obj["ids"].toWeTrakrIds(),
        posterPath = obj.weTrakrString("poster_path"),
        backdropPath = obj.weTrakrString("backdrop_path"),
        genres = genres,
        runtimeMinutes = obj.weTrakrInt("runtime")?.takeIf { it > 0 },
    )
}

internal fun weTrakrSyncErrorMessage(error: WeTrakrSyncError): String = when (error) {
    WeTrakrSyncError.UNAVAILABLE -> "Could not sync with WeTrakr. Please try again."
    WeTrakrSyncError.RATE_LIMIT -> "WeTrakr's request limit has been reached. Syncing will resume shortly."
    WeTrakrSyncError.AUTHORIZATION_REVOKED -> "Your WeTrakr connection has expired or been revoked. Connect again to resume syncing."
    WeTrakrSyncError.PLAN_LIMIT -> "This WeTrakr action needs a higher plan or is over your plan's limits."
}
