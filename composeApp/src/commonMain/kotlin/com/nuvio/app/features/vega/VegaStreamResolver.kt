package com.nuvio.app.features.vega

import com.nuvio.app.features.streams.AddonStreamGroup

internal expect suspend fun resolveVegaProviderStreams(
    provider: VegaProviderItem,
    type: String,
    videoId: String,
    parentMetaId: String?,
    parentMetaType: String?,
    season: Int?,
    episode: Int?,
    searchTitle: String?,
): AddonStreamGroup
