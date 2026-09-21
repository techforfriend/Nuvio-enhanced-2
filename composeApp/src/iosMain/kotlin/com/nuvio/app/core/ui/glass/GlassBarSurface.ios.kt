package com.nuvio.app.core.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState

// Skia on iOS has no RuntimeShader render effect for Compose, so the pill uses the frosted
// haze bar — the same surface Android falls back to below API 33.
@Composable
internal actual fun GlassBarSurface(hazeState: HazeState?, modifier: Modifier, glowStrength: Float) {
    FrostedGlassBar(hazeState, modifier, glowStrength)
}
