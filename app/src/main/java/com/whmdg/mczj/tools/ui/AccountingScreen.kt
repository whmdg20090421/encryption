package com.whmdg.mczj.tools.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun AccountingScreen(onBack: () -> Unit) {
    val listState = rememberLazyListState()
    var showBookMenu by remember { mutableStateOf(false) }
    var currentBookName by remember { mutableStateOf("默认记账本") }
    var selectedTab by remember { mutableIntStateOf(0) }

    // 判断是否在顶部：第一个 item 可见且 offset 为 0
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    // 背景 alpha：顶部时 0，非顶部时 1
    val bgAlpha by animateFloatAsState(targetValue = if (isAtTop) 0f else 1f, label = "bgAlpha")
    val barHeight = 75.dp

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(if (selectedTab == 0) Icons.Filled.Home else Icons.Outlined.Home, contentDescription = "首页") },
                    label = { Text("首页") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(if (selectedTab == 1) Icons.Filled.AccountBalanceWallet else Icons.Outlined.AccountBalanceWallet, contentDescription = "资产") },
                    label = { Text("资产") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(if (selectedTab == 2) Icons.Filled.BarChart else Icons.Outlined.BarChart, contentDescription = "统计") },
                    label = { Text("统计") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(if (selectedTab == 3) Icons.Filled.CalendarMonth else Icons.Outlined.CalendarMonth, contentDescription = "日历") },
                    label = { Text("日历") }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(if (selectedTab == 4) Icons.Filled.Person else Icons.Outlined.Person, contentDescription = "我的") },
                    label = { Text("我的") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 内容区（空白，留待后续实现）
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                // 顶部留白，给按钮区域让位
                item { Spacer(Modifier.height(barHeight)) }
            }

            // 状态栏背景层（独立控制显隐）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .graphicsLayer { alpha = bgAlpha }
                    .background(MaterialTheme.colorScheme.surface)
            )

            // 顶部功能按钮层（始终可见）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 记账本按钮 + 下拉菜单
                Box {
                    TextButton(onClick = { showBookMenu = true }) {
                        Text("记账本", style = MaterialTheme.typography.titleMedium)
                    }
                    DropdownMenu(
                        expanded = showBookMenu,
                        onDismissRequest = { showBookMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(currentBookName) },
                            onClick = { showBookMenu = false }
                        )
                    }
                }
                // 房子按钮（返回主页）
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Home, contentDescription = "返回主页")
                }
            }
        }
    }
}
