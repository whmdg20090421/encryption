package com.whmdg.mczj.tools.ui.hook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.ui.isDebugAuth
import com.whmdg.mczj.tools.util.XposedDebugInfo
import com.whmdg.mczj.tools.util.XposedDetector
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HookScreen(
    onBack: () -> Unit,
    onAppClick: (String) -> Unit
) {
    val context = LocalContext.current
    val moduleActive = remember { XposedDetector.isModuleActive() }
    val isDebugMode = remember { isDebugAuth(context) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var scopeDialogTarget by remember { mutableStateOf<HookTarget?>(null) }
    var scopeRequestLoading by remember { mutableStateOf(false) }
    var scopeRequestError by remember { mutableStateOf<String?>(null) }
    var showDebugDialog by remember { mutableStateOf(false) }
    var debugInfo by remember { mutableStateOf<XposedDebugInfo?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val installed = remember { HookConfig.TARGETS.filter { HookConfig.isInstalled(context, it.packageName) } }
    val notInstalled = remember { HookConfig.TARGETS.filter { !HookConfig.isInstalled(context, it.packageName) } }

    // ── 作用域申请弹窗 ──
    if (scopeDialogTarget != null) {
        val target = scopeDialogTarget!!
        AlertDialog(
            onDismissRequest = {
                if (!scopeRequestLoading) {
                    scopeDialogTarget = null
                    scopeRequestError = null
                }
            },
            icon = { Icon(Icons.Default.Extension, contentDescription = null) },
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
                            val ok = HookConfig.addScope(target.packageName)
                            if (ok) {
                                scopeDialogTarget = null
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
                        scopeDialogTarget = null
                        scopeRequestError = null
                    },
                    enabled = !scopeRequestLoading
                ) {
                    Text("取消")
                }
            }
        )
    }

    // ── Debug 弹窗 ──
    if (showDebugDialog) {
        val info = debugInfo
        AlertDialog(
            onDismissRequest = { showDebugDialog = false },
            icon = { Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Hook 调试信息") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (info == null) {
                        Text("正在采集...")
                    } else {
                        Text("模块激活: ${if (info.moduleActive) "是" else "否"}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Binder 链:", style = MaterialTheme.typography.labelMedium)
                        info.steps.forEach { (step, result) ->
                            Text("  $step → $result", style = MaterialTheme.typography.bodySmall)
                        }
                        if (info.scopeList.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("作用域 (${info.scopeList.size}):", style = MaterialTheme.typography.labelMedium)
                            info.scopeList.forEach { pkg ->
                                Text("  • $pkg", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (info.exception != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("异常: ${info.exception}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (info != null) {
                        val text = buildString {
                            appendLine("=== Hook 调试信息 ===")
                            appendLine("模块激活: ${if (info.moduleActive) "是" else "否"}")
                            appendLine()
                            appendLine("Binder 链:")
                            info.steps.forEach { (step, result) -> appendLine("  $step → $result") }
                            if (info.scopeList.isNotEmpty()) {
                                appendLine()
                                appendLine("作用域 (${info.scopeList.size}):")
                                info.scopeList.forEach { appendLine("  • $it") }
                            }
                            if (info.exception != null) {
                                appendLine()
                                appendLine("异常: ${info.exception}")
                            }
                        }
                        clipboardManager.setText(AnnotatedString(text))
                    }
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = { showDebugDialog = false }) { Text("关闭") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hook") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (isDebugMode) {
                        IconButton(onClick = {
                            debugInfo = null
                            showDebugDialog = true
                            coroutineScope.launch {
                                debugInfo = XposedDetector.collectDebugInfo()
                            }
                        }) {
                            Icon(Icons.Default.BugReport, contentDescription = "调试", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 模块未激活提示
            if (!moduleActive) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .then(if (isDebugMode) Modifier.clickable {
                                debugInfo = null
                                showDebugDialog = true
                                coroutineScope.launch { debugInfo = XposedDetector.collectDebugInfo() }
                            } else Modifier),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Xposed 模块未激活，请在 LSPosed 管理器中启用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // 已安装应用（推荐作用域）
            if (installed.isNotEmpty()) {
                item {
                    Text(
                        "推荐作用域",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        installed.forEachIndexed { index, target ->
                            val currentScope = remember(refreshKey) { HookConfig.isScopeEnabled(target.packageName) }
                            var scopeEnabled by remember { mutableStateOf(currentScope) }
                            LaunchedEffect(refreshKey) { scopeEnabled = currentScope }

                            HookAppRow(
                                target = target,
                                context = context,
                                scopeEnabled = scopeEnabled,
                                moduleActive = moduleActive,
                                onScopeToggle = {
                                    if (it) {
                                        // ON → 弹窗确认后再调 API
                                        scopeDialogTarget = target
                                        scopeRequestError = null
                                    } else {
                                        // OFF → 直接移除
                                        scopeEnabled = false
                                        HookConfig.removeScope(target.packageName)
                                    }
                                },
                                onClick = { onAppClick(target.packageName) }
                            )
                            if (index < installed.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            }

            // 未安装应用
            if (notInstalled.isNotEmpty()) {
                item {
                    Text(
                        "未安装",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
                        )
                    ) {
                        notInstalled.forEachIndexed { index, target ->
                            HookAppRow(
                                target = target,
                                context = context,
                                scopeEnabled = false,
                                moduleActive = moduleActive,
                                enabled = false,
                                onScopeToggle = {},
                                onClick = {}
                            )
                            if (index < notInstalled.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }

            // 空状态
            if (installed.isEmpty() && notInstalled.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无已支持的应用",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HookAppRow(
    target: HookTarget,
    context: android.content.Context,
    scopeEnabled: Boolean,
    moduleActive: Boolean,
    enabled: Boolean = true,
    onScopeToggle: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val version = remember { HookConfig.getVersionName(context, target.packageName) }
    val rowAlpha = if (enabled && moduleActive) 1f else 0.38f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled && moduleActive) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Extension,
            contentDescription = null,
            tint = if (enabled && moduleActive && scopeEnabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = rowAlpha),
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                target.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = rowAlpha)
            )
            Text(
                target.packageName + if (version != null) "  v$version" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = rowAlpha)
            )
            if (enabled && scopeEnabled) {
                Text(
                    "已启用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Switch(
            checked = scopeEnabled,
            onCheckedChange = onScopeToggle,
            enabled = enabled && moduleActive
        )
    }
}
