package com.whmdg.mczj.tools.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
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

    // 判断是否在顶部：第一个 item 可见且 offset 为 0
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    // 背景 alpha：顶部时 0，非顶部时 1
    val bgAlpha by animateFloatAsState(targetValue = if (isAtTop) 0f else 1f, label = "bgAlpha")

    Box(modifier = Modifier.fillMaxSize()) {
        // 内容区
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部留白，给按钮区域让位
            item { Spacer(Modifier.height(50.dp)) }
            // 占位内容
            items(50) { index ->
                Text(
                    text = "记账条目 ${index + 1}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        // 状态栏背景层（独立控制显隐）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .graphicsLayer { alpha = bgAlpha }
                .background(MaterialTheme.colorScheme.surface)
        )

        // 顶部功能按钮层（始终可见）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
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
