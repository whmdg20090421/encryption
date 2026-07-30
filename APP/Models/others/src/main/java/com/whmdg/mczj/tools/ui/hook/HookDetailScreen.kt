package com.whmdg.mczj.tools.ui.hook

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.util.XposedDetector
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HookDetailScreen(
    packageName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val target = remember { HookConfig.TARGETS.find { it.packageName == packageName } }
    val version = remember { HookConfig.getVersionName(context, packageName) }
    val moduleActive = remember { XposedDetector.isModuleActive() }
    val scopeEnabled = remember { mutableStateOf(HookConfig.isScopeEnabled(context, packageName)) }

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
    val allGatesOn = moduleActive && scopeEnabled.value

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(target.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                        checked = scopeEnabled.value,
                        onCheckedChange = {
                            scopeEnabled.value = it
                            HookConfig.setScopeEnabled(context, packageName, it)
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
                var featureOn by remember {
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
                                if (!scopeEnabled.value) append("\n需先开启作用域开关")
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
            val diagFileCount = remember { diagDir.listFiles()?.size ?: 0 }

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
