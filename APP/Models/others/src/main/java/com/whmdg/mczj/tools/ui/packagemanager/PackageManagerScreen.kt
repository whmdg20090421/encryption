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
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.ui.AppNavigation
import com.whmdg.mczj.tools.ui.components.AppInfoDialog
import com.whmdg.mczj.tools.ui.components.AppInfoRowData
import com.whmdg.mczj.tools.ui.filemanager.StandardDialog
import com.whmdg.mczj.tools.ui.theme.DialogWidthFraction
import com.whmdg.mczj.tools.util.FormatUtils
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** 用户应用少于该数量时，判定为应用列表读取被系统拦截 */
private const val USER_APP_BLOCKED_THRESHOLD = 10

/** 安装包列表排序字段 */
private enum class PackageSortField { PACKAGE, NAME, SIZE, UPDATE_TIME }

private const val PREF_KEY_SORT_FIELD = "package_sort_field"
private const val PREF_KEY_SORT_REVERSE = "package_sort_reverse"

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

    // 搜索态：进入后标题替换为输入框，工具栏图标变为叉叉
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }

    // 排序偏好（持久化到 AppDataPaths.PREFS_PACKAGE_MANAGER）
    val sortPrefs = remember { AppDataPaths.prefs(context, AppDataPaths.PREFS_PACKAGE_MANAGER) }
    var sortField by remember {
        mutableStateOf(
            runCatching { PackageSortField.valueOf(sortPrefs.getString(PREF_KEY_SORT_FIELD, null) ?: "") }
                .getOrDefault(PackageSortField.UPDATE_TIME)
        )
    }
    var sortReverse by remember { mutableStateOf(sortPrefs.getBoolean(PREF_KEY_SORT_REVERSE, false)) }

    var showSortMenu by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var tempSortField by remember { mutableStateOf(sortField) }
    var tempSortReverse by remember { mutableStateOf(sortReverse) }

    val onlySystem = selectedTab == 1
    val currentApps = if (onlySystem) systemApps else userApps
    val isLoading = currentApps == null

    // 退出搜索：清空查询词并恢复工具栏
    val exitSearch = {
        isSearching = false
        searchQuery = ""
    }

    // 先按查询词过滤，再按排序字段/方向排序；查询词为空则仅排序
    val displayedApps = remember(currentApps, searchQuery, sortField, sortReverse) {
        val list = currentApps ?: emptyList()
        val searched = searchQuery.takeIf { it.isNotEmpty() }
            ?.let { q ->
                list.filter {
                    it.appName.contains(q, ignoreCase = true) ||
                        it.packageName.contains(q, ignoreCase = true)
                }
            } ?: list
        // 默认方向：包名/名称 A→Z，大小 大到小，更新时间 新到旧
        val sorted = when (sortField) {
            PackageSortField.PACKAGE -> searched.sortedBy { it.packageName.lowercase() }
            PackageSortField.NAME -> searched.sortedBy { it.appName.lowercase() }
            PackageSortField.SIZE -> searched.sortedByDescending { it.totalSize }
            PackageSortField.UPDATE_TIME -> searched.sortedByDescending { it.lastUpdateTime }
        }
        if (sortReverse) sorted.reversed() else sorted
    }

    // 虚拟返回手势：搜索态下等同于点击叉叉
    BackHandler(enabled = isSearching) { exitSearch() }

    // 进入搜索态后自动聚焦并弹出输入法
    LaunchedEffect(isSearching) {
        if (isSearching) searchFocusRequester.requestFocus()
    }

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
                title = {
                    if (isSearching) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                            placeholder = { Text("搜索应用名称或包名") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "清除")
                                    }
                                }
                            },
                            singleLine = true
                        )
                    } else {
                        Text("安装包提取")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (isSearching) exitSearch() else isSearching = true
                        }
                    ) {
                        Icon(
                            if (isSearching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (isSearching) "退出搜索" else "搜索"
                        )
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("排序方式") },
                                trailingIcon = {
                                    val fieldLabel = when (sortField) {
                                        PackageSortField.PACKAGE -> "包名"
                                        PackageSortField.NAME -> "名称"
                                        PackageSortField.SIZE -> "大小"
                                        PackageSortField.UPDATE_TIME -> "更新时间"
                                    }
                                    // 默认方向：包名/名称升序，大小/更新时间降序；逆向选择翻转
                                    val ascending = when (sortField) {
                                        PackageSortField.PACKAGE, PackageSortField.NAME -> !sortReverse
                                        PackageSortField.SIZE, PackageSortField.UPDATE_TIME -> sortReverse
                                    }
                                    Text(
                                        "$fieldLabel${if (ascending) "↑" else "↓"}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                onClick = {
                                    tempSortField = sortField
                                    tempSortReverse = sortReverse
                                    showSortMenu = false
                                    showSortDialog = true
                                }
                            )
                        }
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
                        items(displayedApps, key = { it.packageName }) { app ->
                            AppPackageCard(app, onClick = { selectedApp = app })
                        }
                    }
                }
            }
        }
    }

    if (showSortDialog) {
        val fieldLabels = listOf(
            PackageSortField.PACKAGE to "应用包名排序",
            PackageSortField.NAME to "应用名称排序",
            PackageSortField.SIZE to "安装包大小排序",
            PackageSortField.UPDATE_TIME to "安装时间排序"
        )
        Dialog(
            onDismissRequest = { showSortDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(DialogWidthFraction),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "排序方式",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                    )
                    for ((field, label) in fieldLabels) {
                        val isSelected = tempSortField == field
                        Surface(
                            onClick = { tempSortField = field },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { tempSortReverse = !tempSortReverse }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = tempSortReverse,
                            onCheckedChange = { tempSortReverse = it }
                        )
                        Text(
                            text = "逆向选择",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showSortDialog = false }) {
                            Text("取消")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            sortField = tempSortField
                            sortReverse = tempSortReverse
                            sortPrefs.edit()
                                .putString(PREF_KEY_SORT_FIELD, sortField.name)
                                .putBoolean(PREF_KEY_SORT_REVERSE, sortReverse)
                                .apply()
                            showSortDialog = false
                        }) {
                            Text("确定")
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
        AppPackageInfoDialog(app = app, onDismiss = { selectedApp = null }, onBack = onBack)
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
private fun AppPackageInfoDialog(
    app: AppPackageInfo,
    onDismiss: () -> Unit,
    onBack: () -> Unit
) {
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
    // 提取完成提示：非空时弹窗展示产物名称与路径
    var extractedResult by remember { mutableStateOf<ApkExtractor.Result.Success?>(null) }

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
            when (result) {
                is ApkExtractor.Result.Success -> extractedResult = result
                ApkExtractor.Result.Skipped ->
                    Toast.makeText(context, "已跳过，目标文件已存在", Toast.LENGTH_LONG).show()
                ApkExtractor.Result.Cancelled ->
                    Toast.makeText(context, "已取消提取", Toast.LENGTH_LONG).show()
                is ApkExtractor.Result.Failed ->
                    Toast.makeText(context, "提取失败：${result.message}", Toast.LENGTH_LONG).show()
            }
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

    // ── 提取完成：展示产物名称与路径，可一键定位到文件管理器 ──
    extractedResult?.let { success ->
        val output = File(success.outputPath)
        val dirPath = output.parent ?: ""
        StandardDialog(
            onDismissRequest = { extractedResult = null },
            title = { Text("提取完成", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = output.name,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = success.outputPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    extractedResult = null
                    AppNavigation.requestFileManager(dirPath)
                    onBack()
                }) {
                    Text("定位")
                }
            },
            dismissButton = {
                TextButton(onClick = { extractedResult = null }) {
                    Text("关闭")
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
