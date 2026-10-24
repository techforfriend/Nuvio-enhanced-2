package com.nuvio.app.features.anilist

import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.simkl.SimklAnimeIdPreference
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watchprogress.WatchProgressEntry

internal const val ANILIST_STATUS_SELECTION_GROUP = "anilist:status"
internal val ANILIST_SEASON_FORMATS = setOf("TV", "TV_SHORT", "ONA")
internal const val ANILIST_SOURCE_PROGRESS = "anilist_progress"
internal const val ANILIST_SOURCE_NEXT = "anilist_next"
internal const val ANILIST_SOURCE_LOCAL = "anilist_local"
private const val ANILIST_PLACEHOLDER_PERCENT = 0.1f

internal data class AniListStatusDefinition(
    val status: String,
    val key: String,
    val title: String,
    val trackingStatus: TrackingListStatus?,
    val isMembershipDestination: Boolean = true,
)

internal val aniListStatusDefinitions = listOf(
    AniListStatusDefinition(AniListListStatus.CURRENT, "anilist:status:current", "Watching", TrackingListStatus.WATCHING),
    AniListStatusDefinition(AniListListStatus.REPEATING, "anilist:status:repeating", "Rewatching", null),
    AniListStatusDefinition(AniListListStatus.PLANNING, "anilist:status:planning", "Planning", TrackingListStatus.PLAN_TO_WATCH),
    AniListStatusDefinition(AniListListStatus.COMPLETED, "anilist:status:completed", "Completed", TrackingListStatus.COMPLETED),
    AniListStatusDefinition(AniListListStatus.PAUSED, "anilist:status:paused", "Paused", TrackingListStatus.ON_HOLD),
    AniListStatusDefinition(AniListListStatus.DROPPED, "anilist:status:dropped", "Dropped", TrackingListStatus.DROPPED),
)

internal fun aniListStatusDefinition(key: String): AniListStatusDefinition? =
    aniListStatusDefinitions.firstOrNull { it.key == key }

internal fun aniListStatusDefinitionFor(status: String?): AniListStatusDefinition? =
    aniListStatusDefinitions.firstOrNull { it.status.equals(status, ignoreCase = true) }

internal fun aniListStatusFor(status: TrackingListStatus): String = when (status) {
    TrackingListStatus.WATCHING -> AniListListStatus.CURRENT
    TrackingListStatus.PLAN_TO_WATCH -> AniListListStatus.PLANNING
    TrackingListStatus.ON_HOLD -> AniListListStatus.PAUSED
    TrackingListStatus.COMPLETED -> AniListListStatus.COMPLETED
    TrackingListStatus.DROPPED -> AniListListStatus.DROPPED
}

internal class AniListProjection(
    val snapshot: AniListSyncSnapshot,
    val preference: SimklAnimeIdPreference,
) {
    private val listEntries = snapshot.entries.filter { it.media != null }.distinctBy(AniListEntry::mediaId)
    val entryByMediaId: Map<Long, AniListEntry> = listEntries.associateBy(AniListEntry::mediaId)
    private val mappingByMediaId: Map<Long, AniListMapping> = listEntries.associate { entry ->
        entry.mediaId to (snapshot.mappings[entry.mediaId] ?: fallbackAniListMapping(entry.media!!))
    }
    val contentIdByMediaId: Map<Long, String> = mappingByMediaId.mapValues { (_, mapping) -> mapping.contentId(preference) }
    val aliasIndex: Map<String, Long> = buildMap {
        mappingByMediaId.forEach { (mediaId, mapping) ->
            mapping.aliases().forEach { alias ->
                val key = alias.lowercase()
                if (!containsKey(key)) put(key, mediaId)
            }
        }
        contentIdByMediaId.forEach { (mediaId, contentId) -> put(contentId.lowercase(), mediaId) }
    }

    private val aliasGroups: Map<String, List<Long>> = buildMap<String, MutableSet<Long>> {
        mappingByMediaId.forEach { (mediaId, mapping) ->
            (mapping.aliases() + listOfNotNull(contentIdByMediaId[mediaId])).forEach { alias ->
                getOrPut(alias.lowercase()) { linkedSetOf() }.add(mediaId)
            }
        }
    }.mapValues { (_, ids) -> ids.sortedWith(compareBy({ startDateKey(it) }, { it })) }

    private val seasonNumberByMediaId: Map<Long, Int?> = buildMap {
        contentIdByMediaId.forEach { (mediaId, contentId) ->
            val media = entryByMediaId[mediaId]?.media ?: return@forEach
            val mapping = mappingByMediaId[mediaId] ?: return@forEach
            val group = aliasGroups[contentId.lowercase()].orEmpty()
            val ordered = group.filter { id ->
                entryByMediaId[id]?.media?.format?.uppercase() in ANILIST_SEASON_FORMATS
            }
            val season = when {
                media.isMovie -> null
                mapping.isImdbContent(contentId) && mapping.seasonResolved -> mapping.tvdbSeason ?: 1
                media.format?.uppercase() in ANILIST_SEASON_FORMATS -> ordered.indexOf(mediaId).takeIf { it >= 0 }?.plus(1)
                else -> null
            }
            put(mediaId, season)
        }
    }

    fun franchiseMediaIds(contentId: String): List<Long> = aliasGroups[contentId.trim().lowercase()].orEmpty()

    fun mediaIdFor(contentId: String): Long? {
        val group = franchiseMediaIds(contentId)
        if (group.size <= 1) return group.firstOrNull() ?: aliasIndex[contentId.trim().lowercase()]
        val entries = group.mapNotNull(entryByMediaId::get)
        return (
            entries.filter { it.status == AniListListStatus.CURRENT || it.status == AniListListStatus.REPEATING }
                .maxByOrNull(AniListEntry::updatedAt)
                ?: entries.filter { it.status == AniListListStatus.PAUSED }.maxByOrNull(AniListEntry::updatedAt)
                ?: entries.maxByOrNull(AniListEntry::updatedAt)
            )?.mediaId
    }

    fun membershipKeys(contentId: String): Set<String> =
        franchiseMediaIds(contentId).mapNotNullTo(linkedSetOf()) { id ->
            aniListStatusDefinitionFor(entryByMediaId[id]?.status)?.key
        }

    fun seasonNumber(mediaId: Long): Int? = seasonNumberByMediaId[mediaId]

    fun mapping(mediaId: Long): AniListMapping? = mappingByMediaId[mediaId]

    val continueWatching: List<WatchProgressEntry> = buildContinueWatching()

    fun progressEntries(localPlayback: List<WatchProgressEntry>): List<WatchProgressEntry> {
        val local = localPlayback.filterNot { it.isEffectivelyCompleted }
        val localContentIds = local.mapTo(mutableSetOf()) { it.parentMetaId.lowercase() }
        return (local + continueWatching.filter { candidate ->
            candidate.source == ANILIST_SOURCE_PROGRESS || candidate.parentMetaId.lowercase() !in localContentIds
        }).sortedByDescending(WatchProgressEntry::lastUpdatedEpochMs)
    }

    val watchedItems: List<WatchedItem> = buildWatchedItems()

    val watchedKeys: Set<String> = buildWatchedKeys()

    val showIdSiblings: Map<String, Set<String>> = buildMap {
        listEntries.filter { it.media?.isMovie == false }.forEach { entry ->
            val aliases = mappingByMediaId[entry.mediaId]?.aliases().orEmpty()
            aliases.forEach { alias -> put(alias, aliases) }
        }
    }

    val libraryItems: List<LibraryItem>
    val librarySections: List<LibrarySection>

    init {
        val sections = aniListStatusDefinitions.mapNotNull { definition ->
            val items = listEntries
                .filter { it.status.equals(definition.status, ignoreCase = true) }
                .groupBy { entry -> contentIdByMediaId[entry.mediaId]?.lowercase() }
                .mapNotNull { (contentId, entries) ->
                    contentId ?: return@mapNotNull null
                    libraryItemFor(entries, definition.key)
                }
                .sortedByDescending(LibraryItem::savedAtEpochMs)
            items.takeIf(List<LibraryItem>::isNotEmpty)?.let {
                LibrarySection(type = definition.key, displayTitle = definition.title, items = it)
            }
        }
        librarySections = sections
        libraryItems = sections.flatMap(LibrarySection::items)
            .sortedByDescending(LibraryItem::savedAtEpochMs)
    }

    fun libraryItem(contentId: String): LibraryItem? {
        val target = mediaIdFor(contentId)?.let(entryByMediaId::get) ?: return null
        val key = aniListStatusDefinitionFor(target.status)?.key ?: return null
        val canonical = contentIdByMediaId[target.mediaId] ?: return null
        val sameSection = franchiseMediaIds(canonical)
            .mapNotNull(entryByMediaId::get)
            .filter { it.status.equals(target.status, ignoreCase = true) }
        return libraryItemFor(sameSection.ifEmpty { listOf(target) }, key)
    }

    private fun libraryItemFor(entries: List<AniListEntry>, listKey: String): LibraryItem? {
        val representative = entries.maxByOrNull(AniListEntry::updatedAt) ?: return null
        val item = representative.toLibraryItem(listKey) ?: return null
        val contentId = contentIdByMediaId[representative.mediaId] ?: return item
        val group = franchiseMediaIds(contentId)
        if (group.size <= 1) return item
        val earliest = group.firstNotNullOfOrNull { id ->
            entryByMediaId[id]?.media?.takeIf { it.format?.uppercase() in ANILIST_SEASON_FORMATS }
        } ?: representative.media
        val seasons = entries.mapNotNull { seasonNumber(it.mediaId) }.distinct().sorted()
        val others = entries.count { seasonNumber(it.mediaId) == null }
        val label = buildString {
            if (seasons.isNotEmpty()) append(seasons.joinToString(", ") { "S$it" })
            if (others > 0) {
                if (isNotEmpty()) append(" +")
                append(if (seasons.isEmpty()) representative.media?.displayTitle().orEmpty() else others.toString())
            }
        }
        val baseTitle = earliest?.displayTitle() ?: item.name
        return item.copy(
            name = if (seasons.isEmpty()) label.ifBlank { item.name } else "$baseTitle · $label",
            savedAtEpochMs = entries.maxOf { entry ->
                (if (entry.createdAt > 0L) entry.createdAt else entry.updatedAt).coerceAtLeast(0L) * 1_000L
            },
        )
    }

    private fun startDateKey(mediaId: Long): Long {
        val date = entryByMediaId[mediaId]?.media?.startDate
        return ((date?.year ?: 9999).toLong() * 10_000L) + ((date?.month ?: 12) * 100L) + (date?.day ?: 31)
    }

    private fun buildContinueWatching(): List<WatchProgressEntry> {
        val visited = mutableSetOf<Long>()
        return listEntries.mapNotNull { entry ->
            if (!entry.status.equals(AniListListStatus.CURRENT, ignoreCase = true)) return@mapNotNull null
            if (!visited.add(entry.mediaId)) return@mapNotNull null
            val media = entry.media ?: return@mapNotNull null
            if (media.status == null || media.status.equals(AniListMediaStatus.NOT_YET_RELEASED, ignoreCase = true)) {
                return@mapNotNull null
            }
            val nextEpisodeToWatch = entry.progress + 1
            if (nextEpisodeToWatch > media.currentEpisodeCount()) return@mapNotNull null
            if (snapshot.dismissed[entry.mediaId] == entry.updatedAt) return@mapNotNull null
            val mapping = mappingByMediaId[entry.mediaId] ?: return@mapNotNull null
            val contentId = contentIdByMediaId[entry.mediaId] ?: return@mapNotNull null
            if (entry.progress > 0 && !media.isMovie) {
                progressEntry(entry, media, mapping, contentId, entry.progress, completed = true)
            } else {
                progressEntry(entry, media, mapping, contentId, nextEpisodeToWatch, completed = false)
            }
        }
    }

    private fun progressEntry(
        entry: AniListEntry,
        media: AniListMedia,
        mapping: AniListMapping,
        contentId: String,
        episode: Int,
        completed: Boolean,
    ): WatchProgressEntry {
        val updatedAt = entry.updatedAt.coerceAtLeast(0L) * 1_000L
        val durationMs = (media.duration ?: 0).toLong() * 60_000L
        if (media.isMovie) {
            return WatchProgressEntry(
                contentType = "movie",
                parentMetaId = contentId,
                parentMetaType = "movie",
                videoId = contentId,
                title = media.displayTitle(),
                poster = media.posterUrl(),
                background = media.bannerImage,
                lastPositionMs = 0L,
                durationMs = durationMs,
                lastUpdatedEpochMs = updatedAt,
                isCompleted = false,
                progressPercent = ANILIST_PLACEHOLDER_PERCENT,
                source = ANILIST_SOURCE_NEXT,
                trackingProviderId = TrackingProviderId.ANILIST.storageId,
                trackingProviderItemId = "anilist:${media.id}",
                trackingSourceUrl = media.siteUrl,
            )
        }
        val (season, mappedEpisode) = mapping.coordinates(contentId, episode)
        return WatchProgressEntry(
            contentType = "series",
            parentMetaId = contentId,
            parentMetaType = "series",
            videoId = aniListVideoId(contentId, season, mappedEpisode),
            title = media.displayTitle(),
            poster = media.posterUrl(),
            background = media.bannerImage,
            seasonNumber = season,
            episodeNumber = mappedEpisode,
            lastPositionMs = if (completed) durationMs else 0L,
            durationMs = durationMs,
            lastUpdatedEpochMs = updatedAt,
            isCompleted = completed,
            progressPercent = if (completed) 100f else ANILIST_PLACEHOLDER_PERCENT,
            source = if (completed) ANILIST_SOURCE_PROGRESS else ANILIST_SOURCE_NEXT,
            trackingProviderId = TrackingProviderId.ANILIST.storageId,
            trackingProviderItemId = "anilist:${media.id}",
            trackingSourceUrl = media.siteUrl,
        )
    }

    private fun buildWatchedItems(): List<WatchedItem> = listEntries
        .filter(AniListEntry::isCountedAsWatched)
        .flatMap { entry ->
            val media = entry.media ?: return@flatMap emptyList()
            val mapping = mappingByMediaId[entry.mediaId] ?: return@flatMap emptyList()
            val preferredId = contentIdByMediaId[entry.mediaId] ?: return@flatMap emptyList()
            val contentId = if (!media.isMovie && mapping.isImdbContent(preferredId) && !mapping.seasonResolved) {
                mapping.kitsu?.let { "kitsu:$it" } ?: mapping.mal?.let { "mal:$it" } ?: "anilist:${mapping.anilist}"
            } else {
                preferredId
            }
            val markedAt = entry.updatedAt.coerceAtLeast(0L) * 1_000L
            if (media.isMovie) {
                listOf(
                    WatchedItem(
                        id = contentId,
                        type = "movie",
                        name = media.displayTitle(),
                        poster = media.posterUrl(),
                        releaseInfo = media.releaseYear()?.toString(),
                        markedAtEpochMs = markedAt,
                        trackingProviderId = TrackingProviderId.ANILIST.storageId,
                        trackingProviderItemId = "anilist:${media.id}",
                        trackingSourceUrl = media.siteUrl,
                    ),
                )
            } else {
                (1..entry.effectiveProgress()).map { episode ->
                    val (season, mappedEpisode) = mapping.coordinates(contentId, episode)
                    WatchedItem(
                        id = contentId,
                        type = "series",
                        name = media.displayTitle(),
                        poster = media.posterUrl(),
                        releaseInfo = media.releaseYear()?.toString(),
                        season = season,
                        episode = mappedEpisode,
                        videoId = aniListVideoId(contentId, season, mappedEpisode),
                        markedAtEpochMs = markedAt,
                        trackingProviderId = TrackingProviderId.ANILIST.storageId,
                        trackingProviderItemId = "anilist:${media.id}",
                        trackingSourceUrl = media.siteUrl,
                    )
                }
            }
        }
        .sortedByDescending(WatchedItem::markedAtEpochMs)

    private fun buildWatchedKeys(): Set<String> {
        val keys = linkedSetOf<String>()
        listEntries.filter(AniListEntry::isCountedAsWatched).forEach { entry ->
            val media = entry.media ?: return@forEach
            val mapping = mappingByMediaId[entry.mediaId] ?: return@forEach
            val aliases = mapping.aliases() + listOfNotNull(contentIdByMediaId[entry.mediaId])
            if (media.isMovie) {
                aliases.forEach { alias -> keys += watchedItemKeys("movie", alias) }
                return@forEach
            }
            val progress = entry.effectiveProgress()
            aliases.forEach { alias ->
                if (mapping.isImdbContent(alias) && !mapping.seasonResolved) return@forEach
                for (episode in 1..progress) {
                    val (season, mappedEpisode) = mapping.coordinates(alias, episode)
                    keys += watchedItemKeys("series", alias, season, mappedEpisode)
                }
            }
        }
        return keys
    }

    private fun AniListEntry.toLibraryItem(listKey: String): LibraryItem? {
        val media = media ?: return null
        val contentId = contentIdByMediaId[mediaId] ?: return null
        val mapping = mappingByMediaId[mediaId]
        return LibraryItem(
            id = contentId,
            type = media.contentType,
            name = media.displayTitle(),
            poster = media.posterUrl(),
            banner = media.bannerImage,
            releaseInfo = media.releaseYear()?.toString(),
            imdbRating = media.averageScore?.takeIf { it > 0 }?.let { score ->
                val tenths = score
                "${tenths / 10}.${tenths % 10}"
            },
            genres = media.genres,
            posterShape = PosterShape.Poster,
            listKeys = setOf(listKey),
            imdbId = mapping?.imdb,
            mediaCategory = "anime",
            rawPosterUrl = media.posterUrl(),
            trackingProviderId = TrackingProviderId.ANILIST.storageId,
            trackingProviderItemId = "anilist:${media.id}",
            trackingSourceUrl = media.siteUrl,
            savedAtEpochMs = (if (createdAt > 0L) createdAt else updatedAt).coerceAtLeast(0L) * 1_000L,
        )
    }

    companion object {
        val Empty = AniListProjection(AniListSyncSnapshot(viewerId = 0L), SimklAnimeIdPreference.IMDB)
    }
}
