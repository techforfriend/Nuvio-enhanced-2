package com.nuvio.app.features.publicmetadb

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface PublicMetaDbAuthPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

internal expect object PlatformPublicMetaDbAuthPersistence : PublicMetaDbAuthPersistence {
    override fun read(profileId: Int): String?
    override fun write(profileId: Int, value: String?)
    override fun clear()
}

class PublicMetaDbAuthStore(
    private val persistence: PublicMetaDbAuthPersistence,
    initialProfileId: Int = 1,
) {
    private val lock = SynchronizedObject()
    private var currentScope = PublicMetaDbAuthScope(initialProfileId, 0)
    private var stored = load(initialProfileId)
    private var busy = false
    private var lastError: PublicMetaDbAuthError? = null
    private val mutableState = MutableStateFlow(stateFor())

    val state: StateFlow<PublicMetaDbAuthState> = mutableState.asStateFlow()

    fun scope(): PublicMetaDbAuthScope = synchronized(lock) { currentScope }

    fun isCurrent(scope: PublicMetaDbAuthScope): Boolean = synchronized(lock) { currentScope == scope }

    fun checkScope(scope: PublicMetaDbAuthScope) {
        if (!isCurrent(scope)) throw CancellationException("PublicMetaDB account changed")
    }

    fun apiKey(): String? = synchronized(lock) { stored.apiKey?.takeIf(String::isNotBlank) }

    fun selectProfile(profileId: Int) = synchronized(lock) {
        if (currentScope.profileId == profileId) return@synchronized
        stored = load(profileId)
        currentScope = PublicMetaDbAuthScope(profileId, currentScope.generation + 1)
        busy = false
        lastError = null
        publish()
    }

    internal fun authorize(apiKey: String, connectedAtEpochMs: Long, scope: PublicMetaDbAuthScope): Boolean =
        mutate(scope, advanceGeneration = true, error = null) {
            PublicMetaDbStoredAuth(
                apiKey = apiKey.trim(),
                keyHint = publicMetaDbKeyHint(apiKey),
                connectedAtEpochMs = connectedAtEpochMs,
            )
        }

    fun setBusy(value: Boolean, scope: PublicMetaDbAuthScope) = synchronized(lock) {
        if (currentScope != scope) return@synchronized
        busy = value
        publish()
    }

    fun setError(error: PublicMetaDbAuthError?, scope: PublicMetaDbAuthScope) = synchronized(lock) {
        if (currentScope != scope) return@synchronized
        lastError = error
        publish()
    }

    fun clearAuth(
        scope: PublicMetaDbAuthScope = scope(),
        error: PublicMetaDbAuthError? = null,
        expectedApiKey: String? = null,
    ): Boolean = synchronized(lock) {
        if (expectedApiKey != null && stored.apiKey != expectedApiKey) return@synchronized false
        mutate(scope, advanceGeneration = true, error = error) { PublicMetaDbStoredAuth() }
    }

    fun removeProfile(profileId: Int) = synchronized(lock) {
        persistence.write(profileId, null)
        if (currentScope.profileId == profileId) {
            currentScope = currentScope.copy(generation = currentScope.generation + 1)
            stored = PublicMetaDbStoredAuth()
            busy = false
            lastError = null
            publish()
        }
    }

    fun clearAllProfiles() = synchronized(lock) {
        persistence.clear()
        currentScope = currentScope.copy(generation = currentScope.generation + 1)
        stored = PublicMetaDbStoredAuth()
        busy = false
        lastError = null
        publish()
    }

    private fun mutate(
        scope: PublicMetaDbAuthScope,
        advanceGeneration: Boolean = false,
        error: PublicMetaDbAuthError? = null,
        transform: (PublicMetaDbStoredAuth) -> PublicMetaDbStoredAuth,
    ): Boolean = synchronized(lock) {
        if (currentScope != scope) return@synchronized false
        val value = transform(stored)
        persistence.write(
            scope.profileId,
            if (value.apiKey.isNullOrBlank()) null else publicMetaDbJson.encodeToString(PublicMetaDbStoredAuth.serializer(), value),
        )
        stored = value
        if (advanceGeneration) currentScope = currentScope.copy(generation = currentScope.generation + 1)
        lastError = error
        publish()
        true
    }

    private fun load(profileId: Int): PublicMetaDbStoredAuth = persistence.read(profileId)?.let { value ->
        runCatching { publicMetaDbJson.decodeFromString(PublicMetaDbStoredAuth.serializer(), value) }.getOrNull()
    } ?: PublicMetaDbStoredAuth()

    private fun publish() {
        mutableState.value = stateFor()
    }

    private fun stateFor(): PublicMetaDbAuthState {
        val authorized = !stored.apiKey.isNullOrBlank()
        return PublicMetaDbAuthState(
            scope = currentScope,
            isAuthenticated = authorized,
            keyHint = stored.keyHint.takeIf { authorized },
            connectedAtEpochMs = stored.connectedAtEpochMs.takeIf { authorized },
            isBusy = busy,
            error = lastError,
        )
    }
}
