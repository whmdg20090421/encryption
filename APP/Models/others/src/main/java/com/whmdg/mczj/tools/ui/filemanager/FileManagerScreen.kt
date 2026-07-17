package com.whmdg.mczj.tools.ui.filemanager

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.util.DiagnosticLog

import com.whmdg.mczj.tools.util.CompressService
import com.whmdg.mczj.tools.util.FormatUtils
import com.whmdg.mczj.tools.util.AppIconHelper
import com.whmdg.mczj.tools.util.ArchiveBrowser
import com.whmdg.mczj.tools.ui.ErrorDialog
import com.whmdg.mczj.tools.ui.FileEntry
import com.whmdg.mczj.tools.ui.Screen
import com.whmdg.mczj.tools.ui.encryption.EncryptionSettings
import com.whmdg.mczj.tools.ui.isDebugAuth
import com.whmdg.mczj.tools.util.FileAccessLevel
import com.whmdg.mczj.tools.auth.PasswordDialog

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.ShellExecutor
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.fileop.FileOperationManager
import com.whmdg.mczj.tools.fileop.DeleteEntry
import com.whmdg.mczj.tools.fileop.webdav.WebDavServerStore
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import coil3.request.ImageRequest
import coil3.size.Size as CoilSize
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
import com.whmdg.mczj.tools.ui.components.ApkInfoDialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.viewmodel.compose.viewModel

enum class FocusedPanel { LEFT, RIGHT }
enum class CreateMode { FILE, FOLDER }
enum class SortField { NAME, SIZE, MODIFIED, CREATED }
enum class SortOrder { ASC, DESC }

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
    val encSettings = remember { EncryptionSettings(context) }

    var hasStoragePermission by remember {
        mutableStateOf(Environment.isExternalStorageManager())
    }
    val coroutineScope = rememberCoroutineScope()

    // ── 诊断状态（Debug 模式） ──
    val isDebugMode = remember { isDebugAuth(context) }
    var diagnosticMessage by remember { mutableStateOf<String?>(null) }
    var diagnosticError by remember { mutableStateOf<Throwable?>(null) }

    // ── 滚动状态 ──
    val leftListState = rememberLazyListState()
    val rightListState = rememberLazyListState()

    // ── UI 本地状态 ──
    var showDrawer by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var tempSortField by remember { mutableStateOf(vm.sortField) }
    var tempSortOrder by remember { mutableStateOf(vm.sortOrder) }
    var showSortSizeRefreshDialog by remember { mutableStateOf(false) }
    var unmeasuredDirs by remember { mutableStateOf(listOf<FileEntry>()) }
    var selectedEntry by remember { mutableStateOf<FileEntry?>(null) }
    // ── 多选状态（左右列表独立） ──
    var leftSelectedPaths by remember { mutableStateOf(setOf<String>()) }
    var rightSelectedPaths by remember { mutableStateOf(setOf<String>()) }
    var leftSwipeSelectFlag by remember { mutableIntStateOf(0) }  // 1=刚滑动选中，等待范围选中
    var rightSwipeSelectFlag by remember { mutableIntStateOf(0) }
    var leftLastSwipeIndex by remember { mutableIntStateOf(-1) }
    var rightLastSwipeIndex by remember { mutableIntStateOf(-1) }
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
    var renameText by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var hideToolbarForDelete by remember { mutableStateOf(false) }
    var showDeleteProgress by remember { mutableStateOf(false) }
    // ── 复制/移动确认对话框 ──
    var showCopyMoveConfirmDialog by remember { mutableStateOf(false) }
    var copyMoveConfirmIsCopy by remember { mutableStateOf(true) }
    var copyMoveConfirmSourcePaths by remember { mutableStateOf(listOf<String>()) }
    var copyMoveConfirmTargetDir by remember { mutableStateOf("") }
    var showFileOpProgress by remember { mutableStateOf(false) }
    var cancelingFileOp by remember { mutableStateOf(false) }
    var recycleBinEnabled by remember { mutableStateOf(true) }
    var showForceDeleteDialog by remember { mutableStateOf(false) }
    var forceDeleteEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showPermanentDeleteDialog by remember { mutableStateOf(false) }
    var permanentDeleteTarget by remember { mutableStateOf<String?>(null) }
    var permanentDeleteMultiNames by remember { mutableStateOf<List<String>>(emptyList()) }
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
    // APK 信息弹窗
    var showApkDialog by remember { mutableStateOf(false) }
    var apkDialogPath by remember { mutableStateOf("") }
    // ── 压缩对话框 ──
    var showCompressDialog by remember { mutableStateOf(false) }
    var compressEntries by remember { mutableStateOf(listOf<FileEntry>()) }
    var showCompressProgress by remember { mutableStateOf(false) }
    var compressProgress by remember { mutableFloatStateOf(0f) }
    var compressCurrentFile by remember { mutableIntStateOf(0) }
    var compressTotalFiles by remember { mutableIntStateOf(0) }
    var compressBytesProcessed by remember { mutableStateOf(0L) }
    var compressTotalBytes by remember { mutableStateOf(0L) }
    var compressOutputPath by remember { mutableStateOf("") }
    var compressUseAes by remember { mutableStateOf(encSettings.compressUseAes) }
    var compressEncryptNames by remember { mutableStateOf(false) }
    var compressOutputToOtherPanel by remember { mutableStateOf(false) }
    var showCompressPasswordHint by remember { mutableStateOf(false) }
    var compressError by remember { mutableStateOf<Throwable?>(null) }

    // ── 解压对话框 ──
    var showExtractDialog by remember { mutableStateOf(false) }
    var extractTargetEntries by remember { mutableStateOf(listOf<FileEntry>()) }
    var extractOutputPath by remember { mutableStateOf("") }
    var showExtractProgress by remember { mutableStateOf(false) }
    var extractProgress by remember { mutableFloatStateOf(0f) }
    var extractCurrentFile by remember { mutableIntStateOf(0) }
    var extractTotalFiles by remember { mutableIntStateOf(0) }
    var extractBytesProcessed by remember { mutableStateOf(0L) }
    var extractTotalBytes by remember { mutableStateOf(0L) }
    var extractError by remember { mutableStateOf<Throwable?>(null) }
    var showExtractPasswordDialog by remember { mutableStateOf(false) }
    var extractPasswordInput by remember { mutableStateOf("") }
    var extractPasswordError by remember { mutableStateOf<String?>(null) }

    // ── 加密对话框 ──
    var showEncryptDialog by remember { mutableStateOf(false) }
    var encryptMode by remember { mutableIntStateOf(0) } // 0=打包加密, 1=分片加密

    // 移动/复制（功能待实现，保留 UI 占位）

    // 文件操作进度（从 ViewModel StateFlow 收集）
    val fileOpProgress by vm.fileOpProgress.collectAsState()

    // 文件操作管理器进度（新架构）
    val fileOpManagerProgress by FileOperationManager.progress.collectAsState()

    // ── 外部打开警告 ──
    var forceOpenError by remember { mutableStateOf<String?>(null) }

    // ── 快捷访问 ──
    val quickAccessPrefs = context.getSharedPreferences(AppDataPaths.PREFS_QUICK_ACCESS, Context.MODE_PRIVATE)
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

    // ── WebDAV 快捷访问 ──
    var showQaTypeSelector by remember { mutableStateOf(false) }
    var showWebDavEditDialog by remember { mutableStateOf(false) }
    var webDavServers by remember {
        mutableStateOf(WebDavServerStore.getAll(context))
    }

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
    LaunchedEffect(vm.pendingScrollToFile, vm.leftEntries, vm.rightEntries, vm.leftPath, vm.rightPath) {
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
        if (!isDebugMode && vm.isRootEngine) {
            try {
                val mntNs = ShellExecutor.execute(Permission.ROOT, "readlink /proc/self/ns/mnt")
                Toast.makeText(context, "root 已就位，当前挂载空间为：$mntNs", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(context, "挂载空间异常，请检查，有可能 root 权限不可用", Toast.LENGTH_LONG).show()
            }
        }
        // 注册文件操作完成后的刷新回调
        FileOperationManager.setRefreshCallback {
            vm.refreshBoth()
        }
    }

    // ── 处理滚动位置恢复（绑定跳转+渲染） ──
    LaunchedEffect(vm.pendingScrollTo) {
        val pending = vm.pendingScrollTo ?: return@LaunchedEffect
        val (path, index, offset) = pending
        val listState = if (vm.focusedPanel == FocusedPanel.LEFT) leftListState else rightListState
        listState.scrollToItem(index, offset)
        vm.pendingScrollTo = null
    }

    // 保存当前滚动位置并返回上一级
    val saveScrollAndGoUp: () -> Boolean = {
        vm.saveScrollPosition(
            leftListState.firstVisibleItemIndex,
            leftListState.firstVisibleItemScrollOffset,
            rightListState.firstVisibleItemIndex,
            rightListState.firstVisibleItemScrollOffset
        )
        val targetPath = vm.goUp()
        if (targetPath != null) {
            // 导航时清空当前面板的多选状态
            if (vm.focusedPanel == FocusedPanel.LEFT) {
                leftSelectedPaths = emptySet(); leftSwipeSelectFlag = 0; leftLastSwipeIndex = -1
            } else {
                rightSelectedPaths = emptySet(); rightSwipeSelectFlag = 0; rightLastSwipeIndex = -1
            }
            val saved = vm.getScrollPosition(targetPath)
            vm.navigateToWithScroll(targetPath, saved?.first ?: 0, saved?.second ?: 0)
            true
        } else {
            false
        }
    }

    // ── 弹窗栈式管理：每个弹窗用 registerOverlay 注册，BackHandler 只调栈顶 ──
    data class OverlayEntry(val id: String, val cleanup: () -> Unit)
    val overlayStack = remember { mutableStateListOf<OverlayEntry>() }

    /** 弹窗打开时调用：压入清理函数。弹窗关闭时自动移除。 */
    fun registerOverlay(id: String, cleanup: () -> Unit) {
        overlayStack.removeAll { it.id == id }
        overlayStack.add(OverlayEntry(id, cleanup))
    }
    fun unregisterOverlay(id: String) { overlayStack.removeAll { it.id == id } }

    // 各弹窗注册到 overlayStack（新增弹窗在此处加一组 DisposableEffect）
    DisposableEffect(selectedEntry != null || hideToolbarForDelete) {
        if (selectedEntry != null) registerOverlay("toolbar") { selectedEntry = null; hideToolbarForDelete = false }
        else unregisterOverlay("toolbar")
        onDispose {}
    }
    DisposableEffect(showDeleteDialog) {
        if (showDeleteDialog) registerOverlay("delete") { showDeleteDialog = false; hideToolbarForDelete = false }
        else unregisterOverlay("delete")
        onDispose {}
    }
    DisposableEffect(showDeleteProgress) {
        if (showDeleteProgress) registerOverlay("deleteProgress") { showDeleteProgress = false; selectedEntry = null; hideToolbarForDelete = false }
        else unregisterOverlay("deleteProgress")
        onDispose {}
    }
    DisposableEffect(showFileOpProgress) {
        if (showFileOpProgress) registerOverlay("fileOpProgress") { showFileOpProgress = false }
        else unregisterOverlay("fileOpProgress")
        onDispose {}
    }
    DisposableEffect(showForceDeleteDialog) {
        if (showForceDeleteDialog) registerOverlay("forceDelete") { showForceDeleteDialog = false; forceDeleteEntry = null }
        else unregisterOverlay("forceDelete")
        onDispose {}
    }
    DisposableEffect(showPermanentDeleteDialog) {
        if (showPermanentDeleteDialog) registerOverlay("permanentDelete") { showPermanentDeleteDialog = false; permanentDeleteTarget = null; permanentDeleteMultiNames = emptyList() }
        else unregisterOverlay("permanentDelete")
        onDispose {}
    }
    DisposableEffect(showRenameDialog) {
        if (showRenameDialog) registerOverlay("rename") { showRenameDialog = false }
        else unregisterOverlay("rename")
        onDispose {}
    }
    DisposableEffect(showPropertyDialog) {
        if (showPropertyDialog) registerOverlay("property") { showPropertyDialog = false; propertyEntry = null }
        else unregisterOverlay("property")
        onDispose {}
    }
    DisposableEffect(showPermissionEditor) {
        if (showPermissionEditor) registerOverlay("permission") { showPermissionEditor = false }
        else unregisterOverlay("permission")
        onDispose {}
    }
    DisposableEffect(showApkDialog) {
        if (showApkDialog) registerOverlay("apk") { showApkDialog = false }
        else unregisterOverlay("apk")
        onDispose {}
    }
    DisposableEffect(showCompressDialog) {
        if (showCompressDialog) registerOverlay("compress") { showCompressDialog = false; showCompressProgress = false }
        else unregisterOverlay("compress")
        onDispose {}
    }
    DisposableEffect(showExtractDialog) {
        if (showExtractDialog) registerOverlay("extract") { showExtractDialog = false; showExtractProgress = false }
        else unregisterOverlay("extract")
        onDispose {}
    }
    DisposableEffect(showExtractPasswordDialog) {
        if (showExtractPasswordDialog) registerOverlay("extractPwd") { showExtractPasswordDialog = false }
        else unregisterOverlay("extractPwd")
        onDispose {}
    }
    DisposableEffect(showAddQaDialog) {
        if (showAddQaDialog) registerOverlay("addQa") { showAddQaDialog = false }
        else unregisterOverlay("addQa")
        onDispose {}
    }
    DisposableEffect(showQaTypeSelector) {
        if (showQaTypeSelector) registerOverlay("qaType") { showQaTypeSelector = false }
        else unregisterOverlay("qaType")
        onDispose {}
    }
    DisposableEffect(showWebDavEditDialog) {
        if (showWebDavEditDialog) registerOverlay("webDavEdit") { showWebDavEditDialog = false }
        else unregisterOverlay("webDavEdit")
        onDispose {}
    }

    // 返回手势：栈顶弹窗 → 关闭，压缩包 → 回上一级或退出，WebDAV → 回上一级或退出，回收站 → 回上一级或退出，子目录 → 回上一级，根目录 → 退出
    BackHandler {
        if (overlayStack.isNotEmpty()) {
            val top = overlayStack.last()
            top.cleanup()
            unregisterOverlay(top.id)
            return@BackHandler
        }
        if (vm.isInArchiveMode) {
            if (!vm.archiveGoUp()) {
                vm.exitArchive()
            }
        } else if (vm.isWebDavMode) {
            if (!vm.webDavGoBack()) {
                vm.exitWebDavMode()
            }
        } else if (vm.recycleBinPanel == vm.focusedPanel) {
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
                            vm.isInArchiveMode -> vm.archiveSession?.let {
                                if (it.currentPath == it.archivePath) it.archiveName
                                else "${it.archiveName} / ${it.currentPath.removePrefix(it.archivePath).trimStart('/')}"
                            } ?: "压缩包"
                            vm.recycleBinPanel == vm.focusedPanel -> "回收站"
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
                val leftFocused = vm.focusedPanel == FocusedPanel.LEFT
                val activeSelectedPaths = if (leftFocused) leftSelectedPaths else rightSelectedPaths
                val isMultiSelectMode = activeSelectedPaths.isNotEmpty()
                // 旋转动画：+ 旋转45°变×
                val rotation by animateFloatAsState(
                    targetValue = if (isMultiSelectMode) 45f else 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMedium)
                )
                var rowWidthPx by remember { mutableIntStateOf(0) }
                Box(
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
                            rowWidthPx = coords.size.width
                            val pos = coords.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                            val rect = AndroidRect(
                                pos.x.toInt(),
                                pos.y.toInt(),
                                (pos.x + coords.size.width).toInt(),
                                (pos.y + coords.size.height).toInt()
                            )
                            view.systemGestureExclusionRects = listOf(rect)
                        }
                ) {
                    Crossfade(
                        targetState = isMultiSelectMode,
                        animationSpec = tween(durationMillis = 300),
                        label = "toolbar_switch"
                    ) { isMulti ->
                    if (!isMulti) {
                    // ── 普通模式 6 个按钮 ──
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 后退按钮
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = {
                                    if (vm.isInArchiveMode) {
                                        vm.archiveGoUp()
                                    } else if (vm.isWebDavMode) {
                                        vm.webDavGoBack()
                                    } else {
                                        val targetPath = vm.goBack()
                                        if (targetPath != null) {
                                            val saved = vm.getScrollPosition(targetPath)
                                            vm.pendingScrollTo = Triple(targetPath, saved?.first ?: 0, saved?.second ?: 0)
                                        }
                                    }
                                },
                                enabled = if (vm.isInArchiveMode) !vm.isAtArchiveRoot()
                                    else if (vm.isWebDavMode) vm.webDavCurrentPath != "/"
                                    else vm.currentNavState.canGoBack
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
                                    val targetPath = vm.goForward()
                                    if (targetPath != null) {
                                        val saved = vm.getScrollPosition(targetPath)
                                        vm.pendingScrollTo = Triple(targetPath, saved?.first ?: 0, saved?.second ?: 0)
                                    }
                                },
                                enabled = !vm.isInArchiveMode && vm.currentNavState.canGoForward
                            ) {
                                Icon(Icons.Default.ArrowForward, contentDescription = "前进")
                            }
                        }
                        // 新建按钮
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = { showCreateTypeDialog = true },
                                enabled = !vm.isInArchiveMode
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "新建")
                            }
                        }
                        // 同步按钮
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = { vm.syncPaths() },
                                enabled = !vm.isInArchiveMode
                            ) {
                                Icon(Icons.Default.SwapHoriz, contentDescription = "同步路径")
                            }
                        }
                        // 刷新按钮
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = { vm.refreshCurrent() },
                                enabled = !vm.isInArchiveMode
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新")
                            }
                        }
                        // 返回上一级按钮
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            val canGoUp = if (vm.isInArchiveMode) {
                                !vm.isAtArchiveRoot()
                            } else if (vm.recycleBinPanel == vm.focusedPanel) {
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
                                    if (vm.isInArchiveMode) vm.archiveGoUp()
                                    else if (vm.recycleBinPanel == vm.focusedPanel) vm.goUpInRecycleBin()
                                    else saveScrollAndGoUp()
                                },
                                enabled = canGoUp
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "返回上一级")
                            }
                        }
                    }
                    } else {
                    // ── 多选模式 5 个按钮 ──
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 第1个：选择全部（短按全选，长按按类型全选）
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .combinedClickable(
                                    onClick = {
                                        // 短按：全选所有项目
                                        val entries = if (leftFocused) vm.leftEntries else vm.rightEntries
                                        val allPaths = entries.map { it.path }.toSet()
                                        if (leftFocused) leftSelectedPaths = allPaths else rightSelectedPaths = allPaths
                                    },
                                    onLongClick = {
                                        // 长按：按已选类型全选
                                        val entries = if (leftFocused) vm.leftEntries else vm.rightEntries
                                        val currentPaths = if (leftFocused) leftSelectedPaths else rightSelectedPaths
                                        val hasFiles = entries.any { it.path in currentPaths && !it.isDirectory }
                                        val hasFolders = entries.any { it.path in currentPaths && it.isDirectory }
                                        val newPaths = when {
                                            // 只选了文件夹 → 全选所有文件夹
                                            hasFolders && !hasFiles -> entries.filter { it.isDirectory }.map { it.path }.toSet()
                                            // 只选了文件 → 全选所有文件
                                            hasFiles && !hasFolders -> entries.filter { !it.isDirectory }.map { it.path }.toSet()
                                            // 都有 → 全选所有
                                            else -> entries.map { it.path }.toSet()
                                        }
                                        if (leftFocused) leftSelectedPaths = newPaths else rightSelectedPaths = newPaths
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = "选择全部")
                        }
                        // 第2个：反选（短按清空，长按按类型反选）
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .combinedClickable(
                                    onClick = {
                                        // 短按：清空所有选中
                                        if (leftFocused) {
                                            leftSelectedPaths = emptySet(); leftSwipeSelectFlag = 0; leftLastSwipeIndex = -1
                                        } else {
                                            rightSelectedPaths = emptySet(); rightSwipeSelectFlag = 0; rightLastSwipeIndex = -1
                                        }
                                    },
                                    onLongClick = {
                                        // 长按：按已选类型反选
                                        val entries = if (leftFocused) vm.leftEntries else vm.rightEntries
                                        val currentPaths = if (leftFocused) leftSelectedPaths else rightSelectedPaths
                                        val hasFiles = entries.any { it.path in currentPaths && !it.isDirectory }
                                        val hasFolders = entries.any { it.path in currentPaths && it.isDirectory }
                                        val newPaths = when {
                                            // 只选了文件夹 → 反选文件夹（取消当前，选中其他文件夹）
                                            hasFolders && !hasFiles -> {
                                                val otherFolders = entries.filter { it.isDirectory && it.path !in currentPaths }.map { it.path }.toSet()
                                                val selectedFiles = currentPaths.filter { path -> entries.any { it.path == path && !it.isDirectory } }.toSet()
                                                otherFolders + selectedFiles
                                            }
                                            // 只选了文件 → 反选文件（取消当前，选中其他文件）
                                            hasFiles && !hasFolders -> {
                                                val otherFiles = entries.filter { !it.isDirectory && it.path !in currentPaths }.map { it.path }.toSet()
                                                val selectedFolders = currentPaths.filter { path -> entries.any { it.path == path && it.isDirectory } }.toSet()
                                                otherFiles + selectedFolders
                                            }
                                            // 都有 → 全选所有（等同于短按全选）
                                            else -> entries.map { it.path }.toSet()
                                        }
                                        if (leftFocused) leftSelectedPaths = newPaths else rightSelectedPaths = newPaths
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.FlipToBack, contentDescription = "反选")
                        }
                        // 第3个：取消多选（×）
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = {
                                if (leftFocused) {
                                    leftSelectedPaths = emptySet(); leftSwipeSelectFlag = 0; leftLastSwipeIndex = -1
                                } else {
                                    rightSelectedPaths = emptySet(); rightSwipeSelectFlag = 0; rightLastSwipeIndex = -1
                                }
                                selectedEntry = null // 同时隐藏工具栏
                            }) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "取消多选",
                                    modifier = Modifier.rotate(rotation)
                                )
                            }
                        }
                        // 第4个：选择相同后缀
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = {
                                val entries = if (leftFocused) vm.leftEntries else vm.rightEntries
                                val currentPaths = if (leftFocused) leftSelectedPaths else rightSelectedPaths
                                // 获取已选中文件的后缀（排除文件夹）
                                val selectedFiles = entries.filter { it.path in currentPaths && !it.isDirectory }
                                val extensions = selectedFiles
                                    .map { it.name.substringAfterLast('.', "").lowercase() }
                                    .filter { it.isNotEmpty() }
                                    .toSet()
                                if (extensions.isNotEmpty()) {
                                    // 选中所有包含相同后缀的文件
                                    val matchingPaths = entries
                                        .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in extensions }
                                        .map { it.path }
                                        .toSet()
                                    if (leftFocused) leftSelectedPaths = currentPaths + matchingPaths
                                    else rightSelectedPaths = currentPaths + matchingPaths
                                }
                            }) {
                                Icon(Icons.Default.FilterList, contentDescription = "选择相同后缀")
                            }
                        }
                        // 第5个：唤醒工具栏
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = {
                                // 触发与长按相同的逻辑：设置 selectedEntry
                                val currentPaths = if (leftFocused) leftSelectedPaths else rightSelectedPaths
                                val entries = if (leftFocused) vm.leftEntries else vm.rightEntries
                                val firstSelected = entries.firstOrNull { it.path in currentPaths }
                                if (firstSelected != null) selectedEntry = firstSelected
                            }) {
                                Icon(Icons.Default.TouchApp, contentDescription = "唤醒工具栏")
                            }
                        }
                    }
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
                    val leftParentPath = computeParentPath(
                        currentPath = vm.leftPath,
                        isInArchiveMode = vm.isInArchiveMode,
                        isAtArchiveRoot = { vm.isAtArchiveRoot() },
                        isRecycleBinPanel = vm.recycleBinPanel == FocusedPanel.LEFT,
                        isAtRecycleBinRoot = vm.isAtRecycleBinRoot,
                        recycleBinPath = vm.recycleBinPath,
                        isRootEngine = vm.isRootEngine,
                        canAccessPath = { vm.canAccessPath(it) }
                    )

                    val rightParentPath = computeParentPath(
                        currentPath = vm.rightPath,
                        isInArchiveMode = vm.isInArchiveMode,
                        isAtArchiveRoot = { vm.isAtArchiveRoot() },
                        isRecycleBinPanel = vm.recycleBinPanel == FocusedPanel.RIGHT,
                        isAtRecycleBinRoot = vm.isAtRecycleBinRoot,
                        recycleBinPath = vm.recycleBinPath,
                        isRootEngine = vm.isRootEngine,
                        canAccessPath = { vm.canAccessPath(it) }
                    )

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
                                    if (vm.isInArchiveMode) {
                                        vm.navigateInArchive(entry)
                                    } else if (vm.recycleBinPanel == vm.focusedPanel) {
                                        vm.navigateInRecycleBin(entry)
                                    } else {
                                        DiagnosticLog.beginSession("[LEFT] 点击文件夹 '${entry.name}'")
                                        DiagnosticLog.log("FileMgr", "[LEFT] 点击文件夹 name='${entry.name}' path='${entry.path}' from=${vm.leftPath}")
                                        vm.saveScrollPosition(
                                            leftListState.firstVisibleItemIndex,
                                            leftListState.firstVisibleItemScrollOffset,
                                            rightListState.firstVisibleItemIndex,
                                            rightListState.firstVisibleItemScrollOffset
                                        )
                                        if (vm.isWebDavMode) {
                                            vm.navigateToWebDavFolder(entry.name)
                                        } else {
                                            vm.navigateToFolder(entry)
                                        }
                                        leftSelectedPaths = emptySet(); leftSwipeSelectFlag = 0; leftLastSwipeIndex = -1
                                    }
                                },
                                onFileClick = { entry ->
                                    if (vm.isInArchiveMode) {
                                        DiagnosticLog.beginSession("[LEFT] 压缩包内点击文件 '${entry.name}'")
                                        DiagnosticLog.log("FileMgr", "[LEFT] 压缩包内文件 name='${entry.name}'")
                                        vm.focusedPanel = FocusedPanel.LEFT
                                        coroutineScope.launch {
                                            val screen = vm.openArchiveFile(context, entry)
                                            if (screen != null) {
                                                vm.saveScrollPosition(
                                                    leftListState.firstVisibleItemIndex,
                                                    leftListState.firstVisibleItemScrollOffset,
                                                    rightListState.firstVisibleItemIndex,
                                                    rightListState.firstVisibleItemScrollOffset
                                                )
                                                onNavigate(screen)
                                            } else {
                                                Toast.makeText(context, "文件提取失败", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        return@FileBrowserPanel
                                    }
                                    DiagnosticLog.beginSession("[LEFT] 点击文件 '${entry.name}'")
                                    DiagnosticLog.log("FileMgr", "[LEFT] 点击文件 name='${entry.name}' path='${entry.path}'")
                                    vm.focusedPanel = FocusedPanel.LEFT
                                    val screen = vm.openFile(context, entry, isDebugMode)
                                    if (screen != null) {
                                        vm.saveScrollPosition(
                                            leftListState.firstVisibleItemIndex,
                                            leftListState.firstVisibleItemScrollOffset,
                                            rightListState.firstVisibleItemIndex,
                                            rightListState.firstVisibleItemScrollOffset
                                        )
                                        onNavigate(screen)
                                    }
                                    vm.addHistory(entry.name, entry.path, false)
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
                                    if (vm.isInArchiveMode) {
                                        vm.focusedPanel = FocusedPanel.LEFT
                                        vm.archiveGoUp()
                                    } else if (vm.recycleBinPanel == vm.focusedPanel) {
                                        vm.focusedPanel = FocusedPanel.LEFT
                                        vm.goUpInRecycleBin()
                                    } else if (leftParentPath != null) {
                                        vm.focusedPanel = FocusedPanel.LEFT
                                        vm.saveScrollPosition(
                                            leftListState.firstVisibleItemIndex,
                                            leftListState.firstVisibleItemScrollOffset,
                                            rightListState.firstVisibleItemIndex,
                                            rightListState.firstVisibleItemScrollOffset
                                        )
                                        val saved = vm.getScrollPosition(leftParentPath)
                                        vm.navigateToWithScroll(leftParentPath, saved?.first ?: 0, saved?.second ?: 0)
                                        leftSelectedPaths = emptySet(); leftSwipeSelectFlag = 0; leftLastSwipeIndex = -1
                                    }
                                },
                                archiveSizeProvider = if (vm.isInArchiveMode) { entry ->
                                    if (entry.compressedSize > 0 || entry.size > 0)
                                        "${compactSize(entry.compressedSize)}(${compactSize(entry.size)})"
                                    else "--"
                                } else null,
                                onVisibleRangeChanged = null,
                                thumbnailLoader = null,
                                selectedPaths = leftSelectedPaths,
                                onSwipeSelect = { entry, index ->
                                    vm.focusedPanel = FocusedPanel.LEFT
                                    // 右滑已选中的唯一卡片 → 取消选中，退出多选
                                    if (leftSelectedPaths.size == 1 && entry.path in leftSelectedPaths) {
                                        leftSelectedPaths = emptySet(); leftSwipeSelectFlag = 0; leftLastSwipeIndex = -1
                                    } else if (leftSelectedPaths.isEmpty()) {
                                        // 首次滑动：进入多选模式
                                        leftSelectedPaths = setOf(entry.path)
                                        leftSwipeSelectFlag = 1
                                        leftLastSwipeIndex = index
                                    } else if (leftSwipeSelectFlag == 1) {
                                        // 范围选中：选中两次滑动之间的所有条目
                                        val from = minOf(leftLastSwipeIndex, index)
                                        val to = maxOf(leftLastSwipeIndex, index)
                                        val rangePaths = vm.leftEntries.subList(from, to + 1).map { it.path }.toSet()
                                        leftSelectedPaths = leftSelectedPaths + rangePaths
                                        leftSwipeSelectFlag = 0
                                    } else {
                                        // 单个追加
                                        leftSelectedPaths = leftSelectedPaths + entry.path
                                        leftSwipeSelectFlag = 1
                                        leftLastSwipeIndex = index
                                    }
                                },
                                onToggleSelect = { entry ->
                                    leftSelectedPaths = if (entry.path in leftSelectedPaths) {
                                        leftSelectedPaths - entry.path
                                    } else {
                                        leftSelectedPaths + entry.path
                                    }
                                    if (leftSelectedPaths.isEmpty()) {
                                        leftSwipeSelectFlag = 0
                                        leftLastSwipeIndex = -1
                                    }
                                },
                                extFlagsMap = vm.leftExtFlagsMap
                            )
                            FileBrowserPanel(
                                entries = vm.rightEntries,
                                isFocused = !leftFocused,
                                onFocus = { vm.focusedPanel = FocusedPanel.RIGHT },
                                onFolderClick = { entry ->
                                    vm.focusedPanel = FocusedPanel.RIGHT
                                    if (vm.isInArchiveMode) {
                                        vm.navigateInArchive(entry)
                                    } else if (vm.recycleBinPanel == vm.focusedPanel) {
                                        vm.navigateInRecycleBin(entry)
                                    } else {
                                        DiagnosticLog.beginSession("[RIGHT] 点击文件夹 '${entry.name}'")
                                        DiagnosticLog.log("FileMgr", "[RIGHT] 点击文件夹 name='${entry.name}' path='${entry.path}' from=${vm.rightPath}")
                                        vm.saveScrollPosition(
                                            leftListState.firstVisibleItemIndex,
                                            leftListState.firstVisibleItemScrollOffset,
                                            rightListState.firstVisibleItemIndex,
                                            rightListState.firstVisibleItemScrollOffset
                                        )
                                        vm.navigateToFolder(entry)
                                        rightSelectedPaths = emptySet(); rightSwipeSelectFlag = 0; rightLastSwipeIndex = -1
                                    }
                                },
                                onFileClick = { entry ->
                                    if (vm.isInArchiveMode) {
                                        DiagnosticLog.beginSession("[RIGHT] 压缩包内点击文件 '${entry.name}'")
                                        DiagnosticLog.log("FileMgr", "[RIGHT] 压缩包内文件 name='${entry.name}'")
                                        vm.focusedPanel = FocusedPanel.RIGHT
                                        coroutineScope.launch {
                                            val screen = vm.openArchiveFile(context, entry)
                                            if (screen != null) {
                                                vm.saveScrollPosition(
                                                    leftListState.firstVisibleItemIndex,
                                                    leftListState.firstVisibleItemScrollOffset,
                                                    rightListState.firstVisibleItemIndex,
                                                    rightListState.firstVisibleItemScrollOffset
                                                )
                                                onNavigate(screen)
                                            } else {
                                                Toast.makeText(context, "文件提取失败", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        return@FileBrowserPanel
                                    }
                                    DiagnosticLog.beginSession("[RIGHT] 点击文件 '${entry.name}'")
                                    DiagnosticLog.log("FileMgr", "[RIGHT] 点击文件 name='${entry.name}' path='${entry.path}'")
                                    vm.focusedPanel = FocusedPanel.RIGHT
                                    val screen = vm.openFile(context, entry, isDebugMode)
                                    if (screen != null) {
                                        vm.saveScrollPosition(
                                            leftListState.firstVisibleItemIndex,
                                            leftListState.firstVisibleItemScrollOffset,
                                            rightListState.firstVisibleItemIndex,
                                            rightListState.firstVisibleItemScrollOffset
                                        )
                                        onNavigate(screen)
                                    }
                                    vm.addHistory(entry.name, entry.path, false)
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
                                    if (vm.isInArchiveMode) {
                                        vm.focusedPanel = FocusedPanel.RIGHT
                                        vm.archiveGoUp()
                                    } else if (vm.recycleBinPanel == vm.focusedPanel) {
                                        vm.focusedPanel = FocusedPanel.RIGHT
                                        vm.goUpInRecycleBin()
                                    } else if (rightParentPath != null) {
                                        vm.focusedPanel = FocusedPanel.RIGHT
                                        vm.saveScrollPosition(
                                            leftListState.firstVisibleItemIndex,
                                            leftListState.firstVisibleItemScrollOffset,
                                            rightListState.firstVisibleItemIndex,
                                            rightListState.firstVisibleItemScrollOffset
                                        )
                                        val saved = vm.getScrollPosition(rightParentPath)
                                        vm.navigateToWithScroll(rightParentPath, saved?.first ?: 0, saved?.second ?: 0)
                                        rightSelectedPaths = emptySet(); rightSwipeSelectFlag = 0; rightLastSwipeIndex = -1
                                    }
                                },
                                archiveSizeProvider = if (vm.isInArchiveMode) { entry ->
                                    if (entry.compressedSize > 0 || entry.size > 0)
                                        "${compactSize(entry.compressedSize)}(${compactSize(entry.size)})"
                                    else "--"
                                } else null,
                                onVisibleRangeChanged = null,
                                thumbnailLoader = null,
                                selectedPaths = rightSelectedPaths,
                                onSwipeSelect = { entry, index ->
                                    vm.focusedPanel = FocusedPanel.RIGHT
                                    // 右滑已选中的唯一卡片 → 取消选中，退出多选
                                    if (rightSelectedPaths.size == 1 && entry.path in rightSelectedPaths) {
                                        rightSelectedPaths = emptySet(); rightSwipeSelectFlag = 0; rightLastSwipeIndex = -1
                                    } else if (rightSelectedPaths.isEmpty()) {
                                        rightSelectedPaths = setOf(entry.path)
                                        rightSwipeSelectFlag = 1
                                        rightLastSwipeIndex = index
                                    } else if (rightSwipeSelectFlag == 1) {
                                        val from = minOf(rightLastSwipeIndex, index)
                                        val to = maxOf(rightLastSwipeIndex, index)
                                        val rangePaths = vm.rightEntries.subList(from, to + 1).map { it.path }.toSet()
                                        rightSelectedPaths = rightSelectedPaths + rangePaths
                                        rightSwipeSelectFlag = 0
                                    } else {
                                        rightSelectedPaths = rightSelectedPaths + entry.path
                                        rightSwipeSelectFlag = 1
                                        rightLastSwipeIndex = index
                                    }
                                },
                                onToggleSelect = { entry ->
                                    rightSelectedPaths = if (entry.path in rightSelectedPaths) {
                                        rightSelectedPaths - entry.path
                                    } else {
                                        rightSelectedPaths + entry.path
                                    }
                                    if (rightSelectedPaths.isEmpty()) {
                                        rightSwipeSelectFlag = 0
                                        rightLastSwipeIndex = -1
                                    }
                                },
                                extFlagsMap = vm.rightExtFlagsMap
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
                                                        vm.navigateToWithScroll("/storage/emulated/0/")
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
                                                        vm.navigateToWithScroll("/")
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
                                                    vm.navigateToWithScroll(entry.path)
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
                                // WebDAV 快捷访问
                                webDavServers.forEach { server ->
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth(0.85f)
                                                .padding(vertical = 6.dp)
                                                .clickable {
                                                    vm.navigateToWebDav(server)
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
                                                    Icons.Default.Cloud,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    server.name.ifEmpty { server.getDefaultName() },
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
                                        .clickable { showQaTypeSelector = true }
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
                visible = selectedEntry != null && !hideToolbarForDelete,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val isToRight = vm.focusedPanel == FocusedPanel.LEFT
                val activeSelectedPaths = if (vm.focusedPanel == FocusedPanel.LEFT) leftSelectedPaths else rightSelectedPaths
                val isMultiSelect = activeSelectedPaths.size > 1
                val selectedEntries = if (vm.focusedPanel == FocusedPanel.LEFT)
                    vm.leftEntries.filter { it.path in leftSelectedPaths }
                else
                    vm.rightEntries.filter { it.path in rightSelectedPaths }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                selectedEntry = null
                                // 清空多选状态
                                if (vm.focusedPanel == FocusedPanel.LEFT) {
                                    leftSelectedPaths = emptySet(); leftSwipeSelectFlag = 0; leftLastSwipeIndex = -1
                                } else {
                                    rightSelectedPaths = emptySet(); rightSwipeSelectFlag = 0; rightLastSwipeIndex = -1
                                }
                            }
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
                            if (vm.recycleBinPanel == vm.focusedPanel) {
                                // ── 回收站模式：永久删除 / 恢复到原位置 ──
                                // 多选模式下显示选中数量
                                if (isMultiSelect) {
                                    Text(
                                        text = "已选中 ${activeSelectedPaths.size} 个项目",
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    HorizontalDivider()
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 左列：永久删除
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                if (isMultiSelect) {
                                                    permanentDeleteMultiNames = selectedEntries.map { it.name }
                                                    showPermanentDeleteDialog = true
                                                } else {
                                                    val entry = selectedEntry ?: return@clickable
                                                    permanentDeleteTarget = entry.name
                                                    showPermanentDeleteDialog = true
                                                    selectedEntry = null
                                                }
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
                                                val namesToRestore = if (isMultiSelect) selectedEntries.map { it.name }
                                                else listOfNotNull(selectedEntry?.name)
                                                if (namesToRestore.isEmpty()) return@clickable
                                                var failCount = 0
                                                for (name in namesToRestore) {
                                                    val error = vm.restoreFromRecycleBin(name)
                                                    if (error != null) failCount++
                                                }
                                                if (failCount == 0) {
                                                    Toast.makeText(context, "已恢复 ${namesToRestore.size} 个项目", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "恢复完成，${failCount} 个失败", Toast.LENGTH_SHORT).show()
                                                }
                                                vm.enterRecycleBin()
                                                selectedEntry = null
                                                if (vm.focusedPanel == FocusedPanel.LEFT) leftSelectedPaths = emptySet()
                                                else rightSelectedPaths = emptySet()
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
                            } else {
                            val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            val disabledIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            // 多选模式下显示选中数量
                            if (isMultiSelect) {
                                Text(
                                    text = "已选中 ${activeSelectedPaths.size} 个项目",
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                HorizontalDivider()
                            }
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
                                            val sourcePaths = if (activeSelectedPaths.isNotEmpty()) {
                                                activeSelectedPaths.toList()
                                            } else {
                                                listOfNotNull(selectedEntry?.path)
                                            }
                                            if (sourcePaths.isEmpty()) {
                                                Toast.makeText(context, "请先选择文件", Toast.LENGTH_SHORT).show()
                                                return@clickable
                                            }
                                            val targetDir = if (isToRight) vm.rightPath else vm.leftPath
                                            copyMoveConfirmIsCopy = true
                                            copyMoveConfirmSourcePaths = sourcePaths
                                            copyMoveConfirmTargetDir = targetDir
                                            showCopyMoveConfirmDialog = true
                                        }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isToRight) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("复制", style = MaterialTheme.typography.bodyLarge)
                                            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Text("复制", style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                                VerticalDivider(modifier = Modifier.height(24.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                                // 右列：移动
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            val sourcePaths = if (activeSelectedPaths.isNotEmpty()) {
                                                activeSelectedPaths.toList()
                                            } else {
                                                listOfNotNull(selectedEntry?.path)
                                            }
                                            if (sourcePaths.isEmpty()) {
                                                Toast.makeText(context, "请先选择文件", Toast.LENGTH_SHORT).show()
                                                return@clickable
                                            }
                                            val targetDir = if (isToRight) vm.rightPath else vm.leftPath
                                            copyMoveConfirmIsCopy = false
                                            copyMoveConfirmSourcePaths = sourcePaths
                                            copyMoveConfirmTargetDir = targetDir
                                            showCopyMoveConfirmDialog = true
                                        }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isToRight) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("移动", style = MaterialTheme.typography.bodyLarge)
                                            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
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
                                // 左列：重命名（多选时禁用）
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(enabled = !isMultiSelect) {
                                            val entry = selectedEntry ?: return@clickable
                                            renameText = entry.name
                                            showRenameDialog = true
                                        }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isToRight) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("重命名", style = MaterialTheme.typography.bodyLarge, color = if (isMultiSelect) disabledColor else Color.Unspecified)
                                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (isMultiSelect) disabledIconColor else MaterialTheme.colorScheme.onSurface)
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (isMultiSelect) disabledIconColor else MaterialTheme.colorScheme.onSurface)
                                            Text("重命名", style = MaterialTheme.typography.bodyLarge, color = if (isMultiSelect) disabledColor else Color.Unspecified)
                                        }
                                    }
                                }
                                VerticalDivider(modifier = Modifier.height(24.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                                // 右列：删除
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            if (isMultiSelect) {
                                                hideToolbarForDelete = true
                                                showDeleteDialog = true
                                            } else selectedEntry?.let {
                                                hideToolbarForDelete = true
                                                showDeleteDialog = true
                                            }
                                        }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isToRight) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("删除", style = MaterialTheme.typography.bodyLarge)
                                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Text("删除", style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            }
                            // ── 第三行：压缩 / 解压（占位） ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 左列：压缩
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            val entries = selectedEntries.ifEmpty {
                                                listOfNotNull(selectedEntry)
                                            }
                                            if (entries.isNotEmpty()) {
                                                compressEntries = entries
                                                compressOutputToOtherPanel = false
                                                compressUseAes = encSettings.compressUseAes
                                                compressEncryptNames = false
                                                showCompressDialog = true
                                            }
                                            selectedEntry = null
                                        }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isToRight) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("压缩", style = MaterialTheme.typography.bodyLarge)
                                            Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Text("压缩", style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                                VerticalDivider(modifier = Modifier.height(24.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                                // 右列：解压
                                val extractEntries = selectedEntries.ifEmpty { listOfNotNull(selectedEntry) }
                                val canExtract = extractEntries.isNotEmpty()
                                    && extractEntries.all { ArchiveBrowser.isArchiveFile(it.name) }
                                    && !vm.isInArchiveMode
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(enabled = canExtract) {
                                            extractTargetEntries = extractEntries
                                            showExtractDialog = true
                                            selectedEntry = null
                                        }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isToRight) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("解压", style = MaterialTheme.typography.bodyLarge, color = if (canExtract) Color.Unspecified else disabledColor)
                                            Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (canExtract) MaterialTheme.colorScheme.onSurface else disabledIconColor)
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (canExtract) MaterialTheme.colorScheme.onSurface else disabledIconColor)
                                            Text("解压", style = MaterialTheme.typography.bodyLarge, color = if (canExtract) Color.Unspecified else disabledColor)
                                        }
                                    }
                                }
                            }
                            // ── 第四行：加密 / 解密 ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 左列：加密
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            selectedEntry = null
                                            if (vm.focusedPanel == FocusedPanel.LEFT) leftSelectedPaths = emptySet()
                                            else rightSelectedPaths = emptySet()
                                            showEncryptDialog = true
                                        }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text("加密", style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                                VerticalDivider(modifier = Modifier.height(24.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                                // 右列：解密
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text("解密", style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                            // ── 第五行：大小刷新(仅文件夹) / 属性 ──
                            val isFolder = !isMultiSelect && selectedEntry?.isDirectory == true
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 左列：大小刷新（仅文件夹可用）
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .combinedClickable(
                                            enabled = isFolder,
                                            onClick = {
                                                val target = selectedEntry?.path
                                                selectedEntry = null
                                                if (target != null) vm.calculateFolderSizeAsync(target)
                                            },
                                            onLongClick = { if (isFolder) showSizeCalcOptionsMenu = true }
                                        )
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isToRight) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("大小刷新", style = MaterialTheme.typography.bodyLarge, color = if (isFolder) Color.Unspecified else disabledColor)
                                            Icon(Icons.Default.FolderSpecial, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (isFolder) MaterialTheme.colorScheme.onSurface else disabledIconColor)
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.FolderSpecial, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (isFolder) MaterialTheme.colorScheme.onSurface else disabledIconColor)
                                            Text("大小刷新", style = MaterialTheme.typography.bodyLarge, color = if (isFolder) Color.Unspecified else disabledColor)
                                        }
                                    }
                                }
                                VerticalDivider(modifier = Modifier.height(24.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                                // 右列：属性（文件和文件夹均可用，多选时禁用）
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(enabled = !isMultiSelect) {
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
                                            Text("属性", style = MaterialTheme.typography.bodyLarge, color = if (isMultiSelect) disabledColor else Color.Unspecified)
                                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (isMultiSelect) disabledIconColor else MaterialTheme.colorScheme.onSurface)
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (isMultiSelect) disabledIconColor else MaterialTheme.colorScheme.onSurface)
                                            Text("属性", style = MaterialTheme.typography.bodyLarge, color = if (isMultiSelect) disabledColor else Color.Unspecified)
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

    // ── 诊断错误对话框 ──
    ErrorDialog(error = diagnosticError, onDismiss = {
        diagnosticError = null
    })

    // ── 诊断逻辑（仅 Debug 模式） ──
    LaunchedEffect(isDebugMode) {
        if (!isDebugMode) return@LaunchedEffect
        val legacySp = context.getSharedPreferences(AppDataPaths.PREFS_LEGACY_SPECIAL_PERMISSIONS, Context.MODE_PRIVATE)
        val permissionLevel = legacySp.getString("target_permission_level", "NORMAL") ?: "NORMAL"

        if (permissionLevel != "ROOT") {
            diagnosticMessage = "当前权限级别: $permissionLevel（非 Root 模式）"
            return@LaunchedEffect
        }

        // 检查 Root 权限
        try {
            val isRootAvailable = SpecialPermissionVerifier.isRootAvailable()
            if (!isRootAvailable) {
                val error = SecurityException("Root 权限异常：已设置 Root 模式但无法获取 root 权限")
                DiagnosticLog.log("FileMgr", "Root 权限异常：isRootAvailable=false")
                diagnosticError = error
                return@LaunchedEffect
            }

            // 获取挂载命名空间信息
            val mountInfo = try {
                val selfNs = ShellExecutor.execute(Permission.ROOT, "stat -c '%i' /proc/self/ns/mnt")
                val initNs = ShellExecutor.execute(Permission.ROOT, "stat -c '%i' /proc/1/ns/mnt")
                val sameNs = selfNs.trim() == initNs.trim()
                "Root 权限已就绪\n当前挂载命名空间: self=${selfNs.trim()} init=${initNs.trim()} 同一namespace=$sameNs"
            } catch (e: Exception) {
                "Root 权限已就绪（无法获取命名空间信息: ${e.message}）"
            }

            diagnosticMessage = mountInfo
            DiagnosticLog.log("FileMgr", "诊断完成: $mountInfo")
        } catch (e: Exception) {
            DiagnosticLog.log("FileMgr", "诊断异常: ${e.message}")
            diagnosticError = e
        }
    }

    // ── 诊断信息显示（底部 Snackbar，5 秒自动消失） ──
    LaunchedEffect(diagnosticMessage) {
        if (diagnosticMessage != null) {
            kotlinx.coroutines.delay(5000)
            diagnosticMessage = null
        }
    }
    if (isDebugMode && diagnosticMessage != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 100.dp),  // 底部栏上方
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .clickable { diagnosticMessage = null },  // 点击关闭
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp
            ) {
                Text(
                    text = diagnosticMessage ?: "",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
    }

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

    // ── APK 信息弹窗 ──
    vm.pendingApkEntry?.let { entry ->
        ApkInfoDialog(
            apkPath = entry.path,
            onDismiss = { vm.pendingApkEntry = null }
        )
    }

    // ── 7z 信息弹窗 ──
    val sevenZipDialogEntry = vm.sevenZipInfo
    if (sevenZipDialogEntry != null || vm.sevenZipAnalyzing) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { if (!vm.sevenZipAnalyzing) vm.sevenZipInfo = null },
            title = { Text("7z 压缩包信息", fontWeight = FontWeight.Bold) },
            text = {
                if (vm.sevenZipAnalyzing) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在检测...")
                    }
                } else if (sevenZipDialogEntry != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("文件名  ${sevenZipDialogEntry.fileName}", style = MaterialTheme.typography.bodyMedium)
                        Text("大小    ${FormatUtils.formatBytes(sevenZipDialogEntry.fileSize)}", style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("内容加密", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    sevenZipDialogEntry.isCorrupted -> "无法检测"
                                    sevenZipDialogEntry.contentEncrypted || sevenZipDialogEntry.headerEncrypted -> "✓ 已加密"
                                    else -> "✗ 未加密"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    sevenZipDialogEntry.isCorrupted -> MaterialTheme.colorScheme.error
                                    sevenZipDialogEntry.contentEncrypted || sevenZipDialogEntry.headerEncrypted -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("文件名加密", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    sevenZipDialogEntry.isCorrupted -> "无法检测"
                                    sevenZipDialogEntry.headerEncrypted -> "✓ 已加密"
                                    else -> "✗ 未加密"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    sevenZipDialogEntry.isCorrupted -> MaterialTheme.colorScheme.error
                                    sevenZipDialogEntry.headerEncrypted -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        val errMsg = sevenZipDialogEntry.errorMessage
                        if (errMsg != null) {
                            Text(
                                errMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        if (sevenZipDialogEntry.headerEncrypted || sevenZipDialogEntry.contentEncrypted) {
                            Text(
                                "加密压缩包需要密码才能查看内容",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        // 诊断信息（调试阶段始终显示）
                        val diag = sevenZipDialogEntry.diagnosticInfo
                        if (diag.isNotBlank()) {
                            Text(
                                diag,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (sevenZipDialogEntry != null && sevenZipDialogEntry.diagnosticInfo.isNotBlank()) {
                    TextButton(
                        onClick = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cb.setPrimaryClip(android.content.ClipData.newPlainText("7z diagnostic", sevenZipDialogEntry.diagnosticInfo))
                        },
                        enabled = !vm.sevenZipAnalyzing
                    ) {
                        Text("复制")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { vm.sevenZipInfo = null },
                    enabled = !vm.sevenZipAnalyzing
                ) {
                    Text("取消")
                }
            }
        )
    }

    // ── 压缩包 Debug 信息弹窗 ──
    vm.archiveDebugInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { vm.archiveDebugInfo = null },
            title = { Text("压缩包 Debug 信息", fontWeight = FontWeight.Bold) },
            text = {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    @Composable
                    fun InfoRow(label: String, value: String) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    InfoRow("文件名", info.archiveName)
                    InfoRow("路径", info.archivePath)
                    InfoRow("需要密码", if (info.passwordRequired) "是" else "否")
                    Spacer(Modifier.height(4.dp))
                    InfoRow("列表命令", info.listCommand)
                    InfoRow("退出码", info.listExitCode.toString())
                    if (info.listStderr.isNotBlank()) {
                        InfoRow("stderr", info.listStderr.take(500))
                    }
                    Spacer(Modifier.height(4.dp))
                    InfoRow("解析条目数", info.parsedEntryCount.toString())
                    if (info.rootEntries.isNotEmpty()) {
                        Text("根目录内容:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        info.rootEntries.forEach { entry ->
                            val icon = if (entry.isDirectory) "📁" else "📄"
                            val sizeStr = if (entry.isDirectory) "" else " (${FormatUtils.formatBytes(entry.size)})"
                            Text(
                                text = "$icon ${entry.name}$sizeStr",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    val errorText = info.error
                    if (errorText != null) {
                        Spacer(Modifier.height(4.dp))
                        Text("错误:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        Text(errorText, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { vm.archiveDebugInfo = null }) {
                    Text("取消")
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.confirmOpenArchive() },
                    enabled = info.session != null || info.passwordRequired
                ) {
                    Text(if (info.passwordRequired) "继续" else "打开")
                }
            }
        )
    }

    // ── 压缩包打开错误弹窗 ──
    vm.archiveOpenError?.let { (fileName, message) ->
        AlertDialog(
            onDismissRequest = { vm.archiveOpenError = null },
            title = { Text("无法打开压缩包") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { vm.archiveOpenError = null }) {
                    Text("确定")
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
        val entry = selectedEntry!!
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
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
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
    val delMultiSelect = if (vm.focusedPanel == FocusedPanel.LEFT) leftSelectedPaths.size > 1 else rightSelectedPaths.size > 1
    val delSelectedEntries = if (vm.focusedPanel == FocusedPanel.LEFT)
        vm.leftEntries.filter { it.path in leftSelectedPaths }
    else
        vm.rightEntries.filter { it.path in rightSelectedPaths }
    if (showDeleteDialog && (selectedEntry != null || delMultiSelect)) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除") },
            text = {
                Column {
                    if (delMultiSelect) {
                        Text("确定要删除选中的 ${delSelectedEntries.size} 个项目吗？")
                    } else {
                        Text("确定要删除「${selectedEntry!!.name}」吗？")
                    }
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
                    showDeleteDialog = false
                    showDeleteProgress = true
                    val deleteEntries = if (delMultiSelect) {
                        delSelectedEntries.map { entry ->
                            DeleteEntry(entry.path, entry.name, entry.isDirectory, entry.size)
                        }
                    } else {
                        val entry = selectedEntry ?: return@TextButton
                        listOf(DeleteEntry(entry.path, entry.name, entry.isDirectory, entry.size))
                    }
                    val accessLevel = when {
                        vm.isRootEngine -> com.whmdg.mczj.tools.util.FileAccessLevel.ROOT
                        com.whmdg.mczj.tools.security.SpecialPermissionVerifier.isShizukuAuthorized(context) -> com.whmdg.mczj.tools.util.FileAccessLevel.SHIZUKU
                        else -> com.whmdg.mczj.tools.util.FileAccessLevel.NORMAL
                    }
                    FileOperationManager.delete(deleteEntries, recycleBinEnabled, accessLevel, context)
                    if (vm.focusedPanel == FocusedPanel.LEFT) {
                        leftSelectedPaths = emptySet(); leftSwipeSelectFlag = 0; leftLastSwipeIndex = -1
                    } else {
                        rightSelectedPaths = emptySet(); rightSwipeSelectFlag = 0; rightLastSwipeIndex = -1
                    }
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    hideToolbarForDelete = false
                }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 删除进度对话框 ──
    if (showDeleteProgress) {
        val isMultiDel = if (vm.focusedPanel == FocusedPanel.LEFT) leftSelectedPaths.size > 1 else rightSelectedPaths.size > 1
        AlertDialog(
            onDismissRequest = { /* 不可手动关闭 */ },
            title = { Text("删除") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (isMultiDel) "正在删除..."
                        else "正在删除「${selectedEntry?.name ?: ""}」"
                    )
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
        // 删除完成（progress 变为 null）→ 自动关闭对话框、清除状态
        LaunchedEffect(fileOpManagerProgress) {
            if (fileOpManagerProgress == null) {
                showDeleteProgress = false
                selectedEntry = null
                hideToolbarForDelete = false
            }
        }
    }

    // ── 复制/移动确认对话框 ──
    if (showCopyMoveConfirmDialog) {
        val sourceNames = copyMoveConfirmSourcePaths.map { path ->
            path.substringAfterLast('/')
        }
        val sourceDisplay = if (sourceNames.size == 1) {
            sourceNames[0]
        } else {
            "${sourceNames[0]} 等 ${sourceNames.size} 个文件"
        }
        val sourceDir = copyMoveConfirmSourcePaths.firstOrNull()?.substringBeforeLast('/') ?: ""
        AlertDialog(
            onDismissRequest = { showCopyMoveConfirmDialog = false },
            title = { Text(if (copyMoveConfirmIsCopy) "确认复制" else "确认移动") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (copyMoveConfirmIsCopy) "复制到以下目录：" else "移动到以下目录：",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = copyMoveConfirmTargetDir,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "源文件：",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = sourceDisplay,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                    Text(
                        text = sourceDir,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCopyMoveConfirmDialog = false
                    val accessLevel = when {
                        vm.isRootEngine -> com.whmdg.mczj.tools.util.FileAccessLevel.ROOT
                        com.whmdg.mczj.tools.security.SpecialPermissionVerifier.isShizukuAuthorized(context) -> com.whmdg.mczj.tools.util.FileAccessLevel.SHIZUKU
                        else -> com.whmdg.mczj.tools.util.FileAccessLevel.NORMAL
                    }
                    if (copyMoveConfirmIsCopy) {
                        FileOperationManager.copy(copyMoveConfirmSourcePaths, copyMoveConfirmTargetDir, accessLevel, context, isDebugMode)
                    } else {
                        FileOperationManager.move(copyMoveConfirmSourcePaths, copyMoveConfirmTargetDir, accessLevel, context, isDebugMode)
                    }
                    showFileOpProgress = true
                    cancelingFileOp = false
                    selectedEntry = null
                    leftSelectedPaths = emptySet()
                    rightSelectedPaths = emptySet()
                }) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCopyMoveConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 复制/移动进度对话框 ──
    if (showFileOpProgress) {
        val progress = fileOpManagerProgress
        AlertDialog(
            onDismissRequest = { /* 不可手动关闭 */ },
            title = { Text(progress?.phase ?: "处理中") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (progress != null) {
                        if (progress.currentFileName.isNotEmpty()) {
                            Text(
                                text = progress.currentFileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        if (progress.isScanning) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else if (progress.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { progress.fraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (progress.isScanning) {
                                if (progress.totalBytes > 0) "${FormatUtils.formatBytes(progress.totalBytes)} (正在统计)"
                                else "正在统计..."
                            } else {
                                "${FormatUtils.formatBytes(progress.currentBytes)} / ${FormatUtils.formatBytes(progress.totalBytes)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    if (cancelingFileOp) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "确定取消？当前文件将继续完成。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                if (cancelingFileOp) {
                    TextButton(onClick = {
                        FileOperationManager.gracefulCancel()
                        cancelingFileOp = false
                    }) {
                        Text("确认取消", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(onClick = { cancelingFileOp = true }) {
                        Text("取消")
                    }
                }
            },
            dismissButton = {
                if (cancelingFileOp) {
                    TextButton(onClick = { cancelingFileOp = false }) {
                        Text("继续")
                    }
                } else if (isDebugMode) {
                    TextButton(onClick = {
                        val report = com.whmdg.mczj.tools.fileop.FileOpDiagnostics.export()
                        // 写入剪贴板
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("file_op_diag", report))
                        // 写入文件
                        try {
                            val diagDir = com.whmdg.mczj.tools.AppDataPaths.diagnostics(context)
                            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                            val file = java.io.File(diagDir, "file_op_report_$timestamp.log")
                            file.writeText(report)
                            android.widget.Toast.makeText(context, "报告已保存: ${file.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("提取报告")
                    }
                }
            }
        )
        // 操作完成（progress 变为 null）→ 自动关闭对话框
        LaunchedEffect(fileOpManagerProgress) {
            if (fileOpManagerProgress == null) {
                showFileOpProgress = false
                cancelingFileOp = false
            }
        }
    }

    // ── 文件操作冲突/错误弹窗 ──
    FileConflictDialog()
    FileErrorDialog()

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
    if (showPermanentDeleteDialog && (permanentDeleteTarget != null || permanentDeleteMultiNames.isNotEmpty())) {
        val isMultiDelete = permanentDeleteMultiNames.isNotEmpty()
        AlertDialog(
            onDismissRequest = {
                showPermanentDeleteDialog = false; permanentDeleteTarget = null; permanentDeleteMultiNames = emptyList()
            },
            title = { Text("永久删除") },
            text = {
                if (isMultiDelete) Text("确定要永久删除选中的 ${permanentDeleteMultiNames.size} 个项目吗？此操作不可撤销。")
                else Text("确定要永久删除「${permanentDeleteTarget}」吗？此操作不可撤销。")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isMultiDelete) {
                        var failCount = 0
                        for (name in permanentDeleteMultiNames) {
                            val error = vm.permanentDelete(name)
                            if (error != null) failCount++
                        }
                        if (failCount == 0) {
                            Toast.makeText(context, "已永久删除 ${permanentDeleteMultiNames.size} 个项目", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "删除完成，${failCount} 个失败", Toast.LENGTH_SHORT).show()
                        }
                        if (vm.focusedPanel == FocusedPanel.LEFT) leftSelectedPaths = emptySet()
                        else rightSelectedPaths = emptySet()
                    } else {
                        val name = permanentDeleteTarget ?: return@TextButton
                        val error = vm.permanentDelete(name)
                        if (error == null) {
                            Toast.makeText(context, "已永久删除", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "删除失败: $error", Toast.LENGTH_SHORT).show()
                        }
                    }
                    vm.enterRecycleBin()
                    showPermanentDeleteDialog = false
                    permanentDeleteTarget = null
                    permanentDeleteMultiNames = emptyList()
                    selectedEntry = null
                }) {
                    Text("确定", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermanentDeleteDialog = false; permanentDeleteTarget = null; permanentDeleteMultiNames = emptyList()
                }) {
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

    // ── 添加快捷访问类型选择 ──
    if (showQaTypeSelector) {
        AlertDialog(
            onDismissRequest = { showQaTypeSelector = false },
            title = { Text("添加快捷访问") },
            text = {
                Column {
                    // 本地路径选项
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showQaTypeSelector = false
                                showAddQaDialog = true
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("本地路径", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "添加本地文件夹快捷方式",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // WebDAV 服务器选项
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showQaTypeSelector = false
                                showWebDavEditDialog = true
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("WebDAV 服务器", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "连接远程 WebDAV 文件服务器",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showQaTypeSelector = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ── WebDAV 服务器编辑弹窗 ──
    if (showWebDavEditDialog) {
        WebDavEditDialog(
            onDismiss = { showWebDavEditDialog = false },
            onSaved = { config ->
                webDavServers = WebDavServerStore.getAll(context)
                showWebDavEditDialog = false
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

        // 扩展文件属性（chattr，仅 i/a 在 f2fs 上可用）
        val originalExtFlags = remember { vm.readExtFlags(entry.path) }
        var extImmutable by remember { mutableStateOf(originalExtFlags.contains('i')) }
        var extAppend by remember { mutableStateOf(originalExtFlags.contains('a')) }
        var showExtHelp by remember { mutableStateOf(false) }

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
                            // ── 第4行：特殊扩展属性 ──
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "特殊",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.width(80.dp)
                                )
                                data class ExtFlag(val label: String, val key: Char, val get: () -> Boolean, val set: (Boolean) -> Unit)
                                val extFlags = listOf(
                                    ExtFlag("i", 'i', { extImmutable }, { extImmutable = it }),
                                    ExtFlag("a", 'a', { extAppend }, { extAppend = it }),
                                )
                                extFlags.forEach { flag ->
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Checkbox(
                                                checked = flag.get(),
                                                onCheckedChange = { flag.set(it) },
                                                enabled = vm.isRootEngine
                                            )
                                            Text(
                                                text = flag.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (vm.isRootEngine) MaterialTheme.colorScheme.onSurfaceVariant
                                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                            )
                                        }
                                    }
                                }
                                // 问号帮助按钮
                                Box(
                                    modifier = Modifier.width(40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    IconButton(onClick = { showExtHelp = true }, modifier = Modifier.size(24.dp)) {
                                        Icon(
                                            Icons.Default.Help,
                                            contentDescription = "帮助",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
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

                                // 第一步：处理扩展属性（chattr），优先于基本权限
                                val desiredFlags = buildSet {
                                    if (extImmutable) add('i')
                                    if (extAppend) add('a')
                                }
                                val originalFlagSet = originalExtFlags.filter { it == 'i' || it == 'a' }.toSet()
                                if (desiredFlags != originalFlagSet) {
                                    val extResult = vm.applyExtFlags(entry.path, desiredFlags, originalExtFlags)
                                    if (extResult != null) {
                                        applying = false
                                        errorMsg = extResult
                                        return@TextButton
                                    }
                                }

                                // 第二步：仅当基本权限（rwx/uid/gid）发生变化时才执行 chmod/chown
                                val permChanged = currentMode != originalMode || selectedUid != originalUid || selectedGid != originalGid
                                if (permChanged) {
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
                                        return@TextButton
                                    }
                                } else {
                                    applying = false
                                }

                                showPermissionEditor = false
                                vm.refreshCurrent()
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
            val allUsers = remember { vm.getSystemUsers() }
            // 当前所有者排第一，其余按 UID 排序
            val sortedUsers = remember(allUsers, originalUid) {
                val current = allUsers.find { it.uid == originalUid }
                val rest = allUsers.filter { it.uid != originalUid }
                listOfNotNull(current) + rest
            }
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
                            items(sortedUsers) { user ->
                                val isCurrent = user.uid == originalUid
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
                                    Text(
                                        text = if (isCurrent) "${user.username} (当前)" else user.username,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
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
            val allGroups = remember { vm.getSystemGroups() }
            // 当前用户组排第一，其余按 GID 排序
            val sortedGroups = remember(allGroups, originalGid) {
                val current = allGroups.find { it.gid == originalGid }
                val rest = allGroups.filter { it.gid != originalGid }
                listOfNotNull(current) + rest
            }
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
                            items(sortedGroups) { group ->
                                val isCurrent = group.gid == originalGid
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
                                    Text(
                                        text = if (isCurrent) "${group.groupname} (当前)" else group.groupname,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
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

        // ── 扩展属性帮助弹窗 ──
        if (showExtHelp) {
            Dialog(onDismissRequest = { showExtHelp = false }) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("特殊属性说明", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        // i - 不可变
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("i — 不可变 (Immutable)", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                            Text("设置后文件/目录无法被修改、删除、重命名或创建硬链接。即使 Root 用户也必须先移除此标志才能操作。常用于保护关键系统文件不被意外篡改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(thickness = 0.3.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        // a - 仅追加
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("a — 仅追加 (Append Only)", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                            Text("文件只能以追加模式打开写入，不能覆盖已有内容，也不能删除。适用于日志文件等只需持续追加数据的场景。对目录设置时，目录内只能创建或修改文件，不能删除。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(thickness = 0.3.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        Text("以上属性均需 Root 权限才能修改，读取状态无需 Root。属性存储在文件系统的 inode 标志中，与基本 rwx 权限独立。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showExtHelp = false }) { Text("知道了") }
                        }
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

    // ── 加密方式选择对话框 ──
    if (showEncryptDialog) {
        AlertDialog(
            onDismissRequest = { showEncryptDialog = false },
            title = { Text("选择加密方式") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { encryptMode = 0 }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = encryptMode == 0, onClick = { encryptMode = 0 })
                        Spacer(Modifier.width(8.dp))
                        Text("打包加密", style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { encryptMode = 1 }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = encryptMode == 1, onClick = { encryptMode = 1 })
                        Spacer(Modifier.width(8.dp))
                        Text("分片加密", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showEncryptDialog = false }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { showEncryptDialog = false }) { Text("取消") } }
        )
    }

    // ── 压缩对话框 ──
    if (showCompressDialog && compressEntries.isNotEmpty()) {
        val isDark = isSystemInDarkTheme()
        val formats = listOf("zip", "7z", "tar", "tar.gz", "tar.bz2", "tar.xz")
        val suffixMap = mapOf(
            "zip" to ".zip", "7z" to ".7z", "tar" to ".tar",
            "tar.gz" to ".tar.gz", "tar.bz2" to ".tar.bz2", "tar.xz" to ".tar.xz"
        )

        var selectedFormat by remember { mutableStateOf("zip") }
        var compressLevel by remember { mutableIntStateOf(5) }
        var compressPassword by remember { mutableStateOf("") }
        var passwordError by remember { mutableStateOf<String?>(null) }
        var passwordVisible by remember { mutableStateOf(false) }
        var showFormatDropdown by remember { mutableStateOf(false) }
        var showLevelDropdown by remember { mutableStateOf(false) }
        val defaultFileName = if (compressEntries.size == 1) {
            compressEntries[0].name + ".zip"
        } else {
            val dirName = if (vm.focusedPanel == FocusedPanel.LEFT) {
                vm.leftPath.substringAfterLast('/').ifEmpty { "压缩包" }
            } else {
                vm.rightPath.substringAfterLast('/').ifEmpty { "压缩包" }
            }
            "$dirName.zip"
        }
        var fileName by remember { mutableStateOf(defaultFileName) }

        val levelRange = CompressService.getLevelRange(selectedFormat)
        val defaultLevel = CompressService.getDefaultLevel(selectedFormat)

        LaunchedEffect(selectedFormat) {
            val baseName = if (compressEntries.size == 1) {
                compressEntries[0].name.substringBeforeLast(".")
            } else {
                val dirName = if (vm.focusedPanel == FocusedPanel.LEFT) {
                    vm.leftPath.substringAfterLast('/').ifEmpty { "压缩包" }
                } else {
                    vm.rightPath.substringAfterLast('/').ifEmpty { "压缩包" }
                }
                dirName
            }
            fileName = baseName + (suffixMap[selectedFormat] ?: ".zip")
            compressLevel = defaultLevel
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
                        Text(
                            text = if (compressEntries.size == 1) "创建压缩文件" else "创建压缩文件（${compressEntries.size} 个项目）",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // 文件名
                        Column {
                            Text("文件名", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                        // 格式 + 压缩级别
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("格式", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                            Text(selectedFormat, style = MaterialTheme.typography.bodyLarge)
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = showFormatDropdown,
                                        onDismissRequest = { showFormatDropdown = false }
                                    ) {
                                        formats.forEach { format ->
                                            DropdownMenuItem(
                                                text = { Text(format) },
                                                onClick = {
                                                    selectedFormat = format
                                                    showFormatDropdown = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            if (levelRange != null) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("压缩级别 (${levelRange.first}-${levelRange.last})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                                Text(if (compressLevel == 0) "0 (仅存储)" else compressLevel.toString(), style = MaterialTheme.typography.bodyLarge)
                                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                        DropdownMenu(
                                            expanded = showLevelDropdown,
                                            onDismissRequest = { showLevelDropdown = false }
                                        ) {
                                            levelRange.forEach { level ->
                                                DropdownMenuItem(
                                                    text = { Text(if (level == 0) "0 (仅存储)" else level.toString()) },
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

                        // 密码
                        Column {
                            Text("密码（不加密请留空）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextField(
                                value = compressPassword,
                                onValueChange = {
                                    compressPassword = it
                                    passwordError = when {
                                        it.contains('\u0000') -> "密码不能包含空字符"
                                        it.contains('\n') -> "密码不能包含换行符"
                                        it.contains('\r') -> "密码不能包含回车符"
                                        else -> null
                                    }
                                },
                                singleLine = true,
                                isError = passwordError != null,
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
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
                            if (passwordError != null) {
                                Text(
                                    passwordError!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 0.dp, top = 4.dp)
                                )
                            }
                        }

                        // 输出到另一面板
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("输出到另一面板路径", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Switch(
                                checked = compressOutputToOtherPanel,
                                onCheckedChange = { compressOutputToOtherPanel = it },
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        // ZIP 使用 AES-256 加密（默认关闭，使用 ZipCrypto）
                        if (selectedFormat == "zip") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("使用 AES-256 加密", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Switch(
                                    checked = compressUseAes,
                                    onCheckedChange = {
                                        if (compressPassword.isEmpty()) {
                                            showCompressPasswordHint = true
                                        } else {
                                            compressUseAes = it
                                            encSettings.setCompressUseAes(it)
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        // 7z 加密文件名（-mhe=on，隐藏文件列表）
                        if (selectedFormat == "7z") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("加密文件名", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Switch(
                                    checked = compressEncryptNames,
                                    onCheckedChange = {
                                        if (compressPassword.isEmpty()) {
                                            showCompressPasswordHint = true
                                        } else {
                                            compressEncryptNames = it
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        // 底部按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                showCompressDialog = false
                                compressEntries = emptyList()
                            }) {
                                Text("取消", color = MaterialTheme.colorScheme.primary)
                            }
                            val canConfirm = passwordError == null && fileName.isNotBlank()
                            TextButton(
                                onClick = {
                                    val outDir = if (compressOutputToOtherPanel) {
                                        if (vm.focusedPanel == FocusedPanel.LEFT) vm.rightPath else vm.leftPath
                                    } else {
                                        compressEntries[0].path.substringBeforeLast('/')
                                    }
                                    val outputPath = "$outDir/$fileName"

                                    showCompressDialog = false
                                    showCompressProgress = true
                                    compressProgress = 0f
                                    compressBytesProcessed = 0
                                    compressTotalBytes = 0
                                    compressCurrentFile = 0
                                    compressTotalFiles = 0

                                    vm.compress(
                                        entries = compressEntries,
                                        outputPath = outputPath,
                                        format = selectedFormat,
                                        level = compressLevel,
                                        password = compressPassword,
                                        useAes = compressUseAes,
                                        encryptNames = compressEncryptNames,
                                        onProgress = { info ->
                                            compressProgress = info.progress
                                            compressBytesProcessed = info.bytesProcessed
                                            compressTotalBytes = info.totalBytes
                                            compressCurrentFile = info.currentFile
                                            compressTotalFiles = info.totalFiles
                                        },
                                        onComplete = { success, path, error ->
                                            showCompressProgress = false
                                            if (success) {
                                                Toast.makeText(context, "压缩完成", Toast.LENGTH_SHORT).show()
                                                vm.refreshCurrent()
                                            } else {
                                                compressError = RuntimeException(error ?: "压缩失败")
                                            }
                                        }
                                    )
                                },
                                enabled = canConfirm
                            ) {
                                Text("确定", color = if (canConfirm) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 压缩加密需密码提示 ──
    if (showCompressPasswordHint) {
        AlertDialog(
            onDismissRequest = { showCompressPasswordHint = false },
            title = { Text("需要密码") },
            text = { Text("请先输入密码后再启用加密选项。") },
            confirmButton = {
                TextButton(onClick = { showCompressPasswordHint = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // ── 压缩进度面板 ──
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
                        Text(
                            text = "正在压缩...",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("压缩进度", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = if (compressTotalBytes > 0) "${FormatUtils.formatBytes(compressBytesProcessed)} / ${FormatUtils.formatBytes(compressTotalBytes)}" else "$compressCurrentFile/$compressTotalFiles",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                vm.cancelCompress()
                                showCompressProgress = false
                                compressProgress = 0f
                                compressCurrentFile = 0
                                compressTotalFiles = 0
                                compressBytesProcessed = 0
                                compressTotalBytes = 0
                            }) {
                                Text("取消", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 压缩错误弹窗 ──
    if (compressError != null) {
        ErrorDialog(
            error = compressError,
            onDismiss = { compressError = null }
        )
    }

    // ── 解压对话框 ──
    if (showExtractDialog && extractTargetEntries.isNotEmpty()) {
        val isDark = isSystemInDarkTheme()
        // 目标路径计算
        val firstEntry = extractTargetEntries.first()
        val archiveParent = firstEntry.path.substringBeforeLast('/')
        val strippedName = ArchiveBrowser.stripArchiveExtension(firstEntry.name)
        val currentDir = if (vm.focusedPanel == FocusedPanel.LEFT) vm.leftPath else vm.rightPath

        var extractMode by remember { mutableStateOf(0) }  // 0=压缩包所在文件夹, 1=当前文件夹

        LaunchedEffect(extractMode, extractTargetEntries) {
            extractOutputPath = when (extractMode) {
                0 -> "$archiveParent/$strippedName"
                1 -> currentDir
                else -> "$archiveParent/$strippedName"
            }
        }

        Dialog(onDismissRequest = { showExtractDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = if (extractTargetEntries.size == 1) "解压文件" else "解压文件（${extractTargetEntries.size} 个项目）",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // 解压模式选择
                        Text("解压方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = extractMode == 0, onClick = { extractMode = 0 })
                            Text("解压到压缩包所在文件夹", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { extractMode = 0 })
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = extractMode == 1, onClick = { extractMode = 1 })
                            Text("解压到当前文件夹", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { extractMode = 1 })
                        }

                        // 目标路径（可编辑）
                        OutlinedTextField(
                            value = extractOutputPath,
                            onValueChange = { extractOutputPath = it },
                            label = { Text("解压到") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )

                        // 按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                showExtractDialog = false
                                extractTargetEntries = emptyList()
                            }) {
                                Text("取消", color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = {
                                    val entries = extractTargetEntries
                                    val outputDir = extractOutputPath
                                    showExtractDialog = false
                                    showExtractProgress = true
                                    extractProgress = 0f
                                    extractBytesProcessed = 0
                                    extractTotalBytes = 0
                                    extractCurrentFile = 0
                                    extractTotalFiles = 0
                                    vm.extract(
                                        entries = entries,
                                        outputDir = outputDir,
                                        password = "",
                                        onPasswordRequired = {
                                            showExtractProgress = false
                                            extractPasswordInput = ""
                                            extractPasswordError = null
                                            showExtractPasswordDialog = true
                                        },
                                        onProgress = { info ->
                                            extractProgress = info.progress
                                            extractBytesProcessed = info.bytesProcessed
                                            extractTotalBytes = info.totalBytes
                                            extractCurrentFile = info.currentFile
                                            extractTotalFiles = info.totalFiles
                                        },
                                        onComplete = { success, outputPath, error ->
                                            showExtractProgress = false
                                            extractProgress = 0f
                                            extractBytesProcessed = 0
                                            extractTotalBytes = 0
                                            if (success && outputPath != null) {
                                                vm.refreshAfterExtract(outputPath)
                                            } else if (!success && error != null) {
                                                extractError = RuntimeException(error)
                                            }
                                        }
                                    )
                                },
                                enabled = extractOutputPath.isNotBlank()
                            ) {
                                Text("确定", color = if (extractOutputPath.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 解压密码输入弹窗 ──
    if (showExtractPasswordDialog) {
        val isDark = isSystemInDarkTheme()
        Dialog(onDismissRequest = { showExtractPasswordDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "此压缩包需要密码",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        OutlinedTextField(
                            value = extractPasswordInput,
                            onValueChange = { extractPasswordInput = it; extractPasswordError = null },
                            label = { Text("输入密码") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            isError = extractPasswordError != null,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        if (extractPasswordError != null) {
                            Text(
                                text = extractPasswordError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                showExtractPasswordDialog = false
                                extractPasswordInput = ""
                                extractPasswordError = null
                                vm.cancelExtract()
                            }) {
                                Text("取消", color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = {
                                val password = extractPasswordInput
                                showExtractPasswordDialog = false
                                showExtractProgress = true
                                extractProgress = 0f
                                extractBytesProcessed = 0
                                extractTotalBytes = 0
                                extractCurrentFile = 0
                                extractTotalFiles = 0
                                vm.extract(
                                    entries = extractTargetEntries,
                                    outputDir = extractOutputPath,
                                    password = password,
                                    onPasswordRequired = {
                                        showExtractProgress = false
                                        extractPasswordError = "密码错误"
                                        showExtractPasswordDialog = true
                                    },
                                    onProgress = { info ->
                                        extractProgress = info.progress
                                        extractBytesProcessed = info.bytesProcessed
                                        extractTotalBytes = info.totalBytes
                                        extractCurrentFile = info.currentFile
                                        extractTotalFiles = info.totalFiles
                                    },
                                    onComplete = { success, outputPath, error ->
                                        showExtractProgress = false
                                        extractProgress = 0f
                                        extractBytesProcessed = 0
                                        extractTotalBytes = 0
                                        if (success && outputPath != null) {
                                            vm.refreshAfterExtract(outputPath)
                                        } else if (!success && error != null) {
                                            extractError = RuntimeException(error)
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

    // ── 解压进度面板 ──
    if (showExtractProgress) {
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
                        Text(
                            text = "正在解压...",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("解压进度", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = if (extractTotalBytes > 0) "${FormatUtils.formatBytes(extractBytesProcessed)} / ${FormatUtils.formatBytes(extractTotalBytes)}" else "$extractCurrentFile/$extractTotalFiles",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { extractProgress },
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${(extractProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                vm.cancelExtract()
                                showExtractProgress = false
                                extractProgress = 0f
                                extractCurrentFile = 0
                                extractTotalFiles = 0
                                extractBytesProcessed = 0
                                extractTotalBytes = 0
                            }) {
                                Text("取消", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 解压错误弹窗 ──
    if (extractError != null) {
        ErrorDialog(
            error = extractError,
            onDismiss = { extractError = null }
        )
    }

    // ── 压缩包密码输入弹窗 ──
    val archivePwdEntry = vm.archivePasswordRequest
    if (archivePwdEntry != null) {
        com.whmdg.mczj.tools.auth.PasswordDialog(
            title = "输入压缩包密码",
            onDismiss = { vm.archivePasswordRequest = null },
            onVerify = { password ->
                vm.openArchiveWithPassword(archivePwdEntry, password)
            }
        )
    }
}

/** 计算指定面板的"返回上一级"路径，null 表示不显示。纯路径判断，与焦点无关。 */
private fun computeParentPath(
    currentPath: String,
    isInArchiveMode: Boolean,
    isAtArchiveRoot: () -> Boolean,
    isRecycleBinPanel: Boolean,
    isAtRecycleBinRoot: Boolean,
    recycleBinPath: String,
    isRootEngine: Boolean,
    canAccessPath: (String) -> Boolean
): String? {
    if (isInArchiveMode) {
        return if (isAtArchiveRoot()) null else "__archive_parent__"
    }
    if (isRecycleBinPanel) {
        if (isAtRecycleBinRoot) return null
        return java.io.File(recycleBinPath).parentFile?.absolutePath?.let { p ->
            if (try { java.io.File(p).canRead() } catch (_: Exception) { false }) p else null
        }
    }
    val effectiveRoot = if (isRootEngine) "/" else "/storage/emulated/0"
    if (currentPath != effectiveRoot && currentPath.contains('/')) {
        return currentPath.substringBeforeLast('/').ifEmpty { "/" }.let { p ->
            if (p != currentPath && canAccessPath(p)) p else null
        }
    }
    return null
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
    archiveSizeProvider: ((FileEntry) -> String)? = null,
    selectedPaths: Set<String> = emptySet(),
    onSwipeSelect: (FileEntry, Int) -> Unit = { _, _ -> },
    onToggleSelect: (FileEntry) -> Unit = {},
    extFlagsMap: Map<String, String> = emptyMap(),
    onVisibleRangeChanged: ((firstVisible: Int, lastVisible: Int) -> Unit)? = null,
    thumbnailLoader: ((FileEntry) -> ImageBitmap?)? = null
) {
    val isMultiSelectMode = selectedPaths.isNotEmpty()
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
            // 压缩包缩略图预加载：滚动停止时触发
            if (onVisibleRangeChanged != null) {
                LaunchedEffect(lazyListState.isScrollInProgress) {
                    if (!lazyListState.isScrollInProgress) {
                        val visible = lazyListState.layoutInfo.visibleItemsInfo
                        if (visible.isNotEmpty()) {
                            val first = visible.first().index
                            val last = visible.last().index
                            onVisibleRangeChanged(first, last)
                        }
                    }
                }
            }

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
                    val entryIndex = entries.indexOfFirst { it.path == entry.path }
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
                    val thumb = thumbnailLoader?.invoke(entry)
                    FileEntryRow(
                        entry = entry,
                        isFocused = isFocused,
                        isSelected = entry.path in selectedPaths,
                        onClick = {
                            if (isMultiSelectMode) {
                                onToggleSelect(entry)
                            } else {
                                if (entry.isDirectory) onFolderClick(entry)
                                else onFileClick(entry)
                            }
                        },
                        onLongClick = {
                            if (isMultiSelectMode) {
                                // 多选模式下：仅长按已选中项才弹工具栏
                                if (entry.path in selectedPaths) onLongClick(entry)
                            } else {
                                onLongClick(entry)
                            }
                        },
                        onSwipe = { onSwipeSelect(entry, entryIndex) },
                        folderSize = dirSize,
                        extFlags = extFlagsMap[entry.name] ?: "",
                        thumbnail = thumb
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
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipe: (Float) -> Unit = {},
    folderSize: String = "",
    extFlags: String = "",
    thumbnail: ImageBitmap? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val swipeOffset = remember { Animatable(0f) }
    var rowWidth by remember { mutableIntStateOf(0) }
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                  else Color.Transparent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowWidth = it.size.width }
            .clipToBounds()
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId = down.id
                        var cumulativeX = 0f
                        var cumulativeY = 0f
                        val slop = 30f // 最小水平位移，避免误触
                        var started = false
                        var rejected = false // 垂直意图大于水平，放弃横向手势

                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (change.pressed) {
                                val dx = change.position.x - change.previousPosition.x
                                val dy = change.position.y - change.previousPosition.y
                                // 忽略多指触控
                                if (event.changes.count { it.pressed } > 1) {
                                    change.consume()
                                    break
                                }
                                if (!started && !rejected) {
                                    cumulativeX += dx
                                    cumulativeY += dy
                                    // 垂直位移超过水平位移 → 用户在上下滑动，不触发横向手势
                                    if (abs(cumulativeY) > abs(cumulativeX) && abs(cumulativeY) > slop) {
                                        rejected = true
                                    } else if (abs(cumulativeX) >= slop) {
                                        started = true
                                        change.consume()
                                        coroutineScope.launch {
                                            val maxOffset = rowWidth * 0.5f
                                            swipeOffset.snapTo((cumulativeX).coerceIn(-maxOffset, maxOffset))
                                        }
                                    }
                                } else if (started) {
                                    change.consume()
                                    coroutineScope.launch {
                                        val maxOffset = rowWidth * 0.5f
                                        swipeOffset.snapTo((swipeOffset.value + dx).coerceIn(-maxOffset, maxOffset))
                                    }
                                }
                            } else {
                                // 手指抬起
                                if (started) {
                                    change.consume()
                                    coroutineScope.launch {
                                        val threshold = rowWidth * 0.25f
                                        if (abs(swipeOffset.value) >= threshold) {
                                            onSwipe(swipeOffset.value)
                                        }
                                        swipeOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                    }
                                }
                                break
                            }
                        }
                    }
                }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.weight(0.5f))
            Column(modifier = Modifier.weight(9f)) {
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
                        if (thumbnail != null) {
                            // 压缩包内图片：使用缓存的缩略图
                            Image(
                                painter = BitmapPainter(thumbnail),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            val imagePlaceholder = getFileTypeDrawableRes(category)
                            val density = LocalDensity.current
                            val px36 = with(density) { 36.dp.roundToPx() }
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(entry.path)
                                    .size(CoilSize(px36, px36))
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Crop,
                                placeholder = imagePlaceholder?.let { painterResource(it) },
                                error = imagePlaceholder?.let { painterResource(it) }
                            )
                        }
                    } else {
                        val fileDrawableRes = if (!entry.isDirectory && entry.name != "返回上一级") {
                            getFileTypeDrawableRes(category)
                        } else null

                        if (fileDrawableRes != null) {
                            Icon(
                                painter = painterResource(fileDrawableRes),
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = Color.Unspecified
                            )
                        } else if (!entry.isDirectory && entry.name != "返回上一级"
                            && category == FileCategory.APK) {
                            FileTypeIcon(
                                filename = entry.name,
                                filePath = entry.path,
                                iconSize = 36.dp
                            )
                        } else {
                            val appIconBitmap = if (entry.isDirectory && entry.name != "返回上一级") {
                                val parentPath = File(entry.path).parent
                                if (parentPath != null && AppIconHelper.isAppPackageDir(parentPath, entry.name)) {
                                    AppIconHelper.getAppIconBitmap(context, entry.name)
                                } else null
                            } else null

                            if (appIconBitmap != null) {
                                Image(
                                    painter = BitmapPainter(appIconBitmap),
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = when {
                                        entry.name == "返回上一级" -> Icons.Default.ArrowUpward
                                        entry.isDirectory -> Icons.Default.Folder
                                        else -> Icons.Default.InsertDriveFile
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = if (isFocused) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Text(
                    text = entry.name,
                    modifier = Modifier.weight(4f).align(Alignment.CenterVertically),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = true,
                    textAlign = TextAlign.Start,
                    fontSize = 13.sp,
                )
            }
            // Bottom 3/10: date/permission (left, aligned to icon left) + size (right, aligned to filename right)
            Row(
                modifier = Modifier.weight(3f).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                val label = when {
                    entry.isDirectory -> compactDate(entry.lastModified)
                    entry.permission.isNotEmpty() -> {
                        if (extFlags.isNotEmpty()) "${entry.permission} $extFlags"
                        else entry.permission
                    }
                    extFlags.isNotEmpty() -> extFlags
                    else -> ""
                }
                Text(
                    text = label,
                    fontSize = 11.sp,
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
                        fontSize = 11.sp,
                        color = if (rightLabel == "✕") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            } // inner Column (weight 9f)
            Spacer(modifier = Modifier.weight(0.5f))
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
