package com.nuvio.app.features.publicmetadb

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import com.nuvio.app.features.tracking.TrackingRefreshGate
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

interface PublicMetaDbSyncStorage {
    suspend fun load(profileId: Int): String?
    suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit)
    suspend fun remove(profileId: Int, checkScope: () -> Unit)
}

internal expect object PlatformPublicMetaDbSyncStorage : PublicMetaDbSyncStorage {
    override suspend fun load(profileId: Int): String?
    override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit)
    override suspend fun remove(profileId: Int, checkScope: () -> Unit)
    fun clearAll()
}

internal data class PublicMetaDbProjectionState(
    val scope: PublicMetaDbAuthScope? = null,
    val projection: PublicMetaDbProjection = PublicMetaDbProjection.Empty,
)

class PublicMetaDbSyncRepository internal constructor(
    private val storage: PublicMetaDbSyncStorage,
    private val auth: PublicMetaDbAuthStore,
    private val api: PublicMetaDbApiClient,
    private val activeProfileId: StateFlow<Int>,
    private val coroutineScope: CoroutineScope,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val gate = TrackingRefreshGate()
    private val mutableState = MutableStateFlow(PublicMetaDbSyncState())
    private val mutableProjection = MutableStateFlow(PublicMetaDbProjectionState())
    private val mutableLocalPlayback = MutableStateFlow<List<WatchProgressEntry>>(emptyList())
    private var localPlaybackScope: PublicMetaDbAuthScope? = null
    private var persistJob: Job? = null
    val state: StateFlow<PublicMetaDbSyncState> = mutableState.asStateFlow()
    internal val projectionState: StateFlow<PublicMetaDbProjectionState> = mutableProjection.asStateFlow()
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
                        mutableState.value = PublicMetaDbSyncState(scope = auth.scope(), error = error.toPublicMetaDbSyncError())
                    }
                }
            }
        }
    }

    fun currentScope(): PublicMetaDbAuthScope = auth.scope().also { checkScope(it) }

    fun currentSnapshot(): PublicMetaDbSyncSnapshot? = mutableState.value.takeIf { matchesCurrent(it.scope) }?.snapshot

    internal fun currentProjection(): PublicMetaDbProjection = mutableProjection.value
        .takeIf { matchesCurrent(it.scope) && auth.state.value.isAuthenticated }
        ?.projection
        ?: PublicMetaDbProjection.Empty

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
                    val previous = loadCurrent() ?: return@withLock
                    mutableState.value = mutableState.value.copy(isLoading = true, error = null, attemptedAtEpochMs = attemptedAt)
                    val lists = fetchLists(scope)
                    checkScope(scope)
                    val listItems = linkedMapOf<String, List<PublicMetaDbListItem>>()
                    lists.forEach { list ->
                        listItems[list.id] = fetchListItems(scope, list.id)
                        checkScope(scope)
                    }
                    val watched = fetchWatched(scope)
                    checkScope(scope)
                    val resume = fetchResume(scope) ?: previous.resume
                    checkScope(scope)
                    val media = enrichMedia(
                        scope = scope,
                        previous = previous.media,
                        wanted = wantedMedia(listItems.values.flatten(), watched, resume, previous.ratings),
                    )
                    checkScope(scope)
                    val liveShowKeys = watched
                        .filterNot { isPublicMetaDbMovieType(it.mediaType) }
                        .mapTo(mutableSetOf()) { publicMetaDbShowKey(it.tmdbId) }
                    commit(
                        scope,
                        previous.copy(
                            accountKey = auth.state.value.keyHint.orEmpty(),
                            lists = lists,
                            listItems = listItems,
                            watched = watched,
                            resume = resume,
                            media = media,
                            dismissed = previous.dismissed.filterKeys(liveShowKeys::contains),
                            checkedAtEpochMs = attemptedAt,
                        ),
                    )
                    mutableState.value = mutableState.value.copy(isLoading = false, attemptedAtEpochMs = attemptedAt)
                } catch (error: CancellationException) {
                    if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(isLoading = false)
                    throw error
                } catch (error: Exception) {
                    if (matchesCurrent(scope)) {
                        mutableState.value = mutableState.value.copy(
                            scope = scope,
                            isLoading = false,
                            error = error.toPublicMetaDbSyncError(),
                            retryAtEpochMs = (error as? PublicMetaDbApiException)?.retryAtEpochMs,
                            attemptedAtEpochMs = attemptedAt,
                        )
                    }
                }
            }
        }
        Unit
    }

    internal suspend fun <T> mutate(
        scope: PublicMetaDbAuthScope,
        block: suspend (PublicMetaDbSyncSnapshot) -> Pair<PublicMetaDbSyncSnapshot, T>,
    ): T = withContext(Dispatchers.Default) {
        mutex.withLock {
            checkScope(scope)
            val previous = loadCurrent() ?: throw PublicMetaDbAuthException(PublicMetaDbAuthError.AUTHORIZATION_REVOKED)
            val (next, result) = block(previous)
            if (next != previous) commit(scope, next)
            result
        }
    }

    internal suspend fun applyWatched(
        scope: PublicMetaDbAuthScope,
        added: List<PublicMetaDbWatchedEntry>,
        removed: (PublicMetaDbWatchedEntry) -> Boolean = { false },
    ) {
        mutate(scope) { snapshot ->
            val remaining = snapshot.watched.filterNot(removed)
            snapshot.copy(watched = dedupePublicMetaDbWatched(added + remaining)) to Unit
        }
    }

    internal suspend fun applyListItem(
        scope: PublicMetaDbAuthScope,
        listId: String,
        added: PublicMetaDbListItem?,
        removed: (PublicMetaDbListItem) -> Boolean = { false },
    ) {
        mutate(scope) { snapshot ->
            val current = snapshot.listItems[listId].orEmpty().filterNot(removed)
            val items = if (added == null) {
                current
            } else {
                listOf(added) + current.filterNot { it.tmdbId == added.tmdbId && isPublicMetaDbMovieType(it.mediaType) == isPublicMetaDbMovieType(added.mediaType) }
            }
            val lists = snapshot.lists.map { list -> if (list.id == listId) list.copy(itemCount = items.size) else list }
            snapshot.copy(lists = lists, listItems = snapshot.listItems + (listId to items)) to Unit
        }
    }

    internal suspend fun applyLists(
        scope: PublicMetaDbAuthScope,
        transform: (List<PublicMetaDbList>) -> List<PublicMetaDbList>,
    ) {
        mutate(scope) { snapshot ->
            val lists = transform(snapshot.lists)
            val ids = lists.mapTo(mutableSetOf(), PublicMetaDbList::id)
            snapshot.copy(
                lists = lists,
                listItems = snapshot.listItems.filterKeys(ids::contains) + lists.filter { it.id !in snapshot.listItems }.associate { it.id to emptyList() },
            ) to Unit
        }
    }

    internal suspend fun applyResume(
        scope: PublicMetaDbAuthScope,
        point: PublicMetaDbResumePoint?,
        matches: (PublicMetaDbResumePoint) -> Boolean,
    ) {
        mutate(scope) { snapshot ->
            val remaining = snapshot.resume.filterNot(matches)
            snapshot.copy(resume = if (point == null) remaining else listOf(point) + remaining) to Unit
        }
    }

    internal suspend fun applyRating(scope: PublicMetaDbAuthScope, entry: PublicMetaDbRatingEntry, value: Int?) {
        mutate(scope) { snapshot ->
            val remaining = snapshot.ratings.filterNot { it.sameTargetAs(entry) }
            snapshot.copy(ratings = if (value == null) remaining else listOf(entry.copy(rating = value)) + remaining) to Unit
        }
    }

    internal suspend fun rememberMedia(scope: PublicMetaDbAuthScope, media: PublicMetaDbMedia) {
        mutate(scope) { snapshot ->
            val existing = snapshot.media[media.key]
            val merged = existing?.mergeMissing(media) ?: media
            if (merged == existing) return@mutate snapshot to Unit
            snapshot.copy(media = snapshot.media + (media.key to merged)) to Unit
        }
    }

    internal suspend fun dismiss(scope: PublicMetaDbAuthScope, keys: Map<String, Long>) {
        if (keys.isEmpty()) return
        mutate(scope) { snapshot -> snapshot.copy(dismissed = snapshot.dismissed + keys) to Unit }
    }

    internal suspend fun upsertLocalPlayback(scope: PublicMetaDbAuthScope, entry: WatchProgressEntry) {
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

    internal suspend fun removeLocalPlayback(scope: PublicMetaDbAuthScope, entries: Collection<WatchProgressEntry>) {
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

    private fun schedulePersist(scope: PublicMetaDbAuthScope, delayMs: Long) {
        persistJob?.cancel()
        persistJob = coroutineScope.launch {
            if (delayMs > 0L) delay(delayMs)
            try {
                withContext(Dispatchers.Default) {
                    mutex.withLock {
                        if (!matchesCurrent(scope)) return@withLock
                        val snapshot = mutableState.value.snapshot ?: return@withLock
                        val payload = publicMetaDbJson.encodeToString(
                            PublicMetaDbSyncSnapshot.serializer(),
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

    private suspend fun fetchLists(scope: PublicMetaDbAuthScope): List<PublicMetaDbList> =
        api.paged(scope, "/api/external/lists", LIST_PAGE_SIZE)
            .mapNotNull(::parseList)
            .distinctBy(PublicMetaDbList::id)

    private suspend fun fetchListItems(scope: PublicMetaDbAuthScope, listId: String): List<PublicMetaDbListItem> =
        api.paged(scope, "/api/external/lists/${PublicMetaDbApiClient.pathSegment(listId)}/items", ITEM_PAGE_SIZE)
            .mapNotNull(::parseListItem)
            .distinctBy { publicMetaDbMediaKey(it.mediaType, it.tmdbId) }

    private suspend fun fetchWatched(scope: PublicMetaDbAuthScope): List<PublicMetaDbWatchedEntry> =
        dedupePublicMetaDbWatched(
            api.paged(scope, "/api/external/watched", ITEM_PAGE_SIZE).mapNotNull(::parseWatched),
        )

    private suspend fun fetchResume(scope: PublicMetaDbAuthScope): List<PublicMetaDbResumePoint>? = try {
        api.paged(scope, "/api/external/resume", RESUME_PAGE_SIZE)
            .mapNotNull(::parseResume)
            .groupBy { "${publicMetaDbMediaKey(it.mediaType, it.tmdbId)}|${it.season}|${it.episode}" }
            .map { (_, group) -> group.maxBy(PublicMetaDbResumePoint::updatedAtEpochMs) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: PublicMetaDbAuthException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun wantedMedia(
        listItems: List<PublicMetaDbListItem>,
        watched: List<PublicMetaDbWatchedEntry>,
        resume: List<PublicMetaDbResumePoint>,
        ratings: List<PublicMetaDbRatingEntry>,
    ): Map<String, PublicMetaDbMedia> {
        val wanted = linkedMapOf<String, PublicMetaDbMedia>()
        fun add(seed: PublicMetaDbMedia) {
            wanted[seed.key] = wanted[seed.key]?.mergeMissing(seed) ?: seed
        }
        listItems.forEach { item ->
            add(
                PublicMetaDbMedia(
                    tmdbId = item.tmdbId,
                    mediaType = normalizePublicMetaDbMediaType(item.mediaType),
                    title = item.title,
                    year = item.year,
                    posterPath = item.posterPath,
                ),
            )
        }
        resume.forEach { point ->
            add(
                PublicMetaDbMedia(
                    tmdbId = point.tmdbId,
                    mediaType = normalizePublicMetaDbMediaType(point.mediaType),
                    title = point.title,
                    posterPath = point.posterPath,
                ),
            )
        }
        watched.forEach { entry ->
            add(PublicMetaDbMedia(tmdbId = entry.tmdbId, mediaType = normalizePublicMetaDbMediaType(entry.mediaType)))
        }
        ratings.forEach { rating ->
            add(
                PublicMetaDbMedia(
                    tmdbId = rating.tmdbId,
                    mediaType = normalizePublicMetaDbMediaType(rating.mediaType),
                    imdb = rating.imdb,
                ),
            )
        }
        return wanted
    }

    private suspend fun enrichMedia(
        scope: PublicMetaDbAuthScope,
        previous: Map<String, PublicMetaDbMedia>,
        wanted: Map<String, PublicMetaDbMedia>,
    ): Map<String, PublicMetaDbMedia> {
        val result = linkedMapOf<String, PublicMetaDbMedia>()
        wanted.forEach { (key, seed) -> result[key] = previous[key]?.mergeMissing(seed) ?: seed }
        val nowMs = now()
        val pending = result.values
            .filter { !it.complete && nowMs - it.resolvedAtEpochMs !in 0 until MEDIA_RETRY_MS }
            .take(MAX_MEDIA_LOOKUPS_PER_REFRESH)
        pending.chunked(MEDIA_LOOKUP_PARALLELISM).forEach { chunk ->
            checkScope(scope)
            val fetched = withContext(Dispatchers.Default) {
                chunk.map { media -> async { fetchTmdbMedia(media) } }.awaitAll()
            }
            fetched.forEach { media -> result[media.key] = media }
        }
        return result
    }

    internal suspend fun fetchTmdbMedia(media: PublicMetaDbMedia): PublicMetaDbMedia {
        val resolvedAt = now()
        val root = try {
            val endpoint = if (media.isMovie) "movie/${media.tmdbId}" else "tv/${media.tmdbId}"
            val url = buildTmdbUrl(
                endpoint = endpoint,
                apiKey = TmdbSettingsRepository.effectiveApiKey(),
                query = mapOf("append_to_response" to "external_ids"),
            )
            publicMetaDbJson.parseToJsonElement(httpGetText(url)) as? JsonObject
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return media.copy(resolvedAtEpochMs = resolvedAt)
        val externalIds = root["external_ids"].publicMetaDbObject()
        val imdb = (externalIds?.publicMetaDbString("imdb_id") ?: root.publicMetaDbString("imdb_id"))
            ?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val tvdb = externalIds?.publicMetaDbLong("tvdb_id")?.takeIf { it > 0L }
        val genres = root["genres"].publicMetaDbArray().mapNotNull { genre -> genre.publicMetaDbObject()?.publicMetaDbString("name") }
        val runtime = root.publicMetaDbInt("runtime")?.takeIf { it > 0 }
            ?: root["episode_run_time"].publicMetaDbArray().firstNotNullOfOrNull { value ->
                (value as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }
            }
            ?: root["last_episode_to_air"].publicMetaDbObject()?.publicMetaDbInt("runtime")?.takeIf { it > 0 }
        val year = root.publicMetaDbFirstString("release_date", "first_air_date")?.take(4)?.toIntOrNull()?.takeIf { it in 1870..2200 }
        return media.copy(
            title = root.publicMetaDbFirstString("title", "name") ?: media.title,
            year = year ?: media.year,
            imdb = imdb ?: media.imdb,
            tvdb = tvdb ?: media.tvdb,
            posterPath = root.publicMetaDbString("poster_path") ?: media.posterPath,
            backdropPath = root.publicMetaDbString("backdrop_path") ?: media.backdropPath,
            genres = genres.ifEmpty { media.genres },
            runtimeMinutes = runtime ?: media.runtimeMinutes,
            resolvedAtEpochMs = resolvedAt,
            complete = true,
        )
    }

    private fun parseList(row: JsonObject): PublicMetaDbList? {
        val id = row.publicMetaDbString("id") ?: return null
        return PublicMetaDbList(
            id = id,
            name = row.publicMetaDbFirstString("name", "title") ?: PublicMetaDbConfig.DEFAULT_WATCHLIST_NAME,
            description = row.publicMetaDbString("description"),
            type = row.publicMetaDbString("type")?.lowercase() ?: PublicMetaDbListType.CUSTOM,
            isPublic = row.publicMetaDbBoolean("is_public") ?: row.publicMetaDbBoolean("isPublic") ?: false,
            itemCount = row.publicMetaDbFirstInt("item_count", "items_count", "itemCount") ?: 0,
            createdAtEpochMs = row.publicMetaDbEpochMs("created", "created_at") ?: 0L,
        )
    }

    private fun parseListItem(row: JsonObject): PublicMetaDbListItem? {
        val rawType = row.publicMetaDbFirstString("media_type", "mediaType")
            ?: row["media"].publicMetaDbObject()?.publicMetaDbFirstString("media_type", "type")
        val mediaType = normalizePublicMetaDbMediaType(rawType)
        val tmdbId = row.publicMetaDbTmdbId(mediaType) ?: return null
        val media = row["media"].publicMetaDbObject()
        return PublicMetaDbListItem(
            id = row.publicMetaDbFirstString("id", "item_id"),
            tmdbId = tmdbId,
            mediaType = mediaType,
            title = row.publicMetaDbFirstString("title", "name") ?: media?.publicMetaDbFirstString("title", "name"),
            year = row.publicMetaDbInt("year")
                ?: row.publicMetaDbFirstString("release_date", "first_air_date")?.take(4)?.toIntOrNull(),
            posterPath = row.publicMetaDbFirstString("poster", "poster_path") ?: media?.publicMetaDbFirstString("poster", "poster_path"),
            addedAtEpochMs = row.publicMetaDbEpochMs("added_at", "created", "created_at", "updated") ?: 0L,
        )
    }

    private fun parseWatched(row: JsonObject): PublicMetaDbWatchedEntry? {
        val season = row.publicMetaDbFirstInt("season", "season_number")
        val episode = row.publicMetaDbFirstInt("episode", "episode_number")
        val rawType = row.publicMetaDbFirstString("media_type", "mediaType")
        val mediaType = when {
            rawType != null -> normalizePublicMetaDbMediaType(rawType)
            episode != null -> PublicMetaDbMediaType.TV
            else -> PublicMetaDbMediaType.MOVIE
        }
        val tmdbId = row.publicMetaDbTmdbId(mediaType) ?: return null
        val isMovie = mediaType == PublicMetaDbMediaType.MOVIE
        if (!isMovie && (season == null || episode == null || episode <= 0 || season < 0)) return null
        return PublicMetaDbWatchedEntry(
            id = row.publicMetaDbString("id"),
            tmdbId = tmdbId,
            mediaType = mediaType,
            season = if (isMovie) null else season,
            episode = if (isMovie) null else episode,
            watchedAtEpochMs = row.publicMetaDbEpochMs("watched_at", "watchedAt", "created", "updated") ?: 0L,
        )
    }

    private fun parseResume(row: JsonObject): PublicMetaDbResumePoint? {
        val rawType = row.publicMetaDbFirstString("media_type", "mediaType")
        val season = row.publicMetaDbFirstInt("season", "season_number")
        val episode = row.publicMetaDbFirstInt("episode", "episode_number")
        val mediaType = when {
            rawType != null -> normalizePublicMetaDbMediaType(rawType)
            episode != null && episode > 0 -> PublicMetaDbMediaType.TV
            else -> PublicMetaDbMediaType.MOVIE
        }
        val tmdbId = row.publicMetaDbTmdbId(mediaType) ?: return null
        val isMovie = mediaType == PublicMetaDbMediaType.MOVIE
        if (!isMovie && (season == null || episode == null || episode <= 0)) return null
        val position = row.publicMetaDbLong("position_ms") ?: row.publicMetaDbLong("progress_ms") ?: 0L
        val runtime = row.publicMetaDbLong("runtime_ms") ?: row.publicMetaDbLong("duration_ms") ?: 0L
        val rawProgress = row.publicMetaDbDouble("progress")
        val percent = when {
            runtime > 0L && position > 0L -> position.toDouble() * 100.0 / runtime.toDouble()
            rawProgress != null && rawProgress <= 1.0 -> rawProgress * 100.0
            rawProgress != null -> rawProgress
            else -> 0.0
        }.coerceIn(0.0, 100.0)
        return PublicMetaDbResumePoint(
            id = row.publicMetaDbString("id"),
            tmdbId = tmdbId,
            mediaType = mediaType,
            season = if (isMovie) null else season,
            episode = if (isMovie) null else episode,
            positionMs = position,
            runtimeMs = runtime,
            progressPercent = percent,
            updatedAtEpochMs = row.publicMetaDbEpochMs("updated", "updated_at", "created", "created_at") ?: 0L,
            title = row.publicMetaDbFirstString("title", "name"),
            posterPath = row.publicMetaDbFirstString("poster", "poster_path"),
        )
    }

    private suspend fun loadCurrent(): PublicMetaDbSyncSnapshot? {
        val scope = auth.scope()
        if (!matchesCurrent(scope)) throw CancellationException("PublicMetaDB account changed")
        val authorization = auth.state.value
        if (!authorization.isAuthenticated) {
            clearPublished()
            storage.remove(scope.profileId) { if (!matchesCurrent(scope)) throw CancellationException("PublicMetaDB account changed") }
            return null
        }
        val accountKey = authorization.keyHint.orEmpty()
        val current = mutableState.value
        if (current.scope == scope && current.snapshot != null &&
            (accountKey.isEmpty() || current.snapshot.accountKey.isEmpty() || current.snapshot.accountKey == accountKey)
        ) {
            return current.snapshot
        }
        val payload = storage.load(scope.profileId)
        val snapshot = payload
            ?.let { runCatching { publicMetaDbJson.decodeFromString(PublicMetaDbSyncSnapshot.serializer(), it) }.getOrNull() }
            ?.takeIf { accountKey.isEmpty() || it.accountKey.isEmpty() || it.accountKey == accountKey }
            ?: PublicMetaDbSyncSnapshot(accountKey = accountKey)
        if (!matchesCurrent(scope)) throw CancellationException("PublicMetaDB account changed")
        val projection = runCatching { PublicMetaDbProjection(snapshot) }.getOrNull()
        val usable = if (projection != null) snapshot else PublicMetaDbSyncSnapshot(accountKey = accountKey)
        mutableProjection.value = PublicMetaDbProjectionState(scope, projection ?: PublicMetaDbProjection(usable))
        mutableLocalPlayback.value = usable.localPlayback
            .filter { now() - it.lastUpdatedEpochMs < LOCAL_PLAYBACK_TTL_MS }
            .take(MAX_LOCAL_PLAYBACK)
        localPlaybackScope = scope
        mutableState.value = PublicMetaDbSyncState(scope = scope, snapshot = usable)
        return usable
    }

    private suspend fun commit(scope: PublicMetaDbAuthScope, snapshot: PublicMetaDbSyncSnapshot) {
        checkScope(scope)
        val previous = mutableProjection.value
        val projection = if (
            previous.scope == scope &&
            previous.projection.snapshot == snapshot.copy(localPlayback = previous.projection.snapshot.localPlayback)
        ) {
            previous.projection
        } else {
            PublicMetaDbProjection(snapshot)
        }
        persistJob?.cancel()
        val payload = publicMetaDbJson.encodeToString(
            PublicMetaDbSyncSnapshot.serializer(),
            snapshot.copy(localPlayback = if (localPlaybackScope == scope) mutableLocalPlayback.value else snapshot.localPlayback),
        )
        storage.save(scope.profileId, payload) { checkScope(scope) }
        checkScope(scope)
        mutableProjection.value = PublicMetaDbProjectionState(scope, projection)
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

    private fun checkScope(scope: PublicMetaDbAuthScope) {
        if (!matchesCurrent(scope)) throw CancellationException("PublicMetaDB account changed")
        if (!auth.state.value.isAuthenticated) throw PublicMetaDbAuthException(PublicMetaDbAuthError.AUTHORIZATION_REVOKED)
    }

    private fun matchesCurrent(scope: PublicMetaDbAuthScope?): Boolean =
        scope != null && scope.profileId == activeProfileId.value && auth.isCurrent(scope)

    private fun clearPublished() {
        persistJob?.cancel()
        mutableProjection.value = PublicMetaDbProjectionState()
        mutableLocalPlayback.value = emptyList()
        localPlaybackScope = null
        mutableState.value = PublicMetaDbSyncState()
    }

    private fun WatchProgressEntry.localKey(): String =
        "${parentMetaId.lowercase()}|${seasonNumber ?: -1}|${episodeNumber ?: -1}"

    companion object {
        const val AUTOMATIC_INTERVAL_MS = 10L * 60L * 1_000L
        const val ERROR_RETRY_MS = 60_000L
        const val LOCAL_PLAYBACK_TTL_MS = 60L * 24L * 60L * 60L * 1_000L
        const val MAX_LOCAL_PLAYBACK = 200
        const val LOCAL_PERSIST_DELAY_MS = 30_000L
        private const val LIST_PAGE_SIZE = 100
        private const val ITEM_PAGE_SIZE = 500
        private const val RESUME_PAGE_SIZE = 100
        private const val MEDIA_RETRY_MS = 3L * 24L * 60L * 60L * 1_000L
        private const val MAX_MEDIA_LOOKUPS_PER_REFRESH = 300
        private const val MEDIA_LOOKUP_PARALLELISM = 6
    }
}

internal fun publicMetaDbShowKey(tmdbId: Long): String = "tv:$tmdbId"

internal fun dedupePublicMetaDbWatched(entries: List<PublicMetaDbWatchedEntry>): List<PublicMetaDbWatchedEntry> =
    entries
        .groupBy { "${publicMetaDbMediaKey(it.mediaType, it.tmdbId)}|${it.season ?: -1}|${it.episode ?: -1}" }
        .map { (_, group) -> group.maxBy(PublicMetaDbWatchedEntry::watchedAtEpochMs) }
        .sortedByDescending(PublicMetaDbWatchedEntry::watchedAtEpochMs)

internal fun PublicMetaDbRatingEntry.sameTargetAs(other: PublicMetaDbRatingEntry): Boolean =
    tmdbId == other.tmdbId &&
        isPublicMetaDbMovieType(mediaType) == isPublicMetaDbMovieType(other.mediaType) &&
        season == other.season &&
        episode == other.episode
