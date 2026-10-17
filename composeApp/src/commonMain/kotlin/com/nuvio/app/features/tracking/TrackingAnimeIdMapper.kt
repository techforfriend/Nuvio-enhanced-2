package com.nuvio.app.features.tracking

import com.nuvio.app.features.player.skip.SimklIdResolver
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object TrackingAnimeIdMapper {
    private data class Mapped(
        val imdb: String,
        val isMovie: Boolean,
        val season: Int? = null,
        val episode: Int? = null,
    )

    private val lock = Mutex()
    private val cache = mutableMapOf<String, Mapped>()
    private const val MAX_CACHE_ENTRIES = 512
    private val animePrefixes = setOf("kitsu", "mal", "anilist", "anidb")

    suspend fun toImdbReference(media: TrackingMediaReference): TrackingMediaReference {
        val mapped = map(
            contentId = media.catalog?.contentId,
            videoId = media.catalog?.videoId,
            contentType = media.catalog?.contentType,
            ids = media.ids,
            episode = media.episode?.number,
        ) ?: return media
        val ids = media.ids.mergeMissing(TrackingExternalIds(imdb = mapped.imdb))
        if (mapped.isMovie) {
            return media.copy(
                kind = TrackingMediaKind.MOVIE,
                ids = ids,
                episode = null,
                catalog = TrackingCatalogReference(contentId = mapped.imdb, contentType = "movie"),
            )
        }
        val season = mapped.season ?: return media
        val number = mapped.episode ?: return media
        return media.copy(
            kind = TrackingMediaKind.SHOW,
            ids = ids,
            episode = media.episode?.copy(season = season, number = number)
                ?: TrackingEpisode(season = season, number = number),
            catalog = TrackingCatalogReference(
                contentId = mapped.imdb,
                contentType = "series",
                videoId = "${mapped.imdb}:$season:$number",
            ),
        )
    }

    suspend fun toImdbReferences(items: Collection<TrackingMediaReference>): List<TrackingMediaReference> =
        items.map { toImdbReference(it) }

    suspend fun toImdbHistoryItems(items: Collection<TrackingHistoryItem>): List<TrackingHistoryItem> =
        items.map { it.copy(media = toImdbReference(it.media)) }

    suspend fun toImdbWatchedItems(items: Collection<WatchedItem>): List<WatchedItem> =
        items.map { toImdbWatchedItem(it) }

    private suspend fun toImdbWatchedItem(item: WatchedItem): WatchedItem {
        val mapped = map(
            contentId = item.id,
            videoId = item.videoId,
            contentType = item.type,
            ids = parseTrackingExternalIds(item.id).mergeMissing(parseTrackingExternalIds(item.videoId)),
            episode = item.episode,
        ) ?: return item
        if (mapped.isMovie) return item.copy(id = mapped.imdb, videoId = mapped.imdb)
        val season = mapped.season ?: return item
        val number = mapped.episode ?: return item
        return item.copy(
            id = mapped.imdb,
            season = season,
            episode = number,
            videoId = "${mapped.imdb}:$season:$number",
        )
    }

    private suspend fun map(
        contentId: String?,
        videoId: String?,
        contentType: String?,
        ids: TrackingExternalIds,
        episode: Int?,
    ): Mapped? {
        if (!ids.imdb.isNullOrBlank() || ids.tmdb != null || !ids.tvdb.isNullOrBlank() || ids.trakt != null) return null
        val (source, id) = when {
            ids.kitsu != null -> "kitsu" to ids.kitsu
            ids.mal != null -> "mal" to ids.mal
            ids.anilist != null -> "anilist" to ids.anilist
            ids.anidb != null -> "anidb" to ids.anidb
            else -> return null
        }
        val type = contentType?.trim()?.lowercase()
        val isMovie = type == "movie" || type == "film"
        val animeEpisode = if (isMovie) null else (videoEpisode(videoId) ?: episode ?: return null)
        val key = "$source:$id:${animeEpisode ?: "movie"}"
        return lock.withLock {
            cache[key]?.let { return@withLock it }
            val result = lookup(source, id, isMovie, animeEpisode) ?: return@withLock null
            if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
            cache[key] = result
            result
        }
    }

    private suspend fun lookup(source: String, id: Long, isMovie: Boolean, animeEpisode: Int?): Mapped? {
        val resolved = try {
            SimklIdResolver.resolveIds(source, id.toString(), "anime")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return null
        val imdb = resolved.imdb?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: return null
        if (isMovie) return Mapped(imdb = imdb, isMovie = true)
        val number = animeEpisode ?: return null
        val episodes = try {
            SimklIdResolver.getEpisodeMapping(resolved.simklId, resolved.type)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        episodes.firstOrNull { it.animeEpisode == number }?.let {
            return Mapped(imdb = imdb, isMovie = false, season = it.tvdbSeason, episode = it.tvdbEpisode)
        }
        episodes.filter { it.animeEpisode < number }.maxByOrNull { it.animeEpisode }?.let { previous ->
            return Mapped(
                imdb = imdb,
                isMovie = false,
                season = previous.tvdbSeason,
                episode = previous.tvdbEpisode + (number - previous.animeEpisode),
            )
        }
        if (episodes.isNotEmpty()) return null
        val season = resolved.tvdbSeason ?: return null
        return Mapped(imdb = imdb, isMovie = false, season = season, episode = number)
    }

    private fun videoEpisode(videoId: String?): Int? {
        val parts = videoId?.trim()?.split(':') ?: return null
        if (parts.size < 3 || parts[0].lowercase() !in animePrefixes) return null
        return parts[2].toIntOrNull()?.takeIf { it > 0 }
    }
}
