package com.nuvio.app.features.ratings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.tracking.TRACKING_RATING_MAX
import com.nuvio.app.features.tracking.TRACKING_RATING_MIN
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.user_rating_action_rate
import nuvio.composeapp.generated.resources.user_rating_action_rated
import nuvio.composeapp.generated.resources.user_rating_value
import org.jetbrains.compose.resources.stringResource

internal const val USER_RATING_STAR_COUNT = 5
private const val SPARK_COUNT = 7

/** Maps a 1–10 rating onto star [index] as 0, 0.5 or 1. */
internal fun userRatingStarFill(rating: Int?, index: Int): Float {
    val halfSteps = rating?.coerceIn(0, USER_RATING_STAR_COUNT * 2) ?: 0
    return when {
        halfSteps >= (index + 1) * 2 -> 1f
        halfSteps == index * 2 + 1 -> 0.5f
        else -> 0f
    }
}

/**
 * The viewer's own 1–10 rating drawn as five stars in half-star steps (10 → 5, 9 → 4.5).
 * Unrated shows five outlined stars. The whole row is one tap target that opens the rating popup.
 *
 * Empty stars idle-wave and give a one-shot “look at me” nudge. After a rating is saved they
 * fill left-to-right with a staggered spring, glow, tilt pop and spark burst.
 */
@Composable
fun UserRatingStars(
    rating: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    starSize: Dp = 30.dp,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val outline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val description = rating?.let { stringResource(Res.string.user_rating_action_rated, it) }
        ?: stringResource(Res.string.user_rating_action_rate)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 700f),
        label = "user_rating_press_scale",
    )
    var hintGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(rating) {
        if (rating != null) return@LaunchedEffect
        delay(480)
        hintGeneration++
    }

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 0 until USER_RATING_STAR_COUNT) {
            RatingStar(
                fill = userRatingStarFill(rating, index),
                size = starSize,
                accent = accent,
                outline = outline,
                isRtl = isRtl,
                index = index,
                idleTwinkle = rating == null,
                animateFillChanges = true,
                staggerFill = true,
                hintGeneration = hintGeneration,
            )
        }
    }
}

/**
 * Interactive 5-star control for the rating sheet. Drag or tap (including half-stars)
 * to pick 1–10. Filled stars pop with a spring and spark burst; [onPreview] reports the
 * in-progress value so the headline can track the finger.
 */
@Composable
fun UserRatingStarPicker(
    selected: Int?,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    starSize: Dp = 44.dp,
    onPreview: (Int?) -> Unit = {},
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val outline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptic = LocalHapticFeedback.current
    val description = selected?.let { stringResource(Res.string.user_rating_value, it) }
        ?: stringResource(Res.string.user_rating_action_rate)
    var hoverRating by remember { mutableStateOf<Int?>(null) }
    val displayed = hoverRating ?: selected
    val onSelectState = rememberUpdatedState(onSelect)
    val onPreviewState = rememberUpdatedState(onPreview)
    var lastHapticRating by remember { mutableIntStateOf(selected ?: 0) }

    LaunchedEffect(displayed) {
        val step = displayed ?: return@LaunchedEffect
        if (step != lastHapticRating) {
            lastHapticRating = step
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = description }
            .pointerInput(enabled, isRtl) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    fun ratingAt(x: Float): Int {
                        val nx = if (isRtl) width - x else x
                        val t = (nx / width).coerceIn(0f, 0.999f)
                        return ((t * TRACKING_RATING_MAX).toInt() + TRACKING_RATING_MIN)
                            .coerceIn(TRACKING_RATING_MIN, TRACKING_RATING_MAX)
                    }
                    var current = ratingAt(down.position.x)
                    hoverRating = current
                    onPreviewState.value(current)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.previousPosition != change.position) {
                            current = ratingAt(change.position.x)
                            hoverRating = current
                            onPreviewState.value(current)
                            change.consume()
                        }
                        if (change.changedToUpIgnoreConsumed()) {
                            onSelectState.value(current)
                            hoverRating = null
                            break
                        }
                        if (!change.pressed) {
                            hoverRating = null
                            onPreviewState.value(null)
                            break
                        }
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 0 until USER_RATING_STAR_COUNT) {
            RatingStar(
                fill = userRatingStarFill(displayed, index),
                size = starSize,
                accent = accent,
                outline = outline,
                isRtl = isRtl,
                index = index,
                idleTwinkle = displayed == null,
                animateFillChanges = true,
                staggerFill = false,
                popOnFill = true,
                enterStagger = true,
            )
        }
    }
}

@Composable
private fun RatingStar(
    fill: Float,
    size: Dp,
    accent: Color,
    outline: Color,
    isRtl: Boolean,
    index: Int,
    idleTwinkle: Boolean,
    animateFillChanges: Boolean,
    staggerFill: Boolean = false,
    popOnFill: Boolean = true,
    enterStagger: Boolean = false,
    hintGeneration: Int = 0,
) {
    val fillAnim = remember { Animatable(fill) }
    val scale = remember { Animatable(if (enterStagger) 0.52f else 1f) }
    val rotation = remember { Animatable(0f) }
    val enter = remember { Animatable(if (enterStagger) 0f else 1f) }
    val burst = remember { Animatable(0f) }
    val twinkle = remember { Animatable(0f) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(enterStagger) {
        if (!enterStagger) return@LaunchedEffect
        delay(index * 42L)
        coroutineScope {
            launch {
                enter.animateTo(
                    1f,
                    spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
                )
            }
            launch {
                scale.animateTo(1f, spring(dampingRatio = 0.52f, stiffness = 380f))
            }
        }
    }

    LaunchedEffect(hintGeneration) {
        if (hintGeneration == 0 || fill > 0.04f) return@LaunchedEffect
        delay(index * 70L)
        scale.animateTo(1.18f, spring(dampingRatio = 0.48f, stiffness = 520f))
        scale.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 380f))
    }

    LaunchedEffect(fill, animateFillChanges, staggerFill) {
        if (!animateFillChanges || !initialized) {
            fillAnim.snapTo(fill)
            initialized = true
            return@LaunchedEffect
        }
        val previous = fillAnim.value
        if (staggerFill) delay(index * 52L)
        coroutineScope {
            launch {
                fillAnim.animateTo(
                    fill,
                    tween(
                        durationMillis = 180,
                        easing = NuvioTokens.Motion.standard,
                    ),
                )
            }
            if (popOnFill && fill > previous + 0.05f) {
                launch {
                    scale.snapTo(0.68f)
                    scale.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 560f))
                }
                launch {
                    rotation.snapTo(if (index % 2 == 0) -10f else 10f)
                    rotation.animateTo(0f, spring(dampingRatio = 0.48f, stiffness = 420f))
                }
                launch {
                    burst.snapTo(0f)
                    burst.animateTo(1f, tween(durationMillis = 540, easing = NuvioTokens.Motion.standard))
                }
            }
        }
    }

    LaunchedEffect(idleTwinkle) {
        if (!idleTwinkle) {
            twinkle.snapTo(0f)
            return@LaunchedEffect
        }
        while (true) {
            twinkle.animateTo(1f, tween(durationMillis = 2600, easing = LinearEasing))
            twinkle.snapTo(0f)
        }
    }

    val glow = fillAnim.value
    val wave = (sin((twinkle.value * 2.0 * PI) - index * 0.85).toFloat() * 0.5f + 0.5f)
        .coerceIn(0f, 1f)
    val idleScale = if (idleTwinkle && glow <= 0.04f) 1f + 0.08f * wave else 1f
    val outlineAlpha = if (idleTwinkle && glow <= 0.04f) 0.55f + 0.45f * wave else 1f

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                val pop = scale.value * idleScale
                scaleX = pop
                scaleY = pop
                rotationZ = rotation.value
                alpha = enter.value
                translationY = (1f - enter.value) * 12f
            }
            .drawBehind {
                val px = size.toPx()
                if (glow > 0.04f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.48f * glow),
                                accent.copy(alpha = 0.12f * glow),
                                Color.Transparent,
                            ),
                        ),
                        radius = px * 0.82f,
                    )
                }
                val t = burst.value
                if (t in 0.001f..0.999f) {
                    val center = Offset(this.size.width / 2f, this.size.height / 2f)
                    for (i in 0 until SPARK_COUNT) {
                        val ang = (i / SPARK_COUNT.toFloat()) * (2f * PI.toFloat()) + 0.42f
                        val dist = px * (0.18f + t * 0.62f)
                        drawCircle(
                            color = accent.copy(alpha = (1f - t) * 0.95f),
                            radius = (px * 0.07f * (1f - t)).coerceAtLeast(0.8f),
                            center = Offset(
                                center.x + cos(ang) * dist,
                                center.y + sin(ang) * dist,
                            ),
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.StarBorder,
            contentDescription = null,
            tint = if (glow > 0.04f) accent else outline.copy(alpha = outline.alpha * outlineAlpha),
            modifier = Modifier.size(size),
        )
        if (glow > 0.01f) {
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .size(size)
                    .drawWithContent {
                        val visibleWidth = this.size.width * glow.coerceIn(0f, 1f)
                        val left = if (isRtl) this.size.width - visibleWidth else 0f
                        clipRect(left = left, right = left + visibleWidth) {
                            this@drawWithContent.drawContent()
                        }
                    },
            )
        }
    }
}
