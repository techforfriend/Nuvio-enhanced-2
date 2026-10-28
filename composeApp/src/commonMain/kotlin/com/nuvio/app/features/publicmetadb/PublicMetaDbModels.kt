package com.nuvio.app.features.publicmetadb

import com.nuvio.app.core.time.parseZonedIsoDateTimeToEpochMs
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object PublicMetaDbConfig {
    const val API_BASE_URL = "https://publicmetadb.com"
    const val WEBSITE_URL = "https://publicmetadb.com"
    const val API_KEY_URL = "https://publicmetadb.com/settings"
    const val USER_AGENT = "NuvioMobile"
    const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p"
    const val RATING_LABEL = "Overall"
    const val DEFAULT_WATCHLIST_NAME = "Watchlist"
}

internal val publicMetaDbJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    isLenient = true
    explicitNulls = false
}

object PublicMetaDbMediaType {
    const val MOVIE = "movie"
    const val TV = "tv"
}

object PublicMetaDbListType {
    const val WATCHLIST = "watchlist"
    const val CUSTOM = "custom"
}

@Serializable
data class PublicMetaDbMedia(
    val tmdbId: Long,
    val mediaType: String,
    val title: String? = null,
    val year: Int? = null,
    val imdb: String? = null,
    val tvdb: Long? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val resolvedAtEpochMs: Long = 0L,
    val complete: Boolean = false,
)

@Serializable
data class PublicMetaDbList(
    val id: String,
    val name: String,
    val description: String? = null,
    val type: String = PublicMetaDbListType.CUSTOM,
    val isPublic: Boolean = false,
    val itemCount: Int = 0,
    val createdAtEpochMs: Long = 0L,
)

@Serializable
data class PublicMetaDbListItem(
    val id: String? = null,
    val tmdbId: Long,
    val mediaType: String,
    val title: String? = null,
    val year: Int? = null,
    val posterPath: String? = null,
    val addedAtEpochMs: Long = 0L,
)

@Serializable
data class PublicMetaDbWatchedEntry(
    val id: String? = null,
    val tmdbId: Long,
    val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
    val watchedAtEpochMs: Long = 0L,
)

@Serializable
data class PublicMetaDbResumePoint(
    val id: String? = null,
    val tmdbId: Long,
    val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
    val positionMs: Long = 0L,
    val runtimeMs: Long = 0L,
    val progressPercent: Double = 0.0,
    val updatedAtEpochMs: Long = 0L,
    val title: String? = null,
    val posterPath: String? = null,
)

@Serializable
data class PublicMetaDbRatingEntry(
    val id: String? = null,
    val tmdbId: Long,
    val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
    val imdb: String? = null,
    val rating: Int,
    val ratedAtEpochMs: Long = 0L,
)

@Serializable
data class PublicMetaDbSyncSnapshot(
    val accountKey: String = "",
    val lists: List<PublicMetaDbList> = emptyList(),
    val listItems: Map<String, List<PublicMetaDbListItem>> = emptyMap(),
    val watched: List<PublicMetaDbWatchedEntry> = emptyList(),
    val resume: List<PublicMetaDbResumePoint> = emptyList(),
    val ratings: List<PublicMetaDbRatingEntry> = emptyList(),
    val media: Map<String, PublicMetaDbMedia> = emptyMap(),
    val localPlayback: List<WatchProgressEntry> = emptyList(),
    val dismissed: Map<String, Long> = emptyMap(),
    val checkedAtEpochMs: Long? = null,
)

data class PublicMetaDbSyncState(
    val scope: PublicMetaDbAuthScope? = null,
    val snapshot: PublicMetaDbSyncSnapshot? = null,
    val isLoading: Boolean = false,
    val error: PublicMetaDbSyncError? = null,
    val retryAtEpochMs: Long? = null,
    val attemptedAtEpochMs: Long? = null,
) {
    val hasLoaded: Boolean
        get() = snapshot?.checkedAtEpochMs != null
}

enum class PublicMetaDbSyncError { UNAVAILABLE, RATE_LIMIT, AUTHORIZATION_REVOKED, BLOCKED }

data class PublicMetaDbAuthScope(val profileId: Int, val generation: Long)

enum class PublicMetaDbAuthError {
    MISSING_KEY,
    INVALID_KEY,
    REQUEST_FAILED,
    RATE_LIMITED,
    BLOCKED,
    AUTHORIZATION_REVOKED,
}

data class PublicMetaDbAuthState(
    val scope: PublicMetaDbAuthScope = PublicMetaDbAuthScope(1, 0),
    val isAuthenticated: Boolean = false,
    val keyHint: String? = null,
    val connectedAtEpochMs: Long? = null,
    val isBusy: Boolean = false,
    val error: PublicMetaDbAuthError? = null,
)

@Serializable
internal data class PublicMetaDbStoredAuth(
    val apiKey: String? = null,
    val keyHint: String? = null,
    val connectedAtEpochMs: Long? = null,
) {
    override fun toString(): String = "PublicMetaDbStoredAuth()"
}

class PublicMetaDbAuthException(val error: PublicMetaDbAuthError) : Exception(error.name)

open class PublicMetaDbApiException(
    val status: Int,
    val retryAtEpochMs: Long? = null,
    message: String? = null,
) : Exception(message ?: "PublicMetaDB request failed ($status)")

class PublicMetaDbNotFoundException(message: String? = null) : PublicMetaDbApiException(404, message = message)

internal fun Throwable.toPublicMetaDbSyncError(): PublicMetaDbSyncError = when {
    this is PublicMetaDbAuthException -> PublicMetaDbSyncError.AUTHORIZATION_REVOKED
    this is PublicMetaDbApiException && status == 429 -> PublicMetaDbSyncError.RATE_LIMIT
    this is PublicMetaDbApiException && status == 403 -> PublicMetaDbSyncError.BLOCKED
    else -> PublicMetaDbSyncError.UNAVAILABLE
}

internal fun publicMetaDbSyncErrorMessage(error: PublicMetaDbSyncError): String = when (error) {
    PublicMetaDbSyncError.UNAVAILABLE -> "Could not sync with PublicMetaDB. Please try again."
    PublicMetaDbSyncError.RATE_LIMIT -> "PublicMetaDB's request limit has been reached. Syncing will resume shortly."
    PublicMetaDbSyncError.AUTHORIZATION_REVOKED -> "Your PublicMetaDB API key is no longer valid. Connect again with a new key."
    PublicMetaDbSyncError.BLOCKED -> "PublicMetaDB blocked this request. Please try again later."
}

internal fun publicMetaDbKeyHint(apiKey: String): String = apiKey.trim().takeLast(4)

internal fun publicMetaDbMediaKey(mediaType: String, tmdbId: Long): String =
    "${normalizePublicMetaDbMediaType(mediaType)}:$tmdbId"

internal fun normalizePublicMetaDbMediaType(value: String?): String = when (value?.trim()?.lowercase()) {
    "movie", "movies", "film" -> PublicMetaDbMediaType.MOVIE
    else -> PublicMetaDbMediaType.TV
}

internal fun isPublicMetaDbMovieType(value: String?): Boolean =
    normalizePublicMetaDbMediaType(value) == PublicMetaDbMediaType.MOVIE

internal fun publicMetaDbContentType(mediaType: String): String =
    if (isPublicMetaDbMovieType(mediaType)) "movie" else "series"

internal fun publicMetaDbTypeMatches(mediaType: String, contentType: String?): Boolean {
    val normalized = contentType?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return true
    val isMovie = isPublicMetaDbMovieType(mediaType)
    return if (normalized == "movie" || normalized == "film") isMovie else !isMovie
}

internal val PublicMetaDbMedia.key: String
    get() = publicMetaDbMediaKey(mediaType, tmdbId)

internal val PublicMetaDbMedia.isMovie: Boolean
    get() = isPublicMetaDbMovieType(mediaType)

internal fun PublicMetaDbMedia.contentId(): String =
    imdb?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: "tmdb:$tmdbId"

internal fun PublicMetaDbMedia.aliases(): List<String> = listOfNotNull(
    imdb?.takeIf { it.startsWith("tt", ignoreCase = true) },
    "tmdb:$tmdbId",
    tvdb?.let { "tvdb:$it" },
)

internal fun PublicMetaDbMedia.displayTitle(): String =
    title?.trim()?.takeIf(String::isNotEmpty) ?: "PublicMetaDB $tmdbId"

internal fun PublicMetaDbMedia.posterUrl(): String? = posterPath?.let { publicMetaDbImageUrl("w500", it) }

internal fun PublicMetaDbMedia.backdropUrl(): String? = backdropPath?.let { publicMetaDbImageUrl("w1280", it) }

internal fun PublicMetaDbMedia.providerItemId(): String = "publicmetadb:$mediaType:$tmdbId"

internal fun PublicMetaDbMedia.mergeMissing(other: PublicMetaDbMedia): PublicMetaDbMedia = copy(
    title = title?.takeIf(String::isNotBlank) ?: other.title,
    year = year ?: other.year,
    imdb = imdb?.takeIf(String::isNotBlank) ?: other.imdb,
    tvdb = tvdb ?: other.tvdb,
    posterPath = posterPath?.takeIf(String::isNotBlank) ?: other.posterPath,
    backdropPath = backdropPath?.takeIf(String::isNotBlank) ?: other.backdropPath,
    genres = genres.ifEmpty { other.genres },
    runtimeMinutes = runtimeMinutes ?: other.runtimeMinutes,
)

internal fun publicMetaDbImageUrl(size: String, path: String): String? {
    val trimmed = path.trim().takeIf(String::isNotEmpty) ?: return null
    if (trimmed.startsWith("http", ignoreCase = true)) return trimmed
    return "${PublicMetaDbConfig.IMAGE_BASE_URL}/$size${if (trimmed.startsWith('/')) trimmed else "/$trimmed"}"
}

internal fun normalizePublicMetaDbContentId(value: String): String {
    val trimmed = value.trim().lowercase()
    if (trimmed.startsWith("tt")) return trimmed.substringBefore(':')
    val prefix = trimmed.substringBefore(':', "")
    if (prefix in setOf("tmdb", "tvdb", "imdb")) {
        val parts = trimmed.split(':')
        val id = if (parts.size >= 3 && parts[1] in setOf("movie", "tv", "series", "show")) parts[2] else parts.getOrNull(1).orEmpty()
        return if (prefix == "imdb") id else "$prefix:$id"
    }
    return trimmed
}

internal fun JsonElement?.publicMetaDbObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.publicMetaDbArray(): List<JsonElement> = (this as? JsonArray).orEmpty()

internal fun JsonObject.publicMetaDbString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

internal fun JsonObject.publicMetaDbLong(key: String): Long? =
    publicMetaDbString(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

internal fun JsonObject.publicMetaDbInt(key: String): Int? = publicMetaDbLong(key)?.toInt()

internal fun JsonObject.publicMetaDbDouble(key: String): Double? = publicMetaDbString(key)?.toDoubleOrNull()

internal fun JsonObject.publicMetaDbBoolean(key: String): Boolean? = when (publicMetaDbString(key)?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

internal fun JsonObject.publicMetaDbFirstString(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { publicMetaDbString(it) }

internal fun JsonObject.publicMetaDbFirstInt(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { publicMetaDbInt(it) }

internal fun JsonObject.publicMetaDbEpochMs(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
    parsePublicMetaDbTimestamp(publicMetaDbString(key))
}

internal fun parsePublicMetaDbTimestamp(raw: String?): Long? {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    value.toLongOrNull()?.let { number ->
        val ms = if (number in 1..99_999_999_999L) number * 1_000L else number
        return ms.takeIf { it > 86_400_000L }
    }
    var iso = value.replaceFirst(' ', 'T')
    if (iso.length == 10) iso += "T00:00:00Z"
    if (iso.length >= 16 && iso[13] == ':' && (iso.length == 16 || iso[16] != ':')) {
        iso = iso.substring(0, 16) + ":00" + iso.substring(16)
    }
    val hasZone = iso.endsWith("Z", ignoreCase = true) ||
        Regex("""[+-]\d{2}:?\d{2}$""").containsMatchIn(iso.substringAfter('T', ""))
    if (!hasZone) iso += "Z"
    iso = iso.replace("z", "Z")
    return parseZonedIsoDateTimeToEpochMs(iso)?.takeIf { it > 86_400_000L }
}

internal fun JsonObject.publicMetaDbTmdbId(mediaType: String? = null): Long? =
    coercePublicMetaDbTmdbId(this["tmdb_id"] ?: this["tmdbId"] ?: this["tmdb"], mediaType)
        ?: this["ids"].publicMetaDbObject()?.let { coercePublicMetaDbTmdbId(it["tmdb"], mediaType) }
        ?: this["media"].publicMetaDbObject()?.let { coercePublicMetaDbTmdbId(it["tmdb_id"] ?: it["tmdb"], mediaType) }

internal fun coercePublicMetaDbTmdbId(element: JsonElement?, mediaType: String? = null): Long? = when (element) {
    is JsonPrimitive -> element.takeUnless { it is JsonNull }?.content?.trim()?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
    is JsonObject -> {
        val normalized = mediaType?.let(::normalizePublicMetaDbMediaType)
        val keys = buildList {
            if (normalized == PublicMetaDbMediaType.TV) addAll(listOf("tv", "show", "series"))
            if (normalized == PublicMetaDbMediaType.MOVIE) addAll(listOf("movie", "movies"))
            addAll(listOf("tv", "show", "series", "movie", "movies"))
        }
        keys.firstNotNullOfOrNull { key -> element[key]?.let { coercePublicMetaDbTmdbId(it, mediaType) } }
    }
    is JsonArray -> element.firstNotNullOfOrNull { coercePublicMetaDbTmdbId(it, mediaType) }
    else -> null
}?.takeIf { it > 0L }

internal fun JsonObject.publicMetaDbMediaType(): String? =
    publicMetaDbFirstString("media_type", "mediaType", "type")
        ?: this["media"].publicMetaDbObject()?.publicMetaDbFirstString("media_type", "type")
