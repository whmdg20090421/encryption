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
                ListItem(
                    headlineContent = { Text("权限设置") },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = "权限设置") },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "进入") },
                    modifier = Modifier.clickable { onNavigate(Screen.PermissionSettings) }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("特殊权限") },
                    leadingContent = { Icon(Icons.Default.Lock, contentDescription = "特殊权限") },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "进入") },
                    modifier = Modifier.clickable { onNavigate(Screen.SpecialPermissions) }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("应用权限管理配置") },
                    leadingContent = { Icon(Icons.Default.Settings, contentDescription = "应用权限管理配置") },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "进入") },
                    modifier = Modifier.clickable { onNavigate(Screen.PermissionManagementConfig) }
                )
            }
        }
    }
}
