package com.whmdg.mczj.tools.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.whmdg.mczj.tools.util.DiagnosticLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import java.io.File

enum class FocusedPanel { LEFT, RIGHT }
enum class CreateMode { FILE, FOLDER }

data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean
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
@Composable
fun FileManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var hasStoragePermission by remember {
        mutableStateOf(Environment.isExternalStorageManager())
    }

    // 引擎选择：Root or POSIX（security_prefs 管理，跨模块共享）
    val secPrefs = remember { context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE) }
    // 向下兼容：旧版数据在 special_permissions 里，迁移过来
    val legacySp = remember { context.getSharedPreferences("special_permissions", Context.MODE_PRIVATE) }
    val permissionLevel = remember {
        secPrefs.getString("target_permission_level", null)
            ?: legacySp.getString("target_permission_level", "NORMAL") ?: "NORMAL"
    }
    val isRootEngine = remember {
        permissionLevel == "ROOT" && SpecialPermissionVerifier.isRootAvailable()
    }

    // 文件管理器专用设置（同一个界面的设置存在同一个 XML）
    val fmPrefs = remember { context.getSharedPreferences(AppDataPaths.PREFS_FILE_MANAGER, Context.MODE_PRIVATE) }

    // 安全默认目录
    val safeDefault = "/storage/emulated/0"

    // 校验目录是否可访问，不可用时回退到安全默认
    fun resolveHome(saved: String): String {
        val dir = File(saved)
        if (!dir.exists() || !dir.isDirectory) return safeDefault
        if (!isRootEngine && !dir.canRead()) return safeDefault
        return saved
    }

    // 左右主目录 — 从 file_manager_prefs 读取，向下兼容旧版 special_permissions
    val leftHomeDirectory = remember {
        resolveHome(
            fmPrefs.getString("left_home_directory", null)
                ?: legacySp.getString("left_home_directory", safeDefault)
                ?: safeDefault
        )
    }
    val rightHomeDirectory = remember {
        resolveHome(
            fmPrefs.getString("right_home_directory", null)
                ?: legacySp.getString("right_home_directory", safeDefault)
                ?: safeDefault
        )
    }

    var leftPath by remember { mutableStateOf(leftHomeDirectory) }
    var rightPath by remember { mutableStateOf(rightHomeDirectory) }
    var leftEntries by remember { mutableStateOf(listOf<FileEntry>()) }
    var rightEntries by remember { mutableStateOf(listOf<FileEntry>()) }
    var leftNavState by remember { mutableStateOf(PanelNavState(paths = listOf(leftHomeDirectory), index = 0)) }
    var rightNavState by remember { mutableStateOf(PanelNavState(paths = listOf(rightHomeDirectory), index = 0)) }
    var focusedPanel by remember { mutableStateOf(FocusedPanel.LEFT) }
    var showHiddenFiles by remember {
        mutableStateOf(fmPrefs.getBoolean("show_hidden_files", false))
    }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<Throwable?>(null) }
    var showCreateTypeDialog by remember { mutableStateOf(false) }
    var createMode by remember { mutableStateOf(CreateMode.FILE) }
    var showNameDialog by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }

    // ── 普通引擎：File.listFiles（公开 API，无 hidden API 限制） ──
    fun listWithFile(path: String, showHidden: Boolean, effectiveRoot: String): List<FileEntry> {
        DiagnosticLog.log("FileEngine", "listFiles($path) showHidden=$showHidden")
        val normalizedPath = if (path == "/") "/" else path.trimEnd('/').ifEmpty { "/" }
        val dir = File(normalizedPath)

        if (!dir.exists()) {
            DiagnosticLog.log("FileEngine", "目录不存在: $normalizedPath")
            loadError = RuntimeException("目录不存在: $normalizedPath")
            return emptyList()
        }
        if (!dir.isDirectory) {
            DiagnosticLog.log("FileEngine", "路径不是目录: $normalizedPath")
            loadError = RuntimeException("路径不是目录: $normalizedPath")
            return emptyList()
        }

        val children = try {
            dir.listFiles()
        } catch (e: SecurityException) {
            DiagnosticLog.log("FileEngine", "listFiles SecurityException: ${e.message}")
            loadError = e
            return emptyList()
        }
        if (children == null) {
            DiagnosticLog.log("FileEngine", "listFiles 返回 null（权限不足或 I/O 错误）")
            loadError = RuntimeException("无法列出目录（权限不足）: $normalizedPath")
            return emptyList()
        }
        DiagnosticLog.log("FileEngine", "listFiles 返回 ${children.size} 项")

        val entries = mutableListOf<FileEntry>()
        var dirCount = 0
        var fileCount = 0
        var skipHidden = 0
        for (child in children) {
            val name = child.name
            if (!showHidden && name.startsWith(".")) { skipHidden++; continue }
            val isDir = try {
                child.isDirectory
            } catch (e: Exception) {
                DiagnosticLog.log("FileEngine", "isDirectory 异常 $name: ${e.message}")
                false
            }
            if (isDir) dirCount++ else fileCount++
            entries.add(FileEntry(child.absolutePath, name, isDir))
        }
        DiagnosticLog.log("FileEngine", "统计: dirs=$dirCount, files=$fileCount, hidden 过滤=$skipHidden")

        // 添加 ".." 父目录（仅当不在根目录且父目录可访问）
        if (normalizedPath != effectiveRoot && normalizedPath.contains('/')) {
            val parentPath = normalizedPath.substringBeforeLast('/').ifEmpty { "/" }
            if (parentPath != normalizedPath) {
                val parentFile = File(parentPath)
                val parentAccessible = try {
                    parentFile.canRead()
                } catch (_: Exception) { false }
                if (parentAccessible) {
                    entries.add(0, FileEntry(parentPath, "..", true))
                } else {
                    DiagnosticLog.log("FileEngine", "父目录不可读: $parentPath")
                }
            }
        }

        entries.sortWith(
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
        return entries
    }

    // ── ls 引擎：统一用 `ls -1Ap`，区别只是要不要 su ──
    // useRoot=true → `su -c 'ls -1Ap …'`；false → 直接 `sh -c 'ls -1Ap …'`
    // -1 单列, -A 显示隐藏但不含 . 和 .., -p 给目录加 '/' 后缀（无需另起 stat）
    fun listWithLs(path: String, showHidden: Boolean, useRoot: Boolean, effectiveRoot: String): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        val normalizedPath = if (path == "/") "/" else path.trimEnd('/').ifEmpty { "/" }
        val escapedPath = normalizedPath.replace("'", "'\\''")
        val flags = if (showHidden) "-1Ap" else "-1p"
        val command = "ls $flags '$escapedPath'"
        val tag = if (useRoot) "LsRoot" else "LsShell"
        DiagnosticLog.log(tag, "命令: $command")

        val (stdout, stderr, exitCode) = try {
            if (useRoot) SpecialPermissionVerifier.executeRootCommandFull(command)
            else SpecialPermissionVerifier.executeShellCommandFull(command)
        } catch (e: Throwable) {
            // 精确识别注入到我们进程的 ApkAssets hook 噪声（来自 Magisk/Zygisk/LSPosed 模块）
            val isApkAssetsNoise = e is java.io.IOException && (
                e.stackTrace.any { it.className == "android.content.res.ApkAssets" } ||
                        (e.message?.contains("Failed to load asset path") == true) ||
                        (e.message?.contains(".apk from fd") == true)
            )
            if (isApkAssetsNoise) {
                DiagnosticLog.log(tag, "忽略 hook 注入的 ApkAssets 噪声: ${e.message}")
                return emptyList()
            }
            DiagnosticLog.log(tag, "execute 抛错: ${e.javaClass.simpleName}: ${e.message}")
            loadError = if (e is Exception) e else RuntimeException(e)
            return emptyList()
        }
        DiagnosticLog.log(tag, "exit=$exitCode stdout=${stdout.length}字符 stderr=${stderr.length}字符")
        if (stderr.isNotBlank()) DiagnosticLog.log(tag, "stderr: ${stderr.take(500)}")
        if (stdout.isNotBlank()) DiagnosticLog.log(tag, "stdout 前 500: ${stdout.take(500)}")

        if (exitCode != 0 && stdout.isBlank()) {
            loadError = SecurityException("ls 失败 (exit $exitCode): ${stderr.ifBlank { "(无 stderr)" }}")
            return emptyList()
        }

        val lines = stdout.lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        var dirCount = 0
        var fileCount = 0
        for (raw in lines) {
            val isDir = raw.endsWith("/")
            val name = if (isDir) raw.dropLast(1) else raw
            if (name == "." || name == "..") continue
            if (!showHidden && name.startsWith(".")) continue
            if (isDir) dirCount++ else fileCount++
            val childPath = if (normalizedPath == "/") "/$name" else "$normalizedPath/$name"
            entries.add(FileEntry(childPath, name, isDir))
        }
        DiagnosticLog.log(tag, "解析结果: dirs=$dirCount, files=$fileCount, 总 ${entries.size}")

        // ".." 父目录：只要不是根 "/" 都显示
        if (normalizedPath != effectiveRoot && normalizedPath.contains('/')) {
            val parentPath = normalizedPath.substringBeforeLast('/').ifEmpty { "/" }
            if (parentPath != normalizedPath) {
                entries.add(0, FileEntry(parentPath, "..", true))
            }
        }

        entries.sortWith(
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
        return entries
    }

    // ── 统一入口 ──
    // - 有 root → `su -c ls`
    // - 无 root → `sh -c ls`
    // - ls 完全失败时，最后兜底 File.listFiles
    fun listDirectory(path: String): List<FileEntry> {
        DiagnosticLog.log("FileMgr", ">>> listDirectory START path=$path useRoot=$isRootEngine")
        loadError = null
        val t0 = System.currentTimeMillis()
        val effectiveRoot = if (isRootEngine) "/" else "/storage/emulated/0"

        var entries = listWithLs(path, showHiddenFiles, useRoot = isRootEngine, effectiveRoot = effectiveRoot)

        // 兜底：ls 完全没结果且报错 → 退到 File.listFiles
        if (entries.isEmpty() && loadError != null) {
            DiagnosticLog.log("FileMgr", "ls 失败，回退 File API")
            val prevErr = loadError
            loadError = null
            val fileEntries = listWithFile(path, showHiddenFiles, effectiveRoot)
            if (fileEntries.isNotEmpty()) {
                entries = fileEntries
            } else if (loadError == null) {
                loadError = prevErr
            }
        }

        val took = System.currentTimeMillis() - t0
        DiagnosticLog.log("FileMgr", "<<< listDirectory END path=$path entries=${entries.size} took=${took}ms err=${loadError?.javaClass?.simpleName}")
        return entries
    }

    fun openFile(context: Context, entry: FileEntry) {
        DiagnosticLog.log("OpenFile", "请求打开: ${entry.path}")
        val file = File(entry.path)
        if (entry.name.endsWith(".apk", ignoreCase = true) ||
            entry.name.endsWith(".apex", ignoreCase = true)
        ) {
            DiagnosticLog.log("OpenFile", "拒绝打开 apk/apex: ${entry.name}")
            Toast.makeText(context, "APK 文件请在应用管理器中安装", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val extension = entry.name.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "*/*"
            DiagnosticLog.log("OpenFile", "uri=$uri ext='$extension' mime=$mimeType")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val resolver = intent.resolveActivity(context.packageManager)
            DiagnosticLog.log("OpenFile", "resolveActivity=${resolver?.flattenToString() ?: "(null)"}")
            if (resolver != null) {
                context.startActivity(intent)
                DiagnosticLog.log("OpenFile", "startActivity 已调用")
            } else {
                Toast.makeText(context, "没有应用可以打开此文件", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            DiagnosticLog.log("OpenFile", "异常: ${e.javaClass.simpleName}: ${e.message}")
            Toast.makeText(context, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        hasStoragePermission = Environment.isExternalStorageManager()
        if (hasStoragePermission) {
            leftEntries = listDirectory(leftPath)
            rightEntries = listDirectory(rightPath)
        }
    }

    LaunchedEffect(leftPath, showHiddenFiles) {
        DiagnosticLog.log("FileMgr", "LaunchedEffect[LEFT] 触发 path=$leftPath showHidden=$showHiddenFiles")
        leftEntries = listDirectory(leftPath)
        DiagnosticLog.log("FileMgr", "LaunchedEffect[LEFT] 完成 entries=${leftEntries.size}")
    }
    LaunchedEffect(rightPath, showHiddenFiles) {
        DiagnosticLog.log("FileMgr", "LaunchedEffect[RIGHT] 触发 path=$rightPath showHidden=$showHiddenFiles")
        rightEntries = listDirectory(rightPath)
        DiagnosticLog.log("FileMgr", "LaunchedEffect[RIGHT] 完成 entries=${rightEntries.size}")
    }

    LaunchedEffect(Unit) {
        DiagnosticLog.beginSession("进入 FileManagerScreen")
        DiagnosticLog.log("FileMgr", "FileManagerScreen 启动 isRootEngine=$isRootEngine permissionLevel=$permissionLevel hasStoragePerm=$hasStoragePermission")
        if (!hasStoragePermission) {
            Toast.makeText(context, "需要存储权限才能浏览文件", Toast.LENGTH_LONG).show()
        }

        // ── 一次性旧数据迁移 ──
        if (legacySp.contains("show_hidden_files") && !fmPrefs.contains("show_hidden_files")) {
            fmPrefs.edit().putBoolean("show_hidden_files", legacySp.getBoolean("show_hidden_files", false)).apply()
        }
        if (legacySp.contains("left_home_directory") && !fmPrefs.contains("left_home_directory")) {
            fmPrefs.edit().putString("left_home_directory", legacySp.getString("left_home_directory", null)).apply()
        }
        if (legacySp.contains("right_home_directory") && !fmPrefs.contains("right_home_directory")) {
            fmPrefs.edit().putString("right_home_directory", legacySp.getString("right_home_directory", null)).apply()
        }
        if (legacySp.contains("target_permission_level") && !secPrefs.contains("target_permission_level")) {
            secPrefs.edit().putString("target_permission_level", legacySp.getString("target_permission_level", null)).apply()
        }
    }

    val currentPath = when (focusedPanel) {
        FocusedPanel.LEFT -> leftPath
        FocusedPanel.RIGHT -> rightPath
    }

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
                            onDismissRequest = { showSettingsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("显示隐藏文件") },
                                trailingIcon = {
                                    if (showHiddenFiles) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    showHiddenFiles = !showHiddenFiles
                                    fmPrefs.edit().putBoolean("show_hidden_files", showHiddenFiles).apply()
                                    showSettingsMenu = false
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // 后退按钮（约 1/6 宽度）
                    Box(
                        modifier = Modifier.fillMaxWidth(1f / 6f),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = {
                                val nav = when (focusedPanel) {
                                    FocusedPanel.LEFT -> leftNavState
                                    FocusedPanel.RIGHT -> rightNavState
                                }
                                val back = nav.back()
                                if (back != null) {
                                    when (focusedPanel) {
                                        FocusedPanel.LEFT -> { leftNavState = back; leftPath = back.current }
                                        FocusedPanel.RIGHT -> { rightNavState = back; rightPath = back.current }
                                    }
                                }
                            },
                            enabled = when (focusedPanel) {
                                FocusedPanel.LEFT -> leftNavState.canGoBack
                                FocusedPanel.RIGHT -> rightNavState.canGoBack
                            }
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "后退")
                        }
                    }
                    // 前进按钮（约 1/6 宽度）
                    Box(
                        modifier = Modifier.fillMaxWidth(1f / 6f),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = {
                                val nav = when (focusedPanel) {
                                    FocusedPanel.LEFT -> leftNavState
                                    FocusedPanel.RIGHT -> rightNavState
                                }
                                val fwd = nav.forward()
                                if (fwd != null) {
                                    when (focusedPanel) {
                                        FocusedPanel.LEFT -> { leftNavState = fwd; leftPath = fwd.current }
                                        FocusedPanel.RIGHT -> { rightNavState = fwd; rightPath = fwd.current }
                                    }
                                }
                            },
                            enabled = when (focusedPanel) {
                                FocusedPanel.LEFT -> leftNavState.canGoForward
                                FocusedPanel.RIGHT -> rightNavState.canGoForward
                            }
                        ) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "前进")
                        }
                    }
                    // 新建按钮（约 1/6 宽度）
                    Box(
                        modifier = Modifier.fillMaxWidth(1f / 6f),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(onClick = { showCreateTypeDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "新建")
                        }
                    }
                    // 同步按钮（约 1/6 宽度）
                    Box(
                        modifier = Modifier.fillMaxWidth(1f / 6f),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(onClick = {
                            when (focusedPanel) {
                                FocusedPanel.LEFT -> {
                                    rightPath = leftPath
                                    rightNavState = rightNavState.navigate(leftPath)
                                    rightEntries = listDirectory(rightPath)
                                }
                                FocusedPanel.RIGHT -> {
                                    leftPath = rightPath
                                    leftNavState = leftNavState.navigate(rightPath)
                                    leftEntries = listDirectory(leftPath)
                                }
                            }
                        }) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "同步路径")
                        }
                    }
                    // 右侧预留 2 个图标位（各 1/6 = 共 1/3）
                    Box(
                        modifier = Modifier.fillMaxWidth(1f / 6f),
                        contentAlignment = Alignment.Center
                    ) { /* 预留 */ }
                    Box(
                        modifier = Modifier.fillMaxWidth(1f / 6f),
                        contentAlignment = Alignment.Center
                    ) { /* 预留 */ }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasStoragePermission) {
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
                Row(modifier = Modifier.fillMaxSize()) {
                    FileBrowserPanel(
                        entries = leftEntries,
                        isFocused = focusedPanel == FocusedPanel.LEFT,
                        onFocus = { focusedPanel = FocusedPanel.LEFT },
                        onFolderClick = { entry ->
                            DiagnosticLog.beginSession("[LEFT] 点击文件夹 '${entry.name}'")
                            DiagnosticLog.log("FileMgr", "[LEFT] 点击文件夹 name='${entry.name}' path='${entry.path}' from=$leftPath")
                            focusedPanel = FocusedPanel.LEFT
                            leftNavState = leftNavState.navigate(entry.path)
                            leftPath = entry.path
                        },
                        onFileClick = { entry ->
                            DiagnosticLog.beginSession("[LEFT] 点击文件 '${entry.name}'")
                            DiagnosticLog.log("FileMgr", "[LEFT] 点击文件 name='${entry.name}' path='${entry.path}'")
                            focusedPanel = FocusedPanel.LEFT
                            openFile(context, entry)
                        },
                        modifier = Modifier.weight(1f)
                    )

                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        thickness = 1.dp
                    )

                    FileBrowserPanel(
                        entries = rightEntries,
                        isFocused = focusedPanel == FocusedPanel.RIGHT,
                        onFocus = { focusedPanel = FocusedPanel.RIGHT },
                        onFolderClick = { entry ->
                            DiagnosticLog.beginSession("[RIGHT] 点击文件夹 '${entry.name}'")
                            DiagnosticLog.log("FileMgr", "[RIGHT] 点击文件夹 name='${entry.name}' path='${entry.path}' from=$rightPath")
                            focusedPanel = FocusedPanel.RIGHT
                            rightNavState = rightNavState.navigate(entry.path)
                            rightPath = entry.path
                        },
                        onFileClick = { entry ->
                            DiagnosticLog.beginSession("[RIGHT] 点击文件 '${entry.name}'")
                            DiagnosticLog.log("FileMgr", "[RIGHT] 点击文件 name='${entry.name}' path='${entry.path}'")
                            focusedPanel = FocusedPanel.RIGHT
                            openFile(context, entry)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    ErrorDialog(error = loadError, onDismiss = {
        DiagnosticLog.log("FileMgr", "关闭错误对话框")
        loadError = null
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
                        val currentPath = when (focusedPanel) {
                            FocusedPanel.LEFT -> leftPath
                            FocusedPanel.RIGHT -> rightPath
                        }
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
                            when (focusedPanel) {
                                FocusedPanel.LEFT -> leftEntries = listDirectory(leftPath)
                                FocusedPanel.RIGHT -> rightEntries = listDirectory(rightPath)
                            }
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
}

@Composable
private fun FileBrowserPanel(
    entries: List<FileEntry>,
    isFocused: Boolean,
    onFocus: () -> Unit,
    onFolderClick: (FileEntry) -> Unit,
    onFileClick: (FileEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
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
            }
    ) {
        if (entries.isEmpty()) {
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
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(entries, key = { it.path }) { entry ->
                    FileEntryRow(
                        entry = entry,
                        isFocused = isFocused,
                        onClick = {
                            if (entry.isDirectory) onFolderClick(entry)
                            else onFileClick(entry)
                        }
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
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = if (isFocused) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun StartEllipsisText(
    text: String,
    modifier: Modifier = Modifier
) {
    var displayText by remember(text) { mutableStateOf(text) }

    Text(
        text = displayText,
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        style = MaterialTheme.typography.titleMedium,
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
