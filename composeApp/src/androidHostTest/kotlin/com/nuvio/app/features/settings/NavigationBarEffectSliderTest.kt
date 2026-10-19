package com.nuvio.app.features.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NavigationBarEffectSliderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun draggingGlassEffectSliderUpdatesDraftValue() {
        val value = mutableFloatStateOf(10f)

        compose.setContent {
            MaterialTheme {
                LiquidGlassEffectSlider(
                    title = "Corner radius",
                    value = value.floatValue,
                    valueRange = 0f..50f,
                    valueLabel = "${value.floatValue.roundToInt()}%",
                    isTablet = false,
                    onValueChange = { value.floatValue = it },
                )
            }
        }

        compose.onNodeWithContentDescription("Corner radius").performTouchInput {
            swipe(
                start = Offset(size.width * 0.2f, size.height / 2f),
                end = Offset(size.width * 0.8f, size.height / 2f),
                durationMillis = 250,
            )
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(value.floatValue > 10f)
        }
    }
}
