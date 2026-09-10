package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState

@Composable
internal actual fun FloatingNavigationBar(
    items: List<FloatingNavigationItem>,
    modifier: Modifier,
    scrollState: NuvioNavBarScrollState?,
    hazeState: HazeState?,
) {
    NuvioNavigationBar(modifier, scrollState, hazeState) {
        items.forEach { item ->
            when {
                item.icon != null -> NavItem(
                    selected = item.selected,
                    onClick = item.onClick,
                    icon = item.icon,
                    contentDescription = item.label,
                    label = item.label,
                )
                item.drawable != null -> NavItem(
                    selected = item.selected,
                    onClick = item.onClick,
                    icon = item.drawable,
                    contentDescription = item.label,
                    label = item.label,
                )
                else -> NavItem(
                    selected = item.selected,
                    onClick = item.onClick,
                    label = item.label,
                ) {
                    item.content?.invoke()
                }
            }
        }
    }
}
