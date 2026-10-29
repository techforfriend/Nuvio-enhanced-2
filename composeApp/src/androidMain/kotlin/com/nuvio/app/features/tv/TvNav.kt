package com.nuvio.app.features.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.features.home.MetaPreview

/**
 * Phase 2 TV navigation, extended in Phase 3 with the player. Details
 * (Phase 4) will add its own screen and wire Home card clicks to it, and
 * Details will hand a resolved [TvPlayRequest] to [TvScreen.Player].
 */
sealed interface TvScreen {
    data object Home : TvScreen
    data class Player(val request: TvPlayRequest) : TvScreen
}

@Composable
fun TvNavHost() {
    var currentScreen by remember { mutableStateOf<TvScreen>(TvScreen.Home) }
    when (val screen = currentScreen) {
        TvScreen.Home -> TvHomeScreen(
            onItemClick = { _: MetaPreview ->
                // Phase 4 wires this to TvDetailsScreen.
            },
        )
        is TvScreen.Player -> TvPlayerScreen(
            request = screen.request,
            onBack = { currentScreen = TvScreen.Home },
        )
    }
}
