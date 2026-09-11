package com.nuvio.app.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.glass.GlassBarSurface
import com.nuvio.app.core.ui.jelly.JellyMotion
import com.nuvio.app.core.ui.jelly.JellyTabRow
import com.nuvio.app.core.ui.jelly.JellyTabTargets
import com.nuvio.app.core.ui.jelly.drawJellyGlow
import com.nuvio.app.core.ui.jelly.drawJellyPill
import com.nuvio.app.core.ui.jelly.jellyPillPath
import dev.chrisbanes.haze.HazeState
import kotlin.math.abs
import kotlin.math.max

@Composable
internal actual fun FloatingNavigationBar(
    items: List<FloatingNavigationItem>,
    modifier: Modifier,
    scrollState: NuvioNavBarScrollState?,
    hazeState: HazeState?,
    contentPadding: PaddingValues,
    compactSize: Boolean,
) {
    if (items.isEmpty()) return
    val tokens = MaterialTheme.nuvio
    val accentColor = tokens.colors.accent
    val selectedSurface = accentColor.copy(alpha = NuvioTokens.Opacity.selected)
    val labelFraction by animateFloatAsState(
        targetValue = scrollState?.labelVisibility ?: 1f,
        animationSpec = tween(NuvioTokens.Motion.sheetEnterMillis, easing = NuvioTokens.Motion.standard),
        label = "jelly_labels",
    )
    val selectedIndex = items.indexOfFirst { it.selected }
    val motion = remember { JellyMotion(selectedIndex, items.size) }
    val currentItems by rememberUpdatedState(items)
    val density = LocalDensity.current
    val trackHeight = 48.dp + (if (compactSize) 8.dp else 16.dp) * labelFraction
    val horizontalPadding = 58.dp - 30.dp * labelFraction

    LaunchedEffect(selectedIndex, items.size) {
        motion.select(selectedIndex)
    }
    LaunchedEffect(motion.running) {
        if (!motion.running) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (motion.running) {
            withFrameNanos { now ->
                motion.advance((now - previous) / 1_000_000_000.0)
                previous = now
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .height(trackHeight)
                .onSizeChanged {
                    motion.resize(it.width / density.density, it.height / density.density, items.size)
                }
                .pointerInput(motion, density, items.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        motion.begin(down.position.x / density.density, down.position.y / density.density)
                        var claimed = false
                        var finished = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (!motion.dragging) break
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (event.changes.count { it.pressed } > 1) break
                                val delta = change.position - down.position
                                if (max(abs(delta.x), abs(delta.y)) > viewConfiguration.touchSlop) claimed = true
                                if (claimed) change.consume()
                                awaitPointerEvent(PointerEventPass.Main)
                                if (change.pressed && change.isConsumed && !claimed) break
                                motion.drag(delta.x / density.density, delta.y / density.density)
                                if (!change.pressed) {
                                    val index = motion.finish()
                                    finished = true
                                    if (claimed || !change.isConsumed) currentItems.getOrNull(index)?.onClick?.invoke()
                                    change.consume()
                                    break
                                }
                            }
                        } finally {
                            if (!finished) motion.cancel(currentItems.indexOfFirst { it.selected })
                        }
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val frame = motion.frame
                        scaleX = frame.trackScale
                        scaleY = frame.trackScale
                    },
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val frame = motion.frame
                            transformOrigin = TransformOrigin(
                                if (size.width > 0) frame.originX * density.density / size.width else 0.5f,
                                0.5f,
                            )
                            scaleX = frame.trackScaleX
                            translationY = frame.trackOffsetY * density.density
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { translationX = motion.frame.panelOffset * density.density },
                    ) {
                        Box(
                            Modifier.matchParentSize()
                                .clip(RoundedCornerShape(50))
                                .drawWithContent {
                                    drawContent()
                                    drawJellyGlow(motion.frame, accentColor)
                                },
                        ) {
                            GlassBarSurface(hazeState, Modifier.matchParentSize())
                        }
                        Box(
                            Modifier.matchParentSize().drawWithContent {
                                if (selectedIndex >= 0) {
                                    clipPath(jellyPillPath(motion.frame, items.size), ClipOp.Difference) {
                                        this@drawWithContent.drawContent()
                                    }
                                } else {
                                    drawContent()
                                }
                            },
                        ) {
                            JellyTabRow(items, labelFraction, motion, active = false, compactSize = compactSize, modifier = Modifier.matchParentSize())
                        }
                        if (selectedIndex >= 0) {
                            Box(
                                Modifier.matchParentSize()
                                    .clearAndSetSemantics {}
                                    .drawWithContent {
                                        drawJellyPill(motion.frame, items.size, selectedSurface, accentColor) { drawContent() }
                                    },
                            ) {
                                JellyTabRow(items, labelFraction, motion, active = true, compactSize = compactSize, modifier = Modifier.matchParentSize())
                            }
                        }
                        JellyTabTargets(items, labelFraction, motion, compactSize, Modifier.matchParentSize())
                    }
                }
            }
        }
    }
}
