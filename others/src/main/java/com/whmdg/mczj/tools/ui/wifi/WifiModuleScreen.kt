package com.whmdg.mczj.tools.ui.wifi

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*

@Composable
fun WifiModuleScreen(
    onBack: () -> Unit
) {
    var backStack by remember { mutableStateOf(listOf<String>("wifi_home")) }

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

    WifiScreen(
        onBack = { navigateBack() }
    )
}