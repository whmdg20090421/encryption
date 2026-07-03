package com.whmdg.mczj.tools.ui.filemanager

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*

@Composable
fun FileManagerModuleScreen(
    onBack: () -> Unit
) {
    var backStack by remember { mutableStateOf(listOf<FileManagerRoute>(FileManagerRoute.Home)) }
    val currentRoute = backStack.lastOrNull() ?: FileManagerRoute.Home

    fun navigateTo(route: FileManagerRoute) {
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
        is FileManagerRoute.Home -> {
            FileManagerScreen(
                onBack = { navigateBack() },
                onNavigate = { /* Handle navigation to TextEditor/ImageViewer */ }
            )
        }
        is FileManagerRoute.TextEditor -> {
            TextEditorScreen(
                filePath = currentRoute.filePath,
                onBack = { navigateBack() }
            )
        }
        is FileManagerRoute.ImageViewer -> {
            ImageViewerScreen(
                filePath = currentRoute.filePath,
                onBack = { navigateBack() }
            )
        }
    }
}