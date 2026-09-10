package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.DrawableResource

internal class FloatingNavigationItem(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val drawable: DrawableResource? = null,
    val content: (@Composable () -> Unit)? = null,
)

@Composable
internal expect fun FloatingNavigationBar(
    items: List<FloatingNavigationItem>,
    modifier: Modifier = Modifier,
    scrollState: NuvioNavBarScrollState? = null,
    hazeState: HazeState? = null,
)
