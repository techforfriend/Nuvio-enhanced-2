package com.nuvio.app.features.wetrakr

import com.nuvio.app.features.tracking.TrackingProgressProvider
import com.nuvio.app.features.tracking.TrackingProgressSnapshot
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class WeTrakrTrackingProgressProvider internal constructor(
    private val sync: WeTrakrSyncRepository,
    private val api: WeTrakrApiClient,
    auth: WeTrakrAuthStore,
    activeProfile: StateFlow<Int>,
    private val coroutineScope: CoroutineScope,
    private val ensureAccountLoaded: () -> Unit,
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.WETRAKR
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
            errorMessage = state?.error?.let(::weTrakrSyncErrorMessage),
        )
    }

    override suspend fun removeProgress(entries: Collection<WatchProgressEntry>) {
        if (entries.isEmpty()) return
        val scope = sync.currentScope()
        val playback = removeLocally(scope, entries)
        playback.forEach { item ->
            try {
                api.delete(scope, "/scrobble/playing", cancelBody(item))
            } catch (error: CancellationException) {
                throw error
            } catch (error: WeTrakrAuthException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    override fun applyOptimisticRemoval(entries: Collection<WatchProgressEntry>) {
        if (entries.isEmpty()) return
        val scope = runCatching { sync.currentScope() }.getOrNull() ?: return
        coroutineScope.launch {
            try {
                removeLocally(scope, entries)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    override fun applyOptimisticProgress(entry: WatchProgressEntry) {
        if (entry.source.startsWith("wetrakr")) return
        val scope = runCatching { sync.currentScope() }.getOrNull() ?: return
        val local = entry.copy(source = WETRAKR_SOURCE_LOCAL)
        coroutineScope.launch {
            try {
                sync.upsertLocalPlayback(scope, local)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    override fun isHiddenFromProgress(contentId: String): Boolean = false

    override suspend fun refreshEpisodeProgress(contentId: String, forceRefresh: Boolean) {
        sync.refresh(if (forceRefresh) TrackingRefreshIntent.USER_INITIATED else TrackingRefreshIntent.AUTOMATIC)
    }

    private suspend fun removeLocally(scope: WeTrakrAuthScope, entries: Collection<WatchProgressEntry>): List<WeTrakrPlayback> {
        sync.removeLocalPlayback(scope, entries)
        val projection = sync.currentProjection()
        val playback = entries.mapNotNull(projection::playbackFor).distinct()
        sync.removePlayback(scope, playback)
        val dismissed = entries
            .filter { it.source == WETRAKR_SOURCE_PROGRESS }
            .associate { weTrakrShowKey(it.parentMetaId) to it.lastUpdatedEpochMs }
        sync.dismiss(scope, dismissed)
        return playback
    }

    private fun cancelBody(playback: WeTrakrPlayback) = buildJsonObject {
        val target = WeTrakrTarget(
            isMovie = playback.media.isMovie,
            ids = playback.media.ids,
            title = playback.media.title,
            year = playback.media.year,
            media = playback.media,
        )
        if (playback.media.isMovie) {
            put("movie", target.titleObject())
        } else {
            put("show", target.titleObject())
            putJsonObject("episode") {
                playback.season?.let { put("season", it) }
                playback.number?.let { put("number", it) }
            }
        }
    }
}
