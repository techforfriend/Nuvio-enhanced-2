package com.nuvio.app.features.discord

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.*
import platform.Foundation.NSUserDefaults
import platform.Security.*

@OptIn(ExperimentalForeignApi::class)
internal actual object DiscordRpcStorage {
    private const val settingsKey = "discord_rpc_settings_payload"
    private const val userKey = "discord_rpc_user_payload"

    actual fun loadSettingsPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(settingsKey)

    actual fun saveSettingsPayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = settingsKey)
    }

    actual fun loadUserPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(userKey)

    actual fun saveUserPayload(payload: String?) {
        if (payload == null) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(userKey)
        } else {
            NSUserDefaults.standardUserDefaults.setObject(payload, forKey = userKey)
        }
    }

    actual fun loadToken(): String? = runCatching { readToken() }.getOrNull()

    actual fun saveToken(token: String?) {
        runCatching { writeToken(token?.takeIf { it.isNotBlank() }) }
    }

    private fun readToken(): String? = keychainQuery { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return@memScoped null
            check(status == errSecSuccess) { "Unable to read Discord token (OSStatus $status)" }
            val data: CFDataRef = result.value?.reinterpret() ?: return@memScoped null
            try {
                val bytes = CFDataGetBytePtr(data) ?: return@memScoped null
                ByteArray(CFDataGetLength(data).toInt()) { bytes[it].toByte() }.decodeToString()
            } finally {
                CFRelease(data)
            }
        }
    }

    private fun writeToken(token: String?) = keychainQuery { query ->
        if (token == null) {
            val status = SecItemDelete(query)
            check(status == errSecSuccess || status == errSecItemNotFound) {
                "Unable to clear Discord token (OSStatus $status)"
            }
            return@keychainQuery
        }
        val bytes = token.encodeToByteArray().toUByteArray()
        val data = CFDataCreate(null, bytes.refTo(0), bytes.size.toLong())
            ?: error("Unable to encode Discord token")
        val attributes = dictionary()
        try {
            CFDictionarySetValue(attributes, kSecValueData, data)
            val update = SecItemUpdate(query, attributes)
            if (update == errSecItemNotFound) {
                CFDictionarySetValue(query, kSecValueData, data)
                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                val added = SecItemAdd(query, null)
                check(added == errSecSuccess) { "Unable to save Discord token (OSStatus $added)" }
            } else {
                check(update == errSecSuccess) { "Unable to update Discord token (OSStatus $update)" }
            }
        } finally {
            CFRelease(attributes)
            CFRelease(data)
        }
    }

    private fun dictionary(): CFMutableDictionaryRef =
        CFDictionaryCreateMutable(null, 0L, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            ?: error("Unable to create Keychain query")

    private inline fun <T> keychainQuery(block: (CFMutableDictionaryRef) -> T): T {
        val service = CFStringCreateWithCString(null, "com.nuvio.media.discord", kCFStringEncodingUTF8)
            ?: error("Unable to encode Keychain service")
        val account = CFStringCreateWithCString(null, "token", kCFStringEncodingUTF8)
            ?: error("Unable to encode Keychain account")
        val query = dictionary()
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            return block(query)
        } finally {
            CFRelease(query)
            CFRelease(account)
            CFRelease(service)
        }
    }
}
