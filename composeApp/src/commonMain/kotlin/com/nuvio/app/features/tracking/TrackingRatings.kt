package com.nuvio.app.features.tracking

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a personal rating is attached to. */
enum class TrackingRatingScope {
    MOVIE,
    SHOW,
    SEASON,
    EPISODE,
}

const val TRACKING_RATING_MIN = 1
const val TRACKING_RATING_MAX = 10

/**
 * A rateable item. For [TrackingRatingScope.SEASON] only [season] is set, for
 * [TrackingRatingScope.EPISODE] both [season] and [episode] are set; [ids] always describe the
 * movie or the parent show.
 */
data class TrackingRatingTarget(
    val scope: TrackingRatingScope,
    val kind: TrackingMediaKind,
    val ids: TrackingExternalIds,
    val title: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val catalog: TrackingCatalogReference? = null,
) {
    val key: String
        get() = buildString {
            append(scope.name.lowercase())
            append('|')
            append(
                TrackingMediaReference(kind = kind, title = title, year = year, ids = ids).stableKey,
            )
            season?.let { append("|s").append(it) }
            episode?.let { append("|e").append(it) }
        }

    val isValid: Boolean
        get() = when (scope) {
            TrackingRatingScope.MOVIE, TrackingRatingScope.SHOW -> season == null && episode == null
            TrackingRatingScope.SEASON -> season != null && season >= 0 && episode == null
            TrackingRatingScope.EPISODE -> season != null && season >= 0 && episode != null && episode > 0
        }
}

/** One rating as reported by a provider's "list my ratings" endpoint. */
data class TrackingRatingRecord(
    val scope: TrackingRatingScope,
    val ids: TrackingExternalIds,
    val rating: Int,
    val season: Int? = null,
    val episode: Int? = null,
) {
    fun matches(target: TrackingRatingTarget): Boolean =
        scope == target.scope &&
            season == target.season &&
            episode == target.episode &&
            ids.sharesIdentityWith(target.ids)
}

interface TrackingRatingProvider {
    val providerId: TrackingProviderId
    val supportedScopes: Set<TrackingRatingScope>

    /** Whether this provider can address [target] with the ids it carries. */
    fun canRate(target: TrackingRatingTarget): Boolean

    /**
     * Converts a Nuvio (addon) target into the coordinates the provider uses, e.g. Trakt episode
     * numbering. Called before reading and writing so cached records line up with writes.
     */
    suspend fun normalize(target: TrackingRatingTarget): TrackingRatingTarget = target

    /** Every rating the connected account has for [scope]. */
    suspend fun fetchRatings(profileId: Int, scope: TrackingRatingScope): List<TrackingRatingRecord>

    /** Throws [TrackingRatingException] (or any exception) when the provider did not store it. */
    suspend fun setRating(profileId: Int, target: TrackingRatingTarget, rating: Int)

    suspend fun removeRating(profileId: Int, target: TrackingRatingTarget)
}

class TrackingRatingException(message: String) : Exception(message)

fun TrackingExternalIds.sharesIdentityWith(other: TrackingExternalIds): Boolean {
    fun String?.norm() = this?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
    return (imdb.norm() != null && imdb.norm() == other.imdb.norm()) ||
        (tmdb != null && tmdb == other.tmdb) ||
        (tvdb.norm() != null && tvdb.norm() == other.tvdb.norm()) ||
        (trakt != null && trakt == other.trakt) ||
        (simkl != null && simkl == other.simkl) ||
        (mal != null && mal == other.mal) ||
        (anidb != null && anidb == other.anidb) ||
        (anilist != null && anilist == other.anilist) ||
        (kitsu != null && kitsu == other.kitsu) ||
        (mdblist.norm() != null && mdblist.norm() == other.mdblist.norm())
}

/** Reads a provider `ids` object whose values may be JSON strings or numbers. */
fun JsonElement?.toTrackingExternalIds(): TrackingExternalIds {
    val obj = this as? JsonObject ?: return TrackingExternalIds()
    fun raw(key: String): String? = (obj[key] as? JsonPrimitive)
        ?.takeUnless { it is JsonNull }
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "null" }
    fun long(key: String): Long? = raw(key)?.toLongOrNull()?.takeIf { it > 0L }
    return TrackingExternalIds(
        imdb = raw("imdb")?.takeIf { it.startsWith("tt", ignoreCase = true) },
        tmdb = long("tmdb"),
        tvdb = raw("tvdb")?.takeIf { it.toLongOrNull()?.let { value -> value > 0L } ?: true },
        trakt = long("trakt"),
        simkl = long("simkl") ?: long("simkl_id"),
        mal = long("mal"),
        anidb = long("anidb"),
        anilist = long("anilist"),
        kitsu = long("kitsu"),
        mdblist = raw("mdblist"),
    )
}

internal fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arrayOrEmpty(): List<JsonElement> = (this as? JsonArray).orEmpty()

internal fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toDoubleOrNull()?.toInt()

/** Sums the numeric counters of a `{"movies": 1, "shows": 0, ...}` receipt object. */
internal fun JsonElement?.countersTotal(): Int =
    (this as? JsonObject)?.values?.sumOf { value ->
        (value as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    } ?: 0

/** Counts entries in a `{"movies": [...], "shows": [...]}` not-found object. */
internal fun JsonElement?.notFoundTotal(): Int =
    (this as? JsonObject)?.values?.sumOf { value ->
        when (value) {
            is JsonArray -> value.size
            is JsonPrimitive -> value.content.toIntOrNull() ?: 0
            else -> 0
        }
    } ?: 0
