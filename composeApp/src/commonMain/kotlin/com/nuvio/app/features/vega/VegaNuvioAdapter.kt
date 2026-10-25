package com.nuvio.app.features.vega

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import kotlinx.serialization.Serializable

private const val VEGA_ROUTE_PREFIX = "vega:"

data class VegaRouteData(
    val providerId: String,
    val link: String,
)

fun vegaRouteId(providerId: String, link: String): String =
    "$VEGA_ROUTE_PREFIX${providerId.length}:$providerId$link"

fun parseVegaRouteId(value: String): VegaRouteData? {
    if (!value.startsWith(VEGA_ROUTE_PREFIX)) return null
    val payload = value.removePrefix(VEGA_ROUTE_PREFIX)
    val separator = payload.indexOf(':')
    if (separator <= 0) return null
    val providerLength = payload.substring(0, separator).toIntOrNull() ?: return null
    val content = payload.substring(separator + 1)
    if (providerLength <= 0 || providerLength > content.length) return null
    val providerId = content.substring(0, providerLength)
    val link = content.substring(providerLength)
    if (providerId.isBlank() || link.isBlank()) return null
    return VegaRouteData(providerId = providerId, link = link)
}

@Serializable
data class VegaCatalogCategory(
    val title: String,
    val filter: String = "",
)

@Serializable
data class VegaPost(
    val title: String,
    val link: String,
    val image: String = "",
    val tag: String? = null,
)

@Serializable
data class VegaEpisode(
    val title: String,
    val link: String,
    val description: String? = null,
    val image: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

@Serializable
data class VegaDirectLink(
    val title: String = "",
    val link: String = "",
    val type: String? = null,
)

@Serializable
data class VegaLinkGroup(
    val title: String = "",
    val quality: String? = null,
    val episodesLink: String? = null,
    val directLinks: List<VegaDirectLink> = emptyList(),
)

@Serializable
data class VegaMetaResult(
    val title: String,
    val synopsis: String? = null,
    val image: String? = null,
    val imdbId: String? = null,
    val type: String = "movie",
    val genres: List<String> = emptyList(),
    val episodes: List<VegaEpisode> = emptyList(),
    val linkList: List<VegaLinkGroup> = emptyList(),
)

fun VegaPost.toMetaPreview(providerId: String): MetaPreview = MetaPreview(
    id = vegaRouteId(providerId, link),
    type = "movie",
    name = title,
    poster = image.ifBlank { null },
    banner = image.ifBlank { null },
    posterShape = PosterShape.Poster,
    description = tag,
)

fun VegaMetaResult.toMetaDetails(providerId: String, routeLink: String): MetaDetails {
    val routeId = vegaRouteId(providerId, routeLink)
    val videos = when {
        episodes.isNotEmpty() -> {
            episodes.mapIndexed { index, ep ->
                MetaVideo(
                    id = vegaRouteId(providerId, ep.link),
                    title = ep.title.ifBlank { "Episode ${index + 1}" },
                    season = ep.season ?: 1,
                    episode = ep.episode ?: (index + 1),
                    available = true,
                    thumbnail = ep.image ?: image,
                    overview = ep.description,
                )
            }
        }
        linkList.any { it.directLinks.isNotEmpty() } -> {
            linkList.flatMap { group ->
                group.directLinks.mapIndexed { idx, dl ->
                    val videoTitle = listOfNotNull(
                        group.title.takeIf { it.isNotBlank() },
                        group.quality?.takeIf { it.isNotBlank() },
                        dl.title.takeIf { it.isNotBlank() && it != "Movie" },
                    ).joinToString(" • ").ifBlank { title }
                    MetaVideo(
                        id = vegaRouteId(providerId, dl.link.ifBlank { routeLink }),
                        title = videoTitle,
                        available = true,
                        thumbnail = image,
                    )
                }
            }.ifEmpty {
                listOf(
                    MetaVideo(
                        id = routeId,
                        title = title,
                        available = true,
                        thumbnail = image,
                    ),
                )
            }
        }
        else -> {
            listOf(
                MetaVideo(
                    id = routeId,
                    title = title,
                    available = true,
                    thumbnail = image,
                ),
            )
        }
    }

    val isSeries = episodes.isNotEmpty() ||
        type.equals("series", ignoreCase = true) ||
        type.equals("tv", ignoreCase = true) ||
        linkList.any { it.episodesLink != null }

    return MetaDetails(
        id = routeId,
        type = if (isSeries) "series" else "movie",
        name = title,
        poster = image,
        background = image,
        description = synopsis,
        genres = genres,
        imdbId = imdbId,
        videos = videos,
    )
}
