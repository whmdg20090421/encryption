package com.whmdg.mczj.tools.ui.packagemanager

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.whmdg.mczj.tools.ui.filemanager.StandardDialog
import com.whmdg.mczj.tools.ui.theme.DialogWidthFraction
import com.whmdg.mczj.tools.util.FormatUtils
import kotlinx.coroutines.launch

/** 用户应用少于该数量时，判定为应用列表读取被系统拦截 */
private const val USER_APP_BLOCKED_THRESHOLD = 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    // null 表示该标签尚未加载；非 null 即为已缓存的列表
    var userApps by remember { mutableStateOf<List<AppPackageInfo>?>(null) }
    var systemApps by remember { mutableStateOf<List<AppPackageInfo>?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showBlockedDialog by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<AppPackageInfo?>(null) }

    val onlySystem = selectedTab == 1
    val currentApps = if (onlySystem) systemApps else userApps
    val isLoading = currentApps == null

    // 首次进入当前标签且无缓存时才加载；切换标签命中缓存则直接复用
    LaunchedEffect(selectedTab) {
        if (currentApps == null) {
            val loaded = AppPackageInfoProvider.loadApps(context, onlySystem)
            if (onlySystem) systemApps = loaded else userApps = loaded
        }
    }

    // 每次用户应用列表变化后判定，数量异常则提示
    LaunchedEffect(userApps) {
        val list = userApps ?: return@LaunchedEffect
        if (list.size < USER_APP_BLOCKED_THRESHOLD) {
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

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        scope.launch {
                            isRefreshing = true
                            val loaded = AppPackageInfoProvider.loadApps(context, onlySystem)
                            if (onlySystem) systemApps = loaded else userApps = loaded
                            isRefreshing = false
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(currentApps ?: emptyList(), key = { it.packageName }) { app ->
                            AppPackageCard(app, onClick = { selectedApp = app })
                        }
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
                    text = "当前仅读取到 ${userApps?.size ?: 0} 个用户应用，可能被系统的应用列表隐私管控拦截。请前往系统设置为本应用开启「获取应用列表」权限后重试。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBlockedDialog = false
                    try {
                        context.startActivity(AppPackageInfoProvider.buildAppSettingsIntent(context))
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

    selectedApp?.let { app ->
        AppPackageInfoDialog(app = app, onDismiss = { selectedApp = null })
    }
}

@Composable
private fun AppPackageCard(app: AppPackageInfo, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sizeText = FormatUtils.formatBytes(app.totalSize)
                Text(
                    text = "${app.versionName}  $sizeText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

/** 应用详情弹窗：面板风格与 ApkInfoDialog 保持一致 */
@Composable
private fun AppPackageInfoDialog(app: AppPackageInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var detail by remember(app.packageName) { mutableStateOf<AppPackageDetail?>(null) }
    var loadFailed by remember(app.packageName) { mutableStateOf(false) }

    LaunchedEffect(app.packageName) {
        detail = runCatching { AppPackageInfoProvider.loadDetail(context, app) }.getOrNull()
        if (detail == null) loadFailed = true
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(DialogWidthFraction),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── 头部：图标 + 名称 + 版本名 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val iconBitmap = app.icon?.toBitmap(96, 96)
                    if (iconBitmap != null) {
                        Image(
                            painter = BitmapPainter(iconBitmap.asImageBitmap()),
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                    Column {
                        Text(
                            text = app.appName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = app.versionName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                )

                val d = detail
                if (d == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (loadFailed) {
                            Text(
                                text = "无法解析该应用信息",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    // ── 信息列表（每行可长按复制）──
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppPackageInfoRow("包名", app.packageName)
                        AppPackageInfoRow("版本号 (Code)", d.versionCode.toString())
                        AppPackageInfoRow("安装包大小", FormatUtils.formatBytes(app.totalSize))
                        AppPackageInfoRow("签名状态", d.signatureStatus)
                        AppPackageInfoRow("加固状态", d.hardeningStatus)
                        AppPackageInfoRow("内部数据目录", d.internalDataDir)
                        AppPackageInfoRow("外部数据目录", d.externalDataDir)
                        AppPackageInfoRow("APK 路径", d.apkPath)
                        AppPackageInfoRow("UID", d.uid.toString())
                    }
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                )

                // ── 底部按钮（暂为禁用占位）──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {}, enabled = false) {
                        Text("更多")
                    }
                    TextButton(onClick = {}, enabled = false) {
                        Text("提取安装包")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppPackageInfoRow(label: String, value: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(AnnotatedString(value))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }
            )
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
