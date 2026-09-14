package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

internal val LocalScreenConstraints = compositionLocalOf<Constraints?> { null }

@Composable
internal fun ScreenBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    val constraints = LocalScreenConstraints.current
    if (constraints == null) {
        BoxWithConstraints(modifier = modifier, content = content)
    } else {
        val density = LocalDensity.current
        Box(modifier = modifier) {
            val scope = remember(this, constraints, density) { ScreenBoxScope(this, constraints, density) }
            scope.content()
        }
    }
}

private class ScreenBoxScope(
    boxScope: BoxScope,
    override val constraints: Constraints,
    density: Density,
) : BoxWithConstraintsScope, BoxScope by boxScope {
    override val minWidth = with(density) { constraints.minWidth.toDp() }
    override val maxWidth = with(density) {
        if (constraints.hasBoundedWidth) constraints.maxWidth.toDp() else Dp.Infinity
    }
    override val minHeight = with(density) { constraints.minHeight.toDp() }
    override val maxHeight = with(density) {
        if (constraints.hasBoundedHeight) constraints.maxHeight.toDp() else Dp.Infinity
    }
}
