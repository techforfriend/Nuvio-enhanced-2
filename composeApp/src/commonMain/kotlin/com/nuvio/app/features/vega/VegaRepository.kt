package com.nuvio.app.features.vega

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.createApiHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object VegaRepository {
    private val log = Logger.withTag("VegaRepository")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient: HttpClient by lazy { createApiHttpClient() }
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    const val DEFAULT_REPO_URL = "https://raw.githubusercontent.com/Zenda-Cross/vega-providers/main/manifest.json"

    private val _uiState = MutableStateFlow(VegaUiState())
    val uiState: StateFlow<VegaUiState> = _uiState.asStateFlow()

    @Volatile
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        loadPersistedState()
    }

    private fun loadPersistedState() {
        runCatching {
            val payload = VegaStorage.loadPayload()
            if (!payload.isNullOrBlank()) {
                val stored = json.decodeFromString<StoredVegaState>(payload)
                val allProviders = stored.repositories.flatMap { it.providers }
                _uiState.update {
                    it.copy(
                        vegaEnabled = stored.vegaEnabled,
                        repositories = stored.repositories,
                        providers = allProviders,
                    )
                }
            }
        }.onFailure { error ->
            log.w(error) { "Failed to load stored Vega state" }
        }
    }

    private fun persistState() {
        runCatching {
            val state = _uiState.value
            val stored = StoredVegaState(
                vegaEnabled = state.vegaEnabled,
                repositories = state.repositories,
            )
            VegaStorage.savePayload(json.encodeToString(stored))
        }.onFailure { error ->
            log.w(error) { "Failed to persist Vega state" }
        }
    }

    fun setVegaEnabled(enabled: Boolean) {
        _uiState.update { it.copy(vegaEnabled = enabled) }
        persistState()
    }

    fun normalizeRepoUrl(raw: String): String {
        var url = raw.trim()
        if (url.startsWith("https://github.com/") && !url.contains("/raw/")) {
            url = url.replace("https://github.com/", "https://raw.githubusercontent.com/")
            if (!url.endsWith("/")) url += "/"
            if (!url.contains("/main/") && !url.contains("/master/")) {
                url += "main/"
            }
        }
        if (!url.endsWith(".json")) {
            if (!url.endsWith("/")) url += "/"
            url += "manifest.json"
        }
        return url
    }

    suspend fun addRepository(rawUrl: String): AddVegaRepositoryResult = withContext(Dispatchers.Default) {
        val normalizedUrl = normalizeRepoUrl(rawUrl)
        if (_uiState.value.repositories.any { it.url.equals(normalizedUrl, ignoreCase = true) }) {
            return@withContext AddVegaRepositoryResult.Error("Repository already installed")
        }

        try {
            val responseText = httpClient.get(normalizedUrl).bodyAsText()
            val manifestItems = json.decodeFromString<List<VegaManifestItem>>(responseText)
            if (manifestItems.isEmpty()) {
                return@withContext AddVegaRepositoryResult.Error("Manifest does not contain any providers")
            }

            val repoName = if (normalizedUrl.contains("vega-providers")) {
                "Vega Official Providers"
            } else {
                normalizedUrl.substringAfter("://").substringBefore("/").ifBlank { "Vega Repository" }
            }

            val providers = manifestItems.map { item ->
                VegaProviderItem(
                    repositoryUrl = normalizedUrl,
                    displayName = item.displayName,
                    value = item.value,
                    version = item.version,
                    icon = item.icon,
                    type = item.type,
                    enabled = !item.disabled,
                )
            }

            val repoItem = VegaRepositoryItem(
                url = normalizedUrl,
                name = repoName,
                providers = providers,
                enabled = true,
                lastUpdatedEpochMs = System.currentTimeMillis(),
            )

            _uiState.update { current ->
                val newRepos = current.repositories + repoItem
                current.copy(
                    repositories = newRepos,
                    providers = newRepos.flatMap { it.providers },
                )
            }
            persistState()
            AddVegaRepositoryResult.Success(repoItem)
        } catch (e: Exception) {
            log.w(e) { "Failed to add Vega repository url=$normalizedUrl" }
            AddVegaRepositoryResult.Error(e.message ?: "Failed to fetch repository manifest")
        }
    }

    fun removeRepository(url: String) {
        _uiState.update { current ->
            val newRepos = current.repositories.filterNot { it.url == url }
            current.copy(
                repositories = newRepos,
                providers = newRepos.flatMap { it.providers },
            )
        }
        persistState()
    }

    fun refreshRepository(url: String) {
        scope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val responseText = httpClient.get(url).bodyAsText()
                val manifestItems = json.decodeFromString<List<VegaManifestItem>>(responseText)
                _uiState.update { current ->
                    val updatedRepos = current.repositories.map { repo ->
                        if (repo.url != url) return@map repo
                        val existingEnabled = repo.providers.associate { it.value to it.enabled }
                        val providers = manifestItems.map { item ->
                            VegaProviderItem(
                                repositoryUrl = url,
                                displayName = item.displayName,
                                value = item.value,
                                version = item.version,
                                icon = item.icon,
                                type = item.type,
                                enabled = existingEnabled[item.value] ?: !item.disabled,
                            )
                        }
                        repo.copy(
                            providers = providers,
                            lastUpdatedEpochMs = System.currentTimeMillis(),
                            errorMessage = null,
                        )
                    }
                    current.copy(
                        repositories = updatedRepos,
                        providers = updatedRepos.flatMap { it.providers },
                        isRefreshing = false,
                    )
                }
                persistState()
            } catch (e: Exception) {
                log.w(e) { "Failed to refresh Vega repo url=$url" }
                _uiState.update { current ->
                    val updatedRepos = current.repositories.map { repo ->
                        if (repo.url == url) repo.copy(errorMessage = e.message) else repo
                    }
                    current.copy(repositories = updatedRepos, isRefreshing = false)
                }
            }
        }
    }

    fun setProviderEnabled(repositoryUrl: String, providerValue: String, enabled: Boolean) {
        _uiState.update { current ->
            val updatedRepos = current.repositories.map { repo ->
                if (repo.url != repositoryUrl) return@map repo
                val updatedProviders = repo.providers.map { provider ->
                    if (provider.value == providerValue) provider.copy(enabled = enabled) else provider
                }
                repo.copy(providers = updatedProviders)
            }
            current.copy(
                repositories = updatedRepos,
                providers = updatedRepos.flatMap { it.providers },
            )
        }
        persistState()
    }

    fun getProviderByValue(value: String): VegaProviderItem? {
        val state = _uiState.value
        return state.repositories
            .filter { it.enabled }
            .flatMap { it.providers }
            .firstOrNull { it.value == value }
    }

    suspend fun getOrFetchScript(provider: VegaProviderItem, scriptName: String): String? = withContext(Dispatchers.Default) {
        val sanitizedScriptName = scriptName.replace(".js", "").replace("[^a-zA-Z0-9_]".toRegex(), "_")
        val cacheKey = "${provider.repositoryUrl}_${provider.value}_${provider.version ?: "1.0"}_$sanitizedScriptName"
            .replace("[^a-zA-Z0-9_]".toRegex(), "_")

        val cached = VegaStorage.loadScript(cacheKey)
        if (!cached.isNullOrBlank()) {
            return@withContext cached
        }

        val baseUrl = provider.repositoryUrl.substringBeforeLast("/") + "/"
        val scriptUrl = "${baseUrl}dist/${provider.value}/$scriptName"

        try {
            log.d { "Fetching Vega $scriptName for ${provider.displayName} from $scriptUrl" }
            val script = httpClient.get(scriptUrl).bodyAsText()
            if (script.isNotBlank()) {
                VegaStorage.saveScript(cacheKey, script)
                return@withContext script
            }
        } catch (e: Exception) {
            log.w(e) { "Failed to fetch Vega script $scriptName url=$scriptUrl" }
        }
        null
    }

    suspend fun getOrFetchProviderScript(provider: VegaProviderItem): String? =
        getOrFetchScript(provider, "stream.js")

    fun getEnabledProviders(): List<VegaProviderItem> {
        val state = _uiState.value
        if (!state.vegaEnabled) return emptyList()
        return state.repositories
            .filter { it.enabled }
            .flatMap { it.providers }
            .filter { it.enabled }
    }
}
