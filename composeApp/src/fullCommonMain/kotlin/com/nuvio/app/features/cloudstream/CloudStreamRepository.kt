package com.nuvio.app.features.cloudstream

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetBytesWithHeaders
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.plugins.currentEpochMillis
import com.nuvio.app.core.diagnostics.RuntimeDiagnostics
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.util.LinkedHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

actual object CloudStreamRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("CloudStreamRepo")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(CloudStreamUiState())
    actual val uiState: StateFlow<CloudStreamUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var currentProfileId = 1
    private val refreshJobs = mutableMapOf<String, Job>()
    private val mainPageRequestMutex = Any()
    private val mainPageRequests =
        mutableMapOf<String, Deferred<Result<List<Pair<String, List<CloudStreamSearchItem>>>>>>()
    private val loadLinksRequestMutex = Mutex()
    private val loadLinksRequests =
        mutableMapOf<String, Deferred<Result<List<CloudStreamPlaybackSource>>>>()
    private val loadLinksCache = LinkedHashMap<String, CachedCloudStreamLinks>(16, 0.75f, true)
    private val loadLinksCacheMutex = Mutex()
    private val loadResultCache = LinkedHashMap<String, CloudStreamLoadItem>(16, 0.75f, true)
    private val loadResultCacheMutex = Mutex()
    private const val LOAD_LINKS_CACHE_MAX_ENTRIES = 16
    private const val LOAD_LINKS_CACHE_TTL_MS = 120_000L
    private const val LOAD_RESULT_CACHE_MAX_ENTRIES = 16
    private const val REPOSITORY_DISCOVERY_CONCURRENCY = 6
    private const val PROVIDER_REQUEST_CONCURRENCY = 4

    actual fun initialize() {
        val profileId = ProfileRepository.activeProfileId.coerceAtLeast(1)
        if (initialized && currentProfileId == profileId) return
        currentProfileId = profileId
        CloudStreamPlatformStorage.setActiveProfile(profileId)
        initialized = true
        _uiState.value = restoreState(profileId)

    }

    actual fun onProfileChanged(profileId: Int) {
        refreshJobs.values.forEach { it.cancel() }
        refreshJobs.clear()
        cancelInFlightMainPageRequests()
        cancelInFlightLoadLinksRequests()
        loadLinksCacheMutex.tryLock().let { locked ->
            if (locked) {
                try { loadLinksCache.clear() } finally { loadLinksCacheMutex.unlock() }
            }
        }
        loadResultCacheMutex.tryLock().let { locked ->
            if (locked) {
                try { loadResultCache.clear() } finally { loadResultCacheMutex.unlock() }
            }
        }
        CloudStreamPlatformRuntime.clear()
        currentProfileId = profileId.coerceAtLeast(1)
        CloudStreamPlatformStorage.setActiveProfile(currentProfileId)
        initialized = false
        _uiState.value = CloudStreamUiState()
    }

    actual fun clearLocalState() {
        refreshJobs.values.forEach { it.cancel() }
        refreshJobs.clear()
        initialized = false
        currentProfileId = 1
        _uiState.value = CloudStreamUiState()
        cancelInFlightMainPageRequests()
        cancelInFlightLoadLinksRequests()
        loadLinksCacheMutex.tryLock().let { locked ->
            if (locked) {
                try { loadLinksCache.clear() } finally { loadLinksCacheMutex.unlock() }
            }
        }
        loadResultCacheMutex.tryLock().let { locked ->
            if (locked) {
                try { loadResultCache.clear() } finally { loadResultCacheMutex.unlock() }
            }
        }
        CloudStreamPlatformRuntime.clear()
        CloudStreamPlatformStorage.clearPackages()
        CloudStreamPlatformStorage.clearAllState()
    }

    actual fun acceptSecurityWarning() {
        initialize()
        _uiState.update { it.copy(securityWarningAccepted = true) }
        persist()
    }

    actual suspend fun addRepository(rawUrl: String): AddCloudStreamRepositoryResult {
        initialize()
        val manifestUrl = runCatching { resolveCloudStreamRepositoryInput(rawUrl) }
            .getOrElse { return AddCloudStreamRepositoryResult.Error(it.message ?: "Invalid repository URL") }
        if (_uiState.value.repositories.any { it.manifest.sourceUrl == manifestUrl }) {
            return AddCloudStreamRepositoryResult.Error("CloudStream repository is already added")
        }

        return runCatching { fetchRepository(manifestUrl) }
            .fold(
                onSuccess = { (repository, plugins) ->
                    _uiState.update { current ->
                        current.copy(
                            repositories = current.repositories + CloudStreamRepositoryItem(repository),
                            plugins = (current.plugins + plugins.map(::newPluginItem))
                                .distinctBy { it.metadata.id.value }
                                .sortedBy { it.metadata.name.lowercase() },
                        )
                    }
                    CloudStreamPlatformRuntime.registerNativeRepository(
                        url = manifestUrl,
                        name = repository.name,
                        iconUrl = repository.iconUrl,
                    )
                    persist()
                    AddCloudStreamRepositoryResult.Success(repository)
                },
                onFailure = { error ->
                    log.w(error) { "CloudStream repository install failed url=$manifestUrl" }
                    AddCloudStreamRepositoryResult.Error(error.message ?: "Could not load CloudStream repository")
                },
            )
    }

    actual suspend fun discoverRepositories(rawInput: String): Result<Int> {
        initialize()
        val input = rawInput.trim()
        if (input.isBlank()) return Result.failure(IllegalArgumentException("Repository input is empty"))

        return runCatching {
            val databaseUrl = when {
                input.equals("MegaProvider", ignoreCase = true) ||
                    input.equals("MegaRepo", ignoreCase = true) ||
                    input.contains("self-similarity/MegaRepo", ignoreCase = true) ||
                    input.contains("cs-repos/master/repos-db.json", ignoreCase = true) ->
                    "https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json"
                else -> null
            }

            val repositoryUrls = if (databaseUrl != null) {
                json.parseToJsonElement(httpGetText(databaseUrl)).jsonArray.mapNotNull { element ->
                    when {
                        element is kotlinx.serialization.json.JsonPrimitive -> element.contentOrNull
                        element is kotlinx.serialization.json.JsonObject -> element["url"]?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                }.map(String::trim).filter(String::isNotBlank).distinct()
            } else {
                listOf(resolveCloudStreamRepositoryInput(input))
            }

            val manifests = coroutineScope {
                val gate = Semaphore(REPOSITORY_DISCOVERY_CONCURRENCY)
                repositoryUrls.map { url ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            runCatching {
                                CloudStreamRepositoryParser.parseRepository(url, httpGetText(url))
                            }.getOrNull()
                        }
                    }
                }.mapNotNull { it.await() }
            }.distinctBy { it.sourceUrl }

            _uiState.update { current ->
                current.copy(
                    discoveredRepositories = manifests.filterNot { discovered ->
                        current.repositories.any { it.manifest.sourceUrl == discovered.sourceUrl }
                    },
                )
            }
            log.i { "CloudStream repository discovery found " + manifests.size + " repositories" }
            manifests.size
        }
    }

    actual fun refreshRepository(manifestUrl: String) {
        initialize()
        val normalizedUrl = runCatching { normalizeCloudStreamRepositoryUrl(manifestUrl) }.getOrNull() ?: return
        if (refreshJobs[normalizedUrl]?.isActive == true) return
        _uiState.update { current ->
            current.copy(
                repositories = current.repositories.map { item ->
                    if (item.manifest.sourceUrl == normalizedUrl) item.copy(isRefreshing = true, errorMessage = null) else item
                },
            )
        }
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runCatching { fetchRepository(normalizedUrl) }
                    .fold(
                        onSuccess = { (repository, plugins) -> applyRepositoryRefresh(repository, plugins) },
                        onFailure = { error ->
                            log.w(error) { "CloudStream repository refresh failed url=$normalizedUrl" }
                            _uiState.update { current ->
                                current.copy(
                                    repositories = current.repositories.map { item ->
                                        if (item.manifest.sourceUrl == normalizedUrl) {
                                            item.copy(isRefreshing = false, errorMessage = error.message ?: "Refresh failed")
                                        } else item
                                    },
                                )
                            }
                        },
                    )
                persist()
            } finally {
                if (refreshJobs[normalizedUrl] === job) refreshJobs.remove(normalizedUrl)
            }
        }
        refreshJobs[normalizedUrl] = job
        job.start()
    }

    actual fun refreshAll() {
        initialize()
        _uiState.value.repositories.forEach { refreshRepository(it.manifest.sourceUrl) }
    }

    actual fun removeRepository(manifestUrl: String) {
        initialize()
        val normalizedUrl = runCatching { normalizeCloudStreamRepositoryUrl(manifestUrl) }.getOrNull() ?: return
        val removedPlugins = _uiState.value.plugins.filter { it.metadata.repositoryManifestUrl == normalizedUrl }
        removedPlugins.forEach {
            CloudStreamPlatformRuntime.unload(it.metadata.id.value)
            CloudStreamPlatformStorage.deletePackage(it.metadata.id.storageKey)
        }
        scope.launch {
            CloudStreamPlatformRuntime.removeNativeRepository(normalizedUrl)
        }
        _uiState.update { current ->
            current.copy(
                repositories = current.repositories.filterNot { it.manifest.sourceUrl == normalizedUrl },
                plugins = current.plugins.filterNot { it.metadata.repositoryManifestUrl == normalizedUrl },
                registryRevision = current.registryRevision + 1,
            )
        }
        persist()
    }

    actual suspend fun installPlugin(pluginId: String): CloudStreamInstallResult = installOrUpdate(pluginId)

    actual suspend fun updatePlugin(pluginId: String): CloudStreamInstallResult = installOrUpdate(pluginId)

    actual suspend fun installAndEnablePlugins(pluginIds: List<String>): CloudStreamBulkInstallResult {
        initialize()
        val requestedIds = pluginIds.distinct()
        if (requestedIds.isEmpty()) {
            return CloudStreamBulkInstallResult(
                requestedCount = 0,
                installedCount = 0,
                enabledCount = 0,
                skippedCount = 0,
            )
        }
        if (!_uiState.value.securityWarningAccepted) {
            return CloudStreamBulkInstallResult(
                requestedCount = requestedIds.size,
                installedCount = 0,
                enabledCount = 0,
                skippedCount = 0,
                failures = listOf(
                    CloudStreamBulkInstallFailure(
                        pluginName = "CloudStream",
                        message = "Accept the third-party plugin security warning before installation",
                    ),
                ),
            )
        }

        var installedCount = 0
        var enabledCount = 0
        var skippedCount = 0
        val failures = mutableListOf<CloudStreamBulkInstallFailure>()

        requestedIds.forEach { pluginId ->
            val before = _uiState.value.plugins.firstOrNull { it.metadata.id.value == pluginId }
            if (before == null) {
                failures += CloudStreamBulkInstallFailure(pluginName = pluginId, message = "CloudStream plugin was not found")
                return@forEach
            }
            if (before.isInstalling) {
                skippedCount += 1
                return@forEach
            }
            if (!before.metadata.status.canInstall) {
                failures += CloudStreamBulkInstallFailure(
                    pluginName = before.metadata.name,
                    message = "This CloudStream plugin is marked as down",
                )
                return@forEach
            }
            if (!before.compatibility.isRunnable) {
                failures += CloudStreamBulkInstallFailure(
                    pluginName = before.metadata.name,
                    message = before.compatibility.reason,
                )
                return@forEach
            }

            val needsInstall = !before.isInstalled || before.hasUpdate || !before.verified
            val installResult = if (needsInstall) installOrUpdate(pluginId) else CloudStreamInstallResult.Success(before)
            when (installResult) {
                is CloudStreamInstallResult.Success -> {
                    if (needsInstall) installedCount += 1
                    setPluginEnabled(pluginId, true)
                    val after = _uiState.value.plugins.firstOrNull { it.metadata.id.value == pluginId }
                    if (after?.isRunnable == true) {
                        enabledCount += 1
                    } else {
                        failures += CloudStreamBulkInstallFailure(
                            pluginName = installResult.plugin.metadata.name,
                            message = "The package was installed, but could not be enabled because verification or compatibility checks failed.",
                        )
                    }
                }
                is CloudStreamInstallResult.Error -> {
                    failures += CloudStreamBulkInstallFailure(
                        pluginName = before.metadata.name,
                        message = installResult.message,
                    )
                }
            }
        }

        return CloudStreamBulkInstallResult(
            requestedCount = requestedIds.size,
            installedCount = installedCount,
            enabledCount = enabledCount,
            skippedCount = skippedCount,
            failures = failures,
        )
    }

    actual fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        initialize()
        RuntimeDiagnostics.recordLog("cs-provider-enabled id=$pluginId enabled=$enabled")
        _uiState.update { current ->
            var changed = false
            val plugins = current.plugins.map { item ->
                if (item.metadata.id.value != pluginId) return@map item
                val nextEnabled = enabled && item.isInstalled && item.verified && item.compatibility.isRunnable &&
                    item.metadata.status.canInstall
                changed = item.enabled != nextEnabled
                item.copy(enabled = nextEnabled, errorMessage = null)
            }
            current.copy(
                plugins = plugins,
                registryRevision = current.registryRevision + if (changed) 1 else 0,
            )
        }
        if (!enabled) {
            CloudStreamPlatformRuntime.unload(pluginId)
        } else {
            val enabledPlugin = _uiState.value.plugins.firstOrNull { it.metadata.id.value == pluginId }
            if (enabledPlugin?.isRunnable == true) {
            }
        }
        persist()
    }

    actual fun removePlugin(pluginId: String) {
        initialize()
        val plugin = _uiState.value.plugins.firstOrNull { it.metadata.id.value == pluginId } ?: return
        CloudStreamPlatformRuntime.unload(pluginId)
        CloudStreamPlatformStorage.deletePackage(plugin.metadata.id.storageKey)
        _uiState.update { current ->
            current.copy(
                plugins = current.plugins.map { item ->
                    if (item.metadata.id.value == pluginId) {
                        item.copy(
                            installedVersion = null,
                            installedAtEpochMs = 0L,
                            enabled = false,
                            verified = false,
                            errorMessage = null,
                        )
                    } else item
                },
                registryRevision = current.registryRevision + 1,
            )
        }
        persist()
    }

    actual suspend fun getMainPage(
        providerId: String,
        page: Int,
    ): Result<List<Pair<String, List<CloudStreamSearchItem>>>> {
        val normalizedPage = page.coerceAtLeast(1)
        val requestKey = providerId + ":" + normalizedPage
        RuntimeDiagnostics.recordLog("cs-mainpage-start id=$providerId page=$normalizedPage")

        val request = synchronized(mainPageRequestMutex) {
            mainPageRequests[requestKey]?.takeIf { it.isActive || it.isCompleted }
                ?: scope.async(start = CoroutineStart.LAZY) {
                    providerResult(providerId) { getMainPage(normalizedPage) }
                }.also { deferred ->
                    mainPageRequests[requestKey] = deferred
                    deferred.invokeOnCompletion {
                        synchronized(mainPageRequestMutex) {
                            if (mainPageRequests[requestKey] === deferred) {
                                mainPageRequests.remove(requestKey)
                            }
                        }
                    }
                }
        }

        return request.await()
            .onSuccess { sections ->
                RuntimeDiagnostics.recordLog(
                    "cs-mainpage-success id=$providerId sections=${sections.size} items=${sections.sumOf { it.second.size }}",
                )
            }
            .onFailure { error ->
                RuntimeDiagnostics.recordLog(
                    "cs-mainpage-failure id=$providerId error=${error.message?.take(160)}",
                )
            }
    }
    private fun cancelInFlightMainPageRequests() {
        val requests = synchronized(mainPageRequestMutex) {
            mainPageRequests.values.toList().also { mainPageRequests.clear() }
        }
        requests.forEach { it.cancel() }
    }

    actual suspend fun search(
        query: String,
        providerId: String?,
    ): List<Result<List<CloudStreamSearchItem>>> {
        initialize()
        val activeIds = runnableProviderIds().filter { providerId == null || it == providerId }
        val startedAt = currentEpochMillis()
        RuntimeDiagnostics.recordLog("cs-search-start providers=${activeIds.size}")
        val results = coroutineScope {
            val gate = Semaphore(PROVIDER_REQUEST_CONCURRENCY)
            activeIds.map { id ->
                async {
                    gate.withPermit {
                        RuntimeDiagnostics.recordLog("cs-search-provider-start id=$id")
                        providerResult(id) { search(query.trim()) }
                            .onSuccess { items -> RuntimeDiagnostics.recordLog("cs-search-provider-success id=$id count=${items.size}") }
                            .onFailure { error -> RuntimeDiagnostics.recordLog("cs-search-provider-failure id=$id error=${error.message?.take(160)}") }
                    }
                }
            }.map { it.await() }
        }
        RuntimeDiagnostics.recordLog("cs-search-complete providers=${activeIds.size} successful=${results.count { it.isSuccess }} results=${results.sumOf { it.getOrNull()?.size ?: 0 }} durationMs=${currentEpochMillis() - startedAt}")
        return results
    }

    actual suspend fun load(providerId: String, data: String): Result<CloudStreamLoadItem> {
        initialize()
        val cacheKey = "$providerId\\u0000$data"
        loadResultCacheMutex.withLock {
            loadResultCache[cacheKey]
        }?.let { cached ->
            RuntimeDiagnostics.recordLog("cs-load-cache-hit id=$providerId dataLength=${data.length}")
            return Result.success(cached)
        }

        val normalizedData = data.trimStart()
        val dataKind = when {
            normalizedData.startsWith("http://", ignoreCase = true) ||
                normalizedData.startsWith("https://", ignoreCase = true) -> "url"
            normalizedData.startsWith("[") -> "json-array"
            normalizedData.startsWith("{") -> "json-object"
            normalizedData.isBlank() -> "blank"
            else -> "other"
        }
        RuntimeDiagnostics.recordLog("cs-load-start id=$providerId dataKind=$dataKind dataLength=${data.length}")
        return providerResult(providerId) { load(data) }
            .onSuccess { item ->
                loadResultCacheMutex.withLock {
                    loadResultCache[cacheKey] = item
                    loadResultCache["$providerId\\u0000${item.data}"] = item
                    while (loadResultCache.size > LOAD_RESULT_CACHE_MAX_ENTRIES) {
                        loadResultCache.remove(loadResultCache.entries.first().key)
                    }
                }
                RuntimeDiagnostics.recordLog("cs-load-success id=$providerId episodes=${item.episodes.size}")
            }
            .onFailure { error ->
                RuntimeDiagnostics.recordLog("cs-load-failure id=$providerId dataKind=$dataKind error=${error.message?.take(160)}")
            }
    }

    actual suspend fun loadByExternalId(
        providerId: String,
        externalId: String,
    ): Result<CloudStreamLoadItem?> = providerResult(providerId) {
        loadByExternalId(externalId)
    }

    actual suspend fun loadLinks(providerId: String, data: String): Result<List<CloudStreamPlaybackSource>> {
        initialize()
        val requestKey = "$providerId\\u0000$data"
        val now = currentEpochMillis()
        loadLinksCacheMutex.withLock {
            loadLinksCache[requestKey]
                ?.takeIf { it.expiresAtEpochMs > now }
                ?.sources
        }?.let { cached ->
            RuntimeDiagnostics.recordLog("cs-load-links-cache-hit provider=$providerId links=${cached.size}")
            return Result.success(cached)
        }

        var created = false
        val request = loadLinksRequestMutex.withLock {
            loadLinksRequests[requestKey]
                ?: scope.async(start = CoroutineStart.LAZY) {
                    providerResult(providerId) { loadLinks(data) }
                }.also { deferred ->
                    loadLinksRequests[requestKey] = deferred
                    created = true
                }
        }
        if (!created) {
            RuntimeDiagnostics.recordLog("cs-load-links-coalesced provider=$providerId")
        }
        request.invokeOnCompletion {
            scope.launch {
                loadLinksRequestMutex.withLock {
                    if (loadLinksRequests[requestKey] === request) {
                        loadLinksRequests.remove(requestKey)
                    }
                }
            }
        }
        return request.await().onSuccess { sources ->
            loadLinksCacheMutex.withLock {
                loadLinksCache[requestKey] = CachedCloudStreamLinks(
                    sources = sources,
                    expiresAtEpochMs = currentEpochMillis() + LOAD_LINKS_CACHE_TTL_MS,
                )
                while (loadLinksCache.size > LOAD_LINKS_CACHE_MAX_ENTRIES) {
                    loadLinksCache.remove(loadLinksCache.entries.first().key)
                }
            }
        }
    }

    private data class CachedCloudStreamLinks(
    val sources: List<CloudStreamPlaybackSource>,
    val expiresAtEpochMs: Long,
)

private fun cancelInFlightLoadLinksRequests() {
        scope.launch {
            val requests = loadLinksRequestMutex.withLock {
                loadLinksRequests.values.toList().also { loadLinksRequests.clear() }
            }
            requests.forEach { it.cancel() }
        }
    }

    private suspend fun installOrUpdate(pluginId: String): CloudStreamInstallResult {
        initialize()
        if (!_uiState.value.securityWarningAccepted) {
            return CloudStreamInstallResult.Error("Accept the third-party plugin security warning before installation")
        }
        val item = _uiState.value.plugins.firstOrNull { it.metadata.id.value == pluginId }
            ?: return CloudStreamInstallResult.Error("CloudStream plugin was not found")
        if (!item.metadata.status.canInstall) {
            return CloudStreamInstallResult.Error("This CloudStream plugin is marked as down")
        }
        markInstalling(pluginId, true, null)
        return runCatching {
            val bytes = httpGetBytesWithHeaders(
                item.metadata.packageUrl,
                headers = mapOf("Accept" to "application/zip, application/octet-stream"),
            )
            require(bytes.isNotEmpty()) { "Downloaded .cs3 package is empty" }
            item.metadata.fileSize?.let { expectedSize ->
                if (bytes.size.toLong() != expectedSize) {
                    log.w {
                        "CloudStream package metadata size mismatch id=$pluginId expected=$expectedSize actual=${bytes.size}"
                    }
                }
            }
            CloudStreamPackageInspector.inspect(bytes)
            val expectedHash = item.metadata.fileHash
            val verified = expectedHash?.matches(bytes) ?: true
            if (expectedHash != null) require(verified) { ".cs3 SHA-256 hash mismatch" }
            CloudStreamPlatformRuntime.unload(pluginId)
            CloudStreamPlatformStorage.savePackageAtomically(item.metadata.id.storageKey, bytes)
            val installed = item.copy(
                installedVersion = item.metadata.version,
                installedAtEpochMs = currentEpochMillis(),
                enabled = item.enabled && verified && item.compatibility.isRunnable,
                verified = verified,
                isInstalling = false,
                errorMessage = null,
            )
            _uiState.update { current ->
                current.copy(
                    plugins = current.plugins.map { if (it.metadata.id.value == pluginId) installed else it },
                    registryRevision = current.registryRevision + 1,
                )
            }
            persist()
            installed
        }.fold(
            onSuccess = { CloudStreamInstallResult.Success(it) },
            onFailure = { error ->
                log.w(error) { "CloudStream plugin installation failed id=$pluginId" }
                markInstalling(pluginId, false, error.message ?: "Plugin installation failed")
                CloudStreamInstallResult.Error(error.message ?: "Plugin installation failed")
            },
        )
    }

    private suspend fun fetchRepository(
        manifestUrl: String,
    ): Pair<CloudStreamRepositoryManifest, List<CloudStreamPluginMetadata>> {
        var lastManifestError: Throwable? = null
        val manifest = cloudStreamManifestCandidates(manifestUrl).firstNotNullOfOrNull { candidate ->
            runCatching {
                CloudStreamRepositoryParser.parseRepository(candidate, httpGetText(candidate))
            }.onFailure { error ->
                lastManifestError = error
                log.w(error) { "CloudStream repository manifest failed url=$candidate" }
            }.getOrNull()
        } ?: throw lastManifestError ?: IllegalStateException("CloudStream repository manifest could not be loaded")
        var loadedListCount = 0
        val lists = manifest.pluginListUrls.mapNotNull { pluginListUrl ->
            runCatching {
                val listPayload = httpGetText(pluginListUrl)
                CloudStreamRepositoryParser.parsePluginList(manifest.sourceUrl, pluginListUrl, listPayload)
            }.onSuccess {
                loadedListCount += 1
            }.onFailure { error ->
                log.w(error) { "CloudStream plugin list failed url=$pluginListUrl" }
            }.getOrNull()
        }
        require(loadedListCount > 0) { "CloudStream repository plugin lists could not be loaded" }
        return manifest to CloudStreamRepositoryParser.mergePluginLists(lists)
    }

    private fun cloudStreamManifestCandidates(manifestUrl: String): List<String> {
        val normalized = normalizeCloudStreamRepositoryUrl(manifestUrl)
        val alternate = when {
            "/master/repo.json" in normalized -> normalized.replace("/master/repo.json", "/main/repo.json")
            "/main/repo.json" in normalized -> normalized.replace("/main/repo.json", "/master/repo.json")
            else -> null
        }
        return listOfNotNull(normalized, alternate).distinct()
    }

    private fun applyRepositoryRefresh(
        repository: CloudStreamRepositoryManifest,
        plugins: List<CloudStreamPluginMetadata>,
    ) {
        _uiState.update { current ->
            val previousById = current.plugins.associateBy { it.metadata.id.value }
            val refreshed = plugins.map { metadata ->
                previousById[metadata.id.value]?.copy(
                    metadata = metadata,
                    compatibility = resolveCompatibility(metadata),
                    enabled = previousById[metadata.id.value]?.enabled == true && metadata.status.canInstall,
                    errorMessage = null,
                ) ?: newPluginItem(metadata)
            }
            current.copy(
                repositories = current.repositories.map { item ->
                    if (item.manifest.sourceUrl == repository.sourceUrl) {
                        CloudStreamRepositoryItem(repository)
                    } else item
                },
                plugins = (current.plugins.filterNot { it.metadata.repositoryManifestUrl == repository.sourceUrl } + refreshed)
                    .sortedBy { it.metadata.name.lowercase() },
                registryRevision = current.registryRevision + 1,
            )
        }
    }

    private fun newPluginItem(metadata: CloudStreamPluginMetadata): CloudStreamPluginItem =
        CloudStreamPluginItem(
            metadata = metadata,
            compatibility = resolveCompatibility(metadata),
        )

    private fun markInstalling(pluginId: String, installing: Boolean, error: String?) {
        _uiState.update { current ->
            current.copy(
                plugins = current.plugins.map { item ->
                    if (item.metadata.id.value == pluginId) {
                        item.copy(isInstalling = installing, errorMessage = error)
                    } else item
                },
            )
        }
    }

    private fun restoreState(profileId: Int): CloudStreamUiState {
        val payload = CloudStreamPlatformStorage.loadState(profileId) ?: return CloudStreamUiState()
        val stored = runCatching { json.decodeFromString<StoredCloudStreamState>(payload) }
            .onFailure { log.w(it) { "Could not restore CloudStream state" } }
            .getOrNull() ?: return CloudStreamUiState()
        val repositories = stored.repositories.map { repository ->
            val manifestUrl = runCatching { normalizeCloudStreamRepositoryUrl(repository.manifestUrl) }
                .getOrDefault(repository.manifestUrl)
            CloudStreamRepositoryItem(
                CloudStreamRepositoryManifest(
                    sourceUrl = manifestUrl,
                    name = repository.name,
                    description = repository.description,
                    iconUrl = repository.iconUrl,
                    manifestVersion = repository.manifestVersion,
                    pluginListUrls = repository.pluginListUrls,
                ),
            )
        }
        val plugins = stored.plugins.map { plugin ->
            val metadata = plugin.toMetadata()
            val legacyStorageKey = CloudStreamPluginId(plugin.repositoryManifestUrl, plugin.internalName).storageKey
            val storageKey = metadata.id.storageKey
            val packageExists = plugin.installedVersion != null && (
                CloudStreamPlatformStorage.packageExists(storageKey) ||
                    (legacyStorageKey != storageKey &&
                        CloudStreamPlatformStorage.migratePackage(legacyStorageKey, storageKey))
                )
            val compatibility = resolveCompatibility(metadata)
            CloudStreamPluginItem(
                metadata = metadata,
                compatibility = compatibility,
                installedVersion = plugin.installedVersion.takeIf { packageExists },
                installedAtEpochMs = plugin.installedAtEpochMs.takeIf { packageExists } ?: 0L,
                enabled = plugin.enabled && packageExists && plugin.verified && compatibility.isRunnable && metadata.status.canInstall,
                verified = plugin.verified && packageExists,
            )
        }
        return CloudStreamUiState(
            repositories = repositories,
            plugins = plugins,
            registryRevision = 1,
            securityWarningAccepted = stored.securityWarningAccepted,
        )
    }

    private fun persist() {
        val current = _uiState.value
        val stored = StoredCloudStreamState(
            repositories = current.repositories.map { item ->
                StoredCloudStreamRepository(
                    manifestUrl = item.manifest.sourceUrl,
                    name = item.manifest.name,
                    description = item.manifest.description,
                    iconUrl = item.manifest.iconUrl,
                    manifestVersion = item.manifest.manifestVersion,
                    pluginListUrls = item.manifest.pluginListUrls,
                )
            },
            plugins = current.plugins.map { item -> item.toStored() },
            securityWarningAccepted = current.securityWarningAccepted,
        )
        CloudStreamPlatformStorage.saveState(currentProfileId, json.encodeToString(stored))
    }

    private fun CloudStreamPluginItem.toStored(): StoredCloudStreamPlugin = StoredCloudStreamPlugin(
        repositoryManifestUrl = metadata.repositoryManifestUrl,
        packageUrl = metadata.packageUrl,
        status = metadata.status.name,
        availableVersion = metadata.version,
        installedVersion = installedVersion,
        name = metadata.name,
        internalName = metadata.internalName,
        authors = metadata.authors,
        description = metadata.description,
        fileSize = metadata.fileSize,
        projectUrl = metadata.projectUrl,
        language = metadata.language,
        rawTvTypes = metadata.rawTvTypes,
        iconUrl = metadata.iconUrl,
        apiVersion = metadata.apiVersion,
        fileHash = metadata.fileHash?.wireValue,
        enabled = enabled,
        verified = verified,
        installedAtEpochMs = installedAtEpochMs.takeIf { installedVersion != null } ?: 0L,
    )

    private fun StoredCloudStreamPlugin.toMetadata(): CloudStreamPluginMetadata {
        val normalizedRepositoryUrl = runCatching { normalizeCloudStreamRepositoryUrl(repositoryManifestUrl) }
            .getOrDefault(repositoryManifestUrl)
        return CloudStreamPluginMetadata(
            id = CloudStreamPluginId(normalizedRepositoryUrl, internalName),
            repositoryManifestUrl = normalizedRepositoryUrl,
            packageUrl = packageUrl,
            status = runCatching { CloudStreamPluginStatus.valueOf(status) }
                .getOrDefault(CloudStreamPluginStatus.Unknown),
            version = availableVersion,
            name = name,
            internalName = internalName,
            authors = authors,
            description = description,
            fileSize = fileSize,
            projectUrl = projectUrl,
            language = language,
            tvTypes = rawTvTypes.map(CloudStreamTvType::fromWireValue).distinct(),
            rawTvTypes = rawTvTypes,
            iconUrl = iconUrl,
            apiVersion = apiVersion,
            fileHash = CloudStreamFileHash.parse(fileHash),
        )
    }

    private fun runnableProviderIds(): List<String> =
        _uiState.value.plugins.filter(CloudStreamPluginItem::isRunnable).map { it.metadata.id.value }

    private fun resolveCompatibility(metadata: CloudStreamPluginMetadata): CloudStreamCompatibility =
        CloudStreamCompatibilityResolver.resolve(
            metadata = metadata,
            supportsAndroidDex = CloudStreamPlatformRuntime.supportsAndroidDex,
        )

    private suspend fun <T> providerResult(
        providerId: String,
        block: suspend CloudStreamProvider.() -> T,
    ): Result<T> {
        initialize()
        if (providerId !in runnableProviderIds()) {
            return Result.failure(IllegalStateException("CloudStream provider is disabled or incompatible"))
        }
        return runCatching {
            val plugin = _uiState.value.plugins.firstOrNull { it.metadata.id.value == providerId }
                ?: error("CloudStream plugin was not found")
            val provider = CloudStreamProviderRegistry.find(providerId)
                ?: CloudStreamPlatformRuntime.provider(plugin)
                ?: error("CloudStream provider adapter was not found")
            provider.block()
        }
            .onFailure { log.w(it) { "CloudStream provider request failed id=$providerId" } }
    }

    private suspend fun resolveCloudStreamRepositoryInput(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val expanded = when {
            trimmed.startsWith("!", ignoreCase = false) ->
                resolveCloudStreamShortLink("https://py.md/${trimmed.removePrefix("!")}")
            trimmed.matches(Regex("^[a-zA-Z0-9!_-]+$")) ->
                resolveCloudStreamShortLink("https://cutt.ly/$trimmed")
            else -> trimmed
        }
        return normalizeCloudStreamRepositoryUrl(expanded)
    }

    private suspend fun resolveCloudStreamShortLink(url: String): String {
        val response = httpRequestRaw(
            method = "GET",
            url = url,
            headers = emptyMap(),
            body = "",
            followRedirects = false,
        )
        val location = response.headers["location"]?.substringBefore(',')?.trim()
        require(!location.isNullOrBlank()) { "CloudStream short repository link did not redirect" }
        require(!location.startsWith("https://py.md/404") && !location.startsWith("https://cutt.ly/404")) {
            "CloudStream short repository link was not found"
        }
        return location
    }
}
