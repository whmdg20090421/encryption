package com.whmdg.mczj.tools.ui.filemanager

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import com.whmdg.mczj.tools.encryption.services.VaultSession
import com.whmdg.mczj.tools.ui.Screen

@Composable
fun FileManagerModuleScreen(
    onBack: () -> Unit,
    vaultSession: VaultSession? = null
) {
    var backStack by remember { mutableStateOf(listOf<FileManagerRoute>(FileManagerRoute.Home)) }
    val currentRoute = backStack.lastOrNull() ?: FileManagerRoute.Home

    // vault 模式的文件保存回调（由 FileManagerScreen 设置）
    var vaultSaveCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }

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

    // FileManagerScreen 始终保留在组合树中，避免路由切换时 LaunchedEffect 重新触发
    FileManagerScreen(
        onBack = { navigateBack() },
        onNavigate = { screen ->
            when (screen) {
                is Screen.TextEditor -> navigateTo(FileManagerRoute.TextEditor(screen.filePath))
                is Screen.ImageViewer -> navigateTo(FileManagerRoute.ImageViewer(screen.filePath, screen.imagePaths, screen.startIndex))
            }
        },
        vaultSession = vaultSession,
        onVaultSaveReady = { callback -> vaultSaveCallback = callback }
    )

    // 子页面叠加在 FileManagerScreen 之上
    when (currentRoute) {
        is FileManagerRoute.Home -> { /* FileManagerScreen 已在上方渲染 */ }
        is FileManagerRoute.TextEditor -> {
            TextEditorScreen(
                filePath = currentRoute.filePath,
                onBack = { navigateBack() },
                onSave = if (vaultSession != null) vaultSaveCallback else null
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