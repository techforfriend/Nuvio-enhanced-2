package com.nuvio.app.features.discord

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

internal actual object DiscordRpcPlatform {
    actual val isSupported: Boolean = false

    actual fun clearLoginSession() = Unit
}

internal actual fun createDiscordGatewayHttpClient(): HttpClient = HttpClient(Darwin) {
    install(WebSockets)
}

@Composable
internal actual fun DiscordLoginWebView(
    modifier: Modifier,
    onLoginCompleted: (String) -> Unit,
) {
}
