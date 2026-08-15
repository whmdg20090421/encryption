package com.whmdg.mczj.tools.ui.hook

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import com.whmdg.mczj.tools.ui.hook.内存管理.MemoryUsageScreen

@Composable
fun HookModuleScreen(
    onBack: () -> Unit
) {
    var backStack by remember { mutableStateOf(listOf<HookRoute>(HookRoute.Home)) }
    val currentRoute = backStack.lastOrNull() ?: HookRoute.Home

    fun navigateTo(route: HookRoute) {
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
        is HookRoute.Home -> {
            HookScreen(
                onBack = { navigateBack() },
                onAppClick = { packageName -> navigateTo(HookRoute.Detail(packageName)) },
                onUsageTimeClick = { navigateTo(HookRoute.UsageTime) },
                onMemoryUsageClick = { navigateTo(HookRoute.MemoryUsage) }
            )
        }
        is HookRoute.Detail -> {
            HookDetailScreen(
                packageName = currentRoute.packageName,
                onBack = { navigateBack() }
            )
        }
        is HookRoute.UsageTime -> {
            UsageTimeScreen(onBack = { navigateBack() })
        }
        is HookRoute.MemoryUsage -> {
            MemoryUsageScreen(onBack = { navigateBack() })
        }
    }
}
