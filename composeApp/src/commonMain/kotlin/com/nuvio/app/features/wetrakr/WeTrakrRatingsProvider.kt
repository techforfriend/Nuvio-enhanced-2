package com.nuvio.app.features.wetrakr

import com.nuvio.app.features.player.skip.SimklIdResolver
import com.nuvio.app.features.tracking.TRACKING_RATING_MAX
import com.nuvio.app.features.tracking.TRACKING_RATING_MIN
import com.nuvio.app.features.tracking.TrackingAnimeIdMapper
import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRatingException
import com.nuvio.app.features.tracking.TrackingRatingProvider
import com.nuvio.app.features.tracking.TrackingRatingRecord
import com.nuvio.app.features.tracking.TrackingRatingScope
import com.nuvio.app.features.tracking.TrackingRatingTarget
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

internal class WeTrakrRatingsProvider(
    private val sync: WeTrakrSyncRepository,
    private val api: WeTrakrApiClient,
    private val writes: WeTrakrTrackingWrites,
) : TrackingRatingProvider {
    override val providerId = TrackingProviderId.WETRAKR
    override val supportedScopes = setOf(
        TrackingRatingScope.MOVIE,
        TrackingRatingScope.SHOW,
        TrackingRatingScope.SEASON,
        TrackingRatingScope.EPISODE,
    )

    override fun canRate(target: TrackingRatingTarget): Boolean {
        val effective = weTrakrTarget(target)
        return effective.scope in supportedScopes &&
            effective.isValid &&
            !usesAnimeNumbering(effective) &&
            resolve(effective) != null
    }

    override suspend fun normalize(target: TrackingRatingTarget): TrackingRatingTarget {
        val effective = weTrakrTarget(target)
        if (effective.scope == TrackingRatingScope.EPISODE) return animeEpisodeTarget(effective)
        if (effective.scope != TrackingRatingScope.SHOW || effective.kind != TrackingMediaKind.ANIME) return effective
        val contentId = effective.catalog?.contentId ?: return effective
        val catalogIds = parseTrackingExternalIds(contentId)
        if (catalogIds.toWeTrakrIds().hasAny) return effective
        val (source, id) = when {
            catalogIds.kitsu != null -> "kitsu" to catalogIds.kitsu
            catalogIds.mal != null -> "mal" to catalogIds.mal
            catalogIds.anilist != null -> "anilist" to catalogIds.anilist
            catalogIds.anidb != null -> "anidb" to catalogIds.anidb
            else -> return effective
        }
        val resolved = try {
            SimklIdResolver.resolveIds(source, id.toString(), "anime")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return effective
        val season = resolved.tvdbSeason?.takeIf { it > 1 } ?: return effective
        val imdb = resolved.imdb?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        return effective.copy(
            scope = TrackingRatingScope.SEASON,
            season = season,
            ids = effective.ids.mergeMissing(TrackingExternalIds(imdb = imdb)),
        )
    }

    override suspend fun fetchRatings(profileId: Int, scope: TrackingRatingScope): List<TrackingRatingRecord> {
        scope(profileId)
        if (scope !in supportedScopes) return emptyList()
        sync.refresh(TrackingRefreshIntent.AUTOMATIC)
        val type = scope.weTrakrType()
        return sync.currentProjection().snapshot.ratings
            .filter { it.type.equals(type, ignoreCase = true) }
            .map { rating ->
                TrackingRatingRecord(
                    scope = scope,
                    ids = rating.ids.toTrackingExternalIds(),
                    rating = rating.rating.roundToInt().coerceIn(TRACKING_RATING_MIN, TRACKING_RATING_MAX),
                    season = rating.season,
                    episode = rating.number,
                )
            }
    }

    override suspend fun setRating(profileId: Int, target: TrackingRatingTarget, rating: Int) {
        val authScope = scope(profileId)
        val resolved = resolve(target) ?: throw TrackingRatingException("This title could not be found on WeTrakr")
        val value = rating.coerceIn(TRACKING_RATING_MIN, TRACKING_RATING_MAX)
        val response = api.post(authScope, "/sync/ratings", document(target, resolved, value))
        if (response.notFoundCount() > 0) throw TrackingRatingException("This title could not be found on WeTrakr")
        sync.applyRating(authScope, entry(target, resolved, value.toDouble()), value.toDouble())
    }

    override suspend fun removeRating(profileId: Int, target: TrackingRatingTarget) {
        val authScope = scope(profileId)
        val resolved = resolve(target) ?: return
        api.post(authScope, "/sync/ratings/remove", document(target, resolved, null))
        sync.applyRating(authScope, entry(target, resolved, 0.0), null)
    }

    private suspend fun animeEpisodeTarget(target: TrackingRatingTarget): TrackingRatingTarget {
        val catalog = target.catalog ?: return target
        val number = target.episode ?: return target
        val reference = TrackingMediaReference(
            kind = target.kind,
            title = target.title,
            year = target.year,
            ids = target.ids,
            episode = TrackingEpisode(season = target.season, number = number, title = target.episodeTitle),
            catalog = catalog,
        )
        val mapped = TrackingAnimeIdMapper.toImdbReference(reference)
        if (mapped === reference) return target
        val episode = mapped.episode ?: return target
        return target.copy(
            ids = mapped.ids,
            season = episode.season ?: return target,
            episode = episode.number,
        )
    }

    private fun weTrakrTarget(target: TrackingRatingTarget): TrackingRatingTarget {
        val catalogType = target.catalog?.contentType?.trim()?.lowercase()
        val isAnimeMovie = target.scope == TrackingRatingScope.SHOW &&
            target.kind == TrackingMediaKind.ANIME &&
            (catalogType == "movie" || catalogType == "film")
        return if (isAnimeMovie) target.copy(scope = TrackingRatingScope.MOVIE) else target
    }

    private fun usesAnimeNumbering(target: TrackingRatingTarget): Boolean {
        if (target.scope != TrackingRatingScope.SEASON && target.scope != TrackingRatingScope.EPISODE) return false
        if (target.kind != TrackingMediaKind.ANIME) return false
        val contentId = target.catalog?.contentId ?: return false
        return !parseTrackingExternalIds(contentId).toWeTrakrIds().hasAny
    }

    private fun resolve(target: TrackingRatingTarget): WeTrakrTarget? = writes.resolve(
        TrackingMediaReference(
            kind = if (target.scope == TrackingRatingScope.MOVIE) TrackingMediaKind.MOVIE else TrackingMediaKind.SHOW,
            title = target.title,
            year = target.year,
            ids = target.ids,
            catalog = target.catalog,
        ),
    )

    private fun entry(target: TrackingRatingTarget, resolved: WeTrakrTarget, value: Double) = WeTrakrRatingEntry(
        type = target.scope.weTrakrType(),
        ids = resolved.ids,
        season = target.season,
        number = target.episode.takeIf { target.scope == TrackingRatingScope.EPISODE },
        rating = value,
    )

    private fun document(target: TrackingRatingTarget, resolved: WeTrakrTarget, rating: Int?): JsonObject = buildJsonObject {
        val item = buildJsonObject {
            resolved.putIdentity(this)
            when (target.scope) {
                TrackingRatingScope.MOVIE, TrackingRatingScope.SHOW -> rating?.let { put("rating", it) }
                TrackingRatingScope.SEASON -> put("seasons", buildJsonArray {
                    add(buildJsonObject {
                        put("number", target.season ?: 1)
                        rating?.let { put("rating", it) }
                    })
                })
                TrackingRatingScope.EPISODE -> put("seasons", buildJsonArray {
                    add(buildJsonObject {
                        put("number", target.season ?: 1)
                        put("episodes", buildJsonArray {
                            add(buildJsonObject {
                                put("number", target.episode ?: 1)
                                rating?.let { put("rating", it) }
                            })
                        })
                    })
                })
            }
        }
        put(if (target.scope == TrackingRatingScope.MOVIE) "movies" else "shows", buildJsonArray { add(item) })
    }

    private fun TrackingRatingScope.weTrakrType(): String = when (this) {
        TrackingRatingScope.MOVIE -> WeTrakrMediaType.MOVIE
        TrackingRatingScope.SHOW -> WeTrakrMediaType.SHOW
        TrackingRatingScope.SEASON -> WeTrakrMediaType.SEASON
        TrackingRatingScope.EPISODE -> WeTrakrMediaType.EPISODE
    }

    private fun scope(profileId: Int) = sync.currentScope().also {
        if (it.profileId != profileId) throw CancellationException("WeTrakr profile changed")
    }
}
