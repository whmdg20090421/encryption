package com.whmdg.mczj.tools.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import com.whmdg.mczj.tools.util.DiagnosticLog
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

    // ── UI 本地状态 ──
    var showSettingsMenu by remember { mutableStateOf(false) }
    var sortMenuLevel by remember { mutableStateOf(0) }
    var expandedSortField by remember { mutableStateOf<SortField?>(null) }
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
    var selectedEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var recycleBinEnabled by remember { mutableStateOf(false) }
    var showHistoryPanel by remember { mutableStateOf(false) }
    var panelTab by remember { mutableStateOf(0) } // 0=历史, 1=书签
    var bookmarkDeleteVisible by remember { mutableStateOf(setOf<String>()) }

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

    LaunchedEffect(Unit) {
        DiagnosticLog.beginSession("进入 FileManagerScreen")
        DiagnosticLog.log("FileMgr", "FileManagerScreen 启动 isRootEngine=${vm.isRootEngine} hasStoragePerm=$hasStoragePermission")
        if (!hasStoragePermission) {
            Toast.makeText(context, "需要存储权限才能浏览文件", Toast.LENGTH_LONG).show()
        }
    }

    // 返回手势：子目录 → 回上一级，根目录 → 退出文件管理器
    BackHandler {
        if (!vm.goUp()) {
            onBack()
        }
    }

    val currentPath = vm.currentPath

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    StartEllipsisText(
                        text = currentPath,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Home, contentDescription = "返回主页")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSettingsMenu = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                        DropdownMenu(
                            expanded = showSettingsMenu,
                            onDismissRequest = { showSettingsMenu = false; sortMenuLevel = 0; expandedSortField = null }
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
                            // 排序级联菜单
                            Box {
                                DropdownMenuItem(
                                    text = { Text("排列顺序") },
                                    trailingIcon = {
                                        Icon(Icons.Default.ArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = { expandedSortField = expandedSortField ?: vm.sortField }
                                )
                                // 二级菜单：四个排序字段
                                DropdownMenu(
                                    expanded = expandedSortField != null,
                                    onDismissRequest = { expandedSortField = null }
                                ) {
                                    for (field in SortField.entries) {
                                        val fieldLabel = when (field) {
                                            SortField.NAME -> "名称"
                                            SortField.SIZE -> "大小"
                                            SortField.MODIFIED -> "最后修改时间"
                                            SortField.CREATED -> "创建时间"
                                        }
                                        Box {
                                            DropdownMenuItem(
                                                text = { Text(fieldLabel) },
                                                trailingIcon = {
                                                    if (vm.sortField == field) {
                                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                                    }
                                                    Icon(Icons.Default.ArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                                                },
                                                onClick = { expandedSortField = field }
                                            )
                                            // 三级菜单：升序/降序
                                            DropdownMenu(
                                                expanded = expandedSortField == field,
                                                onDismissRequest = { expandedSortField = null }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(sortAscLabels[field]!!) },
                                                    trailingIcon = {
                                                        if (vm.sortField == field && vm.sortOrder == SortOrder.ASC) {
                                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                                        }
                                                    },
                                                    onClick = {
                                                        vm.updateSortField(field)
                                                        vm.updateSortOrder(SortOrder.ASC)
                                                        showSettingsMenu = false; expandedSortField = null
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(sortDescLabels[field]!!) },
                                                    trailingIcon = {
                                                        if (vm.sortField == field && vm.sortOrder == SortOrder.DESC) {
                                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                                        }
                                                    },
                                                    onClick = {
                                                        vm.updateSortField(field)
                                                        vm.updateSortOrder(SortOrder.DESC)
                                                        showSettingsMenu = false; expandedSortField = null
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
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
                        }
                    }
                }
            )
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
                            onClick = { vm.goBack() },
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
                            onClick = { vm.goForward() },
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
                        val effectiveRoot = if (vm.isRootEngine) "/" else "/storage/emulated/0"
                        val parentPath = vm.currentPath.substringBeforeLast('/').ifEmpty { "/" }
                        val canGoUp = vm.currentPath != effectiveRoot
                            && vm.currentPath.contains('/')
                            && parentPath != vm.currentPath
                            && try { File(parentPath).canRead() } catch (_: Exception) { false }

                        IconButton(
                            onClick = { vm.goUp() },
                            enabled = canGoUp
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "返回上一级")
                        }
                    }
                }
                // 下方 20dp：系统手势区域（白色，从底部上滑退出应用）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .drawBehind { drawRect(MaterialTheme.colorScheme.surface) }
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
                    val leftParentPath = if (vm.leftPath != leftEffectiveRoot && vm.leftPath.contains('/')) {
                        vm.leftPath.substringBeforeLast('/').ifEmpty { "/" }.let { p ->
                            if (p != vm.leftPath && try { java.io.File(p).canRead() } catch (_: Exception) { false }) p else null
                        }
                    } else null

                    val rightEffectiveRoot = if (vm.isRootEngine) "/" else "/storage/emulated/0"
                    val rightParentPath = if (vm.rightPath != rightEffectiveRoot && vm.rightPath.contains('/')) {
                        vm.rightPath.substringBeforeLast('/').ifEmpty { "/" }.let { p ->
                            if (p != vm.rightPath && try { java.io.File(p).canRead() } catch (_: Exception) { false }) p else null
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
                                    DiagnosticLog.beginSession("[LEFT] 点击文件夹 '${entry.name}'")
                                    DiagnosticLog.log("FileMgr", "[LEFT] 点击文件夹 name='${entry.name}' path='${entry.path}' from=${vm.leftPath}")
                                    vm.focusedPanel = FocusedPanel.LEFT
                                    vm.navigateToFolder(entry)
                                },
                                onFileClick = { entry ->
                                    DiagnosticLog.beginSession("[LEFT] 点击文件 '${entry.name}'")
                                    DiagnosticLog.log("FileMgr", "[LEFT] 点击文件 name='${entry.name}' path='${entry.path}'")
                                    vm.focusedPanel = FocusedPanel.LEFT
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
                                    if (leftParentPath != null) {
                                        vm.focusedPanel = FocusedPanel.LEFT
                                        vm.navigateUp(leftParentPath)
                                    }
                                }
                            )
                            FileBrowserPanel(
                                entries = vm.rightEntries,
                                isFocused = !leftFocused,
                                onFocus = { vm.focusedPanel = FocusedPanel.RIGHT },
                                onFolderClick = { entry ->
                                    DiagnosticLog.beginSession("[RIGHT] 点击文件夹 '${entry.name}'")
                                    DiagnosticLog.log("FileMgr", "[RIGHT] 点击文件夹 name='${entry.name}' path='${entry.path}' from=${vm.rightPath}")
                                    vm.focusedPanel = FocusedPanel.RIGHT
                                    vm.navigateToFolder(entry)
                                },
                                onFileClick = { entry ->
                                    DiagnosticLog.beginSession("[RIGHT] 点击文件 '${entry.name}'")
                                    DiagnosticLog.log("FileMgr", "[RIGHT] 点击文件 name='${entry.name}' path='${entry.path}'")
                                    vm.focusedPanel = FocusedPanel.RIGHT
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
                                    if (rightParentPath != null) {
                                        vm.focusedPanel = FocusedPanel.RIGHT
                                        vm.navigateUp(rightParentPath)
                                    }
                                }
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
                    // 上半部分：点击或右滑手势收起面板
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.4f)
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
                                                        val screen = vm.openFile(context, FileEntry(entry.path, entry.name, false))
                                                        if (screen != null) onNavigate(screen)
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
                            .width(220.dp)
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
                                            val source = File(entry.path)
                                            val destDir = File(
                                                if (isToRight) vm.rightPath else vm.leftPath
                                            )
                                            val dest = File(destDir, entry.name)
                                            try {
                                                if (source.isDirectory) {
                                                    source.copyRecursively(dest, overwrite = false)
                                                } else {
                                                    source.copyTo(dest, overwrite = false)
                                                }
                                                Toast.makeText(context, "复制成功", Toast.LENGTH_SHORT).show()
                                                vm.refreshBoth()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
                                            val source = File(entry.path)
                                            val destDir = File(
                                                if (isToRight) vm.rightPath else vm.leftPath
                                            )
                                            val dest = File(destDir, entry.name)
                                            try {
                                                val moved = source.renameTo(dest)
                                                if (!moved) {
                                                    if (source.isDirectory) {
                                                        source.copyRecursively(dest, overwrite = false)
                                                        SpecialPermissionVerifier.safeDelete(source)
                                                    } else {
                                                        source.copyTo(dest, overwrite = false)
                                                        SpecialPermissionVerifier.safeDelete(source)
                                                    }
                                                }
                                                Toast.makeText(context, "移动成功", Toast.LENGTH_SHORT).show()
                                                vm.refreshBoth()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "移动失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
                                            .clickable {
                                                val entry = selectedEntry ?: return@clickable
                                                selectedEntry = null
                                                Toast.makeText(context, "正在计算大小...", Toast.LENGTH_SHORT).show()
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val updatedDb = vm.refreshFolderSize(entry.path)
                                                    val sizeInfo = updatedDb.get(entry.path)
                                                    val sizeText = if (sizeInfo != null && sizeInfo.size > 0) compactSize(sizeInfo.size) else "0"
                                                    withContext(Dispatchers.Main) {
                                                        vm.applyFolderSizeDb(updatedDb)
                                                        Toast.makeText(context, "大小计算完毕: $sizeText", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
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
                                                Toast.makeText(context, "属性", Toast.LENGTH_SHORT).show()
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
                        val target = File(currentPath, name)
                        if (target.exists()) {
                            Toast.makeText(context, "已存在同名文件或文件夹", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        val success = try {
                            if (createMode == CreateMode.FOLDER) target.mkdir()
                            else target.createNewFile()
                        } catch (e: Exception) {
                            DiagnosticLog.log("FileMgr", "创建失败: ${e.message}")
                            Toast.makeText(context, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            null
                        }
                        if (success == true) {
                            Toast.makeText(context, "创建成功", Toast.LENGTH_SHORT).show()
                            vm.refreshCurrent()
                        } else if (success == false) {
                            Toast.makeText(context, "创建失败", Toast.LENGTH_SHORT).show()
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
                    val source = File(entry.path)
                    val parent = source.parentFile ?: return@TextButton
                    val dest = File(parent, newName)
                    if (dest.exists()) {
                        Toast.makeText(context, "已存在同名文件或文件夹", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    try {
                        val success = source.renameTo(dest)
                        if (success) {
                            Toast.makeText(context, "重命名成功", Toast.LENGTH_SHORT).show()
                            vm.refreshBoth()
                        } else {
                            Toast.makeText(context, "重命名失败", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "重命名失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Checkbox(
                            checked = recycleBinEnabled,
                            onCheckedChange = null,
                            enabled = false
                        )
                        Text(
                            "移动到回收站",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val entry = selectedEntry ?: return@TextButton
                    val file = File(entry.path)
                    try {
                        SpecialPermissionVerifier.safeDelete(file)
                        Toast.makeText(context, "删除成功", Toast.LENGTH_SHORT).show()
                        vm.refreshBoth()
                    } catch (e: Exception) {
                        Toast.makeText(context, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
    lazyListState: LazyListState = rememberLazyListState()
) {
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
                        val cached = folderSizeDb.get(entry.path)
                        if (cached != null) {
                            if (cached.size == 0L) {
                                val dir = File(entry.path)
                                val children = try { dir.listFiles() } catch (_: Exception) { null }
                                if (children == null) "✕" else "0MB"
                            } else {
                                compactSize(cached.size)
                            }
                        } else {
                            val dir = File(entry.path)
                            val children = try { dir.listFiles() } catch (_: Exception) { null }
                            if (children == null) "✕" else ""
                        }
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

                // 图片文件：显示缩略图
                val thumbnailBitmap = remember(entry.path, isImageFile) {
                    if (!isImageFile) return@remember null
                    try {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(entry.path, opts)
                        opts.inSampleSize = maxOf(
                            opts.outWidth / 48, opts.outHeight / 48, 1
                        ).coerceAtLeast(1)
                        opts.inJustDecodeBounds = false
                        BitmapFactory.decodeFile(entry.path, opts)?.asImageBitmap()
                    } catch (_: Exception) { null }
                }

                if (thumbnailBitmap != null) {
                    Image(
                        painter = BitmapPainter(thumbnailBitmap),
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(4.dp))
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
                entry.size > 0 -> compactSize(entry.size)
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
    if (bytes <= 0) return ""
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
