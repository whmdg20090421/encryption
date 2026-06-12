package com.whmdg.mczj.tools.ui

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.util.DiagnosticLog
import com.whmdg.mczj.tools.util.CompressService
import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import coil3.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.whmdg.mczj.tools.ui.components.categorizeFile
import com.whmdg.mczj.tools.ui.components.extractExtension
import com.whmdg.mczj.tools.ui.components.getFileTypeDrawableRes
import com.whmdg.mczj.tools.ui.components.FileCategory
import com.whmdg.mczj.tools.ui.components.FileTypeIcon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import android.graphics.Rect as AndroidRect
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.lifecycle.viewmodel.compose.viewModel

enum class FocusedPanel { LEFT, RIGHT }
enum class CreateMode { FILE, FOLDER }
enum class SortField { NAME, SIZE, MODIFIED, CREATED }
enum class SortOrder { ASC, DESC }

data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val permission: String = "",
    val size: Long = 0,
    val lastModified: Long = 0,
    val createdAt: Long = 0
)

@kotlinx.serialization.Serializable
data class QuickAccessEntry(
    val name: String,
    val path: String
)

@kotlinx.serialization.Serializable
data class HistoryEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@kotlinx.serialization.Serializable
data class BookmarkEntry(
    val name: String,
    val path: String
)

/**
 * 面板导航历史状态（不可变，每次操作返回新实例）
 */
data class PanelNavState(
    val paths: List<String> = emptyList(),
    val index: Int = -1
) {
    val canGoBack: Boolean get() = index > 0
    val canGoForward: Boolean get() = index < paths.size - 1

    /** 访问新目录：截断前进历史，追加路径并前进到末尾 */
    fun navigate(path: String): PanelNavState {
        val newPaths = if (index < paths.size - 1) {
            paths.take(index + 1) + path
        } else {
            paths + path
        }
        return copy(paths = newPaths, index = newPaths.size - 1)
    }

    /** 后退一步 */
    fun back(): PanelNavState? = if (canGoBack) copy(index = index - 1) else null

    /** 前进一步 */
    fun forward(): PanelNavState? = if (canGoForward) copy(index = index + 1) else null

    val current: String get() = paths[index]
}

@OptIn(ExperimentalMaterial3Api::class)
// 系统文件管理器（FileManagerScreen）—— 不要与 VaultOpenScreen（保险箱文件浏览器）混淆
@Composable
fun FileManagerScreen(onBack: () -> Unit, onNavigate: (Screen) -> Unit = {}) {
    val context = LocalContext.current
    val vm: FileManagerViewModel = viewModel()

    var hasStoragePermission by remember {
        mutableStateOf(Environment.isExternalStorageManager())
    }
    val coroutineScope = rememberCoroutineScope()

    // ── 滚动状态（从 ViewModel 恢复） ──
    val leftListState = rememberLazyListState(vm.leftFirstVisibleIndex, vm.leftFirstVisibleOffset)
    val rightListState = rememberLazyListState(vm.rightFirstVisibleIndex, vm.rightFirstVisibleOffset)

    // 返回上级目录时恢复滚动位置（scrollPositions 按绝对路径存储，进入子目录时保存，返回时恢复）
    LaunchedEffect(vm.leftPath) {
        val saved = vm.getSavedScrollPosition(vm.leftPath) ?: return@LaunchedEffect
        val (index, offset) = saved
        if (index > 0 || offset > 0) {
            leftListState.scrollToItem(index, offset)
        }
    }
    LaunchedEffect(vm.rightPath) {
        val saved = vm.getSavedScrollPosition(vm.rightPath) ?: return@LaunchedEffect
        val (index, offset) = saved
        if (index > 0 || offset > 0) {
            rightListState.scrollToItem(index, offset)
        }
    }

    // ── UI 本地状态 ──
    var showDrawer by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var tempSortField by remember { mutableStateOf(vm.sortField) }
    var tempSortOrder by remember { mutableStateOf(vm.sortOrder) }
    var showSortSizeRefreshDialog by remember { mutableStateOf(false) }
    var unmeasuredDirs by remember { mutableStateOf(listOf<FileEntry>()) }
    var selectedEntry by remember { mutableStateOf<FileEntry?>(null) }
    val sortAscLabels = mapOf(
        SortField.NAME to "A到Z",
        SortField.SIZE to "小到大",
        SortField.MODIFIED to "最早到最近",
        SortField.CREATED to "最早到最近"
    )
    val sortDescLabels = mapOf(
        SortField.NAME to "Z到A",
        SortField.SIZE to "大到小",
        SortField.MODIFIED to "最近到最早",
        SortField.CREATED to "最近到最早"
    )
    var showCreateTypeDialog by remember { mutableStateOf(false) }
    var createMode by remember { mutableStateOf(CreateMode.FILE) }
    var showNameDialog by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var recycleBinEnabled by remember { mutableStateOf(true) }
    var showForceDeleteDialog by remember { mutableStateOf(false) }
    var forceDeleteEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showPermanentDeleteDialog by remember { mutableStateOf(false) }
    var permanentDeleteTarget by remember { mutableStateOf<String?>(null) }
    var showHistoryPanel by remember { mutableStateOf(false) }
    var panelTab by remember { mutableStateOf(0) } // 0=历史, 1=书签
    var bookmarkDeleteVisible by remember { mutableStateOf(setOf<String>()) }
    var showPropertyDialog by remember { mutableStateOf(false) }
    var showSizeCalcOptionsMenu by remember { mutableStateOf(false) }
    var propertyData by remember { mutableStateOf<FilePropertyData?>(null) }
    var propertyEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showPermissionEditor by remember { mutableStateOf(false) }
    var permissionEditorData by remember { mutableStateOf<FilePropertyData?>(null) }
    var permissionEditorEntry by remember { mutableStateOf<FileEntry?>(null) }

    // ── 外部打开警告 ──
    var forceOpenError by remember { mutableStateOf<String?>(null) }

    // ── 压缩相关状态 ──
    var showCompressDialog by remember { mutableStateOf(false) }
    var compressEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showCompressProgress by remember { mutableStateOf(false) }
    var compressProgress by remember { mutableStateOf(0f) }
    var compressCurrentFile by remember { mutableStateOf(0) }
    var compressTotalFiles by remember { mutableStateOf(0) }
    var compressOutputToOtherPanel by remember { mutableStateOf(false) }
    var compressUseAes by remember { mutableStateOf(true) }
    var compressOutputPath by remember { mutableStateOf("") }

    // ── 压缩包浏览状态 ──
    var showArchivePasswordDialog by remember { mutableStateOf(false) }
    var archivePasswordInput by remember { mutableStateOf("") }
    var archivePendingEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showArchiveOpening by remember { mutableStateOf(false) }
    var archiveOpenError by remember { mutableStateOf<String?>(null) }

    // ── 快捷访问 ──
    val quickAccessPrefs = context.getSharedPreferences("quick_access_prefs", Context.MODE_PRIVATE)
    val qaJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    var quickAccessList by remember {
        val saved = quickAccessPrefs.getString("entries", null)
        val list = if (saved != null) {
            try { qaJson.decodeFromString<List<QuickAccessEntry>>(saved) } catch (_: Exception) { emptyList() }
        } else emptyList()
        mutableStateOf(list)
    }
    var showAddQaDialog by remember { mutableStateOf(false) }
    var qaNameInput by remember { mutableStateOf("") }
    var qaPathInput by remember { mutableStateOf("") }

    fun saveQuickAccess() {
        quickAccessPrefs.edit().putString("entries", qaJson.encodeToString(quickAccessList)).apply()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        hasStoragePermission = Environment.isExternalStorageManager()
        if (hasStoragePermission) {
            vm.refreshBoth()
        }
    }

    // 历史记录持久化
    LaunchedEffect(vm.historyList) {
        vm.saveHistory()
    }
    // 书签持久化
    LaunchedEffect(vm.bookmarkList) {
        vm.saveBookmarks()
    }
    // 历史记录点击文件后滚动到目标文件
    LaunchedEffect(vm.pendingScrollToFile, vm.leftEntries, vm.rightEntries) {
        val targetName = vm.pendingScrollToFile ?: return@LaunchedEffect
        val entries = if (vm.focusedPanel == FocusedPanel.LEFT) vm.leftEntries else vm.rightEntries
        val listState = if (vm.focusedPanel == FocusedPanel.LEFT) leftListState else rightListState
        val index = entries.indexOfFirst { !it.isDirectory && it.name == targetName }
        if (index >= 0) {
            listState.scrollToItem(index)
            vm.pendingScrollToFile = null
        }
    }

    LaunchedEffect(Unit) {
        DiagnosticLog.beginSession("进入 FileManagerScreen")
        DiagnosticLog.log("FileMgr", "FileManagerScreen 启动 isRootEngine=${vm.isRootEngine} hasStoragePerm=$hasStoragePermission")
        if (!hasStoragePermission) {
            Toast.makeText(context, "需要存储权限才能浏览文件", Toast.LENGTH_LONG).show()
        }
    }

    // 保存当前滚动位置并返回上一级
    val saveScrollAndGoUp: () -> Boolean = {
        vm.saveCurrentScrollPosition(
            leftListState.firstVisibleItemIndex,
            leftListState.firstVisibleItemScrollOffset,
            rightListState.firstVisibleItemIndex,
            rightListState.firstVisibleItemScrollOffset
        )
        vm.goUp()
    }

    // 返回手势：压缩包内 → 回上一级或退出压缩包，回收站内 → 回上一级或退出回收站，子目录 → 回上一级，根目录 → 退出文件管理器
    BackHandler {
        if (vm.isInArchive) {
            if (!vm.goUpInArchive()) {
                vm.exitArchive()
            }
        } else if (vm.isInRecycleBin) {
            if (!vm.goUpInRecycleBin()) {
                vm.exitRecycleBin()
            }
        } else if (!saveScrollAndGoUp()) {
            onBack()
        }
    }

    val currentPath = vm.currentPath

    Scaffold(
        topBar = {
            Surface(
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                TopAppBar(
                    title = {
                        val titleText = when {
                            vm.isInArchive -> {
                                val relPath = vm.archivePath.removePrefix("/")
                                if (relPath.isEmpty()) vm.archiveFileName
                                else "${vm.archiveFileName}/$relPath"
                            }
                            vm.isInRecycleBin -> "回收站"
                            else -> currentPath
                        }
                        StartEllipsisText(
                            text = titleText,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { showDrawer = true }) {
                            Icon(Icons.Default.Menu, contentDescription = "菜单")
                        }
                    },
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Home, contentDescription = "返回主页")
                    }
                    Box {
                        IconButton(onClick = { showSettingsMenu = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                        DropdownMenu(
                            expanded = showSettingsMenu,
                            onDismissRequest = { showSettingsMenu = false }
                        ) {
                            // 显示隐藏文件
                            DropdownMenuItem(
                                text = { Text("显示隐藏文件") },
                                trailingIcon = {
                                    if (vm.showHiddenFiles) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    vm.updateShowHiddenFiles(!vm.showHiddenFiles)
                                    showSettingsMenu = false
                                }
                            )
                            HorizontalDivider()
                            // 排序菜单项
                            DropdownMenuItem(
                                text = { Text("排列顺序") },
                                trailingIcon = {
                                    val fieldLabel = when (vm.sortField) {
                                        SortField.NAME -> "名称"
                                        SortField.SIZE -> "大小"
                                        SortField.MODIFIED -> "最后修改时间"
                                        SortField.CREATED -> "创建时间"
                                    }
                                    val orderLabel = if (vm.sortOrder == SortOrder.ASC) "↑" else "↓"
                                    Text("$fieldLabel$orderLabel", style = MaterialTheme.typography.bodySmall)
                                },
                                onClick = {
                                    tempSortField = vm.sortField
                                    tempSortOrder = vm.sortOrder
                                    showSortDialog = true
                                    showSettingsMenu = false
                                }
                            )
                            HorizontalDivider()
                            // 添加书签
                            val currentFocusedPath = vm.currentPath
                            val isAlreadyBookmarked = vm.bookmarkList.any { it.path == currentFocusedPath }
                            DropdownMenuItem(
                                text = { Text("添加书签") },
                                trailingIcon = {
                                    if (isAlreadyBookmarked) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                },
                                onClick = {
                                    val folderName = currentFocusedPath.substringAfterLast('/').ifEmpty { "/" }
                                    if (!isAlreadyBookmarked) {
                                        vm.bookmarkList = listOf(BookmarkEntry(folderName, currentFocusedPath)) + vm.bookmarkList
                                    }
                                    showSettingsMenu = false
                                }
                            )
                            HorizontalDivider()
                            // 刷新当前列表大小
                            DropdownMenuItem(
                                text = { Text("刷新当前列表大小") },
                                trailingIcon = {
                                    Icon(Icons.Default.FolderSpecial, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showSettingsMenu = false
                                    val entriesToSize = if (vm.focusedPanel == FocusedPanel.LEFT) vm.leftEntries else vm.rightEntries
                                    val dirs = entriesToSize.filter { it.isDirectory }
                                    if (dirs.isEmpty()) {
                                        Toast.makeText(context, "当前列表没有文件夹", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val parentPath = if (vm.focusedPanel == FocusedPanel.LEFT) vm.leftPath else vm.rightPath
                                        vm.calculateFolderSizeAsync(parentPath)
                                    }
                                }
                            )
                        }
                    }
                }
            )
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                shadowElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                ) {
                // 上方 60dp：按钮区域（排除系统手势识别 + 上滑触发历史面板）
                val view = LocalView.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount < -30) {
                                    showHistoryPanel = true
                                }
                            }
                        }
                        .onGloballyPositioned { coords ->
                            val pos = coords.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                            val rect = AndroidRect(
                                pos.x.toInt(),
                                pos.y.toInt(),
                                (pos.x + coords.size.width).toInt(),
                                (pos.y + coords.size.height).toInt()
                            )
                            view.systemGestureExclusionRects = listOf(rect)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 后退按钮
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = {
                                vm.saveCurrentScrollPosition(
                                    leftListState.firstVisibleItemIndex,
                                    leftListState.firstVisibleItemScrollOffset,
                                    rightListState.firstVisibleItemIndex,
                                    rightListState.firstVisibleItemScrollOffset
                                )
                                vm.goBack()
                            },
                            enabled = vm.currentNavState.canGoBack
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "后退")
                        }
                    }
                    // 前进按钮
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = {
                                vm.saveCurrentScrollPosition(
                                    leftListState.firstVisibleItemIndex,
                                    leftListState.firstVisibleItemScrollOffset,
                                    rightListState.firstVisibleItemIndex,
                                    rightListState.firstVisibleItemScrollOffset
                                )
                                vm.goForward()
                            },
                            enabled = vm.currentNavState.canGoForward
                        ) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "前进")
                        }
                    }
                    // 新建按钮
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(onClick = { showCreateTypeDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "新建")
                        }
                    }
                    // 同步按钮
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(onClick = { vm.syncPaths() }) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "同步路径")
                        }
                    }
                    // 刷新按钮
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(onClick = { vm.refreshCurrent() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                    // 返回上一级按钮
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        val canGoUp = if (vm.isInArchive) {
                            !vm.isAtArchiveRoot
                        } else if (vm.isInRecycleBin) {
                            !vm.isAtRecycleBinRoot
                        } else {
                            val effectiveRoot = if (vm.isRootEngine) "/" else "/storage/emulated/0"
                            val parentPath = vm.currentPath.substringBeforeLast('/').ifEmpty { "/" }
                            vm.currentPath != effectiveRoot
                                && vm.currentPath.contains('/')
                                && parentPath != vm.currentPath
                                && vm.canAccessPath(parentPath)
                        }

                        IconButton(
                            onClick = {
                                if (vm.isInArchive) vm.goUpInArchive()
                                else if (vm.isInRecycleBin) vm.goUpInRecycleBin()
                                else saveScrollAndGoUp()
                            },
                            enabled = canGoUp
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "返回上一级")
                        }
                    }
                }
                // 下方 20dp：系统手势区域（随主题颜色）
                val surfaceColor = MaterialTheme.colorScheme.surface
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .drawBehind { drawRect(surfaceColor) }
                )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(targetState = hasStoragePermission) { granted ->
                if (!granted) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("需要存储权限", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "文件管理器需要「管理所有文件」权限才能浏览设备文件。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = {
                            val intent = android.content.Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                            ).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                            permissionLauncher.launch(intent)
                        }) {
                            Text("授予权限")
                        }
                    }
                } else {
                    val leftEffectiveRoot = if (vm.isRootEngine) "/" else "/storage/emulated/0"
                    val leftParentPath = if (vm.isInArchive) {
                        if (vm.isAtArchiveRoot) null else "archive_parent"
                    } else if (vm.isInRecycleBin) {
                        if (vm.isAtRecycleBinRoot) null
                        else java.io.File(vm.recycleBinPath).parentFile?.absolutePath?.let { p ->
                            if (try { java.io.File(p).canRead() } catch (_: Exception) { false }) p else null
                        }
                    } else if (vm.leftPath != leftEffectiveRoot && vm.leftPath.contains('/')) {
                        vm.leftPath.substringBeforeLast('/').ifEmpty { "/" }.let { p ->
                            if (p != vm.leftPath && vm.canAccessPath(p)) p else null
                        }
                    } else null

                    val rightEffectiveRoot = if (vm.isRootEngine) "/" else "/storage/emulated/0"
                    val rightParentPath = if (vm.isInArchive) {
                        if (vm.isAtArchiveRoot) null else "archive_parent"
                    } else if (vm.isInRecycleBin) {
                        if (vm.isAtRecycleBinRoot) null
                        else java.io.File(vm.recycleBinPath).parentFile?.absolutePath?.let { p ->
                            if (try { java.io.File(p).canRead() } catch (_: Exception) { false }) p else null
                        }
                    } else if (vm.rightPath != rightEffectiveRoot && vm.rightPath.contains('/')) {
                        vm.rightPath.substringBeforeLast('/').ifEmpty { "/" }.let { p ->
                            if (p != vm.rightPath && vm.canAccessPath(p)) p else null
                        }
                    } else null

                    val leftFocused = vm.focusedPanel == FocusedPanel.LEFT
                    Layout(
                        modifier = Modifier.fillMaxSize(),
                        content = {
                            FileBrowserPanel(
                                entries = vm.leftEntries,
                                isFocused = leftFocused,
                                onFocus = { vm.focusedPanel = FocusedPanel.LEFT },
                                onFolderClick = { entry ->
                                    vm.focusedPanel = FocusedPanel.LEFT
                                    if (vm.isInArchive) {
                                        vm.navigateInArchive(entry)
                                    } else if (vm.isInRecycleBin) {
                                        vm.navigateInRecycleBin(entry)
                                    } else {
                                        DiagnosticLog.beginSession("[LEFT] 点击文件夹 '${entry.name}'")
                                        DiagnosticLog.log("FileMgr", "[LEFT] 点击文件夹 name='${entry.name}' path='${entry.path}' from=${vm.leftPath}")
                                        vm.saveCurrentScrollPosition(
                                            leftListState.firstVisibleItemIndex,
                                            leftListState.firstVisibleItemScrollOffset,
                                            rightListState.firstVisibleItemIndex,
                                            rightListState.firstVisibleItemScrollOffset
                                        )
                                        vm.navigateToFolder(entry)
                                    }
                                },
                                onFileClick = { entry ->
                                    DiagnosticLog.beginSession("[LEFT] 点击文件 '${entry.name}'")
                                    DiagnosticLog.log("FileMgr", "[LEFT] 点击文件 name='${entry.name}' path='${entry.path}'")
                                    vm.focusedPanel = FocusedPanel.LEFT
                                    if (vm.isInArchive) {
                                        // 压缩包内文件点击 → 按需解压并打开
                                        val screen = vm.openFileInArchive(context, entry)
                                        if (screen != null) onNavigate(screen)
                                    } else {
                                        // 压缩包拦截
                                        val archiveFormat = CompressService.detectFormat(entry.name)
                                        if (archiveFormat != null) {
                                            archivePendingEntry = entry
                                            if (CompressService.isEncrypted(entry.path, archiveFormat)) {
                                                archivePasswordInput = ""
                                                showArchivePasswordDialog = true
                                            } else {
                                                showArchiveOpening = true
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val error = vm.openArchive(entry, "")
                                                    withContext(Dispatchers.Main) {
                                                        showArchiveOpening = false
                                                        if (error != null) {
                                                            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            val screen = vm.openFile(context, entry)
                                            if (screen != null) {
                                                vm.saveScrollPosition(
                                                    leftListState.firstVisibleItemIndex,
                                                    leftListState.firstVisibleItemScrollOffset,
                                                    rightListState.firstVisibleItemIndex,
                                                    rightListState.firstVisibleItemScrollOffset
                                                )
                                                onNavigate(screen)
                                            }
                                            vm.historyList = listOf(HistoryEntry(entry.name, entry.path, false)) + vm.historyList
                                        }
                                    }
                                },
                                onLongClick = { entry ->
                                    selectedEntry = entry
                                    vm.focusedPanel = FocusedPanel.LEFT
                                },
                                modifier = Modifier,
                                folderSizeDb = vm.folderSizeDb,
                                parentPath = leftParentPath,
                                lazyListState = leftListState,
                                onNavigateUp = {
                                    if (vm.isInArchive) {
                                        vm.focusedPanel = FocusedPanel.LEFT
                                        vm.goUpInArchive()
                                    } else if (vm.isInRecycleBin) {
                                        vm.focusedPanel = FocusedPanel.LEFT
                                        vm.goUpInRecycleBin()
                                    } else if (leftParentPath != null) {
                                        vm.focusedPanel = FocusedPanel.LEFT
                                        vm.saveCurrentScrollPosition(
                                            leftListState.firstVisibleItemIndex,
                                            leftListState.firstVisibleItemScrollOffset,
                                            rightListState.firstVisibleItemIndex,
                                            rightListState.firstVisibleItemScrollOffset
                                        )
                                        vm.navigateTo(leftParentPath)
                                    }
                                },
                                archiveSizeProvider = if (vm.isInArchive) { entry -> vm.getArchiveSizeText(entry) } else null
                            )
                            FileBrowserPanel(
                                entries = vm.rightEntries,
                                isFocused = !leftFocused,
                                onFocus = { vm.focusedPanel = FocusedPanel.RIGHT },
                                onFolderClick = { entry ->
                                    vm.focusedPanel = FocusedPanel.RIGHT
                                    if (vm.isInArchive) {
                                        vm.navigateInArchive(entry)
                                    } else if (vm.isInRecycleBin) {
                                        vm.navigateInRecycleBin(entry)
                                    } else {
                                        DiagnosticLog.beginSession("[RIGHT] 点击文件夹 '${entry.name}'")
                                        DiagnosticLog.log("FileMgr", "[RIGHT] 点击文件夹 name='${entry.name}' path='${entry.path}' from=${vm.rightPath}")
                                        vm.saveCurrentScrollPosition(
                                            leftListState.firstVisibleItemIndex,
                                            leftListState.firstVisibleItemScrollOffset,
                                            rightListState.firstVisibleItemIndex,
                                            rightListState.firstVisibleItemScrollOffset
                                        )
                                        vm.navigateToFolder(entry)
                                    }
                                },
                                onFileClick = { entry ->
                                    DiagnosticLog.beginSession("[RIGHT] 点击文件 '${entry.name}'")
                                    DiagnosticLog.log("FileMgr", "[RIGHT] 点击文件 name='${entry.name}' path='${entry.path}'")
                                    vm.focusedPanel = FocusedPanel.RIGHT
                                    if (vm.isInArchive) {
                                        // 压缩包内文件点击 → 按需解压并打开
                                        val screen = vm.openFileInArchive(context, entry)
                                        if (screen != null) onNavigate(screen)
                                    } else {
                                        // 压缩包拦截
                                        val archiveFormat = CompressService.detectFormat(entry.name)
                                        if (archiveFormat != null) {
                                            archivePendingEntry = entry
                                            if (CompressService.isEncrypted(entry.path, archiveFormat)) {
                                                archivePasswordInput = ""
                                                showArchivePasswordDialog = true
                                            } else {
                                                showArchiveOpening = true
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val error = vm.openArchive(entry, "")
                                                    withContext(Dispatchers.Main) {
                                                        showArchiveOpening = false
                                                        if (error != null) {
                                                            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            val screen = vm.openFile(context, entry)
                                            if (screen != null) {
                                                vm.saveScrollPosition(
                                                    leftListState.firstVisibleItemIndex,
                                                    leftListState.firstVisibleItemScrollOffset,
                                                    rightListState.firstVisibleItemIndex,
                                                    rightListState.firstVisibleItemScrollOffset
                                                )
                                                onNavigate(screen)
                                            }
                                            vm.historyList = listOf(HistoryEntry(entry.name, entry.path, false)) + vm.historyList
                                        }
                                    }
                                },
                                onLongClick = { entry ->
                                    selectedEntry = entry
                                    vm.focusedPanel = FocusedPanel.RIGHT
                                },
                                modifier = Modifier,
                                folderSizeDb = vm.folderSizeDb,
                                parentPath = rightParentPath,
                                lazyListState = rightListState,
                                onNavigateUp = {
                                    if (vm.isInArchive) {
                                        vm.focusedPanel = FocusedPanel.RIGHT
                                        vm.goUpInArchive()
                                    } else if (vm.isInRecycleBin) {
                                        vm.focusedPanel = FocusedPanel.RIGHT
                                        vm.goUpInRecycleBin()
                                    } else if (rightParentPath != null) {
                                        vm.focusedPanel = FocusedPanel.RIGHT
                                        vm.saveCurrentScrollPosition(
                                            leftListState.firstVisibleItemIndex,
                                            leftListState.firstVisibleItemScrollOffset,
                                            rightListState.firstVisibleItemIndex,
                                            rightListState.firstVisibleItemScrollOffset
                                        )
                                        vm.navigateTo(rightParentPath)
                                    }
                                },
                                archiveSizeProvider = if (vm.isInArchive) { entry -> vm.getArchiveSizeText(entry) } else null
                            )
                        }
                    ) { measurables, constraints ->
                        val halfWidth = constraints.maxWidth / 2
                        val panelConstraints = constraints.copy(minWidth = halfWidth, maxWidth = halfWidth)
                        val leftPlaceable = measurables[0].measure(panelConstraints)
                        val rightPlaceable = measurables[1].measure(panelConstraints)
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            // 先绘制非聚焦面板，再绘制聚焦面板（聚焦的在上层）
                            if (leftFocused) {
                                rightPlaceable.placeRelative(halfWidth, 0)
                                leftPlaceable.placeRelative(0, 0)
                            } else {
                                leftPlaceable.placeRelative(0, 0)
                                rightPlaceable.placeRelative(halfWidth, 0)
                            }
                        }
                    }
                }
            }

            // ── 历史记录面板（从底部滑入，占屏幕一半高度） ──
            if (showHistoryPanel) {
                BackHandler { showHistoryPanel = false }
                LaunchedEffect(Unit) { bookmarkDeleteVisible = emptySet() }
            }
            AnimatedVisibility(
                visible = showHistoryPanel,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // 上半部分：暗色遮罩 + 点击收起面板
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.4f)
                            .background(Color.Black.copy(alpha = 0.4f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { showHistoryPanel = false }
                            )
                    )
                    // 下半部分：面板内容
                    val surfaceColor = MaterialTheme.colorScheme.surface
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.6f)
                            .align(Alignment.BottomCenter)
                            .drawBehind { drawRect(surfaceColor) }
                            .padding(top = 8.dp)
                    ) {
                        // 标题行：标签切换 + 关闭按钮（clickable 拦截空白区域触摸）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {}
                                )
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row {
                                Text(
                                    text = "历史记录",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (panelTab == 0) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clickable { panelTab = 0 }
                                )
                                Spacer(Modifier.width(20.dp))
                                Text(
                                    text = "书签",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (panelTab == 1) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clickable { panelTab = 1 }
                                )
                            }
                            IconButton(onClick = { showHistoryPanel = false }) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        }
                        HorizontalDivider()
                        // 内容区：历史 or 书签（左右滑切换标签）
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures { _, dragAmount ->
                                        if (dragAmount < -50) panelTab = 1
                                        else if (dragAmount > 50) panelTab = 0
                                    }
                                }
                        ) {
                        AnimatedContent(
                            targetState = panelTab,
                            transitionSpec = {
                                if (targetState > initialState) {
                                    slideInHorizontally { it } + fadeIn() togetherWith
                                    slideOutHorizontally { -it } + fadeOut()
                                } else {
                                    slideInHorizontally { -it } + fadeIn() togetherWith
                                    slideOutHorizontally { it } + fadeOut()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        ) { tab ->
                        if (tab == 0) {
                            // ── 历史列表 ──
                            if (vm.historyList.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "暂无操作记录",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    items(vm.historyList) { entry ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(50.dp)
                                                .clickable {
                                                    if (entry.isDirectory) {
                                                        vm.navigateToHistoryDir(entry)
                                                    } else {
                                                        vm.navigateToHistoryFile(entry)
                                                    }
                                                    showHistoryPanel = false
                                                }
                                                .padding(horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val hExt = extractExtension(entry.name)
                                            val hCategory = categorizeFile(hExt)
                                            val hDrawableRes = if (!entry.isDirectory) getFileTypeDrawableRes(hCategory) else null
                                            if (hDrawableRes != null) {
                                                Icon(
                                                    painter = painterResource(hDrawableRes),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                    tint = Color.Unspecified
                                                )
                                            } else if (!entry.isDirectory && hCategory == FileCategory.APK) {
                                                FileTypeIcon(
                                                    filename = entry.name,
                                                    filePath = entry.path,
                                                    iconSize = 20.dp
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                                                           else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = entry.name,
                                                        maxLines = 1,
                                                        modifier = Modifier.weight(1f, fill = false),
                                                        overflow = TextOverflow.Ellipsis,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                    Text(
                                                        text = compactDate(entry.timestamp),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                StartEllipsisText(
                                                    text = entry.path,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // ── 书签列表 ──
                            if (vm.bookmarkList.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "暂无书签",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    items(vm.bookmarkList) { bm ->
                                        val showDelete = bookmarkDeleteVisible.contains(bm.path)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(56.dp)
                                                .combinedClickable(
                                                    onClick = {
                                                        if (showDelete) {
                                                            bookmarkDeleteVisible = bookmarkDeleteVisible - bm.path
                                                        } else {
                                                            vm.navigateToBookmark(bm)
                                                            showHistoryPanel = false
                                                        }
                                                    },
                                                    onLongClick = {
                                                        bookmarkDeleteVisible = if (showDelete) bookmarkDeleteVisible - bm.path
                                                        else bookmarkDeleteVisible + bm.path
                                                    }
                                                )
                                                .padding(horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Folder,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = bm.name,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    text = bm.path,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (showDelete) {
                                                IconButton(
                                                    onClick = {
                                                        vm.bookmarkList = vm.bookmarkList.filter { it.path != bm.path }
                                                        bookmarkDeleteVisible = bookmarkDeleteVisible - bm.path
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = "删除书签",
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        } // AnimatedContent
                        } // Box swipe
                    }
                }
            }

            // ── 左侧功能菜单抽屉 ──
            if (showDrawer) {
                BackHandler { showDrawer = false }
            }
            AnimatedVisibility(
                visible = showDrawer,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // 右侧 30% 点击关闭
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { showDrawer = false }
                            )
                    )
                    // 左侧菜单面板 70%
                    val drawerPrefs = context.getSharedPreferences(AppDataPaths.PREFS_FILE_MANAGER, Context.MODE_PRIVATE)
                    var localExpanded by remember {
                        mutableStateOf(drawerPrefs.getBoolean("drawer_local_expanded", true))
                    }
                    var toolsExpanded by remember {
                        mutableStateOf(drawerPrefs.getBoolean("drawer_tools_expanded", false))
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.7f),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 8.dp
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // 标题栏
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shadowElevation = 4.dp,
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "功能菜单",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    IconButton(onClick = { showDrawer = false }) {
                                        Icon(Icons.Default.Close, contentDescription = "关闭")
                                    }
                                }
                            }
                            // 菜单内容
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                // ── 本地 ──
                                DrawerSectionHeader(
                                    title = "本地",
                                    expandable = true,
                                    expanded = localExpanded,
                                    onClick = {
                                        localExpanded = !localExpanded
                                        drawerPrefs.edit().putBoolean("drawer_local_expanded", localExpanded).apply()
                                    }
                                )
                                AnimatedVisibility(visible = localExpanded) {
                                Column {
                                // 内部储存空间卡片（手机总存储）
                                run {
                                    val stat = try { StatFs(Environment.getDataDirectory().path) } catch (_: Exception) { null }
                                    if (stat != null) {
                                        val total = stat.totalBytes
                                        val available = stat.availableBytes
                                        val used = total - available
                                        val progress = if (total > 0) used.toFloat() / total.toFloat() else 0f
                                        val barColor = if (isSystemInDarkTheme()) Color(0xFF00838F) else Color(0xFF00BCD4)
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth(0.85f)
                                                    .padding(vertical = 6.dp)
                                                    .clickable {
                                                        vm.navigateTo("/storage/emulated/0/")
                                                        showDrawer = false
                                                    },
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                )
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                                ) {
                                                    Text(
                                                        "内部储存",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    Spacer(Modifier.height(6.dp))
                                                    LinearProgressIndicator(
                                                        progress = { progress },
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(6.dp)
                                                            .clip(RoundedCornerShape(3.dp)),
                                                        color = barColor,
                                                        trackColor = barColor.copy(alpha = 0.2f),
                                                    )
                                                    Spacer(Modifier.height(4.dp))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(
                                                            "${compactSize(used)} / ${compactSize(total)}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                        Text(
                                                            "%.1f%%".format(progress * 100),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                // 根目录空间卡片
                                run {
                                    val stat = try { StatFs("/") } catch (_: Exception) { null }
                                    if (stat != null) {
                                        val total = stat.totalBytes
                                        val available = stat.availableBytes
                                        val used = total - available
                                        val progress = if (total > 0) used.toFloat() / total.toFloat() else 0f
                                        val barColor = if (isSystemInDarkTheme()) Color(0xFF00838F) else Color(0xFF00BCD4)
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth(0.85f)
                                                    .padding(vertical = 6.dp)
                                                    .clickable {
                                                        vm.navigateTo("/")
                                                        showDrawer = false
                                                    },
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                )
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                                ) {
                                                    Text(
                                                        "根目录",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    Spacer(Modifier.height(6.dp))
                                                    LinearProgressIndicator(
                                                        progress = { progress },
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(6.dp)
                                                            .clip(RoundedCornerShape(3.dp)),
                                                        color = barColor,
                                                        trackColor = barColor.copy(alpha = 0.2f),
                                                    )
                                                    Spacer(Modifier.height(4.dp))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(
                                                            "${compactSize(used)} / ${compactSize(total)}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                        Text(
                                                            "%.1f%%".format(progress * 100),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                // 自定义快捷访问
                                quickAccessList.forEach { entry ->
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth(0.85f)
                                                .padding(vertical = 6.dp)
                                                .clickable {
                                                    vm.navigateTo(entry.path)
                                                    showDrawer = false
                                                },
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.SubdirectoryArrowRight,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    entry.name,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                        }
                                    }
                                }
                                // 添加快捷访问按钮
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showAddQaDialog = true }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "添加快捷访问",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "添加快捷访问",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                } // AnimatedVisibility
                                } // Column
                                HorizontalDivider()
                                // ── 工具 ──
                                DrawerSectionHeader(
                                    title = "工具",
                                    expandable = true,
                                    expanded = toolsExpanded,
                                    onClick = {
                                        toolsExpanded = !toolsExpanded
                                        drawerPrefs.edit().putBoolean("drawer_tools_expanded", toolsExpanded).apply()
                                    }
                                )
                                AnimatedVisibility(visible = toolsExpanded) {
                                    Column {
                                        DrawerMenuItem(
                                            icon = Icons.Default.Delete,
                                            label = "回收站",
                                            onClick = {
                                                vm.enterRecycleBin()
                                                showDrawer = false
                                            }
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            // ── 长按工具栏悬浮窗（带淡入淡出） ──
            AnimatedVisibility(
                visible = selectedEntry != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val isToRight = vm.focusedPanel == FocusedPanel.LEFT

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { selectedEntry = null }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .wrapContentHeight(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight()
                        ) {
                            if (vm.isInRecycleBin) {
                                // ── 回收站模式：永久删除 / 恢复到原位置 ──
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 左列：永久删除
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                val entry = selectedEntry ?: return@clickable
                                                permanentDeleteTarget = entry.name
                                                showPermanentDeleteDialog = true
                                                selectedEntry = null
                                            }
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.DeleteForever,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "永久删除",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    VerticalDivider(
                                        modifier = Modifier.height(24.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                                    )
                                    // 右列：恢复到原位置
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                val entry = selectedEntry ?: return@clickable
                                                val error = vm.restoreFromRecycleBin(entry.name)
                                                if (error == null) {
                                                    Toast.makeText(context, "已恢复到原位置", Toast.LENGTH_SHORT).show()
                                                    vm.enterRecycleBin() // 刷新回收站列表
                                                } else {
                                                    Toast.makeText(context, "恢复失败: $error", Toast.LENGTH_SHORT).show()
                                                }
                                                selectedEntry = null
                                            }
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Restore,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("恢复到原位置", style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            } else if (vm.isInArchive) {
                                // ── 压缩包模式：仅显示"关于" ──
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                val entry = selectedEntry ?: return@clickable
                                                propertyData = vm.getPropertyData(entry)
                                                propertyEntry = entry
                                                showPropertyDialog = true
                                                selectedEntry = null
                                            }
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Info,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("关于", style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            } else {
                            // ── 第一行：复制 / 移动 ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 左列：复制
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            val entry = selectedEntry ?: return@clickable
                                            val destDir = if (isToRight) vm.rightPath else vm.leftPath
                                            val error = vm.copyEntry(entry, destDir)
                                            if (error == null) {
                                                Toast.makeText(context, "复制成功", Toast.LENGTH_SHORT).show()
                                                vm.refreshBoth()
                                            } else {
                                                Toast.makeText(context, "复制失败: $error", Toast.LENGTH_SHORT).show()
                                            }
                                            selectedEntry = null
                                        }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isToRight) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("复制", style = MaterialTheme.typography.bodyLarge)
                                            Icon(
                                                Icons.Default.ArrowForward,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.ArrowBack,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text("复制", style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                                // 极淡的分割线
                                VerticalDivider(
                                    modifier = Modifier.height(24.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                                )
                                // 右列：移动
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            val entry = selectedEntry ?: return@clickable
                                            val destDir = if (isToRight) vm.rightPath else vm.leftPath
                                            val error = vm.moveEntry(entry, destDir)
                                            if (error == null) {
                                                Toast.makeText(context, "移动成功", Toast.LENGTH_SHORT).show()
                                                vm.refreshBoth()
                                            } else {
                                                Toast.makeText(context, "移动失败: $error", Toast.LENGTH_SHORT).show()
                                            }
                                            selectedEntry = null
                                        }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isToRight) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("移动", style = MaterialTheme.typography.bodyLarge)
                                            Icon(
                                                Icons.Default.ArrowForward,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.ArrowBack,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text("移动", style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            }
                            // ── 第二行：重命名 / 删除 ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 左列：重命名
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            val entry = selectedEntry ?: return@clickable
                                            renameText = entry.name
                                            showRenameDialog = true
                                        }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isToRight) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("重命名", style = MaterialTheme.typography.bodyLarge)
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text("重命名", style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                                // 极淡的分割线
                                VerticalDivider(
                                    modifier = Modifier.height(24.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                                )
                                // 右列：删除
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            selectedEntry?.let { showDeleteDialog = true }
                                        }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isToRight) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("删除", style = MaterialTheme.typography.bodyLarge)
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text("删除", style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            }
                            // ── 第三行：大小刷新(文件夹)或属性 / 分享 ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 左列：大小刷新（仅文件夹）或属性
                                if (selectedEntry?.isDirectory == true) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .combinedClickable(
                                                onClick = {
                                                    val target = selectedEntry?.path
                                                    selectedEntry = null
                                                    if (target != null) vm.calculateFolderSizeAsync(target)
                                                },
                                                onLongClick = {
                                                    showSizeCalcOptionsMenu = true
                                                }
                                            )
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isToRight) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("大小刷新", style = MaterialTheme.typography.bodyLarge)
                                                Icon(
                                                    Icons.Default.FolderSpecial,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Default.FolderSpecial,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text("大小刷新", style = MaterialTheme.typography.bodyLarge)
                                            }
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                val entry = selectedEntry ?: return@clickable
                                                propertyData = vm.getPropertyData(entry)
                                                propertyEntry = entry
                                                showPropertyDialog = true
                                                selectedEntry = null
                                            }
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isToRight) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("属性", style = MaterialTheme.typography.bodyLarge)
                                                Icon(
                                                    Icons.Default.Info,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Default.Info,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text("属性", style = MaterialTheme.typography.bodyLarge)
                                            }
                                        }
                                    }
                                }
                                // 极淡的分割线
                                VerticalDivider(
                                    modifier = Modifier.height(24.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                                )
                                // 右列：压缩
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            compressEntry = selectedEntry
                                            showCompressDialog = true
                                            selectedEntry = null
                                        }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isToRight) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("压缩", style = MaterialTheme.typography.bodyLarge)
                                            Icon(
                                                Icons.Default.FolderZip,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.FolderZip,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text("压缩", style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            }
                            // ── 第四行：关于 / 分享 ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 左列：关于
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            val entry = selectedEntry ?: return@clickable
                                            propertyData = vm.getPropertyData(entry)
                                            propertyEntry = entry
                                            showPropertyDialog = true
                                            selectedEntry = null
                                        }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isToRight) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("关于", style = MaterialTheme.typography.bodyLarge)
                                            Icon(
                                                Icons.Default.Info,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Info,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text("关于", style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                                VerticalDivider(
                                    modifier = Modifier.height(24.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                                )
                                // 右列：分享
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            Toast.makeText(context, "分享", Toast.LENGTH_SHORT).show()
                                            selectedEntry = null
                                        }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isToRight) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("分享", style = MaterialTheme.typography.bodyLarge)
                                            Icon(
                                                Icons.Default.Share,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Share,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text("分享", style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            }
                            } // else (非回收站模式)
                        }
                    }
                }
            }
        }
    }

    ErrorDialog(error = vm.loadError, onDismiss = {
        DiagnosticLog.log("FileMgr", "关闭错误对话框")
        vm.loadError = null
    })

    // ── 外部打开警告对话框 ──
    vm.pendingExternalEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { vm.pendingExternalEntry = null },
            title = { Text("无法打开文件") },
            text = { Text("该文件「${entry.name}」可能无法使用外部应用打开，是否强行打开？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.pendingExternalEntry = null
                    val error = vm.forceOpenExternalFile(context, entry)
                    if (error != null) forceOpenError = error
                }) {
                    Text("强行打开")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.pendingExternalEntry = null }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 强行打开失败详情 ──
    if (forceOpenError != null) {
        AlertDialog(
            onDismissRequest = { forceOpenError = null },
            title = { Text("打开失败", color = MaterialTheme.colorScheme.error) },
            text = {
                val scrollState = rememberScrollState()
                Box(modifier = Modifier.heightIn(max = 240.dp).verticalScroll(scrollState)) {
                    Text(
                        text = forceOpenError!!,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                Row {
                    OutlinedButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("Error Info", forceOpenError))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("复制")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { forceOpenError = null }) {
                        Text("关闭")
                    }
                }
            }
        )
    }

    // ── 新建类型选择对话框 ──
    if (showCreateTypeDialog) {
        AlertDialog(
            onDismissRequest = { showCreateTypeDialog = false },
            title = { Text("新建") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            createMode = CreateMode.FILE
                            showCreateTypeDialog = false
                            createName = ""
                            showNameDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("创建文件")
                    }
                    TextButton(
                        onClick = {
                            createMode = CreateMode.FOLDER
                            showCreateTypeDialog = false
                            createName = ""
                            showNameDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("创建文件夹")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCreateTypeDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 名称输入对话框 ──
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = {
                showNameDialog = false
                createName = ""
            },
            title = { Text(if (createMode == CreateMode.FILE) "创建文件" else "创建文件夹") },
            text = {
                OutlinedTextField(
                    value = createName,
                    onValueChange = { createName = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = createName.trim()
                        if (name.isBlank()) return@TextButton
                        val currentPath = vm.currentPath
                        val isFolder = createMode == CreateMode.FOLDER
                        val error = vm.createEntry(currentPath, name, isFolder)
                        if (error == null) {
                            Toast.makeText(context, "创建成功", Toast.LENGTH_SHORT).show()
                            vm.refreshCurrent()
                        } else {
                            Toast.makeText(context, "创建失败: $error", Toast.LENGTH_SHORT).show()
                        }
                        showNameDialog = false
                        createName = ""
                    },
                    enabled = createName.isNotBlank()
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNameDialog = false
                    createName = ""
                }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 重命名对话框 ──
    if (showRenameDialog && selectedEntry != null) {
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
                renameText = ""
            },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            modifier = Modifier.heightIn(max = 200.dp),
            confirmButton = {
                TextButton(onClick = {
                    val entry = selectedEntry ?: return@TextButton
                    val newName = renameText.trim()
                    if (newName.isBlank() || newName == entry.name) {
                        showRenameDialog = false
                        renameText = ""
                        return@TextButton
                    }
                    val error = vm.renameEntry(entry, newName)
                    if (error == null) {
                        Toast.makeText(context, "重命名成功", Toast.LENGTH_SHORT).show()
                        vm.refreshBoth()
                    } else {
                        Toast.makeText(context, "重命名失败: $error", Toast.LENGTH_SHORT).show()
                    }
                    showRenameDialog = false
                    renameText = ""
                    selectedEntry = null
                }) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    renameText = ""
                }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 删除确认对话框 ──
    if (showDeleteDialog && selectedEntry != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除") },
            text = {
                Column {
                    Text("确定要删除「${selectedEntry!!.name}」吗？")
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { recycleBinEnabled = !recycleBinEnabled }
                            .padding(start = 4.dp)
                    ) {
                        Checkbox(
                            checked = recycleBinEnabled,
                            onCheckedChange = { recycleBinEnabled = it }
                        )
                        Text("移动到回收站")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val entry = selectedEntry ?: return@TextButton
                    if (recycleBinEnabled) {
                        val error = vm.moveToRecycleBin(entry)
                        if (error == null) {
                            Toast.makeText(context, "已移至回收站", Toast.LENGTH_SHORT).show()
                            vm.refreshBoth()
                        } else {
                            // 移动失败，询问是否永久删除
                            forceDeleteEntry = entry
                            showForceDeleteDialog = true
                        }
                    } else {
                        val error = vm.deleteEntry(entry)
                        if (error == null) {
                            Toast.makeText(context, "删除成功", Toast.LENGTH_SHORT).show()
                            vm.refreshBoth()
                        } else {
                            Toast.makeText(context, "删除失败: $error", Toast.LENGTH_SHORT).show()
                        }
                    }
                    showDeleteDialog = false
                    selectedEntry = null
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 强制删除确认对话框（移动到回收站失败时） ──
    if (showForceDeleteDialog && forceDeleteEntry != null) {
        AlertDialog(
            onDismissRequest = { showForceDeleteDialog = false; forceDeleteEntry = null },
            title = { Text("删除") },
            text = { Text("无法移动到回收站，是否永久删除？") },
            confirmButton = {
                TextButton(onClick = {
                    val entry = forceDeleteEntry ?: return@TextButton
                    val error = vm.deleteEntry(entry)
                    if (error == null) {
                        Toast.makeText(context, "删除成功", Toast.LENGTH_SHORT).show()
                        vm.refreshBoth()
                    } else {
                        Toast.makeText(context, "删除失败: $error", Toast.LENGTH_SHORT).show()
                    }
                    showForceDeleteDialog = false
                    forceDeleteEntry = null
                }) {
                    Text("是")
                }
            },
            dismissButton = {
                TextButton(onClick = { showForceDeleteDialog = false; forceDeleteEntry = null }) {
                    Text("否")
                }
            }
        )
    }

    // ── 回收站永久删除确认对话框 ──
    if (showPermanentDeleteDialog && permanentDeleteTarget != null) {
        AlertDialog(
            onDismissRequest = { showPermanentDeleteDialog = false; permanentDeleteTarget = null },
            title = { Text("永久删除") },
            text = { Text("确定要永久删除「${permanentDeleteTarget}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val name = permanentDeleteTarget ?: return@TextButton
                    val error = vm.permanentDelete(name)
                    if (error == null) {
                        Toast.makeText(context, "已永久删除", Toast.LENGTH_SHORT).show()
                        vm.enterRecycleBin() // 刷新回收站列表
                    } else {
                        Toast.makeText(context, "删除失败: $error", Toast.LENGTH_SHORT).show()
                    }
                    showPermanentDeleteDialog = false
                    permanentDeleteTarget = null
                    selectedEntry = null
                }) {
                    Text("确定", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermanentDeleteDialog = false; permanentDeleteTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 添加快捷访问对话框 ──
    if (showAddQaDialog) {
        // 路径规范化：支持完整绝对路径和相对内部储存的路径
        // /storage/emulated/0/DCIM → 绝对路径，原样使用
        // /DCIM → 相对内部储存，补全为 /storage/emulated/0/DCIM
        // DCIM → 同上
        fun normalizeQaPath(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            val full = when {
                trimmed.startsWith("/storage/emulated/") ||
                trimmed.startsWith("/data/") ||
                trimmed.startsWith("/sdcard/") -> trimmed
                trimmed.startsWith("/") -> "/storage/emulated/0$trimmed"
                else -> "/storage/emulated/0/${trimmed.trimStart('/')}"
            }
            return if (full.endsWith("/") || full.endsWith("\\")) full.dropLast(1) else full
        }

        val name = qaNameInput.trim()
        val isDuplicate = name.isNotEmpty() && quickAccessList.any { it.name == name }
        val normalizedPath = normalizeQaPath(qaPathInput)
        val pathInvalid = qaPathInput.trim().isNotEmpty() &&
            normalizedPath.isNotEmpty() &&
            !vm.isDirectoryShell(normalizedPath)

        AlertDialog(
            onDismissRequest = {
                showAddQaDialog = false
                qaNameInput = ""; qaPathInput = ""
            },
            title = { Text("添加快捷访问") },
            text = {
                Column {
                    OutlinedTextField(
                        value = qaNameInput,
                        onValueChange = {
                            qaNameInput = it
                        },
                        label = { Text("命名") },
                        singleLine = true,
                        isError = isDuplicate,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isDuplicate) {
                        Text(
                            "该名称已存在",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = qaPathInput,
                        onValueChange = {
                            qaPathInput = it
                        },
                        label = { Text("绝对路径") },
                        singleLine = true,
                        isError = pathInvalid,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pathInvalid) {
                        Text(
                            "当前文件夹路径似乎无效",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val finalPath = normalizeQaPath(qaPathInput)
                        if (name.isNotEmpty() && finalPath.isNotEmpty() && !isDuplicate && !pathInvalid) {
                            quickAccessList = quickAccessList + QuickAccessEntry(name, finalPath)
                            saveQuickAccess()
                            showAddQaDialog = false
                            qaNameInput = ""; qaPathInput = ""
                        } else if (name.isEmpty() || finalPath.isEmpty()) {
                            Toast.makeText(context, "请填写完整", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = name.isNotEmpty() && qaPathInput.trim().isNotEmpty() && !isDuplicate && !pathInvalid
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddQaDialog = false
                    qaNameInput = ""; qaPathInput = ""
                }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 大小统计长按选项 ──
    if (showSizeCalcOptionsMenu && selectedEntry?.isDirectory == true) {
        val targetPath = selectedEntry!!.path
        val targetName = selectedEntry!!.name
        AlertDialog(
            onDismissRequest = { showSizeCalcOptionsMenu = false },
            title = { Text("大小统计选项") },
            text = {
                Column {
                    Text("对「$targetName」的大小统计操作")
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = {
                        showSizeCalcOptionsMenu = false
                        selectedEntry = null
                        vm.deleteSizeCacheAndRefresh(targetPath)
                    }) {
                        Text("删除缓存并刷新", modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = {
                        showSizeCalcOptionsMenu = false
                        selectedEntry = null
                        vm.recalculateFolderSizeForce(targetPath)
                    }) {
                        Text("忽略缓存重新统计", modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSizeCalcOptionsMenu = false }) { Text("取消") }
            }
        )
    }

    // ── 属性弹窗 ──
    if (showPropertyDialog && propertyData != null) {
        val data = propertyData!!
        Dialog(onDismissRequest = { showPropertyDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.8f),
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
                    Text(
                        text = "属性",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )

                    PropertyRow("名称", data.name)
                    PropertyRow("目录", data.directory)
                    PropertyRow("类型", data.type)
                    PropertyRow("大小", data.sizeDisplay)
                    PropertyRow("修改时间", data.modifiedTime)

                    // 权限信息容器
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PropertyRowWithButton("权限", data.permission, hasRoot = vm.isRootEngine, onClick = {
                                permissionEditorData = data
                                permissionEditorEntry = propertyEntry
                                showPropertyDialog = false
                                showPermissionEditor = true
                            })
                            PropertyRow("所有者", data.owner)
                            PropertyRow("用户组", data.group)
                        }
                    }

                    if (data.isDirectory) {
                        PropertyRow("文件数", data.fileCount.toString())
                        PropertyRow("文件夹数", data.folderCount.toString())
                    }

                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = { /* TODO */ },
                            enabled = false
                        ) {
                            Text("更多", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        TextButton(onClick = { showPropertyDialog = false }) {
                            Text("关闭")
                        }
                    }
                }
            }
        }
    }

    // ── 权限编辑弹窗 ──
    if (showPermissionEditor && permissionEditorData != null && permissionEditorEntry != null) {
        val entry = permissionEditorEntry!!
        val stat = try { android.system.Os.stat(entry.path) } catch (_: Exception) { null }
        val originalMode = stat?.st_mode ?: 0
        val originalUid = stat?.st_uid ?: 0
        val originalGid = stat?.st_gid ?: 0

        // 当前权限位（9个checkbox状态）
        var ownerRead by remember { mutableStateOf(originalMode and 0b100_000_000 != 0) }
        var ownerWrite by remember { mutableStateOf(originalMode and 0b010_000_000 != 0) }
        var ownerExec by remember { mutableStateOf(originalMode and 0b001_000_000 != 0) }
        var groupRead by remember { mutableStateOf(originalMode and 0b000_100_000 != 0) }
        var groupWrite by remember { mutableStateOf(originalMode and 0b000_010_000 != 0) }
        var groupExec by remember { mutableStateOf(originalMode and 0b000_001_000 != 0) }
        var otherRead by remember { mutableStateOf(originalMode and 0b000_000_100 != 0) }
        var otherWrite by remember { mutableStateOf(originalMode and 0b000_000_010 != 0) }
        var otherExec by remember { mutableStateOf(originalMode and 0b000_000_001 != 0) }

        // 当前选中的 UID/GID
        var selectedUid by remember { mutableStateOf(originalUid) }
        var selectedGid by remember { mutableStateOf(originalGid) }
        var selectedUserName by remember { mutableStateOf(originalUid.toString()) }
        var selectedGroupName by remember { mutableStateOf(originalGid.toString()) }

        // 用户/组选择弹窗
        var showUserPicker by remember { mutableStateOf(false) }
        var showGroupPicker by remember { mutableStateOf(false) }

        // 应用状态
        var applying by remember { mutableStateOf(false) }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        // 计算当前权限数字和符号
        val currentMode = (if (ownerRead) 0b100_000_000 else 0) or
                (if (ownerWrite) 0b010_000_000 else 0) or
                (if (ownerExec) 0b001_000_000 else 0) or
                (if (groupRead) 0b000_100_000 else 0) or
                (if (groupWrite) 0b000_010_000 else 0) or
                (if (groupExec) 0b000_001_000 else 0) or
                (if (otherRead) 0b000_000_100 else 0) or
                (if (otherWrite) 0b000_000_010 else 0) or
                (if (otherExec) 0b000_000_001 else 0)
        val octalStr = String.format("%o", currentMode)
        val symbolStr = buildString {
            append(if (ownerRead) 'r' else '-')
            append(if (ownerWrite) 'w' else '-')
            append(if (ownerExec) 'x' else '-')
            append(if (groupRead) 'r' else '-')
            append(if (groupWrite) 'w' else '-')
            append(if (groupExec) 'x' else '-')
            append(if (otherRead) 'r' else '-')
            append(if (otherWrite) 'w' else '-')
            append(if (otherExec) 'x' else '-')
        }

        // 初始化用户名/组名
        LaunchedEffect(Unit) {
            val users = vm.getSystemUsers()
            val groups = vm.getSystemGroups()
            selectedUserName = users.find { it.uid == originalUid }?.username ?: originalUid.toString()
            selectedGroupName = groups.find { it.gid == originalGid }?.groupname ?: originalGid.toString()
        }

        Dialog(onDismissRequest = { /* 禁止点击外部关闭 */ }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Card(
                modifier = Modifier.fillMaxWidth(0.8f),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("权限编辑", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(entry.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    // ── 权限网格 ──
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // 表头
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Spacer(modifier = Modifier.width(80.dp))
                                listOf("所有者", "用户组", "其他").forEach { header ->
                                    Text(
                                        text = header,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            // 三行：读、写、执行
                            data class PermRow(val label: String, val ownerGet: () -> Boolean, val ownerSet: (Boolean) -> Unit, val groupGet: () -> Boolean, val groupSet: (Boolean) -> Unit, val otherGet: () -> Boolean, val otherSet: (Boolean) -> Unit)
                            val rows = listOf(
                                PermRow("读", { ownerRead }, { ownerRead = it }, { groupRead }, { groupRead = it }, { otherRead }, { otherRead = it }),
                                PermRow("写", { ownerWrite }, { ownerWrite = it }, { groupWrite }, { groupWrite = it }, { otherWrite }, { otherWrite = it }),
                                PermRow("执行", { ownerExec }, { ownerExec = it }, { groupExec }, { groupExec = it }, { otherExec }, { otherExec = it })
                            )
                            rows.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = row.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.width(80.dp)
                                    )
                                    listOf(
                                        row.ownerGet to row.ownerSet,
                                        row.groupGet to row.groupSet,
                                        row.otherGet to row.otherSet
                                    ).forEach { (get, set) ->
                                        Box(
                                            modifier = Modifier.weight(1f),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Checkbox(
                                                checked = get(),
                                                onCheckedChange = { set(it) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── 数字和符号显示 ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = symbolStr,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "($octalStr)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    // ── 所有者选择 ──
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { showUserPicker = true },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("所有者", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(60.dp))
                                Text(selectedUserName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text("($originalUid)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(thickness = 0.3.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { showGroupPicker = true },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("用户组", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(60.dp))
                                Text(selectedGroupName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text("($originalGid)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // 错误信息
                    if (errorMsg != null) {
                        Text(errorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    // ── 按钮 ──
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            showPermissionEditor = false
                            showPropertyDialog = true
                        }) {
                            Text("取消")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                applying = true
                                errorMsg = null
                                val result = vm.applyPermissions(
                                    path = entry.path,
                                    mode = currentMode,
                                    uid = selectedUid,
                                    gid = selectedGid,
                                    originalMode = originalMode,
                                    originalUid = originalUid,
                                    originalGid = originalGid
                                )
                                applying = false
                                if (result != null) {
                                    errorMsg = result
                                } else {
                                    showPermissionEditor = false
                                    // 刷新文件列表
                                    vm.refreshCurrent()
                                }
                            },
                            enabled = !applying
                        ) {
                            Text(if (applying) "应用中..." else "确认")
                        }
                    }
                }
            }
        }

        // ── 用户选择弹窗 ──
        if (showUserPicker) {
            val users = remember { vm.getSystemUsers() }
            Dialog(onDismissRequest = { showUserPicker = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.6f),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("选择所有者", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(users) { user ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedUid = user.uid
                                            selectedUserName = user.username
                                            showUserPicker = false
                                        }
                                        .padding(vertical = 10.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(user.username, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text("(${user.uid})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    RadioButton(
                                        selected = selectedUid == user.uid,
                                        onClick = {
                                            selectedUid = user.uid
                                            selectedUserName = user.username
                                            showUserPicker = false
                                        }
                                    )
                                }
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showUserPicker = false }) { Text("取消") }
                        }
                    }
                }
            }
        }

        // ── 组选择弹窗 ──
        if (showGroupPicker) {
            val groups = remember { vm.getSystemGroups() }
            Dialog(onDismissRequest = { showGroupPicker = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.6f),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("选择用户组", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(groups) { group ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedGid = group.gid
                                            selectedGroupName = group.groupname
                                            showGroupPicker = false
                                        }
                                        .padding(vertical = 10.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(group.groupname, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text("(${group.gid})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    RadioButton(
                                        selected = selectedGid == group.gid,
                                        onClick = {
                                            selectedGid = group.gid
                                            selectedGroupName = group.groupname
                                            showGroupPicker = false
                                        }
                                    )
                                }
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showGroupPicker = false }) { Text("取消") }
                        }
                    }
                }
            }
        }
    }

    // ── 压缩弹窗面板 ──
    if (showCompressDialog && compressEntry != null) {
        val entry = compressEntry!!
        val isDark = isSystemInDarkTheme()
        val formats = listOf("zip", "7z", "tar", "tar.gz", "tar.bz2", "tar.xz", "jxl")
        // 格式显示名（带标注）
        val formatLabels = mapOf(
            "jxl" to "jxl (图片)"
        )
        val suffixMap = mapOf(
            "zip" to ".zip", "7z" to ".7z", "tar" to ".tar",
            "tar.gz" to ".tar.gz", "tar.bz2" to ".tar.bz2", "tar.xz" to ".tar.xz",
            "jxl" to ".jxl"
        )

        var selectedFormat by remember { mutableStateOf("zip") }
        var compressLevel by remember { mutableStateOf(5) }
        var compressPassword by remember { mutableStateOf("") }
        var passwordVisible by remember { mutableStateOf(false) }
        var showFormatDropdown by remember { mutableStateOf(false) }
        var showLevelDropdown by remember { mutableStateOf(false) }
        var fileName by remember { mutableStateOf(entry.name + ".zip") }

        // 各格式实际支持的压缩等级范围
        val levelRange = when (selectedFormat) {
            "zip" -> 0..9
            "7z" -> 0..9
            "tar" -> null
            "tar.gz" -> 0..9
            "tar.bz2" -> 1..9   // BZip2 blockSize 参数，最小为 1
            "tar.xz" -> 0..9
            "jxl" -> 1..10      // JPEG XL Effort（1=最快，10=最大压缩比）
            else -> 0..9
        }
        val defaultLevel = when (selectedFormat) {
            "jxl" -> 7  // SQUIRREL
            else -> 5
        }

        // 格式切换时更新后缀和压缩级别
        LaunchedEffect(selectedFormat) {
            val baseName = entry.name.substringBeforeLast(".")
            fileName = if (selectedFormat == "jxl") {
                baseName + if (vm.jxlPackZip) ".jxl.zip" else ".jxl"
            } else {
                baseName + (suffixMap[selectedFormat] ?: ".zip")
            }
            compressLevel = defaultLevel
        }

        // JXL 打包开关切换时更新后缀
        LaunchedEffect(vm.jxlPackZip) {
            if (selectedFormat == "jxl") {
                val baseName = entry.name.substringBeforeLast(".")
                fileName = baseName + if (vm.jxlPackZip) ".jxl.zip" else ".jxl"
            }
        }

        Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isDark) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 标题
                        Text(
                            text = "创建压缩文件",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // 文件名区域
                        Column {
                            Text(
                                text = "文件名",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextField(
                                value = fileName,
                                onValueChange = { fileName = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                                )
                            )
                        }

                        // 并排双选项栏：格式 + 压缩级别
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 左列：格式
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "格式",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Box {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showFormatDropdown = true }
                                            .background(
                                                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(formatLabels[selectedFormat] ?: selectedFormat, style = MaterialTheme.typography.bodyLarge)
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = showFormatDropdown,
                                        onDismissRequest = { showFormatDropdown = false }
                                    ) {
                                        formats.forEach { format ->
                                            DropdownMenuItem(
                                                text = { Text(formatLabels[format] ?: format) },
                                                onClick = {
                                                    selectedFormat = format
                                                    showFormatDropdown = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // 右列：压缩级别（tar 时隐藏）
                            if (levelRange != null) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "压缩级别 (${levelRange.first}-${levelRange.last})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Box {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { showLevelDropdown = true }
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(compressLevel.toString(), style = MaterialTheme.typography.bodyLarge)
                                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                        DropdownMenu(
                                            expanded = showLevelDropdown,
                                            onDismissRequest = { showLevelDropdown = false }
                                        ) {
                                            levelRange.forEach { level ->
                                                DropdownMenuItem(
                                                    text = { Text(level.toString()) },
                                                    onClick = {
                                                        compressLevel = level
                                                        showLevelDropdown = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // JXL 打包成 ZIP 开关
                        if (selectedFormat == "jxl") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "打包成 ZIP 压缩包",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Switch(
                                    checked = vm.jxlPackZip,
                                    onCheckedChange = { vm.updateJxlPackZip(it) },
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        // 密码输入栏（JXL 不打包时不支持加密）
                        if (selectedFormat != "jxl" || vm.jxlPackZip) Column {
                            Text(
                                text = "密码（不加密请留空）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextField(
                                value = compressPassword,
                                onValueChange = { compressPassword = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                                ),
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }, modifier = Modifier.size(24.dp)) {
                                        Icon(
                                            if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            )
                        }

                        // 输出路径开关
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "其余基于定义窗口路径",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Switch(
                                checked = compressOutputToOtherPanel,
                                onCheckedChange = { compressOutputToOtherPanel = it },
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        // ZIP加密方式（仅zip格式时显示）
                        if (selectedFormat == "zip") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "加密方式",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = { compressUseAes = false },
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            "ZipCrypto",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (!compressUseAes) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                            textDecoration = if (!compressUseAes) TextDecoration.Underline else null
                                        )
                                    }
                                    TextButton(
                                        onClick = { compressUseAes = true },
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            "AES",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (compressUseAes) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                            textDecoration = if (compressUseAes) TextDecoration.Underline else null
                                        )
                                    }
                                }
                            }
                        }

                        // 底部按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                showCompressDialog = false
                                compressEntry = null
                            }) {
                                Text("取消", color = MaterialTheme.colorScheme.primary)
                            }
                            TextButton(onClick = {
                                showCompressDialog = false
                                compressCurrentFile = 0
                                compressTotalFiles = 0
                                compressProgress = 0f
                                compressOutputPath = ""
                                showCompressProgress = true

                                vm.compress(
                                    entry = entry,
                                    fileName = fileName,
                                    format = selectedFormat,
                                    level = compressLevel,
                                    password = compressPassword,
                                    useAes = compressUseAes,
                                    outputToOtherPanel = compressOutputToOtherPanel,
                                    jxlPackZip = vm.jxlPackZip,
                                    onProgress = { current, total, progress ->
                                        compressCurrentFile = current
                                        compressTotalFiles = total
                                        compressProgress = progress
                                    },
                                    onComplete = { success, outPath, error ->
                                        compressOutputPath = outPath ?: ""
                                        showCompressProgress = false
                                        if (success) {
                                            Toast.makeText(context, "压缩完成: ${outPath?.substringAfterLast('/')}", Toast.LENGTH_SHORT).show()
                                        } else if (error != "已取消") {
                                            Toast.makeText(context, "压缩失败: $error", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }) {
                                Text("确定", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 压缩进度条面板 ──
    if (showCompressProgress) {
        val isDark = isSystemInDarkTheme()
        Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isDark) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.8f),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 标题
                        Text(
                            text = "正在压缩...",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // 第一行：压缩进度 + 文件数
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "压缩进度",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$compressCurrentFile/$compressTotalFiles",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // 第二行：进度条 + 百分比
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { compressProgress },
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${(compressProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // 取消按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                // 取消压缩任务
                                vm.compressCancelFlag.set(true)
                                // 删除残余压缩包
                                if (compressOutputPath.isNotEmpty()) {
                                    File(compressOutputPath).delete()
                                }
                                showCompressProgress = false
                                compressProgress = 0f
                                compressCurrentFile = 0
                                compressTotalFiles = 0
                                compressOutputPath = ""
                            }) {
                                Text("取消", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 压缩包密码输入对话框 ──
    if (showArchivePasswordDialog && archivePendingEntry != null) {
        var passwordVisible by remember { mutableStateOf(false) }
        var verifying by remember { mutableStateOf(false) }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = {
                showArchivePasswordDialog = false
                archivePendingEntry = null
                archivePasswordInput = ""
                errorMsg = null
            },
            title = { Text("请输入密码") },
            text = {
                Column {
                    Text(
                        text = "「${archivePendingEntry!!.name}」已加密",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = archivePasswordInput,
                        onValueChange = { archivePasswordInput = it; errorMsg = null },
                        singleLine = true,
                        label = { Text("密码") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                    if (errorMsg != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(errorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (archivePasswordInput.isEmpty()) {
                            errorMsg = "请输入密码"
                            return@TextButton
                        }
                        verifying = true
                        errorMsg = null
                        val entry = archivePendingEntry!!
                        val password = archivePasswordInput
                        coroutineScope.launch(Dispatchers.IO) {
                            val error = vm.openArchive(entry, password)
                            withContext(Dispatchers.Main) {
                                verifying = false
                                if (error != null) {
                                    errorMsg = error
                                } else {
                                    showArchivePasswordDialog = false
                                    archivePendingEntry = null
                                    archivePasswordInput = ""
                                }
                            }
                        }
                    },
                    enabled = !verifying && archivePasswordInput.isNotEmpty()
                ) {
                    Text(if (verifying) "验证中..." else "确认")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showArchivePasswordDialog = false
                    archivePendingEntry = null
                    archivePasswordInput = ""
                    errorMsg = null
                }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 压缩包正在打开进度面板 ──
    if (showArchiveOpening) {
        val isDark = isSystemInDarkTheme()
        Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isDark) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.8f),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "正在打开...",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    // ── 排序对话框 ──
    if (showSortDialog) {
        val fieldLabels = mapOf(
            SortField.NAME to "名称",
            SortField.SIZE to "大小",
            SortField.MODIFIED to "最后修改时间",
            SortField.CREATED to "创建时间"
        )

        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("排列顺序") },
            text = {
                Column {
                    // 排序字段列表（始终显示全部4项，当前选中的高亮）
                    Text(
                        text = "排序方式",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    for (field in SortField.entries) {
                        val isSelected = tempSortField == field
                        Surface(
                            onClick = { tempSortField = field },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = fieldLabels[field] ?: "",
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

                    Spacer(modifier = Modifier.height(16.dp))

                    // 正序/逆序选择
                    Text(
                        text = "排序方向",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val ascLabel = when (tempSortField) {
                            SortField.NAME -> "A到Z"
                            SortField.SIZE -> "小到大"
                            SortField.MODIFIED -> "最早到最近"
                            SortField.CREATED -> "最早到最近"
                        }
                        val descLabel = when (tempSortField) {
                            SortField.NAME -> "Z到A"
                            SortField.SIZE -> "大到小"
                            SortField.MODIFIED -> "最近到最早"
                            SortField.CREATED -> "最近到最早"
                        }

                        FilterChip(
                            selected = tempSortOrder == SortOrder.ASC,
                            onClick = { tempSortOrder = SortOrder.ASC },
                            label = { Text(ascLabel) }
                        )
                        FilterChip(
                            selected = tempSortOrder == SortOrder.DESC,
                            onClick = { tempSortOrder = SortOrder.DESC },
                            label = { Text(descLabel) }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (tempSortField == SortField.SIZE) {
                        // 压缩包模式：文件夹大小已从索引获取，无需统计
                        if (vm.isInArchive) {
                            vm.updateSortField(tempSortField)
                            vm.updateSortOrder(tempSortOrder)
                            showSortDialog = false
                        } else {
                            // 检查是否有未统计大小的文件夹
                            val currentEntries = if (vm.focusedPanel == FocusedPanel.LEFT) vm.leftEntries else vm.rightEntries
                            val unmeasured = currentEntries.filter { entry ->
                                if (!entry.isDirectory) return@filter false
                                val cached = vm.folderSizeDb.get(entry.path)
                                if (cached != null) return@filter false // 已统计
                                // 检查是否空文件夹或权限不足（受保护路径走 shell）
                                val children = vm.listChildrenOrNull(entry.path)
                                if (children == null || children.isEmpty()) return@filter false
                                true
                            }
                            if (unmeasured.isNotEmpty()) {
                                unmeasuredDirs = unmeasured
                                showSortDialog = false
                                showSortSizeRefreshDialog = true
                            } else {
                                vm.updateSortField(tempSortField)
                                vm.updateSortOrder(tempSortOrder)
                                showSortDialog = false
                            }
                        }
                    } else {
                        vm.updateSortField(tempSortField)
                        vm.updateSortOrder(tempSortOrder)
                        showSortDialog = false
                    }
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSortDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 排序前刷新大小确认对话框 ──
    if (showSortSizeRefreshDialog) {
        AlertDialog(
            onDismissRequest = { showSortSizeRefreshDialog = false },
            title = { Text("统计大小") },
            text = { Text("当前列表有 ${unmeasuredDirs.size} 个文件夹尚未统计大小，是否先统计再排序？") },
            confirmButton = {
                TextButton(onClick = {
                    showSortSizeRefreshDialog = false
                    val parentPath = if (vm.focusedPanel == FocusedPanel.LEFT) vm.leftPath else vm.rightPath
                    vm.calculateFolderSizeAsync(parentPath)
                    // 统计完成后由 refreshCurrent 触发列表刷新，但排序字段需在此立即应用
                    vm.updateSortField(tempSortField)
                    vm.updateSortOrder(tempSortOrder)
                }) {
                    Text("是")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSortSizeRefreshDialog = false
                    // 直接应用排序，不统计
                    vm.updateSortField(tempSortField)
                    vm.updateSortOrder(tempSortOrder)
                }) {
                    Text("否，直接排序")
                }
            }
        )
    }
}

@Composable
private fun FileBrowserPanel(
    entries: List<FileEntry>,
    isFocused: Boolean,
    onFocus: () -> Unit,
    onFolderClick: (FileEntry) -> Unit,
    onFileClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
    folderSizeDb: FolderSizeDb = FolderSizeDb(),
    parentPath: String? = null,
    onNavigateUp: () -> Unit = {},
    lazyListState: LazyListState = rememberLazyListState(),
    archiveSizeProvider: ((FileEntry) -> String)? = null
) {
    val context = LocalContext.current
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onFocus)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            onFocus()
                        }
                    }
                }
            },
        shadowElevation = if (isFocused) 6.dp else 0.dp
    ) {
        if (entries.isEmpty() && parentPath == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "空目录",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                // 独立的"返回上一级"条目，不走 onFolderClick，不记录历史
                if (parentPath != null) {
                    item(key = "__parent__") {
                        FileEntryRow(
                            entry = FileEntry(parentPath, "返回上一级", true),
                            isFocused = isFocused,
                            onClick = onNavigateUp,
                            onLongClick = {},
                            folderSize = ""
                        )
                    }
                }
                items(entries, key = { it.path }) { entry ->
                    val dirSize = if (entry.isDirectory) {
                        if (archiveSizeProvider != null) archiveSizeProvider(entry)
                        else {
                            val cached = folderSizeDb.get(entry.path)
                            if (cached != null) {
                                if (cached.size == 0L) "0MB"
                                else compactSize(cached.size)
                            } else ""
                        }
                    } else if (archiveSizeProvider != null) {
                        archiveSizeProvider(entry)
                    } else ""
                    FileEntryRow(
                        entry = entry,
                        isFocused = isFocused,
                        onClick = {
                            if (entry.isDirectory) onFolderClick(entry)
                            else onFileClick(entry)
                        },
                        onLongClick = { onLongClick(entry) },
                        folderSize = dirSize
                    )
                }
            }
        }
    }
}

@Composable
private fun FileEntryRow(
    entry: FileEntry,
    isFocused: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    folderSize: String = ""
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(65.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 2.5.dp)
    ) {
        // Top 7/10: icon (left 1/5) + filename (right 4/5)
        Row(modifier = Modifier.weight(7f)) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                val ext = extractExtension(entry.name)
                val category = categorizeFile(ext)
                val isImageFile = category == FileCategory.IMAGE && !entry.isDirectory
                    && entry.name != "返回上一级"

                if (isImageFile) {
                    val imagePlaceholder = getFileTypeDrawableRes(category)
                    AsyncImage(
                        model = entry.path,
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop,
                        placeholder = imagePlaceholder?.let { painterResource(it) },
                        error = imagePlaceholder?.let { painterResource(it) }
                    )
                } else {
                    val fileDrawableRes = if (!entry.isDirectory && entry.name != "返回上一级") {
                        getFileTypeDrawableRes(category)
                    } else null

                    if (fileDrawableRes != null) {
                        Icon(
                            painter = painterResource(fileDrawableRes),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = Color.Unspecified
                        )
                    } else if (!entry.isDirectory && entry.name != "返回上一级"
                        && category == FileCategory.APK) {
                        FileTypeIcon(
                            filename = entry.name,
                            filePath = entry.path,
                            iconSize = 28.dp
                        )
                    } else {
                        Icon(
                            imageVector = when {
                                entry.name == "返回上一级" -> Icons.Default.ArrowUpward
                                entry.isDirectory -> Icons.Default.Folder
                                else -> Icons.Default.InsertDriveFile
                            },
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = if (isFocused) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Box(
                modifier = Modifier.weight(4f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = entry.name,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = true,
                    textAlign = TextAlign.Start,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        // Bottom 3/10: date/permission (left, aligned to icon left) + size (right, aligned to filename right)
        Row(
            modifier = Modifier.weight(3f).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            val label = when {
                entry.isDirectory -> compactDate(entry.lastModified)
                entry.permission.isNotEmpty() -> entry.permission
                else -> ""
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val rightLabel = when {
                entry.isDirectory -> folderSize.ifEmpty { "--" }
                !entry.isDirectory -> compactSize(entry.size)
                else -> ""
            }
            if (rightLabel.isNotEmpty()) {
                Text(
                    text = rightLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (rightLabel == "✕") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatPermission(mode: Int): String {
    val type = when (mode and 0xF000) {
        0x4000 -> 'd'  // directory
        0x8000 -> '-'  // regular file
        0xA000 -> 'l'  // symlink
        0x6000 -> 'b'  // block device
        0x2000 -> 'c'  // char device
        0x1000 -> 'p'  // pipe
        0xC000 -> 's'  // socket
        else -> '?'
    }
    val rwx = charArrayOf('r', 'w', 'x')
    val sb = StringBuilder(10)
    sb.append(type)
    for (shift in 8 downTo 0) {
        sb.append(if ((mode shr shift) and 1 != 0) rwx[2 - shift % 3] else '-')
    }
    return sb.toString()
}

private fun compactSize(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes == 0L) return "0 B"
    val v = bytes.toDouble()
    return when {
        v < 1024 -> "%.0f B".format(v)
        v < 1024 * 1024 -> "%.1f K".format(v / 1024)
        v < 1024 * 1024 * 1024 -> "%.1f M".format(v / (1024 * 1024))
        else -> "%.1f G".format(v / (1024 * 1024 * 1024))
    }
}

private fun compactDate(millis: Long): String {
    if (millis <= 0) return ""
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}

@Composable
private fun StartEllipsisText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = Color.Unspecified
) {
    var displayText by remember(text) { mutableStateOf(text) }

    Text(
        text = displayText,
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        style = style,
        color = color,
        onTextLayout = { result ->
            if (result.hasVisualOverflow) {
                val visible = result.getLineEnd(0)
                if (visible > 0 && visible <= text.length) {
                    displayText = "…${text.takeLast(visible - 1)}"
                }
            } else if (displayText != text) {
                displayText = text
            }
        }
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            softWrap = true
        )
    }
}

@Composable
private fun PropertyRowWithButton(label: String, value: String, hasRoot: Boolean = false, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = onClick,
            enabled = hasRoot
        ) {
            Text(
                "更改",
                color = if (hasRoot) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        }
    }
}

@Composable
private fun DrawerSectionHeader(
    title: String,
    expandable: Boolean = false,
    expanded: Boolean = false,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (expandable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        if (expandable) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DrawerMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
