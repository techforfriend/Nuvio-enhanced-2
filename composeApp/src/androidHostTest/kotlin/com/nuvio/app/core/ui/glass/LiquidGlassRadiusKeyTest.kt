package com.nuvio.app.core.ui.glass

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LiquidGlassRadiusKeyTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun effectiveRadiusChangeRecreatesGlassSubtreeWithoutScrollOrResize() {
        val radius = mutableFloatStateOf(50f)
        val mounts = AtomicInteger()
        val disposals = AtomicInteger()

        compose.setContent {
            KeyedLiquidGlassRadius(radius.floatValue) {
                DisposableEffect(Unit) {
                    mounts.incrementAndGet()
                    onDispose { disposals.incrementAndGet() }
                }
                // Keep geometry constant: the refresh must come from the radius key, not layout.
                Box(Modifier.size(100.dp))
            }
        }

        compose.runOnIdle {
            assertEquals(1, mounts.get())
            radius.floatValue = 8f
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(2, mounts.get())
            assertEquals(1, disposals.get())
            // Both values render as an 8% RoundedCornerShape, so no layer reset is needed.
            radius.floatValue = 8.4f
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(2, mounts.get())
            assertEquals(1, disposals.get())
        }
    }
}
