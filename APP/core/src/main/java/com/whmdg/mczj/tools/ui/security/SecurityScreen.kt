package com.whmdg.mczj.tools.ui.security

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.ui.Screen
import com.whmdg.mczj.tools.ui.components.GlowCard
import com.whmdg.mczj.tools.ui.components.GlowListItem
import com.whmdg.mczj.tools.util.XposedDetector

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
                        XposedDetectionItem()
                    }
                }
            }
        }
    }
}

@Composable
private fun XposedDetectionItem() {
    val isActive = remember { XposedDetector.isModuleActive() }

    GlowListItem(
        title = "Xposed 模块",
        subtitle = if (isActive) "模块已生效" else "模块未生效",
        icon = if (isActive) Icons.Default.CheckCircle else Icons.Default.Close,
        iconTint = if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336),
        trailing = {
            Icon(
                imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Close,
                contentDescription = null,
                tint = if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336),
                modifier = Modifier.size(20.dp)
            )
        }
    )
}
