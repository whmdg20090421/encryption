package com.whmdg.mczj.tools.ui

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
import com.whmdg.mczj.tools.encryption.data.FolderSizeInfo
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.util.DiagnosticLog
import com.whmdg.mczj.tools.util.CompressService
import com.whmdg.mczj.tools.util.FileAccessLevel
import com.whmdg.mczj.tools.util.FileAccessor
import com.whmdg.mczj.tools.util.SizeCalcResult
import com.whmdg.mczj.tools.util.calculateFolderSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import android.system.Os
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class RecycleBinEntry(
    val binName: String,
    val originalPath: String,
    val deletedAt: Long,
    val isDirectory: Boolean
)

data class FilePropertyData(
    val name: String,
    val directory: String,
    val type: String,
    val sizeBytes: Long,
    val sizeDisplay: String,
    val modifiedTime: String,
    val permission: String,
    val owner: String,
    val group: String,
    val fileCount: Int,
    val folderCount: Int,
    val isDirectory: Boolean
)

class FileManagerViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Context get() = getApplication()

    // ── 引擎 & 权限 ──
    private val secPrefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
    private val legacySp = context.getSharedPreferences(AppDataPaths.PREFS_LEGACY_SPECIAL_PERMISSIONS, Context.MODE_PRIVATE)
    val isRootEngine: Boolean
    private val permissionLevel: String
    /** 当前是否有可用的 shell 引擎（Root/libsu 或 Shizuku/ADB） */
    private val hasShellEngine: Boolean
        get() = isRootEngine || SpecialPermissionVerifier.isShizukuAuthorized(getApplication())

    // ── 文件管理器偏好 ──
    private val fmPrefs = context.getSharedPreferences(AppDataPaths.PREFS_FILE_MANAGER, Context.MODE_PRIVATE)
    private val safeDefault = "/storage/emulated/0"

    // ── 核心浏览状态（导航返回时保留） ──
    var leftPath by mutableStateOf(safeDefault)
        private set
    var rightPath by mutableStateOf(safeDefault)
        private set
    var leftEntries by mutableStateOf(listOf<FileEntry>())
        private set
    var rightEntries by mutableStateOf(listOf<FileEntry>())
        private set
    var leftNavState by mutableStateOf(PanelNavState())
        private set
    var rightNavState by mutableStateOf(PanelNavState())
        private set
    var focusedPanel by mutableStateOf(FocusedPanel.LEFT)
    var showHiddenFiles by mutableStateOf(false)
        private set
    var sortField by mutableStateOf(SortField.NAME)
        private set
    var sortOrder by mutableStateOf(SortOrder.ASC)
        private set
    var loadError by mutableStateOf<Throwable?>(null)
    // 最近一次 listDirEntriesViaShell 的 stderr，用于调用方判断失败原因
    private var lastShellStderr = ""
    var folderSizeDb by mutableStateOf(FolderSizeDb())
        private set
    var refreshVersion by mutableStateOf(0L)
        private set

    // ── 回收站 ──
    var isInRecycleBin by mutableStateOf(false)
        private set
    var recycleBinPath by mutableStateOf("")
        private set
    var jxlPackZip by mutableStateOf(false)
        private set
    var pendingExternalEntry by mutableStateOf<FileEntry?>(null)

    // ── 压缩包浏览 ──
    var isInArchive by mutableStateOf(false)
        private set
    var archivePath by mutableStateOf("")         // 压缩包内当前相对路径
        private set
    var archiveRootPath by mutableStateOf("")     // 压缩包根路径（始终 "/"）
        private set
    var archiveFilePath by mutableStateOf("")     // 原始压缩包文件路径
        private set
    var archiveFileName by mutableStateOf("")     // 原始压缩包文件名
        private set
    var archiveFormat by mutableStateOf("")
        private set
    var archivePassword by mutableStateOf("")
        private set
    private var archiveMemFs: CompressService.ArchiveMemFs? = null
    private val recycleBinJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true
    }
    private var recycleBinMetaList by mutableStateOf(listOf<RecycleBinEntry>())

    // ── 历史 & 书签 ──
    private val historyJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true
    }
    private val historyFile = File(AppDataPaths.fileManager(context), "file_history.json")
    private val bookmarkFile = File(AppDataPaths.fileManager(context), "bookmarks.json")
    var historyList by mutableStateOf(
        try {
            if (historyFile.exists()) historyJson.decodeFromString<List<HistoryEntry>>(historyFile.readText())
            else emptyList()
        } catch (_: Exception) { emptyList() }
    )
    var bookmarkList by mutableStateOf(
        try {
            if (bookmarkFile.exists()) historyJson.decodeFromString<List<BookmarkEntry>>(bookmarkFile.readText())
            else emptyList()
        } catch (_: Exception) { emptyList() }
    )

    init {
        // 权限级别
        permissionLevel = secPrefs.getString("target_permission_level", null)
            ?: legacySp.getString("target_permission_level", "NORMAL") ?: "NORMAL"
        isRootEngine = permissionLevel == "ROOT" && SpecialPermissionVerifier.isRootAvailable()

        // 一次性旧数据迁移
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

        // 读取主目录
        fun resolveHome(saved: String): String {
            val dir = File(saved)
            if (!dir.exists() || !dir.isDirectory) return safeDefault
            if (!hasShellEngine && !dir.canRead()) return safeDefault
            return saved
        }
        val lHome = resolveHome(
            fmPrefs.getString("left_home_directory", null)
                ?: legacySp.getString("left_home_directory", safeDefault)
                ?: safeDefault
        )
        val rHome = resolveHome(
            fmPrefs.getString("right_home_directory", null)
                ?: legacySp.getString("right_home_directory", safeDefault)
                ?: safeDefault
        )
        leftPath = lHome
        rightPath = rHome
        leftNavState = PanelNavState(paths = listOf(lHome), index = 0)
        rightNavState = PanelNavState(paths = listOf(rHome), index = 0)

        // 读取设置
        showHiddenFiles = fmPrefs.getBoolean("show_hidden_files", false)
        sortField = when (fmPrefs.getString("sort_field", "NAME")) {
            "SIZE" -> SortField.SIZE
            "MODIFIED" -> SortField.MODIFIED
            "CREATED" -> SortField.CREATED
            else -> SortField.NAME
        }
        sortOrder = if (fmPrefs.getString("sort_order", "ASC") == "DESC") SortOrder.DESC else SortOrder.ASC
        jxlPackZip = fmPrefs.getBoolean("jxl_pack_zip", false)

        // 加载文件夹大小数据库
        folderSizeDb = FolderSizeDb.load(AppDataPaths.fileManager(context))

        // 初始加载
        leftEntries = listDirectory(lHome)
        rightEntries = listDirectory(rHome)
    }

    /**
     * 判断路径是否位于受 Scoped Storage 保护的目录下。
     */
    private fun isProtectedPath(path: String): Boolean =
        path.contains("/Android/data") || path.contains("/Android/obb")

    /**
     * 通过 shell 检查路径是否存在（对 Android/data 等受保护路径使用 Shizuku/Root）。
     * 普通路径直接用 Java File API。
     */
    private fun shellPathExists(path: String): Boolean {
        if (!hasShellEngine) return File(path).exists()
        val escaped = path.replace("'", "'\\''")
        val useShizuku = SpecialPermissionVerifier.isShizukuAuthorized(getApplication())
        val (_, _, exit) = try {
            when {
                isRootEngine -> SpecialPermissionVerifier.executeRootCommandFull("test -e '$escaped'")
                useShizuku -> SpecialPermissionVerifier.executeShizukuCommand("test -e '$escaped'")
                else -> return File(path).exists()
            }
        } catch (_: Exception) { return false }
        return exit == 0
    }

    /**
     * 通过 shell 检查路径是否为目录。
     */
    private fun shellIsDirectory(path: String): Boolean {
        if (!hasShellEngine) return File(path).isDirectory
        val escaped = path.replace("'", "'\\''")
        val useShizuku = SpecialPermissionVerifier.isShizukuAuthorized(getApplication())
        val (_, _, exit) = try {
            when {
                isRootEngine -> SpecialPermissionVerifier.executeRootCommandFull("test -d '$escaped'")
                useShizuku -> SpecialPermissionVerifier.executeShizukuCommand("test -d '$escaped'")
                else -> return File(path).isDirectory
            }
        } catch (_: Exception) { return false }
        return exit == 0
    }

    /**
     * 检查路径是否可读（受保护路径走 shell，普通路径走 Java API）。
     */
    fun canAccessPath(path: String): Boolean = shellPathExists(path)

    /**
     * 检查路径是否为目录（受保护路径走 shell，普通路径走 Java API）。
     */
    fun isDirectoryShell(path: String): Boolean = shellIsDirectory(path)

    /**
     * 通过 shell 列出目录直接子项，返回列表（空目录返回空列表，失败返回 null）。
     * 用于替代 Java File.listFiles()，受保护路径走 shell。
     */
    fun listChildrenOrNull(path: String): List<FileEntry>? {
        if (hasShellEngine) return listDirChildrenViaShell(path)
        return try { File(path).listFiles()?.map { f ->
            FileEntry(f.absolutePath, f.name, f.isDirectory, "", if (f.isDirectory) 0L else f.length(), f.lastModified())
        } } catch (_: Exception) { null }
    }

    /**
     * 根据当前引擎执行 shell 命令（Root 优先，回退 Shizuku）。
     * 用于受保护路径的文件操作。
     */
    private fun executeShell(cmd: String): Triple<String, String, Int> {
        val useShizuku = !isRootEngine && SpecialPermissionVerifier.isShizukuAuthorized(getApplication())
        return when {
            isRootEngine -> SpecialPermissionVerifier.executeRootCommandFull(cmd)
            useShizuku -> SpecialPermissionVerifier.executeShizukuCommand(cmd)
            else -> Triple("", "无可用权限引擎", -1)
        }
    }

    /**
     * 格式化 shell 错误信息，输出中文提示 + 原始英文报错。
     */
    private fun formatShellError(name: String, stderr: String): String {
        val detail = stderr.trim().ifBlank { "未知错误" }
        return when {
            detail.contains("Permission denied", ignoreCase = true) -> "$name 权限不足: $detail"
            detail.contains("No such file or directory", ignoreCase = true) -> "$name 不存在: $detail"
            else -> "$name 错误: $detail"
        }
    }

    /**
     * 从 ls -lap 输出行中精确提取原始文件名（保留多空格）。
     * 跳过前 7 个空白分隔字段后，剩余全部为原始文件名。
     */
    private fun parseLsFilename(line: String): String? {
        var pos = 0
        repeat(7) {
            while (pos < line.length && line[pos].isWhitespace()) pos++
            if (pos >= line.length) return null
            while (pos < line.length && !line[pos].isWhitespace()) pos++
        }
        while (pos < line.length && line[pos].isWhitespace()) pos++
        return if (pos < line.length) line.substring(pos) else null
    }

    /**
     * 通过 shell 命令列出目录内容（Shizuku / Root / 普通 shell）。
     * 用于访问 Android/data 等受 Scoped Storage 保护的目录。
     * @return 条目列表，失败返回空列表并设置 lastShellStderr
     */
    private fun listDirEntriesViaShell(path: String, showHidden: Boolean, longFormat: Boolean = false): List<FileEntry> {
        val normalizedPath = if (path == "/") "/" else path.trimEnd('/').ifEmpty { "/" }
        val escapedPath = normalizedPath.replace("'", "'\\''")
        val flags = buildString {
            append("-l")
            if (showHidden) append("a")
            append("p")
        }
        val command = "ls $flags '$escapedPath'"

        val useShizuku = !isRootEngine && SpecialPermissionVerifier.isShizukuAuthorized(getApplication())
        val (stdout, stderr, exitCode) = try {
            when {
                isRootEngine -> SpecialPermissionVerifier.executeRootCommandFull(command)
                useShizuku -> SpecialPermissionVerifier.executeShizukuCommand(command)
                else -> {
                    lastShellStderr = "无可用权限引擎"
                    return emptyList()
                }
            }
        } catch (e: Throwable) {
            DiagnosticLog.log("ShellLs", "执行异常: ${e.message}")
            lastShellStderr = e.message ?: "执行异常"
            return emptyList()
        }

        DiagnosticLog.log("ShellLs", "cmd=$command exit=$exitCode out=${stdout.length} err=${stderr.length}")
        if (exitCode != 0 && stdout.isBlank()) {
            DiagnosticLog.log("ShellLs", "失败: $stderr")
            lastShellStderr = stderr
            return emptyList()
        }
        lastShellStderr = ""

        val entries = mutableListOf<FileEntry>()
        for (raw in stdout.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            // 跳过 "total N" 行
            if (line.startsWith("total ")) continue

            // 解析 ls -lap 输出: drwxrwx--x  4 root sdcard_rw  4096 2024-01-01 00:00 dirname/
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 8) continue

            val perms = parts[0]
            if (perms.length < 10) continue

            val nameWithSlash = parseLsFilename(line) ?: continue
            val isDir = nameWithSlash.endsWith("/")
            val name = if (isDir) nameWithSlash.dropLast(1) else nameWithSlash
            if (name == "." || name == "..") continue
            if (!showHidden && name.startsWith(".")) continue

            val size = parts[4].toLongOrNull() ?: 0L
            val childPath = if (normalizedPath == "/") "/$name" else "$normalizedPath/$name"
            val modified = try {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse("${parts[5]} ${parts[6]}")?.time ?: 0L
            } catch (_: Exception) { 0L }
            entries.add(FileEntry(childPath, name, isDir, perms, if (isDir) 0L else size, modified))
        }
        return entries
    }

    // ── 便捷属性 ──
    val currentPath: String get() = if (focusedPanel == FocusedPanel.LEFT) leftPath else rightPath
    val currentNavState: PanelNavState get() = if (focusedPanel == FocusedPanel.LEFT) leftNavState else rightNavState

    // ── 滚动位置保存（按路径记忆，内存中，应用关闭自动清空） ──
    private val scrollPositions = HashMap<String, Pair<Int, Int>>()

    /** 保存当前聚焦面板的滚动位置（按路径存储） */
    fun saveScrollPosition(leftIndex: Int, leftOffset: Int, rightIndex: Int, rightOffset: Int) {
        if (focusedPanel == FocusedPanel.LEFT) {
            scrollPositions[leftPath] = leftIndex to leftOffset
        } else {
            scrollPositions[rightPath] = rightIndex to rightOffset
        }
    }

    /** 读取指定路径的滚动位置 */
    fun getScrollPosition(path: String): Pair<Int, Int>? = scrollPositions[path]

    /** 待恢复滚动的目标路径（非 null 时触发 LaunchedEffect 恢复滚动） */
    var pendingScrollRestore by mutableStateOf<String?>(null)

    // ── 核心导航：切换路径 + 刷新列表 ──
    fun navigateTo(path: String) {
        if (isInRecycleBin) isInRecycleBin = false
        if (isInArchive) exitArchive()
        if (focusedPanel == FocusedPanel.LEFT) {
            if (leftPath == path) return
            leftNavState = leftNavState.navigate(path)
            leftPath = path
            leftEntries = listDirectory(path)
        } else {
            if (rightPath == path) return
            rightNavState = rightNavState.navigate(path)
            rightPath = path
            rightEntries = listDirectory(path)
        }
    }

    // ── 导航操作 ──
    fun navigateToFolder(entry: FileEntry) {
        if (hasShellEngine) {
            // 最高权限优先：Root(libsu) 或 Shizuku/ADB
            listDirEntriesViaShell(entry.path, showHiddenFiles)
            if (lastShellStderr.isBlank()) {
                navigateTo(entry.path)
                historyList = listOf(HistoryEntry(entry.name, entry.path, true)) + historyList
            } else {
                // shell 失败，回退 Java API
                val testDir = File(entry.path)
                val accessible = try { testDir.listFiles() } catch (_: Exception) { null }
                if (accessible != null) {
                    navigateTo(entry.path)
                    historyList = listOf(HistoryEntry(entry.name, entry.path, true)) + historyList
                } else {
                    loadError = RuntimeException("${formatShellError(entry.name, lastShellStderr)}\n路径: ${entry.path}")
                }
            }
        } else {
            // 无 shell 引擎，用 Java File API
            val testDir = File(entry.path)
            val accessible = try { testDir.listFiles() } catch (_: Exception) { null }
            if (accessible != null) {
                navigateTo(entry.path)
                historyList = listOf(HistoryEntry(entry.name, entry.path, true)) + historyList
            } else if (!testDir.exists()) {
                loadError = RuntimeException("文件夹不存在: ${entry.name}\n路径: ${entry.path}")
            } else {
                loadError = RuntimeException("权限不足: ${entry.name}\n路径: ${entry.path}")
            }
        }
    }

    fun navigateToHistoryDir(entry: HistoryEntry) {
        if (hasShellEngine) {
            listDirEntriesViaShell(entry.path, showHiddenFiles)
            if (lastShellStderr.isBlank()) navigateTo(entry.path)
            else loadError = RuntimeException("${formatShellError(entry.name, lastShellStderr)}\n路径: ${entry.path}")
        } else {
            val testDir = File(entry.path)
            if (testDir.exists() && testDir.canRead()) navigateTo(entry.path)
        }
    }

    /**
     * 从历史记录点击文件：导航到文件所在父目录，并记录待滚动目标文件名。
     */
    var pendingScrollToFile by mutableStateOf<String?>(null)

    fun navigateToHistoryFile(entry: HistoryEntry) {
        val file = File(entry.path)
        val parentDir = file.parentFile ?: return
        if (hasShellEngine) {
            listDirEntriesViaShell(parentDir.absolutePath, showHiddenFiles)
            if (lastShellStderr.isBlank()) {
                pendingScrollToFile = file.name
                navigateTo(parentDir.absolutePath)
            } else {
                loadError = RuntimeException("${formatShellError(parentDir.name, lastShellStderr)}\n路径: ${parentDir.absolutePath}")
            }
        } else if (parentDir.exists() && parentDir.canRead()) {
            pendingScrollToFile = file.name
            navigateTo(parentDir.absolutePath)
        }
    }

    fun navigateToBookmark(bm: BookmarkEntry) {
        if (hasShellEngine) {
            listDirEntriesViaShell(bm.path, showHiddenFiles)
            if (lastShellStderr.isBlank()) navigateTo(bm.path)
            else loadError = RuntimeException("${formatShellError(bm.name, lastShellStderr)}\n路径: ${bm.path}")
        } else {
            val testDir = File(bm.path)
            if (testDir.exists() && testDir.canRead()) navigateTo(bm.path)
        }
    }

    /** 后退，返回目标路径，null 表示无法后退 */
    fun goBack(): String? {
        val nav = if (focusedPanel == FocusedPanel.LEFT) leftNavState else rightNavState
        val back = nav.back() ?: return null
        navigateTo(back.current)
        return back.current
    }

    /** 前进，返回目标路径，null 表示无法前进 */
    fun goForward(): String? {
        val nav = if (focusedPanel == FocusedPanel.LEFT) leftNavState else rightNavState
        val fwd = nav.forward() ?: return null
        navigateTo(fwd.current)
        return fwd.current
    }

    /** 返回上级目录，返回目标路径，null 表示已在根目录 */
    fun goUp(): String? {
        val effectiveRoot = if (isRootEngine) "/" else safeDefault
        val path = if (focusedPanel == FocusedPanel.LEFT) leftPath else rightPath
        if (path == effectiveRoot || !path.contains('/')) return null
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        if (parent == path) return null
        navigateTo(parent)
        return parent
    }

    fun syncPaths() {
        if (focusedPanel == FocusedPanel.LEFT) {
            rightPath = leftPath
            rightNavState = rightNavState.navigate(leftPath)
            rightEntries = listDirectory(rightPath)
        } else {
            leftPath = rightPath
            leftNavState = leftNavState.navigate(rightPath)
            leftEntries = listDirectory(leftPath)
        }
    }

    fun refreshCurrent() {
        if (focusedPanel == FocusedPanel.LEFT) {
            leftEntries = listDirectory(leftPath)
        } else {
            rightEntries = listDirectory(rightPath)
        }
    }

    fun refreshBoth() {
        leftEntries = listDirectory(leftPath)
        rightEntries = listDirectory(rightPath)
    }

    // ── 文件夹大小统计 ──

    /** 选用当前可用的最高权限通道（ROOT > SHIZUKU > NORMAL）。 */
    private fun detectMaxAvailablePermission(): FileAccessLevel = when {
        SpecialPermissionVerifier.isRootAvailable() -> FileAccessLevel.ROOT
        SpecialPermissionVerifier.isShizukuAuthorized(context) -> FileAccessLevel.SHIZUKU
        else -> FileAccessLevel.NORMAL
    }

    /**
     * 异步统计指定目录大小（含整棵子树）。
     * - 长按入口：传入文件夹自身路径
     * - 批量入口（菜单/排序）：传入当前面板路径作为父目录
     *
     * 完成后将 FolderSizeDb 持久化并刷新当前面板列表。
     */
    fun calculateFolderSizeAsync(rootPath: String) {
        if (SizeCalcManager.isCalculating) {
            Toast.makeText(context, "已有统计任务在进行中", Toast.LENGTH_SHORT).show()
            return
        }
        val permission = detectMaxAvailablePermission()
        val accessor = FileAccessor.create(permission, context)
        val saveDir = AppDataPaths.fileManager(context)
        SizeCalcManager.begin(folderSizeDb, saveDir, onDiscard = {
            folderSizeDb = FolderSizeDb.load(saveDir)
            refreshCurrent()
        })
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                calculateFolderSize(
                    rootPath = rootPath,
                    accessor = accessor,
                    db = folderSizeDb,
                    onTotal = { total -> SizeCalcManager.onTotal(total) },
                    onScanned = { count, folder -> SizeCalcManager.onScanned(count, folder) },
                    onProgress = { p, t, f -> SizeCalcManager.onProgress(p, t, f) },
                    isCancelled = { SizeCalcManager.cancelRequested },
                    onBinderCooldown = { sec -> SizeCalcManager.onBinderCooldown(sec) }
                )
            } catch (e: Throwable) {
                SizeCalcResult.Failed(e.message ?: "未知错误")
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is SizeCalcResult.Success -> {
                        folderSizeDb.save(saveDir)
                        folderSizeDb = FolderSizeDb.load(saveDir)
                        refreshCurrent()
                        SizeCalcManager.finish(result.rootSize)
                    }
                    is SizeCalcResult.PermissionDenied -> {
                        // 弹窗询问用户是否保存已统计的部分结果
                        SizeCalcManager.finish()
                        SizeCalcManager.pendingSaveDialog = true
                        SizeCalcManager.loadError = RuntimeException(
                            "权限不足，部分目录无法访问\n路径: ${result.path}"
                        )
                    }
                    is SizeCalcResult.Failed -> {
                        // 弹窗询问用户是否保存已统计的部分结果
                        SizeCalcManager.finish()
                        SizeCalcManager.pendingSaveDialog = true
                        SizeCalcManager.loadError = RuntimeException("统计失败: ${result.reason}")
                    }
                    is SizeCalcResult.Cancelled -> {
                        // 用户取消，丢弃本次数据
                        folderSizeDb = FolderSizeDb.load(saveDir)
                        refreshCurrent()
                        SizeCalcManager.finish()
                    }
                }
            }
        }
    }

    /** 删除指定目录的大小缓存（含子树），保存并刷新当前列表。 */
    fun deleteSizeCacheAndRefresh(path: String) {
        folderSizeDb.removeDescendants(path)
        val saveDir = AppDataPaths.fileManager(context)
        folderSizeDb.save(saveDir)
        folderSizeDb = FolderSizeDb.load(saveDir)
        refreshCurrent()
    }

    /** 忽略缓存，强制全量重新统计指定目录大小。 */
    fun recalculateFolderSizeForce(path: String) {
        folderSizeDb.removeDescendants(path)
        calculateFolderSizeAsync(path)
    }

    // ── 回收站操作 ──

    private fun loadRecycleBinMeta() {
        val metaFile = AppDataPaths.recycleBinMeta(context)
        recycleBinMetaList = try {
            if (metaFile.exists()) recycleBinJson.decodeFromString<List<RecycleBinEntry>>(metaFile.readText())
            else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun saveRecycleBinMeta() {
        try {
            AppDataPaths.recycleBinMeta(context).writeText(recycleBinJson.encodeToString(recycleBinMetaList))
        } catch (_: Exception) {}
    }

    /**
     * 移动文件到回收站。成功返回 null，失败返回错误信息。
     */
    fun moveToRecycleBin(entry: FileEntry): String? {
        val binDir = AppDataPaths.recycleBin(context)
        if (!shellPathExists(entry.path)) return "文件不存在"

        // 同名冲突时追加时间戳后缀
        var targetName = entry.name
        var target = File(binDir, targetName)
        if (target.exists()) {
            val ts = System.currentTimeMillis() / 1000
            val dotIdx = entry.name.lastIndexOf('.')
            targetName = if (dotIdx > 0) {
                "${entry.name.substring(0, dotIdx)}_${ts}${entry.name.substring(dotIdx)}"
            } else {
                "${entry.name}_${ts}"
            }
            target = File(binDir, targetName)
        }

        if (hasShellEngine) {
            val escapedSrc = entry.path.replace("'", "'\\''")
            val escapedDst = target.absolutePath.replace("'", "'\\''")
            val cpFlag = if (entry.isDirectory) "-rf" else "-f"
            val (_, cpErr, cpExit) = try {
                executeShell("cp $cpFlag '$escapedSrc' '$escapedDst'")
            } catch (e: Exception) { return e.message ?: "复制失败" }
            if (cpExit != 0) return "复制失败: $cpErr"
            val rmFlag = if (entry.isDirectory) "-rf" else "-f"
            executeShell("rm $rmFlag '$escapedSrc'")
        } else {
            val source = File(entry.path)
            try {
                val moved = source.renameTo(target)
                if (!moved) {
                    if (entry.isDirectory) {
                        source.copyRecursively(target, overwrite = false)
                    } else {
                        source.copyTo(target, overwrite = false)
                    }
                    SpecialPermissionVerifier.safeDelete(source)
                }
            } catch (e: Exception) {
                return e.message ?: "移动失败"
            }
        }

        // 写入元数据
        recycleBinMetaList = recycleBinMetaList + RecycleBinEntry(
            binName = targetName,
            originalPath = entry.path,
            deletedAt = System.currentTimeMillis(),
            isDirectory = entry.isDirectory
        )
        saveRecycleBinMeta()
        return null
    }

    /**
     * 重命名文件或文件夹。成功返回 null，失败返回错误信息。
     */
    fun renameEntry(entry: FileEntry, newName: String): String? {
        val source = File(entry.path)
        val parent = source.parentFile ?: return "无法获取父目录"
        val dest = File(parent, newName)

        if (hasShellEngine) {
            val escapedSrc = entry.path.replace("'", "'\\''")
            val escapedDst = dest.absolutePath.replace("'", "'\\''")
            val (_, err, exit) = try {
                executeShell("mv '$escapedSrc' '$escapedDst'")
            } catch (e: Exception) { return e.message ?: "重命名失败" }
            return if (exit == 0) null else "重命名失败: $err"
        }

        if (dest.exists()) return "已存在同名文件或文件夹"
        return try {
            if (source.renameTo(dest)) null else "重命名失败"
        } catch (e: Exception) { e.message ?: "重命名失败" }
    }

    /**
     * 永久删除文件或文件夹。成功返回 null，失败返回错误信息。
     */
    fun deleteEntry(entry: FileEntry): String? {
        if (hasShellEngine) {
            val escaped = entry.path.replace("'", "'\\''")
            val flag = if (entry.isDirectory) "-rf" else "-f"
            val (_, err, exit) = try {
                executeShell("rm $flag '$escaped'")
            } catch (e: Exception) { return e.message ?: "删除失败" }
            return if (exit == 0) null else "删除失败: $err"
        }
        val file = File(entry.path)
        return try {
            if (SpecialPermissionVerifier.safeDelete(file)) null else "删除失败"
        } catch (e: Exception) { e.message ?: "删除失败" }
    }

    /**
     * 创建文件或文件夹。成功返回 null，失败返回错误信息。
     */
    fun createEntry(parentPath: String, name: String, isFolder: Boolean): String? {
        val target = File(parentPath, name)

        if (hasShellEngine) {
            val escaped = target.absolutePath.replace("'", "'\\''")
            val cmd = if (isFolder) "mkdir '$escaped'" else "touch '$escaped'"
            val (_, err, exit) = try {
                executeShell(cmd)
            } catch (e: Exception) { return e.message ?: "创建失败" }
            return if (exit == 0) null else "创建失败: $err"
        }

        if (target.exists()) return "已存在同名文件或文件夹"
        return try {
            val success = if (isFolder) target.mkdir() else target.createNewFile()
            if (success) null else "创建失败"
        } catch (e: Exception) { e.message ?: "创建失败" }
    }

    /**
     * 移动文件/文件夹到目标目录。成功返回 null，失败返回错误信息。
     */
    fun moveEntry(source: FileEntry, destDir: String): String? {
        val dest = File(destDir, source.name)

        if (hasShellEngine) {
            val escapedSrc = source.path.replace("'", "'\\''")
            val escapedDst = dest.absolutePath.replace("'", "'\\''")
            val cpFlag = if (source.isDirectory) "-rf" else "-f"
            val (_, cpErr, cpExit) = try {
                executeShell("cp $cpFlag '$escapedSrc' '$escapedDst'")
            } catch (e: Exception) { return e.message ?: "移动失败" }
            if (cpExit != 0) return "移动失败: $cpErr"
            val rmFlag = if (source.isDirectory) "-rf" else "-f"
            executeShell("rm $rmFlag '$escapedSrc'")
            return null
        }

        val sourceFile = File(source.path)
        return try {
            val moved = sourceFile.renameTo(dest)
            if (!moved) {
                if (source.isDirectory) {
                    sourceFile.copyRecursively(dest, overwrite = false)
                } else {
                    sourceFile.copyTo(dest, overwrite = false)
                }
                SpecialPermissionVerifier.safeDelete(sourceFile)
            }
            null
        } catch (e: Exception) { e.message ?: "移动失败" }
    }

    /**
     * 复制文件或文件夹到目标目录。成功返回 null，失败返回错误信息。
     */
    fun copyEntry(source: FileEntry, destDir: String): String? {
        val dest = File(destDir, source.name)

        if (hasShellEngine) {
            val escapedSrc = source.path.replace("'", "'\\''")
            val escapedDst = dest.absolutePath.replace("'", "'\\''")
            val flag = if (source.isDirectory) "-rf" else "-f"
            val (_, err, exit) = try {
                executeShell("cp $flag '$escapedSrc' '$escapedDst'")
            } catch (e: Exception) { return e.message ?: "复制失败" }
            return if (exit == 0) null else "复制失败: $err"
        }

        val sourceFile = File(source.path)
        return try {
            if (source.isDirectory) {
                sourceFile.copyRecursively(dest, overwrite = false)
            } else {
                sourceFile.copyTo(dest, overwrite = false)
            }
            null
        } catch (e: Exception) { e.message ?: "复制失败" }
    }

    /**
     * 永久删除回收站中的文件。成功返回 null，失败返回错误信息。
     */
    fun permanentDelete(binName: String): String? {
        val binDir = AppDataPaths.recycleBin(context)
        val file = File(binDir, binName)
        if (!file.exists()) {
            // 文件已不存在，只清理元数据
            recycleBinMetaList = recycleBinMetaList.filter { it.binName != binName }
            saveRecycleBinMeta()
            return null
        }
        return try {
            SpecialPermissionVerifier.safeDelete(file)
            recycleBinMetaList = recycleBinMetaList.filter { it.binName != binName }
            saveRecycleBinMeta()
            null
        } catch (e: Exception) {
            e.message ?: "删除失败"
        }
    }

    /**
     * 从回收站恢复文件到原位置。成功返回 null，失败返回错误信息。
     */
    fun restoreFromRecycleBin(binName: String): String? {
        val binDir = AppDataPaths.recycleBin(context)
        val file = File(binDir, binName)
        val meta = recycleBinMetaList.find { it.binName == binName }
            ?: return "元数据不存在"

        val originalPath = meta.originalPath
        val originalFile = File(originalPath)

        // 检查原路径父目录是否存在
        val parentDir = originalFile.parentFile
        if (parentDir == null || !parentDir.exists()) {
            return "原目录不存在: ${parentDir?.absolutePath}"
        }

        // 检查原路径是否已有同名文件
        if (originalFile.exists()) {
            return "目标位置已存在同名文件: ${originalFile.name}"
        }

        try {
            val moved = file.renameTo(originalFile)
            if (!moved) {
                if (file.isDirectory) {
                    file.copyRecursively(originalFile, overwrite = false)
                } else {
                    file.copyTo(originalFile, overwrite = false)
                }
                SpecialPermissionVerifier.safeDelete(file)
            }
        } catch (e: Exception) {
            return e.message ?: "恢复失败"
        }

        // 从元数据中移除
        recycleBinMetaList = recycleBinMetaList.filter { it.binName != binName }
        saveRecycleBinMeta()
        return null
    }

    /**
     * 进入回收站视图：将聚焦面板的 entries 替换为回收站内容。
     */
    fun enterRecycleBin() {
        loadRecycleBinMeta()
        val binDir = AppDataPaths.recycleBin(context)
        recycleBinPath = binDir.absolutePath
        isInRecycleBin = true
        val entries = listRecycleBinDir(binDir)
        if (focusedPanel == FocusedPanel.LEFT) {
            leftEntries = entries
        } else {
            rightEntries = entries
        }
    }

    /** 在回收站内进入子文件夹 */
    fun navigateInRecycleBin(entry: FileEntry) {
        if (!entry.isDirectory) return
        val dir = java.io.File(entry.path)
        if (!dir.exists() || !dir.canRead()) {
            Toast.makeText(context, "权限不足: ${entry.name}", Toast.LENGTH_SHORT).show()
            return
        }
        recycleBinPath = entry.path
        val entries = listRecycleBinDir(dir)
        if (focusedPanel == FocusedPanel.LEFT) {
            leftEntries = entries
        } else {
            rightEntries = entries
        }
    }

    /** 在回收站内返回上一级 */
    fun goUpInRecycleBin(): Boolean {
        val binRoot = AppDataPaths.recycleBin(context).absolutePath
        if (recycleBinPath == binRoot) return false
        val parent = java.io.File(recycleBinPath).parentFile ?: return false
        recycleBinPath = parent.absolutePath
        val entries = listRecycleBinDir(parent)
        if (focusedPanel == FocusedPanel.LEFT) {
            leftEntries = entries
        } else {
            rightEntries = entries
        }
        return true
    }

    private fun listRecycleBinDir(dir: java.io.File): List<FileEntry> {
        return (dir.listFiles() ?: emptyArray())
            .filter { it.name != ".meta.json" }
            .map { f ->
                FileEntry(
                    path = f.absolutePath,
                    name = f.name,
                    isDirectory = f.isDirectory,
                    size = if (f.isFile) f.length() else 0,
                    lastModified = f.lastModified()
                )
            }
            .sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /** 回收站是否在根目录 */
    val isAtRecycleBinRoot: Boolean get() {
        val binRoot = AppDataPaths.recycleBin(context).absolutePath
        return recycleBinPath == binRoot
    }

    // ── 压缩包浏览操作 ──

    /** 压缩包是否在根目录 */
    val isAtArchiveRoot: Boolean get() = archivePath == archiveRootPath

    /**
     * 打开压缩包进入预览模式。
     * @param entry 压缩包文件的 FileEntry
     * @param password 密码（空=无密码）
     * @return 错误信息，null 表示成功
     */
    fun openArchive(entry: FileEntry, password: String): String? {
        try {
            // 检测格式
            val format = CompressService.detectFormat(entry.name)
                ?: CompressService.detectFormatByMagic(File(entry.path))
                ?: return "不支持的压缩格式"

            // RAR5 检测：读取文件头 version byte，RAR5 的 header version >= 50
            if (format == "rar") {
                try {
                    val fis = File(entry.path).inputStream()
                    val header = ByteArray(7)
                    fis.read(header)
                    fis.close()
                    // RAR 格式: "Rar!\x1a\x07" 后第7字节是 header version
                    // RAR4: version byte = 0x01, RAR5: version byte >= 0x02
                    if (header.size >= 7 && header[6].toInt() and 0xFF >= 2) {
                        return "不支持 RAR5+ 格式"
                    }
                } catch (_: Exception) {}
            }

            // 密码验证
            if (password.isNotEmpty()) {
                if (!CompressService.verifyPassword(entry.path, format, password)) {
                    return "密码错误"
                }
            }

            // 读取索引
            val memFs = CompressService.openArchiveIndex(entry.path, format, password)

            archiveMemFs = memFs
            archiveFilePath = entry.path
            archiveFileName = entry.name
            archiveFormat = format
            archivePassword = password
            archivePath = ""
            archiveRootPath = ""
            isInArchive = true

            // 设置聚焦面板的 entries 为压缩包根目录内容
            val rootEntries = listArchiveDir("")
            if (focusedPanel == FocusedPanel.LEFT) {
                leftEntries = rootEntries
            } else {
                rightEntries = rootEntries
            }
            return null
        } catch (e: Exception) {
            Log.e("FileMgr", "打开压缩包失败", e)
            return "打开失败: ${e.message}"
        }
    }

    /** 在压缩包内进入子文件夹 */
    fun navigateInArchive(entry: FileEntry) {
        if (!entry.isDirectory) return
        val memFs = archiveMemFs ?: return
        val targetPath = if (archivePath.isEmpty()) entry.name else "$archivePath/${entry.name}"
        // 验证目标路径在压缩包内存在
        val normalizedPath = targetPath.trimEnd('/')
        val hasDir = memFs.entries.containsKey(normalizedPath) &&
                memFs.entries[normalizedPath] is CompressService.ArchiveMemDir
        val hasFiles = memFs.entries.keys.any { it.startsWith("$normalizedPath/") }
        if (!hasDir && !hasFiles) {
            Toast.makeText(context, "目录不存在: ${entry.name}", Toast.LENGTH_SHORT).show()
            return
        }
        archivePath = targetPath
        val entries = listArchiveDir(targetPath)
        if (focusedPanel == FocusedPanel.LEFT) {
            leftEntries = entries
        } else {
            rightEntries = entries
        }
    }

    /** 在压缩包内返回上一级 */
    fun goUpInArchive(): Boolean {
        if (archivePath == archiveRootPath) return false
        val parent = archivePath.substringBeforeLast("/", "")
        archivePath = parent
        val entries = listArchiveDir(parent)
        if (focusedPanel == FocusedPanel.LEFT) {
            leftEntries = entries
        } else {
            rightEntries = entries
        }
        return true
    }

    /** 退出压缩包预览模式 */
    fun exitArchive() {
        // 关闭 reader 释放句柄
        try { archiveMemFs?.reader?.close() } catch (_: Exception) {}
        // 先保存路径用于清理临时文件，再清空状态
        val tempFileHash = archiveFilePath.hashCode().toString().take(16)
        archiveMemFs = null
        isInArchive = false
        archivePath = ""
        archiveRootPath = ""
        archiveFilePath = ""
        archiveFileName = ""
        archiveFormat = ""
        archivePassword = ""
        // 清理临时解压文件
        try {
            val tempDir = File(AppDataPaths.archiveCache(context), tempFileHash)
            if (tempDir.exists()) tempDir.deleteRecursively()
        } catch (_: Exception) {}
        refreshCurrent()
    }

    /** 列出压缩包内指定路径下的条目 */
    private fun listArchiveDir(dirPath: String): List<FileEntry> {
        val memFs = archiveMemFs ?: return emptyList()
        val normalized = dirPath.trimEnd('/')
        // prefix 统一不带前导 /，与 entries map key 格式一致
        val prefix = if (normalized == "/" || normalized.isEmpty()) "" else normalized.removePrefix("/") + "/"

        val dirNames = mutableSetOf<String>()
        val files = mutableListOf<FileEntry>()

        for ((path, entry) in memFs.entries) {
            // path 统一去掉前导 / 再处理
            val cleanPath = path.removePrefix("/")
            if (prefix.isEmpty()) {
                // 根目录：取第一级
                if (cleanPath.isEmpty()) continue
                val slashIdx = cleanPath.indexOf('/')
                if (slashIdx < 0) {
                    if (entry is CompressService.ArchiveMemFile) {
                        files.add(FileEntry(
                            path = cleanPath,
                            name = entry.name,
                            isDirectory = false,
                            size = entry.size,
                            lastModified = 0
                        ))
                    } else if (entry is CompressService.ArchiveMemDir) {
                        dirNames.add(cleanPath)
                    }
                } else {
                    dirNames.add(cleanPath.substring(0, slashIdx))
                }
            } else {
                // 子目录：匹配前缀
                if (!cleanPath.startsWith(prefix)) continue
                val relative = cleanPath.removePrefix(prefix)
                if (relative.isEmpty()) continue
                val slashIdx = relative.indexOf('/')
                if (slashIdx < 0) {
                    if (entry is CompressService.ArchiveMemFile) {
                        files.add(FileEntry(
                            path = cleanPath,
                            name = entry.name,
                            isDirectory = false,
                            size = entry.size,
                            lastModified = 0
                        ))
                    } else if (entry is CompressService.ArchiveMemDir) {
                        dirNames.add(relative)
                    }
                } else {
                    dirNames.add(relative.substring(0, slashIdx))
                }
            }
        }

        // 构建目录 FileEntry（带 ArchiveMemDir.size）
        val dirEntries = dirNames.map { dirName ->
            val fullPath = if (prefix.isEmpty()) dirName else "$prefix$dirName"
            val dirSize = (memFs.entries[fullPath] as? CompressService.ArchiveMemDir)?.size ?: 0L
            FileEntry(
                path = fullPath,
                name = dirName,
                isDirectory = true,
                size = dirSize,
                lastModified = 0
            )
        }

        // 合并并排序（目录优先，然后按 sortField 排序）
        val allEntries = dirEntries + files
        return when (sortField) {
            SortField.NAME -> if (sortOrder == SortOrder.ASC)
                allEntries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            else
                allEntries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.name.lowercase() })
            SortField.SIZE -> if (sortOrder == SortOrder.ASC)
                allEntries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.size })
            else
                allEntries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.size } )
            SortField.MODIFIED -> allEntries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            SortField.CREATED -> allEntries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    /**
     * 打开压缩包内的文件（按需解压）。
     * 小于 50MB → 解压到内存（ByteArray），速度快
     * 大于等于 50MB → 解压到磁盘临时文件，避免内存溢出
     */
    fun openFileInArchive(context: Context, entry: FileEntry): Screen? {
        val memFs = archiveMemFs ?: return null
        val memEntry = memFs.entries[entry.path] as? CompressService.ArchiveMemFile ?: return null

        val textExtensions = setOf(
            "txt", "md", "json", "xml", "html", "htm", "css", "js",
            "kt", "java", "py", "sh", "bat", "log", "csv", "yaml", "yml",
            "toml", "ini", "conf", "cfg", "properties", "gradle", "kts",
            "c", "cpp", "h", "hpp", "rs", "go", "rb", "php", "sql",
            "lua", "r", "swift", "dart", "ts", "jsx", "tsx", "vue"
        )
        val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "jxl", "thumb")
        val ext = entry.name.substringAfterLast('.', "").lowercase()

        val useMemory = memEntry.size in 1 until EXTRACT_TO_MEMORY_THRESHOLD

        return try {
            if (ext in textExtensions) {
                // 文本文件：解压到内存再写临时文件（文本编辑器需要文件路径）
                val data = CompressService.extractSingleFile(
                    archiveFilePath, archiveFormat, archivePassword, memEntry
                )
                val tempDir = getArchiveTempDir(context)
                val tempFile = File(tempDir, memEntry.name)
                tempFile.writeBytes(data)
                Screen.TextEditor(tempFile.absolutePath)
            } else if (ext in imageExtensions) {
                // 图片文件
                val tempDir = getArchiveTempDir(context)
                val tempFile = if (useMemory) {
                    // 小于 50MB：解压到内存再写临时文件
                    val data = CompressService.extractSingleFile(
                        archiveFilePath, archiveFormat, archivePassword, memEntry
                    )
                    val f = File(tempDir, memEntry.name)
                    f.writeBytes(data)
                    f
                } else {
                    // 大于等于 50MB：直接解压到磁盘
                    CompressService.extractSingleFileToDisk(
                        archiveFilePath, archiveFormat, archivePassword, memEntry, tempDir
                    )
                }
                // 构建图片列表（压缩包内所有图片）
                val currentEntries = if (focusedPanel == FocusedPanel.LEFT) leftEntries else rightEntries
                val imagePaths = currentEntries
                    .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in imageExtensions }
                    .mapNotNull { e ->
                        val me = memFs.entries[e.path] as? CompressService.ArchiveMemFile ?: return@mapNotNull null
                        try {
                            val imgData = if (me.size in 1 until EXTRACT_TO_MEMORY_THRESHOLD) {
                                CompressService.extractSingleFile(archiveFilePath, archiveFormat, archivePassword, me)
                            } else null
                            val tf = if (imgData != null) {
                                val f = File(tempDir, me.name)
                                f.writeBytes(imgData)
                                f
                            } else {
                                CompressService.extractSingleFileToDisk(
                                    archiveFilePath, archiveFormat, archivePassword, me, tempDir
                                )
                            }
                            tf.absolutePath
                        } catch (_: Exception) { null }
                    }
                val startIndex = imagePaths.indexOf(tempFile.absolutePath).coerceAtLeast(0)
                Screen.ImageViewer(tempFile.absolutePath, imagePaths, startIndex)
            } else {
                // 其他文件
                val tempDir = getArchiveTempDir(context)
                val tempFile = if (useMemory) {
                    val data = CompressService.extractSingleFile(
                        archiveFilePath, archiveFormat, archivePassword, memEntry
                    )
                    val f = File(tempDir, memEntry.name)
                    f.writeBytes(data)
                    f
                } else {
                    CompressService.extractSingleFileToDisk(
                        archiveFilePath, archiveFormat, archivePassword, memEntry, tempDir
                    )
                }
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", tempFile
                    )
                    val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                null
            }
        } catch (e: Exception) {
            Toast.makeText(context, "解压失败: ${e.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }

    /** 获取压缩包临时解压目录，创建 .active 标记防止启动清理 */
    private fun getArchiveTempDir(context: Context): File {
        val tempDir = File(AppDataPaths.archiveCache(context), archiveFilePath.hashCode().toString().take(16))
        if (!tempDir.exists()) tempDir.mkdirs()
        File(tempDir, ".active").createNewFile()
        return tempDir
    }

    /** 获取压缩包内文件的压缩率信息文本 */
    fun getArchiveSizeText(entry: FileEntry): String {
        val memFs = archiveMemFs ?: return ""
        val memEntry = memFs.entries[entry.path] ?: return ""
        when (memEntry) {
            is CompressService.ArchiveMemDir -> {
                if (memEntry.size <= 0) return ""
                if (memEntry.compressedSize > 0) {
                    val ratio = (memEntry.compressedSize * 100 / memEntry.size).toInt()
                    return "(${formatSize(memEntry.compressedSize)}/${formatSize(memEntry.size)})($ratio%)"
                }
                return formatSize(memEntry.size)
            }
            is CompressService.ArchiveMemFile -> {
                if (memEntry.size <= 0) return ""
                if (memEntry.compressedSize > 0) {
                    val ratio = (memEntry.compressedSize * 100 / memEntry.size).toInt()
                    return "(${formatSize(memEntry.compressedSize)}/${formatSize(memEntry.size)})($ratio%)"
                }
                return formatSize(memEntry.size)
            }
        }
    }

    /**
     * 退出回收站视图，恢复到正常目录浏览。
     */
    fun exitRecycleBin() {
        isInRecycleBin = false
        recycleBinPath = ""
        refreshCurrent()
    }

    // ── 设置 ──
    fun updateShowHiddenFiles(value: Boolean) {
        showHiddenFiles = value
        fmPrefs.edit().putBoolean("show_hidden_files", value).apply()
        leftEntries = listDirectory(leftPath)
        rightEntries = listDirectory(rightPath)
    }

    fun updateJxlPackZip(value: Boolean) {
        jxlPackZip = value
        fmPrefs.edit().putBoolean("jxl_pack_zip", value).apply()
    }

    fun updateSortField(field: SortField) {
        sortField = field
        fmPrefs.edit().putString("sort_field", field.name).apply()
        if (isInArchive) {
            val entries = listArchiveDir(archivePath)
            if (focusedPanel == FocusedPanel.LEFT) leftEntries = entries else rightEntries = entries
        } else if (isInRecycleBin) {
            val entries = listRecycleBinDir(java.io.File(recycleBinPath))
            if (focusedPanel == FocusedPanel.LEFT) leftEntries = entries else rightEntries = entries
        } else {
            leftEntries = listDirectory(leftPath)
            rightEntries = listDirectory(rightPath)
        }
    }

    fun updateSortOrder(order: SortOrder) {
        sortOrder = order
        fmPrefs.edit().putString("sort_order", order.name).apply()
        if (isInArchive) {
            val entries = listArchiveDir(archivePath)
            if (focusedPanel == FocusedPanel.LEFT) leftEntries = entries else rightEntries = entries
        } else if (isInRecycleBin) {
            val entries = listRecycleBinDir(java.io.File(recycleBinPath))
            if (focusedPanel == FocusedPanel.LEFT) leftEntries = entries else rightEntries = entries
        } else {
            leftEntries = listDirectory(leftPath)
            rightEntries = listDirectory(rightPath)
        }
    }

    fun forceRefresh() {
        refreshVersion++
    }

    // ── 持久化历史 & 书签 ──
    fun saveHistory() {
        try { historyFile.writeText(historyJson.encodeToString(historyList)) } catch (_: Exception) {}
    }

    fun saveBookmarks() {
        try { bookmarkFile.writeText(historyJson.encodeToString(bookmarkList)) } catch (_: Exception) {}
    }

    // ── 目录列表 ──
    fun listDirectory(path: String): List<FileEntry> {
        DiagnosticLog.log("FileMgr", ">>> listDirectory START path=$path useRoot=$isRootEngine")
        loadError = null
        val t0 = System.currentTimeMillis()
        val effectiveRoot = if (isRootEngine) "/" else safeDefault

        var entries = listWithLs(path, showHiddenFiles, useRoot = isRootEngine, effectiveRoot = effectiveRoot)

        // 兜底：ls 完全没结果且报错 → 退到 File.listFiles
        // Android/data 等受保护路径跳过兜底，因为 File API 无法访问
        val isProtectedPath = path.contains("/Android/data") || path.contains("/Android/obb")
        if (entries.isEmpty() && loadError != null && !isProtectedPath) {
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

        // 填充创建时间（API 26+ 使用 NIO）
        if (sortField == SortField.CREATED && android.os.Build.VERSION.SDK_INT >= 26) {
            entries = entries.map { e ->
                if (e.createdAt > 0) return@map e
                val ct = try {
                    Files.readAttributes(File(e.path).toPath(), BasicFileAttributes::class.java).creationTime().toMillis()
                } catch (_: Exception) { e.lastModified }
                e.copy(createdAt = ct)
            }
        }

        // 自定义排序
        entries = when (sortField) {
            SortField.NAME -> if (sortOrder == SortOrder.ASC)
                entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            else
                entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.name.lowercase() })
            SortField.SIZE -> {
                // 获取条目的有效大小：文件用 entry.size，已统计目录用 folderSizeDb，未统计目录用 -1
                fun effectiveSize(entry: FileEntry): Long {
                    if (!entry.isDirectory) return entry.size
                    val cached = folderSizeDb.get(entry.path)
                    return cached?.size ?: -1L // -1 表示未统计
                }
                if (sortOrder == SortOrder.ASC)
                    entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { effectiveSize(it).let { s -> if (s < 0) Long.MAX_VALUE else s } })
                else
                    entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { effectiveSize(it).let { s -> if (s < 0) Long.MIN_VALUE else s } })
            }
            SortField.MODIFIED -> if (sortOrder == SortOrder.ASC)
                entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.lastModified })
            else
                entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.lastModified })
            SortField.CREATED -> if (sortOrder == SortOrder.ASC)
                entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.createdAt })
            else
                entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.createdAt })
        }

        val took = System.currentTimeMillis() - t0
        DiagnosticLog.log("FileMgr", "<<< listDirectory END path=$path entries=${entries.size} took=${took}ms err=${loadError?.javaClass?.simpleName}")
        return entries
    }

    // ── 文件操作 ──
    fun openFile(context: Context, entry: FileEntry): Screen? {
        DiagnosticLog.log("OpenFile", "请求打开: ${entry.path}")
        if (entry.name.endsWith(".apk", ignoreCase = true) ||
            entry.name.endsWith(".apex", ignoreCase = true)
        ) {
            DiagnosticLog.log("OpenFile", "拒绝打开 apk/apex: ${entry.name}")
            Toast.makeText(context, "APK 文件请在应用管理器中安装", Toast.LENGTH_SHORT).show()
            return null
        }
        val textExtensions = setOf(
            "txt", "md", "json", "xml", "html", "htm", "css", "js",
            "kt", "java", "py", "sh", "bat", "log", "csv", "yaml", "yml",
            "toml", "ini", "conf", "cfg", "properties", "gradle", "kts",
            "c", "cpp", "h", "hpp", "rs", "go", "rb", "php", "sql",
            "lua", "r", "swift", "dart", "ts", "jsx", "tsx", "vue"
        )
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        if (ext in textExtensions) {
            DiagnosticLog.log("OpenFile", "内置编辑器打开: ${entry.name}")
            return Screen.TextEditor(entry.path)
        }
        val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "jxl", "thumb")
        if (ext in imageExtensions) {
            DiagnosticLog.log("OpenFile", "内置查看器打开: ${entry.name}")
            val currentEntries = if (focusedPanel == FocusedPanel.LEFT) leftEntries else rightEntries
            val imagePaths = currentEntries
                .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in imageExtensions }
                .map { it.path }
            val startIndex = imagePaths.indexOf(entry.path).coerceAtLeast(0)
            return Screen.ImageViewer(entry.path, imagePaths, startIndex)
        }
        // 外部 Intent
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", File(entry.path)
            )
            val extension = entry.name.substringAfterLast('.', "").lowercase()
            val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            DiagnosticLog.log("OpenFile", "uri=$uri ext='$extension' mime=$mimeType")
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val resolver = intent.resolveActivity(context.packageManager)
            DiagnosticLog.log("OpenFile", "resolveActivity=${resolver?.flattenToString() ?: "(null)"}")
            if (resolver == null) {
                // 没有匹配的应用，设置待处理状态由 UI 弹出警告
                pendingExternalEntry = entry
                return null
            }
            // 使用 createChooser 弹出应用选择器，让用户选择用哪个应用打开
            val chooser = android.content.Intent.createChooser(intent, "选择应用打开")
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            DiagnosticLog.log("OpenFile", "startActivity 已调用，匹配: ${resolver.flattenToString()}")
        } catch (e: Exception) {
            DiagnosticLog.log("OpenFile", "异常: ${e.javaClass.simpleName}: ${e.message}")
            DiagnosticLog.exportCrashReport(context, e, "外部Intent打开失败: ${entry.path}")
            Toast.makeText(context, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        return null
    }

    /**
     * 强行用外部 Intent 打开文件（忽略 resolveActivity 检查）。
     * 返回 null 表示成功，返回错误信息表示失败。
     */
    fun forceOpenExternalFile(context: Context, entry: FileEntry): String? {
        return try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", File(entry.path)
            )
            val extension = entry.name.substringAfterLast('.', "").lowercase()
            val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            DiagnosticLog.log("OpenFile", "强行打开: uri=$uri mime=$mimeType")
            val chooser = android.content.Intent.createChooser(intent, "选择应用打开")
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            DiagnosticLog.log("OpenFile", "强行打开 startActivity 已调用")
            null
        } catch (e: Exception) {
            DiagnosticLog.log("OpenFile", "强行打开异常: ${e.javaClass.simpleName}: ${e.message}")
            DiagnosticLog.exportCrashReport(context, e, "强行打开失败: ${entry.path}")
            "错误类型: ${e.javaClass.simpleName}\n文件: ${entry.path}\n\n${e.stackTraceToString()}"
        }
    }

    // ── 文件夹大小（待重构） ──

    /** 通过 shell 列出目录直接子项（含文件大小），用于受保护目录 */
    private fun listDirChildrenViaShell(dirPath: String): List<FileEntry>? {
        val escapedPath = dirPath.replace("'", "'\\''")
        val cmd = "ls -lap '$escapedPath'"
        val useShizuku = !isRootEngine && SpecialPermissionVerifier.isShizukuAuthorized(getApplication())
        val (stdout, _, exitCode) = try {
            when {
                isRootEngine -> SpecialPermissionVerifier.executeRootCommandFull(cmd)
                useShizuku -> SpecialPermissionVerifier.executeShizukuCommand(cmd)
                else -> return null
            }
        } catch (_: Exception) { return null }
        if (exitCode != 0 || stdout.isBlank()) return null

        val entries = mutableListOf<FileEntry>()
        for (raw in stdout.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("total ")) continue
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 8) continue
            val perms = parts[0]
            if (perms.length < 10) continue
            val nameWithSlash = parseLsFilename(line) ?: continue
            val isDir = nameWithSlash.endsWith("/")
            val name = if (isDir) nameWithSlash.dropLast(1) else nameWithSlash
            if (name == "." || name == "..") continue
            val size = parts[4].toLongOrNull() ?: 0L
            val childPath = "$dirPath/$name"
            val modified = try {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse("${parts[5]} ${parts[6]}")?.time ?: 0L
            } catch (_: Exception) { 0L }
            entries.add(FileEntry(childPath, name, isDir, perms, if (isDir) 0L else size, modified))
        }
        return entries
    }

    private fun listWithFile(path: String, showHidden: Boolean, effectiveRoot: String): List<FileEntry> {
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
            val perm = try { formatPermission(Os.stat(child.absolutePath).st_mode) } catch (_: Exception) { "" }
            val sz = if (isDir) 0L else try { child.length() } catch (_: Exception) { 0L }
            val modified = try { child.lastModified() } catch (_: Exception) { 0L }
            entries.add(FileEntry(child.absolutePath, name, isDir, perm, sz, modified))
        }
        DiagnosticLog.log("FileEngine", "统计: dirs=$dirCount, files=$fileCount, hidden 过滤=$skipHidden")

        val sorted = entries.sortedWith(
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
        entries.clear()
        entries.addAll(sorted)
        return entries
    }

    private fun listWithLs(path: String, showHidden: Boolean, useRoot: Boolean, effectiveRoot: String): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        val normalizedPath = if (path == "/") "/" else path.trimEnd('/').ifEmpty { "/" }
        val escapedPath = normalizedPath.replace("'", "'\\''")

        // 判断是否使用 Shizuku（ADB 权限 + Shizuku 在线）
        val useShizuku = !useRoot && SpecialPermissionVerifier.isShizukuAuthorized(getApplication())

        // Shizuku 使用长格式 ls -lap 以获取文件大小和时间戳
        val lsFlags = if (useShizuku) {
            buildString {
                append("-l")
                if (showHidden) append("a")
                append("p")
            }
        } else {
            if (showHidden) "-1Ap" else "-1p"
        }
        val command = "ls $lsFlags '$escapedPath'"
        val tag = when {
            useRoot -> "LsRoot"
            useShizuku -> "LsShizuku"
            else -> "LsShell"
        }
        DiagnosticLog.log(tag, "命令: $command")

        val (stdout, stderr, exitCode) = try {
            when {
                useRoot -> SpecialPermissionVerifier.executeRootCommandFull(command)
                useShizuku -> SpecialPermissionVerifier.executeShizukuCommand(command)
                else -> SpecialPermissionVerifier.executeShellCommandFull(command)
            }
        } catch (e: Throwable) {
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

        if (useShizuku || useRoot) {
            // 长格式解析 ls -lap: drwxrwx--x  4 root sdcard_rw  4096 2024-01-01 00:00 dirname/
            for (raw in lines) {
                if (raw.startsWith("total ")) continue
                val parts = raw.split("\\s+".toRegex())
                if (parts.size < 8) continue
                val perms = parts[0]
                if (perms.length < 10) continue
                val nameWithSlash = parseLsFilename(raw) ?: continue
                val isDir = nameWithSlash.endsWith("/")
                val name = if (isDir) nameWithSlash.dropLast(1) else nameWithSlash
                if (name == "." || name == "..") continue
                if (!showHidden && name.startsWith(".")) continue
                if (isDir) dirCount++ else fileCount++
                val sz = parts[4].toLongOrNull() ?: 0L
                val childPath = if (normalizedPath == "/") "/$name" else "$normalizedPath/$name"
                val modified = try {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse("${parts[5]} ${parts[6]}")?.time ?: 0L
                } catch (_: Exception) { 0L }
                entries.add(FileEntry(childPath, name, isDir, perms, if (isDir) 0L else sz, modified))
            }
        } else {
            // 短格式解析 ls -1p: dirname/ 或 filename
            for (raw in lines) {
                val isDir = raw.endsWith("/")
                val name = if (isDir) raw.dropLast(1) else raw
                if (name == "." || name == "..") continue
                if (!showHidden && name.startsWith(".")) continue
                if (isDir) dirCount++ else fileCount++
                val childPath = if (normalizedPath == "/") "/$name" else "$normalizedPath/$name"
                val perm = try { formatPermission(Os.stat(childPath).st_mode) } catch (_: Exception) { "" }
                val sz = if (isDir) 0L else try { File(childPath).length() } catch (_: Exception) { 0L }
                val modified = try { File(childPath).lastModified() } catch (_: Exception) { 0L }
                entries.add(FileEntry(childPath, name, isDir, perm, sz, modified))
            }
        }
        DiagnosticLog.log(tag, "解析结果: dirs=$dirCount, files=$fileCount, 总 ${entries.size}")

        val sorted = entries.sortedWith(
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
        entries.clear()
        entries.addAll(sorted)
        return entries
    }

    fun getPropertyData(entry: FileEntry): FilePropertyData {
        // 压缩包模式：使用内存元数据，不访问文件系统
        if (isInArchive) {
            val memEntry = archiveMemFs?.entries?.get(entry.path)
            val sizeBytes = when (memEntry) {
                is CompressService.ArchiveMemFile -> memEntry.size
                is CompressService.ArchiveMemDir -> memEntry.size
                else -> entry.size
            }
            val sizeDisplay = if (sizeBytes > 0) "${formatSize(sizeBytes)} ($sizeBytes)" else "0 B (0)"
            val type = if (entry.isDirectory) "文件夹" else {
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                if (ext.isNotEmpty()) "${ext.uppercase()} 文件" else "文件"
            }
            var fileCount = 0
            var folderCount = 0
            if (entry.isDirectory && memEntry is CompressService.ArchiveMemDir) {
                val prefix = entry.path + "/"
                for ((path, child) in archiveMemFs!!.entries) {
                    if (!path.startsWith(prefix)) continue
                    val remainder = path.removePrefix(prefix)
                    if (remainder.contains('/')) continue
                    if (child is CompressService.ArchiveMemDir) folderCount++ else fileCount++
                }
            }
            return FilePropertyData(
                name = entry.name,
                directory = archiveFileName,
                type = type,
                sizeBytes = sizeBytes,
                sizeDisplay = sizeDisplay,
                modifiedTime = "",
                permission = "",
                owner = "",
                group = "",
                fileCount = fileCount,
                folderCount = folderCount,
                isDirectory = entry.isDirectory
            )
        }

        val file = File(entry.path)
        val stat = try { Os.stat(entry.path) } catch (_: Exception) { null }

        // Os.stat 失败时回退到 shell ls -lapd（如路径含括号等特殊字符）
        var shellPermission = ""
        var shellOwner = ""
        var shellGroup = ""
        if (stat == null && hasShellEngine) {
            val escaped = entry.path.replace("'", "'\\''")
            val (lsOut, _, lsExit) = try {
                executeShell("ls -lapd '$escaped'")
            } catch (_: Exception) { Triple("", "", -1) }
            if (lsExit == 0 && lsOut.isNotBlank()) {
                val line = lsOut.lines().firstOrNull { it.isNotBlank() && !it.startsWith("total ") }
                if (line != null) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 7) {
                        val permStr = parts[0]  // e.g. "drwxrwxrwx"
                        shellOwner = parts[2]
                        shellGroup = parts[3]
                        val modeFromShell = parseRwxToMode(permStr)
                        if (modeFromShell != 0) {
                            shellPermission = "${permStr}(${String.format("%03o", modeFromShell and 0x1FF)})"
                        }
                    }
                }
            }
        }

        val mode = stat?.st_mode ?: 0
        val permission = if (stat != null) {
            "${formatPermission(mode)}(${String.format("%03o", mode and 0x1FF)})"
        } else shellPermission

        val owner = stat?.st_uid?.let { resolveUserName(it) } ?: shellOwner
        val group = stat?.st_gid?.let { resolveGroupName(it) } ?: shellGroup

        val modifiedTime = if (entry.lastModified > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.lastModified))
        } else ""

        val sizeDisplay = if (entry.isDirectory) {
            val cached = folderSizeDb.get(entry.path)
            val bytes = cached?.size ?: 0L
            if (bytes > 0) "${formatSize(bytes)} ($bytes)" else "0 B (0)"
        } else {
            "${formatSize(entry.size)} (${entry.size})"
        }

        val parentPath = file.parent ?: ""

        // 类型描述
        val type = if (entry.isDirectory) {
            "文件夹"
        } else {
            val ext = entry.name.substringAfterLast('.', "").lowercase()
            when (ext) {
                "png" -> "PNG 图片"
                "jpg", "jpeg" -> "JPEG 图片"
                "gif" -> "GIF 图片"
                "webp" -> "WebP 图片"
                "bmp" -> "BMP 图片"
                "mp4" -> "MP4 视频"
                "mkv" -> "MKV 视频"
                "avi" -> "AVI 视频"
                "mp3" -> "MP3 音频"
                "flac" -> "FLAC 音频"
                "wav" -> "WAV 音频"
                "zip" -> "ZIP 压缩包"
                "rar" -> "RAR 压缩包"
                "7z" -> "7Z 压缩包"
                "tar" -> "TAR 归档"
                "gz" -> "GZ 压缩"
                "apk" -> "APK 安装包"
                "txt" -> "文本文件"
                "pdf" -> "PDF 文档"
                "doc", "docx" -> "Word 文档"
                "xls", "xlsx" -> "Excel 表格"
                "json" -> "JSON 文件"
                "xml" -> "XML 文件"
                "html", "htm" -> "HTML 文件"
                "js" -> "JavaScript 文件"
                "kt" -> "Kotlin 文件"
                "java" -> "Java 文件"
                "py" -> "Python 文件"
                "sh" -> "Shell 脚本"
                else -> if (ext.isNotEmpty()) "${ext.uppercase()} 文件" else "文件"
            }
        }

        var fileCount = 0
        var folderCount = 0
        if (entry.isDirectory) {
            val children = try { file.listFiles() } catch (_: Exception) { null }
            if (children != null) {
                for (child in children) {
                    if (child.isDirectory) folderCount++ else fileCount++
                }
            } else if (hasShellEngine) {
                // Java API 失败，通过 shell 统计子项数量
                val shellEntries = listDirEntriesViaShell(entry.path, showHiddenFiles)
                for (se in shellEntries) {
                    if (se.isDirectory) folderCount++ else fileCount++
                }
            }
        }

        return FilePropertyData(
            name = entry.name,
            directory = parentPath,
            type = type,
            sizeBytes = entry.size,
            sizeDisplay = sizeDisplay,
            modifiedTime = modifiedTime,
            permission = permission,
            owner = owner,
            group = group,
            fileCount = fileCount,
            folderCount = folderCount,
            isDirectory = entry.isDirectory
        )
    }

    // ── 权限编辑 ──

    data class SystemUser(val uid: Int, val username: String)
    data class SystemGroup(val gid: Int, val groupname: String)

    fun getSystemUsers(): List<SystemUser> {
        if (!isRootEngine) return emptyList()
        val (stdout, _, exitCode) = try {
            SpecialPermissionVerifier.executeRootCommandFull("cat /etc/passwd")
        } catch (_: Exception) { return emptyList() }
        if (exitCode != 0 || stdout.isBlank()) return emptyList()
        return stdout.lines().mapNotNull { line ->
            val parts = line.split(":")
            if (parts.size >= 3) {
                val uid = parts[2].toIntOrNull()
                if (uid != null) SystemUser(uid, parts[0]) else null
            } else null
        }.sortedBy { it.uid }
    }

    fun getSystemGroups(): List<SystemGroup> {
        if (!isRootEngine) return emptyList()
        val (stdout, _, exitCode) = try {
            SpecialPermissionVerifier.executeRootCommandFull("cat /etc/group")
        } catch (_: Exception) { return emptyList() }
        if (exitCode != 0 || stdout.isBlank()) return emptyList()
        return stdout.lines().mapNotNull { line ->
            val parts = line.split(":")
            if (parts.size >= 3) {
                val gid = parts[2].toIntOrNull()
                if (gid != null) SystemGroup(gid, parts[0]) else null
            } else null
        }.sortedBy { it.gid }
    }

    /** 读取 /etc/passwd 解析 UID→用户名（无需 root） */
    private fun resolveUserName(uid: Int): String {
        val name = try {
            File("/etc/passwd").readLines().firstNotNullOfOrNull { line ->
                val parts = line.split(":")
                if (parts.size >= 3 && parts[2].toIntOrNull() == uid) parts[0] else null
            }
        } catch (_: Exception) { null }
        return if (name != null) "$name ($uid)" else uid.toString()
    }

    /** 读取 /etc/group 解析 GID→组名（无需 root） */
    private fun resolveGroupName(gid: Int): String {
        val name = try {
            File("/etc/group").readLines().firstNotNullOfOrNull { line ->
                val parts = line.split(":")
                if (parts.size >= 3 && parts[2].toIntOrNull() == gid) parts[0] else null
            }
        } catch (_: Exception) { null }
        return if (name != null) "$name ($gid)" else gid.toString()
    }

    /**
     * 应用权限修改。成功返回 null，失败返回错误信息。
     * 如果中途失败，会尝试回滚到原始权限。
     */
    fun applyPermissions(path: String, mode: Int, uid: Int, gid: Int, originalMode: Int, originalUid: Int, originalGid: Int): String? {
        val escapedPath = path.replace("'", "'\\''")

        // chmod
        val octal = String.format("%o", mode and 0x1FF)
        val (_, chmodErr, chmodExit) = try {
            SpecialPermissionVerifier.executeRootCommandFull("chmod $octal '$escapedPath'")
        } catch (e: Exception) { return "chmod 执行异常: ${e.message}" }
        if (chmodExit != 0) return "chmod 失败 (exit $chmodExit): $chmodErr"

        // chown
        val (_, chownErr, chownExit) = try {
            SpecialPermissionVerifier.executeRootCommandFull("chown $uid:$gid '$escapedPath'")
        } catch (e: Exception) {
            // 回滚 chmod
            val rollbackOctal = String.format("%o", originalMode and 0x1FF)
            try { SpecialPermissionVerifier.executeRootCommandFull("chmod $rollbackOctal '$escapedPath'") } catch (_: Exception) {}
            return "chown 执行异常: ${e.message}"
        }
        if (chownExit != 0) {
            // 回滚 chmod
            val rollbackOctal = String.format("%o", originalMode and 0x1FF)
            try { SpecialPermissionVerifier.executeRootCommandFull("chmod $rollbackOctal '$escapedPath'") } catch (_: Exception) {}
            return "chown 失败 (exit $chownExit): $chownErr"
        }

        return null
    }

    // ── 压缩功能 ──

    /** 压缩任务取消标志 */
    val compressCancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 执行压缩。
     * @param entry 要压缩的文件/文件夹
     * @param fileName 输出文件名（含后缀）
     * @param format 压缩格式
     * @param level 压缩级别
     * @param password 密码（空=不加密）
     * @param useAes zip 加密方式（true=AES, false=ZipCrypto）
     * @param outputToOtherPanel 是否输出到非聚焦面板目录
     * @param jxlPackZip JXL 格式是否打包成 ZIP
     * @param onProgress 进度回调
     * @param onComplete 完成回调 (success, outputPath?, error?)
     */
    fun compress(
        entry: FileEntry,
        fileName: String,
        format: String,
        level: Int,
        password: String,
        useAes: Boolean,
        outputToOtherPanel: Boolean,
        jxlPackZip: Boolean = false,
        onProgress: (Int, Int, Float) -> Unit,
        onComplete: (Boolean, String?, String?) -> Unit
    ) {
        compressCancelFlag.set(false)

        val outputDir = if (outputToOtherPanel) {
            if (focusedPanel == FocusedPanel.LEFT) rightPath else leftPath
        } else {
            if (focusedPanel == FocusedPanel.LEFT) leftPath else rightPath
        }

        val outputPath = java.io.File(outputDir, fileName).absolutePath

        val options = CompressService.CompressOptions(
            sourcePath = entry.path,
            outputPath = outputPath,
            format = format,
            compressionLevel = level,
            password = password,
            useAes = useAes,
            jxlPackZip = jxlPackZip
        )

        CoroutineScope(Dispatchers.IO).launch {
            CompressService.compress(options, compressCancelFlag, object : CompressService.ProgressCallback {
                override fun onProgress(info: CompressService.ProgressInfo) {
                    onProgress(info.currentFile, info.totalFiles, info.progress)
                }

                override fun onComplete(success: Boolean, outPath: String?, error: String?) {
                    CoroutineScope(Dispatchers.Main).launch {
                        onComplete(success, outPath, error)
                        refreshCurrent()
                    }
                }
            })
        }
    }

    companion object {
        /** 按需解压阈值：小于此大小的文件解压到内存，大于等于此大小解压到磁盘 */
        private const val EXTRACT_TO_MEMORY_THRESHOLD = 50L * 1024 * 1024 // 50MB

        fun formatPermission(mode: Int): String {
            val type = when (mode and 0xF000) {
                0x4000 -> 'd'
                0x8000 -> '-'
                0xA000 -> 'l'
                0x6000 -> 'b'
                0x2000 -> 'c'
                0x1000 -> 'p'
                0xC000 -> 's'
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

        /** 将 rwx 权限字符串（如 "drwxrwxrwx"）解析为 mode 整数 */
        fun parseRwxToMode(perm: String): Int {
            if (perm.length < 10) return 0
            var mode = 0
            // 文件类型
            mode = when (perm[0]) {
                'd' -> 0x4000
                '-' -> 0x8000
                'l' -> 0xA000
                'b' -> 0x6000
                'c' -> 0x2000
                'p' -> 0x1000
                's' -> 0xC000
                else -> 0
            }
            // 9 位 rwx 权限
            for (i in 1..9) {
                if (perm[i] != '-') {
                    mode = mode or (1 shl (9 - i))
                }
            }
            return mode
        }

        fun formatSize(bytes: Long): String {
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
    }
}
