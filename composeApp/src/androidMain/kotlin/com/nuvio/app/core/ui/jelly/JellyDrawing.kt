package com.nuvio.app.core.ui.jelly

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.min

internal fun Density.jellyGlowBrush(color: Color): Brush {
    val alpha = 0.15f * color.alpha
    return Brush.radialGradient(
        0f to color.copy(alpha = alpha),
        0.45f to color.copy(alpha = alpha * 0.43f),
        1f to color.copy(alpha = 0f),
        center = Offset.Zero,
        radius = 300.dp.toPx(),
    )
}

internal fun DrawScope.drawJellyGlow(frame: JellyFrame, brush: Brush, strength: Float) {
    val alpha = frame.glowOpacity * strength
    if (alpha <= 0f) return
    val origin = Offset(frame.originX.dp.toPx(), frame.glowY.dp.toPx())
    translate(origin.x, origin.y) {
        drawRect(
            brush = brush,
            topLeft = Offset(-48.dp.toPx(), -16.dp.toPx()) - origin,
            size = Size(size.width + 96.dp.toPx(), size.height + 32.dp.toPx()),
            alpha = alpha,
        )
    }
}

internal fun DrawScope.jellyPillPath(frame: JellyFrame, count: Int, path: Path): Path {
    val inset = 4.dp.toPx()
    val tabWidth = (size.width - 2 * inset) / count
    val itemHeight = size.height - 2 * inset
    val centerX = inset + (frame.position + 0.5f) * tabWidth
    val halfWidth = tabWidth * frame.pillScaleX / 2
    val halfHeight = itemHeight * frame.pillScaleY / 2
    val radius = min(tabWidth, itemHeight) / 2
    return path.apply {
        rewind()
        addRoundRect(
            RoundRect(
                left = centerX - halfWidth,
                top = size.height / 2 - halfHeight,
                right = centerX + halfWidth,
                bottom = size.height / 2 + halfHeight,
                cornerRadius = CornerRadius(radius * frame.pillScaleX, radius * frame.pillScaleY),
            ),
        )
    }
}

internal fun DrawScope.drawJellyPill(
    frame: JellyFrame,
    count: Int,
    path: Path,
    surfaceColor: Color,
    glowBrush: Brush,
    glowStrength: Float,
    content: () -> Unit,
) {
    clipPath(jellyPillPath(frame, count, path)) {
        drawRect(
            color = surfaceColor,
            topLeft = Offset(-48.dp.toPx(), -16.dp.toPx()),
            size = Size(size.width + 96.dp.toPx(), size.height + 32.dp.toPx()),
        )
        drawJellyGlow(frame, glowBrush, glowStrength)
        content()
    }
}
