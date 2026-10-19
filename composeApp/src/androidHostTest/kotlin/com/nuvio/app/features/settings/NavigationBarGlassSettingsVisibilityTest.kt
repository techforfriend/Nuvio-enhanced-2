package com.nuvio.app.features.settings

import com.nuvio.app.core.ui.glass.LiquidGlassMaterial
import com.nuvio.app.core.ui.glass.LiquidGlassStyle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationBarGlassSettingsVisibilityTest {
    @Test
    fun lensControlsFollowTheMaterialAndDeviceSupport() {
        assertTrue(navBarGlassShowsLensControls(LiquidGlassStyle(LiquidGlassMaterial.LIQUID), refractionSupported = true))
        assertTrue(navBarGlassShowsLensControls(LiquidGlassStyle(LiquidGlassMaterial.CLEAR), refractionSupported = true))
        assertFalse(navBarGlassShowsLensControls(LiquidGlassStyle(LiquidGlassMaterial.FROSTED), refractionSupported = true))
        assertFalse(navBarGlassShowsLensControls(LiquidGlassStyle(LiquidGlassMaterial.LIQUID), refractionSupported = false))
    }
}
