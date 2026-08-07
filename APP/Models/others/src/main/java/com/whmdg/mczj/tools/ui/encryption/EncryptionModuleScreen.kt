package com.whmdg.mczj.tools.ui.encryption

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import com.whmdg.mczj.tools.encryption.data.VaultRecord
import com.whmdg.mczj.tools.encryption.services.VaultService
import com.whmdg.mczj.tools.encryption.services.VaultSession
import com.whmdg.mczj.tools.ui.Screen

@Composable
fun EncryptionModuleScreen(
    vaultService: VaultService,
    onBack: () -> Unit,
    onNavigate: (Screen) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { EncryptionSettings(context) }
    var backStack by remember { mutableStateOf(listOf<EncryptionRoute>(EncryptionRoute.Home)) }
    val currentRoute = backStack.lastOrNull() ?: EncryptionRoute.Home

    fun navigateTo(route: EncryptionRoute) {
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

    // Screen → EncryptionRoute 转换（用于接收现有 Screen 函数的 onNavigate 回调）
    fun screenToRoute(screen: Screen): EncryptionRoute? = when (screen) {
        is Screen.VaultCreate -> EncryptionRoute.VaultCreate
        is Screen.VaultChangePassword -> EncryptionRoute.VaultChangePassword(screen.vault.relativePath)
        else -> null
    }

    // 包装 onNavigate：将 Screen 子类转换为 EncryptionRoute 并压栈，非加密路由传递给父级
    val navigateFromScreen: (Screen) -> Unit = { screen ->
        val route = screenToRoute(screen)
        if (route != null) {
            backStack = backStack + route
        } else {
            // 非加密子路由（如 Screen.FileManager），传递给父级处理
            onNavigate(screen)
        }
    }

    when (currentRoute) {
        is EncryptionRoute.Home -> {
            EncryptionHomeScreen(
                vaultService = vaultService,
                settings = settings,
                onBack = { navigateBack() },
                onNavigate = navigateFromScreen
            )
        }
        is EncryptionRoute.VaultCreate -> {
            VaultCreateScreen(
                vaultService = vaultService,
                onBack = { navigateBack() }
            )
        }
        is EncryptionRoute.VaultChangePassword -> {
            // VaultChangePasswordScreen needs a VaultRecord - this will be handled through EncryptionHomeScreen
            EncryptionHomeScreen(
                vaultService = vaultService,
                settings = settings,
                onBack = { navigateBack() },
                onNavigate = navigateFromScreen
            )
        }
        is EncryptionRoute.Settings -> {
            EncryptionSettingsTab(settings = settings)
        }
    }
}