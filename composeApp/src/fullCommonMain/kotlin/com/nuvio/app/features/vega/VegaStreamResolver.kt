package com.nuvio.app.features.vega

import com.nuvio.app.features.streams.AddonStreamGroup
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal actual suspend fun resolveVegaProviderStreams(
    provider: VegaProviderItem,
    type: String,
    videoId: String,
    parentMetaId: String?,
    parentMetaType: String?,
    season: Int?,
    episode: Int?,
    searchTitle: String?,
): AddonStreamGroup {
    val vegaRoute = parseVegaRouteId(videoId) ?: parentMetaId?.let { parseVegaRouteId(it) }
    if (vegaRoute != null && vegaRoute.providerId == provider.value) {
        val targetLink = vegaRoute.link
        val isEpisodeOrDirect = targetLink.startsWith("{") ||
            targetLink.contains("hubcloud") ||
            targetLink.contains("vcloud") ||
            targetLink.contains("vegadrive") ||
            targetLink.contains("fastdl") ||
            targetLink.contains("gofile") ||
            targetLink.contains("nexdrive")

        val targetLinks = if (type == "movie" && !isEpisodeOrDirect) {
            val meta = VegaBridge.getMetaDetails(provider, targetLink)
            val directLinks = meta?.videos?.mapNotNull { parseVegaRouteId(it.id)?.link }?.distinct()
                ?: emptyList()
            directLinks.ifEmpty { listOf(targetLink) }
        } else {
            listOf(targetLink)
        }

        val allStreams = mutableListOf<com.nuvio.app.features.streams.StreamItem>()
        coroutineScope {
            targetLinks.forEach { dl ->
                launch {
                    val s = VegaBridge.getStreams(provider = provider, link = dl, type = type)
                    synchronized(allStreams) {
                        allStreams.addAll(s)
                    }
                }
            }
        }
        val distinctStreams = allStreams.distinctBy { it.url }
        return AddonStreamGroup(
            addonName = "Vega - ${provider.displayName}",
            addonId = "vega:${provider.value}",
            streams = distinctStreams,
            isLoading = false,
            error = if (distinctStreams.isEmpty()) "No streams found" else null,
        )
    }

    return AddonStreamGroup(
        addonName = "Vega - ${provider.displayName}",
        addonId = "vega:${provider.value}",
        streams = emptyList(),
        isLoading = false,
        error = null,
    )
}
