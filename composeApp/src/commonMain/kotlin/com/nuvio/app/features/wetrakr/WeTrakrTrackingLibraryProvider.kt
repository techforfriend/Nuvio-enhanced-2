package com.nuvio.app.features.wetrakr

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.tracking.TrackingLibraryProvider
import com.nuvio.app.features.tracking.TrackingLibrarySnapshot
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.app.features.tracking.TrackingMembershipRemovalImpact
import com.nuvio.app.features.tracking.TrackingMembershipResolution
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class WeTrakrTrackingLibraryProvider internal constructor(
    private val sync: WeTrakrSyncRepository,
    private val writes: WeTrakrTrackingWrites,
    private val ensureAccountLoaded: () -> Unit,
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.WETRAKR
    override val changes: Flow<Unit> = combine(sync.projectionState, sync.state) { _, _ -> Unit }
    override val connectionRefreshIntent = TrackingRefreshIntent.AUTOMATIC

    override fun ensureLoaded() = ensureAccountLoaded()

    override fun prepare() {
        sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = sync.refresh(intent)

    override fun snapshot(): TrackingLibrarySnapshot {
        val projection = sync.currentProjection()
        val state = sync.state.value.takeIf { it.scope == runCatching { sync.currentScope() }.getOrNull() }
        return TrackingLibrarySnapshot(
            items = projection.libraryItems,
            sections = projection.librarySections,
            tabs = weTrakrStatusDefinitions.map { definition ->
                TrackingLibraryTab(
                    key = definition.key,
                    title = definition.title,
                    providerId = TrackingProviderId.WETRAKR,
                    kind = if (definition.status == WeTrakrStatus.PLANNING) {
                        TrackingLibraryTabKind.WATCHLIST
                    } else {
                        TrackingLibraryTabKind.STATUS
                    },
                    selectionGroup = WETRAKR_STATUS_SELECTION_GROUP,
                    supportedContentTypes = definition.contentTypes,
                    isMembershipDestination = definition.isMembershipDestination,
                )
            },
            hasLoaded = state?.hasLoaded == true,
            isLoading = state?.isLoading == true,
            errorMessage = state?.error?.let(::weTrakrSyncErrorMessage),
        )
    }

    override fun contains(contentId: String, contentType: String?): Boolean =
        sync.currentProjection().entryFor(contentId, contentType) != null

    override fun find(contentId: String): LibraryItem? = sync.currentProjection().libraryItem(contentId)

    override suspend fun membership(item: LibraryItem): Map<String, Boolean> {
        val keys = sync.currentProjection().membershipKeys(item.id, item.type)
        return weTrakrStatusDefinitions.associate { definition -> definition.key to (definition.key in keys) }
    }

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> =
        currentMembership.mapValues { false }.toMutableMap().apply {
            if (currentMembership.values.none { it }) {
                this[weTrakrStatusDefinitions.single { it.status == WeTrakrStatus.PLANNING }.key] = true
            }
        }

    override fun membershipRemovalConfirmation(
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
    ): TrackingMembershipRemovalConfirmation? {
        val currentKeys = sync.currentProjection().membershipKeys(item.id, item.type)
        val desiredKeys = desiredKeys(desiredMembership)
        if ((desiredKeys - currentKeys).isNotEmpty()) return null
        val removedKeys = currentKeys - desiredKeys
        val watchedKey = weTrakrStatusDefinitions.single { it.status == WeTrakrStatus.WATCHED }.key
        if (watchedKey !in removedKeys) return null
        return TrackingMembershipRemovalConfirmation(
            providerId = TrackingProviderId.WETRAKR,
            impacts = setOf(TrackingMembershipRemovalImpact.WATCHED_HISTORY),
        )
    }

    override suspend fun applyMembership(
        profileId: Int,
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
        destructiveRemovalConfirmed: Boolean,
    ): TrackingMembershipResolution? {
        val scope = sync.currentScope()
        if (scope.profileId != profileId) throw CancellationException("WeTrakr profile changed")
        val projection = sync.currentProjection()
        val currentKeys = projection.membershipKeys(item.id, item.type)
        val desiredKeys = desiredKeys(desiredMembership)
        val addedKeys = desiredKeys - currentKeys
        require(addedKeys.size <= 1) { "A WeTrakr title can be in only one list" }
        val target = target(item) ?: throw IllegalStateException("This title could not be found on WeTrakr")
        val added = addedKeys.singleOrNull()?.let(::weTrakrStatusDefinition)
        if (added != null) {
            val requestedStatus = added.status
            val resolvedStatus = when {
                target.isMovie && requestedStatus !in WeTrakrStatus.movieStatuses -> WeTrakrStatus.PLANNING
                !target.isMovie && requestedStatus !in WeTrakrStatus.writableShowStatuses -> WeTrakrStatus.WATCHING
                else -> requestedStatus
            }
            if (!writes.setStatus(scope, target, resolvedStatus)) {
                throw IllegalStateException("This title could not be found on WeTrakr")
            }
            sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
            val resolvedKey = weTrakrStatusDefinitionFor(resolvedStatus)?.key ?: added.key
            return TrackingMembershipResolution(
                providerId = TrackingProviderId.WETRAKR,
                requestedListKey = added.key,
                resolvedListKey = resolvedKey,
            )
        }
        val removedKeys = currentKeys - desiredKeys
        if (removedKeys.isEmpty()) return null
        val removal = membershipRemovalConfirmation(item, desiredMembership)
        require(removal == null || destructiveRemovalConfirmed) {
            "Removing this title from WeTrakr Watched would also clear its watch history"
        }
        removedKeys.mapNotNull(::weTrakrStatusDefinition).forEach { definition ->
            if (definition.status == WeTrakrStatus.WAITING) return@forEach
            writes.clearStatus(scope, target, definition.status, removeAllPlays = true)
        }
        sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
        return null
    }

    private fun target(item: LibraryItem): WeTrakrTarget? {
        val known = sync.currentProjection().entryFor(item.id, item.type)?.media
        if (known != null) {
            return WeTrakrTarget(
                isMovie = known.isMovie,
                ids = known.ids,
                title = known.title,
                year = known.year,
                media = known,
            )
        }
        return writes.resolve(
            buildTrackingMediaReference(
                contentType = if (weTrakrTypeMatches(true, item.type)) "movie" else "series",
                parentMetaId = item.imdbId?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: item.id,
                title = item.name,
                releaseInfo = item.releaseInfo,
            ).let { reference ->
                if (reference.ids.tmdb == null && item.tmdbId != null) {
                    reference.copy(ids = reference.ids.copy(tmdb = item.tmdbId.toLong()))
                } else {
                    reference
                }
            },
        )
    }

    private fun desiredKeys(desiredMembership: Map<String, Boolean>): Set<String> =
        weTrakrStatusDefinitions.filter { desiredMembership[it.key] == true }.mapTo(linkedSetOf()) { it.key }
}
