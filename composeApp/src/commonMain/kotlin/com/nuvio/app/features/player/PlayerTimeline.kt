package com.nuvio.app.features.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedEpisodeTitle
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.themePalette
import com.nuvio.app.features.player.seekpreview.SeekPreviewController
import com.nuvio.app.features.player.seekpreview.SeekPreviewOverlay
import com.nuvio.app.features.streams.StreamBadge
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal val PlayerTimelineContentInset = 2.dp

internal val PlayerChapterMarkWidth = 2.dp

internal val PlayerChapterMarkColor = Color.Black.copy(alpha = 0.6f)

@Composable
internal fun PlayerTimelineDetails(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    releaseInfo: String?,
    metrics: PlayerLayoutMetrics,
    badges: List<StreamBadge> = emptyList(),
    qualityLabel: String? = null,
    onHeightChanged: (Dp) -> Unit = {},
) {
    val typeScale = MaterialTheme.nuvioTypeScale
    val density = LocalDensity.current
    // Screenshot-style title block, default player only (legacy uses
    // ProgressControls and does not call this). Order matches the
    // screenshot: large title, badge pills, then the meta line, with
    // the seekbar (PlayerTimeline) directly underneath in the shell.
    val formatPills = listOfNotNull(
        qualityLabel?.trim()?.takeIf { it.isNotBlank() && !it.equals("Quality", ignoreCase = true) },
    )
    Column(
        Modifier
            .padding(horizontal = PlayerTimelineContentInset)
            .padding(bottom = 4.dp)
            .onSizeChanged { size -> onHeightChanged(with(density) { size.height.toDp() }) },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = typeScale.titleLg.copy(
                fontSize = metrics.titleSize,
                lineHeight = metrics.titleSize * 1.16f,
                fontWeight = FontWeight.ExtraBold,
            ),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (formatPills.isNotEmpty() || badges.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                formatPills.forEach { pill ->
                    Box(
                        modifier = Modifier
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(999.dp),
                            )
                            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = pill,
                            style = typeScale.labelSm.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                }
                if (badges.isNotEmpty()) {
                    PlayerBadges(badges = badges)
                }
            }
        }
        if (seasonNumber != null && episodeNumber != null) {
            val episodeCode = stringResource(Res.string.compose_player_episode_code_full, seasonNumber, episodeNumber)
            val displayEpisodeTitle = localizedEpisodeTitle(episodeTitle, episodeNumber)
            Text(
                text = if (displayEpisodeTitle.isNullOrBlank()) episodeCode else "$episodeCode • $displayEpisodeTitle",
                style = typeScale.bodyMd.copy(fontSize = metrics.episodeInfoSize),
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!releaseInfo.isNullOrBlank()) {
            Text(
                text = releaseInfo,
                style = typeScale.bodyMd.copy(fontSize = metrics.metadataSize),
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ReportTimelineTouch(touching: Boolean, onTouchChange: (Boolean) -> Unit) {
    val latestOnTouchChange by rememberUpdatedState(onTouchChange)
    LaunchedEffect(touching) { latestOnTouchChange(touching) }
    DisposableEffect(Unit) {
        onDispose { latestOnTouchChange(false) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerTimeline(
    snapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    seekPreview: SeekPreviewController? = null,
    onTouchChange: (Boolean) -> Unit = {},
) {
    val durationMs = snapshot.durationMs.coerceAtLeast(0L)
    val rangeEnd = durationMs.coerceAtLeast(1L).toFloat()
    val bufferedFraction = playerBufferedFraction(snapshot.bufferedPositionMs, durationMs)
    val chapterMarks = remember(snapshot.chapters, durationMs) {
        snapshot.chapters.chapterMarkFractions(durationMs)
    }
    val accentBrush = MaterialTheme.themePalette.accentBrush()
    val description = stringResource(Res.string.player_seek_position)
    var scrubPosition by remember { mutableStateOf<Long?>(null) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isInteracting = enabled && durationMs > 0L && (isPressed || isDragged)
    ReportTimelineTouch(isInteracting, onTouchChange)
    val trackThickness by animateDpAsState(
        targetValue = if (isInteracting) 10.dp else 6.dp,
        animationSpec = tween(durationMillis = if (isInteracting) 140 else 180),
        label = "playerTimelineThickness",
    )

    val sliderState = remember(rangeEnd) { SliderState(trackRange = 0f..rangeEnd) }
    sliderState.value = displayedPositionMs.coerceIn(0L, durationMs).toFloat()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box {
            Slider(
                state = sliderState,
                onValueChange = { value ->
                    val position = value.toLong().coerceIn(0L, durationMs)
                    scrubPosition = position
                    onScrubChange(position)
                },
                onValueChangeFinished = {
                    onScrubFinished((scrubPosition ?: displayedPositionMs).coerceIn(0L, durationMs))
                    scrubPosition = null
                },
                enabled = enabled && durationMs > 0L,
                interactionSource = interactionSource,
                thumb = {},
                track = { state ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .drawBehind {
                                val trackHeight = trackThickness.toPx()
                                val trackOrigin = Offset(0f, 35.dp.toPx() - trackHeight / 2)
                                val radius = CornerRadius(trackHeight / 2)
                                drawRoundRect(
                                    color = PlayerBaseTrackColor,
                                    topLeft = trackOrigin,
                                    size = Size(size.width, trackHeight),
                                    cornerRadius = radius,
                                )
                                drawRoundRect(
                                    color = PlayerBufferedTrackColor,
                                    topLeft = trackOrigin,
                                    size = Size(size.width * bufferedFraction, trackHeight),
                                    cornerRadius = radius,
                                )
                                drawRoundRect(
                                    brush = accentBrush,
                                    topLeft = trackOrigin,
                                    size = Size(size.width * (state.value / rangeEnd).coerceIn(0f, 1f), trackHeight),
                                    cornerRadius = radius,
                                )
                                val markWidth = PlayerChapterMarkWidth.toPx()
                                chapterMarks.forEach { fraction ->
                                    drawRect(
                                        color = PlayerChapterMarkColor,
                                        topLeft = Offset(size.width * fraction - markWidth / 2f, trackOrigin.y),
                                        size = Size(markWidth, trackHeight),
                                    )
                                }
                            },
                    )
                },
                modifier = modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .wrapContentHeight(Alignment.Bottom, unbounded = true)
                    .requiredHeight(48.dp)
                    .semantics { contentDescription = description },
            )
            SeekPreviewOverlay(
                controller = seekPreview,
                active = isInteracting,
                positionMs = displayedPositionMs.coerceIn(0L, durationMs),
                durationMs = durationMs,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
