package com.nuvio.app.features.streams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.supportsResource
import com.nuvio.app.features.cloudstream.CloudStreamRepository
import com.nuvio.app.features.cloudstream.parseCloudStreamRouteId
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.PluginsUiState
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.ServerStreams

import com.nuvio.app.features.vega.VegaRepository
import com.nuvio.app.features.vega.parseVegaRouteId

internal fun AddonManifest.supportsStream(type: String, videoId: String): Boolean =
    supportsResource("stream", type, videoId)

internal fun hasCompatiblePlaybackSource(
    addons: List<ManagedAddon>,
    plugins: PluginsUiState,
    type: String,
    videoId: String,
    hasCloudStreamProviders: Boolean = false,
    hasVegaProviders: Boolean = false,
): Boolean = addons.any { it.enabled && it.manifest?.supportsStream(type, videoId) == true } ||
    (plugins.pluginsEnabled && plugins.scrapers.any { it.enabled && it.supportsType(type) }) ||
    parseCloudStreamRouteId(videoId) != null ||
    parseVegaRouteId(videoId) != null ||
    hasCloudStreamProviders ||
    hasVegaProviders

internal class PlaybackAvailability(
    private val addons: List<ManagedAddon>,
    private val plugins: PluginsUiState,
    private val hasCloudStreamProviders: Boolean = false,
    private val hasVegaProviders: Boolean = false,
) {
    fun canStream(type: String, videoId: String, parentMetaId: String? = null): Boolean =
        hasCompatiblePlaybackSource(addons, plugins, type, videoId, hasCloudStreamProviders, hasVegaProviders) ||
            MetaDetailsRepository.findEmbeddedStreams(videoId).isNotEmpty() ||
            ServerStreams.canServe(type, videoId) ||
            parseCloudStreamRouteId(videoId) != null ||
            parseVegaRouteId(videoId) != null ||
            (parentMetaId != null && (parseCloudStreamRouteId(parentMetaId) != null || parseVegaRouteId(parentMetaId) != null))

    fun canPlay(
        type: String,
        videoId: String,
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): Boolean = canStream(type, videoId, parentMetaId) ||
        parseCloudStreamRouteId(parentMetaId) != null ||
        parseVegaRouteId(parentMetaId) != null ||
        DownloadsRepository.findPlayableDownload(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            videoId = videoId,
        ) != null

    companion object {
        fun current(): PlaybackAvailability = PlaybackAvailability(
            addons = AddonRepository.uiState.value.addons,
            plugins = if (AppFeaturePolicy.pluginsEnabled) {
                PluginRepository.uiState.value
            } else {
                PluginsUiState(pluginsEnabled = false)
            },
            hasCloudStreamProviders = if (AppFeaturePolicy.pluginsEnabled) {
                CloudStreamRepository.uiState.value.plugins.any { it.isRunnable }
            } else {
                false
            },
            hasVegaProviders = if (AppFeaturePolicy.pluginsEnabled) {
                VegaRepository.initialize()
                VegaRepository.getEnabledProviders().isNotEmpty()
            } else {
                false
            },
        )
    }
}

@Composable
internal fun rememberPlaybackAvailability(): PlaybackAvailability {
    val addons by remember {
        AddonRepository.initialize()
        AddonRepository.uiState
    }.collectAsStateWithLifecycle()
    val plugins = if (AppFeaturePolicy.pluginsEnabled) {
        val state by remember {
            PluginRepository.initialize()
            PluginRepository.uiState
        }.collectAsStateWithLifecycle()
        state
    } else {
        PluginsUiState(pluginsEnabled = false)
    }
    val cloudStreamState = if (AppFeaturePolicy.pluginsEnabled) {
        val state by remember {
            CloudStreamRepository.initialize()
            CloudStreamRepository.uiState
        }.collectAsStateWithLifecycle()
        state
    } else null
    val vegaState = if (AppFeaturePolicy.pluginsEnabled) {
        val state by remember {
            VegaRepository.initialize()
            VegaRepository.uiState
        }.collectAsStateWithLifecycle()
        state
    } else null
    val downloads by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    val servers by remember {
        ServerRepository.ensureLoaded()
        ServerRepository.uiState
    }.collectAsStateWithLifecycle()
    val hasCloudStreamProviders = cloudStreamState?.plugins?.any { it.isRunnable } == true
    val hasVegaProviders = vegaState?.let { it.vegaEnabled && it.providers.any { p -> p.enabled } } == true
    return remember(addons, plugins, downloads, servers.revision, hasCloudStreamProviders, hasVegaProviders) {
        PlaybackAvailability(addons.addons, plugins, hasCloudStreamProviders, hasVegaProviders)
    }
}
