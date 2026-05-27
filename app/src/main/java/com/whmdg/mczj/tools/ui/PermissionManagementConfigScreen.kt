package com.whmdg.mczj.tools.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier

data class PermissionMode(
    val key: String,
    val title: String,
    val description: String,
    val requiredPermission: String,
    val icon: @Composable () -> Unit
)

fun checkModeAvailability(context: Context, mode: String): Boolean {
    val sp = context.getSharedPreferences("special_permissions", Context.MODE_PRIVATE)
    val savedLevel = sp.getString("target_permission_level", "STANDARD") ?: "STANDARD"
    return when (mode) {
        "NORMAL" -> true
        "APPOPS" -> SpecialPermissionVerifier.isAdbEnabled(context)
                    || savedLevel == "ADB" || savedLevel == "ROOT"
        "PERMISSION_CONTROLLER" -> SpecialPermissionVerifier.isRootAvailable()
                    || savedLevel == "ROOT"
        else -> false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionManagementConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("permission_management_mode", Context.MODE_PRIVATE)
    var selectedMode by remember { mutableStateOf(prefs.getString("mode", "NORMAL") ?: "NORMAL") }
    var showErrorDialog by remember { mutableStateOf<String?>(null) }

    val modes = listOf(
        PermissionMode(
            key = "NORMAL",
            title = "普通模式",
            description = "使用标准 Android 权限开关，通过系统授权/撤销管理应用权限。无需额外权限即可使用。",
            requiredPermission = "无需额外权限",
            icon = { Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        ),
        PermissionMode(
            key = "APPOPS",
            title = "AppOps 模式",
            description = "通过应用操作管理（AppOps）细粒度控制权限，支持「允许」「忽略」「拒绝」「仅前台」等状态。需要「修改系统设置」权限或 Shizuku 授权。",
            requiredPermission = "需要：修改系统设置权限 / Shizuku / ADB",
            icon = { Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) }
        ),
        PermissionMode(
            key = "PERMISSION_CONTROLLER",
            title = "高级权限管理模式",
            description = "通过 Root 权限访问 PermissionController 内部接口，支持「一次性允许」「用户固定拒绝」「策略固定」等全部状态。需要 Root 权限。",
            requiredPermission = "需要：Root 权限",
            icon = { Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) }
        )
    )

    if (showErrorDialog != null) {
        val mode = showErrorDialog!!
        AlertDialog(
            onDismissRequest = { showErrorDialog = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("权限不足") },
            text = {
                Text(
                    when (mode) {
                        "APPOPS" -> "AppOps 模式需要「修改系统设置」权限或 Shizuku 授权才能生效。\n\n部分手机可在系统设置的特殊权限中手动开启。"
                        "PERMISSION_CONTROLLER" -> "高级权限管理模式需要 Root 权限才能生效。\n\n请先通过 Root 管理器（如 Magisk）授予本应用 su 授权。"
                        else -> ""
                    }
                )
            },
            confirmButton = {
                if (mode == "APPOPS") {
                    Button(onClick = {
                        showErrorDialog = null
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            android.widget.Toast.makeText(context, "无法打开设置，请使用 ADB 命令授予", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }) { Text("打开设置") }
                } else {
                    Button(onClick = { showErrorDialog = null }) { Text("确定") }
                }
            },
            dismissButton = if (mode == "APPOPS") {
                { TextButton(onClick = { showErrorDialog = null }) { Text("取消") } }
            } else null
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用权限管理配置") },
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "选择应用权限管理模式",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "不同模式提供不同粒度的权限控制能力，选择后将应用到「应用权限管理」工具中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            modes.forEach { mode ->
                item {
                    val isAvailable = checkModeAvailability(context, mode.key)
                    val isSelected = selectedMode == mode.key

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isAvailable) {
                                    selectedMode = mode.key
                                    prefs.edit().putString("mode", mode.key).apply()
                                } else {
                                    showErrorDialog = mode.key
                                }
                            },
                        shape = MaterialTheme.shapes.medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surface,
                        tonalElevation = if (isSelected) 2.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    if (isAvailable) {
                                        selectedMode = mode.key
                                        prefs.edit().putString("mode", mode.key).apply()
                                    } else {
                                        showErrorDialog = mode.key
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    mode.icon()
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        mode.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (!isAvailable) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                                        ) {
                                            Text(
                                                "权限不足",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    mode.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    mode.requiredPermission,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isAvailable) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "提示",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "• 普通模式：无需额外权限，使用系统标准授权开关\n" +
                            "• AppOps 模式：需「修改系统设置」权限，可在特殊权限中授予\n" +
                            "• 高级模式：需 Root 权限，支持最细粒度的权限控制",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
