package com.nuvio.app.features.player

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w640dp-h360dp-land")
class OpeningOverlayTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun statusToggleKeepsTheTitleAndCloseActionVisible() {
        val statusVisible = mutableStateOf(true)
        var closed = false
        compose.setContent {
            NuvioTheme {
                OpeningOverlay(
                    artwork = null,
                    logo = null,
                    title = "Example movie",
                    onBack = { closed = true },
                    horizontalSafePadding = 0.dp,
                    message = if (statusVisible.value) "Finding stream source" else null,
                )
            }
        }
        compose.onNodeWithText("Example movie").assertIsDisplayed()
        compose.onNodeWithText("Finding stream source").assertIsDisplayed()
        compose.runOnIdle { statusVisible.value = false }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Finding stream source").assertDoesNotExist()
        compose.onNodeWithText("Example movie").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close player").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test
    fun subtitleProgressUsesTheTvCopy() {
        compose.setContent {
            NuvioTheme {
                OpeningOverlay(
                    artwork = null,
                    logo = null,
                    title = "Example movie",
                    onBack = {},
                    horizontalSafePadding = 0.dp,
                    message = subtitleLoadingStatusMessage(
                        SubtitleLoadingProgress(total = 3, completed = 1, addonName = "Example addon"),
                    ),
                )
            }
        }
        compose.onNodeWithText("Subtitles: Example addon (1 / 3)").assertIsDisplayed()
    }
}
