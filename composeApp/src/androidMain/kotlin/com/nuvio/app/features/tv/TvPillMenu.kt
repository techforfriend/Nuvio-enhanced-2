package com.nuvio.app.features.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Reshaped-style pill menu: a floating pill bar at the top of Home that
 * replaces the old title + rectangular header buttons. D-pad Up from the
 * rails lands here; it stays visible while browsing Home. Home is the
 * current screen, so its pill is a selected indicator, not a navigation.
 */
@Composable
fun TvPillMenu(
    onOpenSearch: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenParty: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(28.dp))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(28.dp),
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvPillMenuItem(label = "Home", selected = true, onClick = {})
        TvPillMenuItem(label = "Search", onClick = onOpenSearch)
        TvPillMenuItem(label = "Downloads", onClick = onOpenDownloads)
        TvPillMenuItem(label = "Watch Party", onClick = onOpenParty)
        TvPillMenuItem(label = "Settings", onClick = onOpenSettings)
    }
}

@Composable
private fun TvPillMenuItem(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .background(
                color = when {
                    focused -> Color.White
                    selected -> Color.White.copy(alpha = 0.15f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(20.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) Color.Black else Color.White,
        )
    }
}
