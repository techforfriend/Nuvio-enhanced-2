package com.nuvio.app.features.wetrakr

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object WeTrakrTracker : TrackingAuthProvider {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeProfile = MutableStateFlow(ProfileRepository.activeProfileId)
    private val pollLock = SynchronizedObject()
    private var pollJob: Job? = null
    internal val store = WeTrakrAuthStore(PlatformWeTrakrAuthPersistence, activeProfile.value)
    internal val api = WeTrakrApiClient(store)
    val sync = WeTrakrSyncRepository(PlatformWeTrakrSyncStorage, store, api, activeProfile, coroutineScope)
    val writes = WeTrakrTrackingWrites(sync, api)
    val progressProvider = WeTrakrTrackingProgressProvider(sync, api, store, activeProfile, coroutineScope, ::ensureLoaded)
    val watchedProvider = WeTrakrWatchedSyncAdapter(sync, writes, store, activeProfile)
    val libraryProvider = WeTrakrTrackingLibraryProvider(sync, writes, ::ensureLoaded)
    internal val ratingProvider = WeTrakrRatingsProvider(sync, api, writes)
    val authState: StateFlow<WeTrakrAuthState> = store.state
    private val authenticated = MutableStateFlow(store.state.value.isAuthenticated)
    override val isAuthenticated: StateFlow<Boolean> = authenticated.asStateFlow()
    override val accountGeneration: Long get() = store.scope().generation
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.WETRAKR,
        "WeTrakr",
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

    fun hasRequiredCredentials(): Boolean = WeTrakrConfig.isConfigured

    suspend fun connect(): String? {
        ensureLoaded()
        val scope = store.scope()
        if (!hasRequiredCredentials()) {
            store.setError(WeTrakrAuthError.MISSING_CLIENT_ID, scope)
            return null
        }
        if (store.state.value.isAuthenticated) return null
        if (store.pendingAuthorization() != null) {
            startPolling()
            return null
        }
        store.setBusy(true, scope)
        try {
            val pending = api.requestDeviceCode()
            if (store.beginAuthorization(pending, scope)) startPolling()
        } catch (error: CancellationException) {
            throw error
        } catch (error: WeTrakrAuthException) {
            store.setError(error.error, scope)
        } catch (_: Exception) {
            store.setError(WeTrakrAuthError.REQUEST_FAILED, scope)
        } finally {
            store.setBusy(false, scope)
        }
        return null
    }

    fun pendingAuthorizationUrl(): String? = store.pendingAuthorization()?.verificationUrl

    fun cancelAuthorization() {
        stopPolling()
        store.cancelAuthorization(store.scope())
    }

    fun disconnect() {
        stopPolling()
        val accessToken = store.accessToken()
        val refreshToken = store.refreshToken()
        store.clearAuth(store.scope())
        authenticated.value = false
        coroutineScope.launch {
            try {
                api.logout(accessToken, refreshToken)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
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
        store.refreshExpiry()
        authenticated.value = store.state.value.isAuthenticated
        if (!store.state.value.isAuthenticated && store.pendingAuthorization() != null) startPolling()
    }

    override fun onProfileChanged() {
        stopPolling()
        activeProfile.value = ProfileRepository.activeProfileId
        store.selectProfile(activeProfile.value)
        authenticated.value = store.state.value.isAuthenticated
    }

    override fun clearLocalState() {
        stopPolling()
        store.clearAllProfiles()
        PlatformWeTrakrSyncStorage.clearAll()
        authenticated.value = false
    }

    override fun removeStoredProfile(profileId: Int) {
        if (store.scope().profileId == profileId) stopPolling()
        store.removeProfile(profileId)
        val scope = store.scope()
        coroutineScope.launch {
            try {
                PlatformWeTrakrSyncStorage.remove(profileId) { store.checkScope(scope) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    private fun startPolling() = synchronized(pollLock) {
        if (pollJob?.isActive == true) return@synchronized
        val scope = store.scope()
        pollJob = coroutineScope.launch {
            var intervalSeconds = store.pendingAuthorization()?.intervalSeconds ?: return@launch
            while (true) {
                delay(intervalSeconds * 1_000L)
                if (!store.isCurrent(scope)) return@launch
                val pending = store.pendingAuthorization()
                if (pending == null) {
                    store.refreshExpiry()
                    return@launch
                }
                val result = try {
                    api.pollDeviceToken(pending.deviceCode)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    WeTrakrDevicePoll.Pending
                }
                when (result) {
                    WeTrakrDevicePoll.Pending -> Unit
                    WeTrakrDevicePoll.SlowDown -> intervalSeconds = (intervalSeconds + 5L).coerceAtMost(60L)
                    is WeTrakrDevicePoll.Finished -> {
                        store.cancelAuthorization(scope, result.error)
                        return@launch
                    }
                    is WeTrakrDevicePoll.Approved -> {
                        if (store.authorize(result.tokens, scope)) onAuthorized()
                        return@launch
                    }
                }
            }
        }
    }

    private fun stopPolling() = synchronized(pollLock) {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun onAuthorized() {
        val scope = store.scope()
        authenticated.value = store.state.value.isAuthenticated
        store.setBusy(true, scope)
        try {
            val user = api.account(scope)
            store.saveUser(user, scope)
            sync.refresh(TrackingRefreshIntent.INVALIDATED)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        } finally {
            store.setBusy(false, store.scope())
        }
    }
}
