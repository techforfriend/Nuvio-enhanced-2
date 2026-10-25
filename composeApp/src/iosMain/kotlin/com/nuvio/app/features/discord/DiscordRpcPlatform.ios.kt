package com.nuvio.app.features.discord

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import kotlinx.coroutines.delay
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.distantPast
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private const val TOKEN_READER_SCRIPT =
    "(function(){try{var i=document.createElement('iframe');i.style.display='none';document.body.appendChild(i);var t=i.contentWindow.localStorage.getItem('token');i.remove();return t?JSON.parse(t):null;}catch(e){return null;}})()"

private const val DESKTOP_SAFARI_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

private const val TOKEN_POLL_INTERVAL_MS = 750L

internal actual object DiscordRpcPlatform {
    actual val isSupported: Boolean = true

    actual fun clearLoginSession() {
        dispatch_async(dispatch_get_main_queue()) {
            WKWebsiteDataStore.defaultDataStore().removeDataOfTypes(
                dataTypes = WKWebsiteDataStore.allWebsiteDataTypes(),
                modifiedSince = NSDate.distantPast,
                completionHandler = {},
            )
        }
    }
}

internal actual fun createDiscordGatewayHttpClient(): HttpClient = HttpClient(Darwin) {
    install(WebSockets)
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun DiscordLoginWebView(
    modifier: Modifier,
    onLoginCompleted: (String) -> Unit,
) {
    val currentOnLoginCompleted = rememberUpdatedState(onLoginCompleted)
    val webView = remember {
        WKWebView(
            frame = CGRectZero.readValue(),
            configuration = WKWebViewConfiguration().apply {
                websiteDataStore = WKWebsiteDataStore.defaultDataStore()
            },
        ).apply {
            customUserAgent = DESKTOP_SAFARI_USER_AGENT
            NSURL.URLWithString(DiscordRpcConfig.LOGIN_URL)?.let { url ->
                loadRequest(NSURLRequest.requestWithURL(url))
            }
        }
    }

    LaunchedEffect(webView) {
        var delivered = false
        while (!delivered) {
            delay(TOKEN_POLL_INTERVAL_MS)
            if (!webView.isSignedInRoute()) continue
            var token: String? = null
            var done = false
            webView.evaluateJavaScript(TOKEN_READER_SCRIPT) { result, _ ->
                token = (result as? String)?.trim()?.removeSurrounding("\"")
                    ?.takeIf { it.isNotBlank() && it != "null" }
                done = true
            }
            while (!done) delay(50)
            token?.let {
                delivered = true
                webView.setHidden(true)
                currentOnLoginCompleted.value(it)
            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
        }
    }

    UIKitView(
        factory = { webView },
        modifier = modifier,
    )
}

private fun WKWebView.isSignedInRoute(): Boolean {
    val url = URL ?: return false
    val host = url.host.orEmpty()
    val path = url.path.orEmpty()
    if (!host.endsWith("discord.com")) return false
    return !(path.isBlank() || path == "/" || path.startsWith("/login") || path.startsWith("/register"))
}
