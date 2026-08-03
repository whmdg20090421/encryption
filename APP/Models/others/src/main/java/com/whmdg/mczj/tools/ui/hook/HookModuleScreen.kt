package com.whmdg.mczj.tools.ui.hook

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*

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
                onAppClick = { packageName -> navigateTo(HookRoute.Detail(packageName)) }
            )
        }
        is HookRoute.Detail -> {
            HookDetailScreen(
                packageName = currentRoute.packageName,
                onBack = { navigateBack() },
                onNavigateToUsageTime = { navigateTo(HookRoute.UsageTime) }
            )
        }
        is HookRoute.UsageTime -> {
            UsageStatsScreen(onBack = { navigateBack() })
        }
    }
}
