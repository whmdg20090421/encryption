package com.whmdg.mczj.tools.ui.download

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import com.whmdg.mczj.tools.ui.Screen
import com.whmdg.mczj.tools.ui.download.Deviant.DeviantDownloaderScreen
import com.whmdg.mczj.tools.ui.download.Deviant.DeviantLoginScreen

@Composable
fun DownloaderModuleScreen(
    onBack: () -> Unit
) {
    var backStack by remember { mutableStateOf(listOf<DownloaderRoute>(DownloaderRoute.BatchDownloader)) }
    val currentRoute = backStack.lastOrNull() ?: DownloaderRoute.BatchDownloader

    fun navigateTo(route: DownloaderRoute) {
        backStack = backStack + route
    }

    fun navigateBack() {
        if (backStack.size > 1) {
            backStack = backStack.dropLast(1)
        } else {
            onBack()
        }
    }

    BackHandler(enabled = backStack.size > 1) {
        navigateBack()
    }

    when (currentRoute) {
        is DownloaderRoute.BatchDownloader -> {
            BatchDownloaderScreen(
                onBack = { navigateBack() },
                onNavigate = { screen ->
                    when (screen) {
                        is Screen.FADownloader -> navigateTo(DownloaderRoute.FADownloader)
                        is Screen.DeviantDownloader -> navigateTo(DownloaderRoute.DeviantDownloader)
                    }
                }
            )
        }
        is DownloaderRoute.FADownloader -> {
            FADownloaderScreen(
                onBack = { navigateBack() },
                onLogin = { navigateTo(DownloaderRoute.FALogin) }
            )
        }
        is DownloaderRoute.FALogin -> {
            FALoginScreen(
                onBack = { navigateBack() },
                onLoginSuccess = { _, _ -> navigateBack() }
            )
        }
        is DownloaderRoute.DeviantDownloader -> {
            DeviantDownloaderScreen(
                onBack = { navigateBack() },
                onLogin = { navigateTo(DownloaderRoute.DeviantLogin) }
            )
        }
        is DownloaderRoute.DeviantLogin -> {
            DeviantLoginScreen(
                onBack = { navigateBack() },
                onLoginSuccess = { _ -> navigateBack() }
            )
        }
    }
}
