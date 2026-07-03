package com.whmdg.mczj.tools.ui.rphub

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*

@Composable
fun RpHubModuleScreen(
    onBack: () -> Unit
) {
    var backStack by remember { mutableStateOf(listOf<RpHubRoute>(RpHubRoute.Home)) }
    val currentRoute = backStack.lastOrNull() ?: RpHubRoute.Home

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
        is RpHubRoute.Home -> {
            RpHubScreen(
                onBack = { navigateBack() }
            )
        }
    }
}