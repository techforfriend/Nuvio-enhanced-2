package com.nuvio.app.core.ui.glass

import android.app.Application
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LiquidGlassRuntimeShaderSupportTest {
    @Test
    @Config(sdk = [32], application = Application::class)
    fun runtimeShaderGlassUsesFallbackBeforeAndroid13() {
        assertFalse(isRuntimeShaderSupported())
    }

    @Test
    @Config(sdk = [33], application = Application::class)
    fun runtimeShaderGlassUsesAndroid13AsMinimumSupportedApi() {
        assertTrue(isRuntimeShaderSupported())
    }
}
