package com.nuvio.app.features.trakt

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRatingException
import com.nuvio.app.features.tracking.TrackingRatingProvider
import com.nuvio.app.features.tracking.TrackingRatingRecord
import com.nuvio.app.features.tracking.TrackingRatingScope
import com.nuvio.app.features.tracking.TrackingRatingTarget
import com.nuvio.app.features.tracking.arrayOrEmpty
import com.nuvio.app.features.tracking.countersTotal
import com.nuvio.app.features.tracking.intOrNull
import com.nuvio.app.features.tracking.notFoundTotal
import com.nuvio.app.features.tracking.objectOrNull
import com.nuvio.app.features.tracking.toTrackingExternalIds
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TRAKT_RATINGS_BASE_URL = "https://api.trakt.tv"
private const val TRAKT_RATINGS_MAX_PAGES = 50

/**
 * Trakt personal ratings: `GET /sync/ratings/{type}`, `POST /sync/ratings` and
 * `POST /sync/ratings/remove`. Trakt rates movies, shows, seasons and episodes on 1–10.
 */
object TraktRatingsProvider : TrackingRatingProvider {
    override val providerId = TrackingProviderId.TRAKT
    override val supportedScopes = TrackingRatingScope.entries.toSet()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun canRate(target: TrackingRatingTarget): Boolean = target.ids.traktRequestIds() != null

    override suspend fun normalize(target: TrackingRatingTarget): TrackingRatingTarget {
        if (target.scope != TrackingRatingScope.EPISODE) return target
        val catalog = target.catalog ?: return target
        val mapped = try {
            TraktEpisodeMappingService.resolveEpisodeMapping(
                contentId = catalog.contentId,
                contentType = catalog.contentType,
                videoId = catalog.videoId,
                season = target.season,
                episode = target.episode,
                episodeTitle = target.episodeTitle,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        } ?: return target
        return target.copy(season = mapped.season, episode = mapped.episode)
    }

    override suspend fun fetchRatings(profileId: Int, scope: TrackingRatingScope): List<TrackingRatingRecord> {
        val headers = headers(profileId)
        val type = when (scope) {
            TrackingRatingScope.MOVIE -> "movies"
            TrackingRatingScope.SHOW -> "shows"
            TrackingRatingScope.SEASON -> "seasons"
            TrackingRatingScope.EPISODE -> "episodes"
        }
        val rows = mutableListOf<JsonElement>()
        val first = request("GET", "$TRAKT_RATINGS_BASE_URL/sync/ratings/$type", headers)
        rows += json.parseToJsonElement(first.body.ifBlank { "[]" }).arrayOrEmpty()
        val pageCount = first.header("X-Pagination-Page-Count")?.toIntOrNull() ?: 1
        val limit = first.header("X-Pagination-Limit")?.toIntOrNull()
        for (page in 2..pageCount.coerceAtMost(TRAKT_RATINGS_MAX_PAGES)) {
            val limitQuery = limit?.let { "&limit=$it" }.orEmpty()
            val response = request("GET", "$TRAKT_RATINGS_BASE_URL/sync/ratings/$type?page=$page$limitQuery", headers)
            rows += json.parseToJsonElement(response.body.ifBlank { "[]" }).arrayOrEmpty()
        }
        return rows.mapNotNull { row -> row.objectOrNull()?.toRecord(scope) }
    }

    override suspend fun setRating(profileId: Int, target: TrackingRatingTarget, rating: Int) {
        val response = request(
            method = "POST",
            url = "$TRAKT_RATINGS_BASE_URL/sync/ratings",
            headers = headers(profileId),
            body = buildBody(target, rating).toString(),
        )
        val payload = json.parseToJsonElement(response.body.ifBlank { "{}" }).objectOrNull()
        val added = payload?.get("added").countersTotal()
        if (added <= 0 || payload?.get("not_found").notFoundTotal() > 0) {
            throw TrackingRatingException("Trakt did not find this item")
        }
    }

    override suspend fun removeRating(profileId: Int, target: TrackingRatingTarget) {
        val response = request(
            method = "POST",
            url = "$TRAKT_RATINGS_BASE_URL/sync/ratings/remove",
            headers = headers(profileId),
            body = buildBody(target, null).toString(),
        )
        val payload = json.parseToJsonElement(response.body.ifBlank { "{}" }).objectOrNull()
        if (payload?.get("not_found").notFoundTotal() > 0) {
            throw TrackingRatingException("Trakt did not find this item")
        }
    }

    private fun buildBody(target: TrackingRatingTarget, rating: Int?): JsonObject {
        val ids = target.ids.traktRequestIds() ?: throw TrackingRatingException("Missing Trakt ids")
        val item = buildJsonObject {
            target.title?.let { put("title", it) }
            target.year?.let { put("year", it) }
            put("ids", ids)
            when (target.scope) {
                TrackingRatingScope.MOVIE, TrackingRatingScope.SHOW -> rating?.let { put("rating", it) }
                TrackingRatingScope.SEASON -> put(
                    "seasons",
                    JsonArray(listOf(buildJsonObject {
                        put("number", requireNotNull(target.season))
                        rating?.let { put("rating", it) }
                    })),
                )
                TrackingRatingScope.EPISODE -> put(
                    "seasons",
                    JsonArray(listOf(buildJsonObject {
                        put("number", requireNotNull(target.season))
                        put("episodes", JsonArray(listOf(buildJsonObject {
                            put("number", requireNotNull(target.episode))
                            rating?.let { put("rating", it) }
                        })))
                    })),
                )
            }
        }
        val group = if (target.scope == TrackingRatingScope.MOVIE) "movies" else "shows"
        return buildJsonObject { put(group, JsonArray(listOf(item))) }
    }

    private fun JsonObject.toRecord(scope: TrackingRatingScope): TrackingRatingRecord? {
        val rating = intOrNull("rating") ?: return null
        return when (scope) {
            TrackingRatingScope.MOVIE -> TrackingRatingRecord(
                scope, get("movie").objectOrNull()?.get("ids").toTrackingExternalIds(), rating,
            )
            TrackingRatingScope.SHOW -> TrackingRatingRecord(
                scope, get("show").objectOrNull()?.get("ids").toTrackingExternalIds(), rating,
            )
            TrackingRatingScope.SEASON -> {
                val season = get("season").objectOrNull()?.intOrNull("number") ?: return null
                TrackingRatingRecord(
                    scope, get("show").objectOrNull()?.get("ids").toTrackingExternalIds(), rating, season = season,
                )
            }
            TrackingRatingScope.EPISODE -> {
                val episode = get("episode").objectOrNull() ?: return null
                TrackingRatingRecord(
                    scope,
                    get("show").objectOrNull()?.get("ids").toTrackingExternalIds(),
                    rating,
                    season = episode.intOrNull("season") ?: return null,
                    episode = episode.intOrNull("number") ?: return null,
                )
            }
        }
    }

    private fun TrackingExternalIds.traktRequestIds(): JsonObject? {
        val value = buildJsonObject {
            trakt?.let { put("trakt", it) }
            imdb?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }?.let { put("imdb", it) }
            tmdb?.let { put("tmdb", it) }
            tvdb?.trim()?.toLongOrNull()?.let { put("tvdb", it) }
        }
        return value.takeIf { it.isNotEmpty() }
    }

    private suspend fun headers(profileId: Int): Map<String, String> {
        if (profileId != ProfileRepository.activeProfileId) throw CancellationException("Trakt profile changed")
        return TraktAuthRepository.authorizedHeaders()
            ?: throw TrackingRatingException("Trakt is not connected")
    }

    private suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String = "",
    ): RawHttpResponse {
        val response = httpRequestRaw(
            method = method,
            url = url,
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
            ) + headers,
            body = body,
        )
        if (response.status !in 200..299) {
            throw TrackingRatingException("Trakt request failed (HTTP ${response.status})")
        }
        return response
    }

    private fun RawHttpResponse.header(name: String): String? =
        headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value
            ?.substringBefore(',')
            ?.trim()
}
