package com.nuvio.app.features.publicmetadb

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.LibraryListPrivacy
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingLibraryProvider
import com.nuvio.app.features.tracking.TrackingLibrarySnapshot
import com.nuvio.app.features.tracking.TrackingListManagementCapabilities
import com.nuvio.app.features.tracking.TrackingListManager
import com.nuvio.app.features.tracking.TrackingMembershipResolution
import com.nuvio.app.features.tracking.TrackingProgressProvider
import com.nuvio.app.features.tracking.TrackingProgressSnapshot
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingWatchedProvider
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.trakt.TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL
import com.nuvio.app.features.trakt.normalizeTraktContinueWatchingDaysCap
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object PublicMetaDbTracker : TrackingAuthProvider {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeProfile = MutableStateFlow(ProfileRepository.activeProfileId)
    internal val store = PublicMetaDbAuthStore(PlatformPublicMetaDbAuthPersistence, activeProfile.value)
    internal val api = PublicMetaDbApiClient(store)
    val sync = PublicMetaDbSyncRepository(PlatformPublicMetaDbSyncStorage, store, api, activeProfile, coroutineScope)
    internal val resolver = PublicMetaDbIdResolver(api, sync)
    val writes = PublicMetaDbTrackingWrites(sync, api, resolver)
    val progressProvider = PublicMetaDbTrackingProgressProvider(sync, writes, store, activeProfile, coroutineScope, ::ensureLoaded)
    val watchedProvider = PublicMetaDbWatchedSyncAdapter(sync, writes, store, activeProfile)
    val libraryProvider = PublicMetaDbTrackingLibraryProvider(sync, api, writes, ::ensureLoaded)
    internal val ratingProvider = PublicMetaDbRatingsProvider(sync, api, writes)
    val authState: StateFlow<PublicMetaDbAuthState> = store.state
    private val authenticated = MutableStateFlow(store.state.value.isAuthenticated)
    override val isAuthenticated: StateFlow<Boolean> = authenticated.asStateFlow()
    override val accountGeneration: Long get() = store.scope().generation
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.PUBLICMETADB,
        "PublicMetaDB",
        setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE,
            TrackingCapability.RATINGS,
        ),
    )

    init {
        coroutineScope.launch {
            store.state.collectLatest { state ->
                authenticated.value = state.scope.profileId == activeProfile.value && state.isAuthenticated
            }
        }
    }

    fun register() {
        if (TrackingProviderRegistry.authProvider(providerId) === this) return
        TrackingProviderRegistry.register(this)
        TrackingProviderRegistry.registerHistoryWriter(writes)
        TrackingProviderRegistry.registerListWriter(writes)
        TrackingProviderRegistry.registerScrobbler(writes)
        TrackingProviderRegistry.registerProgressProvider(progressProvider)
        TrackingProviderRegistry.registerWatchedProvider(watchedProvider)
        TrackingProviderRegistry.registerLibraryProvider(libraryProvider)
        TrackingProviderRegistry.registerRatingProvider(ratingProvider)
    }

    suspend fun connect(apiKey: String): Boolean {
        ensureLoaded()
        val scope = store.scope()
        val key = apiKey.trim()
        if (key.isEmpty()) {
            store.setError(PublicMetaDbAuthError.MISSING_KEY, scope)
            return false
        }
        store.setBusy(true, scope)
        try {
            val result = api.verify(key)
            if (!store.isCurrent(scope)) return false
            val error = when (result) {
                PublicMetaDbKeyCheck.VALID -> null
                PublicMetaDbKeyCheck.INVALID -> PublicMetaDbAuthError.INVALID_KEY
                PublicMetaDbKeyCheck.RATE_LIMITED -> PublicMetaDbAuthError.RATE_LIMITED
                PublicMetaDbKeyCheck.BLOCKED -> PublicMetaDbAuthError.BLOCKED
                PublicMetaDbKeyCheck.FAILED -> PublicMetaDbAuthError.REQUEST_FAILED
            }
            if (error != null) {
                store.setError(error, scope)
                return false
            }
            if (!store.authorize(key, kotlin.time.Clock.System.now().toEpochMilliseconds(), scope)) return false
            authenticated.value = store.state.value.isAuthenticated
            sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            store.setError(PublicMetaDbAuthError.REQUEST_FAILED, scope)
            return false
        } finally {
            store.setBusy(false, store.scope())
        }
    }

    fun clearError() {
        store.setError(null, store.scope())
    }

    fun disconnect() {
        store.clearAuth(store.scope())
        resolver.clear()
        authenticated.value = false
    }

    fun syncNow() {
        coroutineScope.launch {
            try {
                sync.refresh(TrackingRefreshIntent.USER_INITIATED)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    override fun ensureLoaded() {
        if (activeProfile.value != ProfileRepository.activeProfileId) onProfileChanged()
        authenticated.value = store.state.value.isAuthenticated
    }

    override fun onProfileChanged() {
        activeProfile.value = ProfileRepository.activeProfileId
        store.selectProfile(activeProfile.value)
        resolver.clear()
        authenticated.value = store.state.value.isAuthenticated
    }

    override fun clearLocalState() {
        store.clearAllProfiles()
        PlatformPublicMetaDbSyncStorage.clearAll()
        resolver.clear()
        authenticated.value = false
    }

    override fun removeStoredProfile(profileId: Int) {
        store.removeProfile(profileId)
        val scope = store.scope()
        coroutineScope.launch {
            try {
                PlatformPublicMetaDbSyncStorage.remove(profileId) { store.checkScope(scope) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }
}

class PublicMetaDbTrackingProgressProvider internal constructor(
    private val sync: PublicMetaDbSyncRepository,
    private val writes: PublicMetaDbTrackingWrites,
    auth: PublicMetaDbAuthStore,
    activeProfile: StateFlow<Int>,
    private val coroutineScope: CoroutineScope,
    private val ensureAccountLoaded: () -> Unit,
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.PUBLICMETADB
    override val changes = combine(sync.projectionState, sync.localPlayback, auth.state, activeProfile) { _, _, _, _ -> Unit }
    override val ownsCompletedHistoryProjection = true

    override fun showIdSiblings(): Map<String, Set<String>> = sync.currentProjection().showIdSiblings

    override fun continueWatchingCutoffEpochMs(daysCap: Int, nowEpochMs: Long): Long? {
        val normalizedDaysCap = normalizeTraktContinueWatchingDaysCap(daysCap)
        if (normalizedDaysCap == TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL) return null
        return nowEpochMs - normalizedDaysCap.toLong() * MILLIS_PER_DAY
    }

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
            errorMessage = state?.error?.let(::publicMetaDbSyncErrorMessage),
        )
    }

    override suspend fun removeProgress(entries: Collection<WatchProgressEntry>) {
        if (entries.isEmpty()) return
        val scope = sync.currentScope()
        val points = removeLocally(scope, entries)
        points.forEach { point ->
            try {
                writes.deleteResumeById(scope, point)
            } catch (error: CancellationException) {
                throw error
            } catch (error: PublicMetaDbAuthException) {
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
        if (entry.source.startsWith("publicmetadb")) return
        val scope = runCatching { sync.currentScope() }.getOrNull() ?: return
        val local = entry.copy(source = PUBLICMETADB_SOURCE_LOCAL)
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

    private suspend fun removeLocally(
        scope: PublicMetaDbAuthScope,
        entries: Collection<WatchProgressEntry>,
    ): List<PublicMetaDbResumePoint> {
        sync.removeLocalPlayback(scope, entries)
        val projection = sync.currentProjection()
        val points = entries.mapNotNull { entry -> projection.resumeFor(entry) }.distinct()
        points.forEach { point -> sync.applyResume(scope, null) { it == point } }
        val dismissed = entries
            .filter { it.source == PUBLICMETADB_SOURCE_PROGRESS }
            .mapNotNull { entry ->
                projection.resolve(entry.parentMetaId, "series")?.let { ref -> publicMetaDbShowKey(ref.tmdbId) to entry.lastUpdatedEpochMs }
            }
            .toMap()
        sync.dismiss(scope, dismissed)
        return points
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
    }
}

class PublicMetaDbTrackingLibraryProvider internal constructor(
    private val sync: PublicMetaDbSyncRepository,
    private val api: PublicMetaDbApiClient,
    private val writes: PublicMetaDbTrackingWrites,
    private val ensureAccountLoaded: () -> Unit,
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.PUBLICMETADB
    override val changes: Flow<Unit> = combine(sync.projectionState, sync.state) { _, _ -> Unit }
    override val connectionRefreshIntent = TrackingRefreshIntent.AUTOMATIC
    override val listManager: TrackingListManager = PublicMetaDbListManager(sync, api)

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
            tabs = projection.tabs,
            hasLoaded = state?.hasLoaded == true,
            isLoading = state?.isLoading == true,
            errorMessage = state?.error?.let(::publicMetaDbSyncErrorMessage),
        )
    }

    override fun contains(contentId: String, contentType: String?): Boolean {
        val projection = sync.currentProjection()
        val ref = projection.resolve(contentId, contentType) ?: return false
        return projection.membershipKeys(ref).isNotEmpty()
    }

    override fun find(contentId: String): LibraryItem? = sync.currentProjection().libraryItem(contentId)

    override suspend fun membership(item: LibraryItem): Map<String, Boolean> {
        val projection = sync.currentProjection()
        val ref = projection.resolve(item.id, item.type)
            ?: item.tmdbId?.let { PublicMetaDbTitleRef(normalizePublicMetaDbMediaType(item.type), it.toLong()) }
        val keys = ref?.let(projection::membershipKeys).orEmpty()
        return projection.tabs.associate { tab -> tab.key to (tab.key in keys) }
    }

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> {
        val key = sync.currentProjection().watchlistKey
        return currentMembership.toMutableMap().apply {
            this[key] = currentMembership[key] != true
        }
    }

    override suspend fun applyMembership(
        profileId: Int,
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
        destructiveRemovalConfirmed: Boolean,
    ): TrackingMembershipResolution? {
        val scope = sync.currentScope()
        if (scope.profileId != profileId) throw CancellationException("PublicMetaDB profile changed")
        val target = target(item) ?: throw IllegalStateException("This title could not be found on PublicMetaDB")
        val projection = sync.currentProjection()
        val currentKeys = projection.membershipKeys(target.ref)
        desiredMembership.forEach { (key, desired) ->
            val isMember = key in currentKeys
            when {
                desired && !isMember -> {
                    val listId = publicMetaDbListId(key)
                        ?: if (key == PUBLICMETADB_PENDING_WATCHLIST_KEY) writes.ensureWatchlist(scope).id else null
                    if (listId != null) writes.addToList(scope, listId, target)
                }
                !desired && isMember -> publicMetaDbListId(key)?.let { listId -> writes.removeFromList(scope, listId, target) }
            }
        }
        sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
        return null
    }

    private suspend fun target(item: LibraryItem): PublicMetaDbTarget? {
        val projection = sync.currentProjection()
        val ref = projection.resolve(item.id, item.type)
            ?: item.tmdbId?.let { PublicMetaDbTitleRef(normalizePublicMetaDbMediaType(item.type), it.toLong()) }
        if (ref != null) {
            val media = projection.mediaFor(ref)
            return PublicMetaDbTarget(
                tmdbId = ref.tmdbId,
                isMovie = ref.isMovie,
                imdb = media.imdb ?: item.imdbId?.takeIf { it.startsWith("tt", ignoreCase = true) },
                title = media.title ?: item.name,
                year = media.year ?: item.releaseInfo?.take(4)?.toIntOrNull(),
            )
        }
        return writes.resolve(
            buildTrackingMediaReference(
                contentType = if (publicMetaDbTypeMatches(PublicMetaDbMediaType.MOVIE, item.type)) "movie" else "series",
                parentMetaId = item.imdbId?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: item.id,
                title = item.name,
                releaseInfo = item.releaseInfo,
            ),
        )?.copy(season = null, episode = null)
    }
}

internal class PublicMetaDbListManager(
    private val sync: PublicMetaDbSyncRepository,
    private val api: PublicMetaDbApiClient,
) : TrackingListManager {
    override val capabilities = TrackingListManagementCapabilities(
        privacyOptions = listOf(LibraryListPrivacy.PRIVATE, LibraryListPrivacy.PUBLIC),
        supportsDescription = true,
    )

    override suspend fun createList(name: String, description: String?, privacy: LibraryListPrivacy) {
        val scope = sync.currentScope()
        val response = api.post(
            scope,
            "/api/external/lists",
            buildJsonObject {
                put("name", name.trim())
                put("description", description?.trim().orEmpty())
                put("is_public", privacy == LibraryListPrivacy.PUBLIC)
                put("type", PublicMetaDbListType.CUSTOM)
            },
        )
        response.itemId()?.let { id ->
            sync.applyLists(scope) { lists ->
                lists.filterNot { it.id == id } + PublicMetaDbList(
                    id = id,
                    name = name.trim(),
                    description = description?.trim()?.takeIf(String::isNotEmpty),
                    isPublic = privacy == LibraryListPrivacy.PUBLIC,
                )
            }
        }
        sync.refresh(TrackingRefreshIntent.INVALIDATED)
    }

    override suspend fun updateList(key: String, name: String, description: String?, privacy: LibraryListPrivacy) {
        val scope = sync.currentScope()
        val listId = publicMetaDbListId(key) ?: throw IllegalArgumentException("This list is no longer available")
        val body = buildJsonObject {
            put("name", name.trim())
            put("description", description?.trim().orEmpty())
            put("is_public", privacy == LibraryListPrivacy.PUBLIC)
        }
        val path = "/api/external/lists/${PublicMetaDbApiClient.pathSegment(listId)}"
        val response = api.request(scope, HttpMethod.Patch, path, body, allowNotFound = true)
        if (response.status == 404) api.request(scope, HttpMethod.Put, path, body)
        sync.applyLists(scope) { lists ->
            lists.map { list ->
                if (list.id == listId) {
                    list.copy(
                        name = name.trim(),
                        description = description?.trim()?.takeIf(String::isNotEmpty),
                        isPublic = privacy == LibraryListPrivacy.PUBLIC,
                    )
                } else {
                    list
                }
            }
        }
        sync.refresh(TrackingRefreshIntent.INVALIDATED)
    }

    override suspend fun deleteList(key: String) {
        val scope = sync.currentScope()
        val listId = publicMetaDbListId(key) ?: throw IllegalArgumentException("This list is no longer available")
        val list = sync.currentProjection().lists.firstOrNull { it.id == listId }
        require(list?.type != PublicMetaDbListType.WATCHLIST) { "Your PublicMetaDB watchlist cannot be deleted" }
        api.delete(scope, "/api/external/lists/${PublicMetaDbApiClient.pathSegment(listId)}")
        sync.applyLists(scope) { lists -> lists.filterNot { it.id == listId } }
        sync.refresh(TrackingRefreshIntent.INVALIDATED)
    }
}

class PublicMetaDbWatchedSyncAdapter internal constructor(
    private val sync: PublicMetaDbSyncRepository,
    private val writes: PublicMetaDbTrackingWrites,
    auth: PublicMetaDbAuthStore,
    private val activeProfile: StateFlow<Int>,
) : TrackingWatchedProvider {
    override val providerId = TrackingProviderId.PUBLICMETADB
    private val changes = combine(sync.projectionState, auth.state, activeProfile) { _, _, _ -> Unit }

    override suspend fun pull(profileId: Int, pageSize: Int): List<WatchedItem> {
        val scope = scope(profileId)
        sync.refresh(TrackingRefreshIntent.AUTOMATIC)
        if (sync.currentScope() != scope) throw CancellationException("PublicMetaDB account changed")
        val state = sync.state.value
        if (!state.hasLoaded && state.error != null) throw PublicMetaDbApiException(0, message = "PublicMetaDB sync unavailable")
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
        if (it.profileId != profileId) throw CancellationException("PublicMetaDB profile changed")
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
