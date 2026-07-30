package com.whmdg.mczj.tools.ui.hook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.util.XposedDetector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HookScreen(
    onBack: () -> Unit,
    onAppClick: (String) -> Unit
) {
    val context = LocalContext.current
    val moduleActive = remember { XposedDetector.isModuleActive() }

    val installed = remember { HookConfig.TARGETS.filter { HookConfig.isInstalled(context, it.packageName) } }
    val notInstalled = remember { HookConfig.TARGETS.filter { !HookConfig.isInstalled(context, it.packageName) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hook") },
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
                .padding(innerPadding)
        ) {
            // 模块未激活提示
            if (!moduleActive) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                            val scopeOn = remember {
                                HookConfig.isScopeEnabled(context, target.packageName)
                            }
                            var scopeEnabled by remember { mutableStateOf(scopeOn) }

                            HookAppRow(
                                target = target,
                                context = context,
                                scopeEnabled = scopeEnabled,
                                moduleActive = moduleActive,
                                onScopeToggle = {
                                    scopeEnabled = it
                                    HookConfig.setScopeEnabled(context, target.packageName, it)
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
