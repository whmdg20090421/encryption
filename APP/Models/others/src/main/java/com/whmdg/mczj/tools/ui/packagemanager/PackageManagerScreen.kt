package com.whmdg.mczj.tools.ui.packagemanager

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.whmdg.mczj.tools.ui.filemanager.StandardDialog
import com.whmdg.mczj.tools.util.FormatUtils

/** 用户应用少于该数量时，判定为应用列表读取被系统拦截 */
private const val USER_APP_BLOCKED_THRESHOLD = 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showBlockedDialog by remember { mutableStateOf(false) }

    val onlySystem = selectedTab == 1

    suspend fun reload() {
        loading = true
        apps = InstalledAppProvider.loadApps(context, onlySystem)
        loading = false
    }

    LaunchedEffect(selectedTab) {
        reload()
    }

    // 每次加载完用户应用后判定，数量异常则提示
    LaunchedEffect(apps, selectedTab) {
        if (!onlySystem && !loading && apps.size < USER_APP_BLOCKED_THRESHOLD) {
            showBlockedDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("安装包提取") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("用户应用") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("系统应用") }
                )
            }

            if (loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        InstalledAppCard(app)
                    }
                }
            }
        }
    }

    if (showBlockedDialog) {
        StandardDialog(
            onDismissRequest = { showBlockedDialog = false },
            title = {
                Text(
                    text = "无法读取应用列表",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = "当前仅读取到 ${apps.size} 个用户应用，可能被系统的应用列表隐私管控拦截。请前往系统设置为本应用开启「获取应用列表」权限后重试。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBlockedDialog = false
                    try {
                        context.startActivity(InstalledAppProvider.buildAppSettingsIntent(context))
                    } catch (_: Exception) {}
                }) {
                    Text("前往设置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockedDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun InstalledAppCard(app: InstalledApp) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val iconBitmap = app.icon?.toBitmap(96, 96)
            if (iconBitmap != null) {
                Image(
                    painter = BitmapPainter(iconBitmap.asImageBitmap()),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = app.versionName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = FormatUtils.formatBytes(app.apkSize),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (app.splitSize > 0) {
                        Text(
                            text = "拆分 +${FormatUtils.formatBytes(app.splitSize)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
