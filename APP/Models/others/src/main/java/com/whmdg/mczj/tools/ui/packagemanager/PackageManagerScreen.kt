package com.whmdg.mczj.tools.ui.packagemanager

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.ui.components.AppInfoDialog
import com.whmdg.mczj.tools.ui.components.AppInfoRowData
import com.whmdg.mczj.tools.ui.filemanager.StandardDialog
import com.whmdg.mczj.tools.util.FormatUtils
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

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

/** 应用详情弹窗：复用 core 的 AppInfoDialog 外壳，与 ApkInfoDialog 保持一致 */
@Composable
private fun AppPackageInfoDialog(app: AppPackageInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var detail by remember(app.packageName) { mutableStateOf<AppPackageDetail?>(null) }
    var loadFailed by remember(app.packageName) { mutableStateOf(false) }

    // ── 提取安装包 ──
    var isExtracting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ApkExtractor.Progress?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var conflictName by remember { mutableStateOf<String?>(null) }
    var conflictCont by remember { mutableStateOf<CancellableContinuation<ApkExtractor.ConflictDecision>?>(null) }
    val cancelFlag = remember { AtomicBoolean(false) }

    // 从系统设置返回后，若已授权则自动继续等待用户再次点击（不自动触发，避免状态错乱）
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (hasAllFilesAccess(context)) {
            Toast.makeText(context, "已获得所有文件访问权限，请再次点击提取", Toast.LENGTH_SHORT).show()
        }
    }
    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(context, "已获得存储权限，请再次点击提取", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(app.packageName) {
        detail = runCatching { AppPackageInfoProvider.loadDetail(context, app) }.getOrNull()
        if (detail == null) loadFailed = true
    }

    fun startExtract(d: AppPackageDetail) {
        if (!hasAllFilesAccess(context)) {
            showPermissionDialog = true
            return
        }
        if (isExtracting) return
        isExtracting = true
        progress = null
        cancelFlag.set(false)
        scope.launch {
            val result = ApkExtractor.extract(
                outputDir = AppDataPaths.extractedApks(context),
                appName = app.appName,
                versionName = app.versionName,
                baseApkPath = d.apkPath,
                splitApkPaths = d.splitApkPaths,
                onConflict = { name ->
                    suspendCancellableCoroutine { cont ->
                        scope.launch {
                            conflictName = name
                            conflictCont = cont
                        }
                    }
                },
                onProgress = { p ->
                    scope.launch { progress = p }
                },
                cancelFlag = cancelFlag
            )
            isExtracting = false
            conflictName = null
            conflictCont = null
            val message = when (result) {
                is ApkExtractor.Result.Success ->
                    "已提取到 ${result.outputPath}"
                ApkExtractor.Result.Skipped -> "已跳过，目标文件已存在"
                ApkExtractor.Result.Cancelled -> "已取消提取"
                is ApkExtractor.Result.Failed -> "提取失败：${result.message}"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val rows = detail?.let { d ->
        listOf(
            AppInfoRowData("包名", app.packageName),
            AppInfoRowData("版本号 (Code)", d.versionCode.toString()),
            AppInfoRowData("安装包大小", FormatUtils.formatBytes(app.totalSize)),
            AppInfoRowData("签名状态", d.signatureStatus),
            AppInfoRowData("加固状态", d.hardeningStatus),
            AppInfoRowData("内部数据目录", d.internalDataDir),
            AppInfoRowData("外部数据目录", d.externalDataDir),
            AppInfoRowData("APK 路径", d.apkPath),
            AppInfoRowData("UID", d.uid.toString())
        )
    }

    AppInfoDialog(
        icon = app.icon,
        appName = app.appName,
        versionName = app.versionName,
        rows = rows,
        onDismiss = onDismiss,
        loadFailed = loadFailed,
        buttons = {
            TextButton(onClick = {}, enabled = false) {
                Text("更多")
            }
            TextButton(
                onClick = { detail?.let { startExtract(it) } },
                enabled = detail != null && !isExtracting
            ) {
                Text("提取安装包")
            }
        }
    )

    // ── 需所有文件访问权限 ──
    if (showPermissionDialog) {
        StandardDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("需要存储权限", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "提取安装包需要「所有文件访问」权限，以便写入 ${AppDataPaths.extractedApks(context).absolutePath}。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            permissionLauncher.launch(intent)
                        } catch (_: Exception) {
                            try {
                                permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            } catch (_: Exception) {
                                Toast.makeText(context, "无法跳转权限设置，请手动前往系统设置开启", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        runtimePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }) {
                    Text("前往设置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 同名冲突 ──
    conflictName?.let { name ->
        val decide: (ApkExtractor.ConflictDecision) -> Unit = { decision ->
            conflictCont?.resume(decision)
            conflictCont = null
            conflictName = null
        }
        StandardDialog(
            onDismissRequest = { decide(ApkExtractor.ConflictDecision.CANCEL) },
            title = { Text("文件已存在", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "目标目录已存在同名文件：\n$name\n\n覆盖将删除原文件后重新生成。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { decide(ApkExtractor.ConflictDecision.OVERWRITE) }) {
                    Text("覆盖")
                }
            },
            dismissButton = {
                TextButton(onClick = { decide(ApkExtractor.ConflictDecision.SKIP) }) {
                    Text("跳过")
                }
            },
            leadingButton = {
                TextButton(onClick = { decide(ApkExtractor.ConflictDecision.CANCEL) }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 提取进度（同名冲突待用户选择时先隐藏，避免双弹窗叠加） ──
    if (isExtracting && conflictName == null) {
        val p = progress
        StandardDialog(
            onDismissRequest = {},
            title = { Text("正在提取安装包", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (p != null) {
                        LinearProgressIndicator(
                            progress = { p.fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${(p.fraction * 100).toInt()}%  ${FormatUtils.formatBytes(p.bytesWritten)} / ${FormatUtils.formatBytes(p.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { cancelFlag.set(true) }) {
                    Text("取消")
                }
            }
        )
    }
}

/** 是否拥有「所有文件访问」权限（API 30+ 用 MANAGE_EXTERNAL_STORAGE，否则用运行时写权限）。 */
private fun hasAllFilesAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}
