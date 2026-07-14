package com.whmdg.mczj.tools.ui.diary

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import com.whmdg.mczj.tools.ui.Screen

@Composable
fun DiaryModuleScreen(
    onBack: () -> Unit
) {
    var backStack by remember { mutableStateOf(listOf<DiaryRoute>(DiaryRoute.Home)) }
    val currentRoute = backStack.lastOrNull() ?: DiaryRoute.Home

    fun navigateTo(route: DiaryRoute) {
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
        is DiaryRoute.Home -> {
            DiaryScreen(
                onBack = { navigateBack() },
                onNavigate = { screen ->
                    when (screen) {
                        is Screen.DiaryBookDetail -> navigateTo(DiaryRoute.BookDetail(screen.bookName))
                    }
                }
            )
        }
        is DiaryRoute.BookDetail -> {
            DiaryBookScreen(
                bookName = currentRoute.bookName,
                onBack = { navigateBack() }
            )
        }
    }
}