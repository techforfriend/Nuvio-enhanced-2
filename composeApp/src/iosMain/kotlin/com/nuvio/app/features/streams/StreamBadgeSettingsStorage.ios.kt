package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSUserDefaults

actual object StreamBadgeSettingsStorage {
    private const val streamBadgeRulesKey = "stream_badge_rules"
    private const val showFileSizeBadgesKey = "show_file_size_badges"
    private const val showAddonLogoKey = "show_addon_logo"
    private const val showInPlayerKey = "show_badges_in_player"
    private const val streamBackgroundModeKey = "stream_background_mode"
    private const val streamBadgePlacementKey = "stream_badge_placement"
    private const val legacyDebridStreamBadgeRulesKey = "debrid_stream_badge_rules"
    private val syncKeys = listOf(streamBadgeRulesKey, showFileSizeBadgesKey, showInPlayerKey, streamBadgePlacementKey, streamBackgroundModeKey)

    actual fun loadStreamBadgeRules(): String? = loadString(streamBadgeRulesKey)

    actual fun saveStreamBadgeRules(rules: String) {
        saveString(streamBadgeRulesKey, rules)
    }

    actual fun loadShowFileSizeBadges(): Boolean? = loadBoolean(showFileSizeBadgesKey)

    actual fun saveShowFileSizeBadges(enabled: Boolean) {
        saveBoolean(showFileSizeBadgesKey, enabled)
    }

    actual fun loadShowAddonLogo(): Boolean? = loadBoolean(showAddonLogoKey)

    actual fun saveShowAddonLogo(enabled: Boolean) {
        saveBoolean(showAddonLogoKey, enabled)
    }

    actual fun loadShowInPlayer(): Boolean? = loadBoolean(showInPlayerKey)

    actual fun saveShowInPlayer(enabled: Boolean) {
        saveBoolean(showInPlayerKey, enabled)
    }

    actual fun loadStreamBadgePlacement(): String? = loadString(streamBadgePlacementKey)

    actual fun loadStreamBackgroundMode(): String? = loadString(streamBackgroundModeKey)

    actual fun saveStreamBackgroundMode(mode: String) {
        saveString(streamBackgroundModeKey, mode)
    }

    actual fun saveStreamBadgePlacement(placement: String) {
        saveString(streamBadgePlacementKey, placement)
    }

    actual fun loadLegacyDebridStreamBadgeRules(): String? =
        loadString(legacyDebridStreamBadgeRulesKey)

    actual fun clearLegacyDebridStreamBadgeRules() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(legacyDebridStreamBadgeRulesKey))
    }

    private fun loadString(key: String): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val scoped = defaults.stringForKey(ProfileScopedKey.of(key))?.trim()
        if (!scoped.isNullOrEmpty()) return scoped
        val profile1 = defaults.stringForKey(ProfileScopedKey.of(key, 1))?.trim()
        if (!profile1.isNullOrEmpty()) return profile1
        return defaults.stringForKey(key)?.trim()
    }

    private fun saveString(key: String, value: String) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setObject(value, forKey = ProfileScopedKey.of(key))
        defaults.setObject(value, forKey = key)
    }

    private fun loadBoolean(key: String): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val scopedKey = ProfileScopedKey.of(key)
        if (defaults.objectForKey(scopedKey) != null) {
            return defaults.boolForKey(scopedKey)
        }
        val profile1Key = ProfileScopedKey.of(key, 1)
        if (defaults.objectForKey(profile1Key) != null) {
            return defaults.boolForKey(profile1Key)
        }
        if (defaults.objectForKey(key) != null) {
            return defaults.boolForKey(key)
        }
        return null
    }

    private fun saveBoolean(key: String, enabled: Boolean) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setBool(enabled, forKey = ProfileScopedKey.of(key))
        defaults.setBool(enabled, forKey = key)
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadStreamBadgeRules()?.let { put(streamBadgeRulesKey, encodeSyncString(it)) }
        loadShowFileSizeBadges()?.let { put(showFileSizeBadgesKey, encodeSyncBoolean(it)) }
        loadShowInPlayer()?.let { put(showInPlayerKey, encodeSyncBoolean(it)) }
        loadStreamBadgePlacement()?.let { put(streamBadgePlacementKey, encodeSyncString(it)) }
        loadStreamBackgroundMode()?.let { put(streamBackgroundModeKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { key ->
            NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(key))
        }

        payload.decodeSyncString(streamBadgeRulesKey)?.let(::saveStreamBadgeRules)
        payload.decodeSyncBoolean(showFileSizeBadgesKey)?.let(::saveShowFileSizeBadges)
        payload.decodeSyncBoolean(showInPlayerKey)?.let(::saveShowInPlayer)
        payload.decodeSyncString(streamBadgePlacementKey)?.let(::saveStreamBadgePlacement)
        payload.decodeSyncString(streamBackgroundModeKey)?.let(::saveStreamBackgroundMode)
    }
}
