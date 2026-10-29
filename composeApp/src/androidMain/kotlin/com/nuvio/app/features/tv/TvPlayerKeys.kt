package com.nuvio.app.features.tv

import android.view.KeyEvent

/**
 * Phase 3 remote-key mapping for the TV player. Pure mapping only — the
 * screen applies the returned action — so key behaviour stays in one place.
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
    HideControlsThenBack,
    Back,
}

fun mapTvPlayerKey(keyCode: Int, controlsVisible: Boolean): TvPlayerKeyAction? =
    when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        -> TvPlayerKeyAction.TogglePlayPause
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        -> TvPlayerKeyAction.SeekBack
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        -> TvPlayerKeyAction.SeekForward
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        -> TvPlayerKeyAction.ShowControls
        KeyEvent.KEYCODE_BACK -> if (controlsVisible) {
            TvPlayerKeyAction.HideControlsThenBack
        } else {
            TvPlayerKeyAction.Back
        }
        else -> null
    }
