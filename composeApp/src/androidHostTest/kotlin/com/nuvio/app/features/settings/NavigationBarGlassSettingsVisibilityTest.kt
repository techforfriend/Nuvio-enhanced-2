package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationBarGlassSettingsVisibilityTest {
    @Test
    fun legacyGlassControlsAreHiddenOnlyWhenTheNewMaterialIsSupportedAndEnabled() {
        assertFalse(
            shouldShowLegacyNavBarGlassControls(
                liquidGlassSupported = true,
                liquidGlassEnabled = true,
            ),
        )
    }

    @Test
    fun legacyGlassControlsRemainAvailableWhenTheNewMaterialIsDisabled() {
        assertTrue(
            shouldShowLegacyNavBarGlassControls(
                liquidGlassSupported = true,
                liquidGlassEnabled = false,
            ),
        )
    }

    @Test
    fun legacyGlassControlsRemainAvailableWhenTheNewMaterialIsUnsupported() {
        assertTrue(
            shouldShowLegacyNavBarGlassControls(
                liquidGlassSupported = false,
                liquidGlassEnabled = true,
            ),
        )
    }
}
