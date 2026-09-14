package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeLayoutState
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import com.nuvio.app.core.ui.LocalScreenConstraints
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

@Composable
internal fun TabContentHost(
    selectedTab: AppScreenTab,
    modifier: Modifier = Modifier,
    content: @Composable (AppScreenTab) -> Unit,
) {
    val layoutState = remember { SubcomposeLayoutState() }
    val screenConstraints = remember { mutableStateOf(Constraints()) }
    var displayedTab by remember { mutableStateOf(selectedTab) }
    val currentContent by rememberUpdatedState(content)
    val tabContents = remember {
        AppScreenTab.entries.associateWith { tab ->
            val tabContent: @Composable () -> Unit = {
                CompositionLocalProvider(LocalScreenConstraints provides screenConstraints.value) {
                    currentContent(tab)
                }
            }
            tabContent
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == displayedTab) return@LaunchedEffect
        val preparation = layoutState.createPausedPrecomposition(selectedTab, tabContents.getValue(selectedTab))
        var applied = false
        try {
            do {
                withFrameNanos { }
                yield()
                val started = TimeSource.Monotonic.markNow()
                preparation.resume { started.elapsedNow() >= 2.milliseconds }
            } while (!preparation.isComplete)
            ensureActive()
            preparation.apply()
            applied = true
            displayedTab = selectedTab
        } finally {
            if (!applied) preparation.cancel()
        }
    }

    SubcomposeLayout(state = layoutState, modifier = modifier) { constraints ->
        screenConstraints.value = constraints
        val placeables = subcompose(displayedTab, tabContents.getValue(displayedTab)).map {
            it.measure(constraints)
        }
        layout(
            constraints.constrainWidth(placeables.maxOfOrNull { it.width } ?: 0),
            constraints.constrainHeight(placeables.maxOfOrNull { it.height } ?: 0),
        ) {
            placeables.forEach { it.placeRelative(0, 0) }
        }
    }
}
