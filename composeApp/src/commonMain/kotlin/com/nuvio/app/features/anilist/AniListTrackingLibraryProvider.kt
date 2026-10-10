package com.nuvio.app.features.anilist

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

class AniListTrackingLibraryProvider internal constructor(
    private val sync: AniListSyncRepository,
    private val writes: AniListTrackingWrites,
    private val ensureAccountLoaded: () -> Unit,
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.ANILIST
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
            tabs = aniListStatusDefinitions.map { definition ->
                TrackingLibraryTab(
                    key = definition.key,
                    title = definition.title,
                    providerId = TrackingProviderId.ANILIST,
                    kind = if (definition.status == AniListListStatus.PLANNING) {
                        TrackingLibraryTabKind.WATCHLIST
                    } else {
                        TrackingLibraryTabKind.STATUS
                    },
                    selectionGroup = ANILIST_STATUS_SELECTION_GROUP,
                    supportedContentTypes = setOf("movie", "series", "anime"),
                    isMembershipDestination = definition.isMembershipDestination,
                )
            },
            hasLoaded = state?.hasLoaded == true,
            isLoading = state?.isLoading == true,
            errorMessage = state?.error?.let(::aniListSyncErrorMessage),
        )
    }

    override fun contains(contentId: String, contentType: String?): Boolean {
        val projection = sync.currentProjection()
        val mediaId = projection.mediaIdFor(contentId) ?: return false
        val media = projection.entryByMediaId[mediaId]?.media ?: return false
        return contentType == null || contentType.equals(media.contentType, ignoreCase = true) ||
            contentType.equals("anime", ignoreCase = true)
    }

    override fun find(contentId: String): LibraryItem? = sync.currentProjection().libraryItem(contentId)

    override suspend fun membership(item: LibraryItem): Map<String, Boolean> {
        val keys = sync.currentProjection().membershipKeys(item.id)
        return aniListStatusDefinitions.associate { definition -> definition.key to (definition.key in keys) }
    }

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> =
        currentMembership.mapValues { false }.toMutableMap().apply {
            if (currentMembership.values.none { it }) {
                this[aniListStatusDefinitions.single { it.status == AniListListStatus.PLANNING }.key] = true
            }
        }

    override fun membershipRemovalConfirmation(
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
    ): TrackingMembershipRemovalConfirmation? {
        val projection = sync.currentProjection()
        val currentKeys = projection.membershipKeys(item.id)
        val desiredKeys = desiredKeys(desiredMembership)
        if ((desiredKeys - currentKeys).isNotEmpty()) return null
        val removedKeys = currentKeys - desiredKeys
        if (removedKeys.isEmpty()) return null
        val affected = removedEntries(projection, item.id, removedKeys)
        if (affected.none { it.progress > 0 || it.score > 0.0 }) return null
        return TrackingMembershipRemovalConfirmation(
            providerId = TrackingProviderId.ANILIST,
            impacts = setOf(TrackingMembershipRemovalImpact.WATCHED_HISTORY, TrackingMembershipRemovalImpact.RATING),
        )
    }

    override suspend fun applyMembership(
        profileId: Int,
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
        destructiveRemovalConfirmed: Boolean,
    ): TrackingMembershipResolution? {
        val scope = sync.currentScope()
        if (scope.profileId != profileId) throw CancellationException("AniList profile changed")
        val projection = sync.currentProjection()
        val currentKeys = projection.membershipKeys(item.id)
        val desiredKeys = desiredKeys(desiredMembership)
        val addedKeys = desiredKeys - currentKeys
        require(addedKeys.size <= 1) { "An AniList entry can have only one list status" }
        val added = addedKeys.singleOrNull()?.let(::aniListStatusDefinition)
        if (added != null) {
            val mediaId = projection.mediaIdFor(item.id) ?: writes.resolve(
                buildTrackingMediaReference(
                    contentType = item.type,
                    parentMetaId = item.id,
                    title = item.name,
                    releaseInfo = item.releaseInfo,
                ),
            )?.mediaId ?: throw IllegalStateException("This title could not be found on AniList")
            writes.setStatus(scope, mediaId, added.status)
            return null
        }
        val removedKeys = currentKeys - desiredKeys
        if (removedKeys.isEmpty()) return null
        val removal = membershipRemovalConfirmation(item, desiredMembership)
        require(removal == null || destructiveRemovalConfirmed) {
            "Removing this title from AniList would also clear its progress and score"
        }
        removedEntries(projection, item.id, removedKeys).forEach { entry ->
            writes.deleteEntry(scope, entry.mediaId)
        }
        return null
    }

    private fun desiredKeys(desiredMembership: Map<String, Boolean>): Set<String> =
        aniListStatusDefinitions.filter { desiredMembership[it.key] == true }.mapTo(linkedSetOf()) { it.key }

    private fun removedEntries(projection: AniListProjection, contentId: String, removedKeys: Set<String>): List<AniListEntry> =
        projection.franchiseMediaIds(contentId)
            .mapNotNull(projection.entryByMediaId::get)
            .filter { entry -> aniListStatusDefinitionFor(entry.status)?.key in removedKeys }
}
