package com.nuvio.app.features.wetrakr

import com.nuvio.app.features.tracking.TrackingAnimeIdMapper
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.tracking.TrackingListWriter
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResolution
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbler
import com.nuvio.app.features.tracking.notFoundTotal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Instant

internal data class WeTrakrTarget(
    val isMovie: Boolean,
    val ids: WeTrakrIds,
    val title: String?,
    val year: Int?,
    val media: WeTrakrMedia?,
) {
    val key: String
        get() = media?.takeIf { it.id > 0L }?.let { "${it.type}:${it.id}" }
            ?: "${if (isMovie) "movie" else "show"}:${ids.contentId().orEmpty().lowercase()}"

    fun toMedia(): WeTrakrMedia = media ?: WeTrakrMedia(
        id = 0L,
        type = if (isMovie) WeTrakrMediaType.MOVIE else WeTrakrMediaType.SHOW,
        title = title,
        year = year,
        ids = ids,
    )
}

internal data class WeTrakrEpisodeWrite(
    val season: Int,
    val number: Int,
    val trackedAtEpochMs: Long? = null,
)

class WeTrakrTrackingWrites internal constructor(
    private val sync: WeTrakrSyncRepository,
    private val api: WeTrakrApiClient,
) : TrackingScrobbler, TrackingHistoryWriter, TrackingListWriter {
    override val providerId = TrackingProviderId.WETRAKR
    private val writeLock = Mutex()

    override suspend fun scrobble(profileId: Int, action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        val scope = scope(profileId)
        val media = TrackingAnimeIdMapper.toImdbReference(event.media)
        val target = resolve(media) ?: return
        val episode = media.episode
        if (!target.isMovie && episode == null) return
        val body = buildJsonObject {
            if (target.isMovie) {
                put("movie", target.titleObject())
            } else {
                put("show", target.titleObject())
                putJsonObject("episode") {
                    put("season", episode?.season ?: 1)
                    put("number", episode?.number ?: 1)
                }
            }
            put("progress", event.progressPercent.coerceIn(0.0, 100.0))
        }
        val path = when (action) {
            TrackingScrobbleAction.START -> "/scrobble/start"
            TrackingScrobbleAction.PAUSE -> "/scrobble/pause"
            TrackingScrobbleAction.STOP -> "/scrobble/stop"
        }
        val response = api.post(scope, path, body, allowNotFound = true)
        if (response.status == 404) return
        if (action == TrackingScrobbleAction.STOP && event.progressPercent >= COMPLETION_THRESHOLD_PERCENT) {
            sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
        }
    }

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val scope = scope(profileId)
        val movies = linkedMapOf<String, Pair<WeTrakrTarget, Long?>>()
        val shows = linkedMapOf<String, WeTrakrTarget>()
        val wholeShows = linkedMapOf<String, Long?>()
        val episodes = linkedMapOf<String, MutableList<WeTrakrEpisodeWrite>>()
        var notFound = 0
        TrackingAnimeIdMapper.toImdbHistoryItems(items).forEach { item ->
            val target = resolve(item.media)
            if (target == null) {
                notFound += 1
                return@forEach
            }
            val episode = item.media.episode
            when {
                target.isMovie -> movies[target.key] = target to item.watchedAtEpochMs
                episode == null -> {
                    shows[target.key] = target
                    wholeShows[target.key] = item.watchedAtEpochMs
                }
                else -> {
                    shows[target.key] = target
                    episodes.getOrPut(target.key) { mutableListOf() } +=
                        WeTrakrEpisodeWrite(episode.season ?: 1, episode.number, item.watchedAtEpochMs)
                }
            }
        }
        if (movies.isEmpty() && shows.isEmpty()) return TrackingMutationResult(items.size, notFound)
        val document = buildJsonObject {
            put("movies", buildJsonArray {
                movies.values.forEach { (target, watchedAt) ->
                    add(buildJsonObject {
                        target.putIdentity(this)
                        put("status", WeTrakrStatus.WATCHED)
                        watchedAt?.let { put("tracked_at", isoTimestamp(it)) }
                    })
                }
            })
            put("shows", buildJsonArray {
                shows.forEach { (key, target) ->
                    add(showDocument(target, WeTrakrStatus.WATCHED, episodes[key].orEmpty(), key in wholeShows, wholeShows[key]))
                }
            })
        }
        val response = writeLock.withLock { api.post(scope, "/sync/tracking", document) }
        movies.values.forEach { (target, _) -> sync.applyStatus(scope, target.toMedia(), WeTrakrStatus.WATCHED) }
        sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
        return TrackingMutationResult(items.size, notFound + response.notFoundCount())
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult {
        val scope = scope(profileId)
        val movies = linkedMapOf<String, WeTrakrTarget>()
        val shows = linkedMapOf<String, WeTrakrTarget>()
        val wholeShows = mutableSetOf<String>()
        val episodes = linkedMapOf<String, MutableList<WeTrakrEpisodeWrite>>()
        var notFound = 0
        TrackingAnimeIdMapper.toImdbReferences(items).forEach { media ->
            val target = resolve(media)
            if (target == null) {
                notFound += 1
                return@forEach
            }
            val episode = media.episode
            when {
                target.isMovie -> movies[target.key] = target
                episode == null -> {
                    shows[target.key] = target
                    wholeShows += target.key
                }
                else -> {
                    shows[target.key] = target
                    episodes.getOrPut(target.key) { mutableListOf() } += WeTrakrEpisodeWrite(episode.season ?: 1, episode.number)
                }
            }
        }
        if (movies.isEmpty() && shows.isEmpty()) return TrackingMutationResult(items.size, notFound)
        val document = buildJsonObject {
            put("movies", buildJsonArray {
                movies.values.forEach { target ->
                    add(buildJsonObject {
                        target.putIdentity(this)
                        put("status", WeTrakrStatus.WATCHED)
                    })
                }
            })
            put("shows", buildJsonArray {
                shows.forEach { (key, target) ->
                    add(showDocument(target, WeTrakrStatus.WATCHED, episodes[key].orEmpty(), key in wholeShows, null))
                }
            })
        }
        val response = writeLock.withLock { api.post(scope, "/sync/tracking/remove/all", document) }
        movies.values.forEach { target -> sync.applyStatus(scope, target.toMedia(), null) }
        sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
        return TrackingMutationResult(items.size, notFound + response.notFoundCount())
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
                val target = resolve(media)
                if (target == null) {
                    notFound += 1
                    return@forEach
                }
                val status = weTrakrStatusFor(destination, target.isMovie)
                if (!setStatus(scope, target, status)) {
                    notFound += 1
                    return@forEach
                }
                resolutions += TrackingMutationResolution(
                    listStatus = weTrakrStatusDefinitionFor(status)?.trackingStatus ?: destination,
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
            items.forEach { media ->
                val target = resolve(media)
                if (target == null) {
                    notFound += 1
                    return@forEach
                }
                val current = currentStatus(target)
                val status = current ?: WeTrakrStatus.PLANNING
                if (status == WeTrakrStatus.WATCHED || status == WeTrakrStatus.WAITING) return@forEach
                clearStatus(scope, target, status, removeAllPlays = false)
            }
        }
        sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFound)
    }

    internal suspend fun setStatus(scope: WeTrakrAuthScope, target: WeTrakrTarget, status: String): Boolean {
        val allowed = if (target.isMovie) WeTrakrStatus.movieStatuses else WeTrakrStatus.writableShowStatuses
        if (status !in allowed) return false
        val document = singleDocument(target, status)
        val response = api.post(scope, "/sync/tracking", document)
        if (response.notFoundCount() > 0) return false
        sync.applyStatus(scope, target.toMedia(), status)
        return true
    }

    internal suspend fun clearStatus(scope: WeTrakrAuthScope, target: WeTrakrTarget, status: String, removeAllPlays: Boolean) {
        val path = if (status == WeTrakrStatus.WATCHED && removeAllPlays) "/sync/tracking/remove/all" else "/sync/tracking/remove"
        api.post(scope, path, singleDocument(target, status))
        sync.applyStatus(scope, target.toMedia(), null)
    }

    internal fun currentStatus(target: WeTrakrTarget): String? {
        val projection = sync.currentProjection()
        val contentType = if (target.isMovie) "movie" else "series"
        return target.ids.aliases().firstNotNullOfOrNull { alias -> projection.entryFor(alias, contentType)?.status }
    }

    internal fun resolve(media: TrackingMediaReference): WeTrakrTarget? {
        val isMovie = when (media.kind) {
            TrackingMediaKind.MOVIE -> true
            TrackingMediaKind.SHOW -> false
            TrackingMediaKind.ANIME -> media.episode == null &&
                media.catalog?.contentType?.let { weTrakrTypeMatches(true, it) } == true
        }
        val contentType = if (isMovie) "movie" else "series"
        val projection = sync.currentProjection()
        val known = listOfNotNull(media.catalog?.contentId, media.ids.imdb, media.ids.tmdb?.let { "tmdb:$it" })
            .firstNotNullOfOrNull { id -> projection.mediaFor(id, contentType) }
        val ids = media.ids.toWeTrakrIds().takeIf(WeTrakrIds::hasAny)
            ?: known?.ids
            ?: media.catalog?.contentId?.let(::parseCatalogIds)?.takeIf(WeTrakrIds::hasAny)
            ?: return null
        return WeTrakrTarget(
            isMovie = isMovie,
            ids = ids,
            title = media.title ?: known?.title,
            year = media.year ?: known?.year,
            media = known,
        )
    }

    private fun parseCatalogIds(contentId: String): WeTrakrIds {
        val normalized = normalizeWeTrakrContentId(contentId)
        return when {
            normalized.startsWith("tt") -> WeTrakrIds(imdb = normalized)
            normalized.startsWith("tmdb:") -> WeTrakrIds(tmdb = normalized.substringAfter(':').toLongOrNull())
            normalized.startsWith("tvdb:") -> WeTrakrIds(tvdb = normalized.substringAfter(':').toLongOrNull())
            else -> WeTrakrIds()
        }
    }

    private fun singleDocument(target: WeTrakrTarget, status: String): JsonObject = buildJsonObject {
        put(if (target.isMovie) "movies" else "shows", buildJsonArray {
            add(buildJsonObject {
                target.putIdentity(this)
                put("status", status)
            })
        })
    }

    private fun showDocument(
        target: WeTrakrTarget,
        status: String,
        episodes: List<WeTrakrEpisodeWrite>,
        wholeShow: Boolean,
        wholeShowTrackedAt: Long?,
    ): JsonObject = buildJsonObject {
        target.putIdentity(this)
        put("status", status)
        if (wholeShow || episodes.isEmpty()) {
            wholeShowTrackedAt?.let { put("tracked_at", isoTimestamp(it)) }
            return@buildJsonObject
        }
        put("seasons", buildJsonArray {
            episodes.groupBy(WeTrakrEpisodeWrite::season).sortedByKey().forEach { (season, seasonEpisodes) ->
                add(buildJsonObject {
                    put("number", season)
                    put("episodes", buildJsonArray {
                        seasonEpisodes.distinctBy(WeTrakrEpisodeWrite::number).sortedBy(WeTrakrEpisodeWrite::number).forEach { episode ->
                            add(buildJsonObject {
                                put("number", episode.number)
                                put("status", status)
                                episode.trackedAtEpochMs?.let { put("tracked_at", isoTimestamp(it)) }
                            })
                        }
                    })
                })
            }
        })
    }

    private fun scope(profileId: Int): WeTrakrAuthScope = sync.currentScope().also {
        if (it.profileId != profileId) throw CancellationException("WeTrakr profile changed")
    }

    companion object {
        const val COMPLETION_THRESHOLD_PERCENT = 80.0
    }
}

internal fun WeTrakrTarget.putIdentity(builder: JsonObjectBuilder) {
    val wetrakrId = media?.id?.takeIf { it > 0L }
    if (wetrakrId != null) {
        builder.put("id", wetrakrId)
    } else {
        builder.put("ids", ids.toJson())
    }
}

internal fun WeTrakrTarget.titleObject(): JsonObject = buildJsonObject {
    (media?.title ?: title)?.takeIf(String::isNotBlank)?.let { put("title", it) }
    (media?.year ?: year)?.let { put("year", it) }
    if (ids.hasAny) put("ids", ids.toJson())
}

internal fun WeTrakrIds.toJson(): JsonObject = buildJsonObject {
    imdb?.let { put("imdb", it) }
    tmdb?.let { put("tmdb", it) }
    tvdb?.let { put("tvdb", it) }
}

internal fun WeTrakrResponse.notFoundCount(): Int {
    val root = json() as? JsonObject ?: return 0
    return (root["notFound"] ?: root["not_found"]).notFoundTotal()
}

internal fun isoTimestamp(epochMs: Long): String = Instant.fromEpochMilliseconds(epochMs).toString()

private fun <K : Comparable<K>, V> Map<K, V>.sortedByKey(): Map<K, V> = entries.sortedBy { it.key }.associate { it.key to it.value }
