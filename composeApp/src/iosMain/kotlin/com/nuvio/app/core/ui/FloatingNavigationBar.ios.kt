package com.nuvio.app.core.ui

// The jelly glow is drawn by Compose itself, so iOS can offer the same on/off switch as Android.
internal actual val floatingNavigationGlowSupported: Boolean
    get() = true
