package com.nuvio.app.features.anilist

import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingWatchedProvider
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class AniListWatchedSyncAdapter internal constructor(
    private val sync: AniListSyncRepository,
    private val writes: AniListTrackingWrites,
    auth: AniListAuthStore,
    private val activeProfile: StateFlow<Int>,
) : TrackingWatchedProvider {
    override val providerId = TrackingProviderId.ANILIST
    private val changes = combine(sync.projectionState, auth.state, activeProfile) { _, _, _ -> Unit }

    override suspend fun pull(profileId: Int, pageSize: Int): List<WatchedItem> {
        val scope = scope(profileId)
        sync.refresh(TrackingRefreshIntent.AUTOMATIC)
        if (sync.currentScope() != scope) throw CancellationException("AniList account changed")
        val state = sync.state.value
        if (!state.hasLoaded && state.error != null) throw AniListApiException(0, message = "AniList sync unavailable")
        return sync.currentProjection().watchedItems
    }

    override suspend fun pullExtraWatchedKeys(profileId: Int): Set<String> {
        scope(profileId)
        return sync.currentProjection().watchedKeys
    }

    override fun observeExtraWatchedKeys(profileId: Int): Flow<Set<String>> =
        combine(changes, activeProfile) { _, active ->
            if (profileId == active) sync.currentProjection().watchedKeys else emptySet()
        }.distinctUntilChanged()

    override suspend fun push(profileId: Int, items: Collection<WatchedItem>) {
        if (items.isEmpty()) return
        writes.addToHistory(profileId, items.map { TrackingHistoryItem(it.reference(), it.markedAtEpochMs) })
    }

    override suspend fun delete(profileId: Int, items: Collection<WatchedItem>) {
        if (items.isEmpty()) return
        writes.removeFromHistory(profileId, items.map { it.reference() })
    }

    private fun scope(profileId: Int) = sync.currentScope().also {
        if (it.profileId != profileId) throw CancellationException("AniList profile changed")
    }

    private fun WatchedItem.reference() = buildTrackingMediaReference(
        contentType = type,
        parentMetaId = id,
        videoId = videoId,
        title = name,
        releaseInfo = releaseInfo,
        seasonNumber = season,
        episodeNumber = episode,
    )
}
