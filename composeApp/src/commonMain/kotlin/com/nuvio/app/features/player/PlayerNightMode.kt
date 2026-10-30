package com.nuvio.app.features.player

internal object PlayerNightMode {
    const val MPV_FILTER_LABEL = "@nuvio_night"

    const val MPV_FILTER =
        "$MPV_FILTER_LABEL:lavfi=[" +
            "aresample=ochl=stereo:clev=1.0:slev=0.707," +
            "acompressor=threshold=0.063:ratio=6:attack=5:release=300:makeup=3.98:detection=rms," +
            "alimiter=limit=0.94:attack=1:release=150:level=0" +
            "]"
}

internal val PlayerSettingsUiState.nightModeActive: Boolean
    get() = nightModeButtonEnabled && nightModeEnabled
