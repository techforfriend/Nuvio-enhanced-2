package com.nuvio.app.core.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

private val GlassSurfaceColor = Color(0xFF1C1C1E)

@Composable
internal fun GlassBarSurface(hazeState: HazeState?, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hazeState?.blurEnabled == true) {
        RefractedGlassBar(hazeState, modifier)
    } else {
        Box(
            modifier
                .then(if (hazeState != null) Modifier.barBackdrop(hazeState) else Modifier)
                .drawWithCache {
                    val fill = Brush.verticalGradient(
                        0f to GlassSurfaceColor.copy(alpha = 0.64f),
                        0.16f to GlassSurfaceColor.copy(alpha = 0.91f),
                        1f to GlassSurfaceColor.copy(alpha = 0.94f),
                    )
                    val edge = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.27f), Color.White.copy(alpha = 0.02f)),
                    )
                    val width = 0.75.dp.toPx()
                    onDrawBehind {
                        if (hazeState?.blurEnabled == true) {
                            drawRect(fill)
                        } else {
                            drawRect(GlassSurfaceColor.copy(alpha = 0.9f))
                        }
                        drawRoundRect(
                            brush = edge,
                            topLeft = Offset(width / 2, width / 2),
                            size = Size(size.width - width, size.height - width),
                            cornerRadius = CornerRadius((size.height - width) / 2),
                            style = Stroke(width),
                        )
                    }
                },
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun RefractedGlassBar(hazeState: HazeState, modifier: Modifier) {
    val shader = remember { RuntimeShader(GlassBarShader) }
    Box(
        modifier
            .layout { measurable, constraints ->
                val outset = 24.dp.roundToPx()
                val placeable = measurable.measure(constraints.offset(outset * 2, outset * 2))
                layout(placeable.width - outset * 2, placeable.height - outset * 2) {
                    placeable.place(-outset, -outset)
                }
            }
            .graphicsLayer {
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("density", density)
                shader.setFloatUniform("outset", 24.dp.roundToPx().toFloat())
                renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "backdrop").asComposeRenderEffect()
            }
            .barBackdrop(hazeState),
    )
}

private fun Modifier.barBackdrop(hazeState: HazeState): Modifier = hazeEffect(state = hazeState) {
    blurRadius = 20.dp
    backgroundColor = GlassSurfaceColor
    tints = listOf(HazeTint(Color.Transparent))
    noiseFactor = 0f
}
