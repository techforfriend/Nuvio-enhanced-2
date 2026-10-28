package com.nuvio.app.features.publicmetadb

import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tracking.TrackingAnimeIdMapper
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal data class PublicMetaDbTarget(
    val tmdbId: Long,
    val isMovie: Boolean,
    val season: Int? = null,
    val episode: Int? = null,
    val imdb: String? = null,
    val title: String? = null,
    val year: Int? = null,
) {
    val mediaType: String
        get() = if (isMovie) PublicMetaDbMediaType.MOVIE else PublicMetaDbMediaType.TV

    val ref: PublicMetaDbTitleRef
        get() = PublicMetaDbTitleRef(mediaType, tmdbId)

    val hasEpisode: Boolean
        get() = !isMovie && season != null && episode != null

    fun seedMedia(): PublicMetaDbMedia = PublicMetaDbMedia(
        tmdbId = tmdbId,
        mediaType = mediaType,
        title = title,
        year = year,
        imdb = imdb,
    )
}

private data class PublicMetaDbAnimeSeason(
    val seasonNumber: Int,
    val tmdbSeason: Int,
    val episodeStart: Int,
    val episodeEnd: Int?,
    val votes: Long,
)

internal class PublicMetaDbIdResolver(
    private val api: PublicMetaDbApiClient,
    private val sync: PublicMetaDbSyncRepository,
) {
    private val lock = Mutex()
    private val lookupCache = mutableMapOf<String, Long?>()
    private val animeSeasonCache = mutableMapOf<Long, List<PublicMetaDbAnimeSeason>>()

    suspend fun resolve(media: TrackingMediaReference): PublicMetaDbTarget? {
        val scope = sync.currentScope()
        val originalIds = media.ids.mergeMissing(media.catalog?.contentId?.let(::parseTrackingExternalIds) ?: TrackingExternalIds())
        val isAnimeSource = media.kind == TrackingMediaKind.ANIME || originalIds.hasAnimeIds()
        val mapped = if (isAnimeSource) TrackingAnimeIdMapper.toImdbReference(media) else media
        val wasMapped = mapped !== media
        val isMovie = when (mapped.kind) {
            TrackingMediaKind.MOVIE -> true
            TrackingMediaKind.SHOW -> false
            TrackingMediaKind.ANIME -> mapped.episode == null &&
                mapped.catalog?.contentType?.trim()?.lowercase()?.let { it == "movie" || it == "film" } == true
        }
        val ids = mapped.ids.mergeMissing(
            mapped.catalog?.contentId?.let(::parseTrackingExternalIds) ?: TrackingExternalIds(),
        ).let { if (wasMapped) it else it.mergeMissing(originalIds) }
        val tmdbId = resolveTmdb(scope, ids, isMovie) ?: return null
        val imdb = ids.imdb?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        if (isMovie) {
            return PublicMetaDbTarget(tmdbId = tmdbId, isMovie = true, imdb = imdb, title = mapped.title, year = mapped.year)
        }
        val episodeRef = mapped.episode
            ?: return PublicMetaDbTarget(tmdbId = tmdbId, isMovie = false, imdb = imdb, title = mapped.title, year = mapped.year)
        val number = episodeRef.number
        val season = episodeRef.season ?: 1
        val adjusted = if (isAnimeSource) animeSeasonEpisode(scope, tmdbId, season, number) else null
        return PublicMetaDbTarget(
            tmdbId = tmdbId,
            isMovie = false,
            season = adjusted?.first ?: season,
            episode = adjusted?.second ?: number,
            imdb = imdb,
            title = mapped.title,
            year = mapped.year,
        )
    }

    private suspend fun resolveTmdb(scope: PublicMetaDbAuthScope, ids: TrackingExternalIds, isMovie: Boolean): Long? {
        ids.tmdb?.takeIf { it > 0L }?.let { return it }
        val mediaType = if (isMovie) PublicMetaDbMediaType.MOVIE else PublicMetaDbMediaType.TV
        val imdb = ids.imdb?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        if (imdb != null) {
            sync.currentProjection().tmdbForImdb(imdb, isMovie)?.let { return it }
            val viaTmdb = try {
                TmdbService.ensureTmdbId(imdb, if (isMovie) "movie" else "tv")?.toLongOrNull()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            viaTmdb?.takeIf { it > 0L }?.let { return it }
            lookup(scope, "imdb", imdb, mediaType)?.let { return it }
        }
        ids.tvdb?.trim()?.takeIf(String::isNotEmpty)?.let { tvdb -> lookup(scope, "tvdb", tvdb, mediaType)?.let { return it } }
        ids.mal?.let { mal -> lookup(scope, "mal", mal.toString(), mediaType)?.let { return it } }
        ids.anilist?.let { anilist -> lookup(scope, "anilist", anilist.toString(), mediaType)?.let { return it } }
        ids.anidb?.let { anidb -> lookup(scope, "anidb", anidb.toString(), mediaType)?.let { return it } }
        ids.trakt?.let { trakt -> lookup(scope, "trakt", trakt.toString(), mediaType)?.let { return it } }
        return null
    }

    private suspend fun lookup(scope: PublicMetaDbAuthScope, idType: String, idValue: String, mediaType: String): Long? {
        val key = "$idType:$idValue:$mediaType"
        lock.withLock { if (lookupCache.containsKey(key)) return lookupCache[key] }
        val result = try {
            val response = api.get(
                scope,
                "/api/external/mappings/lookup",
                listOf("id_type" to idType, "id_value" to idValue, "media_type" to mediaType),
                allowNotFound = true,
            )
            if (response.status == 404) null else extractLookupTmdbId(response.json(), mediaType)
        } catch (error: CancellationException) {
            throw error
        } catch (error: PublicMetaDbAuthException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        lock.withLock {
            if (lookupCache.size >= MAX_CACHE_ENTRIES) lookupCache.clear()
            lookupCache[key] = result
        }
        return result
    }

    private fun extractLookupTmdbId(root: JsonElement?, mediaType: String): Long? {
        val obj = root as? JsonObject
        val candidates = (obj?.get("results") ?: obj?.get("items") ?: obj?.get("data") ?: root) as? JsonArray
        if (candidates != null) {
            return candidates
                .mapNotNull { it as? JsonObject }
                .filter { row ->
                    row.publicMetaDbFirstString("media_type", "mediaType")
                        ?.let { normalizePublicMetaDbMediaType(it) == mediaType } ?: true
                }
                .sortedByDescending { it.publicMetaDbLong("votes") ?: 0L }
                .firstNotNullOfOrNull { row -> row.publicMetaDbTmdbId(mediaType) }
        }
        return obj?.publicMetaDbTmdbId(mediaType)
    }

    private suspend fun animeSeasonEpisode(
        scope: PublicMetaDbAuthScope,
        tmdbId: Long,
        season: Int,
        episode: Int,
    ): Pair<Int, Int>? {
        if (season <= 0) return null
        val seasons = animeSeasons(scope, tmdbId)
        val match = seasons.filter { it.seasonNumber == season }.maxByOrNull(PublicMetaDbAnimeSeason::votes) ?: return null
        val mappedEpisode = match.episodeStart + episode - 1
        if (match.episodeEnd != null && mappedEpisode > match.episodeEnd) return null
        return match.tmdbSeason to mappedEpisode
    }

    private suspend fun animeSeasons(scope: PublicMetaDbAuthScope, tmdbId: Long): List<PublicMetaDbAnimeSeason> {
        lock.withLock { animeSeasonCache[tmdbId]?.let { return it } }
        val seasons = try {
            api.get(
                scope,
                "/api/external/anime-seasons",
                listOf("tmdb_id" to tmdbId.toString()),
                allowNotFound = true,
            ).takeIf { it.status in 200..299 }?.items().orEmpty().mapNotNull { row ->
                val seasonNumber = row.publicMetaDbFirstInt("season_number", "season", "anime_season") ?: return@mapNotNull null
                val tmdbSeason = row.publicMetaDbFirstInt("tmdb_season", "tmdb_season_number") ?: return@mapNotNull null
                val start = row.publicMetaDbFirstInt("tmdb_episode_start", "episode_start", "start_episode") ?: return@mapNotNull null
                PublicMetaDbAnimeSeason(
                    seasonNumber = seasonNumber,
                    tmdbSeason = tmdbSeason,
                    episodeStart = start.coerceAtLeast(1),
                    episodeEnd = row.publicMetaDbFirstInt("tmdb_episode_end", "episode_end", "end_episode"),
                    votes = row.publicMetaDbLong("votes") ?: 0L,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PublicMetaDbAuthException) {
            throw error
        } catch (_: Exception) {
            return emptyList()
        }
        lock.withLock {
            if (animeSeasonCache.size >= MAX_CACHE_ENTRIES) animeSeasonCache.clear()
            animeSeasonCache[tmdbId] = seasons
        }
        return seasons
    }

    fun clear() {
        if (!lock.tryLock()) return
        try {
            lookupCache.clear()
            animeSeasonCache.clear()
        } finally {
            lock.unlock()
        }
    }

    private fun TrackingExternalIds.hasAnimeIds(): Boolean =
        mal != null || anilist != null || anidb != null || kitsu != null

    private companion object {
        const val MAX_CACHE_ENTRIES = 512
    }
}
