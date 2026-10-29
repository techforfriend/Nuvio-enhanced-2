package com.nuvio.app.features.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * TV root: Phase 1 shell replaced by the nav host in Phase 2.
 * Overscan padding lives in each TV screen (Home pads 48 horizontal /
 * 32 vertical); this root only provides the background.
 */
@Composable
fun TvApp() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020404)),
    ) {
        TvNavHost()
    }
}
