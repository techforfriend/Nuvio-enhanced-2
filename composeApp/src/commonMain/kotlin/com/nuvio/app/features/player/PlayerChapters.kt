package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.compose_player_playing
import nuvio.composeapp.generated.resources.player_chapter_number
import nuvio.composeapp.generated.resources.player_chapters
import org.jetbrains.compose.resources.stringResource

internal fun List<PlayerChapter>.currentChapterIndex(positionMs: Long): Int {
    var index = -1
    for (i in indices) {
        if (this[i].startMs <= positionMs) index = i else break
    }
    return index
}

private const val MaxMpvChapters = 500

internal fun mpvChapters(property: (String) -> String?): List<PlayerChapter> {
    val count = property("chapter-list/count")?.trim()?.toIntOrNull() ?: return emptyList()
    if (count < 2) return emptyList()
    return (0 until count.coerceAtMost(MaxMpvChapters))
        .mapNotNull { index ->
            val seconds = property("chapter-list/$index/time")?.trim()?.toDoubleOrNull()
                ?: return@mapNotNull null
            PlayerChapter(
                title = property("chapter-list/$index/title")?.trim().orEmpty(),
                startMs = (seconds * 1000.0).toLong().coerceAtLeast(0L),
            )
        }
        .sortedBy { it.startMs }
        .distinctBy { it.startMs }
        .takeIf { it.size >= 2 }
        .orEmpty()
}

internal fun List<PlayerChapter>.chapterMarkFractions(durationMs: Long): List<Float> {
    if (durationMs <= 0L) return emptyList()
    return asSequence()
        .map { it.startMs }
        .filter { it > 0L && it < durationMs }
        .map { (it.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) }
        .toList()
}

@Composable
internal fun PlayerChapterLabel(
    chapters: List<PlayerChapter>,
    positionMs: Long,
    metrics: PlayerLayoutMetrics,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val index = chapters.currentChapterIndex(positionMs)
    if (index < 0) return
    val fallbackTitle = stringResource(Res.string.player_chapter_number, index + 1)
    val title = chapters[index].title.ifBlank { fallbackTitle }
    Text(
        text = title,
        style = MaterialTheme.nuvioTypeScale.labelSm.copy(
            fontSize = metrics.metadataSize,
            fontWeight = FontWeight.Medium,
        ),
        color = Color.White.copy(alpha = 0.9f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = PlayerTimelineContentInset, vertical = 2.dp),
    )
}

@Composable
internal fun PlayerChaptersPanel(
    visible: Boolean,
    chapters: List<PlayerChapter>,
    positionMs: Long,
    onChapterSelected: (PlayerChapter) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentIndex = chapters.currentChapterIndex(positionMs)

    PlayerSidePanel(
        visible = visible,
        onDismiss = onDismiss,
        width = 420.dp,
        modifier = modifier,
    ) {
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            PlayerPanelHeader(
                title = stringResource(Res.string.player_chapters),
            ) {
                PlayerDialogButton(
                    label = stringResource(Res.string.action_close),
                    onClick = onDismiss,
                )
            }

            Spacer(Modifier.height(16.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
            ) {
                itemsIndexed(
                    items = chapters,
                    key = { index, chapter -> "${index}_${chapter.startMs}" },
                ) { index, chapter ->
                    PlayerChapterRow(
                        title = chapter.title.ifBlank {
                            stringResource(Res.string.player_chapter_number, index + 1)
                        },
                        startMs = chapter.startMs,
                        isCurrent = index == currentIndex,
                        onClick = { onChapterSelected(chapter) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerChapterRow(
    title: String,
    startMs: Long,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(if (isCurrent) tokens.colors.overlaySelected else tokens.colors.surfacePopover)
            .border(
                tokens.borders.thin,
                if (isCurrent) tokens.colors.borderSelected else tokens.colors.borderSubtle,
                tokens.shapes.compactCard,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatPlaybackTime(startMs),
            color = if (isCurrent) tokens.colors.accent else tokens.colors.textMuted,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(NuvioTokens.Space.s14))
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = tokens.colors.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (isCurrent) {
            Spacer(modifier = Modifier.width(NuvioTokens.Space.s10))
            Text(
                text = stringResource(Res.string.compose_player_playing),
                color = tokens.colors.accent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
