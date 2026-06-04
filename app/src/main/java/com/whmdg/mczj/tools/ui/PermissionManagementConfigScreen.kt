package com.whmdg.mczj.tools.ui

import android.content.Context
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.ui.components.GlowCard

data class PermissionMode(
    val key: String,
    val title: String,
    val description: String,
    val requiredPermission: String,
    val icon: @Composable () -> Unit
)

fun checkModeAvailability(context: Context, mode: String): Boolean {
    val sp = context.getSharedPreferences(AppDataPaths.PREFS_LEGACY_SPECIAL_PERMISSIONS, Context.MODE_PRIVATE)
    val savedLevel = sp.getString("target_permission_level", "STANDARD") ?: "STANDARD"
    val hasRoot = SpecialPermissionVerifier.isRootAvailable() || savedLevel == "ROOT"
    val hasShizuku = SpecialPermissionVerifier.isShizukuAuthorized(context) || savedLevel == "ADB"
    return when (mode) {
        "NORMAL" -> true
        "APPOPS" -> hasRoot || hasShizuku
        "PERMISSION_CONTROLLER" -> hasRoot
        else -> false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionManagementConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(AppDataPaths.PREFS_PERMISSION_MANAGEMENT, Context.MODE_PRIVATE)
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
            description = "通过应用操作管理（AppOps）细粒度控制权限，支持「允许」「忽略」「拒绝」「仅前台」等状态。需要 Root 或 Shizuku 权限执行 appops 命令。",
            requiredPermission = "需要：Root 或 Shizuku 权限",
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
                        "APPOPS" -> "AppOps 模式需要 Root 或 Shizuku 权限才能执行 appops 命令。\n\n请通过 Root 管理器（如 Magisk）授予 su 授权，或启动 Shizuku 并授权本应用。"
                        "PERMISSION_CONTROLLER" -> "高级权限管理模式需要 Root 权限才能生效。\n\n请先通过 Root 管理器（如 Magisk）授予本应用 su 授权。"
                        else -> ""
                    }
                )
            },
            confirmButton = {
                Button(onClick = { showErrorDialog = null }) { Text("确定") }
            }
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

                    GlowCard(
                        modifier = Modifier.clickable {
                            if (isAvailable) {
                                selectedMode = mode.key
                                prefs.edit().putString("mode", mode.key).apply()
                            } else {
                                showErrorDialog = mode.key
                            }
                        }
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
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFFE8F4FF)
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
                                    color = Color(0x9964B4D2)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    mode.requiredPermission,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isAvailable) Color(0xFF38D4F5)
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                GlowCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "提示",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE8F4FF)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "• 普通模式：无需额外权限，使用系统标准授权开关\n" +
                            "• AppOps 模式：需 Root 或 Shizuku 权限，支持细粒度的权限状态控制\n" +
                            "• 高级模式：需 Root 权限，支持最细粒度的权限控制",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0x9964B4D2)
                        )
                    }
                }
            }
        }
    }
}
