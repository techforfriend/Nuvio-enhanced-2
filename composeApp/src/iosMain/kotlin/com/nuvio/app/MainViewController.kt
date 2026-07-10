package com.nuvio.app

import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.nuvio.app.navigation.AppRoute
import platform.UIKit.UIColor
import platform.UIKit.UIViewController

private val nuvioBackgroundColor = UIColor(red = 0.051, green = 0.051, blue = 0.051, alpha = 1.0)

@Suppress("unused")
fun MainViewController(): UIViewController = nuvioComposeViewController {
    App()
}

@Suppress("unused")
fun MainViewController(
    initialTabName: String,
    onNavigate: (AppRoute, Boolean) -> Unit,
    onGoBack: () -> Unit,
    onReplace: (AppRoute) -> Unit,
    onActivate: (String) -> Unit,
    onAppReady: (Boolean) -> Unit,
    onTabTitles: (String, String, String, String) -> Unit,
): UIViewController {
    val initialTab = AppScreenTab.fromName(initialTabName)
    return nuvioComposeViewController {
        App(
            initialTab = initialTab,
            useNativeNavigation = true,
            ownsAppRuntime = initialTab == AppScreenTab.Home,
            bypassAppGate = initialTab != AppScreenTab.Home,
            onNavigate = onNavigate,
            onGoBack = onGoBack,
            onReplace = onReplace,
            onActivate = { tab -> onActivate(tab.name) },
            onAppReady = onAppReady,
            onTabTitles = onTabTitles,
        )
    }
}

@Suppress("unused")
fun ScreenViewController(
    route: AppRoute,
    onNavigate: (AppRoute, Boolean) -> Unit,
    onGoBack: () -> Unit,
    onReplace: (AppRoute) -> Unit,
    onActivate: (String) -> Unit,
): UIViewController = nuvioComposeViewController {
    App(
        initialRoute = route,
        useNativeNavigation = true,
        ownsAppRuntime = false,
        bypassAppGate = true,
        onNavigate = onNavigate,
        onGoBack = onGoBack,
        onReplace = onReplace,
        onActivate = { tab -> onActivate(tab.name) },
    )
}

private fun nuvioComposeViewController(
    content: @androidx.compose.runtime.Composable () -> Unit,
): UIViewController = ComposeUIViewController(
    configure = { onFocusBehavior = OnFocusBehavior.DoNothing },
    content = content,
).apply {
    view.backgroundColor = nuvioBackgroundColor
}
