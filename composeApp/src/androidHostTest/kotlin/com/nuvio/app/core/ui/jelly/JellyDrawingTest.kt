package com.nuvio.app.core.ui.jelly

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JellyDrawingTest {
    @Test
    fun `cached glow preserves color falloff and moving origin`() {
        val frames = listOf(
            JellyFrame(0f, originX = 160f, glowY = 32f, glowOpacity = 0f),
            JellyFrame(3f, originX = 277f, glowY = 16f, glowOpacity = 1f),
            JellyFrame(1.2f, originX = 65f, glowY = 48f, glowOpacity = 0.47f),
            JellyFrame(0f, originX = 0f, glowY = 0f, glowOpacity = 0.12f),
        )
        for (density in listOf(Density(1f), Density(2f))) {
            for (color in listOf(Color.White, Color(0xFFFF7043), Color(0x8000B8D4))) {
                val brush = density.jellyGlowBrush(color)
                for (frame in frames) {
                    for (strength in listOf(0f, 0.45f, 1f)) {
                        val actual = render(density) { drawJellyGlow(frame, brush, strength) }
                        if (frame.glowOpacity == 1f && strength == 1f) {
                            assertTrue(actual.any { it != 0xFF1C1C1E.toInt() })
                        }
                        val expected = render(density) {
                            drawOriginalGlow(frame, color.copy(alpha = color.alpha * strength))
                        }
                        val maxDifference = actual.indices.maxOf { index ->
                            listOf(0, 8, 16, 24).maxOf { shift ->
                                abs(((actual[index] ushr shift) and 255) - ((expected[index] ushr shift) and 255))
                            }
                        }
                        assertTrue(maxDifference <= 1, "Glow changed by $maxDifference for $frame at $strength")
                    }
                }
            }
        }
    }

    @Test
    fun `pill path is reused without retaining old geometry after motion and resize`() {
        val path = Path()
        val cases = listOf(
            Triple(Size(320f, 64f), JellyFrame(2.4f, pillScaleX = 1.3f, pillScaleY = 1.1f), Rect(179.5f, 1.2f, 280.9f, 62.8f)),
            Triple(Size(260f, 48f), JellyFrame(0f), Rect(4f, 4f, 67f, 44f)),
            Triple(Size(260f, 48f), JellyFrame(3f, pillScaleX = 1.3f, pillScaleY = 0.8f), Rect(183.55f, 8f, 265.45f, 40f)),
        )
        for (density in listOf(Density(1f), Density(2f))) {
            for ((size, frame, expected) in cases) {
                val image = ImageBitmap((size.width * density.density).toInt(), (size.height * density.density).toInt())
                CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), size * density.density) {
                    assertSame(path, jellyPillPath(frame, 4, path))
                    val bounds = path.getBounds()
                    assertEquals(expected.left * density.density, bounds.left, 0.0001f)
                    assertEquals(expected.top * density.density, bounds.top, 0.0001f)
                    assertEquals(expected.right * density.density, bounds.right, 0.0001f)
                    assertEquals(expected.bottom * density.density, bounds.bottom, 0.0001f)
                }
            }
        }
    }

    private fun render(density: Density, draw: DrawScope.() -> Unit): IntArray {
        val width = (320 * density.density).toInt()
        val height = (64 * density.density).toInt()
        val image = ImageBitmap(width, height)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
            drawRect(Color(0xFF1C1C1E))
            draw()
        }
        return IntArray(width * height).also { image.readPixels(it) }
    }

    private fun DrawScope.drawOriginalGlow(frame: JellyFrame, color: Color) {
        if (frame.glowOpacity <= 0f || color.alpha <= 0f) return
        val alpha = 0.15f * frame.glowOpacity * color.alpha
        drawRect(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = alpha),
                0.45f to color.copy(alpha = alpha * 0.43f),
                1f to color.copy(alpha = 0f),
                center = Offset(frame.originX.dp.toPx(), frame.glowY.dp.toPx()),
                radius = 300.dp.toPx(),
            ),
            topLeft = Offset(-48.dp.toPx(), -16.dp.toPx()),
            size = Size(size.width + 96.dp.toPx(), size.height + 32.dp.toPx()),
        )
    }
}
