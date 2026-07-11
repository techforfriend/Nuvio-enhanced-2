package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import com.nuvio.app.core.ui.NuvioEmptyState

@Composable
fun HomeEmptyStateCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    iconPainter: Painter? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    NuvioEmptyState(
        modifier = modifier,
        title = title,
        message = message,
        iconPainter = iconPainter,
        actionLabel = actionLabel,
        onActionClick = onActionClick,
    )
}
