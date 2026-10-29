package com.nuvio.app

import androidx.activity.compose.setContent
import com.nuvio.app.features.tv.TvApp

/**
 * TV launcher entry point. Reuses MainActivity's whole init (storage, platform,
 * engines) and only swaps the root composable for the TV shell. The phone flow
 * is untouched: MainActivity still renders the phone UI.
 */
class TvMainActivity : MainActivity() {
    override fun showContent() {
        setContent { TvApp() }
        contentReady = true
    }
}
