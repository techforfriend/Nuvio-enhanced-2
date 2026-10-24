package com.nuvio.app.features.details.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.details.EpisodeRatingsVisibility
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.SPECIALS_SEASON_NUMBER
import com.nuvio.app.features.details.groupedEpisodesForDisplay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.absoluteValue
import kotlin.math.floor
import kotlin.math.roundToInt

private enum class RatingMapSource(val label: String) {
    IMDB("IMDb"),
    TMDB("TMDB"),
    OTHER(""),
}

private data class RatingMapCell(
    val video: MetaVideo,
    val season: Int,
    val episode: Int,
    val rating: Double?,
    val source: RatingMapSource?,
    val isHidden: Boolean,
) {
    val key: String = "$season:$episode:${video.id}"
    val visibleRating: Double? get() = rating.takeUnless { isHidden }
}

private data class RatingMapSeason(
    val number: Int,
    val cells: List<RatingMapCell>,
) {
    val visibleRated: List<RatingMapCell> = cells.filter { it.visibleRating != null }
    val average: Double? = visibleRated.mapNotNull { it.visibleRating }.takeIf { it.isNotEmpty() }?.average()
}

private data class RatingMapTier(
    val minRating: Double,
    val label: String,
    val fill: Color,
    val content: Color,
)

private val ratingMapTiers = listOf(
    RatingMapTier(9.0, "9+", Color(0xFF1B7F45), Color.White),
    RatingMapTier(8.0, "8–9", Color(0xFF3DB46D), Color(0xFF06331A)),
    RatingMapTier(7.0, "7–8", Color(0xFFF2C94C), Color(0xFF4A3703)),
    RatingMapTier(6.0, "6–7", Color(0xFFF2994A), Color(0xFF4A2200)),
    RatingMapTier(0.0, "<6", Color(0xFFE5534B), Color.White),
)

private fun tierFor(rating: Double): RatingMapTier =
    ratingMapTiers.first { rating >= it.minRating }

private fun MetaVideo.ratingMapScore(): Pair<Double, RatingMapSource>? {
    imdbRating?.takeIf { it > 0.0 }?.let { return it to RatingMapSource.IMDB }
    tmdbRating?.takeIf { it > 0.0 }?.let { return it to RatingMapSource.TMDB }
    rating?.takeIf { it > 0.0 }?.let { return it to RatingMapSource.OTHER }
    return null
}

private fun formatRatingMapScore(rating: Double): String {
    val roundedTenths = (rating * 10.0).roundToInt()
    val whole = roundedTenths / 10
    val tenth = (roundedTenths % 10).absoluteValue
    return "$whole.$tenth"
}

internal fun MetaDetails.hasEpisodeRatingMap(): Boolean {
    if (type == "movie") return false
    val rated = videos.count { video ->
        (video.season ?: SPECIALS_SEASON_NUMBER) > SPECIALS_SEASON_NUMBER &&
            video.episode != null &&
            video.ratingMapScore() != null
    }
    return rated >= 2
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailEpisodeRatingMap(
    meta: MetaDetails,
    episodeRatingsVisibility: EpisodeRatingsVisibility,
    isEpisodeWatched: (MetaVideo) -> Boolean,
    onEpisodeClick: (MetaVideo) -> Unit,
    onEpisodeLongPress: (MetaVideo) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!episodeRatingsVisibility.showRatings) return

    val seasons: List<RatingMapSeason> = remember(meta.videos, episodeRatingsVisibility, isEpisodeWatched) {
        meta.groupedEpisodesForDisplay()
            .filterKeys { it > SPECIALS_SEASON_NUMBER }
            .toList()
            .sortedBy { it.first }
            .map { (season, episodes) ->
                RatingMapSeason(
                    number = season,
                    cells = episodes
                        .filter { it.episode != null }
                        .distinctBy { it.episode }
                        .map { video ->
                            val score = video.ratingMapScore()
                            RatingMapCell(
                                video = video,
                                season = season,
                                episode = video.episode ?: 0,
                                rating = score?.first,
                                source = score?.second,
                                isHidden = score != null &&
                                    !episodeRatingsVisibility.showRating(isEpisodeWatched(video)),
                            )
                        },
                )
            }
            .filter { it.cells.isNotEmpty() }
    }
    if (seasons.isEmpty()) return

    var selectedSeasonNumber by rememberSaveable(meta.id) { mutableStateOf(seasons.first().number) }
    val season = seasons.firstOrNull { it.number == selectedSeasonNumber } ?: seasons.first()
    var selectedKey by rememberSaveable(meta.id, season.number) { mutableStateOf<String?>(null) }
    var showSeasonSheet by remember { mutableStateOf(false) }

    val bestCell = remember(season) { season.visibleRated.maxByOrNull { it.visibleRating ?: 0.0 } }
    val lowestCell = remember(season, bestCell) {
        season.visibleRated.minByOrNull { it.visibleRating ?: 0.0 }?.takeIf { it.key != bestCell?.key }
    }
    val selectedCell = season.cells.firstOrNull { it.key == selectedKey }
    val colorScheme = MaterialTheme.colorScheme
    val canPickSeason = seasons.size > 1

    DetailSection(
        title = stringResource(Res.string.details_rating_map),
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .then(
                            if (canPickSeason) {
                                Modifier.clickable { showSeasonSheet = true }
                            } else {
                                Modifier
                            },
                        )
                        .padding(start = 14.dp, end = if (canPickSeason) 8.dp else 14.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.episodes_season, season.number),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                    )
                    if (canPickSeason) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = stringResource(Res.string.details_seasons),
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                season.average?.let { average ->
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = Color(0xFFF5C518),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(
                                Res.string.details_rating_map_summary,
                                formatRatingMapScore(average),
                                season.visibleRated.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val gap = 4.dp
                val targetCell = if (maxWidth >= 720.dp) 46.dp else 36.dp
                val columns = floor(((maxWidth + gap) / (targetCell + gap)).toDouble())
                    .toInt()
                    .coerceIn(5, 25)
                val cellSize: Dp = (maxWidth - gap * (columns - 1)) / columns

                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    season.cells.chunked(columns).forEach { rowCells ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            rowCells.forEach { cell ->
                                RatingMapCellView(
                                    cell = cell,
                                    size = cellSize,
                                    selected = cell.key == selectedKey,
                                    onClick = {
                                        selectedKey = if (selectedKey == cell.key) null else cell.key
                                    },
                                    onLongClick = { onEpisodeLongPress(cell.video) },
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ratingMapTiers.forEach { tier ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(tier.fill),
                        )
                        Text(
                            text = tier.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (selectedCell != null) {
                RatingMapSelectionPanel(
                    cell = selectedCell,
                    shape = RoundedCornerShape(16.dp),
                    onPlay = { onEpisodeClick(selectedCell.video) },
                )
            } else {
                Text(
                    text = stringResource(Res.string.details_rating_map_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }

            if (bestCell != null || lowestCell != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bestCell?.let { cell ->
                        RatingMapHighlightChip(
                            label = stringResource(
                                Res.string.details_rating_map_best,
                                localizedSeasonEpisodeCode(cell.season, cell.episode).orEmpty(),
                            ),
                            tier = tierFor(cell.visibleRating ?: 0.0),
                            onClick = { selectedKey = cell.key },
                        )
                    }
                    lowestCell?.let { cell ->
                        RatingMapHighlightChip(
                            label = stringResource(
                                Res.string.details_rating_map_lowest,
                                localizedSeasonEpisodeCode(cell.season, cell.episode).orEmpty(),
                            ),
                            tier = tierFor(cell.visibleRating ?: 0.0),
                            onClick = { selectedKey = cell.key },
                        )
                    }
                }
            }
        }
    }

    if (showSeasonSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        NuvioModalBottomSheet(
            onDismissRequest = { showSeasonSheet = false },
            sheetState = sheetState,
        ) {
            Text(
                text = stringResource(Res.string.details_seasons),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                contentPadding = PaddingValues(bottom = nuvioSafeBottomPadding(16.dp)),
            ) {
                items(seasons, key = { it.number }) { item ->
                    NuvioBottomSheetActionRow(
                        title = stringResource(Res.string.episodes_season, item.number),
                        selected = item.number == season.number,
                        onClick = {
                            scope.launch {
                                dismissNuvioBottomSheet(sheetState) {
                                    selectedSeasonNumber = item.number
                                    showSeasonSheet = false
                                }
                            }
                        },
                        trailingContent = {
                            Text(
                                text = stringResource(Res.string.details_rating_map_season_rated, item.visibleRated.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            RatingMapAverageBadge(average = item.average)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RatingMapAverageBadge(average: Double?) {
    val colorScheme = MaterialTheme.colorScheme
    val tier = average?.let(::tierFor)
    Box(
        modifier = Modifier
            .width(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(tier?.fill ?: colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = average?.let(::formatRatingMapScore) ?: "–",
            color = tier?.content ?: colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RatingMapCellView(
    cell: RatingMapCell,
    size: Dp,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val rating = cell.visibleRating
    val tier = rating?.let(::tierFor)
    val fill = tier?.fill ?: colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val content = tier?.content ?: colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val label = when {
        rating != null -> formatRatingMapScore(rating)
        cell.isHidden -> "?"
        else -> "–"
    }
    val description = localizedSeasonEpisodeCode(cell.season, cell.episode).orEmpty() +
        (rating?.let { " · ${formatRatingMapScore(it)}" } ?: "")
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(fill)
            .then(
                if (selected) {
                    Modifier.border(2.dp, colorScheme.onBackground, shape)
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = content,
            fontSize = if (size >= 40.dp) 12.sp else 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun RatingMapSelectionPanel(
    cell: RatingMapCell,
    shape: RoundedCornerShape,
    onPlay: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val code = localizedSeasonEpisodeCode(cell.season, cell.episode).orEmpty()
    val title = cell.video.title.takeIf { it.isNotBlank() }
    val rating = cell.visibleRating
    val subtitle = when {
        rating != null && cell.source != null && cell.source.label.isNotEmpty() ->
            stringResource(Res.string.details_rating_map_rated, formatRatingMapScore(rating), cell.source.label)
        rating != null -> formatRatingMapScore(rating)
        cell.isHidden -> stringResource(Res.string.details_rating_map_hidden)
        else -> stringResource(Res.string.details_rating_map_unrated)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        rating?.let {
            val tier = tierFor(it)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tier.fill),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formatRatingMapScore(it),
                    color = tier.content,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (title != null) "$code · $title" else code,
                style = MaterialTheme.typography.titleSmall,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colorScheme.primary)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = stringResource(Res.string.action_play),
                tint = colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun RatingMapHighlightChip(
    label: String,
    tier: RatingMapTier,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tier.fill.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tier.fill),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
