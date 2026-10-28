package com.nuvio.app.features.publicmetadb

import com.nuvio.app.features.tracking.TRACKING_RATING_MAX
import com.nuvio.app.features.tracking.TRACKING_RATING_MIN
import com.nuvio.app.features.tracking.TrackingAnimeIdMapper
import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.tracking.TrackingListWriter
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResolution
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRatingException
import com.nuvio.app.features.tracking.TrackingRatingProvider
import com.nuvio.app.features.tracking.TrackingRatingRecord
import com.nuvio.app.features.tracking.TrackingRatingScope
import com.nuvio.app.features.tracking.TrackingRatingTarget
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbler
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PublicMetaDbTrackingWrites internal constructor(
    private val sync: PublicMetaDbSyncRepository,
    private val api: PublicMetaDbApiClient,
    private val resolver: PublicMetaDbIdResolver,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : TrackingScrobbler, TrackingHistoryWriter, TrackingListWriter {
    override val providerId = TrackingProviderId.PUBLICMETADB
    private val writeLock = Mutex()
    private val resumeLock = Mutex()

    override suspend fun scrobble(profileId: Int, action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        val scope = scope(profileId)
        val target = resolve(event.media) ?: return
        if (!target.isMovie && !target.hasEpisode) return
        val percent = event.progressPercent.coerceIn(0.0, 100.0)
        if (action == TrackingScrobbleAction.STOP && percent >= COMPLETION_THRESHOLD_PERCENT) {
            markWatched(scope, target, now())
            deleteResume(scope, target)
            sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
            return
        }
        if (percent < MIN_RESUME_PERCENT) return
        val runtimeMs = runtimeFor(event.media, target)
        val positionMs = (runtimeMs * (percent / 100.0)).roundToLong().coerceIn(1L, (runtimeMs - 1L).coerceAtLeast(1L))
        saveResume(scope, target, positionMs, runtimeMs)
    }

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val scope = scope(profileId)
        var notFound = 0
        writeLock.withLock {
            items.forEach { item ->
                val target = resolve(item.media)
                if (target == null || (!target.isMovie && !target.hasEpisode)) {
                    notFound += 1
                    return@forEach
                }
                markWatched(scope, target, item.watchedAtEpochMs ?: now())
            }
        }
        sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
        return TrackingMutationResult(items.size, notFound)
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult {
        val scope = scope(profileId)
        var notFound = 0
        writeLock.withLock {
            items.forEach { media ->
                val target = resolve(media)
                if (target == null) {
                    notFound += 1
                    return@forEach
                }
                clearWatched(scope, target)
            }
        }
        sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
        return TrackingMutationResult(items.size, notFound)
    }

    override suspend fun moveToList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
        destination: TrackingListStatus,
    ): TrackingMutationResult {
        val scope = scope(profileId)
        var notFound = 0
        val resolutions = mutableListOf<TrackingMutationResolution>()
        writeLock.withLock {
            items.forEach { media ->
                val target = resolve(media)?.let { if (it.isMovie) it else it.copy(season = null, episode = null) }
                if (target == null) {
                    notFound += 1
                    return@forEach
                }
                when (destination) {
                    TrackingListStatus.COMPLETED -> {
                        if (target.isMovie) {
                            markWatched(scope, target, now())
                        } else {
                            val watchlist = ensureWatchlist(scope)
                            addToList(scope, watchlist.id, target)
                        }
                    }
                    TrackingListStatus.DROPPED -> {
                        sync.currentProjection().watchlist?.let { removeFromList(scope, it.id, target) }
                    }
                    TrackingListStatus.PLAN_TO_WATCH,
                    TrackingListStatus.WATCHING,
                    TrackingListStatus.ON_HOLD,
                    -> {
                        val watchlist = ensureWatchlist(scope)
                        addToList(scope, watchlist.id, target)
                    }
                }
                resolutions += TrackingMutationResolution(
                    listStatus = destination,
                    mediaKind = if (target.isMovie) TrackingMediaKind.MOVIE else TrackingMediaKind.SHOW,
                )
            }
        }
        sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFound, resolutions = resolutions)
    }

    override suspend fun removeFromList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult {
        val scope = scope(profileId)
        var notFound = 0
        writeLock.withLock {
            val watchlist = sync.currentProjection().watchlist
            items.forEach { media ->
                val target = resolve(media)
                if (target == null) {
                    notFound += 1
                    return@forEach
                }
                if (watchlist != null) removeFromList(scope, watchlist.id, target)
            }
        }
        sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFound)
    }

    internal suspend fun resolve(media: TrackingMediaReference): PublicMetaDbTarget? = try {
        resolver.resolve(media)
    } catch (error: CancellationException) {
        throw error
    } catch (error: PublicMetaDbAuthException) {
        throw error
    } catch (_: Exception) {
        null
    }

    internal suspend fun markWatched(scope: PublicMetaDbAuthScope, target: PublicMetaDbTarget, watchedAtEpochMs: Long) {
        val body = buildJsonObject {
            put("tmdb_id", target.tmdbId)
            put("media_type", target.mediaType)
            if (!target.isMovie) {
                target.season?.let { put("season", it) }
                target.episode?.let { put("episode", it) }
            }
            put("watched_at", isoTimestamp(watchedAtEpochMs))
            target.imdb?.let {
                put("id_type", "imdb")
                put("id_value", it)
            }
        }
        val response = api.post(scope, "/api/external/watched", body, query = listOf("dedupe" to "true"), allowConflict = true)
        sync.rememberMedia(scope, target.seedMedia())
        sync.applyWatched(
            scope,
            added = listOf(
                PublicMetaDbWatchedEntry(
                    id = response.itemId(),
                    tmdbId = target.tmdbId,
                    mediaType = target.mediaType,
                    season = target.season.takeIf { !target.isMovie },
                    episode = target.episode.takeIf { !target.isMovie },
                    watchedAtEpochMs = watchedAtEpochMs,
                ),
            ),
        )
    }

    internal suspend fun clearWatched(scope: PublicMetaDbAuthScope, target: PublicMetaDbTarget) {
        val query = buildList {
            add("tmdb_id" to target.tmdbId.toString())
            add("media_type" to target.mediaType)
            if (!target.isMovie) {
                target.season?.let { add("season" to it.toString()) }
                target.episode?.let { add("episode" to it.toString()) }
            }
        }
        api.delete(scope, "/api/external/watched", query)
        sync.applyWatched(scope, added = emptyList()) { entry ->
            entry.tmdbId == target.tmdbId &&
                isPublicMetaDbMovieType(entry.mediaType) == target.isMovie &&
                (target.isMovie || target.season == null || entry.season == target.season) &&
                (target.isMovie || target.episode == null || entry.episode == target.episode)
        }
    }

    internal suspend fun saveResume(scope: PublicMetaDbAuthScope, target: PublicMetaDbTarget, positionMs: Long, runtimeMs: Long) {
        resumeLock.withLock {
            val body = resumeBody(target, positionMs, runtimeMs)
            var response = api.post(scope, "/api/external/resume", body, allowConflict = true)
            if (response.status == 409) {
                val existing = findResumeId(scope, target)
                if (existing != null) {
                    api.delete(scope, "/api/external/resume/${PublicMetaDbApiClient.pathSegment(existing)}")
                    response = api.post(scope, "/api/external/resume", body, allowConflict = true)
                }
            }
            if (response.status == 409) return@withLock
            val action = response.json()?.publicMetaDbObject()?.publicMetaDbString("action")
            if (action.equals("completed", ignoreCase = true)) {
                markWatched(scope, target, now())
                deleteResumeLocked(scope, target)
                return@withLock
            }
            val nowMs = now()
            sync.rememberMedia(scope, target.seedMedia())
            sync.applyResume(
                scope,
                PublicMetaDbResumePoint(
                    id = response.itemId(),
                    tmdbId = target.tmdbId,
                    mediaType = target.mediaType,
                    season = target.season.takeIf { !target.isMovie },
                    episode = target.episode.takeIf { !target.isMovie },
                    positionMs = positionMs,
                    runtimeMs = runtimeMs,
                    progressPercent = if (runtimeMs > 0L) positionMs * 100.0 / runtimeMs else 0.0,
                    updatedAtEpochMs = nowMs,
                    title = target.title,
                ),
            ) { point -> point.matches(target) }
        }
    }

    internal suspend fun deleteResume(scope: PublicMetaDbAuthScope, target: PublicMetaDbTarget) {
        resumeLock.withLock { deleteResumeLocked(scope, target) }
    }

    internal suspend fun deleteResumeById(scope: PublicMetaDbAuthScope, point: PublicMetaDbResumePoint) {
        resumeLock.withLock {
            val id = point.id ?: findResumeId(
                scope,
                PublicMetaDbTarget(
                    tmdbId = point.tmdbId,
                    isMovie = isPublicMetaDbMovieType(point.mediaType),
                    season = point.season,
                    episode = point.episode,
                ),
            )
            if (id != null) api.delete(scope, "/api/external/resume/${PublicMetaDbApiClient.pathSegment(id)}")
            sync.applyResume(scope, null) { it == point }
        }
    }

    private suspend fun deleteResumeLocked(scope: PublicMetaDbAuthScope, target: PublicMetaDbTarget) {
        val known = sync.currentProjection().resumeFor(target.ref, target.season, target.episode)
        val id = known?.id ?: findResumeId(scope, target)
        if (id != null) api.delete(scope, "/api/external/resume/${PublicMetaDbApiClient.pathSegment(id)}")
        sync.applyResume(scope, null) { point -> point.matches(target) }
    }

    private suspend fun findResumeId(scope: PublicMetaDbAuthScope, target: PublicMetaDbTarget): String? {
        for (page in 1..RESUME_LOOKUP_MAX_PAGES) {
            val response = api.get(
                scope,
                "/api/external/resume",
                listOf("page" to page.toString(), "perPage" to RESUME_LOOKUP_PAGE_SIZE.toString()),
                allowNotFound = true,
            )
            if (response.status == 404) return null
            val items = response.items()
            items.firstOrNull { row ->
                val rawType = row.publicMetaDbFirstString("media_type", "mediaType")
                val isMovie = rawType?.let(::isPublicMetaDbMovieType) ?: target.isMovie
                isMovie == target.isMovie &&
                    row.publicMetaDbTmdbId(target.mediaType) == target.tmdbId &&
                    (target.isMovie || (
                        row.publicMetaDbFirstInt("season", "season_number") == target.season &&
                            row.publicMetaDbFirstInt("episode", "episode_number") == target.episode
                        ))
            }?.publicMetaDbString("id")?.let { return it }
            val totalPages = response.totalPages() ?: 1
            if (items.isEmpty() || page >= totalPages) return null
        }
        return null
    }

    internal suspend fun ensureWatchlist(scope: PublicMetaDbAuthScope): PublicMetaDbList {
        sync.currentProjection().watchlist?.let { return it }
        val response = api.post(
            scope,
            "/api/external/lists",
            buildJsonObject {
                put("name", PublicMetaDbConfig.DEFAULT_WATCHLIST_NAME)
                put("description", "")
                put("is_public", false)
                put("type", PublicMetaDbListType.WATCHLIST)
            },
        )
        val id = response.itemId() ?: throw PublicMetaDbApiException(response.status, message = "PublicMetaDB did not return the new watchlist")
        val list = PublicMetaDbList(
            id = id,
            name = PublicMetaDbConfig.DEFAULT_WATCHLIST_NAME,
            type = PublicMetaDbListType.WATCHLIST,
            createdAtEpochMs = now(),
        )
        sync.applyLists(scope) { lists -> listOf(list) + lists.filterNot { it.id == id } }
        return list
    }

    internal suspend fun addToList(scope: PublicMetaDbAuthScope, listId: String, target: PublicMetaDbTarget) {
        if (sync.currentProjection().listItem(listId, target.ref) != null) return
        val response = api.post(
            scope,
            "/api/external/lists/${PublicMetaDbApiClient.pathSegment(listId)}/items",
            buildJsonObject {
                put("tmdb_id", target.tmdbId)
                put("media_type", target.mediaType)
            },
            allowConflict = true,
        )
        sync.rememberMedia(scope, target.seedMedia())
        sync.applyListItem(
            scope,
            listId,
            PublicMetaDbListItem(
                id = response.itemId().takeIf { response.status != 409 },
                tmdbId = target.tmdbId,
                mediaType = target.mediaType,
                title = target.title,
                year = target.year,
                addedAtEpochMs = now(),
            ),
        )
    }

    internal suspend fun removeFromList(scope: PublicMetaDbAuthScope, listId: String, target: PublicMetaDbTarget) {
        var item = sync.currentProjection().listItem(listId, target.ref)
        if (item == null || item.id == null) {
            item = api.paged(scope, "/api/external/lists/${PublicMetaDbApiClient.pathSegment(listId)}/items", LIST_LOOKUP_PAGE_SIZE)
                .firstOrNull { row ->
                    val rawType = row.publicMetaDbFirstString("media_type", "mediaType")
                    rawType?.let(::isPublicMetaDbMovieType) == target.isMovie && row.publicMetaDbTmdbId(target.mediaType) == target.tmdbId
                }
                ?.let { row ->
                    PublicMetaDbListItem(
                        id = row.publicMetaDbFirstString("id", "item_id"),
                        tmdbId = target.tmdbId,
                        mediaType = target.mediaType,
                    )
                }
        }
        val itemId = item?.id
        if (itemId != null) {
            api.delete(
                scope,
                "/api/external/lists/${PublicMetaDbApiClient.pathSegment(listId)}/items/${PublicMetaDbApiClient.pathSegment(itemId)}",
            )
        }
        sync.applyListItem(scope, listId, null) { existing ->
            existing.tmdbId == target.tmdbId && isPublicMetaDbMovieType(existing.mediaType) == target.isMovie
        }
    }

    private fun runtimeFor(media: TrackingMediaReference, target: PublicMetaDbTarget): Long {
        val contentId = media.catalog?.contentId?.lowercase()
        val episode = media.episode
        sync.currentLocalPlayback().firstOrNull { entry ->
            entry.durationMs > 0L &&
                contentId != null &&
                entry.parentMetaId.lowercase() == contentId &&
                (episode == null || (entry.seasonNumber == episode.season && entry.episodeNumber == episode.number))
        }?.let { return it.durationMs }
        val projection = sync.currentProjection()
        projection.resumeFor(target.ref, target.season, target.episode)?.runtimeMs?.takeIf { it > 0L }?.let { return it }
        projection.mediaFor(target.ref).runtimeMinutes?.takeIf { it > 0 }?.let { return it * 60_000L }
        return if (target.isMovie) DEFAULT_MOVIE_RUNTIME_MS else DEFAULT_EPISODE_RUNTIME_MS
    }

    private fun resumeBody(target: PublicMetaDbTarget, positionMs: Long, runtimeMs: Long): JsonObject = buildJsonObject {
        put("tmdb_id", target.tmdbId)
        put("media_type", target.mediaType)
        put("season", if (target.isMovie) 0 else target.season ?: 0)
        put("episode", if (target.isMovie) 0 else target.episode ?: 0)
        put("position_ms", positionMs)
        put("runtime_ms", runtimeMs)
    }

    private fun PublicMetaDbResumePoint.matches(target: PublicMetaDbTarget): Boolean =
        tmdbId == target.tmdbId &&
            isPublicMetaDbMovieType(mediaType) == target.isMovie &&
            (target.isMovie || (season == target.season && episode == target.episode))

    private fun scope(profileId: Int): PublicMetaDbAuthScope = sync.currentScope().also {
        if (it.profileId != profileId) throw CancellationException("PublicMetaDB profile changed")
    }

    companion object {
        const val COMPLETION_THRESHOLD_PERCENT = 80.0
        private const val MIN_RESUME_PERCENT = 1.0
        private const val DEFAULT_MOVIE_RUNTIME_MS = 6_300_000L
        private const val DEFAULT_EPISODE_RUNTIME_MS = 2_640_000L
        private const val RESUME_LOOKUP_PAGE_SIZE = 100
        private const val RESUME_LOOKUP_MAX_PAGES = 5
        private const val LIST_LOOKUP_PAGE_SIZE = 500
    }
}

internal fun isoTimestamp(epochMs: Long): String = Instant.fromEpochMilliseconds(epochMs).toString()

internal class PublicMetaDbRatingsProvider(
    private val sync: PublicMetaDbSyncRepository,
    private val api: PublicMetaDbApiClient,
    private val writes: PublicMetaDbTrackingWrites,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : TrackingRatingProvider {
    override val providerId = TrackingProviderId.PUBLICMETADB
    override val supportedScopes = setOf(
        TrackingRatingScope.MOVIE,
        TrackingRatingScope.SHOW,
        TrackingRatingScope.EPISODE,
    )

    override fun canRate(target: TrackingRatingTarget): Boolean {
        val effective = publicMetaDbTarget(target)
        if (effective.scope !in supportedScopes || !effective.isValid) return false
        val catalogIds = effective.catalog?.contentId?.let(::parseTrackingExternalIds) ?: TrackingExternalIds()
        return effective.ids.hasAny || catalogIds.hasAny
    }

    override suspend fun normalize(target: TrackingRatingTarget): TrackingRatingTarget {
        val effective = publicMetaDbTarget(target)
        if (effective.scope != TrackingRatingScope.EPISODE) return effective
        val catalog = effective.catalog ?: return effective
        val number = effective.episode ?: return effective
        val reference = TrackingMediaReference(
            kind = effective.kind,
            title = effective.title,
            year = effective.year,
            ids = effective.ids,
            episode = TrackingEpisode(season = effective.season, number = number, title = effective.episodeTitle),
            catalog = catalog,
        )
        val mapped = TrackingAnimeIdMapper.toImdbReference(reference)
        if (mapped === reference) return effective
        val episode = mapped.episode ?: return effective
        return effective.copy(
            ids = mapped.ids,
            season = episode.season ?: return effective,
            episode = episode.number,
        )
    }

    override suspend fun fetchRatings(profileId: Int, scope: TrackingRatingScope): List<TrackingRatingRecord> {
        scope(profileId)
        if (scope !in supportedScopes) return emptyList()
        sync.refresh(TrackingRefreshIntent.AUTOMATIC)
        val projection = sync.currentProjection()
        return projection.snapshot.ratings
            .filter { rating -> rating.scope() == scope }
            .map { rating ->
                val media = projection.mediaFor(PublicMetaDbTitleRef(normalizePublicMetaDbMediaType(rating.mediaType), rating.tmdbId))
                TrackingRatingRecord(
                    scope = scope,
                    ids = TrackingExternalIds(
                        imdb = media.imdb ?: rating.imdb,
                        tmdb = rating.tmdbId,
                        tvdb = media.tvdb?.toString(),
                    ),
                    rating = rating.rating.coerceIn(TRACKING_RATING_MIN, TRACKING_RATING_MAX),
                    season = rating.season,
                    episode = rating.episode,
                )
            }
    }

    override suspend fun setRating(profileId: Int, target: TrackingRatingTarget, rating: Int) {
        val authScope = scope(profileId)
        val resolved = resolve(target) ?: throw TrackingRatingException("This title could not be found on PublicMetaDB")
        val value = rating.coerceIn(TRACKING_RATING_MIN, TRACKING_RATING_MAX)
        val entry = entry(target, resolved, value)
        val path = pathFor(target)
        sync.currentProjection().rating(resolved.ref, entry.season, entry.episode)?.id?.let { existingId ->
            api.delete(authScope, "$path/${PublicMetaDbApiClient.pathSegment(existingId)}")
        }
        val body = document(target, resolved, value)
        val response = api.post(authScope, path, body, allowConflict = true)
        val id = if (response.status == 409) {
            findRatingId(authScope, path, target, resolved, value)
                ?: throw TrackingRatingException("This title already has a different PublicMetaDB rating. Remove it on PublicMetaDB first.")
        } else {
            response.itemId()
        }
        sync.rememberMedia(authScope, resolved.seedMedia())
        sync.applyRating(authScope, entry.copy(id = id), value)
    }

    override suspend fun removeRating(profileId: Int, target: TrackingRatingTarget) {
        val authScope = scope(profileId)
        val resolved = resolve(target) ?: return
        val entry = entry(target, resolved, TRACKING_RATING_MIN)
        val path = pathFor(target)
        val id = sync.currentProjection().rating(resolved.ref, entry.season, entry.episode)?.id
        if (id != null) api.delete(authScope, "$path/${PublicMetaDbApiClient.pathSegment(id)}")
        sync.applyRating(authScope, entry, null)
    }

    private suspend fun findRatingId(
        scope: PublicMetaDbAuthScope,
        path: String,
        target: TrackingRatingTarget,
        resolved: PublicMetaDbTarget,
        value: Int,
    ): String? {
        val query = buildList {
            add("tmdb_id" to resolved.tmdbId.toString())
            add("media_type" to resolved.mediaType)
            add("label" to PublicMetaDbConfig.RATING_LABEL)
            if (target.scope == TrackingRatingScope.EPISODE) {
                target.season?.let { add("season" to it.toString()) }
                target.episode?.let { add("episode" to it.toString()) }
            }
        }
        val response = api.get(scope, path, query, allowNotFound = true)
        if (response.status == 404) return null
        return response.items().firstOrNull { row -> ratingFrom(row) == value }?.publicMetaDbFirstString("id", "rating_id")
    }

    private fun ratingFrom(row: JsonObject): Int? {
        val score = row.publicMetaDbDouble("score")
        if (score != null) return (score / 10.0).roundToInt().coerceIn(TRACKING_RATING_MIN, TRACKING_RATING_MAX)
        val rating = row.publicMetaDbDouble("rating") ?: return null
        val normalized = if (rating > 10.0) rating / 10.0 else rating
        return normalized.roundToInt().coerceIn(TRACKING_RATING_MIN, TRACKING_RATING_MAX)
    }

    private suspend fun resolve(target: TrackingRatingTarget): PublicMetaDbTarget? {
        val isMovie = target.scope == TrackingRatingScope.MOVIE
        val resolved = writes.resolve(
            TrackingMediaReference(
                kind = if (isMovie) TrackingMediaKind.MOVIE else TrackingMediaKind.SHOW,
                title = target.title,
                year = target.year,
                ids = target.ids,
                catalog = target.catalog,
            ),
        ) ?: return null
        return if (target.scope == TrackingRatingScope.EPISODE) {
            resolved.copy(season = target.season, episode = target.episode)
        } else {
            resolved.copy(season = null, episode = null)
        }
    }

    private fun entry(target: TrackingRatingTarget, resolved: PublicMetaDbTarget, value: Int) = PublicMetaDbRatingEntry(
        tmdbId = resolved.tmdbId,
        mediaType = resolved.mediaType,
        season = target.season.takeIf { target.scope == TrackingRatingScope.EPISODE },
        episode = target.episode.takeIf { target.scope == TrackingRatingScope.EPISODE },
        imdb = resolved.imdb ?: target.ids.imdb,
        rating = value,
        ratedAtEpochMs = now(),
    )

    private fun document(target: TrackingRatingTarget, resolved: PublicMetaDbTarget, rating: Int): JsonObject = buildJsonObject {
        put("tmdb_id", resolved.tmdbId)
        put("media_type", resolved.mediaType)
        if (target.scope == TrackingRatingScope.EPISODE) {
            put("season", target.season ?: 1)
            put("episode", target.episode ?: 1)
        }
        put("score", rating * 10)
        put("label", PublicMetaDbConfig.RATING_LABEL)
    }

    private fun pathFor(target: TrackingRatingTarget): String =
        if (target.scope == TrackingRatingScope.EPISODE) "/api/external/episode-ratings" else "/api/external/ratings"

    private fun publicMetaDbTarget(target: TrackingRatingTarget): TrackingRatingTarget {
        val catalogType = target.catalog?.contentType?.trim()?.lowercase()
        val isAnimeMovie = target.scope == TrackingRatingScope.SHOW &&
            target.kind == TrackingMediaKind.ANIME &&
            (catalogType == "movie" || catalogType == "film")
        return if (isAnimeMovie) target.copy(scope = TrackingRatingScope.MOVIE) else target
    }

    private fun PublicMetaDbRatingEntry.scope(): TrackingRatingScope = when {
        isPublicMetaDbMovieType(mediaType) -> TrackingRatingScope.MOVIE
        season != null && episode != null -> TrackingRatingScope.EPISODE
        else -> TrackingRatingScope.SHOW
    }

    private fun scope(profileId: Int) = sync.currentScope().also {
        if (it.profileId != profileId) throw CancellationException("PublicMetaDB profile changed")
    }
}
