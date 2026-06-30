package com.nuvio.app.features.watching.application

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.episodePlaybackId
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val SERIES_RECONCILIATION_DEBOUNCE_MS = 500L
private const val SERIES_RECONCILIATION_BATCH_LIMIT = 24
private const val SERIES_RECONCILIATION_CONCURRENCY = 3

private data class SeriesWatchedKey(
    val profileId: Int,
    val type: String,
    val id: String,
)

private data class SeriesReconciliationCandidate(
    val key: SeriesWatchedKey,
    val signature: String,
    val latestUpdatedAt: Long,
)

object SeriesWatchedReconciliationService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("SeriesWatchedReconciliation")
    private val lastReconciledSignatures = linkedMapOf<SeriesWatchedKey, String>()
    private var observeJob: Job? = null

    @OptIn(FlowPreview::class)
    fun startObserving() {
        if (observeJob?.isActive == true) return

        observeJob = scope.launch {
            combine(
                WatchedRepository.uiState,
                WatchProgressRepository.uiState,
            ) { watchedState, progressState ->
                if (!watchedState.isLoaded || !progressState.hasLoadedRemoteProgress) {
                    emptyList()
                } else {
                    buildReconciliationCandidates(
                        watchedItems = watchedState.items,
                        progressState = progressState,
                    )
                }
            }
                .distinctUntilChanged()
                .debounce(SERIES_RECONCILIATION_DEBOUNCE_MS)
                .collectLatest { candidates ->
                    reconcile(candidates)
                }
        }
    }

    fun clear() {
        observeJob?.cancel()
        observeJob = null
        lastReconciledSignatures.clear()
    }

    private suspend fun reconcile(candidates: List<SeriesReconciliationCandidate>) {
        if (candidates.isEmpty()) return
        val activeProfileId = ProfileRepository.activeProfileId
        val pending = candidates
            .filter { candidate -> lastReconciledSignatures[candidate.key] != candidate.signature }
            .sortedByDescending(SeriesReconciliationCandidate::latestUpdatedAt)
            .take(SERIES_RECONCILIATION_BATCH_LIMIT)
        if (pending.isEmpty()) return

        val todayIsoDate = CurrentDateProvider.todayIsoDate()
        val progressByVideoId = WatchProgressRepository.uiState.value.byVideoId
        val semaphore = Semaphore(SERIES_RECONCILIATION_CONCURRENCY)

        coroutineScope {
            pending.map { candidate ->
                async {
                    semaphore.withPermit {
                        reconcileCandidate(
                            candidate = candidate,
                            activeProfileId = activeProfileId,
                            todayIsoDate = todayIsoDate,
                            progressByVideoId = progressByVideoId,
                        )
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun reconcileCandidate(
        candidate: SeriesReconciliationCandidate,
        activeProfileId: Int,
        todayIsoDate: String,
        progressByVideoId: Map<String, WatchProgressEntry>,
    ) {
        if (ProfileRepository.activeProfileId != activeProfileId) return

        val meta = try {
            MetaDetailsRepository.fetch(
                type = candidate.key.type,
                id = candidate.key.id,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log.d { "Skipping series watched reconciliation for ${candidate.key.id}: ${error.message}" }
            null
        } ?: return

        if (ProfileRepository.activeProfileId != activeProfileId) return

        WatchedRepository.reconcileSeriesWatchedState(
            meta = meta,
            todayIsoDate = todayIsoDate,
            isEpisodeCompleted = { episode ->
                progressByVideoId[meta.episodePlaybackId(episode)]?.isEffectivelyCompleted == true
            },
        )
        lastReconciledSignatures[candidate.key] = candidate.signature
    }

    private fun buildReconciliationCandidates(
        watchedItems: List<WatchedItem>,
        progressState: WatchProgressUiState,
    ): List<SeriesReconciliationCandidate> {
        val groupedWatched = watchedItems
            .filter { item -> item.type.isSeriesLikeType() }
            .groupBy { item ->
                SeriesWatchedKey(
                    profileId = ProfileRepository.activeProfileId,
                    type = item.type,
                    id = item.id,
                )
            }
        val groupedProgress = progressState.entries
            .filter { entry -> entry.parentMetaType.isSeriesLikeType() && entry.isEpisode }
            .groupBy { entry ->
                SeriesWatchedKey(
                    profileId = ProfileRepository.activeProfileId,
                    type = entry.parentMetaType,
                    id = entry.parentMetaId,
                )
            }

        return (groupedWatched.keys + groupedProgress.keys)
            .mapNotNull { key ->
                val watched = groupedWatched[key].orEmpty()
                val progress = groupedProgress[key].orEmpty()
                val hasSeriesMarker = watched.any { item -> item.season == null && item.episode == null }
                val watchedEpisodes = watched
                    .filter { item -> item.season != null && item.episode != null }
                    .sortedWith(compareBy<WatchedItem> { it.season ?: -1 }.thenBy { it.episode ?: -1 })
                val completedProgress = progress
                    .filter(WatchProgressEntry::isEffectivelyCompleted)
                    .sortedWith(compareBy<WatchProgressEntry> { it.seasonNumber ?: -1 }.thenBy { it.episodeNumber ?: -1 })

                if (!hasSeriesMarker && watchedEpisodes.isEmpty() && completedProgress.isEmpty()) {
                    return@mapNotNull null
                }

                SeriesReconciliationCandidate(
                    key = key,
                    latestUpdatedAt = maxOf(
                        watched.maxOfOrNull(WatchedItem::markedAtEpochMs) ?: 0L,
                        progress.maxOfOrNull(WatchProgressEntry::lastUpdatedEpochMs) ?: 0L,
                    ),
                    signature = buildString {
                        append("series=")
                        append(hasSeriesMarker)
                        append("|watched=")
                        watchedEpisodes.forEach { item ->
                            append(item.season)
                            append(":")
                            append(item.episode)
                            append(":")
                            append(item.markedAtEpochMs)
                            append(",")
                        }
                        append("|progress=")
                        completedProgress.forEach { entry ->
                            append(entry.seasonNumber)
                            append(":")
                            append(entry.episodeNumber)
                            append(":")
                            append(entry.lastUpdatedEpochMs)
                            append(",")
                        }
                    },
                )
            }
    }
}

private fun String.isSeriesLikeType(): Boolean =
    trim().lowercase() in setOf("series", "show", "tv", "tvshow")
