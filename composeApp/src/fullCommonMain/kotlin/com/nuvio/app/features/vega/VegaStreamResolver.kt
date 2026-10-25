package com.nuvio.app.features.vega

import com.nuvio.app.features.streams.AddonStreamGroup

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
    if (vegaRoute != null && vegaRoute.providerValue == provider.value) {
        val streams = VegaBridge.getStreams(
            provider = provider,
            link = vegaRoute.contentLink,
            type = type,
        )
        return AddonStreamGroup(
            addonName = "Vega - ${provider.displayName}",
            addonId = "vega:${provider.value}",
            streams = streams,
            isLoading = false,
            error = if (streams.isEmpty()) "No streams found" else null,
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
