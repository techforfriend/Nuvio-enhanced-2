package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DownloadsStorage {
    private const val preferencesName = "nuvio_downloads"
    private const val payloadKey = "downloads_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? =
        preferences?.getString(ProfileScopedKey.of(payloadKey), null)

    actual fun savePayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(payloadKey), payload)
            ?.apply()
    }

    fun loadExternalFolderUri(): String? =
        preferences?.getString(ProfileScopedKey.of("external_folder_uri"), null)

    fun saveExternalFolderUri(uri: String?) {
        preferences
            ?.edit()
            ?.let { editor ->
                if (uri == null) {
                    editor.remove(ProfileScopedKey.of("external_folder_uri"))
                } else {
                    editor.putString(ProfileScopedKey.of("external_folder_uri"), uri)
                }
            }
            ?.apply()
    }
}
