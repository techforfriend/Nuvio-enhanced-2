package com.nuvio.app.features.anilist

import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.tracking.TrackingProgressProvider
import com.nuvio.app.features.tracking.TrackingProgressSnapshot
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AniListTrackingProgressProvider internal constructor(
    private val sync: AniListSyncRepository,
    auth: AniListAuthStore,
    activeProfile: StateFlow<Int>,
    private val coroutineScope: CoroutineScope,
    private val ensureAccountLoaded: () -> Unit,
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.ANILIST
    override val changes = combine(sync.projectionState, sync.localPlayback, auth.state, activeProfile) { _, _, _, _ -> Unit }
    override val ownsCompletedHistoryProjection = true

    override fun showIdSiblings(): Map<String, Set<String>> = sync.currentProjection().showIdSiblings

    override fun ensureLoaded() = ensureAccountLoaded()

    override suspend fun refresh(force: Boolean, sourceChanged: Boolean) {
        ensureAccountLoaded()
        sync.refresh(if (force || sourceChanged) TrackingRefreshIntent.USER_INITIATED else TrackingRefreshIntent.AUTOMATIC)
    }

    override fun snapshot(): TrackingProgressSnapshot {
        val projection = sync.currentProjection()
        val state = sync.state.value.takeIf { it.scope == runCatching { sync.currentScope() }.getOrNull() }
        return TrackingProgressSnapshot(
            entries = projection.progressEntries(sync.currentLocalPlayback()),
            hasLoadedRemoteProgress = state?.hasLoaded == true,
            errorMessage = state?.error?.let(::aniListSyncErrorMessage),
        )
    }

    override suspend fun removeProgress(entries: Collection<WatchProgressEntry>) {
        if (entries.isEmpty()) return
        val scope = sync.currentScope()
        sync.removeLocalPlayback(scope, entries)
    }

    override fun applyOptimisticRemoval(entries: Collection<WatchProgressEntry>) {
        if (entries.isEmpty()) return
        val scope = runCatching { sync.currentScope() }.getOrNull() ?: return
        coroutineScope.launch {
            try {
                sync.removeLocalPlayback(scope, entries)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    override fun applyOptimisticProgress(entry: WatchProgressEntry) {
        if (entry.source.startsWith("anilist")) return
        val scope = runCatching { sync.currentScope() }.getOrNull() ?: return
        val local = entry.copy(source = ANILIST_SOURCE_LOCAL)
        coroutineScope.launch {
            try {
                sync.upsertLocalPlayback(scope, local)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun prepareNextUpProgressEntries(
        entries: List<WatchProgressEntry>,
        contentId: String,
    ): List<WatchProgressEntry> {
        val needsMapping = entries.any { entry ->
            entry.parentMetaId == contentId && entry.source == ANILIST_SOURCE_PROGRESS && entry.episodeNumber != null
        }
        if (!needsMapping) return entries
        val parts = parseAniListAnimeVideoId(contentId) ?: return entries
        val meta = try {
            MetaDetailsRepository.fetch(type = "series", id = contentId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return entries
        return entries.map { entry ->
            if (entry.parentMetaId != contentId || entry.source != ANILIST_SOURCE_PROGRESS) return@map entry
            val episode = entry.episodeNumber ?: return@map entry
            val expectedVideoId = "${parts.prefix}:${parts.id}:$episode"
            val video = meta.videos.firstOrNull { it.id.equals(expectedVideoId, ignoreCase = true) }
                ?: return@map entry
            val season = video.season ?: return@map entry
            val mappedEpisode = video.episode ?: return@map entry
            if (season == entry.seasonNumber && mappedEpisode == entry.episodeNumber) return@map entry
            entry.copy(
                seasonNumber = season,
                episodeNumber = mappedEpisode,
                videoId = buildPlaybackVideoId(
                    parentMetaId = entry.parentMetaId,
                    seasonNumber = season,
                    episodeNumber = mappedEpisode,
                    fallbackVideoId = video.id,
                ),
                episodeTitle = video.title.takeIf(String::isNotBlank) ?: entry.episodeTitle,
            )
        }
    }

    override fun isHiddenFromProgress(contentId: String): Boolean = false

    override suspend fun refreshEpisodeProgress(contentId: String, forceRefresh: Boolean) {
        sync.refresh(if (forceRefresh) TrackingRefreshIntent.USER_INITIATED else TrackingRefreshIntent.AUTOMATIC)
    }
}

internal fun aniListSyncErrorMessage(error: AniListSyncError): String = when (error) {
    AniListSyncError.UNAVAILABLE -> "Could not sync with AniList. Please try again."
    AniListSyncError.RATE_LIMIT -> "AniList's request limit has been reached. Syncing will resume shortly."
    AniListSyncError.AUTHORIZATION_REVOKED -> "Your AniList connection has expired or been revoked. Connect again to resume syncing."
}
