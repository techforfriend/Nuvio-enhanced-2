package com.nuvio.app.features.wetrakr

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface WeTrakrAuthPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

internal expect object PlatformWeTrakrAuthPersistence : WeTrakrAuthPersistence {
    override fun read(profileId: Int): String?
    override fun write(profileId: Int, value: String?)
    override fun clear()
}

class WeTrakrAuthStore(
    private val persistence: WeTrakrAuthPersistence,
    initialProfileId: Int = 1,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = SynchronizedObject()
    private var currentScope = WeTrakrAuthScope(initialProfileId, 0)
    private var stored = load(initialProfileId)
    private var busy = false
    private var lastError: WeTrakrAuthError? = null
    private val mutableState = MutableStateFlow(stateFor())

    val state: StateFlow<WeTrakrAuthState> = mutableState.asStateFlow()

    fun scope(): WeTrakrAuthScope = synchronized(lock) { currentScope }

    fun isCurrent(scope: WeTrakrAuthScope): Boolean = synchronized(lock) { currentScope == scope }

    fun checkScope(scope: WeTrakrAuthScope) {
        if (!isCurrent(scope)) throw CancellationException("WeTrakr account changed")
    }

    fun accessToken(): String? = synchronized(lock) { stored.accessToken?.takeIf(String::isNotBlank) }

    fun refreshToken(): String? = synchronized(lock) { stored.refreshToken?.takeIf(String::isNotBlank) }

    fun needsRefresh(marginMs: Long = REFRESH_MARGIN_MS): Boolean = synchronized(lock) {
        val access = stored.accessToken
        if (access.isNullOrBlank()) return@synchronized !stored.refreshToken.isNullOrBlank()
        val expiresAt = stored.expiresAtEpochMs ?: return@synchronized false
        expiresAt - now() <= marginMs
    }

    internal fun pendingAuthorization(): WeTrakrDeviceAuthorization? = synchronized(lock) {
        stored.pending?.takeIf { it.expiresAtEpochMs > now() }
    }

    fun selectProfile(profileId: Int) = synchronized(lock) {
        if (currentScope.profileId == profileId) return@synchronized
        stored = load(profileId)
        currentScope = WeTrakrAuthScope(profileId, currentScope.generation + 1)
        busy = false
        lastError = null
        publish()
    }

    internal fun beginAuthorization(pending: WeTrakrDeviceAuthorization, scope: WeTrakrAuthScope): Boolean =
        mutate(scope, error = null) { it.copy(pending = pending) }

    fun cancelAuthorization(scope: WeTrakrAuthScope = scope(), error: WeTrakrAuthError? = null): Boolean =
        mutate(scope, error = error) { it.copy(pending = null) }

    internal fun authorize(tokens: WeTrakrTokens, scope: WeTrakrAuthScope): Boolean =
        mutate(scope, advanceGeneration = true, error = null) {
            WeTrakrStoredAuth(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAtEpochMs = tokens.expiresAtEpochMs,
                user = tokens.username?.let { name -> WeTrakrUser(username = name) },
            )
        }

    internal fun updateTokens(tokens: WeTrakrTokens, scope: WeTrakrAuthScope, expectedRefreshToken: String): Boolean =
        synchronized(lock) {
            if (stored.refreshToken != expectedRefreshToken) return@synchronized false
            mutate(scope, error = lastError) {
                it.copy(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    expiresAtEpochMs = tokens.expiresAtEpochMs,
                )
            }
        }

    fun saveUser(user: WeTrakrUser, scope: WeTrakrAuthScope): Boolean =
        mutate(scope, error = lastError) { it.copy(user = user) }

    fun setBusy(value: Boolean, scope: WeTrakrAuthScope) = synchronized(lock) {
        if (currentScope != scope) return@synchronized
        busy = value
        publish()
    }

    fun setError(error: WeTrakrAuthError?, scope: WeTrakrAuthScope) = synchronized(lock) {
        if (currentScope != scope) return@synchronized
        lastError = error
        publish()
    }

    fun clearAuth(
        scope: WeTrakrAuthScope = scope(),
        error: WeTrakrAuthError? = null,
        expectedRefreshToken: String? = null,
    ): Boolean = synchronized(lock) {
        if (expectedRefreshToken != null && stored.refreshToken != expectedRefreshToken) return@synchronized false
        mutate(scope, advanceGeneration = true, error = error) { WeTrakrStoredAuth() }
    }

    fun removeProfile(profileId: Int) = synchronized(lock) {
        persistence.write(profileId, null)
        if (currentScope.profileId == profileId) {
            currentScope = currentScope.copy(generation = currentScope.generation + 1)
            stored = WeTrakrStoredAuth()
            busy = false
            lastError = null
            publish()
        }
    }

    fun clearAllProfiles() = synchronized(lock) {
        persistence.clear()
        currentScope = currentScope.copy(generation = currentScope.generation + 1)
        stored = WeTrakrStoredAuth()
        busy = false
        lastError = null
        publish()
    }

    fun refreshExpiry() = synchronized(lock) {
        val pending = stored.pending
        if (pending != null && pending.expiresAtEpochMs <= now()) {
            val value = stored.copy(pending = null)
            persistence.write(currentScope.profileId, weTrakrJson.encodeToString(WeTrakrStoredAuth.serializer(), value))
            stored = value
            if (!isAuthorized(value)) lastError = WeTrakrAuthError.CODE_EXPIRED
            publish()
        }
    }

    private fun mutate(
        scope: WeTrakrAuthScope,
        advanceGeneration: Boolean = false,
        error: WeTrakrAuthError? = null,
        transform: (WeTrakrStoredAuth) -> WeTrakrStoredAuth,
    ): Boolean = synchronized(lock) {
        if (currentScope != scope) return@synchronized false
        val value = transform(stored)
        persistence.write(scope.profileId, weTrakrJson.encodeToString(WeTrakrStoredAuth.serializer(), value))
        stored = value
        if (advanceGeneration) currentScope = currentScope.copy(generation = currentScope.generation + 1)
        lastError = error
        publish()
        true
    }

    private fun load(profileId: Int): WeTrakrStoredAuth = persistence.read(profileId)?.let { value ->
        runCatching { weTrakrJson.decodeFromString(WeTrakrStoredAuth.serializer(), value) }.getOrNull()
    } ?: WeTrakrStoredAuth()

    private fun isAuthorized(value: WeTrakrStoredAuth): Boolean =
        !value.refreshToken.isNullOrBlank() || !value.accessToken.isNullOrBlank()

    private fun publish() {
        mutableState.value = stateFor()
    }

    private fun stateFor(): WeTrakrAuthState {
        val authorized = isAuthorized(stored)
        val pending = stored.pending?.takeIf { !authorized && it.expiresAtEpochMs > now() }
        return WeTrakrAuthState(
            scope = currentScope,
            isAuthenticated = authorized,
            user = stored.user,
            awaitingApproval = pending != null,
            userCode = pending?.userCode,
            verificationUrl = pending?.verificationUrl,
            isBusy = busy,
            error = lastError,
        )
    }

    companion object {
        const val REFRESH_MARGIN_MS = 24L * 60L * 60L * 1_000L
    }
}
