package com.nuvio.app.features.ratings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.tracking.TRACKING_RATING_MAX
import com.nuvio.app.features.tracking.TRACKING_RATING_MIN
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRatingTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.user_rating_last_failed
import nuvio.composeapp.generated.resources.user_rating_loading
import nuvio.composeapp.generated.resources.user_rating_no_provider
import nuvio.composeapp.generated.resources.user_rating_not_rated
import nuvio.composeapp.generated.resources.user_rating_remove
import nuvio.composeapp.generated.resources.user_rating_sync_to
import nuvio.composeapp.generated.resources.user_rating_syncs_with
import nuvio.composeapp.generated.resources.user_rating_value
import org.jetbrains.compose.resources.stringResource

private const val RATING_SETTLE_MS = 560L

/** Observes the current personal rating of [target], loading it on first use. */
@Composable
fun rememberUserRating(target: TrackingRatingTarget?): Int? {
    val state by UserRatingsRepository.uiState.collectAsState()
    val connected by TrackingProviderRegistry.connectedProviderIds.collectAsState()
    if (target == null) return null
    val providers = remember(target.key, connected) { UserRatingsRepository.providersFor(target) }
    LaunchedEffect(target.key, providers) {
        if (providers.isNotEmpty()) UserRatingsRepository.load(target)
    }
    if (providers.isEmpty()) return null
    val enabled = providers.filterNot { it in state.disabledProviders }
    return state.entries[UserRatingsRepository.entryKey(target)]?.ratingFor(enabled)
}

/** Whether any connected service can rate [target]; entry points hide themselves otherwise. */
@Composable
fun rememberCanRate(target: TrackingRatingTarget?): Boolean {
    val connected by TrackingProviderRegistry.connectedProviderIds.collectAsState()
    if (target == null) return false
    return remember(target.key, connected) { UserRatingsRepository.providersFor(target).isNotEmpty() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserRatingSheet(
    target: TrackingRatingTarget,
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val close: () -> Unit = {
        coroutineScope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }
    NuvioModalBottomSheet(onDismissRequest = close, sheetState = sheetState) {
        UserRatingContent(
            target = target,
            title = title,
            subtitle = subtitle,
            onDone = close,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        )
    }
}

/**
 * In-player variant: a centred card over a dimmed scrim, matching the player's other overlays
 * instead of presenting a system sheet on top of the video.
 */
@Composable
fun UserRatingPlayerOverlay(
    target: TrackingRatingTarget,
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.9f)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            UserRatingContent(
                target = target,
                title = title,
                subtitle = subtitle,
                onDone = onDismiss,
                modifier = Modifier.padding(20.dp),
            )
        }
    }
}

@Composable
fun UserRatingContent(
    target: TrackingRatingTarget,
    title: String,
    subtitle: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by UserRatingsRepository.uiState.collectAsState()
    val connected by TrackingProviderRegistry.connectedProviderIds.collectAsState()
    val providers = remember(target.key, connected) { UserRatingsRepository.providersFor(target) }
    LaunchedEffect(target.key, providers) {
        UserRatingsRepository.load(target, maxAgeMs = UserRatingsRepository.SHEET_REFRESH_AGE_MS)
    }
    val enabled = providers.filterNot { it in state.disabledProviders }
    val entry = state.entries[UserRatingsRepository.entryKey(target)] ?: UserRatingEntry()
    val current = entry.ratingFor(enabled)
    val scope = rememberCoroutineScope()
    var previewRating by remember { mutableStateOf<Int?>(null) }
    var settling by remember { mutableStateOf(false) }
    val displayed = previewRating ?: current
    val canEdit = enabled.isNotEmpty() && !entry.isSaving && !settling

    fun commitRating(rating: Int) {
        if (!canEdit) return
        settling = true
        previewRating = rating
        UserRatingsRepository.rate(target, rating)
        scope.launch {
            delay(RATING_SETTLE_MS)
            onDone()
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = if (displayed != null) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            AnimatedContent(
                targetState = Triple(displayed, entry.isLoading, settling),
                transitionSpec = {
                    (fadeIn(tween(NuvioTokens.Motion.fastMillis)) +
                        scaleIn(initialScale = 0.86f, animationSpec = tween(NuvioTokens.Motion.fastMillis))) togetherWith
                        (fadeOut(tween(120)) + scaleOut(targetScale = 0.92f, animationSpec = tween(120)))
                },
                label = "user_rating_value_text",
            ) { (value, loading, _) ->
                Text(
                    text = when {
                        value != null -> stringResource(Res.string.user_rating_value, value)
                        loading -> stringResource(Res.string.user_rating_loading)
                        else -> stringResource(Res.string.user_rating_not_rated)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (entry.isLoading || entry.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }

        UserRatingStarPicker(
            selected = displayed,
            enabled = canEdit,
            onSelect = ::commitRating,
            onPreview = { value ->
                if (value != null) previewRating = value
            },
            modifier = Modifier.fillMaxWidth(),
            starSize = 46.dp,
        )

        UserRatingScale(
            selected = displayed,
            enabled = canEdit,
            onSelect = ::commitRating,
        )

        when {
            providers.size > 1 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.user_rating_sync_to),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providers.forEach { providerId ->
                        ProviderChip(
                            providerId = providerId,
                            rating = entry.byProvider[providerId],
                            selected = providerId in enabled,
                            onToggle = {
                                UserRatingsRepository.setProviderEnabled(providerId, providerId !in enabled)
                            },
                        )
                    }
                }
                if (enabled.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.user_rating_no_provider),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            providers.size == 1 -> Text(
                text = stringResource(Res.string.user_rating_syncs_with, providers.first().displayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val failed = entry.failedProviders.intersect(enabled.toSet())
        if (failed.isNotEmpty()) {
            Text(
                text = stringResource(
                    Res.string.user_rating_last_failed,
                    failed.sortedBy { it.ordinal }.joinToString(", ") { it.displayName },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (enabled.any { entry.byProvider[it] != null }) {
            TextButton(
                onClick = {
                    UserRatingsRepository.remove(target)
                    onDone()
                },
                enabled = !entry.isSaving && !settling,
            ) {
                Text(
                    text = stringResource(Res.string.user_rating_remove),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun UserRatingScale(
    selected: Int?,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (value in TRACKING_RATING_MIN..TRACKING_RATING_MAX) {
            val filled = selected != null && value <= selected
            val isSelected = value == selected
            val label = stringResource(Res.string.user_rating_value, value)
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1.08f else 1f,
                animationSpec = spring(dampingRatio = 0.52f, stiffness = 520f),
                label = "rating_chip_scale_$value",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(
                        when {
                            isSelected -> MaterialTheme.colorScheme.primary
                            filled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    .clickable(enabled = enabled, role = Role.Button) { onSelect(value) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        filled -> MaterialTheme.colorScheme.onPrimary
                        enabled -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        }
    }
}

@Composable
private fun ProviderChip(
    providerId: TrackingProviderId,
    rating: Int?,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(
                text = buildString {
                    append(providerId.displayName)
                    rating?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** What to show in a [UserRatingSheet] opened from a screen. */
data class UserRatingSheetRequest(
    val target: TrackingRatingTarget,
    val title: String,
    val subtitle: String? = null,
)
