package com.nuvio.app.features.vega

import com.nuvio.app.features.meta.MetaDetailsRepository
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.cloudStreamReleaseYear

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
    val metaLookupType = parentMetaType?.takeIf { it.isNotBlank() } ?: type
    val metaLookupId = parentMetaId?.takeIf { it.isNotBlank() } ?: videoId
    val meta = MetaDetailsRepository.peek(metaLookupType, metaLookupId)
    val title = searchTitle?.trim()?.takeIf { it.isNotBlank() }
        ?: meta?.name?.trim()?.takeIf { it.isNotBlank() }
        ?: ""
    val tmdbId = pluginContentId(videoId = videoId, season = season, episode = episode)
    val imdbId = sequenceOf(videoId, parentMetaId, metaLookupId)
        .filterNotNull()
        .map(String::trim)
        .firstOrNull { id -> id.matches(Regex("^tt\\d{5,12}$", RegexOption.IGNORE_CASE)) }
        ?: meta?.imdbId
    val year = meta?.releaseInfo.cloudStreamReleaseYear()?.toString()

    val script = VegaRepository.getOrFetchProviderScript(provider)
        ?: return AddonStreamGroup(
            addonName = "Vega - ${provider.displayName}",
            addonId = "vega:${provider.value}",
            streams = emptyList(),
            isLoading = false,
            error = "Could not fetch provider script",
        )

    val streams = VegaRuntime.executeProvider(
        provider = provider,
        code = script,
        tmdbId = tmdbId,
        imdbId = imdbId,
        title = title,
        year = year,
        mediaType = if (type.equals("movie", ignoreCase = true)) "movie" else "tv",
        season = season,
        episode = episode,
    )

    return AddonStreamGroup(
        addonName = "Vega - ${provider.displayName}",
        addonId = "vega:${provider.value}",
        streams = streams,
        isLoading = false,
        error = if (streams.isEmpty()) "No streams found" else null,
    )
}
