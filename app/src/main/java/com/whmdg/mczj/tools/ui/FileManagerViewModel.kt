package com.whmdg.mczj.tools.ui

import android.app.Application
import android.content.Context
import android.util.Log
import com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient
import com.whmdg.mczj.tools.fileop.webdav.WebDavServerConfig
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
import com.whmdg.mczj.tools.util.ArchiveBrowser
import com.whmdg.mczj.tools.util.CompressService
import com.whmdg.mczj.tools.util.SevenZipCommand
import com.whmdg.mczj.tools.util.DiagnosticLog
import com.whmdg.mczj.tools.util.FileAccessLevel
import com.whmdg.mczj.tools.util.FileAccessor
import com.whmdg.mczj.tools.util.SizeCalcResult
import com.whmdg.mczj.tools.util.calculateFolderSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
    /** 左面板当前目录的扩展属性缓存：文件名 → 仅 i/a 标志 */
    var leftExtFlagsMap by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /** 右面板当前目录的扩展属性缓存：文件名 → 仅 i/a 标志 */
    var rightExtFlagsMap by mutableStateOf<Map<String, String>>(emptyMap())
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

    // ── 压缩任务 ──
    private val compressCancelFlag = AtomicBoolean(false)
    private var compressJob: Job? = null

    // ── 解压任务 ──
    private val extractCancelFlag = AtomicBoolean(false)
    private var extractJob: Job? = null

    // ── 压缩包浏览 ──
    var isInArchiveMode by mutableStateOf(false)
        private set
    var archiveSession by mutableStateOf<com.whmdg.mczj.tools.util.ArchiveBrowser.ArchiveSession?>(null)
        private set
    /** 密码弹窗状态：null=不显示，FileEntry=需要密码的压缩包 */
    var archivePasswordRequest by mutableStateOf<FileEntry?>(null)
    /** Debug 模式压缩包解析信息 */
    var archiveDebugInfo by mutableStateOf<com.whmdg.mczj.tools.util.ArchiveBrowser.ArchiveDebugInfo?>(null)
    /** 档案打开错误弹窗：null=不显示，Pair(文件名, 错误信息)=显示 */
    var archiveOpenError by mutableStateOf<Pair<String, String>?>(null)

    // ── 回收站 ──
    var isInRecycleBin by mutableStateOf(false)
        private set
    var recycleBinPath by mutableStateOf("")
        private set
    var jxlPackZip by mutableStateOf(false)
        private set
    var pendingExternalEntry by mutableStateOf<FileEntry?>(null)
    var pendingApkEntry by mutableStateOf<FileEntry?>(null)

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

    // ── WebDAV 浏览状态 ──
    /** 当前 WebDAV 客户端，null 表示本地模式 */
    var webDavClient by mutableStateOf<WebDavFileClient?>(null)
        private set
    /** WebDAV 服务器配置（用于显示名称等） */
    var webDavConfig by mutableStateOf<WebDavServerConfig?>(null)
        private set
    /** WebDAV 当前浏览路径 */
    var webDavCurrentPath by mutableStateOf("/")
        private set
    /** 是否处于 WebDAV 模式 */
    val isWebDavMode: Boolean get() = webDavClient != null

    init {
        // 权限级别（统一从 legacySp 读取，与 HomeScreen / 安全设置一致）
        permissionLevel = legacySp.getString("target_permission_level", "NORMAL") ?: "NORMAL"
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

        // 初始加载（优先尝试恢复压缩包会话）
        val cachedArchive = ArchiveBrowser.loadSessionCache(context)
        if (cachedArchive != null) {
            val (cache, sourcePanel) = cachedArchive
            try {
                val session = ArchiveBrowser.restoreSession(cache)
                isInArchiveMode = true
                archiveSession = session
                if (sourcePanel == "LEFT") {
                    leftPath = session.currentPath
                    leftEntries = session.currentEntries
                    rightPath = rHome
                    rightEntries = listDirectory(rHome)
                } else {
                    rightPath = session.currentPath
                    rightEntries = session.currentEntries
                    leftPath = lHome
                    leftEntries = listDirectory(lHome)
                }
                ArchiveBrowser.clearSessionCache(context)
            } catch (e: Exception) {
                DiagnosticLog.log("FileMgr", "恢复压缩包会话失败: ${e.message}")
                ArchiveBrowser.clearSessionCache(context)
                leftEntries = listDirectory(lHome)
                rightEntries = listDirectory(rHome)
            }
        } else {
            leftEntries = listDirectory(lHome)
            rightEntries = listDirectory(rHome)
        }
        loadExtFlagsForDir(leftPath, isLeft = true)
        loadExtFlagsForDir(rightPath, isLeft = false)
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
        val escaped = SevenZipCommand.escape(path)
        val (_, _, exit) = try {
            when {
                isRootEngine -> SpecialPermissionVerifier.executeRootCommandFull("test -e $escaped")
                SpecialPermissionVerifier.isShizukuAuthorized(getApplication()) -> SpecialPermissionVerifier.executeShizukuCommand("test -e $escaped")
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
        val escaped = SevenZipCommand.escape(path)
        val (_, _, exit) = try {
            when {
                isRootEngine -> SpecialPermissionVerifier.executeRootCommandFull("test -d $escaped")
                SpecialPermissionVerifier.isShizukuAuthorized(getApplication()) -> SpecialPermissionVerifier.executeShizukuCommand("test -d $escaped")
                else -> return File(path).isDirectory
            }
        } catch (_: Exception) { return false }
        return exit == 0
    }

    /**
     * 检查文件是否可通过 Java API 读取。
     * Android/data 和 Android/obb 是受限目录，除自己包名外均不可读。
     */
    private fun shellCanRead(path: String): Boolean {
        if (!hasShellEngine) return File(path).canRead()
        if (isRestrictedAndroidDir(path)) return false
        return File(path).canRead()
    }

    /** 判断路径是否在受限的 Android/data 或 Android/obb 下（排除自身包名） */
    private fun isRestrictedAndroidDir(path: String): Boolean {
        val p = path.replace("//", "/")
        for (prefix in RESTRICTED_ANDROID_PREFIXES) {
            if (p.startsWith(prefix)) {
                val rest = p.removePrefix(prefix)
                if (rest.startsWith(OWN_PACKAGE_NAME)) return false
                return true
            }
        }
        return false
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
    private fun listDirEntriesViaShell(path: String, showHidden: Boolean, longFormat: Boolean = false, displayPath: String = path): List<FileEntry> {
        val normalizedPath = if (path == "/") "/" else path.trimEnd('/').ifEmpty { "/" }
        val normalizedDisplayPath = if (displayPath == "/") "/" else displayPath.trimEnd('/').ifEmpty { "/" }
        val escapedPath = SevenZipCommand.escape(normalizedPath)
        val flags = buildString {
            append("-l")
            if (showHidden) append("a")
            append("p")
        }
        val command = "ls $flags $escapedPath"

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

        // 收集软链接，批量检测目标类型
        val symlinks = mutableMapOf<String, String>()
        for (raw in stdout.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("total ")) continue
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 8 || parts[0].length < 10) continue
            if (parts[0][0] == 'l') {
                val rn = parseLsFilename(line) ?: continue
                val nm = if (rn.contains(" -> ")) rn.substringBefore(" -> ") else rn.trimEnd('/')
                if (nm == "." || nm == "..") continue
                val cp = if (normalizedDisplayPath == "/") "/$nm" else "$normalizedDisplayPath/$nm"
                symlinks[cp] = rn.substringAfter(" -> ", "")
            }
        }
        checkSymlinkTargets(symlinks)

        for (raw in stdout.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            if (line.startsWith("total ")) continue

            val parts = line.split("\\s+".toRegex())
            if (parts.size < 8) continue
            val perms = parts[0]
            if (perms.length < 10) continue

            val rawName = parseLsFilename(line) ?: continue
            // 提取文件名：软链接去掉 " -> target" 部分，目录去掉尾部 /
            val name = if (rawName.contains(" -> ")) {
                rawName.substringBefore(" -> ")
            } else {
                rawName.trimEnd('/')
            }
            if (name == "." || name == "..") continue
            if (!showHidden && name.startsWith(".")) continue

            val childPath = if (normalizedDisplayPath == "/") "/$name" else "$normalizedDisplayPath/$name"
            val isDir = when {
                perms[0] == 'd' -> true
                perms[0] == 'l' -> symlinkTypeCache[childPath] ?: false
                else -> rawName.endsWith("/")
            }
            val size = parts[4].toLongOrNull() ?: 0L
            val modified = try {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse("${parts[5]} ${parts[6]}")?.time ?: 0L
            } catch (_: Exception) { 0L }
            entries.add(FileEntry(childPath, name, isDir, perms, if (isDir) 0L else size, modified))
        }
        return entries
    }

    // ── 便捷属性 ──
    val currentPath: String get() = if (isWebDavMode && focusedPanel == FocusedPanel.LEFT) {
        webDavConfig?.let { config ->
            val proto = if (config.protocol == "dav") "dav" else "davs"
            "$proto://${config.host}:${config.port}$webDavCurrentPath"
        } ?: webDavCurrentPath
    } else if (focusedPanel == FocusedPanel.LEFT) leftPath else rightPath
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

    /** 清空指定路径的滚动位置（前进导航时使用，确保目标从第一行开始） */
    fun clearScrollPosition(path: String) { scrollPositions.remove(path) }

    // ── 待滚动状态（绑定跳转+渲染） ──
    var pendingScrollTo by mutableStateOf<Triple<String, Int, Int>?>(null)

    /**
     * 统一的导航函数：跳转路径 + 设置滚动位置
     * @param path 目标路径
     * @param scrollToIndex 滚动到第几个卡片（默认 0，即第一行）
     * @param scrollToOffset 滚动偏移量（默认 0）
     */
    fun navigateToWithScroll(path: String, scrollToIndex: Int = 0, scrollToOffset: Int = 0, realPath: String = path) {
        // 1. 执行路径跳转
        navigateTo(path, realPath)
        // 2. 记录滚动位置（供 Compose 侧使用）
        pendingScrollTo = Triple(path, scrollToIndex, scrollToOffset)
    }

    // ── 核心导航：切换路径 + 刷新列表 ──
    // path = 显示路径（软链接路径），realPath = 真实路径（用于读取文件列表）
    fun navigateTo(path: String, realPath: String = path) {
        if (isInRecycleBin) isInRecycleBin = false
        // path = 显示路径（软链接路径），realPath = 真实路径（用于读取文件列表）
        // 后退/前进/返回上一级传入的 realPath 默认等于 path，需要解析真实路径
        val resolvedRealPath = if (realPath == path && hasShellEngine) {
            resolveIfSymlink(path)
        } else {
            realPath
        }
        if (focusedPanel == FocusedPanel.LEFT) {
            if (leftPath == path) return
            leftNavState = leftNavState.navigate(path)
            leftPath = path
            leftEntries = listDirectory(path, resolvedRealPath)
            loadExtFlagsForDir(path, isLeft = true)
        } else {
            if (rightPath == path) return
            rightNavState = rightNavState.navigate(path)
            rightPath = path
            rightEntries = listDirectory(path, resolvedRealPath)
            loadExtFlagsForDir(path, isLeft = false)
        }
    }

    /** 软链接路径 → 真实路径缓存（避免后退/前进时重复执行 readlink） */
    private val symlinkResolveCache = mutableMapOf<String, String>()

    /** 如果路径是软链接，解析真实目标路径；否则原样返回 */
    private fun resolveIfSymlink(path: String): String {
        if (!hasShellEngine) return path
        symlinkResolveCache[path]?.let { return it }
        val escaped = SevenZipCommand.escape(path)
        val (out, _, exit) = try {
            executeShell("readlink -f $escaped")
        } catch (_: Exception) { Triple("", "", -1) }
        val resolved = if (exit == 0 && out.isNotBlank() && out.trim() != path) out.trim() else path
        symlinkResolveCache[path] = resolved
        return resolved
    }

    /** 软链接目标类型缓存：path → 目标是否为目录（避免每次列表重复检测） */
    private val symlinkTypeCache = mutableMapOf<String, Boolean>()

    /**
     * 批量检测软链接目标是否为目录（test -d 自动跟随软链接）。
     * @param symlinks 软链接路径 → 目标路径 的映射
     */
    private fun checkSymlinkTargets(symlinks: Map<String, String>) {
        if (symlinks.isEmpty() || !hasShellEngine) return
        val toCheck = symlinks.filter { it.key !in symlinkTypeCache }
        if (toCheck.isEmpty()) return
        val cmd = toCheck.entries.joinToString("; ") { (path, _) ->
            val escaped = SevenZipCommand.escape(path)
            "test -d $escaped && echo '1' || echo '0'"
        }
        val (out, _, exit) = try { executeShell(cmd) } catch (_: Exception) { return }
        if (exit != 0 && out.isBlank()) return
        val results = out.lines().filter { it.isNotBlank() }
        toCheck.keys.forEachIndexed { i, path ->
            if (i < results.size) symlinkTypeCache[path] = results[i].trim() == "1"
        }
    }

    // ── 导航操作 ──
    fun navigateToFolder(entry: FileEntry, scrollToIndex: Int = 0, scrollToOffset: Int = 0) {
        // 显示路径始终使用软链接本身的路径，真实路径由 navigateTo 内部 resolveIfSymlink 解析
        val displayPath = entry.path

        if (hasShellEngine) {
            // 用软链接路径预检（shell 自动跟随软链接）
            listDirEntriesViaShell(displayPath, showHiddenFiles)
            if (lastShellStderr.isBlank()) {
                navigateToWithScroll(displayPath, scrollToIndex, scrollToOffset)
                addHistory(entry.name, displayPath, true)
            } else {
                loadError = RuntimeException("${formatShellError(entry.name, lastShellStderr)}\n路径: $displayPath")
            }
        } else {
            val testDir = File(displayPath)
            val accessible = try { testDir.listFiles() } catch (_: Exception) { null }
            if (accessible != null) {
                navigateToWithScroll(displayPath, scrollToIndex, scrollToOffset)
                addHistory(entry.name, displayPath, true)
            } else if (!testDir.exists()) {
                loadError = RuntimeException("文件夹不存在: ${entry.name}\n路径: $displayPath")
            } else {
                loadError = RuntimeException("权限不足: ${entry.name}\n路径: $displayPath")
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

    // ── WebDAV 浏览操作 ──

    /**
     * 进入 WebDAV 浏览模式。
     */
    fun navigateToWebDav(config: WebDavServerConfig) {
        try {
            val client = WebDavFileClient(config)
            webDavClient = client
            webDavConfig = config
            webDavCurrentPath = config.relativePath.ifEmpty { "/" }
            loadWebDavEntries()
        } catch (e: Exception) {
            loadError = RuntimeException("连接 WebDAV 失败: ${e.message}")
        }
    }

    /**
     * 在 WebDAV 模式下进入子目录。
     */
    fun navigateToWebDavFolder(name: String) {
        val client = webDavClient ?: return
        val newPath = if (webDavCurrentPath.endsWith("/")) {
            "$webDavCurrentPath$name"
        } else {
            "$webDavCurrentPath/$name"
        }
        webDavCurrentPath = newPath
        loadWebDavEntries()
    }

    /**
     * 在 WebDAV 模式下返回上一级。
     */
    fun webDavGoBack(): Boolean {
        if (webDavCurrentPath == "/" || webDavCurrentPath.isEmpty()) return false
        val parent = webDavCurrentPath.substringBeforeLast("/", "").ifEmpty { "/" }
        webDavCurrentPath = parent
        loadWebDavEntries()
        return true
    }

    /**
     * 退出 WebDAV 模式，恢复本地文件列表。
     */
    fun exitWebDavMode() {
        webDavClient = null
        webDavConfig = null
        webDavCurrentPath = "/"
        refreshCurrent()
    }

    /**
     * 加载当前 WebDAV 路径的文件列表到 leftEntries。
     */
    private fun loadWebDavEntries() {
        val client = webDavClient ?: return
        try {
            val files = client.listChildren(webDavCurrentPath)
            if (files != null) {
                leftEntries = files.map { info ->
                    FileEntry(
                        path = info.remotePath,
                        name = info.name,
                        isDirectory = info.isDirectory,
                        permission = "",
                        size = info.size,
                        lastModified = info.lastModified,
                        createdAt = 0
                    )
                }.let { entries ->
                    when (sortField) {
                        SortField.NAME -> when (sortOrder) {
                            SortOrder.ASC -> entries.sortedBy { it.name.lowercase() }
                            SortOrder.DESC -> entries.sortedByDescending { it.name.lowercase() }
                        }
                        SortField.SIZE -> when (sortOrder) {
                            SortOrder.ASC -> entries.sortedBy { it.size }
                            SortOrder.DESC -> entries.sortedByDescending { it.size }
                        }
                        SortField.MODIFIED -> when (sortOrder) {
                            SortOrder.ASC -> entries.sortedBy { it.lastModified }
                            SortOrder.DESC -> entries.sortedByDescending { it.lastModified }
                        }
                        SortField.CREATED -> entries
                    }
                }
                loadError = null
            }
        } catch (e: Exception) {
            loadError = RuntimeException("WebDAV 加载失败: ${e.message}")
        }
    }

    /** 后退，返回目标路径，null 表示无法后退（不执行跳转，由调用方通过 navigateToWithScroll 跳转） */
    /** 后退一步：更新 nav state index + 切换路径 + 刷新列表，返回目标路径 */
    fun goBack(): String? {
        if (focusedPanel == FocusedPanel.LEFT) {
            val back = leftNavState.back() ?: return null
            leftNavState = back
            leftPath = back.current
            leftEntries = listDirectory(leftPath)
            loadExtFlagsForDir(leftPath, isLeft = true)
            return leftPath
        } else {
            val back = rightNavState.back() ?: return null
            rightNavState = back
            rightPath = back.current
            rightEntries = listDirectory(rightPath)
            loadExtFlagsForDir(rightPath, isLeft = false)
            return rightPath
        }
    }

    /** 前进一步：更新 nav state index + 切换路径 + 刷新列表，返回目标路径 */
    fun goForward(): String? {
        if (focusedPanel == FocusedPanel.LEFT) {
            val fwd = leftNavState.forward() ?: return null
            leftNavState = fwd
            leftPath = fwd.current
            leftEntries = listDirectory(leftPath)
            loadExtFlagsForDir(leftPath, isLeft = true)
            return leftPath
        } else {
            val fwd = rightNavState.forward() ?: return null
            rightNavState = fwd
            rightPath = fwd.current
            rightEntries = listDirectory(rightPath)
            loadExtFlagsForDir(rightPath, isLeft = false)
            return rightPath
        }
    }

    /** 返回上级目录，返回目标路径，null 表示已在根目录（不执行跳转，由调用方通过 navigateToWithScroll 跳转） */
    fun goUp(): String? {
        val effectiveRoot = if (isRootEngine) "/" else safeDefault
        val path = if (focusedPanel == FocusedPanel.LEFT) leftPath else rightPath
        if (path == effectiveRoot || !path.contains('/')) return null
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        if (parent == path) return null
        return parent
    }

    fun syncPaths() {
        if (focusedPanel == FocusedPanel.LEFT) {
            rightPath = leftPath
            rightNavState = rightNavState.navigate(leftPath)
            rightEntries = listDirectory(rightPath)
            loadExtFlagsForDir(rightPath, isLeft = false)
        } else {
            leftPath = rightPath
            leftNavState = leftNavState.navigate(rightPath)
            leftEntries = listDirectory(leftPath)
            loadExtFlagsForDir(leftPath, isLeft = true)
        }
    }

    fun refreshCurrent() {
        if (focusedPanel == FocusedPanel.LEFT && isWebDavMode) {
            loadWebDavEntries()
        } else if (focusedPanel == FocusedPanel.LEFT) {
            leftEntries = listDirectory(leftPath)
            loadExtFlagsForDir(leftPath, isLeft = true)
        } else {
            rightEntries = listDirectory(rightPath)
            loadExtFlagsForDir(rightPath, isLeft = false)
        }
    }

    fun refreshBoth() {
        if (isWebDavMode) {
            loadWebDavEntries()
        } else {
            leftEntries = listDirectory(leftPath)
            loadExtFlagsForDir(leftPath, isLeft = true)
        }
        rightEntries = listDirectory(rightPath)
        loadExtFlagsForDir(rightPath, isLeft = false)
    }

    // ── 文件夹大小统计 ──

    /** 选用当前可用的最高权限通道（ROOT > SHIZUKU > NORMAL）。 */
    private fun detectMaxAvailablePermission(): FileAccessLevel = when {
        isRootEngine -> FileAccessLevel.ROOT
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
                        SizeCalcManager.finish(result.rootSize, result.tree)
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
            val escapedSrc = SevenZipCommand.escape(entry.path)
            val escapedDst = SevenZipCommand.escape(target.absolutePath)
            val cpFlag = if (entry.isDirectory) "-rf" else "-f"
            val (_, cpErr, cpExit) = try {
                executeShell("cp $cpFlag $escapedSrc $escapedDst")
            } catch (e: Exception) { return e.message ?: "复制失败" }
            if (cpExit != 0) return "复制失败: $cpErr"
            val rmFlag = if (entry.isDirectory) "-rf" else "-f"
            executeShell("rm $rmFlag $escapedSrc")
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
            val escapedSrc = SevenZipCommand.escape(entry.path)
            val escapedDst = SevenZipCommand.escape(dest.absolutePath)
            val (_, err, exit) = try {
                executeShell("mv $escapedSrc $escapedDst")
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
            val escaped = SevenZipCommand.escape(entry.path)
            val flag = if (entry.isDirectory) "-rf" else "-f"
            val (_, err, exit) = try {
                executeShell("rm $flag $escaped")
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
            val escaped = SevenZipCommand.escape(target.absolutePath)
            val cmd = if (isFolder) "mkdir $escaped" else "touch $escaped"
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

    /** 批量删除。成功返回 null，失败返回最后一条错误信息。 */
    fun deleteEntries(entries: List<FileEntry>): String? {
        var lastError: String? = null
        for (entry in entries) {
            val err = deleteEntry(entry)
            if (err != null) lastError = err
        }
        return lastError
    }

    /**
     * 批量删除（带进度，在 IO 线程执行）。
     * 完成后回调 onDone(error)，error 为 null 表示成功。
     */
    fun deleteEntriesWithProgress(entries: List<FileEntry>, toRecycleBin: Boolean, onDone: (String?) -> Unit) {
        fileOpCancelFlag.set(false)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 预处理：计算总大小
                var totalSize = 0L
                for (entry in entries) totalSize += calculateTotalSize(entry.path)
                var processedBytes = 0L

                for ((index, entry) in entries.withIndex()) {
                    if (fileOpCancelFlag.get()) {
                        _fileOpProgress.value = null
                        withContext(Dispatchers.Main) { onDone("已取消") }
                        return@launch
                    }
                    _fileOpProgress.value = FileOpProgress(
                        phase = "正在删除",
                        currentBytes = processedBytes,
                        totalBytes = totalSize,
                        currentFileName = entry.name,
                        fileIndex = index,
                        fileCount = entries.size
                    )
                    val error = if (toRecycleBin) moveToRecycleBin(entry) else deleteEntry(entry)
                    if (error != null) {
                        withContext(Dispatchers.Main) { onDone(error) }
                        return@launch
                    }
                    processedBytes += calculateTotalSize(entry.path)
                }
                _fileOpProgress.value = null
                withContext(Dispatchers.Main) { onDone(null) }
            } catch (e: Exception) {
                _fileOpProgress.value = null
                withContext(Dispatchers.Main) { onDone(e.message ?: "删除失败") }
            }
        }
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
        if (isInRecycleBin) {
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
        if (isInRecycleBin) {
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

    /** 添加历史记录：去重（按 path）、上限 100 条 FIFO 淘汰 */
    fun addHistory(name: String, path: String, isDirectory: Boolean) {
        val entry = HistoryEntry(name, path, isDirectory)
        val filtered = historyList.filter { it.path != path }
        historyList = (listOf(entry) + filtered).take(MAX_HISTORY_SIZE)
    }

    fun saveHistory() {
        try { historyFile.writeText(historyJson.encodeToString(historyList)) } catch (_: Exception) {}
    }

    fun saveBookmarks() {
        try { bookmarkFile.writeText(historyJson.encodeToString(bookmarkList)) } catch (_: Exception) {}
    }

    // ── 目录列表 ──
    fun listDirectory(displayPath: String, realPath: String = displayPath): List<FileEntry> {
        DiagnosticLog.log("FileMgr", ">>> listDirectory START displayPath=$displayPath realPath=$realPath useRoot=$isRootEngine")
        loadError = null
        val t0 = System.currentTimeMillis()
        val effectiveRoot = if (isRootEngine) "/" else safeDefault

        var entries = listWithLs(realPath, showHiddenFiles, useRoot = isRootEngine, effectiveRoot = effectiveRoot, displayPath = displayPath)

        // 兜底：ls 完全没结果且报错 → 退到 File.listFiles
        // Android/data 等受保护路径跳过兜底，因为 File API 无法访问
        val isProtectedPath = realPath.contains("/Android/data") || realPath.contains("/Android/obb")
        if (entries.isEmpty() && loadError != null && !isProtectedPath) {
            DiagnosticLog.log("FileMgr", "ls 失败，回退 File API")
            val prevErr = loadError
            loadError = null
            val fileEntries = listWithFile(realPath, showHiddenFiles, effectiveRoot)
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
        DiagnosticLog.log("FileMgr", "<<< listDirectory END displayPath=$displayPath entries=${entries.size} took=${took}ms err=${loadError?.javaClass?.simpleName}")
        return entries
    }

    // ── 文件操作 ──
    fun openFile(context: Context, entry: FileEntry, isDebug: Boolean = false): Screen? {
        DiagnosticLog.log("OpenFile", "请求打开: ${entry.path}")
        if (entry.name.endsWith(".apk", ignoreCase = true)) {
            DiagnosticLog.log("OpenFile", "APK 文件，弹出信息弹窗: ${entry.name}")
            pendingApkEntry = entry
            return null
        }
        if (entry.name.endsWith(".apex", ignoreCase = true)) {
            DiagnosticLog.log("OpenFile", "拒绝打开 apex: ${entry.name}")
            Toast.makeText(context, "APEX 文件无法直接打开", Toast.LENGTH_SHORT).show()
            return null
        }
        if (ArchiveBrowser.isArchiveFile(entry.name)) {
            if (isDebug) {
                DiagnosticLog.log("OpenFile", "压缩包文件（Debug 模式），解析信息: ${entry.name}")
                debugOpenArchive(entry)
            } else {
                DiagnosticLog.log("OpenFile", "压缩包文件，进入浏览模式: ${entry.name}")
                openArchive(entry)
            }
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
        val escapedPath = SevenZipCommand.escape(dirPath)
        val cmd = "ls -lap $escapedPath"
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

        // 收集软链接，批量检测目标类型
        val symlinks = mutableMapOf<String, String>()
        for (raw in stdout.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("total ")) continue
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 8 || parts[0].length < 10) continue
            if (parts[0][0] == 'l') {
                val rn = parseLsFilename(line) ?: continue
                val nm = if (rn.contains(" -> ")) rn.substringBefore(" -> ") else rn.trimEnd('/')
                if (nm == "." || nm == "..") continue
                symlinks["$dirPath/$nm"] = rn.substringAfter(" -> ", "")
            }
        }
        checkSymlinkTargets(symlinks)

        for (raw in stdout.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("total ")) continue
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 8) continue
            val perms = parts[0]
            if (perms.length < 10) continue
            val rawName = parseLsFilename(line) ?: continue
            val name = if (rawName.contains(" -> ")) rawName.substringBefore(" -> ") else rawName.trimEnd('/')
            if (name == "." || name == "..") continue
            val childPath = "$dirPath/$name"
            val isDir = when {
                perms[0] == 'd' -> true
                perms[0] == 'l' -> symlinkTypeCache[childPath] ?: false
                else -> rawName.endsWith("/")
            }
            val size = parts[4].toLongOrNull() ?: 0L
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

    private fun listWithLs(path: String, showHidden: Boolean, useRoot: Boolean, effectiveRoot: String, displayPath: String = path): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        val normalizedPath = if (path == "/") "/" else path.trimEnd('/').ifEmpty { "/" }
        val normalizedDisplayPath = if (displayPath == "/") "/" else displayPath.trimEnd('/').ifEmpty { "/" }
        val escapedPath = SevenZipCommand.escape(normalizedPath)

        // 判断是否使用 Shizuku（ADB 权限 + Shizuku 在线）
        val useShizuku = !useRoot && SpecialPermissionVerifier.isShizukuAuthorized(getApplication())

        // Root/Shizuku 使用长格式 ls -lap 以获取文件大小和时间戳
        val lsFlags = if (useShizuku || useRoot) {
            buildString {
                append("-l")
                if (showHidden) append("a")
                append("p")
            }
        } else {
            if (showHidden) "-1Ap" else "-1p"
        }
        val command = "ls $lsFlags $escapedPath"
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
            // 收集软链接，批量检测目标类型
            val symlinks = mutableMapOf<String, String>()
            for (raw in lines) {
                if (raw.startsWith("total ")) continue
                val parts = raw.split("\\s+".toRegex())
                if (parts.size < 8 || parts[0].length < 10) continue
                if (parts[0][0] == 'l') {
                    val rn = parseLsFilename(raw) ?: continue
                    val nm = if (rn.contains(" -> ")) rn.substringBefore(" -> ") else rn.trimEnd('/')
                    if (nm == "." || nm == "..") continue
                    val cp = if (normalizedDisplayPath == "/") "/$nm" else "$normalizedDisplayPath/$nm"
                    symlinks[cp] = rn.substringAfter(" -> ", "")
                }
            }
            checkSymlinkTargets(symlinks)

            // 长格式解析 ls -lap: drwxrwx--x  4 root sdcard_rw  4096 2024-01-01 00:00 dirname/
            for (raw in lines) {
                if (raw.startsWith("total ")) continue
                val parts = raw.split("\\s+".toRegex())
                if (parts.size < 8) continue
                val perms = parts[0]
                if (perms.length < 10) continue
                val rawName = parseLsFilename(raw) ?: continue
                // 提取文件名：软链接去掉 " -> target"，目录去掉尾部 /
                val name = if (rawName.contains(" -> ")) rawName.substringBefore(" -> ") else rawName.trimEnd('/')
                if (name == "." || name == "..") continue
                if (!showHidden && name.startsWith(".")) continue
                val childPath = if (normalizedDisplayPath == "/") "/$name" else "$normalizedDisplayPath/$name"
                val isDir = when {
                    perms[0] == 'd' -> true
                    perms[0] == 'l' -> symlinkTypeCache[childPath] ?: false
                    else -> rawName.endsWith("/")
                }
                if (isDir) dirCount++ else fileCount++
                val sz = parts[4].toLongOrNull() ?: 0L
                val modified = try {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse("${parts[5]} ${parts[6]}")?.time ?: 0L
                } catch (_: Exception) { 0L }
                entries.add(FileEntry(childPath, name, isDir, perms, if (isDir) 0L else sz, modified))
            }
        } else {
            // 短格式解析 ls -1p: dirname/ 或 filename
            for (raw in lines) {
                val name = if (raw.endsWith("/")) raw.dropLast(1) else raw
                if (name == "." || name == "..") continue
                if (!showHidden && name.startsWith(".")) continue
                val childPath = if (normalizedDisplayPath == "/") "/$name" else "$normalizedDisplayPath/$name"
                val isDir = raw.endsWith("/")
                if (isDir) dirCount++ else fileCount++
                val perm = ""
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
        val file = File(entry.path)

        // shell 路径: ls -lapd 获取权限/用户名/组名，stat 获取 UID/GID 数值
        // 非 shell 路径: Os.stat 获取全部
        var permission = ""
        var owner = ""
        var group = ""

        if (hasShellEngine) {
            val escaped = SevenZipCommand.escape(entry.path)
            val (lsOut, _, lsExit) = try {
                executeShell("ls -lapd $escaped")
            } catch (_: Exception) { Triple("", "", -1) }
            if (lsExit == 0 && lsOut.isNotBlank()) {
                val line = lsOut.lines().firstOrNull { it.isNotBlank() && !it.startsWith("total ") }
                if (line != null) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 7) {
                        val permStr = parts[0]
                        val shellOwner = parts[2]
                        val shellGroup = parts[3]
                        val modeFromShell = parseRwxToMode(permStr)
                        if (modeFromShell != 0) {
                            permission = "${permStr}(${String.format("%03o", modeFromShell and 0x1FF)})"
                        }
                        // 通过 stat 获取 UID/GID 数值
                        val (statOut, _, statExit) = try {
                            executeShell("stat -c '%u %g' '$escaped'")
                        } catch (_: Exception) { Triple("", "", -1) }
                        if (statExit == 0 && statOut.isNotBlank()) {
                            val statParts = statOut.trim().split("\\s+".toRegex())
                            val uid = statParts.getOrNull(0)?.toIntOrNull()
                            val gid = statParts.getOrNull(1)?.toIntOrNull()
                            owner = if (uid != null) "$shellOwner ($uid)" else shellOwner
                            group = if (gid != null) "$shellGroup ($gid)" else shellGroup
                        } else {
                            owner = shellOwner
                            group = shellGroup
                        }
                    }
                }
            }
        } else {
            val stat = try { Os.stat(entry.path) } catch (_: Exception) { null }
            if (stat != null) {
                val mode = stat.st_mode
                permission = "${formatPermission(mode)}(${String.format("%03o", mode and 0x1FF)})"
                owner = resolveUserName(stat.st_uid).let { if (it.isNotBlank()) "$it (${stat.st_uid})" else "${stat.st_uid}" }
                group = resolveGroupName(stat.st_gid).let { if (it.isNotBlank()) "$it (${stat.st_gid})" else "${stat.st_gid}" }
            }
        }

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
            if (hasShellEngine) {
                // shell 模式：一条 find 命令递归统计文件和文件夹数量
                val escaped = SevenZipCommand.escape(entry.path)
                val cmd = "d=\$(find $escaped -mindepth 1 -type d | wc -l); f=\$(find $escaped -type f | wc -l); echo \"\$d \$f\""
                val (out, _, exit) = try { executeShell(cmd) } catch (_: Exception) { Triple("", "", -1) }
                if (exit == 0 && out.isNotBlank()) {
                    val parts = out.trim().split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        folderCount = parts[0].toIntOrNull() ?: 0
                        fileCount = parts[1].toIntOrNull() ?: 0
                    }
                }
            } else {
                // 无 shell 引擎：Java API 递归统计
                fun countRecursive(dir: File) {
                    val children = try { dir.listFiles() } catch (_: Exception) { null } ?: return
                    for (child in children) {
                        if (child.isDirectory) { folderCount++; countRecursive(child) }
                        else fileCount++
                    }
                }
                countRecursive(file)
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

    /**
     * android_filesystem_config.h 完整 AID 映射（AOSP 源码）。
     * 来源: vvb2060/Magica/app/src/main/jni/android_filesystem_config.h
     */
    private val SYSTEM_UID_MAP = mapOf(
        0 to "root",
        1 to "daemon",
        2 to "bin",
        3 to "sys",
        1000 to "system",
        1001 to "radio",
        1002 to "bluetooth",
        1003 to "graphics",
        1004 to "input",
        1005 to "audio",
        1006 to "camera",
        1007 to "log",
        1008 to "compass",
        1009 to "mount",
        1010 to "wifi",
        1011 to "adb",
        1012 to "install",
        1013 to "media",
        1014 to "dhcp",
        1015 to "sdcard_rw",
        1016 to "vpn",
        1017 to "keystore",
        1018 to "usb",
        1019 to "drm",
        1020 to "mdnsr",
        1021 to "gps",
        1023 to "media_rw",
        1024 to "mtp",
        1026 to "drmrpc",
        1027 to "nfc",
        1028 to "sdcard_r",
        1029 to "clat",
        1030 to "loop_radio",
        1031 to "media_drm",
        1032 to "package_info",
        1033 to "sdcard_pics",
        1034 to "sdcard_av",
        1035 to "sdcard_all",
        1036 to "logd",
        1037 to "shared_relro",
        1038 to "dbus",
        1039 to "tlsdate",
        1040 to "media_ex",
        1041 to "audioserver",
        1042 to "metrics_coll",
        1043 to "metricsd",
        1044 to "webserv",
        1045 to "debuggerd",
        1046 to "media_codec",
        1047 to "cameraserver",
        1048 to "firewall",
        1049 to "trunks",
        1050 to "nvram",
        1051 to "dns",
        1052 to "dns_tether",
        1053 to "webview_zygote",
        1054 to "vehicle_network",
        1055 to "media_audio",
        1056 to "media_video",
        1057 to "media_image",
        1058 to "tombstoned",
        1059 to "media_obb",
        1060 to "ese",
        1061 to "ota_update",
        1062 to "automotive_evs",
        1063 to "lowpan",
        1064 to "hsm",
        1065 to "reserved_disk",
        1066 to "statd",
        1067 to "incidentd",
        1068 to "secure_element",
        1069 to "lmkd",
        1070 to "llkd",
        1071 to "iorapd",
        1072 to "gpu_service",
        1073 to "network_stack",
        1074 to "gsid",
        1075 to "fsverity_cert",
        1076 to "credstore",
        1077 to "external_storage",
        1078 to "ext_data_rw",
        1079 to "ext_obb_rw",
        1080 to "context_hub",
        1081 to "virtualizationservice",
        1082 to "artd",
        1083 to "uwb",
        1084 to "thread_network",
        1085 to "diced",
        1086 to "dmesgd",
        1087 to "jc_weaver",
        1088 to "jc_strongbox",
        1089 to "jc_identitycred",
        1090 to "sdk_sandbox",
        1091 to "security_log_writer",
        1092 to "prng_seeder",
        1093 to "uprobestats",
        1094 to "cros_ec",
        1095 to "mmd",
        2000 to "shell",
        2001 to "cache",
        2002 to "diag",
        3001 to "net_bt_admin",
        3002 to "net_bt",
        3003 to "inet",
        3004 to "net_raw",
        3005 to "net_admin",
        3006 to "net_bw_stats",
        3007 to "net_bw_acct",
        3009 to "readproc",
        3010 to "wakelock",
        3011 to "uhid",
        3012 to "readtracefs",
        3013 to "virtualmachine",
        9997 to "everybody",
        9998 to "misc",
        9999 to "nobody",
    )

    /** 读取全部系统用户：系统 UID 映射 + pm list packages -U（应用 UID） */
    fun getSystemUsers(): List<SystemUser> {
        val result = mutableMapOf<Int, String>()

        // 1. 系统 UID（android_filesystem_config.h）
        result.putAll(SYSTEM_UID_MAP)

        // 2. 应用 UID（≥10000）：通过 pm list packages -U 获取包名+UID
        if (isRootEngine) {
            val (stdout, _, exit) = try {
                SpecialPermissionVerifier.executeRootCommandFull("pm list packages -U")
            } catch (_: Exception) { Triple("", "", -1) }
            if (exit == 0 && stdout.isNotBlank()) {
                stdout.lines().forEach { line ->
                    // 格式: "package:com.example.app uid:10123"
                    val pkg = line.removePrefix("package:").substringBefore(" ").trim()
                    val uidStr = line.substringAfter("uid:", "").trim()
                    val uid = uidStr.toIntOrNull()
                    if (uid != null && uid >= 10000 && uid !in result) {
                        result[uid] = pkg
                    }
                }
            }
        }

        return result.map { (uid, name) -> SystemUser(uid, name) }.sortedBy { it.uid }
    }

    /** 读取全部系统用户组：系统 GID 映射 + pm list packages -G（应用 GID） */
    fun getSystemGroups(): List<SystemGroup> {
        val result = mutableMapOf<Int, String>()

        // 系统 GID（与 UID 共享同一套映射）
        SYSTEM_UID_MAP.forEach { (id, name) -> result[id] = name }

        // 应用 GID：pm list packages -G
        if (isRootEngine) {
            val (stdout, _, exit) = try {
                SpecialPermissionVerifier.executeRootCommandFull("pm list packages -G")
            } catch (_: Exception) { Triple("", "", -1) }
            if (exit == 0 && stdout.isNotBlank()) {
                stdout.lines().forEach { line ->
                    val pkg = line.removePrefix("package:").substringBefore(" ").trim()
                    val gidStr = line.substringAfter("gid:", "").trim()
                    val gid = gidStr.toIntOrNull()
                    if (gid != null && gid >= 10000 && gid !in result) {
                        result[gid] = pkg
                    }
                }
            }
        }

        return result.map { (gid, name) -> SystemGroup(gid, name) }.sortedBy { it.gid }
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
        val escapedPath = SevenZipCommand.escape(path)

        // chmod
        val octal = String.format("%o", mode and 0x1FF)
        val (_, chmodErr, chmodExit) = try {
            SpecialPermissionVerifier.executeRootCommandFull("chmod $octal $escapedPath")
        } catch (e: Exception) { return "chmod 执行异常: ${e.message}" }
        if (chmodExit != 0) return "chmod 失败 (exit $chmodExit): $chmodErr"

        // chown
        val (_, chownErr, chownExit) = try {
            SpecialPermissionVerifier.executeRootCommandFull("chown $uid:$gid $escapedPath")
        } catch (e: Exception) {
            // 回滚 chmod
            val rollbackOctal = String.format("%o", originalMode and 0x1FF)
            try { SpecialPermissionVerifier.executeRootCommandFull("chmod $rollbackOctal $escapedPath") } catch (_: Exception) {}
            return "chown 执行异常: ${e.message}"
        }
        if (chownExit != 0) {
            // 回滚 chmod
            val rollbackOctal = String.format("%o", originalMode and 0x1FF)
            try { SpecialPermissionVerifier.executeRootCommandFull("chmod $rollbackOctal $escapedPath") } catch (_: Exception) {}
            return "chown 失败 (exit $chownExit): $chownErr"
        }

        return null
    }

    // ── 扩展文件属性（chattr/lsattr） ──

    /** 将 FUSE 路径转换为底层真实路径，使 chattr/lsattr 能操作 inode 标志。 */
    private fun toRealPathForAttr(path: String): String {
        // /storage/emulated/0/xxx → /data/media/0/xxx
        val regex = Regex("^/storage/emulated/(\\d+)/")
        val match = regex.find(path)
        return if (match != null) {
            path.replaceFirst("/storage/emulated/${match.groupValues[1]}/", "/data/media/${match.groupValues[1]}/")
        } else {
            path
        }
    }

    /** 读取扩展属性标志字符串（如 "----i----------" 或 "-a-----------"）。无 shell 引擎时返回空字符串。 */
    fun readExtFlags(path: String): String {
        if (!hasShellEngine) return ""
        val realPath = toRealPathForAttr(path)
        val escaped = SevenZipCommand.escape(realPath)
        val (out, _, exit) = try { executeShell("lsattr $escaped") } catch (_: Exception) { Triple("", "", -1) }
        if (exit != 0 || out.isBlank()) return ""
        val line = out.lines().firstOrNull { it.isNotBlank() } ?: return ""
        // lsattr 输出格式: "----i----------  /path/to/file" 或 "----i----------" (部分实现)
        val flags = line.split("\\s+".toRegex()).firstOrNull() ?: return ""
        // 只提取我们关心的标志（i/a），忽略 e/c/s 等文件系统默认标志
        return flags.filter { it == 'i' || it == 'a' }
    }

    /**
     * 批量读取目录下所有文件的扩展属性（i/a），结果存入 leftExtFlagsMap 或 rightExtFlagsMap。
     * 仅在有 shell 引擎时执行，否则清空对应 map。
     */
    fun loadExtFlagsForDir(dirPath: String, isLeft: Boolean) {
        if (!hasShellEngine) {
            if (isLeft) leftExtFlagsMap = emptyMap() else rightExtFlagsMap = emptyMap()
            return
        }
        val realPath = toRealPathForAttr(dirPath)
        val escaped = SevenZipCommand.escape(realPath.trimEnd('/'))
        // 使用 lsattr 目录/* 展开通配符，确保列出目录内容（Android toybox 的 lsattr 可能不支持目录参数）
        val (out, _, exit) = try {
            executeShell("lsattr $escaped/* 2>/dev/null")
        } catch (_: Exception) {
            Triple("", "", -1)
        }
        if (exit != 0 || out.isBlank()) {
            if (isLeft) leftExtFlagsMap = emptyMap() else rightExtFlagsMap = emptyMap()
            return
        }
        val map = mutableMapOf<String, String>()
        for (raw in out.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            // lsattr 输出: "----i----------  /path/to/file"
            val parts = line.split("\\s+".toRegex(), limit = 2)
            if (parts.size < 2) continue
            val flags = parts[0].filter { it == 'i' || it == 'a' }
            if (flags.isEmpty()) continue
            val nameOrPath = parts[1].trim()
            val name = nameOrPath.substringAfterLast('/')
            if (name.isNotEmpty()) {
                map[name] = flags
            }
        }
        if (isLeft) leftExtFlagsMap = map else rightExtFlagsMap = map
    }

    /** 应用扩展属性修改。传入目标标志字符集（如 "ia" 表示要设置 immutable + append-only）。成功返回 null。 */
    fun applyExtFlags(path: String, desiredFlags: Set<Char>, originalFlags: String): String? {
        if (!isRootEngine) return "需要 Root 权限"
        val realPath = toRealPathForAttr(path)
        val escaped = SevenZipCommand.escape(realPath)
        val originalSet = originalFlags.filter { it == 'i' || it == 'a' }.toSet()
        // 需要添加的标志
        val toAdd = desiredFlags - originalSet
        // 需要移除的标志
        val toRemove = originalSet - desiredFlags
        if (toAdd.isNotEmpty()) {
            val (_, err, exit) = try {
                SpecialPermissionVerifier.executeRootCommandFull("chattr +${toAdd.joinToString("")} $escaped")
            } catch (e: Exception) { return "chattr 执行异常: ${e.message}" }
            if (exit != 0) return "chattr +${toAdd.joinToString("")} 失败: $err"
        }
        if (toRemove.isNotEmpty()) {
            val (_, err, exit) = try {
                SpecialPermissionVerifier.executeRootCommandFull("chattr -${toRemove.joinToString("")} $escaped")
            } catch (e: Exception) { return "chattr 执行异常: ${e.message}" }
            if (exit != 0) return "chattr -${toRemove.joinToString("")} 失败: $err"
        }
        return null
    }

    // ── 文件操作进度系统 ──

    data class FileOpProgress(
        val phase: String,           // "正在复制" / "正在移动" / "正在删除" / "正在压缩" / "正在解压"
        val currentBytes: Long,      // 已处理字节
        val totalBytes: Long,        // 总字节
        val currentFileName: String = "", // 当前处理的文件名
        val isRunning: Boolean = true,
        val fileIndex: Int = 0,      // 当前处理到第几个文件（从 0 开始）
        val fileCount: Int = 0       // 总文件数（0 表示不使用文件计数模式）
    ) {
        val fraction: Float get() {
            // 文件计数模式：按文件数计算进度
            if (fileCount > 0) return fileIndex.toFloat() / fileCount
            // 字节模式
            return if (totalBytes > 0) currentBytes.toFloat() / totalBytes else 0f
        }
    }

    private val _fileOpProgress = MutableStateFlow<FileOpProgress?>(null)
    val fileOpProgress: StateFlow<FileOpProgress?> = _fileOpProgress

    /** 文件操作取消标志 */
    val fileOpCancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 递归计算文件/文件夹总大小（字节） */
    private fun calculateTotalSize(path: String): Long {
        val file = File(path)
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(file)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (f.isDirectory) {
                val children = f.listFiles()
                if (children != null) {
                    for (child in children) stack.add(child)
                }
            } else {
                total += f.length()
            }
        }
        return total
    }

    /**
     * 启动压缩任务。
     * @param entries 待压缩的文件列表
     * @param outputPath 输出压缩包完整路径
     * @param format 格式: zip/7z/tar/tar.gz/tar.bz2/tar.xz
     * @param level 压缩级别 0-9
     * @param password 密码（空=不加密）
     * @param useAes ZIP 是否使用 AES-256
     * @param onProgress 进度回调（主线程）
     * @param onComplete 完成回调（主线程）
     */
    fun compress(
        entries: List<FileEntry>,
        outputPath: String,
        format: String,
        level: Int,
        password: String,
        useAes: Boolean,
        onProgress: (CompressService.ProgressInfo) -> Unit,
        onComplete: (Boolean, String?, String?) -> Unit
    ) {
        compressCancelFlag.set(false)
        compressJob?.cancel()
        compressJob = viewModelScope.launch(Dispatchers.IO) {
            val options = CompressService.CompressOptions(
                sourcePaths = entries.map { it.path },
                outputPath = outputPath,
                format = format,
                compressionLevel = level,
                password = password,
                useAes = useAes
            )
            CompressService.compress(
                context = getApplication(),
                options = options,
                permissionLevel = permissionLevel,
                cancelFlag = compressCancelFlag,
                callback = object : CompressService.ProgressCallback {
                    override fun onProgress(info: CompressService.ProgressInfo) {
                        onProgress(info)
                    }
                    override fun onComplete(success: Boolean, path: String?, error: String?) {
                        launch(Dispatchers.Main) { onComplete(success, path, error) }
                    }
                }
            )
        }
    }

    /** 取消正在进行的压缩任务 */
    fun cancelCompress() {
        compressCancelFlag.set(true)
        compressJob?.cancel()
        compressJob = null
    }

    // ── 解压 ──

    /**
     * 解压压缩包。
     * 解压前通过 7zzs l 获取文件列表和大小，实现真实字节级进度。
     * 若需要密码则通过回调通知 UI 弹密码框。
     */
    fun extract(
        entries: List<FileEntry>,
        outputDir: String,
        password: String,
        onPasswordRequired: () -> Unit,
        onProgress: (CompressService.ProgressInfo) -> Unit,
        onComplete: (Boolean, String?, String?) -> Unit
    ) {
        extractCancelFlag.set(false)
        extractJob?.cancel()
        extractJob = viewModelScope.launch(Dispatchers.IO) {
            val permLevel = legacySp.getString("target_permission_level", "NORMAL") ?: "NORMAL"
            val context = getApplication<Application>()

            for (entry in entries) {
                if (extractCancelFlag.get()) break

                // 若用户未提供密码，先探测是否需要密码
                if (password.isEmpty()) {
                    val needsPassword = ArchiveBrowser.checkPasswordRequired(context, entry.path, permLevel)
                    if (needsPassword == true) {
                        withContext(Dispatchers.Main) { onPasswordRequired() }
                        return@launch
                    }
                }

                // 通过 7zzs l 获取文件列表（含原始大小）
                val sessionResult = ArchiveBrowser.openArchive(
                    context = context,
                    archivePath = entry.path,
                    archiveName = entry.name,
                    permissionLevel = permLevel,
                    password = password
                )
                val session = sessionResult.getOrNull()
                if (session == null) {
                    val err = sessionResult.exceptionOrNull()
                    val msg = err?.message ?: ""
                    withContext(Dispatchers.Main) {
                        onComplete(false, null, "读取压缩包信息失败: $msg")
                    }
                    return@launch
                }

                // 构建 fileSizes 列表（扁平化的文件大小列表，顺序与 7zzs l 输出一致）
                val fileSizes = flattenFileSizes(session.root)
                val totalBytes = fileSizes.sum()

                // 计算单个压缩包的目标目录
                val singleOutputDir = if (entries.size == 1) {
                    outputDir
                } else {
                    // 多个压缩包时，每个解压到以自身命名的子目录
                    "$outputDir/${ArchiveBrowser.stripArchiveExtension(entry.name)}"
                }

                val options = CompressService.ExtractOptions(
                    archivePath = entry.path,
                    outputDir = singleOutputDir,
                    password = password,
                    fileSizes = fileSizes,
                    totalUncompressedBytes = totalBytes
                )

                CompressService.extract(
                    context = context,
                    options = options,
                    permissionLevel = permLevel,
                    cancelFlag = extractCancelFlag,
                    callback = object : CompressService.ProgressCallback {
                        override fun onProgress(info: CompressService.ProgressInfo) {
                            onProgress(info)
                        }
                        override fun onComplete(success: Boolean, path: String?, error: String?) {
                            launch(Dispatchers.Main) { onComplete(success, path, error) }
                        }
                    }
                )
            }
        }
    }

    /** 取消正在进行的解压任务 */
    fun cancelExtract() {
        extractCancelFlag.set(true)
        extractJob?.cancel()
        extractJob = null
    }

    /** 解压完成后刷新文件列表：聚焦面板必刷，非聚焦面板仅在压缩包所在目录或解压目录时刷新 */
    fun refreshAfterExtract(outputDir: String) {
        val focusedPath = if (focusedPanel == FocusedPanel.LEFT) leftPath else rightPath
        val otherPath = if (focusedPanel == FocusedPanel.LEFT) rightPath else leftPath
        val otherIsLeft = focusedPanel != FocusedPanel.LEFT

        // 聚焦面板：必刷
        refreshPanel(isLeft = focusedPanel == FocusedPanel.LEFT)

        // 非聚焦面板：仅当在压缩包所在目录（即聚焦目录）或解压目录时刷新
        if (otherPath == focusedPath || otherPath == outputDir) {
            refreshPanel(isLeft = otherIsLeft)
        }
    }

    private fun refreshPanel(isLeft: Boolean) {
        if (isLeft) {
            leftEntries = listDirectory(leftPath)
            loadExtFlagsForDir(leftPath, isLeft = true)
        } else {
            rightEntries = listDirectory(rightPath)
            loadExtFlagsForDir(rightPath, isLeft = false)
        }
    }

    /** 递归展开目录树，获取扁平的文件大小列表（顺序与 7zzs l 一致） */
    private fun flattenFileSizes(node: ArchiveBrowser.ArchiveNode): List<Long> {
        val result = mutableListOf<Long>()
        fun walk(n: ArchiveBrowser.ArchiveNode) {
            for (child in n.children) {
                if (child.isDirectory) {
                    walk(child)
                } else {
                    result.add(child.size)
                }
            }
        }
        walk(node)
        return result
    }

    // ── 压缩包浏览 ──

    /** 打开压缩包（首次，无密码）。若需要密码则设置 archivePasswordRequest 触发弹窗 */
    fun openArchive(entry: FileEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val permLevel = legacySp.getString("target_permission_level", "NORMAL") ?: "NORMAL"
                val currentPathVal = if (focusedPanel == FocusedPanel.LEFT) leftPath else rightPath
                val currentEntriesVal = if (focusedPanel == FocusedPanel.LEFT) leftEntries else rightEntries

                val passwordCheck = ArchiveBrowser.checkPasswordRequired(context, entry.path, permLevel)

                if (passwordCheck == null) {
                    // exitCode≠0 且未检测到加密标志 → 档案本身有问题
                    withContext(Dispatchers.Main) {
                        archiveOpenError = Pair(entry.name, "该压缩包无法读取，可能已损坏或格式不受支持。")
                    }
                    return@launch
                }

                if (passwordCheck == true) {
                    // Encrypted = + → 需要密码
                    withContext(Dispatchers.Main) { archivePasswordRequest = entry }
                    return@launch
                }

                // 不需要密码，直接打开
                val result = ArchiveBrowser.openArchive(
                    context = context,
                    archivePath = entry.path,
                    archiveName = entry.name,
                    permissionLevel = permLevel,
                    password = "",
                    originalPath = currentPathVal,
                    originalEntries = currentEntriesVal
                )

                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { session ->
                            enterArchiveMode(session)
                        },
                        onFailure = { error ->
                            archiveOpenError = Pair(entry.name, "打开压缩包失败: ${error.message}")
                        }
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    archiveOpenError = Pair(entry.name, "打开压缩包异常: ${e.message}")
                }
            }
        }
    }

    /** Debug 模式：解析压缩包信息，弹出预览弹窗 */
    fun debugOpenArchive(entry: FileEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val permLevel = legacySp.getString("target_permission_level", "NORMAL") ?: "NORMAL"
            val currentPathVal = if (focusedPanel == FocusedPanel.LEFT) leftPath else rightPath
            val currentEntriesVal = if (focusedPanel == FocusedPanel.LEFT) leftEntries else rightEntries

            val info = ArchiveBrowser.parseArchiveDebug(
                context = context,
                archivePath = entry.path,
                archiveName = entry.name,
                permissionLevel = permLevel,
                originalPath = currentPathVal,
                originalEntries = currentEntriesVal
            )

            withContext(Dispatchers.Main) {
                archiveDebugInfo = info.copy(sourceEntry = entry)
            }
        }
    }

    /** Debug 弹窗确认打开：从 debugInfo.session 进入压缩包浏览模式，或触发密码对话框 */
    fun confirmOpenArchive() {
        val info = archiveDebugInfo ?: return
        // 需要密码但无 session → 弹出密码对话框
        if (info.passwordRequired && info.session == null) {
            val entry = info.sourceEntry ?: return
            archiveDebugInfo = null
            archivePasswordRequest = entry
            return
        }
        val session = info.session ?: return
        enterArchiveMode(session)
        archiveDebugInfo = null
    }

    /** 密码弹窗验证回调：带密码重试打开压缩包 */
    /** 带密码重试打开压缩包（挂起函数，供密码弹窗 onVerify 使用）。返回 true=成功 */
    suspend fun openArchiveWithPassword(entry: FileEntry, password: String): Boolean {
        return try {
            val permLevel = legacySp.getString("target_permission_level", "NORMAL") ?: "NORMAL"
            val currentPathVal = if (focusedPanel == FocusedPanel.LEFT) leftPath else rightPath
            val currentEntriesVal = if (focusedPanel == FocusedPanel.LEFT) leftEntries else rightEntries

            val result = ArchiveBrowser.openArchive(
                context = context,
                archivePath = entry.path,
                archiveName = entry.name,
                permissionLevel = permLevel,
                password = password,
                originalPath = currentPathVal,
                originalEntries = currentEntriesVal
            )

            result.fold(
                onSuccess = { session ->
                    withContext(Dispatchers.Main) {
                        enterArchiveMode(session)
                        archivePasswordRequest = null
                    }
                    true
                },
                onFailure = { error ->
                    // 密码错误或其他失败，保持弹窗让用户重试
                    Log.w("FileMgr", "打开压缩包失败: ${error.message}")
                    false
                }
            )
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                loadError = RuntimeException("打开压缩包失败: ${e.message}")
                archivePasswordRequest = null
            }
            false
        }
    }

    /** 进入压缩包浏览模式 */
    private fun enterArchiveMode(session: ArchiveBrowser.ArchiveSession) {
        if (focusedPanel == FocusedPanel.LEFT) {
            leftEntries = session.currentEntries
            leftPath = session.currentPath
        } else {
            rightEntries = session.currentEntries
            rightPath = session.currentPath
        }
        archiveSession = session
        isInArchiveMode = true
        ArchiveBrowser.saveSessionCache(context, session, focusedPanel.name)
    }

    /** 从 Screen 层调用进入压缩包浏览模式（密码验证成功后） */
    fun enterArchiveModeFromScreen(session: ArchiveBrowser.ArchiveSession) {
        enterArchiveMode(session)
    }

    /** 在压缩包内导航到子目录 */
    fun navigateInArchive(entry: FileEntry) {
        val session = archiveSession ?: return
        val newSession = ArchiveBrowser.navigateTo(session, entry.name)
        if (newSession == null) {
            loadError = RuntimeException("无法进入压缩包子目录: ${entry.name}")
            return
        }
        archiveSession = newSession
        if (focusedPanel == FocusedPanel.LEFT) {
            leftEntries = newSession.currentEntries
            leftPath = newSession.currentPath
        } else {
            rightEntries = newSession.currentEntries
            rightPath = newSession.currentPath
        }
        ArchiveBrowser.saveSessionCache(context, newSession, focusedPanel.name)
    }

    /** 压缩包内返回上一级，返回 false 表示已在根目录 */
    fun archiveGoUp(): Boolean {
        val session = archiveSession ?: return false
        val newSession = ArchiveBrowser.navigateUp(session)
        if (newSession == null) {
            // 已在根目录，退出压缩包
            exitArchive()
            return true
        }
        archiveSession = newSession
        if (focusedPanel == FocusedPanel.LEFT) {
            leftEntries = newSession.currentEntries
            leftPath = newSession.currentPath
        } else {
            rightEntries = newSession.currentEntries
            rightPath = newSession.currentPath
        }
        ArchiveBrowser.saveSessionCache(context, newSession, focusedPanel.name)
        return true
    }

    /** 退出压缩包浏览模式，恢复原始状态 */
    fun exitArchive() {
        val session = archiveSession ?: return
        if (focusedPanel == FocusedPanel.LEFT) {
            leftPath = session.originalPath
            leftEntries = session.originalEntries.ifEmpty { listDirectory(session.originalPath) }
        } else {
            rightPath = session.originalPath
            rightEntries = session.originalEntries.ifEmpty { listDirectory(session.originalPath) }
        }
        archiveSession = null
        isInArchiveMode = false
        ArchiveBrowser.clearSessionCache(context)
    }

    /** 当前是否在压缩包根目录 */
    fun isAtArchiveRoot(): Boolean {
        val session = archiveSession ?: return true
        return ArchiveBrowser.isAtRoot(session)
    }

    companion object {
        var MAX_HISTORY_SIZE = 100
        private val RESTRICTED_ANDROID_PREFIXES = listOf(
            "/storage/emulated/0/Android/data/",
            "/storage/emulated/0/Android/obb/",
            "/sdcard/Android/data/",
            "/sdcard/Android/obb/"
        )
        private const val OWN_PACKAGE_NAME = "com.whmdg.mczj.tools"

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
