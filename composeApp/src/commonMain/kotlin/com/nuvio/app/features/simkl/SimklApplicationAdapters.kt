package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProgressProvider
import com.nuvio.app.features.tracking.TrackingProgressSnapshot
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingWatchedProvider
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object SimklWatchedSyncAdapter : TrackingWatchedProvider {
    private val log = Logger.withTag("SimklWatched")
    override val providerId: TrackingProviderId = TrackingProviderId.SIMKL
    override suspend fun pull(profileId: Int, pageSize: Int): List<WatchedItem> {
        if (profileId != ProfileRepository.activeProfileId) return emptyList()
        SimklSyncRepository.refresh(
            intent = TrackingRefreshIntent.AUTOMATIC,
            origin = SimklRefreshOrigin.WATCHED_ITEMS,
        )
        val snapshot = SimklSyncRepository.state.value.snapshot
        val projection = snapshot.toSimklWatchedProjection()
        SimklWatchDiagnostics.logProjection(
            stage = "items-pull",
            snapshot = snapshot,
            projection = projection,
        )
        return projection.items
    }

    override suspend fun pullFullyWatchedSeriesKeys(profileId: Int): Set<String>? {
        return null
    }

    override suspend fun pullExtraWatchedKeys(profileId: Int): Set<String> {
        if (profileId != ProfileRepository.activeProfileId) return emptySet()
        SimklSyncRepository.refresh(
            intent = TrackingRefreshIntent.AUTOMATIC,
            origin = SimklRefreshOrigin.WATCHED_ITEMS,
        )
        val snapshot = SimklSyncRepository.state.value.snapshot
        return snapshot.animeAlternateWatchedKeys() + snapshot.movieAlternateWatchedKeys()
    }

    override fun observeExtraWatchedKeys(profileId: Int): kotlinx.coroutines.flow.Flow<Set<String>> =
        SimklSyncRepository.state
            .map { state ->
                SimklAnimeWatchedFallback.clearOptimisticRemovals()
                state.snapshot.animeAlternateWatchedKeys() + state.snapshot.movieAlternateWatchedKeys()
            }
            .distinctUntilChanged()

    override suspend fun push(profileId: Int, items: Collection<WatchedItem>) {
        if (profileId != ProfileRepository.activeProfileId || items.isEmpty()) return
        val pushableItems = simklHistoryPushItems(items)
        if (pushableItems.isEmpty()) {
            log.i { "Skipped ${items.size} Simkl history items: nothing but whole-series marks" }
            return
        }
        SimklSyncRepository.ensureLoaded()
        val snapshot = SimklSyncRepository.state.value.snapshot
        val historyItems = pushableItems.map { item ->
            TrackingHistoryItem(
                media = snapshot.mediaReference(
                    contentId = item.id,
                    contentType = item.type,
                    title = item.name,
                    releaseInfo = item.releaseInfo,
                    season = item.season,
                    episode = item.episode,
                    videoId = item.videoId,
                    posterUrl = item.poster,
                ),
                watchedAtEpochMs = item.markedAtEpochMs,
            )
        }
        val result = SimklMutationRepository.addToHistory(profileId = profileId, items = historyItems)
        check(result.isComplete) {
            "Simkl could not match ${result.notFoundCount} of ${result.attemptedCount} watched items"
        }
        markExplicitSeriesCompleted(profileId = profileId, items = items, snapshot = snapshot)
    }

    private suspend fun markExplicitSeriesCompleted(
        profileId: Int,
        items: Collection<WatchedItem>,
        snapshot: SimklSyncSnapshot,
    ) {
        val series = simklExplicitlyCompletedSeries(items)
        if (series.isEmpty()) return
        val media = series.map { item ->
            snapshot.mediaReference(
                contentId = item.id,
                contentType = item.type,
                title = item.name,
                releaseInfo = item.releaseInfo,
                posterUrl = item.poster,
            )
        }
        runCatching {
            SimklMutationRepository.moveToList(
                profileId = profileId,
                items = media,
                destination = TrackingListStatus.COMPLETED,
            )
        }.onSuccess { result ->
            if (!result.isComplete) {
                log.w { "Simkl could not move ${result.notFoundCount} of ${result.attemptedCount} series to Completed" }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w(error) { "Moving ${media.size} explicitly watched series to Simkl Completed failed" }
        }
    }

    override suspend fun delete(profileId: Int, items: Collection<WatchedItem>) {
        if (profileId != ProfileRepository.activeProfileId || items.isEmpty()) return
        val episodeItems = items.filter { item -> item.season != null && item.episode != null }
        if (episodeItems.isEmpty()) return
        episodeItems.forEach { item -> item.videoId?.let(SimklAnimeWatchedFallback::markOptimisticallyRemoved) }
        SimklSyncRepository.ensureLoaded()
        val snapshot = SimklSyncRepository.state.value.snapshot
        val media = episodeItems.map { item ->
            snapshot.mediaReference(
                contentId = item.id,
                contentType = item.type,
                title = item.name,
                releaseInfo = item.releaseInfo,
                season = item.season,
                episode = item.episode,
                videoId = item.videoId,
            ).let { ref ->
                val enriched = snapshot.enrichMediaReference(ref)
                enriched.resolveAnimeEpisodeForSimkl()
            }
        }
        val result = SimklMutationRepository.removeFromHistory(profileId = profileId, items = media)
        check(result.isComplete) {
            "Simkl could not match ${result.notFoundCount} of ${result.attemptedCount} watched items"
        }
    }
}

data class SimklProgressUiState(
    val entries: List<WatchProgressEntry> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoadedRemoteProgress: Boolean = false,
    val errorMessage: String? = null,
    val hiddenContentIds: Set<String> = emptySet(),
)

internal fun simklHistoryPushItems(items: Collection<WatchedItem>): List<WatchedItem> =
    items.filterNot(WatchedItem::isWholeSeriesMark)

internal fun simklExplicitlyCompletedSeries(items: Collection<WatchedItem>): List<WatchedItem> {
    val episodeParents = items
        .filter { item -> item.season != null && item.episode != null }
        .mapTo(hashSetOf()) { item -> item.id }
    return items
        .filter { item -> item.isWholeSeriesMark() && item.id in episodeParents }
        .distinctBy { item -> item.id }
}

private fun WatchedItem.isWholeSeriesMark(): Boolean =
    season == null && episode == null && type.trim().lowercase() !in MOVIE_LIKE_WATCHED_TYPES

private val MOVIE_LIKE_WATCHED_TYPES = setOf("movie", "film")

object SimklProgressRepository {
    private val log = Logger.withTag("SimklProgress")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(SimklProgressUiState())
    val uiState: StateFlow<SimklProgressUiState> = _uiState.asStateFlow()
    private val publicationLock = SynchronizedObject()
    private val projectionCache = SimklSnapshotProjectionCache(SimklSyncSnapshot::toSimklProgressEntries)
    private var publishedSyncState: SimklSyncUiState? = null

    init {
        scope.launch {
            SimklSyncRepository.state.collectLatest(::publish)
        }
    }

    fun ensureLoaded() {
        SimklAuthRepository.ensureLoaded()
        SimklSyncRepository.ensureLoaded()
        publish(SimklSyncRepository.state.value)
    }

    suspend fun refresh(intent: TrackingRefreshIntent) {
        SimklSyncRepository.refresh(
            intent = intent,
            origin = SimklRefreshOrigin.PROGRESS,
        )
        publish(SimklSyncRepository.state.value)
    }

    suspend fun removeProgress(entries: Collection<WatchProgressEntry>) {
        val sessionIds = entries.mapNotNullTo(linkedSetOf()) { entry ->
            entry.progressKey
                ?.removePrefix(SIMKL_PLAYBACK_PROGRESS_KEY_PREFIX)
                ?.takeIf { entry.progressKey.startsWith(SIMKL_PLAYBACK_PROGRESS_KEY_PREFIX) }
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        }
        if (sessionIds.isEmpty()) return

        val removed = linkedSetOf<Long>()
        for (sessionId in sessionIds) {
            try {
                SimklApi.client.execute(
                    SimklApiRequest(
                        method = SimklHttpMethod.DELETE,
                        path = "/sync/playback/$sessionId",
                    ),
                )
                removed += sessionId
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val apiError = error as? SimklApiException
                log.w {
                    "Failed to remove Simkl playback: status=${apiError?.status} " +
                        "code=${apiError?.errorCode ?: "transport_failure"}"
                }
            }
        }
        SimklSyncRepository.commitPlaybackRemoval(removed)
    }

    private fun publish(syncState: SimklSyncUiState) {
        synchronized(publicationLock) {
            if (syncState === publishedSyncState || syncState !== SimklSyncRepository.state.value) return
            _uiState.value = SimklProgressUiState(
                entries = projectionCache.get(syncState),
                isLoading = syncState.isLoading,
                hasLoadedRemoteProgress = syncState.hasLoaded && syncState.errorMessage == null,
                errorMessage = syncState.errorMessage,
                hiddenContentIds = syncState.snapshot.hiddenFromContinueWatchingContentIds(),
            )
            publishedSyncState = syncState
        }
    }
}

internal class SimklSnapshotProjectionCache<T : Any>(
    private val project: (SimklSyncSnapshot) -> T,
) {
    private var snapshot: SimklSyncSnapshot? = null
    private var projectionVersion = 0L
    private var projection: T? = null

    fun get(state: SimklSyncUiState): T {
        val current = projection
        if (current != null && snapshot === state.snapshot && projectionVersion == state.projectionVersion) {
            return current
        }
        return project(state.snapshot).also { updated ->
            snapshot = state.snapshot
            projectionVersion = state.projectionVersion
            projection = updated
        }
    }
}

object SimklTrackingProgressProvider : TrackingProgressProvider {
    override val providerId: TrackingProviderId = TrackingProviderId.SIMKL
    override val changes: Flow<Unit> = SimklProgressRepository.uiState.map { Unit }
    override fun showIdSiblings() = SimklSyncRepository.state.value.snapshot.toSimklShowIdSiblings()

    override fun ensureLoaded() = SimklProgressRepository.ensureLoaded()

    override fun onProfileChanged() = SimklProgressRepository.ensureLoaded()

    override suspend fun refresh(force: Boolean, sourceChanged: Boolean) =
        SimklProgressRepository.refresh(simklProgressRefreshIntent)

    override fun snapshot(): TrackingProgressSnapshot {
        val state = SimklProgressRepository.uiState.value
        return TrackingProgressSnapshot(
            entries = state.entries,
            hiddenContentIds = state.hiddenContentIds,
            hasLoadedRemoteProgress = state.hasLoadedRemoteProgress,
            errorMessage = state.errorMessage,
        )
    }

    override suspend fun removeProgress(entries: Collection<WatchProgressEntry>) =
        SimklProgressRepository.removeProgress(entries)

    override fun isHiddenFromProgress(contentId: String): Boolean =
        SimklSyncRepository.state.value.snapshot.isHiddenFromContinueWatching(contentId)

    override fun normalizeParentContentId(parentContentId: String, videoId: String?): String {
        val snapshot = SimklSyncRepository.state.value.snapshot
        val resolvedId = snapshot.resolveCanonicalContentId(parentContentId)
            ?: videoId?.let(snapshot::resolveAnimeVideoCanonicalContentId)
        return resolvedId ?: parentContentId
    }

    override fun nextUpSeedContentId(contentId: String): String =
        SimklSyncRepository.state.value.snapshot.canonicalSeriesContentId(contentId) ?: contentId
}

private const val SIMKL_PLAYBACK_PROGRESS_KEY_PREFIX = "simkl-playback:"
