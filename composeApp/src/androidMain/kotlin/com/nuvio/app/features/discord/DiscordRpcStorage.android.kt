package com.nuvio.app.features.discord

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal actual object DiscordRpcStorage {
    private const val preferencesName = "nuvio_discord_rpc"
    private const val settingsKey = "settings_payload"
    private const val userKey = "user_payload"
    private const val tokenKey = "token"
    private const val keyAlias = "com.nuvio.media.discord.token.v1"

    private var preferences: SharedPreferences? = null
    private val lock = Any()

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadSettingsPayload(): String? =
        preferences?.getString(settingsKey, null)

    actual fun saveSettingsPayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(settingsKey, payload)
            ?.apply()
    }

    actual fun loadUserPayload(): String? =
        preferences?.getString(userKey, null)

    actual fun saveUserPayload(payload: String?) {
        val editor = preferences?.edit() ?: return
        if (payload == null) editor.remove(userKey) else editor.putString(userKey, payload)
        editor.apply()
    }

    actual fun loadToken(): String? = synchronized(lock) {
        val prefs = preferences ?: return@synchronized null
        val value = prefs.getString(tokenKey, null) ?: return@synchronized null
        try {
            val parts = value.split('.', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, decode(parts[0])))
            cipher.updateAAD("$keyAlias:$tokenKey".toByteArray(Charsets.UTF_8))
            cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            prefs.edit().remove(tokenKey).commit()
            null
        }
    }

    actual fun saveToken(token: String?) {
        synchronized(lock) {
            writeToken(token)
        }
    }

    private fun writeToken(token: String?) {
        val prefs = preferences ?: return
        val editor = prefs.edit()
        if (token.isNullOrBlank()) {
            editor.remove(tokenKey)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD("$keyAlias:$tokenKey".toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            editor.putString(tokenKey, "${encode(cipher.iv)}.${encode(encrypted)}")
        }
        editor.commit()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
