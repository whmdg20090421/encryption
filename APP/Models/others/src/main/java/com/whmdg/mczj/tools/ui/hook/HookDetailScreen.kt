package com.whmdg.mczj.tools.ui.hook

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.ShellExecutor
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.util.XposedDetector
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HookDetailScreen(
    packageName: String,
    onBack: () -> Unit,
    onNavigateToUsageTime: () -> Unit = {}
) {
    val context = LocalContext.current

    // System Server 专用页面
    if (packageName == "system_server") {
        SystemServerDetailScreen(onBack = onBack, onNavigateToUsageTime = onNavigateToUsageTime)
        return
    }

    val target = remember { HookConfig.TARGETS.find { it.packageName == packageName } }
    val version = remember { HookConfig.getVersionName(context, packageName) }
    val moduleActive = remember { XposedDetector.isModuleActive() }
    var refreshKey by remember { mutableIntStateOf(0) }
    val currentScope = remember(refreshKey) { HookConfig.isScopeEnabled(packageName) }
    var scopeEnabled by remember { mutableStateOf(currentScope) }
    LaunchedEffect(refreshKey) { scopeEnabled = currentScope }
    var scopeDialogVisible by remember { mutableStateOf(false) }
    var scopeRequestLoading by remember { mutableStateOf(false) }
    var scopeRequestError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    if (target == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("未知应用") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
                Text("未找到包名 $packageName 的 hook 配置")
            }
        }
        return
    }

    // L1 + L2 是否全部开启
    val allGatesOn = moduleActive && scopeEnabled

    // ── 作用域申请弹窗 ──
    if (scopeDialogVisible) {
        AlertDialog(
            onDismissRequest = {
                if (!scopeRequestLoading) {
                    scopeDialogVisible = false
                    scopeRequestError = null
                }
            },
            title = { Text("申请 Hook 作用域") },
            text = {
                Column {
                    Text("是否向 LSPosed 申请 ${target.displayName} 的作用域？\n\n申请后 LSPosed 将弹出确认窗口，请确认授权。")
                    if (scopeRequestError != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            scopeRequestError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scopeRequestLoading = true
                        scopeRequestError = null
                        coroutineScope.launch {
                            val ok = HookConfig.addScope(packageName)
                            if (ok) {
                                scopeDialogVisible = false
                                scopeRequestLoading = false
                                refreshKey++
                            } else {
                                scopeRequestLoading = false
                                scopeRequestError = "申请失败，请在 LSPosed 中手动勾选"
                            }
                        }
                    },
                    enabled = !scopeRequestLoading
                ) {
                    if (scopeRequestLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("确认")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scopeDialogVisible = false
                        scopeRequestError = null
                    },
                    enabled = !scopeRequestLoading
                ) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(target.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── 应用信息卡片 ──
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (allGatesOn) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        target.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (allGatesOn) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "包名: $packageName",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (allGatesOn) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    if (version != null) {
                        Text(
                            "版本: $version",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (allGatesOn) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // ── L1 作用域开关 ──
            Text(
                "作用域",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            ListItem(
                headlineContent = { Text("注入 Hook") },
                supportingContent = {
                    Text(
                        if (moduleActive) "开启后将对该应用注入 Xposed Hook"
                        else "模块未激活，请先在 LSPosed 管理器中启用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingContent = {
                    Switch(
                        checked = scopeEnabled,
                        onCheckedChange = {
                            if (it) {
                                scopeDialogVisible = true
                                scopeRequestError = null
                            } else {
                                scopeEnabled = false
                                HookConfig.removeScope(packageName)
                            }
                        },
                        enabled = moduleActive
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // ── L2 功能开关 ──
            Text(
                "Hook 功能",
                style = MaterialTheme.typography.titleSmall,
                color = if (allGatesOn) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            target.hookFeatures.forEach { feature ->
                var featureOn by remember(refreshKey) {
                    mutableStateOf(HookConfig.isFeatureEnabled(context, packageName, feature))
                }

                ListItem(
                    headlineContent = {
                        Text(
                            feature.label,
                            color = if (allGatesOn) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    },
                    supportingContent = {
                        Text(
                            buildString {
                                append(feature.description)
                                if (!scopeEnabled) append("\n需先开启作用域开关")
                                else if (!moduleActive) append("\n模块未激活")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (allGatesOn) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = featureOn,
                            onCheckedChange = {
                                featureOn = it
                                HookConfig.setFeatureEnabled(context, packageName, feature, it)
                            },
                            enabled = allGatesOn
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── 诊断信息 ──
            Text(
                "诊断",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            val diagDir = remember {
                AppDataPaths.diagnostics(context).let { File(it, "Hook/$packageName") }
            }
            val diagFileCount = remember(refreshKey) { diagDir.listFiles()?.size ?: 0 }

            ListItem(
                headlineContent = { Text("Hook 日志") },
                supportingContent = {
                    Text(
                        if (diagFileCount > 0) "$diagFileCount 个诊断文件" else "暂无诊断文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemServerDetailScreen(onBack: () -> Unit, onNavigateToUsageTime: () -> Unit) {
    val context = LocalContext.current
    var showPermissionDialog by remember { mutableStateOf(false) }

    // ── 使用时长权限不足弹窗 ──
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("需要权限") },
            text = { Text("查看手机使用时长需要「应用使用时长」特殊权限，请在设置中手动开启。") },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }) { Text("前往设置") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("System Server") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
                Text(
                    "Android 系统服务进程",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                onClick = {
                    handleUsageStatsClick(context, onNavigateToUsageTime) { showPermissionDialog = true }
                }
            ) {
                ListItem(
                    headlineContent = {
                        Text("手机使用时长")
                    },
                    supportingContent = {
                        Text(
                            "通过反射 API 获取设备使用时长统计",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                )
            }
        }
    }
}

private fun handleUsageStatsClick(context: Context, onNavigate: () -> Unit, onNeedPermission: () -> Unit) {
    // 先检查是否已有权限
    if (hasUsageStatsPermission(context)) {
        onNavigate()
        return
    }

    // 无权限，尝试 Root 授权
    if (SpecialPermissionVerifier.isRootAvailable()) {
        try {
            ShellExecutor.execute(
                Permission.ROOT,
                "appops set ${context.packageName} GET_USAGE_STATS allow"
            )
            if (hasUsageStatsPermission(context)) {
                Toast.makeText(context, "已通过 Root 授予应用使用时长权限", Toast.LENGTH_SHORT).show()
                onNavigate()
            } else {
                Toast.makeText(context, "Root 授权失败，请手动开启", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Root 授权失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    } else {
        // 无 Root，弹窗引导手动开启
        onNeedPermission()
    }
}

private fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}
