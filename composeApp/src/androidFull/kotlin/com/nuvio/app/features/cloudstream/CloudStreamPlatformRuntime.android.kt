package com.nuvio.app.features.cloudstream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import co.touchlab.kermit.Logger
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.network.initClient
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.extractorApis
import com.nuvio.app.core.diagnostics.RuntimeDiagnostics
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.memberFunctions

internal actual object CloudStreamPlatformRuntime {
    actual val supportsAndroidDex: Boolean = true

    private val log = Logger.withTag("CloudStreamDex")
    private val json = Json { ignoreUnknownKeys = true }
    private val loadMutex = Mutex()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadedLock = Any()
    private val loaded = linkedMapOf<String, LoadedPlugin>()
    private var appContext: Context? = null
    private var activityReference: WeakReference<Activity>? = null
    private var hostInitialized = false

    actual fun initialize(context: Any?) {
        val androidContext = context as? Context ?: return
        val applicationContext = androidContext.applicationContext
        if (appContext === applicationContext) {
            activityReference = (androidContext as? Activity)?.let(::WeakReference)
            return
        }
        appContext = applicationContext
        activityReference = (androidContext as? Activity)?.let(::WeakReference)
        hostInitialized = false
    }

    actual suspend fun provider(plugin: CloudStreamPluginItem): CloudStreamProvider? {
        if (plugin.compatibility.runtimeKind != CloudStreamRuntimeKind.AndroidDex) return null
        return withContext(Dispatchers.IO) {
            var loadedNow = false
            val loadedPlugin = loadMutex.withLock {
                synchronized(loadedLock) {
                    loaded[plugin.metadata.id.value]
                        ?: loadPlugin(plugin).also {
                            loaded[plugin.metadata.id.value] = it
                            loadedNow = true
                        }
                }
            }
            if (loadedNow) {
                // Reconcile synchronous repository registrations immediately, then give
                // asynchronous bootstrap plugins a bounded background window to publish
                // RepositoryManager entries just like the real CloudStream host does.
                syncNativeRepositories()
                if (loadedPlugin.providers.isEmpty()) {
                    backgroundScope.launch {
                        repeat(NATIVE_REPOSITORY_SYNC_ATTEMPTS - 1) {
                            delay(NATIVE_REPOSITORY_SYNC_DELAY_MS)
                            syncNativeRepositories()
                        }
                    }
                }
            }
            loadedPlugin.provider
        }
    }

    actual suspend fun registerNativeRepository(url: String, name: String, iconUrl: String?) {
        val normalized = url.trim()
        if (normalized.isBlank()) return
        val context = appContext ?: return
        // Repository registration can happen before the first plugin load. In that path
        // CloudStreamApp.context has not yet been installed by prepareHostContext(), and
        // RepositoryManager.setKey() would silently become a no-op.
        if (CloudStreamApp.context == null) {
            prepareHostContext(context)
        }
        runCatching {
            val managerClass = Class.forName(CLOUDSTREAM_REPOSITORY_MANAGER_CLASS).kotlin
            val manager = managerClass.java.getField("INSTANCE").get(null) ?: return@runCatching
            val dataClass = Class.forName(CLOUDSTREAM_REPOSITORY_DATA_CLASS)
            val repository = dataClass.getDeclaredConstructor(
                String::class.java,
                String::class.java,
                String::class.java,
            ).newInstance(iconUrl, name, normalized)
            val addRepository = managerClass.memberFunctions.firstOrNull {
                it.name == "addRepository" && it.parameters.size == 2
            } ?: return@runCatching
            addRepository.callSuspend(manager, repository)
            val registeredCount = managerClass.memberFunctions
                .firstOrNull { it.name == "getRepositories" && it.parameters.size == 1 }
                ?.call(manager)
                ?.let { it as? Array<*> }
                ?.count { entry ->
                    entry?.javaClass?.getMethod("getUrl")?.invoke(entry)?.toString()?.trim() == normalized
                }
                ?: 0
            RuntimeDiagnostics.updateCloudStreamNativeRepositories(
                managerClass.memberFunctions
                    .firstOrNull { it.name == "getRepositories" && it.parameters.size == 1 }
                    ?.call(manager)
                    ?.let { it as? Array<*> }
                    ?.size
                    ?: 0,
            )
            RuntimeDiagnostics.recordLog(
                "CloudStream native-repository-register-success url=$normalized stored=$registeredCount",
            )
            log.i { "[CS-DYN] native repository registered url=$normalized" }
        }.onFailure { error ->
            RuntimeDiagnostics.recordLog("CloudStream native-repository-register-failure url=$normalized error=${error.message?.take(160)}")
            log.w(error) { "[CS-DYN] native repository registration unavailable/failed url=$normalized" }
        }
    }

    actual suspend fun removeNativeRepository(url: String) {
        val context = appContext ?: return
        val normalized = url.trim()
        if (normalized.isBlank()) return
        runCatching {
            val managerClass = Class.forName(CLOUDSTREAM_REPOSITORY_MANAGER_CLASS).kotlin
            val manager = managerClass.java.getField("INSTANCE").get(null) ?: return@runCatching
            val repository = managerClass.memberFunctions
                .firstOrNull { it.name == "getRepositories" && it.parameters.size == 1 }
                ?.call(manager)
                ?.let { it as? Array<*> }
                ?.firstOrNull { entry ->
                    entry?.javaClass?.getMethod("getUrl")?.invoke(entry)?.toString()?.trim() == normalized
                }
                ?: return@runCatching
            val removeRepository = managerClass.memberFunctions.firstOrNull {
                it.name == "removeRepository" && it.parameters.size == 3
            } ?: return@runCatching
            removeRepository.callSuspend(manager, context, repository)
            RuntimeDiagnostics.recordLog("CloudStream native-repository-remove-success url=$normalized")
            log.i { "[CS-DYN] native repository removed url=$normalized" }
        }.onFailure { error ->
            log.w(error) { "[CS-DYN] native repository removal unavailable/failed url=$normalized" }
        }
    }

    actual fun unload(pluginId: String) {
        synchronized(loadedLock) { loaded.remove(pluginId) }?.unload()
    }

    actual fun clear() {
        val plugins = synchronized(loadedLock) {
            loaded.values.toList().also {
                loaded.clear()
            }
        }
        plugins.forEach(LoadedPlugin::unload)
        // The shared CloudStream HTTP client belongs to the host process, not the plugin registry.
        // Clearing/unloading plugins must not force the next provider load to recreate it.
        PluginManager.clear()
    }

    private fun loadPlugin(item: CloudStreamPluginItem): LoadedPlugin {
        val context = requireNotNull(appContext) { "CloudStream Android runtime is not initialized" }
        prepareHostContext(context)
        val filePath = requireNotNull(CloudStreamPlatformStorage.packagePath(item.metadata.id.storageKey)) {
            "Installed CloudStream package is missing"
        }
        val file = File(filePath)
        require(file.isFile) { "Installed CloudStream package is missing" }
        item.metadata.fileSize?.let { expectedSize ->
            if (file.length() != expectedSize) {
                log.w {
                    "Installed CloudStream package metadata size mismatch id=${item.metadata.id.value} " +
                        "expected=$expectedSize actual=${file.length()}"
                }
            }
        }
        item.metadata.fileHash?.let { expectedHash ->
            require(expectedHash.matches(file.readBytes())) {
                "Installed CloudStream package SHA-256 no longer matches metadata"
            }
        }
        require(file.setReadOnly() || !file.canWrite()) { "CloudStream package could not be made read-only" }

        val manifest = ZipFile(file).use { archive ->
            val entry = archive.getEntry("manifest.json")
                ?: error("CloudStream package has no manifest.json")
            archive.getInputStream(entry).bufferedReader().use { reader ->
                json.decodeFromString<CloudStreamCs3Manifest>(reader.readText())
            }
        }
        val pluginClassName = manifest.pluginClassName?.takeIf(String::isNotBlank)
            ?: error("CloudStream manifest has no pluginClassName")
        val loader = PathClassLoader(file.absolutePath, context.classLoader)
        val pluginClass = loader.loadClass(pluginClassName)
        require(BasePlugin::class.java.isAssignableFrom(pluginClass)) {
            "CloudStream entry point does not extend BasePlugin"
        }

        @Suppress("UNCHECKED_CAST")
        val instance = (pluginClass as Class<out BasePlugin>).getDeclaredConstructor().newInstance()
        instance.filename = file.absolutePath
        PluginManager.currentlyLoading = item.metadata.internalName
        val identityContext = CloudStreamIdentityContext(context)
        CloudStreamApp.context = identityContext
        setContext(WeakReference(identityContext))
        applyHostCompatibilityDefaults(pluginClassName)
        if (manifest.requiresResources) instance.attachResources(identityContext, file)

        val providersBefore = APIHolder.allProviders.toSet()
        val extractorsBefore = extractorApis.toSet()
        val pluginData = PluginData(
            internalName = item.metadata.internalName,
            url = item.metadata.packageUrl,
            isOnline = true,
            filePath = file.absolutePath,
            version = manifest.version ?: item.metadata.version,
        )
        // The real CloudStream host registers the plugin before invoking load(). Some
        // extensions inspect PluginManager during load(), so keep that lifecycle ordering.
        PluginManager.register(pluginData, instance)
        try {
            log.i { "[CS-DYN] plugin-load-start id=" + item.metadata.id.value }
            RuntimeDiagnostics.recordLog("CloudStream plugin-load-start id=" + item.metadata.id.value)
            if (instance is Plugin) instance.load(identityContext) else instance.load()
            val providers = APIHolder.allProviders
                .filter { it !in providersBefore || it.sourcePlugin == file.absolutePath }
                .distinct()
            RuntimeDiagnostics.recordLog(
                "cs-provider-register plugin=" + item.metadata.id.value + " count=" + providers.size,
            )
            // CloudStream plugins are allowed to be repository/bootstrap plugins with no MainAPI.
            // Some real plugins (for example MegaRepo) perform their work from an async coroutine
            // launched by load(). The upstream CloudStream host considers load() successful here;
            // rejecting zero providers would incorrectly break those plugins in Mew.
            RuntimeDiagnostics.recordLog(
                "CloudStream plugin-load-success id=" + item.metadata.id.value + " providers=" + providers.size,
            )
            providers.forEach(MainAPI::init)
            val registeredExtractors = extractorApis
                .filter { it !in extractorsBefore || it.sourcePlugin == file.absolutePath }
                .distinct()
            log.i {
                "Loaded ${item.metadata.internalName}: ${providers.size} provider(s), " +
                    "${registeredExtractors.size} extractor(s)"
            }
            return LoadedPlugin(
                id = item.metadata.id.value,
                path = file.absolutePath,
                context = identityContext,
                instance = instance,
                providers = providers,
                extractors = registeredExtractors,
                provider = providers.takeIf { it.isNotEmpty() }
                    ?.let { AndroidDexCloudStreamProvider(item.metadata.id.value, it) },
            )
        } catch (error: Throwable) {
            RuntimeDiagnostics.recordLog(
                "CloudStream plugin-load-failure id=" + item.metadata.id.value + " error=" + error.message,
            )
            log.e(error) { "Failed to load ${item.metadata.internalName}" }
            APIHolder.allProviders.removeAll { it !in providersBefore && it.sourcePlugin == file.absolutePath }
            extractorApis.removeAll { it !in extractorsBefore && it.sourcePlugin == file.absolutePath }
            PluginManager.unregister(file.absolutePath)
            throw error
        } finally {
            PluginManager.currentlyLoading = null
        }
    }

    private fun prepareHostContext(context: Context) {
        activityReference?.get()?.let(CommonActivity::setActivityInstance)
        CloudStreamApp.context = context
        setContext(WeakReference(context))

        if (hostInitialized) return

        // CloudStream normally initializes NiceHttp from its Application lifecycle.
        // Mew does not run that Application, so initialize the shared client explicitly
        // before any third-party extension can call app.get().
        app.initClient(context)
        // Mew invokes MainAPI directly rather than through CloudStream's APIRepository.
        // That removes the upstream 120s provider execution guard, so the shared NiceHttp
        // client must not inherit a shorter runtime default from the embedded CloudStream AAR.
        app.defaultTimeOut = DEFAULT_HTTP_TIMEOUT_SECONDS
        hostInitialized = true
        RuntimeDiagnostics.recordLog(
            "CloudStream HTTP client initialized defaultTimeoutSeconds=${app.defaultTimeOut}",
        )

    }

    /**
     * CloudStream's RepositoryManager persists repositories registered by extensions, but the
     * upstream API does not expose a repository-added callback. Read that native registry after
     * plugin loading and reconcile only repositories Mew does not already own.
     */
    private suspend fun syncNativeRepositories() {
        val nativeRepositories: List<String> = runCatching {
            val managerClass = Class.forName(CLOUDSTREAM_REPOSITORY_MANAGER_CLASS).kotlin
            val manager = managerClass.java.getField("INSTANCE").get(null) ?: return@runCatching emptyList<String>()
            managerClass.memberFunctions
                .firstOrNull { it.name == "getRepositories" && it.parameters.size == 1 }
                ?.call(manager)
                ?.let { it as? Array<*> }
                ?.mapNotNull { entry ->
                    val url = entry?.javaClass?.getMethod("getUrl")?.invoke(entry)?.toString()?.trim()
                    if (url.isNullOrBlank()) null else url
                }
                ?: emptyList()
        }.getOrElse { error ->
            log.d(error) { "[CS-DYN] native repository registry unavailable" }
            return
        }
        RuntimeDiagnostics.updateCloudStreamNativeRepositories(nativeRepositories.size)
        RuntimeDiagnostics.recordLog("CloudStream native-repository-registry-count count=${nativeRepositories.size}")
        log.d { "[CS-DYN] native repository registry count=${nativeRepositories.size}" }

        val knownUrls = CloudStreamRepository.uiState.value.repositories
            .asSequence()
            .map { it.manifest.sourceUrl.trim() }
            .filter(String::isNotBlank)
            .toHashSet()

        nativeRepositories
            .asSequence()
            .filter { it !in knownUrls }
            .distinct()
            .forEach { url -> importDynamicRepository(url) }
    }

    private suspend fun importDynamicRepository(url: String) {
        val normalized = url.trim()
        if (normalized.isBlank()) return

        log.i { "[CS-DYN] repository-import-start url=$normalized" }
        RuntimeDiagnostics.recordLog("CloudStream repository-import-start url=" + normalized)
        val result = runCatching { CloudStreamRepository.addRepository(normalized) }
            .getOrElse { error ->
                log.w(error) {
                    "[CS-DYN] repository-import-failed url=$normalized error=" + error.message
                }
                return
            }

        when (result) {
            is AddCloudStreamRepositoryResult.Success -> {
                log.i { "[CS-DYN] repository-import-success url=$normalized" }
                RuntimeDiagnostics.recordLog("CloudStream repository-import-success url=" + normalized)
            }
            is AddCloudStreamRepositoryResult.Error -> {
                log.w {
                    "[CS-DYN] repository-import-failed url=$normalized error=" + result.message
                }
                RuntimeDiagnostics.recordLog(
                    "CloudStream repository-import-failed url=" + normalized + " error=" + result.message,
                )
            }
        }
        log.i {
            "[CS-DYN] repository-state-count count=" +
                CloudStreamRepository.uiState.value.repositories.size
        }
    }

    /**
     * CineStream's optional Simkl catalogue eagerly constructs CloudStream account-sync APIs.
     * Those APIs are application services rather than extension APIs and are intentionally not
     * embedded in Nuvio. The catalogue does not participate in CineStream's source extraction;
     * disabling it lets the primary CineStream/TMDB catalogues and every built-in link provider
     * load exactly as configured by the extension.
     */
    private fun applyHostCompatibilityDefaults(pluginClassName: String) {
        if (pluginClassName != CINESTREAM_PLUGIN_CLASS) return
        val preferences = appContext?.getSharedPreferences(
            com.lagradost.cloudstream3.utils.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ) ?: return
        if (preferences.getString(CINESTREAM_SIMKL_PROVIDER_KEY, null) != "false") {
            CloudStreamApp.setKey(CINESTREAM_SIMKL_PROVIDER_KEY, false)
        }
    }

    @Suppress("DEPRECATION")
    private fun BasePlugin.attachResources(context: Context, file: File) {
        val plugin = this as? Plugin ?: return
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            .invoke(assets, file.absolutePath)
        plugin.resources = Resources(
            assets,
            context.resources.displayMetrics,
            context.resources.configuration,
        )
    }

    private const val DEFAULT_HTTP_TIMEOUT_SECONDS = 120L
    private const val NATIVE_REPOSITORY_SYNC_ATTEMPTS = 16
    private const val NATIVE_REPOSITORY_SYNC_DELAY_MS = 500L

    private const val CLOUDSTREAM_REPOSITORY_MANAGER_CLASS = "com.lagradost.cloudstream3.plugins.RepositoryManager"
    private const val CLOUDSTREAM_REPOSITORY_DATA_CLASS = "com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData"

    private const val CINESTREAM_PLUGIN_CLASS = "com.megix.CineStream"
    private const val CINESTREAM_SIMKL_PROVIDER_KEY = "ProviderSimkl"

    private data class LoadedPlugin(
        val id: String,
        val path: String,
        @Suppress("unused") val context: Context,
        val instance: BasePlugin,
        val providers: List<MainAPI>,
        val extractors: List<com.lagradost.cloudstream3.utils.ExtractorApi>,
        val provider: CloudStreamProvider?,
    ) {
        fun unload() {
            runCatching { instance.beforeUnload() }
            providers.forEach { api ->
                APIHolder.allProviders.remove(api)
                APIHolder.removePluginMapping(api)
            }
            extractorApis.removeAll(extractors.toSet())
            VideoClickActionHolder.allVideoClickActions.removeAll { it.sourcePlugin == path }
            PluginManager.unregister(path)
        }
    }
}

private class CloudStreamIdentityContext(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = CLOUDSTREAM_PACKAGE_NAME
    override fun getApplicationContext(): Context = this

    companion object {
        private const val CLOUDSTREAM_PACKAGE_NAME = "com.lagradost.cloudstream3"
    }
}

private class AndroidDexCloudStreamProvider(
    override val id: String,
    private val providers: List<MainAPI>,
) : CloudStreamProvider {
    private val log = Logger.withTag("CloudStreamProvider")

    override suspend fun getMainPage(
        page: Int,
    ): List<Pair<String, List<CloudStreamSearchItem>>> = withContext(Dispatchers.IO) {
        val sections = Collections.synchronizedList(
            mutableListOf<Pair<String, List<CloudStreamSearchItem>>>(),
        )
        val apiGate = Semaphore(CLOUDSTREAM_API_CONCURRENCY)
        var lastError: Throwable? = null

        coroutineScope {
            providers.filter(MainAPI::hasMainPage).map { api ->
                async {
                    apiGate.withPermit {
                        val requests = api.mainPage.ifEmpty {
                            listOf(com.lagradost.cloudstream3.MainPageData(api.name, api.mainUrl, false))
                        }

                        suspend fun loadRequest(data: com.lagradost.cloudstream3.MainPageData) =
                            runCatching {
                                withTimeout(stageTimeout(api.getMainPageTimeoutMs, DEFAULT_DISCOVERY_TIMEOUT_MS)) {
                                    api.getMainPage(
                                        page.coerceAtLeast(1),
                                        MainPageRequest(data.name, data.data, data.horizontalImages),
                                    )
                                }
                            }.onSuccess { response ->
                                response?.items.orEmpty().forEach { section ->
                                    val title = if (providers.size > 1) "${api.name} · ${section.name}" else section.name
                                    val items = section.list.map { it.toNuvioSearchItem(api) }
                                    if (items.isNotEmpty()) sections += title to items
                                }
                            }.onFailure { error ->
                                synchronized(sections) { lastError = error }
                                log.w(error) { "CloudStream main page failed api=${api.name}" }
                            }

                        if (api.sequentialMainPage) {
                            requests.forEachIndexed { index, data ->
                                if (index > 0 && api.sequentialMainPageDelay > 0) {
                                    delay(api.sequentialMainPageDelay)
                                }
                                loadRequest(data)
                            }
                        } else {
                            requests.map { data -> async { loadRequest(data) } }.awaitAll()
                        }
                    }
                }
            }.awaitAll()
        }

        if (sections.isEmpty()) throw lastError ?: return@withContext emptyList()
        synchronized(sections) { sections.toList() }
    }

    override suspend fun search(query: String): List<CloudStreamSearchItem> = withContext(Dispatchers.IO) {
        val results = Collections.synchronizedList(mutableListOf<CloudStreamSearchItem>())
        val apiGate = Semaphore(CLOUDSTREAM_API_CONCURRENCY)
        var lastError: Throwable? = null

        coroutineScope {
            providers.map { api ->
                async {
                    apiGate.withPermit {
                        runCatching {
                            withTimeout(stageTimeout(api.searchTimeoutMs, DEFAULT_DISCOVERY_TIMEOUT_MS)) {
                                api.search(query, 1)?.items.orEmpty()
                            }
                        }.onSuccess { items ->
                            log.d {
                                "CloudStream search api=${api.name} query=$query returned=${items.size} " +
                                    "sample=${items.take(3).joinToString { it.name }}"
                            }
                            results += items.map { it.toNuvioSearchItem(api) }
                        }.onFailure { error ->
                            synchronized(results) { lastError = error }
                            log.w(error) { "CloudStream search failed api=${api.name}" }
                        }
                    }
                }
            }.awaitAll()
        }

        if (results.isEmpty()) throw lastError ?: return@withContext emptyList()
        synchronized(results) { results.toList().distinctBy { it.data } }
    }
    override suspend fun loadByExternalId(externalId: String): CloudStreamLoadItem? = withContext(Dispatchers.IO) {
        val syncName = when {
            externalId.matches(IMDB_ID_REGEX) -> SyncIdName.Imdb
            else -> return@withContext null
        }
        providers.firstNotNullOfOrNull { api ->
            if (syncName !in api.supportedSyncNames) return@firstNotNullOfOrNull null
            runCatching {
                val url = withTimeout(stageTimeout(api.loadTimeoutMs, DEFAULT_LOAD_TIMEOUT_MS)) {
                    api.getLoadUrl(syncName, externalId)
                }?.takeIf(String::isNotBlank) ?: return@runCatching null
                withTimeout(stageTimeout(api.loadTimeoutMs, DEFAULT_LOAD_TIMEOUT_MS)) {
                    api.load(url)
                }?.toNuvioLoadItem(api)
            }.onFailure { error ->
                log.w(error) { "CloudStream external-id load failed api=${api.name} id=$externalId" }
            }.getOrNull()
        }
    }

    override suspend fun load(data: String): CloudStreamLoadItem = withContext(Dispatchers.IO) {
        val route = decodeAndroidDexRoute(data)
        val api = findProvider(route.providerClassName)
        val effectiveTimeoutMs = loadStageTimeout(api.loadTimeoutMs)
        RuntimeDiagnostics.recordLog(
            "cs-load-timeout-config provider=${api.name} hintMs=${api.loadTimeoutMs} effectiveMs=$effectiveTimeoutMs",
        )
        val response = withTimeout(effectiveTimeoutMs) {
            api.load(route.data)
        } ?: error("CloudStream provider returned no details")
        response.toNuvioLoadItem(api)
    }

    override suspend fun loadLinks(data: String): List<CloudStreamPlaybackSource> = withContext(Dispatchers.IO) {
        val route = decodeAndroidDexRoute(data)
        val api = findProvider(route.providerClassName)
        val subtitles = Collections.synchronizedList(mutableListOf<CloudStreamSubtitle>())
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        // Match the real CloudStream/Nuvio execution contract: loadLinks() is allowed
        // to return false and/or finish after producing callbacks. The callback collection
        // is the authoritative result. A timeout must not discard links already emitted.
        val completed = withTimeoutOrNull(
            stageTimeout(api.loadLinksTimeoutMs, DEFAULT_LINK_TIMEOUT_MS),
        ) {
            runCatching {
                api.loadLinks(
                    data = route.data,
                    isCasting = false,
                    subtitleCallback = { subtitle ->
                        subtitles += CloudStreamSubtitle(
                            url = subtitle.url,
                            language = subtitle.lang,
                            name = subtitle.lang,
                            headers = subtitle.headers.orEmpty(),
                        )
                    },
                    callback = links::add,
                )
            }.onFailure { error ->
                log.w(error) {
                    "CloudStream loadLinks threw provider=${api.name} " +
                        "links=${links.size} error=${error.javaClass.simpleName}: ${error.message}"
                }
            }.getOrDefault(false)
        }

        val subtitleSnapshot = synchronized(subtitles) { subtitles.toList() }
        val linkSnapshot = synchronized(links) { links.toList() }

        RuntimeDiagnostics.recordLog(
            "cs-load-links-result provider=${api.name} completed=${completed != null} " +
                "returned=${completed ?: "timeout"} links=${linkSnapshot.size} subtitles=${subtitleSnapshot.size}",
        )

        if (completed == null) {
            log.w {
                "CloudStream loadLinks timed out provider=${api.name} " +
                    "links=${linkSnapshot.size}; returning partial callbacks"
            }
        } else if (completed == false && linkSnapshot.isEmpty()) {
            log.w {
                "CloudStream loadLinks returned false with no links provider=${api.name}"
            }
        }

        linkSnapshot.distinctBy { link ->
            listOf(
                link.url,
                link.referer,
                link.quality.toString(),
                link.type.name,
                link.headers.toString(),
            )
        }.map { link ->
            CloudStreamPlaybackSource(
                name = link.name.ifBlank { link.source },
                url = link.url,
                quality = link.quality.takeIf { it > 0 },
                referer = link.referer.takeIf(String::isNotBlank),
                headers = link.headers,
                subtitles = subtitleSnapshot,
                isHls = link.type == ExtractorLinkType.M3U8,
                isDash = link.type == ExtractorLinkType.DASH,
            )
        }.also {
            if (it.isEmpty()) {
                error(
                    "CloudStream provider returned no playable links " +
                        "(provider=${api.name}, completed=${completed ?: "timeout"})",
                )
            }
        }
    }

    private fun findProvider(className: String): MainAPI =
        providers.firstOrNull { it.javaClass.name == className }
            ?: error("CloudStream provider is no longer registered")

    private fun SearchResponse.toNuvioSearchItem(api: MainAPI): CloudStreamSearchItem =
        CloudStreamSearchItem(
            providerId = this@AndroidDexCloudStreamProvider.id,
            data = encodeAndroidDexRoute(api.javaClass.name, url),
            name = name,
            type = type.toNuvioTvType(),
            posterUrl = posterUrl,
            year = when (this) {
                is AnimeSearchResponse -> year
                is MovieSearchResponse -> year
                is TvSeriesSearchResponse -> year
                is LiveSearchResponse -> null
                else -> null
            },
        )

    private fun LoadResponse.toNuvioLoadItem(api: MainAPI): CloudStreamLoadItem {
        val playbackData = when (this) {
            is MovieLoadResponse -> dataUrl
            is LiveStreamLoadResponse -> dataUrl
            is TorrentLoadResponse -> magnet ?: torrent ?: url
            else -> url
        }
        val episodes = when (this) {
            is TvSeriesLoadResponse -> episodes.map { episode -> episode.toNuvioEpisode(api) }
            is AnimeLoadResponse -> episodes.flatMap { (dubStatus, values) ->
                values.map { episode ->
                    episode.toNuvioEpisode(api, dubStatus.name.takeUnless { episodes.size == 1 })
                }
            }
            else -> emptyList()
        }
        return CloudStreamLoadItem(
            providerId = id,
            data = encodeAndroidDexRoute(api.javaClass.name, playbackData),
            name = name,
            type = type.toNuvioTvType(),
            posterUrl = posterUrl,
            backgroundUrl = backgroundPosterUrl,
            description = plot,
            year = year,
            ratingPercent = score?.toInt(100),
            tags = tags.orEmpty(),
            episodes = episodes,
        )
    }

    private fun com.lagradost.cloudstream3.Episode.toNuvioEpisode(
        api: MainAPI,
        variant: String? = null,
    ): CloudStreamEpisode = CloudStreamEpisode(
        data = encodeAndroidDexRoute(api.javaClass.name, data),
        name = listOfNotNull(name?.takeIf(String::isNotBlank), variant).joinToString(" · ")
            .ifBlank { "Episode ${episode ?: ""}".trim() },
        season = season,
        episode = episode,
        posterUrl = posterUrl,
        description = description,
    )

    private companion object {
        private val IMDB_ID_REGEX = Regex("^tt\\d{5,12}$", RegexOption.IGNORE_CASE)
        private fun stageTimeout(providerHint: Long?, hostMaximum: Long): Long =
            providerHint?.coerceIn(5_000L, hostMaximum) ?: hostMaximum

        /**
         * CloudStream's current host uses 120s as the default load timeout and clamps
         * provider hints between 5s and 8 minutes. The bundled runtime AAR can expose
         * its legacy 5s default through MainAPI.loadTimeoutMs even when the provider
         * does not explicitly request a 5s timeout. Treat that exact legacy default
         * as unspecified so CS3 providers retain the upstream detail-load behavior.
         */
        private fun loadStageTimeout(providerHint: Long?): Long {
            val effectiveHint = providerHint?.takeUnless { it == LEGACY_RUNTIME_DEFAULT_LOAD_TIMEOUT_MS }
            return (effectiveHint ?: DEFAULT_LOAD_TIMEOUT_MS).coerceIn(
                MIN_PROVIDER_TIMEOUT_MS,
                MAX_LOAD_TIMEOUT_MS,
            )
        }

        private const val CLOUDSTREAM_API_CONCURRENCY = 4
        private const val DEFAULT_DISCOVERY_TIMEOUT_MS = 15_000L
        private const val DEFAULT_LOAD_TIMEOUT_MS = 120_000L
        private const val DEFAULT_LINK_TIMEOUT_MS = 120_000L
        private const val LEGACY_RUNTIME_DEFAULT_LOAD_TIMEOUT_MS = 5_000L
        private const val MIN_PROVIDER_TIMEOUT_MS = 5_000L
        private const val MAX_LOAD_TIMEOUT_MS = 480_000L
    }
}

private data class AndroidDexRoute(val providerClassName: String, val data: String)

private fun encodeAndroidDexRoute(providerClassName: String, data: String): String =
    "nuvio-cs-api:${providerClassName.length}:$providerClassName$data"

private fun decodeAndroidDexRoute(value: String): AndroidDexRoute {
    val payload = value.removePrefix("nuvio-cs-api:")
    require(payload != value) { "Invalid CloudStream provider route" }
    val separator = payload.indexOf(':')
    require(separator > 0) { "Invalid CloudStream provider route" }
    val providerLength = payload.substring(0, separator).toIntOrNull()
        ?: error("Invalid CloudStream provider route")
    val content = payload.substring(separator + 1)
    require(providerLength in 1..content.length) { "Invalid CloudStream provider route" }
    return AndroidDexRoute(
        providerClassName = content.substring(0, providerLength),
        data = content.substring(providerLength),
    )
}

private fun TvType?.toNuvioTvType(): CloudStreamTvType = when (this) {
    TvType.Movie -> CloudStreamTvType.Movie
    TvType.AnimeMovie -> CloudStreamTvType.AnimeMovie
    TvType.TvSeries -> CloudStreamTvType.TvSeries
    TvType.Cartoon -> CloudStreamTvType.Cartoon
    TvType.Anime -> CloudStreamTvType.Anime
    TvType.OVA -> CloudStreamTvType.Ova
    TvType.Torrent -> CloudStreamTvType.Torrent
    TvType.Documentary -> CloudStreamTvType.Documentary
    TvType.AsianDrama -> CloudStreamTvType.AsianDrama
    TvType.Live -> CloudStreamTvType.Live
    TvType.Music, TvType.Audio, TvType.AudioBook, TvType.Podcast -> CloudStreamTvType.Music
    else -> CloudStreamTvType.Other
}
