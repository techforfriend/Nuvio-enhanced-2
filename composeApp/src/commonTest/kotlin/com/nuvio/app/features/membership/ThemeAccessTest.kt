package com.nuvio.app.features.membership

import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.CustomThemeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeAccessTest {
    @Test
    fun memberWithoutSavedThemeDefaultsToWhite() {
        assertEquals(AppTheme.WHITE, resolveAppTheme(null, CosmeticEntitlements.None))
    }

    @Test
    fun supporterThemesAreAlwaysAvailable() {
        val themes = availableAppThemes(CosmeticEntitlements.None)

        assertTrue(AppTheme.GOLD in themes)
        assertTrue(AppTheme.JADE in themes)
        assertTrue(AppTheme.ROSE_GOLD in themes)
        assertTrue(AppTheme.ARCTIC_BLUE in themes)
        assertTrue(AppTheme.GRAPHITE in themes)
        assertTrue(AppTheme.WHITE in themes)
        assertTrue(AppTheme.CUSTOM in themes)
    }

    @Test
    fun customThemesAreAvailableWithoutMembership() {
        val themes = availableAppThemes(CosmeticEntitlements.None)

        assertEquals(AppTheme.GOLD, themes.first())
        assertEquals(AppTheme.CUSTOM, resolveAppTheme(AppTheme.CUSTOM, CosmeticEntitlements.None))
        assertEquals(AppTheme.WHITE, resolveAppTheme(null, CosmeticEntitlements.None))
    }

    @Test
    fun gradientColorsAreAlwaysPreserved() {
        val saved = CustomThemeColors(0x112233, 0x445566, 0x778899)

        assertEquals(saved, resolveCustomThemeColors(saved, null))
        assertEquals(saved, resolveCustomThemeColors(saved, MemberTier.SUPPORTER))
        assertEquals(saved, resolveCustomThemeColors(saved, MemberTier.SUPPORTER_PLUS))
        assertEquals("#112233,#445566,#778899", saved.encode())
    }

    @Test
    fun bothMemberTiersKeepAllGradientStops() {
        val colors = CustomThemeColors(0xFF0000, 0x00FF00, 0x0000FF)

        MemberTier.entries.forEach { tier ->
            assertEquals(colors, resolveCustomThemeColors(colors, tier))
        }
    }

    @Test
    fun solidColorsStayTheSameAcrossMembershipChanges() {
        val colors = CustomThemeColors.solid(0x123456)

        (listOf(null) + MemberTier.entries).forEach { tier ->
            assertEquals(colors, resolveCustomThemeColors(colors, tier))
        }
    }
}
