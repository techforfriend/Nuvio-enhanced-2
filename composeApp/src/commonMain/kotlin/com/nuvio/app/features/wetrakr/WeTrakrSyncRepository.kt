package com.nuvio.app.features.wetrakr

import com.nuvio.app.features.tracking.TrackingRefreshGate
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class WeTrakrProjectionState(
    val scope: WeTrakrAuthScope? = null,
    val projection: WeTrakrProjection = WeTrakrProjection.Empty,
)

class WeTrakrSyncRepository internal constructor(
    private val storage: WeTrakrSyncStorage,
    private val auth: WeTrakrAuthStore,
    private val api: WeTrakrApiClient,
    private val activeProfileId: StateFlow<Int>,
    private val coroutineScope: CoroutineScope,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val gate = TrackingRefreshGate()
    private val mutableState = MutableStateFlow(WeTrakrSyncState())
    private val mutableProjection = MutableStateFlow(WeTrakrProjectionState())
    private val mutableLocalPlayback = MutableStateFlow<List<WatchProgressEntry>>(emptyList())
    private var localPlaybackScope: WeTrakrAuthScope? = null
    private var persistJob: Job? = null
    val state: StateFlow<WeTrakrSyncState> = mutableState.asStateFlow()
    internal val projectionState: StateFlow<WeTrakrProjectionState> = mutableProjection.asStateFlow()
    internal val localPlayback: StateFlow<List<WatchProgressEntry>> = mutableLocalPlayback.asStateFlow()

    init {
        coroutineScope.launch {
            combine(activeProfileId, auth.state) { profileId, authorization ->
                Triple(profileId, authorization.scope, authorization.isAuthenticated)
            }.distinctUntilChanged().collectLatest {
                if (!matchesCurrent(mutableState.value.scope)) clearPublished()
                try {
                    ensureLoaded()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (matchesCurrent(auth.scope())) {
                        mutableState.value = WeTrakrSyncState(scope = auth.scope(), error = error.toWeTrakrSyncError())
                    }
                }
            }
        }
    }

    fun currentScope(): WeTrakrAuthScope = auth.scope().also { checkScope(it) }

    fun currentSnapshot(): WeTrakrSyncSnapshot? = mutableState.value.takeIf { matchesCurrent(it.scope) }?.snapshot

    internal fun currentProjection(): WeTrakrProjection = mutableProjection.value
        .takeIf { matchesCurrent(it.scope) && auth.state.value.isAuthenticated }
        ?.projection
        ?: WeTrakrProjection.Empty

    internal fun currentLocalPlayback(): List<WatchProgressEntry> =
        if (matchesCurrent(localPlaybackScope) && auth.state.value.isAuthenticated) mutableLocalPlayback.value else emptyList()

    suspend fun ensureLoaded() = withContext(Dispatchers.Default) { mutex.withLock { loadCurrent() } }

    fun refreshAsync(intent: TrackingRefreshIntent) {
        coroutineScope.launch {
            try {
                refresh(intent)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    suspend fun refresh(intent: TrackingRefreshIntent): Unit = withContext(Dispatchers.Default) {
        val scope = auth.scope()
        gate.runIfNeeded(scope.generation, shouldRun = {
            auth.state.value.isAuthenticated && matchesCurrent(scope) && shouldRefresh(intent)
        }) {
            mutex.withLock {
                checkScope(scope)
                val attemptedAt = now()
                try {
                    val user = ensureUser(scope)
                    val previous = loadCurrent() ?: return@withLock
                    mutableState.value = mutableState.value.copy(isLoading = true, error = null, attemptedAtEpochMs = attemptedAt)
                    val fingerprint = api.lastActivitiesFingerprint(scope)
                    checkScope(scope)
                    val unchanged = intent == TrackingRefreshIntent.AUTOMATIC &&
                        fingerprint != null &&
                        fingerprint == previous.activitiesFingerprint &&
                        previous.checkedAtEpochMs != null &&
                        previous.userId == user.id
                    val next = if (unchanged) {
                        previous.copy(
                            playback = fetchPlayback(scope) ?: previous.playback,
                            checkedAtEpochMs = attemptedAt,
                        )
                    } else {
                        val entries = fetchEntries(scope)
                        checkScope(scope)
                        val episodes = fetchWatchedEpisodes(scope)
                        checkScope(scope)
                        val playback = fetchPlayback(scope) ?: previous.playback
                        val ratings = fetchRatings(scope) ?: previous.ratings
                        checkScope(scope)
                        val liveShowKeys = entries
                            .filter { !it.media.isMovie }
                            .mapNotNullTo(mutableSetOf()) { entry -> entry.media.ids.contentId()?.let(::weTrakrShowKey) }
                        previous.copy(
                            userId = user.id,
                            entries = entries,
                            watchedEpisodes = episodes,
                            playback = playback,
                            ratings = ratings,
                            dismissed = previous.dismissed.filterKeys(liveShowKeys::contains),
                            activitiesFingerprint = fingerprint,
                            checkedAtEpochMs = attemptedAt,
                        )
                    }
                    commit(scope, next)
                    mutableState.value = mutableState.value.copy(isLoading = false, attemptedAtEpochMs = attemptedAt)
                } catch (error: CancellationException) {
                    if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(isLoading = false)
                    throw error
                } catch (error: Exception) {
                    if (matchesCurrent(scope)) {
                        mutableState.value = mutableState.value.copy(
                            scope = scope,
                            isLoading = false,
                            error = error.toWeTrakrSyncError(),
                            retryAtEpochMs = (error as? WeTrakrApiException)?.retryAtEpochMs,
                            attemptedAtEpochMs = attemptedAt,
                        )
                    }
                }
            }
        }
        Unit
    }

    internal suspend fun <T> mutate(
        scope: WeTrakrAuthScope,
        block: suspend (WeTrakrSyncSnapshot) -> Pair<WeTrakrSyncSnapshot, T>,
    ): T = withContext(Dispatchers.Default) {
        mutex.withLock {
            checkScope(scope)
            val previous = loadCurrent() ?: throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
            val (next, result) = block(previous)
            if (next != previous) commit(scope, next)
            result
        }
    }

    internal suspend fun applyStatus(scope: WeTrakrAuthScope, media: WeTrakrMedia, status: String?) {
        mutate(scope) { snapshot ->
            val matches = { entry: WeTrakrListEntry -> entry.media.sameTitleAs(media) }
            val existing = snapshot.entries.firstOrNull(matches)
            val remaining = snapshot.entries.filterNot(matches)
            val entries = if (status == null) {
                remaining
            } else {
                val nowMs = now()
                listOf(
                    WeTrakrListEntry(
                        media = existing?.media?.takeIf { it.id > 0L } ?: media,
                        status = status,
                        addedAtEpochMs = nowMs,
                        watchedAtEpochMs = if (status == WeTrakrStatus.WATCHED) nowMs else existing?.watchedAtEpochMs ?: 0L,
                        watchedCount = existing?.watchedCount ?: 0,
                    ),
                ) + remaining
            }
            snapshot.copy(entries = entries, activitiesFingerprint = null) to Unit
        }
    }

    internal suspend fun applyRating(scope: WeTrakrAuthScope, rating: WeTrakrRatingEntry, value: Double?) {
        mutate(scope) { snapshot ->
            val remaining = snapshot.ratings.filterNot { it.sameTargetAs(rating) }
            val ratings = if (value == null) remaining else listOf(rating.copy(rating = value)) + remaining
            snapshot.copy(ratings = ratings, activitiesFingerprint = null) to Unit
        }
    }

    internal suspend fun removePlayback(scope: WeTrakrAuthScope, playback: Collection<WeTrakrPlayback>) {
        if (playback.isEmpty()) return
        mutate(scope) { snapshot ->
            snapshot.copy(playback = snapshot.playback.filterNot { it in playback }) to Unit
        }
    }

    internal suspend fun dismiss(scope: WeTrakrAuthScope, keys: Map<String, Long>) {
        if (keys.isEmpty()) return
        mutate(scope) { snapshot -> snapshot.copy(dismissed = snapshot.dismissed + keys) to Unit }
    }

    internal suspend fun upsertLocalPlayback(scope: WeTrakrAuthScope, entry: WatchProgressEntry) {
        withContext(Dispatchers.Default) {
            mutex.withLock {
                checkScope(scope)
                loadCurrent() ?: return@withLock
                val key = entry.localKey()
                val remaining = mutableLocalPlayback.value.filterNot { it.localKey() == key }
                val next = if (entry.isEffectivelyCompleted) remaining else (listOf(entry) + remaining).take(MAX_LOCAL_PLAYBACK)
                if (next == mutableLocalPlayback.value) return@withLock
                localPlaybackScope = scope
                mutableLocalPlayback.value = next
                schedulePersist(scope, if (entry.isEffectivelyCompleted) 0L else LOCAL_PERSIST_DELAY_MS)
            }
        }
    }

    internal suspend fun removeLocalPlayback(scope: WeTrakrAuthScope, entries: Collection<WatchProgressEntry>) {
        val keys = entries.mapTo(mutableSetOf()) { it.localKey() }
        withContext(Dispatchers.Default) {
            mutex.withLock {
                checkScope(scope)
                loadCurrent() ?: return@withLock
                localPlaybackScope = scope
                mutableLocalPlayback.value = mutableLocalPlayback.value.filterNot { it.localKey() in keys }
            }
        }
        schedulePersist(scope, 0L)
    }

    private fun schedulePersist(scope: WeTrakrAuthScope, delayMs: Long) {
        persistJob?.cancel()
        persistJob = coroutineScope.launch {
            if (delayMs > 0L) delay(delayMs)
            try {
                withContext(Dispatchers.Default) {
                    mutex.withLock {
                        if (!matchesCurrent(scope)) return@withLock
                        val snapshot = mutableState.value.snapshot ?: return@withLock
                        val payload = weTrakrJson.encodeToString(
                            WeTrakrSyncSnapshot.serializer(),
                            snapshot.copy(localPlayback = mutableLocalPlayback.value),
                        )
                        storage.save(scope.profileId, payload) { checkScope(scope) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun ensureUser(scope: WeTrakrAuthScope): WeTrakrUser {
        auth.state.value.user?.takeIf { it.id > 0L || it.plan != null }?.let { return it }
        val user = api.account(scope)
        auth.saveUser(user, scope)
        return user
    }

    private suspend fun fetchEntries(scope: WeTrakrAuthScope): List<WeTrakrListEntry> {
        val entries = mutableListOf<WeTrakrListEntry>()
        WeTrakrStatus.movieStatuses.forEach { status ->
            api.pagedList(scope, "/sync/tracking/$status/movies", listOf("extended" to "movie_level_1"))
                .mapNotNullTo(entries) { row -> parseEntry(row, status, WeTrakrMediaType.MOVIE) }
        }
        WeTrakrStatus.showStatuses.forEach { status ->
            api.pagedList(scope, "/sync/tracking/$status/shows", listOf("extended" to "show_level_1"))
                .mapNotNullTo(entries) { row -> parseEntry(row, status, WeTrakrMediaType.SHOW) }
        }
        return entries
    }

    private suspend fun fetchWatchedEpisodes(scope: WeTrakrAuthScope): List<WeTrakrWatchedEpisode> {
        val rows = try {
            api.compactList(scope, "/sync/tracking/watched/episodes")
        } catch (error: WeTrakrApiException) {
            if (error.status != 400) throw error
            api.pagedList(scope, "/sync/tracking/watched/episodes")
        }
        return rows.mapNotNull(::parseWatchedEpisode)
            .groupBy { Triple(it.showId ?: it.showIds.contentId(), it.season, it.number) }
            .map { (_, group) -> group.maxBy(WeTrakrWatchedEpisode::watchedAtEpochMs) }
    }

    private suspend fun fetchPlayback(scope: WeTrakrAuthScope): List<WeTrakrPlayback>? = optional {
        val movies = api.pagedList(scope, "/sync/tracking/playing/movies", listOf("extended" to "movie_level_1"))
            .mapNotNull { row -> parsePlayback(row, WeTrakrMediaType.MOVIE) }
        val episodes = api.pagedList(
            scope,
            "/sync/tracking/playing/episodes",
            listOf("extended" to "show_level_1,episode_level_1"),
        ).mapNotNull { row -> parsePlayback(row, WeTrakrMediaType.EPISODE) }
        movies + episodes
    }

    private suspend fun fetchRatings(scope: WeTrakrAuthScope): List<WeTrakrRatingEntry>? = optional {
        listOf(
            "movies" to WeTrakrMediaType.MOVIE,
            "shows" to WeTrakrMediaType.SHOW,
            "seasons" to WeTrakrMediaType.SEASON,
            "episodes" to WeTrakrMediaType.EPISODE,
        ).flatMap { (target, type) ->
            api.compactList(scope, "/sync/ratings/$target").mapNotNull { row -> parseRating(row, type) }
        }
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: WeTrakrAuthException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun parseEntry(row: JsonObject, status: String, type: String): WeTrakrListEntry? {
        val media = parseWeTrakrMedia(row, type)
            ?: parseWeTrakrMedia(row[type], type)
            ?: return null
        if (!media.ids.hasAny) return null
        return WeTrakrListEntry(
            media = media.copy(type = type),
            status = status,
            addedAtEpochMs = row.weTrakrEpochMs("tracked_at", "added_at", "listed_at", "updated_at") ?: 0L,
            watchedAtEpochMs = row.weTrakrEpochMs("watched_at", "last_watched_at") ?: 0L,
            watchedCount = row.weTrakrInt("watched_count") ?: row.weTrakrInt("plays") ?: 0,
        )
    }

    private fun parseWatchedEpisode(row: JsonObject): WeTrakrWatchedEpisode? {
        val show = row["show"].weTrakrObject()
        val season = row.weTrakrInt("season_number") ?: row.weTrakrInt("season") ?: return null
        val number = row.weTrakrInt("number") ?: row.weTrakrInt("episode_number") ?: return null
        val showIds = row["show_ids"].toWeTrakrIds().takeIf(WeTrakrIds::hasAny) ?: show?.get("ids").toWeTrakrIds()
        val showId = row.weTrakrLong("show_id") ?: show?.weTrakrLong("id")
        if (showId == null && !showIds.hasAny) return null
        return WeTrakrWatchedEpisode(
            showId = showId,
            showIds = showIds,
            showTitle = show?.weTrakrString("title"),
            season = season,
            number = number,
            watchedAtEpochMs = row.weTrakrEpochMs("watched_at", "last_watched_at", "tracked_at") ?: 0L,
        )
    }

    private fun parsePlayback(row: JsonObject, type: String): WeTrakrPlayback? {
        val playback = row["playback"].weTrakrObject() ?: row
        val progress = playback.weTrakrDouble("progress_percent") ?: playback.weTrakrDouble("progress") ?: return null
        val trackedAt = playback.weTrakrEpochMs("tracked_at", "updated_at") ?: row.weTrakrEpochMs("tracked_at") ?: 0L
        val status = playback.weTrakrString("status") ?: WeTrakrStatus.PLAYING
        val runtime = playback.weTrakrLong("runtime_seconds")
        if (type == WeTrakrMediaType.MOVIE) {
            val media = (parseWeTrakrMedia(row, WeTrakrMediaType.MOVIE) ?: parseWeTrakrMedia(row["movie"], WeTrakrMediaType.MOVIE))
                ?.copy(type = WeTrakrMediaType.MOVIE)
                ?.takeIf { it.ids.hasAny }
                ?: return null
            return WeTrakrPlayback(
                type = WeTrakrMediaType.MOVIE,
                media = media,
                status = status,
                progressPercent = progress,
                runtimeSeconds = runtime,
                trackedAtEpochMs = trackedAt,
            )
        }
        val showObject = row["show"].weTrakrObject()
        val episodeObject = row["episode"].weTrakrObject() ?: row
        val show = parseWeTrakrMedia(showObject, WeTrakrMediaType.SHOW)?.copy(type = WeTrakrMediaType.SHOW)
            ?: WeTrakrMedia(
                id = row.weTrakrLong("show_id") ?: 0L,
                type = WeTrakrMediaType.SHOW,
                title = showObject?.weTrakrString("title"),
                ids = row["show_ids"].toWeTrakrIds(),
            )
        if (!show.ids.hasAny) return null
        return WeTrakrPlayback(
            type = WeTrakrMediaType.EPISODE,
            media = show,
            season = episodeObject.weTrakrInt("season_number") ?: episodeObject.weTrakrInt("season"),
            number = episodeObject.weTrakrInt("number") ?: episodeObject.weTrakrInt("episode_number"),
            episodeTitle = episodeObject.weTrakrString("title")?.takeIf { episodeObject !== row || showObject != null },
            status = status,
            progressPercent = progress,
            runtimeSeconds = runtime ?: episodeObject.weTrakrInt("runtime")?.toLong()?.times(60L),
            trackedAtEpochMs = trackedAt,
        )
    }

    private fun parseRating(row: JsonObject, type: String): WeTrakrRatingEntry? {
        if (row.weTrakrString("status").equals("removed", ignoreCase = true)) return null
        val user = row["interactions"].weTrakrObject()?.get("user").weTrakrObject()
        val nested = user?.get("rating")
        val value = row.weTrakrDouble("rating")
            ?: (nested as? JsonPrimitive)?.content?.toDoubleOrNull()
            ?: nested.weTrakrObject()?.weTrakrDouble("rating")
            ?: row.weTrakrDouble("user_rating")
            ?: return null
        val isParent = type == WeTrakrMediaType.MOVIE || type == WeTrakrMediaType.SHOW
        val ids = if (isParent) {
            row["ids"].toWeTrakrIds()
        } else {
            row["show_ids"].toWeTrakrIds().takeIf(WeTrakrIds::hasAny)
                ?: row["show"].weTrakrObject()?.get("ids").toWeTrakrIds()
        }
        if (!ids.hasAny) return null
        val season = when (type) {
            WeTrakrMediaType.SEASON -> row.weTrakrInt("season_number") ?: row.weTrakrInt("number")
            WeTrakrMediaType.EPISODE -> row.weTrakrInt("season_number") ?: row.weTrakrInt("season")
            else -> null
        }
        val number = if (type == WeTrakrMediaType.EPISODE) row.weTrakrInt("number") ?: return null else null
        if (!isParent && season == null) return null
        return WeTrakrRatingEntry(
            type = type,
            ids = ids,
            id = row.weTrakrLong("id"),
            season = season,
            number = number,
            rating = value,
        )
    }

    private suspend fun loadCurrent(): WeTrakrSyncSnapshot? {
        val scope = auth.scope()
        if (!matchesCurrent(scope)) throw CancellationException("WeTrakr account changed")
        val authorization = auth.state.value
        if (!authorization.isAuthenticated) {
            clearPublished()
            storage.remove(scope.profileId) { if (!matchesCurrent(scope)) throw CancellationException("WeTrakr account changed") }
            return null
        }
        val userId = authorization.user?.id ?: 0L
        val current = mutableState.value
        if (current.scope == scope && current.snapshot != null &&
            (userId == 0L || current.snapshot.userId == 0L || current.snapshot.userId == userId)
        ) {
            return current.snapshot
        }
        val payload = storage.load(scope.profileId)
        val snapshot = payload
            ?.let { runCatching { weTrakrJson.decodeFromString(WeTrakrSyncSnapshot.serializer(), it) }.getOrNull() }
            ?.takeIf { userId == 0L || it.userId == userId || it.userId == 0L }
            ?: WeTrakrSyncSnapshot(userId)
        if (!matchesCurrent(scope)) throw CancellationException("WeTrakr account changed")
        val projection = runCatching { WeTrakrProjection(snapshot) }.getOrNull()
        val usable = if (projection != null) snapshot else WeTrakrSyncSnapshot(userId)
        mutableProjection.value = WeTrakrProjectionState(scope, projection ?: WeTrakrProjection(usable))
        mutableLocalPlayback.value = usable.localPlayback
            .filter { now() - it.lastUpdatedEpochMs < LOCAL_PLAYBACK_TTL_MS }
            .take(MAX_LOCAL_PLAYBACK)
        localPlaybackScope = scope
        mutableState.value = WeTrakrSyncState(scope = scope, snapshot = usable)
        return usable
    }

    private suspend fun commit(scope: WeTrakrAuthScope, snapshot: WeTrakrSyncSnapshot) {
        checkScope(scope)
        val previous = mutableProjection.value
        val projection = if (previous.scope == scope && previous.projection.snapshot == snapshot.copy(localPlayback = previous.projection.snapshot.localPlayback)) {
            previous.projection
        } else {
            WeTrakrProjection(snapshot)
        }
        persistJob?.cancel()
        val payload = weTrakrJson.encodeToString(
            WeTrakrSyncSnapshot.serializer(),
            snapshot.copy(localPlayback = if (localPlaybackScope == scope) mutableLocalPlayback.value else snapshot.localPlayback),
        )
        storage.save(scope.profileId, payload) { checkScope(scope) }
        checkScope(scope)
        mutableProjection.value = WeTrakrProjectionState(scope, projection)
        mutableState.value = mutableState.value.copy(scope = scope, snapshot = snapshot)
    }

    private fun shouldRefresh(intent: TrackingRefreshIntent): Boolean {
        val current = mutableState.value.takeIf { matchesCurrent(it.scope) } ?: return true
        val nowMs = now()
        if (current.retryAtEpochMs?.let { it > nowMs } == true) return false
        if (intent != TrackingRefreshIntent.AUTOMATIC) return true
        if (current.error != null) {
            return current.attemptedAtEpochMs?.let { nowMs - it !in 0 until ERROR_RETRY_MS } ?: true
        }
        return current.snapshot?.checkedAtEpochMs?.let { nowMs - it !in 0 until AUTOMATIC_INTERVAL_MS } ?: true
    }

    private fun checkScope(scope: WeTrakrAuthScope) {
        if (!matchesCurrent(scope)) throw CancellationException("WeTrakr account changed")
        if (!auth.state.value.isAuthenticated) throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
    }

    private fun matchesCurrent(scope: WeTrakrAuthScope?): Boolean =
        scope != null && scope.profileId == activeProfileId.value && auth.isCurrent(scope)

    private fun clearPublished() {
        persistJob?.cancel()
        mutableProjection.value = WeTrakrProjectionState()
        mutableLocalPlayback.value = emptyList()
        localPlaybackScope = null
        mutableState.value = WeTrakrSyncState()
    }

    private fun WatchProgressEntry.localKey(): String =
        "${parentMetaId.lowercase()}|${seasonNumber ?: -1}|${episodeNumber ?: -1}"

    companion object {
        const val AUTOMATIC_INTERVAL_MS = 10L * 60L * 1_000L
        const val ERROR_RETRY_MS = 60_000L
        const val LOCAL_PLAYBACK_TTL_MS = 60L * 24L * 60L * 60L * 1_000L
        const val MAX_LOCAL_PLAYBACK = 200
        const val LOCAL_PERSIST_DELAY_MS = 30_000L
    }
}

internal fun WeTrakrMedia.sameTitleAs(other: WeTrakrMedia): Boolean {
    if (isMovie != other.isMovie) return false
    if (id > 0L && other.id > 0L) return id == other.id
    val mine = ids.aliases().mapTo(mutableSetOf()) { it.lowercase() }
    return other.ids.aliases().any { it.lowercase() in mine }
}

internal fun WeTrakrRatingEntry.sameTargetAs(other: WeTrakrRatingEntry): Boolean {
    if (!type.equals(other.type, ignoreCase = true) || season != other.season || number != other.number) return false
    val mine = ids.aliases().mapTo(mutableSetOf()) { it.lowercase() }
    return other.ids.aliases().any { it.lowercase() in mine }
}
