package com.nuvio.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import com.nuvio.app.core.ui.ScreenBox
import kotlinx.coroutines.isActive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TabContentHostTest {
    @get:Rule
    val compose = createComposeRule()

    private val selection = mutableStateOf(AppScreenTab.Home)
    private val shown = mutableStateOf(true)
    private val viewport = mutableStateOf(DpSize(320.dp, 480.dp))
    private val screenSizes = mutableMapOf<AppScreenTab, DpSize>()
    private val activeTabs = mutableSetOf<AppScreenTab>()
    private val constructedOnFrame = mutableListOf<Long>()
    private var animationFrames = 0
    private var homeValue = -1
    private var updateHome: () -> Unit = {}

    @Test
    fun `destination construction yields to animation frames and preserves saved tab state`() {
        setContent()
        compose.onNodeWithTag("Home").assertExists()
        compose.runOnIdle {
            updateHome()
            selection.value = AppScreenTab.Search
        }
        advanceFrame()
        val framesBeforePreparation = animationFrames
        advanceUntilShown(AppScreenTab.Search)

        compose.runOnIdle {
            assertEquals(24, constructedOnFrame.size)
            assertTrue(constructedOnFrame.distinct().size > 1)
            assertTrue(animationFrames > framesBeforePreparation + 1)
            assertEquals(setOf(AppScreenTab.Search), activeTabs)
            selection.value = AppScreenTab.Home
        }
        advanceUntilShown(AppScreenTab.Home)
        compose.runOnIdle {
            assertEquals(1, homeValue)
            assertEquals(setOf(AppScreenTab.Home), activeTabs)
        }
    }

    @Test
    fun `rapid selections abandon unfinished destinations without launching their effects`() {
        setContent()
        compose.runOnIdle { selection.value = AppScreenTab.Search }
        advanceUntilPartiallyConstructed()
        compose.onNodeWithTag("Home").assertExists()
        compose.onNodeWithTag("Search").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(setOf(AppScreenTab.Home), activeTabs)
            selection.value = AppScreenTab.Library
        }
        advanceUntilShown(AppScreenTab.Library)
        compose.runOnIdle { assertEquals(setOf(AppScreenTab.Library), activeTabs) }
        compose.onNodeWithTag("Search").assertDoesNotExist()

        compose.runOnIdle { selection.value = AppScreenTab.Search }
        advanceUntilShown(AppScreenTab.Search)
        compose.runOnIdle { assertEquals(setOf(AppScreenTab.Search), activeTabs) }
    }

    @Test
    fun `returning to the displayed tab cancels preparation and disposing host releases pages`() {
        setContent()
        compose.runOnIdle { selection.value = AppScreenTab.Search }
        advanceUntilPartiallyConstructed()
        compose.runOnIdle { selection.value = AppScreenTab.Home }
        repeat(3) { advanceFrame() }
        val constructionsAfterCancel = constructedOnFrame.size
        repeat(3) { advanceFrame() }
        compose.runOnIdle {
            assertEquals(constructionsAfterCancel, constructedOnFrame.size)
            assertEquals(setOf(AppScreenTab.Home), activeTabs)
            selection.value = AppScreenTab.Search
        }
        advanceFrame()
        compose.runOnIdle { shown.value = false }
        repeat(3) { advanceFrame() }
        compose.runOnIdle { assertTrue(activeTabs.isEmpty()) }
        compose.onNodeWithTag("Search").assertDoesNotExist()
    }

    @Test
    fun `resize during preparation uses the latest screen bounds`() {
        setContent()
        compose.runOnIdle {
            assertEquals(viewport.value, screenSizes[AppScreenTab.Home])
            selection.value = AppScreenTab.Search
        }
        advanceUntilPartiallyConstructed()
        compose.runOnIdle { viewport.value = DpSize(800.dp, 600.dp) }
        advanceUntilShown(AppScreenTab.Search)
        compose.runOnIdle {
            assertEquals(viewport.value, screenSizes[AppScreenTab.Search])
            assertEquals(setOf(AppScreenTab.Search), activeTabs)
        }
    }

    private fun setContent() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LaunchedEffect(Unit) {
                while (isActive) withFrameNanos { animationFrames++ }
            }
            if (shown.value) {
                val savedState = rememberSaveableStateHolder()
                TabContentHost(selection.value, Modifier.requiredSize(viewport.value)) { tab ->
                    savedState.SaveableStateProvider(tab.name) {
                        var value by rememberSaveable { mutableIntStateOf(0) }
                        if (tab == AppScreenTab.Home) {
                            homeValue = value
                            updateHome = { value++ }
                        }
                        ScreenBox(Modifier.fillMaxSize().testTag(tab.name)) {
                            screenSizes[tab] = DpSize(maxWidth, maxHeight)
                            DisposableEffect(tab) {
                                activeTabs += tab
                                onDispose { activeTabs -= tab }
                            }
                            Column {
                                if (tab == AppScreenTab.Search) {
                                    repeat(24) { HeavyChild() }
                                }
                            }
                        }
                    }
                }
            }
        }
        repeat(2) { advanceFrame() }
    }

    @Composable
    private fun HeavyChild() {
        remember {
            constructedOnFrame += compose.mainClock.currentTime
            Thread.sleep(3)
            Unit
        }
        Box(Modifier.size(1.dp))
    }

    private fun advanceFrame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun advanceUntilPartiallyConstructed() {
        repeat(8) {
            advanceFrame()
            if (constructedOnFrame.isNotEmpty()) {
                assertTrue(constructedOnFrame.size < 24)
                assertFalse(AppScreenTab.Search in activeTabs)
                return
            }
        }
        error("Destination preparation did not start")
    }

    private fun advanceUntilShown(tab: AppScreenTab) {
        repeat(100) {
            advanceFrame()
            if (compose.onAllNodesWithTag(tab.name).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag(tab.name).assertExists()
                return
            }
        }
        error("Destination $tab was not displayed")
    }
}
