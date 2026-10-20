package com.nuvio.app.features.discord

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.ktor.client.HttpClient

internal expect object DiscordRpcPlatform {
    val isSupported: Boolean
    fun clearLoginSession()
}

internal expect fun createDiscordGatewayHttpClient(): HttpClient

@Composable
internal expect fun DiscordLoginWebView(
    modifier: Modifier,
    onLoginCompleted: (String) -> Unit,
)
