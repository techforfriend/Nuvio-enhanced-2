package com.nuvio.app.features.simkl

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingMediaKind
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

private const val SIMKL_ALL_RATINGS = "1,2,3,4,5,6,7,8,9,10"

/**
 * Simkl personal ratings. Simkl only rates whole titles (movies, shows and anime), so season and
 * episode ratings are not offered for Simkl.
 */
object SimklRatingsProvider : TrackingRatingProvider {
    override val providerId = TrackingProviderId.SIMKL
    override val supportedScopes = setOf(TrackingRatingScope.MOVIE, TrackingRatingScope.SHOW)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun canRate(target: TrackingRatingTarget): Boolean =
        target.scope in supportedScopes &&
            (target.ids.toSimklJsonObjectOrNull() != null || !target.title.isNullOrBlank())

    override suspend fun fetchRatings(profileId: Int, scope: TrackingRatingScope): List<TrackingRatingRecord> {
        if (scope !in supportedScopes) return emptyList()
        checkProfile(profileId)
        val types = if (scope == TrackingRatingScope.MOVIE) listOf("movies") else listOf("shows", "anime")
        return types.flatMap { type ->
            val response = SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.GET,
                    path = "/sync/ratings/$type/$SIMKL_ALL_RATINGS",
                ),
            )
            checkProfile(profileId)
            parseRows(json.parseToJsonElement(response.body.ifBlank { "{}" }))
                .mapNotNull { row -> row.toRecord(scope) }
        }
    }

    override suspend fun setRating(profileId: Int, target: TrackingRatingTarget, rating: Int) {
        checkProfile(profileId)
        val response = SimklApi.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/ratings",
                body = buildBody(target, rating).toString(),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE,
            ),
        )
        val payload = json.parseToJsonElement(response.body.ifBlank { "{}" }).objectOrNull()
        val added = payload?.get("added").countersTotal()
        if (payload?.get("not_found").notFoundTotal() > 0 || (payload?.containsKey("added") == true && added <= 0)) {
            throw TrackingRatingException("Simkl did not find this item")
        }
    }

    override suspend fun removeRating(profileId: Int, target: TrackingRatingTarget) {
        checkProfile(profileId)
        val response = SimklApi.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/ratings/remove",
                body = buildBody(target, null).toString(),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE,
            ),
        )
        val payload = json.parseToJsonElement(response.body.ifBlank { "{}" }).objectOrNull()
        if (payload?.get("not_found").notFoundTotal() > 0) {
            throw TrackingRatingException("Simkl did not find this item")
        }
    }

    private fun buildBody(target: TrackingRatingTarget, rating: Int?): JsonObject {
        if (target.scope !in supportedScopes) throw TrackingRatingException("Simkl cannot rate ${target.scope}")
        val item = buildJsonObject {
            rating?.let { put("rating", it) }
            target.ids.toSimklJsonObjectOrNull()?.let { put("ids", it) }
            target.title?.takeIf(String::isNotBlank)?.let { put("title", it) }
            target.year?.let { put("year", it) }
        }
        val group = when {
            target.scope == TrackingRatingScope.MOVIE -> "movies"
            target.kind == TrackingMediaKind.ANIME -> "anime"
            else -> "shows"
        }
        return buildJsonObject { put(group, JsonArray(listOf(item))) }
    }

    private fun parseRows(root: JsonElement): List<JsonObject> = when (root) {
        is JsonArray -> root.mapNotNull { it.objectOrNull() }
        is JsonObject -> listOf("movies", "shows", "anime").flatMap { key ->
            root[key].arrayOrEmpty().mapNotNull { it.objectOrNull() }
        }
        else -> emptyList()
    }

    private fun JsonObject.toRecord(scope: TrackingRatingScope): TrackingRatingRecord? {
        val rating = intOrNull("user_rating") ?: intOrNull("rating") ?: return null
        val media = listOf("movie", "show", "anime").firstNotNullOfOrNull { key -> get(key).objectOrNull() }
            ?: return null
        val ids = media["ids"].toTrackingExternalIds()
        if (!ids.hasAny) return null
        return TrackingRatingRecord(scope = scope, ids = ids, rating = rating)
    }

    private fun checkProfile(profileId: Int) {
        if (profileId != ProfileRepository.activeProfileId) throw CancellationException("Simkl profile changed")
    }
}
