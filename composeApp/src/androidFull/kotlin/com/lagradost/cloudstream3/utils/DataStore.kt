package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.mapper

const val PREFERENCES_NAME: String = "rebuild_preference"

/**
 * Minimal persistent storage ABI used by extensions.
 *
 * Values use the same JSON-backed preference contract as CloudStream's DataStore.
 * The host keeps the data inside its own application sandbox.
 */
object DataStore {
    fun getFolderName(folder: String, path: String): String =
        "${folder.trimEnd('/')}/${path.trimStart('/')}"

    fun Context.getSharedPrefs(): SharedPreferences =
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun <T : Any> Context.getKey(path: String, valueType: Class<T>): T? {
        return runCatching {
            val json = getSharedPrefs().getString(path, null) ?: return@runCatching null
            mapper.readValue(json, valueType)
        }.getOrNull()
    }

    inline fun <reified T : Any> Context.getKey(path: String): T? {
        return runCatching {
            val json = getSharedPrefs().getString(path, null) ?: return@runCatching null
            mapper.readValue(json, T::class.java)
        }.getOrNull()
    }

    fun <T> Context.setKey(path: String, value: T) {
        runCatching {
            getSharedPrefs().edit().apply {
                if (value == null) remove(path) else putString(path, mapper.writeValueAsString(value))
            }.apply()
        }
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) {
        setKey(getFolderName(folder, path), value)
    }

    fun Context.removeKey(path: String) {
        runCatching { getSharedPrefs().edit().remove(path).apply() }
    }

    fun Context.removeKey(folder: String, path: String) {
        removeKey(getFolderName(folder, path))
    }

    fun Context.removeKeys(folder: String): Int {
        val keys = getKeys(folder)
        getSharedPrefs().edit().apply {
            keys.forEach(::remove)
        }.apply()
        return keys.size
    }

    fun Context.getKeys(folder: String): List<String> {
        val prefix = folder.trimEnd('/') + "/"
        return getSharedPrefs().all.keys.filter { it.startsWith(prefix) }
    }
}
