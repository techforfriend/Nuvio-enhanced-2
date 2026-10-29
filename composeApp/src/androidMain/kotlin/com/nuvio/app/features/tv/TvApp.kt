package com.nuvio.app.features.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Phase 1 TV shell: proves the TV activity boots, respects overscan padding,
 * and moves focus with the remote. Real TV screens (Home rails, player,
 * Details, Search, Settings) land in Phase 2-4 and replace the placeholder
 * buttons below; the focus pattern here is the one they follow.
 */
@Composable
fun TvApp() {
    val items = listOf("Home", "Library", "Search", "Settings")
    val focusRequesters = remember { items.map { FocusRequester() } }
    var focusedIndex by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { focusRequesters.firstOrNull()?.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020404))
            // TV overscan: real TVs crop the edges, so keep content inside a safe area.
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Column {
            Text(
                text = "Nuvio Enhanced TV",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "TV home is coming in Phase 2.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(32.dp))
            items.forEachIndexed { index, label ->
                val focused = focusedIndex == index
                Button(
                    onClick = {},
                    modifier = Modifier
                        .focusRequester(focusRequesters[index])
                        .onFocusChanged { if (it.isFocused) focusedIndex = index }
                        .padding(vertical = 6.dp)
                        .border(
                            width = 2.dp,
                            color = if (focused) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                        ),
                ) {
                    Text(label)
                }
            }
        }
    }
}
