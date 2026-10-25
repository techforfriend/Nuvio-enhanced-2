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
): AddonStreamGroup = AddonStreamGroup(
    addonName = "Vega - ${provider.displayName}",
    addonId = "vega:${provider.value}",
    streams = emptyList(),
    isLoading = false,
)
