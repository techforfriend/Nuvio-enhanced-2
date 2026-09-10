package com.nuvio.app.features.details

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.nuvio.app.supportsPosterNavigationMotion

@Composable
internal fun Modifier.detailsContentReveal(ready: Boolean): Modifier {
    if (!supportsPosterNavigationMotion) return this
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(ready) {
        if (ready) {
            withFrameNanos { }
            opacity.animateTo(1f, tween(360, easing = CubicBezierEasing(0.2f, 0f, 0.2f, 1f)))
        } else {
            opacity.snapTo(0f)
        }
    }
    return graphicsLayer {
        alpha = if (ready) opacity.value else 1f
    }
}
