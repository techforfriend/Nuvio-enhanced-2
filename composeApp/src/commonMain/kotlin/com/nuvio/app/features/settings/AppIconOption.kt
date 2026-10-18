package com.nuvio.app.features.settings

internal enum class AppIconOption(
    val key: String,
    val platformName: String?,
) {
    ORIGINAL(
        key = "original",
        platformName = null,
    ),
    ENHANCED(
        key = "enhanced",
        platformName = "AppIconEnhanced",
    ),
    ARCTIC_BLUE(
        key = "arctic_blue",
        platformName = "AppIconArcticBlue",
    ),
    EMERALD(
        key = "emerald",
        platformName = "AppIconEmerald",
    ),
    ROSE_GOLD(
        key = "rose_gold",
        platformName = "AppIconRoseGold",
    ),
    COPPER(
        key = "copper",
        platformName = "AppIconCopper",
    ),
    GRAPHITE(
        key = "graphite",
        platformName = "AppIconGraphite",
    ),
    MONOCHROME(
        key = "monochrome",
        platformName = "AppIconMonochrome",
    ),
    NEON(
        key = "neon",
        platformName = "AppIconNeon",
    ),
    GEAR(
        key = "gear",
        platformName = "AppIconGear",
    ),
    CHROME(
        key = "chrome",
        platformName = "AppIconChrome",
    ),
    AURORA(
        key = "aurora",
        platformName = "AppIconAurora",
    ),
    ;

    companion object {
        fun fromPlatformName(name: String?): AppIconOption =
            entries.firstOrNull { it.platformName == name } ?: ORIGINAL
    }
}
