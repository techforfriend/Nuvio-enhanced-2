package com.nuvio.app.features.settings

import android.app.Application
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.membership.MemberAssetStorage
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NavigationBarEffectDraftScopeTest {
    @get:Rule
    val compose = createComposeRule()

    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_theme_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ThemeSettingsStorage.initialize(context)
        MemberAssetStorage.initialize(context)
        MemberAccessRepository.clearLocalState()
        ThemeSettingsRepository.clearLocalState()
        ThemeSettingsRepository.ensureLoaded()
    }

    @AfterTest
    fun clearState() {
        ThemeSettingsRepository.clearLocalState()
    }

    @Test
    fun draftSliderUpdatesRecomposeTheDraftHostWithoutRecomposingItsParent() {
        var parentCompositions = 0
        var draftHostCompositions = 0

        compose.setContent {
            parentCompositions += 1
            NavigationBarLiquidGlassEffectDrafts { bar, lens ->
                SideEffect { draftHostCompositions += 1 }
                Text(text = "${bar.blurRadiusDp}:${lens.blurRadiusDp}")
            }
        }
        compose.waitForIdle()

        var parentBefore = 0
        var draftBefore = 0
        var currentBlur = 0f
        compose.runOnIdle {
            parentBefore = parentCompositions
            draftBefore = draftHostCompositions
            currentBlur = ThemeSettingsRepository.navBarLiquidGlassBarEffects.value.blurRadiusDp
        }
        val nextBlur = if (currentBlur == 7f) 8f else 7f

        compose.runOnIdle {
            val current = ThemeSettingsRepository.navBarLiquidGlassBarEffects.value
            ThemeSettingsRepository.updateNavBarLiquidGlassBarEffects(current.copy(blurRadiusDp = nextBlur))
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(parentBefore, parentCompositions, "Draft changes should not invalidate the parent settings item")
            assertTrue(draftHostCompositions > draftBefore, "The draft host must observe the updated slider value")
        }
    }
}
