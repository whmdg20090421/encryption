package com.whmdg.mczj.tools.ui.accounting

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.whmdg.mczj.tools.ui.Screen

/**
 * 记账本模块导航入口，管理记账本内部所有子页面的跳转。
 * 点击进入模块 → 显示首页；子页面跳转由内部 backStack 管理；返回到空栈时回调 onBack 回到主页。
 */
@Composable
fun AccountingModuleScreen(onBack: () -> Unit) {
    val backStack = remember { mutableStateListOf<AccountingRoute>(AccountingRoute.Home) }
    var selectedTab by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    BackHandler {
        if (backStack.size > 1) backStack.removeLast() else onBack()
    }

    // AccountingRoute → Screen 转换（用于传给现有 Screen 函数的 onNavigate）
    fun routeToScreen(route: AccountingRoute): Screen = when (route) {
        is AccountingRoute.Detail -> Screen.Accounting // placeholder, 不会真正用到
        is AccountingRoute.AddRecord -> Screen.Accounting
        is AccountingRoute.ReimbursementAccount -> Screen.Accounting
        is AccountingRoute.ReimbursementDetail -> Screen.Accounting
        is AccountingRoute.AddReimbursementAccount -> Screen.Accounting
        is AccountingRoute.AssetDetail -> Screen.Accounting
        else -> Screen.Accounting
    }

    // Screen → AccountingRoute 转换（用于接收现有 Screen 函数的 onNavigate 回调）
    fun screenToRoute(screen: Screen): AccountingRoute? = when (screen) {
        is Screen.AccountingDetail -> AccountingRoute.Detail(screen.bookName, screen.recordId)
        is Screen.AddAccounting -> AccountingRoute.AddRecord(screen.bookName, screen.recordId)
        is Screen.ReimbursementAccount -> AccountingRoute.ReimbursementAccount
        is Screen.ReimbursementAccountDetail -> AccountingRoute.ReimbursementDetail(screen.accountId)
        is Screen.AddReimbursementAccount -> AccountingRoute.AddReimbursementAccount
        is Screen.AssetDetail -> AccountingRoute.AssetDetail(screen.accountId)
        else -> null
    }

    // 包装 onNavigate：将 Screen 子类转换为 AccountingRoute 并压栈
    val navigateFromScreen: (Screen) -> Unit = { screen ->
        val route = screenToRoute(screen)
        if (route != null) {
            backStack.add(route)
        }
    }

    val current = backStack.last()
    when (current) {
        is AccountingRoute.Home -> AccountingScreen(
            onBack = onBack,
            onNavigate = navigateFromScreen,
            selectedTab = selectedTab,
            onTabSelect = { selectedTab = it }
        )
        is AccountingRoute.Detail -> AccountingDetailScreen(
            onBack = { backStack.removeLast() },
            onNavigate = navigateFromScreen,
            bookName = current.bookName,
            recordId = current.recordId
        )
        is AccountingRoute.AddRecord -> AddAccountingScreen(
            onBack = { backStack.removeLast() },
            bookName = current.bookName,
            recordId = current.recordId
        )
        is AccountingRoute.ReimbursementAccount -> ReimbursementAccountScreen(
            onBack = { backStack.removeLast() },
            onNavigate = navigateFromScreen
        )
        is AccountingRoute.ReimbursementDetail -> ReimbursementAccountDetailScreen(
            accountId = current.accountId,
            onBack = { backStack.removeLast() },
            onNavigate = navigateFromScreen
        )
        is AccountingRoute.AddReimbursementAccount -> AddReimbursementAccountScreen(
            onBack = { backStack.removeLast() }
        )
        is AccountingRoute.AssetDetail -> AssetDetailScreen(
            accountId = current.accountId,
            onBack = { backStack.removeLast() },
            onNavigate = navigateFromScreen
        )
    }
}
