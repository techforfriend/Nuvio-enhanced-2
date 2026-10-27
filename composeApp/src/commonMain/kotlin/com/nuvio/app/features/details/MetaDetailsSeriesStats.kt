package com.nuvio.app.features.details

data class SeriesStats(
    val seasonCount: Int,
    val episodeCount: Int,
)

fun MetaDetails.mainSeriesStats(): SeriesStats? {
    val isSeriesLike = type == "series" || videos.any { it.season != null || it.episode != null }
    if (!isSeriesLike) return null
    val seasonCount = videos
        .mapNotNull { video -> video.season?.takeIf { it > 0 } }
        .distinct()
        .size
    val episodeCount = videos
        .map { video ->
            when {
                video.season != null || video.episode != null -> "${video.season ?: -1}:${video.episode ?: -1}"
                video.id.isNotBlank() -> video.id
                else -> video.title
            }
        }
        .distinct()
        .size
    if (seasonCount == 0 && episodeCount == 0) return null
    return SeriesStats(seasonCount = seasonCount, episodeCount = episodeCount)
}
