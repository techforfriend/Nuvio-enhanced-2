package com.nuvio.app.features.tmdb

import android.app.Application
import android.content.Context
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.buildJsonObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TmdbSettingsRepositoryTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_tmdb_settings", Context.MODE_PRIVATE).edit().clear().commit()
        TmdbSettingsStorage.initialize(context)
        TmdbSettingsRepository.onProfileChanged()
    }

    @AfterTest
    fun clearState() {
        initialize()
    }

    @Test
    fun enrichmentCanBeEnabledAndReloadedWithoutAPersonalKey() {
        assertFalse(TmdbSettingsRepository.snapshot().enabled)

        TmdbSettingsRepository.setEnabled(true)
        TmdbSettingsRepository.onProfileChanged()

        val settings = TmdbSettingsRepository.snapshot()
        assertTrue(settings.enabled)
        assertNull(TmdbSettingsStorage.exportToSyncPayload()["tmdb_api_key"])
    }

    @Test
    fun legacyPersonalKeysAreIgnoredAndExcludedFromSync() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("nuvio_tmdb_settings", Context.MODE_PRIVATE)
            .edit()
            .putString(ProfileScopedKey.of("tmdb_api_key"), "legacy-personal-key")
            .commit()
        TmdbSettingsRepository.onProfileChanged()
        TmdbSettingsRepository.setEnabled(true)
        TmdbSettingsRepository.onProfileChanged()

        assertTrue(TmdbSettingsRepository.snapshot().enabled)
        assertNull(TmdbSettingsStorage.exportToSyncPayload()["tmdb_api_key"])
    }

    @Test
    fun syncedSettingsIgnoreLegacyPersonalKeys() {
        TmdbSettingsStorage.replaceFromSyncPayload(buildJsonObject {
            put("tmdb_enabled", encodeSyncBoolean(true))
            put("tmdb_api_key", encodeSyncString("remote-personal-key"))
        })
        TmdbSettingsRepository.onProfileChanged()

        assertTrue(TmdbSettingsRepository.snapshot().enabled)
        assertNull(TmdbSettingsStorage.exportToSyncPayload()["tmdb_api_key"])

        TmdbSettingsRepository.setEnabled(false)
        TmdbSettingsRepository.onProfileChanged()

        assertFalse(TmdbSettingsRepository.snapshot().enabled)
    }
}
