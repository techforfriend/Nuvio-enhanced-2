package com.nuvio.app.features.settings

import androidx.compose.ui.graphics.Color
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.CustomThemeColors
import com.nuvio.app.core.ui.NativeTabBridge
import com.nuvio.app.core.ui.ThemeAccentColor
import com.nuvio.app.core.ui.ThemeColors
import com.nuvio.app.core.ui.toThemeColor
import com.nuvio.app.core.ui.toThemeHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemeSettingsRepository {
    private val _selectedTheme = MutableStateFlow(AppTheme.WHITE)
    val selectedTheme: StateFlow<AppTheme> = _selectedTheme.asStateFlow()

    private val _customThemeFirstColor = MutableStateFlow(ThemeAccentColor.PINK.color)
    val customThemeFirstColor: StateFlow<Color> = _customThemeFirstColor.asStateFlow()

    private val _customThemeSecondColor = MutableStateFlow(ThemeAccentColor.CYAN.color)
    val customThemeSecondColor: StateFlow<Color> = _customThemeSecondColor.asStateFlow()

    private val _customThemeColors = MutableStateFlow(CustomThemeColors.solid(CustomThemeColors.Default.second))
    val customThemeColors: StateFlow<CustomThemeColors> = _customThemeColors.asStateFlow()

    private val _amoledEnabled = MutableStateFlow(false)
    val amoledEnabled: StateFlow<Boolean> = _amoledEnabled.asStateFlow()

    private val _liquidGlassNativeTabBarEnabled = MutableStateFlow(false)
    val liquidGlassNativeTabBarEnabled: StateFlow<Boolean> = _liquidGlassNativeTabBarEnabled.asStateFlow()

    private val _liquidGlassAutoHideOnScrollEnabled = MutableStateFlow(false)
    val liquidGlassAutoHideOnScrollEnabled: StateFlow<Boolean> = _liquidGlassAutoHideOnScrollEnabled.asStateFlow()

    private val _selectedAppLanguage = MutableStateFlow(AppLanguage.DEVICE)
    val selectedAppLanguage: StateFlow<AppLanguage> = _selectedAppLanguage.asStateFlow()

    private val _navBarStyle = MutableStateFlow(NavBarStyle.ADAPTIVE)
    val navBarStyle: StateFlow<NavBarStyle> = _navBarStyle.asStateFlow()

    private val _navBarGlowEnabled = MutableStateFlow(true)
    val navBarGlowEnabled: StateFlow<Boolean> = _navBarGlowEnabled.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        _selectedTheme.value = AppTheme.WHITE
        _customThemeFirstColor.value = ThemeAccentColor.PINK.color
        _customThemeSecondColor.value = ThemeAccentColor.CYAN.color
        _customThemeColors.value = CustomThemeColors.solid(CustomThemeColors.Default.second)
        _amoledEnabled.value = false
        _liquidGlassNativeTabBarEnabled.value = false
        _liquidGlassAutoHideOnScrollEnabled.value = false
        NativeTabBridge.publishAccentColor(AppTheme.WHITE.nativeTabAccentHex())
        NativeTabBridge.publishLiquidGlassEnabled(false)
        _selectedAppLanguage.value = AppLanguage.DEVICE
        _navBarGlowEnabled.value = true
        _navBarStyle.value = NavBarStyle.ADAPTIVE
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val stored = ThemeSettingsStorage.loadSelectedTheme()
        val theme = stored.toAppTheme()
        _selectedTheme.value = theme
        _customThemeFirstColor.value = ThemeSettingsStorage.loadCustomThemeFirstColor()
            .toThemeColor(ThemeAccentColor.PINK.color)
        _customThemeSecondColor.value = ThemeSettingsStorage.loadCustomThemeSecondColor()
            .toThemeColor(ThemeAccentColor.CYAN.color)
        _customThemeColors.value = CustomThemeColors(
            first = _customThemeFirstColor.value.toArgbLong(),
            second = _customThemeSecondColor.value.toArgbLong(),
            isGradient = true,
        )
        NativeTabBridge.publishAccentColor(theme.nativeTabAccentHex(_customThemeFirstColor.value))
        _amoledEnabled.value = ThemeSettingsStorage.loadAmoledEnabled() ?: false
        val liquidGlassEnabled = ThemeSettingsStorage.loadLiquidGlassNativeTabBarEnabled() ?: false
        _liquidGlassNativeTabBarEnabled.value = liquidGlassEnabled
        _liquidGlassAutoHideOnScrollEnabled.value =
            ThemeSettingsStorage.loadLiquidGlassAutoHideOnScrollEnabled() ?: false
        NativeTabBridge.publishLiquidGlassEnabled(liquidGlassEnabled)
        val appLanguage = AppLanguage.fromCode(ThemeSettingsStorage.loadSelectedAppLanguage())
        ThemeSettingsStorage.applySelectedAppLanguage(appLanguage.code)
        _selectedAppLanguage.value = appLanguage
        _navBarGlowEnabled.value = ThemeSettingsStorage.loadNavBarGlowEnabled() ?: true
        _navBarStyle.value = NavBarStyle.fromKey(ThemeSettingsStorage.loadNavBarStyle())
    }

    fun setTheme(theme: AppTheme) {
        ensureLoaded()
        if (_selectedTheme.value == theme) return
        _selectedTheme.value = theme
        ThemeSettingsStorage.saveSelectedTheme(theme.name)
        NativeTabBridge.publishAccentColor(theme.nativeTabAccentHex(_customThemeFirstColor.value))
    }

    fun setCustomThemeFirstColor(color: Color) {
        ensureLoaded()
        if (_customThemeFirstColor.value == color) return
        _customThemeFirstColor.value = color
        ThemeSettingsStorage.saveCustomThemeFirstColor(color.toThemeHex())
        _customThemeColors.value = CustomThemeColors(
            first = color.toArgbLong(),
            second = _customThemeSecondColor.value.toArgbLong(),
            isGradient = true,
        )
        if (_selectedTheme.value == AppTheme.CUSTOM) {
            NativeTabBridge.publishAccentColor(AppTheme.CUSTOM.nativeTabAccentHex(color))
        }
    }

    fun setCustomThemeSecondColor(color: Color) {
        ensureLoaded()
        if (_customThemeSecondColor.value == color) return
        _customThemeSecondColor.value = color
        ThemeSettingsStorage.saveCustomThemeSecondColor(color.toThemeHex())
        _customThemeColors.value = CustomThemeColors(
            first = _customThemeFirstColor.value.toArgbLong(),
            second = color.toArgbLong(),
            isGradient = true,
        )
    }

    fun setCustomTheme(colors: CustomThemeColors) {
        ensureLoaded()
        _customThemeColors.value = colors
        val first = Color(colors.first or 0xFF000000.toInt())
        val second = Color(colors.second or 0xFF000000.toInt())
        setCustomThemeFirstColor(first)
        setCustomThemeSecondColor(second)
        setTheme(AppTheme.CUSTOM)
    }

    fun setAmoled(enabled: Boolean) {
        ensureLoaded()
        if (_amoledEnabled.value == enabled) return
        _amoledEnabled.value = enabled
        ThemeSettingsStorage.saveAmoledEnabled(enabled)
    }

    fun setLiquidGlassNativeTabBar(enabled: Boolean) {
        ensureLoaded()
        if (_liquidGlassNativeTabBarEnabled.value == enabled) return
        _liquidGlassNativeTabBarEnabled.value = enabled
        ThemeSettingsStorage.saveLiquidGlassNativeTabBarEnabled(enabled)
        NativeTabBridge.publishLiquidGlassEnabled(enabled)
    }

    fun setLiquidGlassAutoHideOnScroll(enabled: Boolean) {
        ensureLoaded()
        if (_liquidGlassAutoHideOnScrollEnabled.value == enabled) return
        _liquidGlassAutoHideOnScrollEnabled.value = enabled
        ThemeSettingsStorage.saveLiquidGlassAutoHideOnScrollEnabled(enabled)
    }

    fun setAppLanguage(language: AppLanguage) {
        ensureLoaded()
        if (_selectedAppLanguage.value == language) return
        ThemeSettingsStorage.saveSelectedAppLanguage(language.code)
        ThemeSettingsStorage.applySelectedAppLanguage(language.code)
        _selectedAppLanguage.value = language
    }

    fun setNavBarStyle(style: NavBarStyle) {
        ensureLoaded()
        if (_navBarStyle.value == style) return
        _navBarStyle.value = style
        ThemeSettingsStorage.saveNavBarStyle(style.key)
    }

    fun setNavBarGlowEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_navBarGlowEnabled.value == enabled) return
        _navBarGlowEnabled.value = enabled
        ThemeSettingsStorage.saveNavBarGlowEnabled(enabled)
    }
}

private fun Color.toArgbLong(): Long {
    val a = (alpha * 255.0f + 0.5f).toInt()
    val r = (red * 255.0f + 0.5f).toInt()
    val g = (green * 255.0f + 0.5f).toInt()
    val b = (blue * 255.0f + 0.5f).toInt()
    return ((a.toLong() and 0xFF) shl 24) or
        ((r.toLong() and 0xFF) shl 16) or
        ((g.toLong() and 0xFF) shl 8) or
        (b.toLong() and 0xFF)
}

private fun AppTheme.nativeTabAccentHex(customFirst: Color = ThemeAccentColor.PINK.color): String =
    ThemeColors.getColorPalette(this, customFirst = customFirst).nativeAccentHex

private fun String?.toAppTheme(): AppTheme = when (this) {
    "AURORA", "LAVENDER" -> AppTheme.MESSENGER
    "PRISM" -> AppTheme.AMETHYST
    "NEBULA", "ORCHID" -> AppTheme.BLOSSOM
    "OPAL", "TWILIGHT" -> AppTheme.LAGOON
    "ULTRAVIOLET" -> AppTheme.SUNSET
    else -> this?.let { stored -> AppTheme.entries.firstOrNull { it.name == stored } } ?: AppTheme.WHITE
}
