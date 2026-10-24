package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppVersionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class UpdatePreferences(versionName: String = AppVersionConfig.VERSION_NAME) {
    private val storedChannel = runCatching {
        UpdateChannel.fromStoredValue(AppUpdaterPlatform.getUpdateChannel())
    }.getOrNull()?.takeIf { it != UpdateChannel.STABLE }
    private val _channel = MutableStateFlow(storedChannel ?: UpdateChannel.BETA)
    val channel = _channel.asStateFlow()

    init {
        runCatching {
            if ((storedChannel == null || AppUpdaterPlatform.getUpdateChannel() == UpdateChannel.STABLE.storedValue) && AppUpdaterPlatform.isSupported) {
                AppUpdaterPlatform.setUpdateChannel(UpdateChannel.BETA.storedValue)
            }
        }
    }

    fun setChannel(channel: UpdateChannel) {
        if (_channel.value == channel) return
        runCatching {
            AppUpdaterPlatform.setUpdateChannel(channel.storedValue)
            AppUpdaterPlatform.setIgnoredTag(null)
        }
        _channel.value = channel
    }

    companion object {
        val shared by lazy { UpdatePreferences() }
    }
}
