package com.whmdg.mczj.tools.ui.download

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*

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
                onNavigate = { /* Handle navigation to FADownloader/DeviantDownloader */ }
            )
        }
        is DownloaderRoute.FADownloader -> {
            FADownloaderScreen(
                onBack = { navigateBack() },
                onNavigate = { /* Handle navigation to FALogin */ }
            )
        }
        is DownloaderRoute.FALogin -> {
            FALoginScreen(
                onBack = { navigateBack() },
                onLoginSuccess = { /* Handle login success */ }
            )
        }
        is DownloaderRoute.DeviantDownloader -> {
            DeviantDownloaderScreen(
                onBack = { navigateBack() },
                onNavigate = { /* Handle navigation to DeviantLogin */ }
            )
        }
        is DownloaderRoute.DeviantLogin -> {
            DeviantLoginScreen(
                onBack = { navigateBack() },
                onLoginSuccess = { /* Handle login success */ }
            )
        }
    }
}