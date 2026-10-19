package com.nuvio.app.core.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal val GlassSurfaceColor = Color(0xFF1C1C1E)

/**
 * A dark fill and a thin top-lit edge: the navigation pill without any backdrop (SOLID material,
 * glass turned off app-wide, or no RenderEffect). The glass version is [LiquidGlassFrostedBar].
 */
@Composable
internal fun SolidGlassBar(modifier: Modifier, edgeHighlight: Boolean = true) {
    Box(
        modifier
            .drawWithCache {
                val fill = GlassSurfaceColor.copy(alpha = 0.82f)
                val edge = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.27f), Color.White.copy(alpha = 0.02f)),
                )
                val width = 0.75.dp.toPx()
                onDrawBehind {
                    drawRoundRect(fill, cornerRadius = CornerRadius(size.height / 2))
                    if (edgeHighlight) {
                        drawRoundRect(
                            brush = edge,
                            topLeft = Offset(width / 2, width / 2),
                            size = Size(size.width - width, size.height - width),
                            cornerRadius = CornerRadius((size.height - width) / 2),
                            style = Stroke(width),
                        )
                    }
                }
            },
    )
}
