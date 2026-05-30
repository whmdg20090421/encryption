package com.whmdg.mczj.tools.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.ui.components.GlowCard
import com.whmdg.mczj.tools.ui.components.GlowListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    onBack: () -> Unit,
    onNavigate: (Screen) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("安全") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                GlowCard {
                    Column {
                        GlowListItem(
                            title = "权限设置",
                            subtitle = "查看和管理应用权限",
                            icon = Icons.Default.Info,
                            onClick = { onNavigate(Screen.PermissionSettings) }
                        )
                        GlowListItem(
                            title = "特殊权限",
                            subtitle = "无障碍、ADB、Root 等特殊权限",
                            icon = Icons.Default.Lock,
                            onClick = { onNavigate(Screen.SpecialPermissions) }
                        )
                        GlowListItem(
                            title = "应用权限管理配置",
                            subtitle = "配置权限管理策略",
                            icon = Icons.Default.Settings,
                            onClick = { onNavigate(Screen.PermissionManagementConfig) }
                        )
                    }
                }
            }
        }
    }
}
