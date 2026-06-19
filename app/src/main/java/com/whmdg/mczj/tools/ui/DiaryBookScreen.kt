package com.whmdg.mczj.tools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DiaryBookScreen(
    onBack: () -> Unit
) {
    Scaffold { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        // 顶部工具栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        }

        // 主内容区域
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
        ) {
            Text("日记内容区域", style = MaterialTheme.typography.bodyLarge)
        }
    }
    } // Scaffold
}
