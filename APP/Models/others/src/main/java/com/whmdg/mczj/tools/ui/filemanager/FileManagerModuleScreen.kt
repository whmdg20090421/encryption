package com.whmdg.mczj.tools.ui.filemanager

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import com.whmdg.mczj.tools.ui.Screen

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
                onNavigate = { screen ->
                    when (screen) {
                        is Screen.TextEditor -> navigateTo(FileManagerRoute.TextEditor(screen.filePath))
                        is Screen.ImageViewer -> navigateTo(FileManagerRoute.ImageViewer(screen.filePath, screen.imagePaths, screen.startIndex))
                    }
                }
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
                imagePaths = currentRoute.imagePaths,
                startIndex = currentRoute.startIndex,
                onBack = { navigateBack() }
            )
        }
    }
}