package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.debrid.DirectDebridStreamPreparer
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridStreamPresentation
import com.nuvio.app.features.debrid.LocalDebridAvailabilityService
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.plugins.PluginsUiState
import com.nuvio.app.features.cloudstream.CloudStreamRepository
import com.nuvio.app.features.cloudstream.parseCloudStreamRouteId
import com.nuvio.app.features.vega.VegaMergedPostsRepository
import com.nuvio.app.features.vega.VegaBridge
import com.nuvio.app.features.vega.VegaRepository
import com.nuvio.app.features.vega.parseVegaRouteId
import com.nuvio.app.features.vega.resolveVegaProviderStreams
import com.nuvio.app.features.servers.ServerStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object StreamsRepository {
    private val log = Logger.withTag("StreamsRepo")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(StreamsUiState())
    val uiState: StateFlow<StreamsUiState> = _uiState.asStateFlow()

    private val cloudStreamSemaphore = Semaphore(4)
    private const val STREAM_PROVIDER_TIMEOUT_MS = 35_000L

    private var activeJob: Job? = null
    private var activeRequestKey: String? = null

    fun requestToken(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        manualSelection: Boolean = false,
    ): String =
        "$type::$videoId::$season::$episode::$manualSelection"

    fun load(
        type: String,
        videoId: String,
        parentMetaId: String? = null,
        parentMetaType: String? = null,
        season: Int? = null,
        episode: Int? = null,
        manualSelection: Boolean = false,
        searchTitle: String? = null,
    ) {
        PluginRepository.setLocalPluginSearchPaused(false)
        load(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            season = season,
            episode = episode,
            manualSelection = manualSelection,
            searchTitle = searchTitle,
            forceRefresh = false,
        )
    }

    fun reload(
        type: String,
        videoId: String,
        parentMetaId: String? = null,
        parentMetaType: String? = null,
        season: Int? = null,
        episode: Int? = null,
        manualSelection: Boolean = false,
        searchTitle: String? = null,
    ) {
        PluginRepository.setLocalPluginSearchPaused(false)
        load(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            season = season,
            episode = episode,
            manualSelection = manualSelection,
            searchTitle = searchTitle,
            forceRefresh = true,
        )
    }

    private fun load(
        type: String,
        videoId: String,
        parentMetaId: String?,
        parentMetaType: String? = null,
        season: Int?,
        episode: Int?,
        manualSelection: Boolean,
        searchTitle: String? = null,
        forceRefresh: Boolean,
    ) {
        val pluginUiState = if (AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.initialize()
            PluginRepository.uiState.value
        } else {
            PluginsUiState(pluginsEnabled = false)
        }
        val cloudStreamSearchRequest = buildCloudStreamSearchRequest(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            season = season,
            episode = episode,
            searchTitle = searchTitle,
        )
        val cloudStreamProviderGroups = if (AppFeaturePolicy.pluginsEnabled) {
            cloudStreamProviderGroupsForRequest(type, cloudStreamSearchRequest)
        } else {
            emptyList()
        }
        val vegaProviders = if (AppFeaturePolicy.pluginsEnabled) {
            VegaRepository.initialize()
            VegaRepository.getEnabledProviders()
        } else {
            emptyList()
        }
        val requestToken = requestToken(
            type = type,
            videoId = videoId,
            season = season,
            episode = episode,
            manualSelection = manualSelection,
        )
        val requestKey = "$requestToken::pluginsGrouped=${pluginUiState.groupStreamsByRepository}::cloudTarget=${cloudStreamSearchRequest?.cacheKey.orEmpty()}"
        val currentState = _uiState.value
        if (
            !forceRefresh &&
            activeRequestKey == requestKey &&
            (currentState.groups.isNotEmpty() || currentState.emptyStateReason != null || currentState.isAnyLoading)
        ) {
            log.d { "Skipping stream reload for unchanged request type=$type id=$videoId" }
            return
        }

        activeRequestKey = requestKey
        activeJob?.cancel()
        _uiState.value = StreamsUiState(requestToken = requestToken)

        PlayerSettingsRepository.ensureLoaded()
        val playerSettings = PlayerSettingsRepository.uiState.value
        val debridSettings = DebridSettingsRepository.snapshot()
        val streamBadgeRules = StreamBadgeSettingsRepository.snapshot()
        InAppLogger.info(
            "Streams/StreamsRepository",
            "Loading streams type=$type id=$videoId season=${season ?: -1} episode=${episode ?: -1} " +
                "manual=$manualSelection force=$forceRefresh",
        )
        val autoPlayMode = playerSettings.streamAutoPlayMode
        val isAutoPlayEnabled = !manualSelection && autoPlayMode != StreamAutoPlayMode.MANUAL &&
            !(autoPlayMode == StreamAutoPlayMode.REGEX_MATCH &&
                !StreamAutoPlayPolicy.isRegexSelectionConfigured(playerSettings.streamAutoPlayRegex))

        // Look up persisted binge group when both settings are enabled
        val persistedBingeGroup = if (
            playerSettings.streamAutoPlayPreferBingeGroup &&
            playerSettings.streamAutoPlayReuseBingeGroup
        ) {
            parentMetaId?.let { BingeGroupCacheRepository.get(it) }
        } else null
        val persistedFilename = if (persistedBingeGroup != null) {
            parentMetaId?.let { BingeGroupCacheRepository.getFilename(it) }
        } else null
        val persistedInfoHash = if (persistedBingeGroup != null) {
            parentMetaId?.let { BingeGroupCacheRepository.getInfoHash(it) }
        } else null

        // Enable direct auto-play flow if normal auto-play is enabled,
        // OR if we have a persisted binge group in MANUAL mode
        val bingeGroupDirectFlow = !manualSelection &&
            persistedBingeGroup != null &&
            autoPlayMode == StreamAutoPlayMode.MANUAL
        val isDirectAutoPlayFlow = isAutoPlayEnabled || bingeGroupDirectFlow

        if (isDirectAutoPlayFlow) {
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                isDirectAutoPlayFlow = true,
                autoPlayDecided = true,
                showDirectAutoPlayOverlay = true,
            )
        }

        val embeddedStreams = MetaDetailsRepository.findEmbeddedStreams(videoId)
        if (embeddedStreams.isNotEmpty()) {
            log.d { "Using ${embeddedStreams.size} embedded streams for type=$type id=$videoId" }
            InAppLogger.info("Streams/StreamsRepository", "Using embedded streams count=${embeddedStreams.size} type=$type id=$videoId")
            val group = AddonStreamGroup(
                addonName = embeddedStreams.first().addonName,
                addonId = "embedded",
                streams = embeddedStreams,
                isLoading = false,
            )
            val presentedGroup = StreamBadgePresentation.apply(
                groups = listOf(group),
                rules = streamBadgeRules,
            ).firstOrNull() ?: group
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                groups = listOf(presentedGroup),
                autoPlayDecided = true,
                activeAddonIds = setOf("embedded"),
                isAnyLoading = false,
            )
            return
        }

        val cloudStreamRoute = parseCloudStreamRouteId(videoId)
            ?: parentMetaId?.takeIf { type == "movie" }?.let { parseCloudStreamRouteId(it) }
        if (cloudStreamRoute != null) {
            CloudStreamRepository.initialize()
            val providerItem = CloudStreamRepository.uiState.value.plugins
                .firstOrNull { it.metadata.id.value == cloudStreamRoute.providerId }
            val providerName = providerItem?.metadata?.name ?: "CloudStream"
            val providerAddonId = "cloudstream:${cloudStreamRoute.providerId}"
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                groups = listOf(
                    AddonStreamGroup(
                        addonName = providerName,
                        addonId = providerAddonId,
                        streams = emptyList(),
                        isLoading = true,
                    ),
                ),
                activeAddonIds = setOf(providerAddonId),
                isAnyLoading = true,
                isDirectAutoPlayFlow = isDirectAutoPlayFlow,
                showDirectAutoPlayOverlay = isDirectAutoPlayFlow,
            )
            activeJob = scope.launch {
                val result = runCatching {
                    withTimeoutOrNull(STREAM_PROVIDER_TIMEOUT_MS) {
                        CloudStreamRepository.loadLinks(cloudStreamRoute.providerId, cloudStreamRoute.data)
                    } ?: error("$providerName timed out")
                }.fold(
                    onSuccess = { it },
                    onFailure = { Result.failure(it) },
                )
                if (activeRequestKey != requestKey) return@launch
                result.fold(
                    onSuccess = { sources ->
                        val streams = cloudStreamSourcesToStreamItems(
                            providerId = cloudStreamRoute.providerId,
                            providerName = providerName,
                            sources = sources,
                        )
                        val presentedGroup = StreamBadgePresentation.apply(
                            groups = listOf(
                                AddonStreamGroup(
                                    addonName = providerName,
                                    addonId = providerAddonId,
                                    streams = streams,
                                    isLoading = false,
                                    error = if (streams.isEmpty()) "No links found" else null,
                                ),
                            ),
                            rules = streamBadgeRules,
                        )
                        val autoPlayStream = if (isDirectAutoPlayFlow) streams.firstOrNull() else null
                        _uiState.value = StreamsUiState(
                            requestToken = requestToken,
                            groups = presentedGroup,
                            activeAddonIds = setOf(providerAddonId),
                            isAnyLoading = false,
                            emptyStateReason = if (streams.isEmpty()) StreamsEmptyStateReason.NoStreamsFound else null,
                            autoPlayStream = autoPlayStream,
                            autoPlayCandidates = if (isDirectAutoPlayFlow) streams else emptyList(),
                            autoPlayDecided = true,
                            isDirectAutoPlayFlow = isDirectAutoPlayFlow && autoPlayStream != null,
                            showDirectAutoPlayOverlay = isDirectAutoPlayFlow && autoPlayStream != null,
                        )
                    },
                    onFailure = { error ->
                        log.w(error) { "CloudStream link resolution failed provider=${cloudStreamRoute.providerId}" }
                        _uiState.value = StreamsUiState(
                            requestToken = requestToken,
                            groups = listOf(
                                AddonStreamGroup(
                                    addonName = providerName,
                                    addonId = providerAddonId,
                                    streams = emptyList(),
                                    isLoading = false,
                                    error = error.message ?: "CloudStream link resolution failed",
                                ),
                            ),
                            activeAddonIds = setOf(providerAddonId),
                            isAnyLoading = false,
                            emptyStateReason = StreamsEmptyStateReason.StreamFetchFailed,
                            autoPlayDecided = true,
                            isDirectAutoPlayFlow = false,
                            showDirectAutoPlayOverlay = false,
                        )
                    },
                )
            }
            return
        }

        val vegaRoute = parseVegaRouteId(videoId)
            ?: parentMetaId?.takeIf { type == "movie" }?.let { parseVegaRouteId(it) }
        if (vegaRoute != null) {
            VegaRepository.initialize()
            val providerItem = VegaRepository.getProviderByValue(vegaRoute.providerId)
            val providerName = providerItem?.displayName ?: "Vega"
            val providerAddonId = "vega:${vegaRoute.providerId}"
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                groups = listOf(
                    AddonStreamGroup(
                        addonName = providerName,
                        addonId = providerAddonId,
                        streams = emptyList(),
                        isLoading = true,
                    ),
                ),
                activeAddonIds = setOf(providerAddonId),
                isAnyLoading = true,
                isDirectAutoPlayFlow = isDirectAutoPlayFlow,
                showDirectAutoPlayOverlay = isDirectAutoPlayFlow,
            )
            activeJob = scope.launch {
                val alternatePosts = VegaMergedPostsRepository.getAlternatePosts(vegaRoute.providerId, vegaRoute.link)
                val allLinks = if (alternatePosts.isNotEmpty()) {
                    alternatePosts.map { it.link to it.title }.distinctBy { it.first }
                } else {
                    listOf(vegaRoute.link to "")
                }

                val allStreams = mutableListOf<com.nuvio.app.features.streams.StreamItem>()
                coroutineScope {
                    allLinks.forEach { (link, postTitle) ->
                        launch {
                            val streams = runCatching {
                                withTimeoutOrNull(STREAM_PROVIDER_TIMEOUT_MS) {
                                    if (providerItem != null) {
                                        VegaBridge.getStreams(providerItem, link, type)
                                    } else {
                                        emptyList()
                                    }
                                } ?: emptyList()
                            }.getOrElse { emptyList() }

                            val qualityTag = Regex("""(?i)\b(2160p|1080p|720p|480p|4k)\b""").find(postTitle)?.value
                            val tagged = streams.map { stream ->
                                val streamName = stream.name.orEmpty()
                                if (qualityTag != null && !streamName.contains(qualityTag, ignoreCase = true)) {
                                    stream.copy(name = if (streamName.isBlank()) qualityTag else "$streamName • $qualityTag")
                                } else {
                                    stream
                                }
                            }
                            synchronized(allStreams) {
                                allStreams.addAll(tagged)
                            }
                        }
                    }
                }
                if (activeRequestKey != requestKey) return@launch
                val distinctStreams = allStreams.distinctBy { it.url }
                val presentedGroup = StreamBadgePresentation.apply(
                    groups = listOf(
                        AddonStreamGroup(
                            addonName = providerName,
                            addonId = providerAddonId,
                            streams = distinctStreams,
                            isLoading = false,
                            error = if (distinctStreams.isEmpty()) "No links found" else null,
                        ),
                    ),
                    rules = streamBadgeRules,
                )
                val autoPlayStream = if (isDirectAutoPlayFlow) distinctStreams.firstOrNull() else null
                _uiState.value = StreamsUiState(
                    requestToken = requestToken,
                    groups = presentedGroup,
                    activeAddonIds = setOf(providerAddonId),
                    isAnyLoading = false,
                    emptyStateReason = if (distinctStreams.isEmpty()) StreamsEmptyStateReason.NoStreamsFound else null,
                    autoPlayStream = autoPlayStream,
                    autoPlayCandidates = if (isDirectAutoPlayFlow) distinctStreams else emptyList(),
                    autoPlayDecided = true,
                    isDirectAutoPlayFlow = isDirectAutoPlayFlow && autoPlayStream != null,
                    showDirectAutoPlayOverlay = isDirectAutoPlayFlow && autoPlayStream != null,
                )
            }
            return
        }

        val isNativeServerRequest = ServerStreams.isNativeRequest(videoId)
        val serverSources = ServerStreams.sources(type, videoId, season, episode, forceRefresh)
        val preferredGroupIds = serverSources.filter { it.preferred }.mapTo(mutableSetOf()) { it.addonId }
        val installedAddons = if (isNativeServerRequest) emptyList() else AddonRepository.uiState.value.addons.enabledAddons()
        val pluginScrapers = if (AppFeaturePolicy.pluginsEnabled && !isNativeServerRequest) {
            PluginRepository.getEnabledScrapersForType(type)
        } else {
            emptyList()
        }
        val pluginProviderGroups = pluginScrapers.toPluginProviderGroups(
            repositories = pluginUiState.repositories,
            groupByRepository = pluginUiState.groupStreamsByRepository,
        )

        if (installedAddons.isEmpty() && pluginProviderGroups.isEmpty() && serverSources.isEmpty() && cloudStreamProviderGroups.isEmpty()) {
            InAppLogger.warn("Streams/StreamsRepository", "No stream addons, plugin scrapers, or CloudStream providers installed for type=$type id=$videoId")
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                isAnyLoading = false,
                emptyStateReason = StreamsEmptyStateReason.NoAddonsInstalled,
                autoPlayDecided = true,
            )
            return
        }

        val streamAddons = installedAddons
            .mapNotNull { addon ->
                val manifest = addon.manifest ?: return@mapNotNull null
                if (!manifest.supportsStream(type, videoId)) return@mapNotNull null

                InstalledStreamAddonTarget(
                    addonName = addon.displayTitle.ifBlank { manifest.name },
                    addonId = addon.streamAddonInstanceId(manifest.id),
                    manifest = manifest,
                )
            }

        log.d { "Found ${streamAddons.size} addons for stream type=$type id=$videoId" }
        InAppLogger.info(
            "Streams/StreamsRepository",
            "Found addons=${streamAddons.size} pluginGroups=${pluginProviderGroups.size} pluginScrapers=${pluginScrapers.size} " +
                "for type=$type id=$videoId",
        )

        if (streamAddons.isEmpty() && pluginProviderGroups.isEmpty() && serverSources.isEmpty() && cloudStreamProviderGroups.isEmpty()) {
            InAppLogger.warn("Streams/StreamsRepository", "No compatible stream addon, plugin, or CloudStream provider for type=$type id=$videoId")
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                isAnyLoading = false,
                emptyStateReason = StreamsEmptyStateReason.NoCompatibleAddons,
                autoPlayDecided = true,
            )
            return
        }

        // Initialise loading placeholders
        val installedAddonOrder = streamAddons.map { it.addonName }
        val initialGroups = StreamAutoPlaySelector.orderAddonStreams(streamAddons.map { addon ->
            AddonStreamGroup(
                addonName = addon.addonName,
                addonId = addon.addonId,
                streams = emptyList(),
                isLoading = true,
            )
        } + pluginProviderGroups.map { providerGroup ->
            AddonStreamGroup(
                addonName = providerGroup.addonName,
                addonId = providerGroup.addonId,
                streams = emptyList(),
                isLoading = true,
            )
        } + cloudStreamProviderGroups.map { providerGroup ->
            AddonStreamGroup(
                addonName = providerGroup.addonName,
                addonId = providerGroup.addonId,
                streams = emptyList(),
                isLoading = true,
            )
        } + vegaProviders.map { provider ->
            AddonStreamGroup(
                addonName = "Vega - ${provider.displayName}",
                addonId = "vega:${provider.value}",
                streams = emptyList(),
                isLoading = true,
            )
        } + serverSources.map { it.loadingGroup() }, installedAddonOrder, preferredGroupIds)
        val isInitiallyLoading = initialGroups.any { it.isLoading }
        _uiState.value = StreamsUiState(
            requestToken = requestToken,
            groups = initialGroups,
            activeAddonIds = initialGroups.map { it.addonId }.toSet(),
            isAnyLoading = isInitiallyLoading,
            emptyStateReason = null,
            isDirectAutoPlayFlow = isDirectAutoPlayFlow,
            autoPlayDecided = true,
            showDirectAutoPlayOverlay = isDirectAutoPlayFlow,
        )

        activeJob = scope.launch {
            val completions = Channel<StreamLoadCompletion>(capacity = Channel.BUFFERED)
            val pluginRemainingByAddonId = pluginProviderGroups
                .associate { it.addonId to it.scrapers.size }
                .toMutableMap()
            val pluginFirstErrorByAddonId = mutableMapOf<String, String>()
            val totalTasks = streamAddons.size +
                pluginProviderGroups.sumOf { it.scrapers.size } +
                serverSources.size +
                cloudStreamProviderGroups.size +
                vegaProviders.size

            val installedAddonNames = installedAddonOrder.toSet()
            val installedAddonIds = streamAddons.map { it.addonId }.toSet()
            val debridAvailabilityJobs = mutableListOf<Job>()
            var autoSelectTriggered = false
            var timeoutElapsed = false
            fun evaluateAutoPlay(bingeGroupOnly: Boolean = false): StreamAutoPlayEvaluation =
                StreamAutoPlaySelector.evaluateAutoPlayStream(
                    streams = _uiState.value.groups.flatMap { it.streams },
                    mode = autoPlayMode,
                    regexPattern = playerSettings.streamAutoPlayRegex,
                    source = playerSettings.streamAutoPlaySource,
                    installedAddonNames = installedAddonNames,
                    selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                    selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                    preferredBingeGroup = persistedBingeGroup,
                    preferBingeGroupInSelection = persistedBingeGroup != null,
                    bingeGroupOnly = bingeGroupOnly,
                    debridEnabled = debridSettings.canResolvePlayableLinks,
                    activeResolverProviderId = debridSettings.activeResolverProviderId,
                )

            fun settleAutoPlay(evaluation: StreamAutoPlayEvaluation) {
                if (autoSelectTriggered) return
                autoSelectTriggered = true
                _uiState.update { current ->
                    if (evaluation.stream == null) {
                        current.copy(
                            autoPlayStream = null,
                            autoPlayCandidates = emptyList(),
                            isDirectAutoPlayFlow = false,
                            showDirectAutoPlayOverlay = false,
                        )
                    } else {
                        current.copy(
                            autoPlayStream = evaluation.stream,
                            autoPlayCandidates = evaluation.readyStreams,
                        )
                    }
                }
            }

            fun updateAutoPlayAfterStreamsChanged() {
                if (!isDirectAutoPlayFlow || autoSelectTriggered) return

                // Try binge group + filename first (most precise), then infoHash
                if (persistedBingeGroup != null && !timeoutElapsed) {
                    val allStreams = _uiState.value.groups.flatMap { it.streams }
                    // Filename match
                    if (persistedFilename != null) {
                        val nextFilename = run {
                            val extracted = FilenameEpisodeMatcher.extractSeasonEpisode(persistedFilename)
                            if (extracted != null) {
                                FilenameEpisodeMatcher.buildNextFilename(
                                    persistedFilename, extracted.first, extracted.second, season, episode ?: 0
                                ) ?: persistedFilename
                            } else persistedFilename
                        }
                        val bingeAndFilename = allStreams.firstOrNull { stream ->
                            stream.behaviorHints.bingeGroup == persistedBingeGroup &&
                                FilenameEpisodeMatcher.matches(stream.behaviorHints.filename, nextFilename)
                        }
                        if (bingeAndFilename != null) {
                            settleAutoPlay(StreamAutoPlayEvaluation(bingeAndFilename, allStreams))
                            return
                        }
                    }
                    // InfoHash match (fallback when filename didn't match or was null)
                    if (persistedInfoHash != null) {
                        val bingeAndInfoHash = allStreams.firstOrNull { stream ->
                            stream.behaviorHints.bingeGroup == persistedBingeGroup &&
                                stream.infoHash?.equals(persistedInfoHash, ignoreCase = true) == true
                        }
                        if (bingeAndInfoHash != null) {
                            settleAutoPlay(StreamAutoPlayEvaluation(bingeAndInfoHash, allStreams))
                            return
                        }
                    }
                }

                val earlyEvaluation = when {
                    timeoutElapsed -> evaluateAutoPlay()
                    persistedBingeGroup != null -> evaluateAutoPlay(bingeGroupOnly = true)
                    else -> null
                }
                if (earlyEvaluation?.stream != null) {
                    settleAutoPlay(earlyEvaluation)
                    return
                }

                if (
                    _uiState.value.groups.areAutoPlaySourcesLoaded(
                        source = playerSettings.streamAutoPlaySource,
                        installedAddonIds = installedAddonIds,
                    )
                ) {
                    settleAutoPlay(evaluateAutoPlay())
                }
            }

            fun publishCompletion(completion: StreamLoadCompletion) {
                if (completions.trySend(completion).isFailure) {
                    log.d { "Ignoring late stream load completion after channel close" }
                }
            }

            fun presentStreamGroup(group: AddonStreamGroup): AddonStreamGroup {
                val badgeGroup = StreamBadgePresentation.apply(
                    groups = listOf(group),
                    rules = streamBadgeRules,
                ).firstOrNull() ?: group
                return DebridStreamPresentation.apply(
                    groups = listOf(badgeGroup),
                    settings = debridSettings,
                ).firstOrNull() ?: badgeGroup
            }

            fun publishAddonGroup(group: AddonStreamGroup) {
                _uiState.update { current ->
                    val updated = StreamAutoPlaySelector.orderAddonStreams(
                        groups = current.groups.map { currentGroup ->
                            if (currentGroup.addonId == group.addonId) group else currentGroup
                        },
                        installedOrder = installedAddonOrder,
                        preferredGroupIds = preferredGroupIds,
                    )
                    val anyLoading = updated.any { it.isLoading }
                    current.copy(
                        groups = updated,
                        isAnyLoading = anyLoading,
                        emptyStateReason = updated.toEmptyStateReason(anyLoading),
                    )
                }
                updateAutoPlayAfterStreamsChanged()
            }

            fun publishAddonGroupAfterCacheCheck(group: AddonStreamGroup) {
                if (group.addonId !in installedAddonIds || group.streams.isEmpty()) {
                    publishAddonGroup(presentStreamGroup(group))
                    return
                }

                val eligibleGroupIds = setOf(group.addonId)
                val shouldWaitForCacheCheck = LocalDebridAvailabilityService.hasPendingCacheCheck(
                    groups = listOf(group),
                    eligibleGroupIds = eligibleGroupIds,
                )
                if (!shouldWaitForCacheCheck) {
                    publishAddonGroup(presentStreamGroup(group))
                    return
                }

                val checkingGroup = LocalDebridAvailabilityService.markChecking(
                    groups = listOf(group),
                    eligibleGroupIds = eligibleGroupIds,
                ).firstOrNull() ?: group

                val availabilityJob = launch {
                    val availabilityGroup = LocalDebridAvailabilityService.annotateCachedAvailability(
                        groups = listOf(checkingGroup),
                        eligibleGroupIds = eligibleGroupIds,
                    ).firstOrNull() ?: checkingGroup
                    publishAddonGroup(presentStreamGroup(availabilityGroup))
                }
                debridAvailabilityJobs += availabilityJob
            }

            updateAutoPlayAfterStreamsChanged()

            val timeoutJob = if (isDirectAutoPlayFlow) {
                val timeoutSeconds = playerSettings.streamAutoPlayTimeoutSeconds
                val isUnlimitedTimeout = timeoutSeconds == Int.MAX_VALUE
                // Timeout semantics:
                // - 0 (instant): timeoutElapsed immediately, full select on each response
                // - 1-30 (bounded): wait the configured delay, then full select
                // - unlimited (Int.MAX_VALUE): timeoutElapsed immediately, full select on each response,
                //   with 60s hard fallback to stream picker
                if (timeoutSeconds <= 0 || isUnlimitedTimeout) {
                    timeoutElapsed = true
                    // For unlimited: launch a hard 60s fallback to dismiss overlay
                    if (isUnlimitedTimeout) {
                        launch {
                            delay(60_000L)
                            if (!autoSelectTriggered) {
                                settleAutoPlay(evaluateAutoPlay())
                            }
                        }
                    } else {
                        null
                    }
                } else {
                    // Bounded timeout (1-30s)
                    launch {
                        delay(timeoutSeconds * 1_000L)
                        timeoutElapsed = true
                        if (!autoSelectTriggered) {
                            val allStreams = _uiState.value.groups.flatMap { it.streams }
                            if (allStreams.isNotEmpty()) {
                                val evaluation = evaluateAutoPlay()
                                if (evaluation.stream != null || !evaluation.hasPendingDebridCandidate) {
                                    settleAutoPlay(evaluation)
                                }
                            }
                        }
                    }
                }
            } else {
                null
            }

            streamAddons.forEach { addon ->
                launch {
                    val url = buildAddonResourceUrl(
                        manifestUrl = addon.manifest.transportUrl,
                        resource = "stream",
                        type = type,
                        id = videoId,
                    )
                    log.d { "Fetching streams from: $url" }

                    val displayName = addon.addonName
                    InAppLogger.info(
                        "Streams/AddonFetch",
                        "Fetching streams addon=$displayName addonId=${addon.addonId} url=${InAppLogger.redactUrl(url)}",
                    )
                    val group = runCatchingUnlessCancelled {
                        val payload = fetchAddonResponseText(
                            url = url,
                            forceRefresh = forceRefresh,
                        )
                        StreamParser.parse(
                            payload = payload,
                            addonName = displayName,
                            addonId = addon.addonId,
                            addonLogo = addon.manifest.logoUrl,
                        )
                    }.fold(
                        onSuccess = { streams ->
                            log.d { "Got ${streams.size} streams from ${displayName}" }
                            InAppLogger.info("Streams/AddonFetch", "Loaded streams addon=$displayName count=${streams.size}")
                            AddonStreamGroup(
                                addonName = displayName,
                                addonId = addon.addonId,
                                streams = streams,
                                isLoading = false,
                            )
                        },
                        onFailure = { err ->
                            log.w(err) { "Failed to fetch streams from ${displayName}" }
                            InAppLogger.warn("Streams/AddonFetch", "Failed addon=$displayName error=${InAppLogger.throwableSummary(err)}")
                            AddonStreamGroup(
                                addonName = displayName,
                                addonId = addon.addonId,
                                streams = emptyList(),
                                isLoading = false,
                                error = err.message,
                            )
                        },
                    )
                    publishCompletion(StreamLoadCompletion.Addon(group))
                }
            }

            serverSources.forEach { source ->
                launch { publishCompletion(StreamLoadCompletion.Addon(source.load())) }
            }

            pluginProviderGroups.forEach { providerGroup ->
                val includeScraperNameInSubtitle = false
                providerGroup.scrapers.forEach { scraper ->
                    launch {
                        InAppLogger.info(
                            "Streams/PluginFetch",
                            "Executing scraper=${scraper.name} provider=${providerGroup.addonName} type=$type id=$videoId season=${season ?: -1} episode=${episode ?: -1}",
                        )
                        val completion = PluginRepository.executeScraper(
                            scraper = scraper,
                            tmdbId = pluginContentId(
                                videoId = videoId,
                                season = season,
                                episode = episode,
                            ),
                            mediaType = type,
                            season = season,
                            episode = episode,
                        ).fold(
                            onSuccess = { results ->
                                InAppLogger.info("Streams/PluginFetch", "Scraper loaded scraper=${scraper.name} provider=${providerGroup.addonName} count=${results.size}")
                                StreamLoadCompletion.PluginScraper(
                                    addonId = providerGroup.addonId,
                                    streams = results.map { result ->
                                        result.toStreamItem(
                                            scraper = scraper,
                                            addonName = providerGroup.addonName,
                                            addonId = providerGroup.addonId,
                                            includeScraperNameInSubtitle = includeScraperNameInSubtitle,
                                        )
                                    },
                                    error = null,
                                )
                            },
                            onFailure = { error ->
                                InAppLogger.warn("Streams/PluginFetch", "Scraper failed scraper=${scraper.name} provider=${providerGroup.addonName} error=${InAppLogger.throwableSummary(error)}")
                                StreamLoadCompletion.PluginScraper(
                                    addonId = providerGroup.addonId,
                                    streams = emptyList(),
                                    error = error.message ?: getString(Res.string.streams_failed_to_load_scraper, scraper.name),
                                )
                            },
                        )
                        publishCompletion(completion)
                    }
                }
            }
            cloudStreamProviderGroups.forEach { providerGroup ->
                launch {
                    val group = withTimeoutOrNull(STREAM_PROVIDER_TIMEOUT_MS) {
                        cloudStreamSemaphore.withPermit {
                            resolveCloudStreamProviderStreams(
                                providerGroup = providerGroup,
                                request = cloudStreamSearchRequest,
                            )
                        }
                    } ?: AddonStreamGroup(
                        addonName = providerGroup.addonName,
                        addonId = providerGroup.addonId,
                        streams = emptyList(),
                        isLoading = false,
                        error = "${providerGroup.addonName} timed out",
                    )
                    publishCompletion(StreamLoadCompletion.Addon(group))
                }
            }
            vegaProviders.forEach { provider ->
                launch {
                    val group = withTimeoutOrNull(STREAM_PROVIDER_TIMEOUT_MS) {
                        resolveVegaProviderStreams(
                            provider = provider,
                            type = type,
                            videoId = videoId,
                            parentMetaId = parentMetaId,
                            parentMetaType = parentMetaType,
                            season = season,
                            episode = episode,
                            searchTitle = searchTitle,
                        )
                    } ?: AddonStreamGroup(
                        addonName = "Vega - ${provider.displayName}",
                        addonId = "vega:${provider.value}",
                        streams = emptyList(),
                        isLoading = false,
                        error = "${provider.displayName} timed out",
                    )
                    publishCompletion(StreamLoadCompletion.Addon(group))
                }
            }

            repeat(totalTasks) {
                when (val completion = completions.receive()) {
                    is StreamLoadCompletion.Addon -> {
                        val result = completion.group
                        publishAddonGroupAfterCacheCheck(result)
                    }

                    is StreamLoadCompletion.PluginScraper -> {
                        val remaining = (pluginRemainingByAddonId[completion.addonId] ?: 1) - 1
                        pluginRemainingByAddonId[completion.addonId] = remaining.coerceAtLeast(0)
                        if (!completion.error.isNullOrBlank() && pluginFirstErrorByAddonId[completion.addonId].isNullOrBlank()) {
                            pluginFirstErrorByAddonId[completion.addonId] = completion.error
                        }

                        _uiState.update { current ->
                            val updated = StreamAutoPlaySelector.orderAddonStreams(
                                groups = current.groups.map { group ->
                                    if (group.addonId != completion.addonId) {
                                        group
                                    } else {
                                        val mergedStreams = if (completion.streams.isEmpty()) {
                                            group.streams
                                        } else {
                                            (group.streams + completion.streams).sortedForGroupedDisplay()
                                        }
                                        val stillLoading = remaining > 0
                                        val finalError = if (mergedStreams.isEmpty() && !stillLoading) {
                                            pluginFirstErrorByAddonId[completion.addonId]
                                        } else {
                                            null
                                        }
                                        group.copy(
                                            streams = mergedStreams,
                                            isLoading = stillLoading,
                                            error = finalError,
                                        )
                                    }
                                },
                                installedOrder = installedAddonOrder,
                                preferredGroupIds = preferredGroupIds,
                            )
                            val anyLoading = updated.any { it.isLoading }
                            current.copy(
                                groups = updated,
                                isAnyLoading = anyLoading,
                                emptyStateReason = updated.toEmptyStateReason(anyLoading),
                            )
                        }
                        updateAutoPlayAfterStreamsChanged()
                    }

                }
            }

            for (availabilityJob in debridAvailabilityJobs) {
                availabilityJob.join()
            }

            launch {
                DirectDebridStreamPreparer.prepare(
                    streams = _uiState.value.groups
                        .filter { it.addonId in installedAddonIds }
                        .flatMap { it.streams },
                    season = season,
                    episode = episode,
                    playerSettings = playerSettings,
                    installedAddonNames = installedAddonNames,
                ) { original, prepared ->
                    _uiState.update { current ->
                        current.copy(
                            groups = DirectDebridStreamPreparer.replacePreparedStream(
                                groups = current.groups,
                                original = original,
                                prepared = prepared,
                                eligibleGroupIds = installedAddonIds,
                            ),
                        )
                    }
                    updateAutoPlayAfterStreamsChanged()
                }
            }

            if (isDirectAutoPlayFlow && !autoSelectTriggered) {
                settleAutoPlay(evaluateAutoPlay())
            }
            InAppLogger.info(
                "Streams/StreamsRepository",
                "Finished loading streams type=$type id=$videoId groups=${_uiState.value.groups.size} streams=${_uiState.value.groups.sumOf { it.streams.size }}",
            )
            timeoutJob?.cancel()
        }
    }

    fun selectFilter(addonId: String?) {
        _uiState.update { it.copy(selectedFilter = addonId) }
    }

    fun consumeAutoPlay() {
        activeRequestKey = null
        _uiState.update {
            it.copy(
                autoPlayStream = null,
                autoPlayCandidates = emptyList(),
                isDirectAutoPlayFlow = false,
                showDirectAutoPlayOverlay = false,
            )
        }
    }

    fun skipAutoPlayStream(stream: StreamItem): Boolean {
        var hasNext = false
        _uiState.update { current ->
            val failedIndex = current.autoPlayCandidates.indexOf(stream)
            val remaining = if (failedIndex >= 0) {
                current.autoPlayCandidates.drop(failedIndex + 1)
            } else {
                current.autoPlayCandidates.drop(1)
            }
            hasNext = remaining.isNotEmpty()
            current.copy(
                autoPlayStream = remaining.firstOrNull(),
                autoPlayCandidates = remaining,
                isDirectAutoPlayFlow = remaining.isNotEmpty(),
                showDirectAutoPlayOverlay = remaining.isNotEmpty(),
                overlayMessage = null,
            )
        }
        return hasNext
    }

    fun cancelLoading() {
        PluginRepository.setLocalPluginSearchPaused(true)
        activeJob?.cancel()
        activeJob = null
        _uiState.update { current ->
            if (!current.isAnyLoading && current.groups.none { it.isLoading }) {
                current
            } else {
                val updatedGroups = current.groups.map { group ->
                    if (group.isLoading) group.copy(isLoading = false) else group
                }
                current.copy(
                    groups = updatedGroups,
                    isAnyLoading = false,
                    emptyStateReason = if (updatedGroups.isEmpty()) {
                        current.emptyStateReason
                    } else {
                        updatedGroups.toEmptyStateReason(anyLoading = false)
                    },
                )
            }
        }
    }

    fun clear() {
        PluginRepository.setLocalPluginSearchPaused(true)
        activeJob?.cancel()
        activeJob = null
        activeRequestKey = null
        _uiState.value = StreamsUiState()
    }

    fun setOverlayVisible(visible: Boolean, message: String? = null) {
        _uiState.update { it.copy(showDirectAutoPlayOverlay = visible, overlayMessage = message) }
    }
}
