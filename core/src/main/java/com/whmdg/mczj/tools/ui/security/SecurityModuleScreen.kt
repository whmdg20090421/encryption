package com.whmdg.mczj.tools.ui.security

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.whmdg.mczj.tools.ui.Screen

/**
 * 安全模块导航入口，管理安全/权限相关子页面的内部跳转。
 */
@Composable
fun SecurityModuleScreen(onBack: () -> Unit) {
    val backStack = remember { mutableStateListOf<SecurityRoute>(SecurityRoute.Security) }

    BackHandler {
        if (backStack.size > 1) backStack.removeLast() else onBack()
    }

    val current = backStack.last()
    when (current) {
        SecurityRoute.Security -> SecurityScreen(
            onBack = onBack,
            onNavigate = { screen ->
                when (screen) {
                    is Screen.PermissionSettings -> backStack.add(SecurityRoute.PermissionSettings)
                    is Screen.SpecialPermissions -> backStack.add(SecurityRoute.SpecialPermissions)
                    is Screen.AppPermissions -> backStack.add(SecurityRoute.AppPermissions)
                    is Screen.PermissionManagementConfig -> backStack.add(SecurityRoute.PermissionManagementConfig)
                    is Screen.AuthManagement -> backStack.add(SecurityRoute.PermissionSettings) // fallback
                    else -> {}
                }
            }
        )
        SecurityRoute.PermissionSettings -> PermissionSettingsScreen(
            onBack = { backStack.removeLast() }
        )
        SecurityRoute.SpecialPermissions -> SpecialPermissionsScreen(
            onBack = { backStack.removeLast() }
        )
        SecurityRoute.AppPermissions -> AppPermissionsScreen(
            onBack = { backStack.removeLast() }
        )
        SecurityRoute.PermissionManagementConfig -> PermissionManagementConfigScreen(
            onBack = { backStack.removeLast() }
        )
    }
}
