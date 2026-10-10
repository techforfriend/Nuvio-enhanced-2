package com.nuvio.app.features.anilist

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object AniListTracker : TrackingAuthProvider {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeProfile = MutableStateFlow(ProfileRepository.activeProfileId)
    internal val store = AniListAuthStore(PlatformAniListAuthPersistence, activeProfile.value)
    internal val api = AniListApiClient(store)
    private val resolver = AniListIdResolver(api)
    val sync = AniListSyncRepository(PlatformAniListSyncStorage, store, api, resolver, activeProfile, coroutineScope)
    val writes = AniListTrackingWrites(sync, api, resolver)
    val progressProvider = AniListTrackingProgressProvider(sync, store, activeProfile, coroutineScope, ::ensureLoaded)
    val watchedProvider = AniListWatchedSyncAdapter(sync, writes, store, activeProfile)
    val libraryProvider = AniListTrackingLibraryProvider(sync, writes, ::ensureLoaded)
    internal val ratingProvider = AniListRatingsProvider(sync, writes)
    val authState: StateFlow<AniListAuthState> = store.state
    private val authenticated = MutableStateFlow(store.state.value.isAuthenticated)
    override val isAuthenticated: StateFlow<Boolean> = authenticated.asStateFlow()
    override val accountGeneration: Long get() = store.scope().generation
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.ANILIST,
        "AniList",
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

    fun hasRequiredCredentials(): Boolean = AniListConfig.isConfigured

    fun connect(): String? {
        ensureLoaded()
        if (!hasRequiredCredentials()) return null
        if (store.state.value.isAuthenticated) return null
        store.beginAuthorization(store.scope())
        return authorizationUrl()
    }

    fun pendingAuthorizationUrl(): String? =
        if (hasRequiredCredentials() && store.state.value.awaitingApproval) authorizationUrl() else connect()

    fun cancelAuthorization() {
        store.cancelAuthorization(store.scope())
    }

    fun disconnect() {
        store.clearAuth(store.scope())
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

    override fun handleAuthCallback(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith(AniListConfig.REDIRECT_URI, ignoreCase = true)) return false
        val remainder = trimmed.substring(AniListConfig.REDIRECT_URI.length)
        if (remainder.isNotEmpty() && remainder.first() !in setOf('#', '?', '/')) return false
        ensureLoaded()
        val scope = store.scope()
        if (!store.isPending()) return true
        val parameters = parseCallbackParameters(remainder)
        if (parameters["error"] != null) {
            store.cancelAuthorization(scope, AniListAuthError.ACCESS_DENIED)
            return true
        }
        val token = parameters["access_token"]?.takeIf(String::isNotBlank)
        if (token == null) {
            store.cancelAuthorization(scope, AniListAuthError.INVALID_CALLBACK)
            return true
        }
        val expiresIn = parameters["expires_in"]?.toLongOrNull()?.takeIf { it > 0L }
        val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val expiresAt = expiresIn?.let { nowMs + it * 1_000L }
        if (!store.authorize(token, expiresAt, scope)) return true
        val authorizedScope = store.scope()
        coroutineScope.launch {
            store.setBusy(true, authorizedScope)
            try {
                val viewer = api.viewer(authorizedScope)
                store.saveViewer(viewer, authorizedScope)
                sync.refresh(TrackingRefreshIntent.INVALIDATED)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            } finally {
                store.setBusy(false, store.scope())
            }
        }
        return true
    }

    override fun ensureLoaded() {
        if (activeProfile.value != ProfileRepository.activeProfileId) onProfileChanged()
        store.refreshExpiry()
        authenticated.value = store.state.value.isAuthenticated
    }

    override fun onProfileChanged() {
        activeProfile.value = ProfileRepository.activeProfileId
        store.selectProfile(activeProfile.value)
        authenticated.value = store.state.value.isAuthenticated
    }

    override fun clearLocalState() {
        store.clearAllProfiles()
        PlatformAniListSyncStorage.clearAll()
        authenticated.value = false
    }

    override fun removeStoredProfile(profileId: Int) {
        store.removeProfile(profileId)
        val scope = store.scope()
        coroutineScope.launch {
            try {
                PlatformAniListSyncStorage.remove(profileId) { store.checkScope(scope) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    private fun authorizationUrl(): String =
        "${AniListConfig.AUTHORIZE_URL}?client_id=${AniListConfig.CLIENT_ID.encodeURLParameter()}&response_type=token"

    private fun parseCallbackParameters(remainder: String): Map<String, String> {
        val parts = buildList {
            remainder.substringAfter('?', "").substringBefore('#').takeIf(String::isNotBlank)?.let(::add)
            remainder.substringAfter('#', "").takeIf(String::isNotBlank)?.let(::add)
        }
        return parts.flatMap { it.split('&') }
            .mapNotNull { pair ->
                val key = pair.substringBefore('=').trim()
                if (key.isBlank()) return@mapNotNull null
                val value = pair.substringAfter('=', "")
                key to runCatching { value.decodeURLQueryComponent(plusIsSpace = true) }.getOrDefault(value)
            }
            .toMap()
    }
}
