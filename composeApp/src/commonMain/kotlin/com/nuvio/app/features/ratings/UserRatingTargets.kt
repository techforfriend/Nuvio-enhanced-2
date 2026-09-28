package com.nuvio.app.features.ratings

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingRatingScope
import com.nuvio.app.features.tracking.TrackingRatingTarget
import com.nuvio.app.features.tracking.buildTrackingMediaReference

private fun MetaDetails.mediaReference(video: MetaVideo? = null): TrackingMediaReference {
    val reference = buildTrackingMediaReference(
        contentType = type,
        parentMetaId = id,
        videoId = video?.id,
        title = name,
        releaseInfo = releaseInfo,
        seasonNumber = video?.season,
        episodeNumber = video?.episode,
        episodeTitle = video?.title,
    )
    val imdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
    return if (imdb == null) reference else reference.copy(ids = reference.ids.mergeMissing(TrackingExternalIds(imdb = imdb)))
}

/** The movie itself, or the whole show. */
fun MetaDetails.userRatingTarget(): TrackingRatingTarget {
    val media = mediaReference()
    return media.toRatingTarget(
        scope = if (media.kind == TrackingMediaKind.MOVIE) TrackingRatingScope.MOVIE else TrackingRatingScope.SHOW,
    )
}

fun MetaDetails.seasonUserRatingTarget(season: Int): TrackingRatingTarget =
    mediaReference().toRatingTarget(scope = TrackingRatingScope.SEASON, season = season)

fun MetaDetails.episodeUserRatingTarget(video: MetaVideo): TrackingRatingTarget? {
    val season = video.season ?: return null
    val episode = video.episode ?: return null
    return mediaReference(video).toRatingTarget(
        scope = TrackingRatingScope.EPISODE,
        season = season,
        episode = episode,
        episodeTitle = video.title,
    )
}

/** Target for whatever [this] media reference points at (an episode when it carries one). */
fun TrackingMediaReference.toUserRatingTarget(): TrackingRatingTarget? {
    val episode = episode
    return when {
        kind == TrackingMediaKind.MOVIE -> toRatingTarget(TrackingRatingScope.MOVIE)
        episode != null && episode.season != null -> toRatingTarget(
            scope = TrackingRatingScope.EPISODE,
            season = episode.season,
            episode = episode.number,
            episodeTitle = episode.title,
        )
        episode == null -> toRatingTarget(TrackingRatingScope.SHOW)
        else -> null
    }
}

private fun TrackingMediaReference.toRatingTarget(
    scope: TrackingRatingScope,
    season: Int? = null,
    episode: Int? = null,
    episodeTitle: String? = null,
): TrackingRatingTarget = TrackingRatingTarget(
    scope = scope,
    kind = kind,
    ids = ids,
    title = title,
    year = year,
    season = season,
    episode = episode,
    episodeTitle = episodeTitle,
    catalog = catalog,
)
