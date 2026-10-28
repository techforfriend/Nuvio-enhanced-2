package com.nuvio.app.features.publicmetadb

import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.tracking.LibraryListPrivacy
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId

internal const val PUBLICMETADB_LIST_KEY_PREFIX = "publicmetadb:list:"
internal const val PUBLICMETADB_PENDING_WATCHLIST_KEY = "publicmetadb:watchlist"
internal const val PUBLICMETADB_SOURCE_PLAYBACK = "publicmetadb_playback"
internal const val PUBLICMETADB_SOURCE_PROGRESS = "publicmetadb_progress"
internal const val PUBLICMETADB_SOURCE_LOCAL = "publicmetadb_local"
private const val PUBLICMETADB_MIN_PLAYBACK_PERCENT = 1.0
private const val PUBLICMETADB_MAX_PLAYBACK_PERCENT = 97.0
private const val PUBLICMETADB_MAX_NEXT_UP_SEEDS = 120

private val publicMetaDbSupportedContentTypes = setOf("movie", "series", "tv", "show", "anime")

internal fun publicMetaDbListKey(listId: String): String = "$PUBLICMETADB_LIST_KEY_PREFIX$listId"

internal fun publicMetaDbListId(key: String): String? =
    key.takeIf { it.startsWith(PUBLICMETADB_LIST_KEY_PREFIX) }?.removePrefix(PUBLICMETADB_LIST_KEY_PREFIX)?.takeIf(String::isNotBlank)

internal data class PublicMetaDbTitleRef(val mediaType: String, val tmdbId: Long) {
    val key: String get() = publicMetaDbMediaKey(mediaType, tmdbId)
    val isMovie: Boolean get() = isPublicMetaDbMovieType(mediaType)
}

internal class PublicMetaDbProjection(val snapshot: PublicMetaDbSyncSnapshot) {
    private val media: Map<String, PublicMetaDbMedia> = snapshot.media

    private val aliasIndex: Map<String, List<PublicMetaDbTitleRef>> = buildMap<String, MutableList<PublicMetaDbTitleRef>> {
        media.values.forEach { item ->
            val ref = PublicMetaDbTitleRef(normalizePublicMetaDbMediaType(item.mediaType), item.tmdbId)
            item.aliases().forEach { alias ->
                val list = getOrPut(alias.lowercase()) { mutableListOf() }
                if (ref !in list) list.add(ref)
            }
        }
    }

    val lists: List<PublicMetaDbList> = snapshot.lists
        .sortedWith(compareBy<PublicMetaDbList> { if (it.type == PublicMetaDbListType.WATCHLIST) 0 else 1 }.thenBy { it.createdAtEpochMs })

    val watchlist: PublicMetaDbList? = lists.firstOrNull { it.type == PublicMetaDbListType.WATCHLIST }

    val tabs: List<TrackingLibraryTab> = buildList {
        if (watchlist == null) {
            add(
                TrackingLibraryTab(
                    key = PUBLICMETADB_PENDING_WATCHLIST_KEY,
                    title = PublicMetaDbConfig.DEFAULT_WATCHLIST_NAME,
                    providerId = TrackingProviderId.PUBLICMETADB,
                    kind = TrackingLibraryTabKind.WATCHLIST,
                    supportedContentTypes = publicMetaDbSupportedContentTypes,
                    privacy = LibraryListPrivacy.PRIVATE,
                ),
            )
        }
        lists.forEach { list ->
            add(
                TrackingLibraryTab(
                    key = publicMetaDbListKey(list.id),
                    title = list.name,
                    providerId = TrackingProviderId.PUBLICMETADB,
                    kind = if (list.type == PublicMetaDbListType.WATCHLIST) TrackingLibraryTabKind.WATCHLIST else TrackingLibraryTabKind.PERSONAL,
                    supportedContentTypes = publicMetaDbSupportedContentTypes,
                    description = list.description,
                    privacy = if (list.isPublic) LibraryListPrivacy.PUBLIC else LibraryListPrivacy.PRIVATE,
                ),
            )
        }
    }

    val watchlistKey: String
        get() = watchlist?.let { publicMetaDbListKey(it.id) } ?: PUBLICMETADB_PENDING_WATCHLIST_KEY

    fun mediaFor(ref: PublicMetaDbTitleRef): PublicMetaDbMedia =
        media[ref.key] ?: PublicMetaDbMedia(tmdbId = ref.tmdbId, mediaType = normalizePublicMetaDbMediaType(ref.mediaType))

    fun contentIdFor(ref: PublicMetaDbTitleRef): String = mediaFor(ref).contentId()

    fun aliasesFor(ref: PublicMetaDbTitleRef): List<String> = mediaFor(ref).aliases()

    fun resolve(contentId: String, contentType: String? = null): PublicMetaDbTitleRef? {
        val normalized = normalizePublicMetaDbContentId(contentId)
        if (normalized.startsWith("tmdb:")) {
            val tmdbId = normalized.substringAfter(':').toLongOrNull() ?: return null
            val explicitType = contentId.trim().lowercase().split(':').getOrNull(1)
                ?.takeIf { it in setOf("movie", "tv", "series", "show") }
            val type = explicitType ?: contentType
            if (type != null) return PublicMetaDbTitleRef(normalizePublicMetaDbMediaType(type), tmdbId)
            return listOf(PublicMetaDbMediaType.TV, PublicMetaDbMediaType.MOVIE)
                .map { PublicMetaDbTitleRef(it, tmdbId) }
                .firstOrNull { media.containsKey(it.key) || hasActivity(it) }
        }
        return aliasIndex[normalized].orEmpty()
            .firstOrNull { ref -> publicMetaDbTypeMatches(ref.mediaType, contentType) }
    }

    fun tmdbForImdb(imdb: String, isMovie: Boolean): Long? =
        aliasIndex[imdb.trim().lowercase()].orEmpty().firstOrNull { it.isMovie == isMovie }?.tmdbId

    private fun hasActivity(ref: PublicMetaDbTitleRef): Boolean =
        snapshot.watched.any { it.tmdbId == ref.tmdbId && isPublicMetaDbMovieType(it.mediaType) == ref.isMovie } ||
            snapshot.listItems.values.any { items -> items.any { it.tmdbId == ref.tmdbId && isPublicMetaDbMovieType(it.mediaType) == ref.isMovie } }

    fun listItem(listId: String, ref: PublicMetaDbTitleRef): PublicMetaDbListItem? =
        snapshot.listItems[listId].orEmpty().firstOrNull { it.tmdbId == ref.tmdbId && isPublicMetaDbMovieType(it.mediaType) == ref.isMovie }

    fun membershipKeys(ref: PublicMetaDbTitleRef): Set<String> =
        lists.filter { list -> listItem(list.id, ref) != null }.mapTo(linkedSetOf()) { publicMetaDbListKey(it.id) }

    val librarySections: List<LibrarySection>
    val libraryItems: List<LibraryItem>

    init {
        val keysByTitle = linkedMapOf<String, MutableSet<String>>()
        lists.forEach { list ->
            snapshot.listItems[list.id].orEmpty().forEach { item ->
                keysByTitle.getOrPut(publicMetaDbMediaKey(item.mediaType, item.tmdbId)) { linkedSetOf() } += publicMetaDbListKey(list.id)
            }
        }
        val sections = lists.mapNotNull { list ->
            val key = publicMetaDbListKey(list.id)
            val items = snapshot.listItems[list.id].orEmpty()
                .map { item ->
                    item.toLibraryItem(keysByTitle[publicMetaDbMediaKey(item.mediaType, item.tmdbId)].orEmpty().ifEmpty { setOf(key) })
                }
                .distinctBy { "${it.type}|${it.id}" }
                .sortedByDescending(LibraryItem::savedAtEpochMs)
            items.takeIf(List<LibraryItem>::isNotEmpty)?.let { LibrarySection(type = key, displayTitle = list.name, items = it) }
        }
        librarySections = sections
        libraryItems = sections.flatMap(LibrarySection::items)
            .groupBy { "${it.type}|${it.id}" }
            .map { (_, group) -> group.maxBy(LibraryItem::savedAtEpochMs) }
            .sortedByDescending(LibraryItem::savedAtEpochMs)
    }

    fun libraryItem(contentId: String, contentType: String? = null): LibraryItem? {
        val ref = resolve(contentId, contentType) ?: return null
        return libraryItems.firstOrNull { it.tmdbId?.toLong() == ref.tmdbId && publicMetaDbTypeMatches(ref.mediaType, it.type) }
    }

    val watchedItems: List<WatchedItem> = snapshot.watched.mapNotNull { entry ->
        val ref = PublicMetaDbTitleRef(normalizePublicMetaDbMediaType(entry.mediaType), entry.tmdbId)
        val title = mediaFor(ref)
        val contentId = title.contentId()
        if (ref.isMovie) {
            WatchedItem(
                id = contentId,
                type = "movie",
                name = title.displayTitle(),
                poster = title.posterUrl(),
                releaseInfo = title.year?.toString(),
                markedAtEpochMs = entry.watchedAtEpochMs,
                trackingProviderId = TrackingProviderId.PUBLICMETADB.storageId,
                trackingProviderItemId = title.providerItemId(),
                trackingSourceUrl = PublicMetaDbConfig.WEBSITE_URL,
            )
        } else {
            val season = entry.season ?: return@mapNotNull null
            val episode = entry.episode ?: return@mapNotNull null
            WatchedItem(
                id = contentId,
                type = "series",
                name = title.displayTitle(),
                poster = title.posterUrl(),
                releaseInfo = title.year?.toString(),
                season = season,
                episode = episode,
                videoId = buildPlaybackVideoId(contentId, season, episode),
                markedAtEpochMs = entry.watchedAtEpochMs,
                trackingProviderId = TrackingProviderId.PUBLICMETADB.storageId,
                trackingProviderItemId = title.providerItemId(),
                trackingSourceUrl = PublicMetaDbConfig.WEBSITE_URL,
            )
        }
    }.sortedByDescending(WatchedItem::markedAtEpochMs)

    val watchedKeys: Set<String> = buildSet {
        snapshot.watched.forEach { entry ->
            val ref = PublicMetaDbTitleRef(normalizePublicMetaDbMediaType(entry.mediaType), entry.tmdbId)
            val aliases = aliasesFor(ref)
            if (ref.isMovie) {
                aliases.forEach { alias -> addAll(watchedItemKeys("movie", alias)) }
            } else {
                val season = entry.season ?: return@forEach
                val episode = entry.episode ?: return@forEach
                aliases.forEach { alias -> addAll(watchedItemKeys("series", alias, season, episode)) }
            }
        }
    }

    val showIdSiblings: Map<String, Set<String>> = buildMap {
        media.values.filterNot(PublicMetaDbMedia::isMovie).forEach { item ->
            val aliases = item.aliases().toSet()
            if (aliases.size > 1) aliases.forEach { alias -> put(alias, aliases) }
        }
    }

    fun isWatched(ref: PublicMetaDbTitleRef, season: Int? = null, episode: Int? = null): Boolean =
        snapshot.watched.any { entry ->
            entry.tmdbId == ref.tmdbId &&
                isPublicMetaDbMovieType(entry.mediaType) == ref.isMovie &&
                (ref.isMovie || (entry.season == season && entry.episode == episode))
        }

    fun resumeFor(ref: PublicMetaDbTitleRef, season: Int?, episode: Int?): PublicMetaDbResumePoint? =
        snapshot.resume.firstOrNull { point ->
            point.tmdbId == ref.tmdbId &&
                isPublicMetaDbMovieType(point.mediaType) == ref.isMovie &&
                (ref.isMovie || (point.season == season && point.episode == episode))
        }

    fun resumeFor(entry: WatchProgressEntry): PublicMetaDbResumePoint? {
        val ref = resolve(entry.parentMetaId, entry.parentMetaType) ?: return null
        return resumeFor(ref, entry.seasonNumber, entry.episodeNumber)
    }

    fun rating(ref: PublicMetaDbTitleRef, season: Int?, episode: Int?): PublicMetaDbRatingEntry? =
        snapshot.ratings.firstOrNull { rating ->
            rating.tmdbId == ref.tmdbId &&
                isPublicMetaDbMovieType(rating.mediaType) == ref.isMovie &&
                rating.season == season &&
                rating.episode == episode
        }

    val continueWatching: List<WatchProgressEntry> = buildContinueWatching()

    fun progressEntries(localPlayback: List<WatchProgressEntry>): List<WatchProgressEntry> {
        val local = localPlayback.filterNot { it.isEffectivelyCompleted }
        val localContentIds = local.mapTo(mutableSetOf()) { it.parentMetaId.lowercase() }
        return (local + continueWatching.filter { candidate ->
            candidate.source == PUBLICMETADB_SOURCE_PROGRESS || candidate.parentMetaId.lowercase() !in localContentIds
        }).sortedByDescending(WatchProgressEntry::lastUpdatedEpochMs)
    }

    private fun buildContinueWatching(): List<WatchProgressEntry> {
        val playback = snapshot.resume.mapNotNull { it.toProgressEntry() }
        val playingShows = snapshot.resume
            .filterNot { isPublicMetaDbMovieType(it.mediaType) }
            .mapTo(mutableSetOf()) { it.tmdbId }
        val seeds = snapshot.watched
            .filterNot { isPublicMetaDbMovieType(it.mediaType) }
            .filter { it.season != null && it.episode != null }
            .groupBy(PublicMetaDbWatchedEntry::tmdbId)
            .mapNotNull { (tmdbId, episodes) ->
                if (tmdbId in playingShows) return@mapNotNull null
                val latest = (episodes.filter { (it.season ?: 0) > 0 }.ifEmpty { episodes })
                    .maxWithOrNull(
                        compareBy<PublicMetaDbWatchedEntry> { it.watchedAtEpochMs }
                            .thenBy { it.season ?: 0 }
                            .thenBy { it.episode ?: 0 },
                    ) ?: return@mapNotNull null
                if (snapshot.dismissed[publicMetaDbShowKey(tmdbId)] == latest.watchedAtEpochMs) return@mapNotNull null
                latest
            }
            .sortedByDescending(PublicMetaDbWatchedEntry::watchedAtEpochMs)
            .take(PUBLICMETADB_MAX_NEXT_UP_SEEDS)
            .mapNotNull { latest ->
                val season = latest.season ?: return@mapNotNull null
                val episode = latest.episode ?: return@mapNotNull null
                val title = mediaFor(PublicMetaDbTitleRef(PublicMetaDbMediaType.TV, latest.tmdbId))
                val contentId = title.contentId()
                val runtimeMs = (title.runtimeMinutes ?: 0).toLong() * 60_000L
                WatchProgressEntry(
                    contentType = "series",
                    parentMetaId = contentId,
                    parentMetaType = "series",
                    videoId = buildPlaybackVideoId(contentId, season, episode),
                    title = title.displayTitle(),
                    poster = title.posterUrl(),
                    background = title.backdropUrl(),
                    seasonNumber = season,
                    episodeNumber = episode,
                    lastPositionMs = runtimeMs,
                    durationMs = runtimeMs,
                    lastUpdatedEpochMs = latest.watchedAtEpochMs,
                    isCompleted = true,
                    progressPercent = 100f,
                    source = PUBLICMETADB_SOURCE_PROGRESS,
                    trackingProviderId = TrackingProviderId.PUBLICMETADB.storageId,
                    trackingProviderItemId = title.providerItemId(),
                    trackingSourceUrl = PublicMetaDbConfig.WEBSITE_URL,
                )
            }
        return (playback + seeds).sortedByDescending(WatchProgressEntry::lastUpdatedEpochMs)
    }

    private fun PublicMetaDbResumePoint.toProgressEntry(): WatchProgressEntry? {
        if (progressPercent < PUBLICMETADB_MIN_PLAYBACK_PERCENT || progressPercent >= PUBLICMETADB_MAX_PLAYBACK_PERCENT) return null
        val ref = PublicMetaDbTitleRef(normalizePublicMetaDbMediaType(mediaType), tmdbId)
        val title = mediaFor(ref).let { known ->
            known.copy(
                title = known.title ?: this.title,
                posterPath = known.posterPath ?: posterPath,
            )
        }
        val contentId = title.contentId()
        val isEpisode = !ref.isMovie
        if (isEpisode && (season == null || episode == null)) return null
        val durationMs = runtimeMs.takeIf { it > 0L } ?: (title.runtimeMinutes ?: 0).toLong() * 60_000L
        val position = positionMs.takeIf { it > 0L } ?: (durationMs * (progressPercent / 100.0)).toLong()
        return WatchProgressEntry(
            contentType = if (isEpisode) "series" else "movie",
            parentMetaId = contentId,
            parentMetaType = if (isEpisode) "series" else "movie",
            videoId = if (isEpisode) buildPlaybackVideoId(contentId, season, episode) else contentId,
            title = title.displayTitle(),
            poster = title.posterUrl(),
            background = title.backdropUrl(),
            seasonNumber = if (isEpisode) season else null,
            episodeNumber = if (isEpisode) episode else null,
            lastPositionMs = position,
            durationMs = durationMs,
            lastUpdatedEpochMs = updatedAtEpochMs,
            isCompleted = false,
            progressPercent = progressPercent.toFloat(),
            source = PUBLICMETADB_SOURCE_PLAYBACK,
            trackingProviderId = TrackingProviderId.PUBLICMETADB.storageId,
            trackingProviderItemId = title.providerItemId(),
            trackingSourceUrl = PublicMetaDbConfig.WEBSITE_URL,
        )
    }

    private fun PublicMetaDbListItem.toLibraryItem(listKeys: Set<String>): LibraryItem {
        val ref = PublicMetaDbTitleRef(normalizePublicMetaDbMediaType(mediaType), tmdbId)
        val known = mediaFor(ref)
        val title = known.copy(
            title = known.title ?: this.title,
            year = known.year ?: year,
            posterPath = known.posterPath ?: posterPath,
        )
        return LibraryItem(
            id = title.contentId(),
            type = publicMetaDbContentType(ref.mediaType),
            name = title.displayTitle(),
            poster = title.posterUrl(),
            banner = title.backdropUrl(),
            releaseInfo = title.year?.toString(),
            genres = title.genres,
            posterShape = PosterShape.Poster,
            listKeys = listKeys,
            imdbId = title.imdb,
            tmdbId = tmdbId.toInt(),
            rawPosterUrl = title.posterUrl(),
            trackingProviderId = TrackingProviderId.PUBLICMETADB.storageId,
            trackingProviderItemId = title.providerItemId(),
            trackingSourceUrl = PublicMetaDbConfig.WEBSITE_URL,
            savedAtEpochMs = addedAtEpochMs,
        )
    }

    companion object {
        val Empty = PublicMetaDbProjection(PublicMetaDbSyncSnapshot())
    }
}
