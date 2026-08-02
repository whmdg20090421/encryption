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
import com.whmdg.mczj.tools.ui.theme.DialogWidthFraction
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
import com.whmdg.mczj.tools.encryption.services.VaultSession
import com.whmdg.mczj.tools.fileop.FileOperationManager
import com.whmdg.mczj.tools.fileop.DeleteEntry
import com.whmdg.mczj.tools.fileop.webdav.WebDavServerStore
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
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

enum class FocusedPanel { LEFT, RIGHT;
    val index: Int get() = ordinal
}

/** FocusedPanel → PanelId 桥接（UI 层使用，不传入 Controller） */
val FocusedPanel.panelId: PanelId
    get() = if (this == FocusedPanel.LEFT) PanelId.LEFT else PanelId.RIGHT
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

/** 滑动选择 UI 状态（纯 Compose 交互状态，不存 ViewModel） */
class SwipeUiState {
    var selectFlag by mutableIntStateOf(0)   // 1=刚滑动选中，等待范围选中
    var lastIndex by mutableIntStateOf(-1)
}

@OptIn(ExperimentalMaterial3Api::class)
// 系统文件管理器（FileManagerScreen）—— 不要与 VaultOpenScreen（保险箱文件浏览器）混淆
@Composable
fun FileManagerScreen(
    onBack: () -> Unit,
    onNavigate: (Screen) -> Unit = {},
    vaultSession: VaultSession? = null,
    onVaultSaveReady: (((String) -> Unit) -> Unit)? = null
) {
    val context = LocalContext.current
    val vm: FileManagerViewModel = viewModel()
    val encSettings = remember { EncryptionSettings(context) }

    // vault 模式初始化
    LaunchedEffect(vaultSession) {
        if (vaultSession != null) {
            vm.initVaultMode(vaultSession)
            vm.onNavigateVault = { screen -> onNavigate(screen) }
        }
    }

    // vault 模式保存回调注册
    LaunchedEffect(vm.vaultSession, onVaultSaveReady) {
        if (vm.vaultSession != null && onVaultSaveReady != null) {
            onVaultSaveReady { content ->
                vm.handleVaultTextSave(content)
            }
        }
    }

    var hasStoragePermission by remember {
        mutableStateOf(Environment.isExternalStorageManager())
    }
    val coroutineScope = rememberCoroutineScope()

    // ── 诊断状态（Debug 模式） ──
    val isDebugMode = remember { isDebugAuth(context) }
    var diagnosticMessage by remember { mutableStateOf<String?>(null) }
    var diagnosticError by remember { mutableStateOf<Throwable?>(null) }

    // ── 滚动状态（按面板索引：0=左, 1=右） ──
    val listStates = listOf(rememberLazyListState(), rememberLazyListState())

    // ── UI 本地状态 ──
    var showDrawer by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var tempSortField by remember { mutableStateOf(vm.sortField) }
    var tempSortOrder by remember { mutableStateOf(vm.sortOrder) }
    var showSortSizeRefreshDialog by remember { mutableStateOf(false) }
    var unmeasuredDirs by remember { mutableStateOf(listOf<FileEntry>()) }
    var selectedEntry by remember { mutableStateOf<FileEntry?>(null) }
    // ── 多选状态（按面板索引：0=左, 1=右） ──
    val swipeStates = listOf(remember { SwipeUiState() }, remember { SwipeUiState() })
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
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var hideToolbarForDelete by remember { mutableStateOf(false) }
    var showDeleteProgress by remember { mutableStateOf(false) }
    // ── 复制/移动确认对话框 ──
    var showCopyMoveConfirmDialog by remember { mutableStateOf(false) }
    var copyMoveConfirmIsCopy by remember { mutableStateOf(true) }
    var copyMoveConfirmSourcePaths by remember { mutableStateOf(listOf<String>()) }
    var copyMoveConfirmTargetDir by remember { mutableStateOf("") }
    var showFileOpProgress by remember { mutableStateOf(false) }
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
    // 统一阻断错误弹窗
    var showBlockErrors by remember { mutableStateOf(false) }
    var blockErrorMessages by remember { mutableStateOf<List<String>>(emptyList()) }
    // 权限验证失败重试弹窗
    var showVerifyFailed by remember { mutableStateOf(false) }
    var verifyFailedPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var verifyRetryCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
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
    var showVaultExitDialog by remember { mutableStateOf(false) }

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
    LaunchedEffect(vm.pendingScrollToFile, vm.左.entries, vm.右.entries, vm.左.path, vm.右.path) {
        val targetName = vm.pendingScrollToFile ?: return@LaunchedEffect
        val entries = vm.currentPanel.entries
        val listState = if (vm.focusedPanel == FocusedPanel.LEFT) listStates[0] else listStates[1]
        val index = entries.indexOfFirst { !it.isDirectory && it.name == targetName }
        if (index >= 0) {
            listState.scrollToItem(index)
            vm.currentPanel.pendingScrollToFile = null
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
        val listState = if (vm.focusedPanel == FocusedPanel.LEFT) listStates[0] else listStates[1]
        listState.scrollToItem(index, offset)
        vm.currentPanel.pendingScrollTo = null
    }

    // ── 持续同步滚动位置到 ViewModel（供 refreshCurrent 读取） ──
    LaunchedEffect(listStates[0]) {
        snapshotFlow { listStates[0].firstVisibleItemIndex to listStates[0].firstVisibleItemScrollOffset }
            .collect { (idx, off) -> vm.左.currentScrollIndex = idx; vm.左.currentScrollOffset = off }
    }
    LaunchedEffect(listStates[1]) {
        snapshotFlow { listStates[1].firstVisibleItemIndex to listStates[1].firstVisibleItemScrollOffset }
            .collect { (idx, off) -> vm.右.currentScrollIndex = idx; vm.右.currentScrollOffset = off }
    }

    // 保存当前滚动位置并返回上一级（统一入口：工具栏按钮、列表条目、系统返回手势共用）
    val saveScrollAndGoUp: () -> Boolean = {
        if (vm.isAtVaultRoot()) {
            showVaultExitDialog = true
            true
        } else {
            listStates[vm.focusedPanel.index].let { _s -> vm.saveScrollPosition(_s.firstVisibleItemIndex, _s.firstVisibleItemScrollOffset) }
            val targetPath = vm.goUp()
            if (targetPath != null) {
                // 导航时清空当前面板的多选状态
                if (vm.focusedPanel == FocusedPanel.LEFT) {
                    vm.左.selectedPaths = emptySet(); swipeStates[0].selectFlag = 0; swipeStates[0].lastIndex = -1
                } else {
                    vm.右.selectedPaths = emptySet(); swipeStates[1].selectFlag = 0; swipeStates[1].lastIndex = -1
                }
                val saved = vm.getScrollPosition(targetPath)
                vm.navigateToWithScroll(targetPath, saved?.first ?: 0, saved?.second ?: 0)
                true
            } else {
                false
            }
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
    DisposableEffect(showVaultExitDialog) {
        if (showVaultExitDialog) registerOverlay("vaultExit") { showVaultExitDialog = false }
        else unregisterOverlay("vaultExit")
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
        if (!saveScrollAndGoUp()) {
            // 在根目录按返回：压缩包/回收站退出模式，其余退出文件管理器
            if (vm.isInArchiveMode) {
                vm.exitArchive()
            } else if (vm.recycleBinPanel == vm.focusedPanel) {
                vm.exitRecycleBin()
            } else {
                if (vm.isVaultMode) vm.exitVaultMode()
                onBack()
            }
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
                    IconButton(onClick = {
                        if (vm.isVaultMode) vm.exitVaultMode()
                        onBack()
                    }) {
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
                                    val entriesToSize = vm.currentPanel.entries
                                    val dirs = entriesToSize.filter { it.isDirectory }
                                    if (dirs.isEmpty()) {
                                        Toast.makeText(context, "当前列表没有文件夹", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val parentPath = vm.currentPanel.path
                                        vm.calculateFolderSizeAsync(parentPath)
                                    }
                                }
                            )
                            HorizontalDivider()
                            // 调整字体大小
                            DropdownMenuItem(
                                text = { Text("调整字体大小") },
                                trailingIcon = {
                                    Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showFontSizeDialog = true
                                    showSettingsMenu = false
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
                val activePanel = vm.currentPanel
                val activeSelectedPaths = activePanel.selectedPaths
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
                                            vm.currentPanel.pendingScrollTo = Triple(targetPath, saved?.first ?: 0, saved?.second ?: 0)
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
                                        vm.currentPanel.pendingScrollTo = Triple(targetPath, saved?.first ?: 0, saved?.second ?: 0)
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
                            } else if (vm.isVaultMode) {
                                val effectiveRoot = if (vm.isRootEngine) "/" else "/storage/emulated/0"
                                val parentPath = vm.currentPath.substringBeforeLast('/').ifEmpty { "/" }
                                vm.currentPath != effectiveRoot
                                    && vm.currentPath.contains('/')
                                    && parentPath != vm.currentPath
                            } else {
                                val effectiveRoot = if (vm.isRootEngine) "/" else "/storage/emulated/0"
                                val parentPath = vm.currentPath.substringBeforeLast('/').ifEmpty { "/" }
                                vm.currentPath != effectiveRoot
                                    && vm.currentPath.contains('/')
                                    && parentPath != vm.currentPath
                            }

                            IconButton(
                                onClick = { saveScrollAndGoUp() },
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
                                        val entries = activePanel.entries
                                        val allPaths = entries.map { it.path }.toSet()
                                        activePanel.selectedPaths = allPaths
                                    },
                                    onLongClick = {
                                        // 长按：按已选类型全选
                                        val entries = activePanel.entries
                                        val currentPaths = activePanel.selectedPaths
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
                                        activePanel.selectedPaths = newPaths
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
                                        activePanel.selectedPaths = emptySet()
                                        val swipeIdx = if (activePanel === vm.左) 0 else 1
                                        swipeStates[swipeIdx].selectFlag = 0; swipeStates[swipeIdx].lastIndex = -1
                                    },
                                    onLongClick = {
                                        // 长按：按已选类型反选
                                        val entries = activePanel.entries
                                        val currentPaths = activePanel.selectedPaths
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
                                        activePanel.selectedPaths = newPaths
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
                                activePanel.selectedPaths = emptySet()
                                val swipeIdx = if (activePanel === vm.左) 0 else 1
                                swipeStates[swipeIdx].selectFlag = 0; swipeStates[swipeIdx].lastIndex = -1
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
                                val entries = activePanel.entries
                                val currentPaths = activePanel.selectedPaths
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
                                    activePanel.selectedPaths = currentPaths + matchingPaths
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
                                val currentPaths = activePanel.selectedPaths
                                val entries = activePanel.entries
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
                    // 按面板索引计算父路径
                    val parentPaths = FocusedPanel.entries.map { side ->
                        val panel = vm.panels[side.panelId].state
                        computeParentPath(
                            currentPath = panel.path,
                            isInArchiveMode = panel.isInArchiveMode,
                            isAtArchiveRoot = { vm.isAtArchiveRoot() },
                            isRecycleBinPanel = vm.recycleBinPanel == side,
                            isAtRecycleBinRoot = vm.isAtRecycleBinRoot,
                            recycleBinPath = panel.recycleBinPath,
                            isRootEngine = vm.isRootEngine,
                            isVaultMode = vm.isVaultMode,
                            vaultRootPath = vm.vaultSession?.vaultDir?.absolutePath
                        )
                    }

                    val activePanel = vm.currentPanel
                    val currentFontSize = vm.fileNameFontSize  // ponytail: 在 Layout 外读取，使状态变化触发重组
                    Layout(
                        modifier = Modifier.fillMaxSize(),
                        content = {
                            FocusedPanel.entries.forEach { side ->
                                val panel = vm.panels[side.panelId].state
                                val idx = side.index
                                FileBrowserPanel(
                                    entries = panel.entries,
                                    isFocused = vm.focusedPanel == side,
                                    currentPath = panel.path,
                                    isLeftPanel = side == FocusedPanel.LEFT,
                                    onFocus = { vm.focusedPanel = side },
                                    onFolderClick = { entry ->
                                        vm.focusedPanel = side
                                        if (panel.isInArchiveMode) {
                                            vm.navigateInArchive(entry)
                                        } else if (vm.recycleBinPanel == vm.focusedPanel) {
                                            vm.navigateInRecycleBin(entry)
                                        } else {
                                            DiagnosticLog.beginSession("[$side] 点击文件夹 '${entry.name}'")
                                            DiagnosticLog.log("FileMgr", "[$side] 点击文件夹 name='${entry.name}' path='${entry.path}' from=${panel.path}")
                                            listStates[vm.focusedPanel.index].let { _s -> vm.saveScrollPosition(_s.firstVisibleItemIndex, _s.firstVisibleItemScrollOffset) }
                                            if (panel.isWebDavMode) {
                                                vm.navigateToWebDavFolder(entry.name)
                                            } else {
                                                vm.navigateToFolder(entry)
                                            }
                                            panel.selectedPaths = emptySet(); swipeStates[idx].selectFlag = 0; swipeStates[idx].lastIndex = -1
                                        }
                                    },
                                    onFileClick = { entry ->
                                        if (panel.isInArchiveMode) {
                                            DiagnosticLog.beginSession("[$side] 压缩包内点击文件 '${entry.name}'")
                                            DiagnosticLog.log("FileMgr", "[$side] 压缩包内文件 name='${entry.name}'")
                                            vm.focusedPanel = side
                                            coroutineScope.launch {
                                                val screen = vm.openArchiveFile(context, entry)
                                                if (screen != null) {
                                                    listStates[vm.focusedPanel.index].let { _s -> vm.saveScrollPosition(_s.firstVisibleItemIndex, _s.firstVisibleItemScrollOffset) }
                                                    onNavigate(screen)
                                                } else {
                                                    Toast.makeText(context, "文件提取失败", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            return@FileBrowserPanel
                                        }
                                        DiagnosticLog.beginSession("[$side] 点击文件 '${entry.name}'")
                                        DiagnosticLog.log("FileMgr", "[$side] 点击文件 name='${entry.name}' path='${entry.path}'")
                                        vm.focusedPanel = side
                                        val screen = vm.openFile(context, entry, isDebugMode)
                                        if (screen != null) {
                                            listStates[vm.focusedPanel.index].let { _s -> vm.saveScrollPosition(_s.firstVisibleItemIndex, _s.firstVisibleItemScrollOffset) }
                                            onNavigate(screen)
                                        }
                                        vm.addHistory(entry.name, entry.path, false)
                                    },
                                    onLongClick = { entry ->
                                        selectedEntry = entry
                                        vm.focusedPanel = side
                                    },
                                    modifier = Modifier,
                                    folderSizeDb = vm.folderSizeDb,
                                    parentPath = parentPaths[idx],
                                    lazyListState = listStates[idx],
                                    onNavigateUp = {
                                        vm.focusedPanel = side
                                        saveScrollAndGoUp()
                                    },
                                    archiveSizeProvider = if (panel.isInArchiveMode) { entry ->
                                        if (entry.compressedSize > 0 || entry.size > 0)
                                            "${compactSize(entry.compressedSize)}(${compactSize(entry.size)})"
                                        else "--"
                                    } else null,
                                    onVisibleRangeChanged = null,
                                    thumbnailLoader = null,
                                    selectedPaths = panel.selectedPaths,
                                    onSwipeSelect = { entry, index ->
                                        vm.focusedPanel = side
                                        if (panel.selectedPaths.size == 1 && entry.path in panel.selectedPaths) {
                                            panel.selectedPaths = emptySet(); swipeStates[idx].selectFlag = 0; swipeStates[idx].lastIndex = -1
                                        } else if (panel.selectedPaths.isEmpty()) {
                                            panel.selectedPaths = setOf(entry.path)
                                            swipeStates[idx].selectFlag = 1
                                            swipeStates[idx].lastIndex = index
                                        } else if (swipeStates[idx].selectFlag == 1) {
                                            val from = minOf(swipeStates[idx].lastIndex, index)
                                            val to = maxOf(swipeStates[idx].lastIndex, index)
                                            val rangePaths = panel.entries.subList(from, to + 1).map { it.path }.toSet()
                                            panel.selectedPaths = panel.selectedPaths + rangePaths
                                            swipeStates[idx].selectFlag = 0
                                        } else {
                                            panel.selectedPaths = panel.selectedPaths + entry.path
                                            swipeStates[idx].selectFlag = 1
                                            swipeStates[idx].lastIndex = index
                                        }
                                    },
                                    onToggleSelect = { entry ->
                                        panel.selectedPaths = if (entry.path in panel.selectedPaths) {
                                            panel.selectedPaths - entry.path
                                        } else {
                                            panel.selectedPaths + entry.path
                                        }
                                        if (panel.selectedPaths.isEmpty()) {
                                            swipeStates[idx].selectFlag = 0
                                            swipeStates[idx].lastIndex = -1
                                        }
                                    },
                                    extFlagsMap = panel.extFlagsMap,
                                    fileNameFontSize = currentFontSize
                                )
                            }
                        }
                    ) { measurables, constraints ->
                        val halfWidth = constraints.maxWidth / 2
                        val panelConstraints = constraints.copy(minWidth = halfWidth, maxWidth = halfWidth)
                        val leftPlaceable = measurables[0].measure(panelConstraints)
                        val rightPlaceable = measurables[1].measure(panelConstraints)
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            // 先绘制非聚焦面板，再绘制聚焦面板（聚焦的在上层）
                            if (activePanel === vm.左) {
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
                                // ── 本地存储 + 快捷访问 ──
                                val barColor = if (isSystemInDarkTheme()) Color(0xFF00838F) else Color(0xFF00BCD4)
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        // 内部储存
                                        val internalStat = try { StatFs(Environment.getDataDirectory().path) } catch (_: Exception) { null }
                                        if (internalStat != null) {
                                            val total = internalStat.totalBytes
                                            val available = internalStat.availableBytes
                                            val used = total - available
                                            val progress = if (total > 0) used.toFloat() / total.toFloat() else 0f
                                            Column(
                                                modifier = Modifier.fillMaxWidth().clickable { vm.navigateToWithScroll("/storage/emulated/0/"); showDrawer = false }.padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Text("内部储存", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                                Spacer(Modifier.height(4.dp))
                                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = barColor, trackColor = barColor.copy(alpha = 0.2f))
                                                Spacer(Modifier.height(2.dp))
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text("${compactSize(used)} / ${compactSize(total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Text("%.1f%%".format(progress * 100), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                        // 根目录
                                        val rootStat = try { StatFs("/") } catch (_: Exception) { null }
                                        if (rootStat != null) {
                                            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                                            val total = rootStat.totalBytes
                                            val available = rootStat.availableBytes
                                            val used = total - available
                                            val progress = if (total > 0) used.toFloat() / total.toFloat() else 0f
                                            Column(
                                                modifier = Modifier.fillMaxWidth().clickable { vm.navigateToWithScroll("/"); showDrawer = false }.padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Text("根目录", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                                Spacer(Modifier.height(4.dp))
                                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = barColor, trackColor = barColor.copy(alpha = 0.2f))
                                                Spacer(Modifier.height(2.dp))
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text("${compactSize(used)} / ${compactSize(total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Text("%.1f%%".format(progress * 100), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                        // 自定义快捷访问
                                        quickAccessList.forEach { entry ->
                                            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth().clickable { vm.navigateToWithScroll(entry.path); showDrawer = false }.padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Spacer(Modifier.width(8.dp))
                                                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                        // WebDAV 快捷访问
                                        webDavServers.forEach { server ->
                                            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth().clickable { vm.navigateToWebDav(server); showDrawer = false }.padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                                Spacer(Modifier.width(8.dp))
                                                Text(server.name.ifEmpty { server.getDefaultName() }, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                        // 添加快捷访问
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clickable { showQaTypeSelector = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = "添加快捷访问", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(8.dp))
                                            Text("添加快捷访问", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
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
                val activeSelectedPaths = vm.currentPanel.selectedPaths
                val isMultiSelect = activeSelectedPaths.size > 1
                val selectedEntries = vm.currentPanel.entries.filter { it.path in vm.currentPanel.selectedPaths }

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
                                    vm.左.selectedPaths = emptySet(); swipeStates[0].selectFlag = 0; swipeStates[0].lastIndex = -1
                                } else {
                                    vm.右.selectedPaths = emptySet(); swipeStates[1].selectFlag = 0; swipeStates[1].lastIndex = -1
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(DialogWidthFraction)
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
                                                vm.currentPanel.selectedPaths = emptySet()
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
                                            val targetDir = vm.otherPanel.path
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
                                            val targetDir = vm.otherPanel.path
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

                            // ── 第四行：大小刷新(仅文件夹) / 属性 ──
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
                                            selectedEntry = null
                                            coroutineScope.launch {
                                                propertyData = vm.getPropertyData(entry)
                                                propertyEntry = entry
                                                showPropertyDialog = true
                                                // 异步统计文件/文件夹数量
                                                if (entry.isDirectory) {
                                                    val (folders, files) = vm.countFilesInFolder(entry.path)
                                                    propertyData = propertyData?.copy(fileCount = files, folderCount = folders)
                                                }
                                            }
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
        StandardDialog(
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

    // ── 软链接提示对话框 ──
    vm.pendingSymlinkEntry?.let { entry ->
        StandardDialog(
            onDismissRequest = { vm.pendingSymlinkEntry = null },
            title = { Text("暂不支持软链接跳转") },
            text = { Text("「${entry.name}」是软链接，当前版本暂不支持跳转到目标路径。") },
            confirmButton = {
                TextButton(onClick = { vm.pendingSymlinkEntry = null }) {
                    Text("关闭")
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
        StandardDialog(
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
        StandardDialog(
            onDismissRequest = { vm.currentPanel.archiveDebugInfo = null },
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
                OutlinedButton(onClick = { vm.currentPanel.archiveDebugInfo = null }) {
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
        StandardDialog(
            onDismissRequest = { vm.currentPanel.archiveOpenError = null },
            title = { Text("无法打开压缩包") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { vm.currentPanel.archiveOpenError = null }) {
                    Text("确定")
                }
            }
        )
    }

    // ── 强行打开失败详情 ──
    if (forceOpenError != null) {
        StandardDialog(
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
    CreateTypeDialog(
        show = showCreateTypeDialog,
        onDismiss = { showCreateTypeDialog = false },
        onSelect = { mode ->
            createMode = mode
            showCreateTypeDialog = false
            showNameDialog = true
        }
    )

    // ── 名称输入对话框 ──
    NameInputDialog(
        show = showNameDialog,
        createMode = createMode,
        onDismiss = { showNameDialog = false },
        onConfirm = { name ->
            if (name.isBlank()) return@NameInputDialog
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
        }
    )

    // ── 重命名对话框 ──
    RenameDialog(
        show = showRenameDialog && selectedEntry != null,
        currentName = selectedEntry?.name ?: "",
        onDismiss = { showRenameDialog = false },
        onConfirm = { newName ->
            val entry = selectedEntry ?: return@RenameDialog
            val error = vm.renameEntry(entry, newName)
            if (error == null) {
                Toast.makeText(context, "重命名成功", Toast.LENGTH_SHORT).show()
                vm.refreshBoth()
            } else {
                Toast.makeText(context, "重命名失败: $error", Toast.LENGTH_SHORT).show()
            }
            showRenameDialog = false
            selectedEntry = null
        }
    )

    // ── 删除确认对话框 ──
    val delMultiSelect = vm.currentPanel.selectedPaths.size > 1
    val delSelectedEntries = vm.currentPanel.entries.filter { it.path in vm.currentPanel.selectedPaths }
    DeleteConfirmDialog(
        show = showDeleteDialog && (selectedEntry != null || delMultiSelect),
        isMultiDel = delMultiSelect,
        delCount = delSelectedEntries.size,
        entryName = selectedEntry?.name ?: "",
        onDismiss = { showDeleteDialog = false; hideToolbarForDelete = false },
        onConfirm = { recycleBinEnabled ->
            showDeleteDialog = false
            showDeleteProgress = true
            val deleteEntries = if (delMultiSelect) {
                delSelectedEntries.map { entry ->
                    DeleteEntry(entry.path, entry.name, entry.isDirectory, entry.size)
                }
            } else {
                val entry = selectedEntry ?: return@DeleteConfirmDialog
                listOf(DeleteEntry(entry.path, entry.name, entry.isDirectory, entry.size))
            }
            val accessLevel = when {
                vm.isRootEngine -> FileAccessLevel.ROOT
                com.whmdg.mczj.tools.security.SpecialPermissionVerifier.isShizukuAuthorized(context) -> FileAccessLevel.SHIZUKU
                else -> FileAccessLevel.NORMAL
            }
            FileOperationManager.delete(deleteEntries, recycleBinEnabled, accessLevel, context)
            if (vm.focusedPanel == FocusedPanel.LEFT) {
                vm.左.selectedPaths = emptySet(); swipeStates[0].selectFlag = 0; swipeStates[0].lastIndex = -1
            } else {
                vm.右.selectedPaths = emptySet(); swipeStates[1].selectFlag = 0; swipeStates[1].lastIndex = -1
            }
        }
    )

    // ── 删除进度对话框 ──
    DeleteProgressDialog(
        show = showDeleteProgress,
        isMultiDel = vm.currentPanel.selectedPaths.size > 1,
        entryName = selectedEntry?.name ?: "",
        onAutoDismiss = {
            showDeleteProgress = false
            selectedEntry = null
            hideToolbarForDelete = false
            vm.refreshBoth()
        }
    )

    // ── 复制/移动确认对话框 ──
    CopyMoveConfirmDialog(
        show = showCopyMoveConfirmDialog,
        isCopy = copyMoveConfirmIsCopy,
        sourcePaths = copyMoveConfirmSourcePaths,
        targetDir = copyMoveConfirmTargetDir,
        onDismiss = { showCopyMoveConfirmDialog = false },
        onConfirm = {
            showCopyMoveConfirmDialog = false
            val accessLevel = when {
                vm.isRootEngine -> FileAccessLevel.ROOT
                com.whmdg.mczj.tools.security.SpecialPermissionVerifier.isShizukuAuthorized(context) -> FileAccessLevel.SHIZUKU
                else -> FileAccessLevel.NORMAL
            }
            if (copyMoveConfirmIsCopy) {
                FileOperationManager.copy(copyMoveConfirmSourcePaths, copyMoveConfirmTargetDir, accessLevel, context, isDebugMode)
            } else {
                FileOperationManager.move(copyMoveConfirmSourcePaths, copyMoveConfirmTargetDir, accessLevel, context, isDebugMode)
            }
            showFileOpProgress = true
            selectedEntry = null
            vm.左.selectedPaths = emptySet()
            vm.右.selectedPaths = emptySet()
        }
    )

    // ── 复制/移动进度对话框 ──
    CopyMoveProgressDialog(
        show = showFileOpProgress,
        isDebugMode = isDebugMode,
        onDismiss = { showFileOpProgress = false },
        onCancel = { FileOperationManager.cancelHard() },
        onExtractReport = {
            val diag = com.whmdg.mczj.tools.fileop.FileOpDiagnostics
            val summary = diag.exportSummary()
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("file_op_diag", summary))
            try {
                val diagDir = com.whmdg.mczj.tools.AppDataPaths.diagnostics(context)
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                val file = java.io.File(diagDir, "file_op_report_$timestamp.log")
                file.writeText(diag.export())
                android.widget.Toast.makeText(context, "报告已保存: ${file.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    )

    // ── 文件操作冲突/错误弹窗 ──
    FileConflictDialog()
    FileErrorDialog()

    // ── 强制删除确认对话框（移动到回收站失败时） ──
    ForceDeleteDialog(
        show = showForceDeleteDialog && forceDeleteEntry != null,
        entryName = forceDeleteEntry?.name ?: "",
        onDismiss = { showForceDeleteDialog = false; forceDeleteEntry = null },
        onConfirm = {
            val entry = forceDeleteEntry ?: return@ForceDeleteDialog
            val error = vm.deleteEntry(entry)
            if (error == null) {
                Toast.makeText(context, "删除成功", Toast.LENGTH_SHORT).show()
                vm.refreshBoth()
            } else {
                Toast.makeText(context, "删除失败: $error", Toast.LENGTH_SHORT).show()
            }
            showForceDeleteDialog = false
            forceDeleteEntry = null
        }
    )

    // ── 回收站永久删除确认对话框 ──
    PermanentDeleteDialog(
        show = showPermanentDeleteDialog && (permanentDeleteTarget != null || permanentDeleteMultiNames.isNotEmpty()),
        isMultiDelete = permanentDeleteMultiNames.isNotEmpty(),
        count = permanentDeleteMultiNames.size,
        targetName = permanentDeleteTarget ?: "",
        onDismiss = { showPermanentDeleteDialog = false; permanentDeleteTarget = null; permanentDeleteMultiNames = emptyList() },
        onConfirm = {
            if (permanentDeleteMultiNames.isNotEmpty()) {
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
                vm.currentPanel.selectedPaths = emptySet()
            } else {
                val name = permanentDeleteTarget ?: return@PermanentDeleteDialog
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
        }
    )

    // ── 保险箱退出确认对话框 ──
    if (showVaultExitDialog) {
        StandardDialog(
            onDismissRequest = { showVaultExitDialog = false },
            title = { Text("离开加密保险箱") },
            text = { Text("你将离开加密保险箱，重新进入需要重新从加密入口进入，密钥将会被销毁。") },
            confirmButton = {
                TextButton(onClick = {
                    showVaultExitDialog = false
                    val parentPath = vm.goUp()
                    vm.exitVaultMode()
                    if (parentPath != null) {
                        vm.navigateTo(parentPath)
                    }
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showVaultExitDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 添加快捷访问对话框 ──
    AddQuickAccessDialog(
        show = showAddQaDialog,
        existingNames = quickAccessList.map { it.name },
        isPathValid = { path -> vm.isDirectoryShell(path) },
        onDismiss = { showAddQaDialog = false },
        onConfirm = { name, path ->
            quickAccessList = quickAccessList + QuickAccessEntry(name, path)
            saveQuickAccess()
            showAddQaDialog = false
        }
    )

    // ── 添加快捷访问类型选择 ──
    if (showQaTypeSelector) {
        StandardDialog(
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
        StandardDialog(
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
                    .fillMaxWidth(DialogWidthFraction),
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

                    PropertyRow("名称", data.name,
                        onClick = {
                            val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("name", data.name))
                            Toast.makeText(context, "已复制: ${data.name}", Toast.LENGTH_SHORT).show()
                        },
                        onLongClick = {
                            val fullPath = if (data.directory.endsWith("/")) "${data.directory}${data.name}" else "${data.directory}/${data.name}"
                            val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("path", fullPath))
                            Toast.makeText(context, "已复制: $fullPath", Toast.LENGTH_SHORT).show()
                        }
                    )
                    PropertyRow("目录", data.directory,
                        onLongClick = {
                            val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("path", data.directory))
                            Toast.makeText(context, "已复制: ${data.directory}", Toast.LENGTH_SHORT).show()
                        }
                    )
                    PropertyRow("类型", data.type,
                        onLongClick = {
                            val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("type", data.type))
                            Toast.makeText(context, "已复制: ${data.type}", Toast.LENGTH_SHORT).show()
                        }
                    )
                    PropertyRow("大小", data.sizeDisplay,
                        onLongClick = {
                            val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("size", data.sizeDisplay))
                            Toast.makeText(context, "已复制: ${data.sizeDisplay}", Toast.LENGTH_SHORT).show()
                        }
                    )
                    PropertyRow("修改时间", data.modifiedTime,
                        onLongClick = {
                            val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("time", data.modifiedTime))
                            Toast.makeText(context, "已复制: ${data.modifiedTime}", Toast.LENGTH_SHORT).show()
                        }
                    )

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
                            PropertyRowWithButton("权限", data.permission, onClick = {
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
                        PropertyRow("文件数", data.fileCount?.toString() ?: "正在统计",
                            onLongClick = {
                                val text = data.fileCount?.toString() ?: return@PropertyRow
                                val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clip.setPrimaryClip(android.content.ClipData.newPlainText("fileCount", text))
                                Toast.makeText(context, "已复制: $text", Toast.LENGTH_SHORT).show()
                            }
                        )
                        PropertyRow("文件夹数", data.folderCount?.toString() ?: "正在统计",
                            onLongClick = {
                                val text = data.folderCount?.toString() ?: return@PropertyRow
                                val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clip.setPrimaryClip(android.content.ClipData.newPlainText("folderCount", text))
                                Toast.makeText(context, "已复制: $text", Toast.LENGTH_SHORT).show()
                            }
                        )
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
        val scope = rememberCoroutineScope()
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
        var showErrorDetail by remember { mutableStateOf(false) }

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
                modifier = Modifier.fillMaxWidth(DialogWidthFraction),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("权限编辑", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(entry.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    // ── 权限网格 ──
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            // 表头
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Spacer(modifier = Modifier.width(40.dp))
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
                            Spacer(modifier = Modifier.height(2.dp))

                            // 三行：读、写、执行
                            data class PermRow(val label: String, val ownerGet: () -> Boolean, val ownerSet: (Boolean) -> Unit, val groupGet: () -> Boolean, val groupSet: (Boolean) -> Unit, val otherGet: () -> Boolean, val otherSet: (Boolean) -> Unit)
                            val rows = listOf(
                                PermRow("读", { ownerRead }, { ownerRead = it }, { groupRead }, { groupRead = it }, { otherRead }, { otherRead = it }),
                                PermRow("写", { ownerWrite }, { ownerWrite = it }, { groupWrite }, { groupWrite = it }, { otherWrite }, { otherWrite = it }),
                                PermRow("执行", { ownerExec }, { ownerExec = it }, { groupExec }, { groupExec = it }, { otherExec }, { otherExec = it })
                            )
                            rows.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = row.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.width(40.dp)
                                    )
                                    listOf(
                                        row.ownerGet to row.ownerSet,
                                        row.groupGet to row.groupSet,
                                        row.otherGet to row.otherSet
                                    ).forEach { (get, set) ->
                                        Box(
                                            modifier = Modifier.weight(1f).requiredSize(32.dp),
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
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "特殊",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.width(40.dp)
                                )
                                data class ExtFlag(val label: String, val key: Char, val get: () -> Boolean, val set: (Boolean) -> Unit)
                                val extFlags = listOf(
                                    ExtFlag("i", 'i', { extImmutable }, { extImmutable = it }),
                                    ExtFlag("a", 'a', { extAppend }, { extAppend = it }),
                                )
                                extFlags.forEach { flag ->
                                    Box(
                                        modifier = Modifier.weight(1f).requiredSize(32.dp),
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
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        Text(errorMsg!!.substringBefore("\n\n"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
                                // 前置检查：收集所有阻断错误
                                val errors = mutableListOf<String>()
                                val fusePath = vm.resolveFuseRealPath(entry.path)
                                if (fusePath != null) {
                                    errors.add("该文件位于 FUSE 虚拟挂载层，无法直接修改权限。如需修改，请通过以下真实路径操作：\n$fusePath")
                                }
                                if (!vm.isRootEngine) {
                                    errors.add("当前无 Root 权限，无法执行 chmod/chown 操作。")
                                }
                                if (errors.isNotEmpty()) {
                                    blockErrorMessages = errors
                                    showBlockErrors = true
                                    return@TextButton
                                }

                                // 执行权限修改 + 后台验证的 lambda（可重试）
                                lateinit var doApply: () -> Unit
                                doApply = {
                                    scope.launch {
                                        applying = true
                                        errorMsg = null

                                        val extResult = withContext(Dispatchers.IO) {
                                            val desiredFlags = buildSet {
                                                if (extImmutable) add('i')
                                                if (extAppend) add('a')
                                            }
                                            val originalFlagSet = originalExtFlags.filter { it == 'i' || it == 'a' }.toSet()
                                            if (desiredFlags != originalFlagSet) vm.applyExtFlags(entry.path, desiredFlags, originalExtFlags) else null
                                        }
                                        if (extResult != null) {
                                            applying = false
                                            errorMsg = extResult
                                            showErrorDetail = true
                                            return@launch
                                        }

                                        val permResult = withContext(Dispatchers.IO) {
                                            val permChanged = currentMode != originalMode || selectedUid != originalUid || selectedGid != originalGid
                                            if (permChanged) vm.applyPermissions(entry.path, currentMode, selectedUid, selectedGid, originalMode, originalUid, originalGid).first else null
                                        }
                                        applying = false
                                        if (permResult != null) {
                                            errorMsg = permResult
                                            showErrorDetail = true
                                            return@launch
                                        }

                                        showPermissionEditor = false
                                        listStates[vm.focusedPanel.index].let { _s -> vm.saveScrollPosition(_s.firstVisibleItemIndex, _s.firstVisibleItemScrollOffset) }
                                        val targetPath = vm.currentPanel.path
                                        val saved = vm.getScrollPosition(targetPath)
                                        vm.refreshCurrent()
                                        if (saved != null) vm.currentPanel.pendingScrollTo = Triple(targetPath, saved.first, saved.second)

                                        // 后台验证
                                        val failed = withContext(Dispatchers.IO) {
                                            vm.verifyPermissions(entry.path, currentMode, selectedUid, selectedGid)
                                        }
                                        if (failed.isNotEmpty()) {
                                            verifyFailedPaths = failed
                                            verifyRetryCallback = { scope.launch { doApply() } }
                                            showVerifyFailed = true
                                        }
                                    }
                                }
                                doApply()
                            },
                            enabled = !applying
                        ) {
                            Text(if (applying) "应用中..." else "确认")
                        }
                    }
                }
            }
        }

        // ── 统一阻断错误弹窗 ──
        if (showBlockErrors) {
            Dialog(onDismissRequest = { showBlockErrors = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Card(
                    modifier = Modifier.fillMaxWidth(DialogWidthFraction),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("无法修改权限", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        blockErrorMessages.forEachIndexed { index, msg ->
                            if (index > 0) Spacer(modifier = Modifier.height(4.dp))
                            Text(msg, style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showBlockErrors = false }) { Text("确认") }
                        }
                    }
                }
            }
        }

        // ── 权限验证失败弹窗 ──
        if (showVerifyFailed) {
            Dialog(onDismissRequest = { showVerifyFailed = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Card(
                    modifier = Modifier.fillMaxWidth(DialogWidthFraction),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("权限未能正确赋予", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "以下文件权限未生效：",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        verifyFailedPaths.take(5).forEach { p ->
                            Text(p, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showVerifyFailed = false }) { Text("取消") }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = {
                                showVerifyFailed = false
                                verifyRetryCallback?.invoke()
                            }) { Text("重新尝试") }
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
                    modifier = Modifier.fillMaxWidth(DialogWidthFraction).fillMaxHeight(0.75f),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("选择所有者", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(sortedUsers) { user ->
                                val isCurrent = user.uid == originalUid
                                val displayName = if (user.appLabel.isNotEmpty()) "${user.username}（${user.appLabel}）" else user.username
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedUid = user.uid
                                            selectedUserName = user.username
                                            showUserPicker = false
                                        }
                                        .padding(vertical = 4.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isCurrent) "$displayName (当前)" else displayName,
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
                    modifier = Modifier.fillMaxWidth(DialogWidthFraction).fillMaxHeight(0.75f),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("选择用户组", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(sortedGroups) { group ->
                                val isCurrent = group.gid == originalGid
                                val displayName = if (group.appLabel.isNotEmpty()) "${group.groupname}（${group.appLabel}）" else group.groupname
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedGid = group.gid
                                            selectedGroupName = group.groupname
                                            showGroupPicker = false
                                        }
                                        .padding(vertical = 4.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isCurrent) "$displayName (当前)" else displayName,
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
            Dialog(onDismissRequest = { showExtHelp = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Card(
                    modifier = Modifier.fillMaxWidth(DialogWidthFraction),
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

        // ── 权限修改错误详情弹窗 ──
        if (showErrorDetail && errorMsg != null) {
            val clipboardManager = LocalClipboardManager.current
            Dialog(onDismissRequest = { showErrorDetail = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Card(
                    modifier = Modifier.fillMaxWidth(DialogWidthFraction),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("权限修改失败", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        val message = errorMsg!!.substringBefore("\n\n")
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        val stackTrace = errorMsg!!.substringAfter("\n\n", "")
                        if (stackTrace.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val scrollState = rememberScrollState()
                                Text(
                                    text = stackTrace,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(8.dp).heightIn(max = 200.dp).verticalScroll(scrollState)
                                )
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                clipboardManager.setText(AnnotatedString(errorMsg!!))
                            }) { Text("复制") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { showErrorDetail = false }) { Text("关闭") }
                        }
                    }
                }
            }
        }
    }
    if (showFontSizeDialog) {
        var fontSizeText by remember { mutableStateOf(vm.fileNameFontSize.toInt().toString()) }
        AlertDialog(
            onDismissRequest = { showFontSizeDialog = false },
            title = { Text("调整字体大小") },
            text = {
                OutlinedTextField(
                    value = fontSizeText,
                    onValueChange = { newValue ->
                        if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                            fontSizeText = newValue
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val size = fontSizeText.toFloatOrNull()
                    if (size != null && size in 8f..32f) {
                        vm.updateFileNameFontSize(size)
                        showFontSizeDialog = false
                    }
                }) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFontSizeDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
    if (showSortDialog) {
        val fieldLabels = mapOf(
            SortField.NAME to "名称",
            SortField.SIZE to "大小",
            SortField.MODIFIED to "最后修改时间",
            SortField.CREATED to "创建时间"
        )

        StandardDialog(
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
                        val currentEntries = vm.currentPanel.entries
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
        StandardDialog(
            onDismissRequest = { showSortSizeRefreshDialog = false },
            title = { Text("统计大小") },
            text = { Text("当前列表有 ${unmeasuredDirs.size} 个文件夹尚未统计大小，是否先统计再排序？") },
            confirmButton = {
                TextButton(onClick = {
                    showSortSizeRefreshDialog = false
                    val parentPath = vm.currentPanel.path
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
                vm.左.path.substringAfterLast('/').ifEmpty { "压缩包" }
            } else {
                vm.右.path.substringAfterLast('/').ifEmpty { "压缩包" }
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
                    vm.左.path.substringAfterLast('/').ifEmpty { "压缩包" }
                } else {
                    vm.右.path.substringAfterLast('/').ifEmpty { "压缩包" }
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
                    modifier = Modifier.fillMaxWidth(DialogWidthFraction),
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
                                        vm.otherPanel.path
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
        StandardDialog(
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
                    modifier = Modifier.fillMaxWidth(DialogWidthFraction),
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
        val currentDir = vm.currentPanel.path

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
                    modifier = Modifier.fillMaxWidth(DialogWidthFraction),
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
                    modifier = Modifier.fillMaxWidth(DialogWidthFraction),
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
                    modifier = Modifier.fillMaxWidth(DialogWidthFraction),
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
            onDismiss = { vm.currentPanel.archivePasswordRequest = null },
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
    isVaultMode: Boolean = false,
    vaultRootPath: String? = null
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
    if (isVaultMode && vaultRootPath != null) {
        return currentPath.substringBeforeLast('/').ifEmpty { "/" }.let { p ->
            if (p != currentPath) p else null
        }
    }
    val effectiveRoot = if (isRootEngine) "/" else "/storage/emulated/0"
    if (currentPath != effectiveRoot && currentPath.contains('/')) {
        return currentPath.substringBeforeLast('/').ifEmpty { "/" }.let { p ->
            if (p != currentPath) p else null
        }
    }
    return null
}

@Composable
private fun FileBrowserPanel(
    entries: List<FileEntry>,
    isFocused: Boolean,
    currentPath: String = "",
    isLeftPanel: Boolean = true,
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
    thumbnailLoader: ((FileEntry) -> ImageBitmap?)? = null,
    fileNameFontSize: Float = 12f
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
                            folderSize = "",
                            fileNameFontSize = fileNameFontSize
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
                        thumbnail = thumb,
                        fileNameFontSize = fileNameFontSize
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
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipe: (Float) -> Unit = {},
    folderSize: String = "",
    extFlags: String = "",
    thumbnail: ImageBitmap? = null,
    fileNameFontSize: Float = 12f
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val swipeOffset = remember { Animatable(0f) }
    var rowWidth by remember { mutableIntStateOf(0) }
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                  else Color.Transparent

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowWidth = it.size.width }
            .clipToBounds()
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
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
            Row(modifier = Modifier.weight(7f).fillMaxHeight()) {
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
                    modifier = Modifier.weight(4f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = fileNameFontSize.sp,
                    lineHeight = fileNameFontSize.sp,
                    textAlign = TextAlign.Center,
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
private fun PropertyRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
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
        val valueModifier = if (onClick != null || onLongClick != null) {
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .combinedClickable(
                    onClick = { onClick?.invoke() },
                    onLongClick = { onLongClick?.invoke() }
                )
        } else {
            Modifier.weight(1f)
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = valueModifier,
            softWrap = true
        )
    }
}

@Composable
private fun PropertyRowWithButton(label: String, value: String, onClick: () -> Unit = {}) {
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
        TextButton(onClick = onClick) {
            Text("更改", color = MaterialTheme.colorScheme.primary)
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

// StandardDialog 已移至 FileManagerDialogs_FileOps.kt
