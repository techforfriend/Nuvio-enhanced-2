package com.nuvio.app.features.anilist

import com.nuvio.app.features.tracking.TRACKING_RATING_MAX
import com.nuvio.app.features.tracking.TRACKING_RATING_MIN
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRatingException
import com.nuvio.app.features.tracking.TrackingRatingProvider
import com.nuvio.app.features.tracking.TrackingRatingRecord
import com.nuvio.app.features.tracking.TrackingRatingScope
import com.nuvio.app.features.tracking.TrackingRatingTarget
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

internal class AniListRatingsProvider(
    private val sync: AniListSyncRepository,
    private val writes: AniListTrackingWrites,
) : TrackingRatingProvider {
    override val providerId = TrackingProviderId.ANILIST
    override val supportedScopes = setOf(TrackingRatingScope.MOVIE, TrackingRatingScope.SHOW)

    override fun canRate(target: TrackingRatingTarget): Boolean {
        if (target.scope !in supportedScopes) return false
        val ids = target.ids
        if (ids.anilist != null || ids.mal != null || ids.kitsu != null || ids.anidb != null) return true
        val projection = sync.currentProjection()
        return listOfNotNull(target.catalog?.contentId, ids.imdb).any { projection.mediaIdFor(it) != null }
    }

    override suspend fun fetchRatings(profileId: Int, scope: TrackingRatingScope): List<TrackingRatingRecord> {
        scope(profileId)
        if (scope !in supportedScopes) return emptyList()
        val projection = sync.currentProjection()
        return projection.entryByMediaId.values.mapNotNull { entry ->
            val media = entry.media ?: return@mapNotNull null
            if (entry.score <= 0.0) return@mapNotNull null
            val entryScope = if (media.isMovie) TrackingRatingScope.MOVIE else TrackingRatingScope.SHOW
            if (entryScope != scope) return@mapNotNull null
            val mapping = projection.mapping(entry.mediaId)
            TrackingRatingRecord(
                scope = entryScope,
                ids = TrackingExternalIds(
                    imdb = mapping?.imdb,
                    mal = mapping?.mal ?: media.idMal,
                    anidb = mapping?.anidb,
                    anilist = media.id,
                    kitsu = mapping?.kitsu,
                ),
                rating = (entry.score / 10.0).roundToInt().coerceIn(TRACKING_RATING_MIN, TRACKING_RATING_MAX),
            )
        }
    }

    override suspend fun setRating(profileId: Int, target: TrackingRatingTarget, rating: Int) {
        val authScope = scope(profileId)
        val mediaId = resolve(target) ?: throw TrackingRatingException("This title could not be found on AniList")
        writes.setScore(authScope, mediaId, rating.coerceIn(TRACKING_RATING_MIN, TRACKING_RATING_MAX) * 10)
    }

    override suspend fun removeRating(profileId: Int, target: TrackingRatingTarget) {
        val authScope = scope(profileId)
        val mediaId = resolve(target) ?: return
        writes.setScore(authScope, mediaId, 0)
    }

    private suspend fun resolve(target: TrackingRatingTarget): Long? {
        val projection = sync.currentProjection()
        listOfNotNull(target.catalog?.contentId, target.ids.imdb)
            .firstNotNullOfOrNull { projection.mediaIdFor(it) }
            ?.let { return it }
        return try {
            writes.resolve(
                TrackingMediaReference(
                    kind = if (target.scope == TrackingRatingScope.MOVIE) TrackingMediaKind.MOVIE else target.kind,
                    title = target.title,
                    year = target.year,
                    ids = target.ids,
                    catalog = target.catalog,
                ),
            )?.mediaId
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun scope(profileId: Int) = sync.currentScope().also {
        if (it.profileId != profileId) throw CancellationException("AniList profile changed")
    }
}
