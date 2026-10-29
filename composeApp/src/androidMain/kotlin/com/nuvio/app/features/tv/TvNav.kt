package com.nuvio.app.features.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.features.home.MetaPreview

/**
 * Phase 2 TV navigation: only Home exists so far. Details (Phase 4) and
 * Player (Phase 3) join this sealed screen list when they land; card clicks
 * are accepted by [TvHomeScreen] already and currently do nothing.
 */
sealed interface TvScreen {
    data object Home : TvScreen
}

@Composable
fun TvNavHost() {
    var currentScreen by remember { mutableStateOf<TvScreen>(TvScreen.Home) }
    when (currentScreen) {
        TvScreen.Home -> TvHomeScreen(
            onItemClick = { _: MetaPreview ->
                // Phase 4 wires this to TvDetailsScreen.
            },
        )
    }
}
