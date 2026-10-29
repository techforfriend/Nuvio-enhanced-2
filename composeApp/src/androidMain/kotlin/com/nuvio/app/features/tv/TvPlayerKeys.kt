package com.nuvio.app.features.tv

import androidx.compose.ui.input.key.Key

/**
 * Phase 3 remote-key mapping for the TV player. Pure mapping only — the
 * screen applies the returned action — so key behaviour stays in one place.
 * BACK is not mapped here: the screen handles it with BackHandler, which
 * hides visible controls first and exits only when they are hidden.
 *
 * Notes on focus: when controls are visible and a control button is focused,
 * the button consumes center/arrows itself (click / focus move) before the
 * screen-level handler sees them. The screen handler below therefore mostly
 * runs while focus sits on the video surface (controls hidden).
 */
enum class TvPlayerKeyAction {
    TogglePlayPause,
    SeekBack,
    SeekForward,
    ShowControls,
}

fun mapTvPlayerKey(key: Key): TvPlayerKeyAction? =
    when (key) {
        Key.DirectionCenter,
        Key.MediaPlayPause,
        Key.MediaPlay,
        Key.MediaPause,
        -> TvPlayerKeyAction.TogglePlayPause
        Key.DirectionLeft,
        Key.MediaRewind,
        -> TvPlayerKeyAction.SeekBack
        Key.DirectionRight,
        Key.MediaFastForward,
        -> TvPlayerKeyAction.SeekForward
        Key.DirectionUp,
        Key.DirectionDown,
        -> TvPlayerKeyAction.ShowControls
        else -> null
    }
