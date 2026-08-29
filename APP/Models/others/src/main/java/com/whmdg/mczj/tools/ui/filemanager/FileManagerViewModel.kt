package com.whmdg.mczj.tools.ui.filemanager

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient
import com.whmdg.mczj.tools.fileop.webdav.WebDavServerConfig
import com.whmdg.mczj.tools.fileop.sync.SyncFileProgress
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.ui.FileEntry
import com.whmdg.mczj.tools.ui.Screen
import com.whmdg.mczj.tools.ui.SizeCalcManager
import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
import com.whmdg.mczj.tools.encryption.data.FolderSizeInfo
import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.ShellException
import com.whmdg.mczj.tools.security.ShellExecutor
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.util.ArchiveBrowser
import com.whmdg.mczj.tools.util.CompressService
import com.whmdg.mczj.tools.util.CompressPreviewCache
import com.whmdg.mczj.tools.util.SevenZipCommand
import com.whmdg.mczj.tools.util.DiagnosticLog
import com.whmdg.mczj.tools.util.FileAccessLevel
import com.whmdg.mczj.tools.util.FileAccessor
import com.whmdg.mczj.tools.util.SizeCalcResult
import com.whmdg.mczj.tools.util.calculateFolderSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import android.system.ErrnoException
import android.system.Os
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import com.whmdg.mczj.tools.encryption.services.VaultSession
import com.whmdg.mczj.tools.encryption.core.FilenameCodec
import com.whmdg.mczj.tools.encryption.core.FileCodec
import com.whmdg.mczj.tools.encryption.core.FileConstants
import com.whmdg.mczj.tools.encryption.services.CryptoService
import com.whmdg.mczj.tools.encryption.services.VaultKeyHolder
import com.whmdg.mczj.tools.encryption.services.VaultViewContext
import com.whmdg.mczj.tools.ui.viewer.ViewerActivity

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
    val fileCount: Int? = null,  // null 表示正在统计
    val folderCount: Int? = null,  // null 表示正在统计
    val isDirectory: Boolean
)

/** 面板标识（仅 Coordinator 层使用，不传入 Controller） */
enum class PanelId { LEFT, RIGHT }

/**
 * 单面板控制器 — 沙箱实例。
 *
 * 一份代码，运行时被实例化两次。内部完全没有 left/right/左/右字样，
 * 不知道自己会被贴什么标签，不知道另一个实例的存在。
 *
 * 所有可变状态封装在 [VmPanelState] 中。
 */
class FilePaneController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hasShellEngine: () -> Boolean,
    private val isRootEngine: () -> Boolean,
    private val permissionLevel: String,
    private val safeDefault: String,
    // 共享设置通过 lambda 注入（由 Coordinator 维护实际值）
    private val showHiddenFiles: () -> Boolean,
    private val sortField: () -> SortField,
    private val sortOrder: () -> SortOrder,
    private val folderSizeDb: () -> FolderSizeDb
) {
    // ── Vault 会话（每个 Controller 独立持有，由 Coordinator 注入） ──
    var vaultSession by mutableStateOf<VaultSession?>(null)
        internal set
    val isVaultMode: Boolean get() = vaultSession != null
    // ── 面板状态（沙箱数据，不含任何身份标识） ──
    val state = VmPanelState("/storage/emulated/0")

    // ── 回调（由 Coordinator 注入，用于处理需要身份信息的副作用） ──
    /** 进入压缩包模式时触发（Coordinator 用于保存会话缓存） */
    var onArchiveSessionEntered: ((ArchiveBrowser.ArchiveSession) -> Unit)? = null
    /** 保险箱内容被修改时触发（Coordinator 用于更新 lastModifiedAt） */
    var onVaultContentModified: ((vaultId: Int) -> Unit)? = null

    // 最近一次 listDirEntriesViaShell 的 stderr，用于调用方判断失败原因
    private var lastShellStderr = ""

    // ── 压缩/解压任务 ──
    internal val compressCancelFlag = AtomicBoolean(false)
    internal var compressJob: Job? = null
    internal val extractCancelFlag = AtomicBoolean(false)
    internal var extractJob: Job? = null

    // ── 文件操作进度 ──
    data class FileOpProgress(
        val phase: String,
        val currentBytes: Long,
        val totalBytes: Long,
        val currentFileName: String = "",
        val isRunning: Boolean = true,
        val fileIndex: Int = 0,
        val fileCount: Int = 0
    ) {
        val fraction: Float get() {
            if (fileCount > 0) return fileIndex.toFloat() / fileCount
            return if (totalBytes > 0) currentBytes.toFloat() / totalBytes else 0f
        }
    }

    internal val _fileOpProgress = MutableStateFlow<FileOpProgress?>(null)
    val fileOpProgress: StateFlow<FileOpProgress?> = _fileOpProgress
    val fileOpCancelFlag = AtomicBoolean(false)

    /** 递归计算文件/文件夹总大小（字节） */
    fun calculateTotalSize(path: String): Long {
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

    // ── 单面板状态容器 ──
    class VmPanelState(
        defaultPath: String
    ) {
        var path by mutableStateOf<PanelPath>(PanelPath.FileSystem(defaultPath))
            internal set
        var entries by mutableStateOf(listOf<FileEntry>())
            internal set
        var extFlagsMap by mutableStateOf<Map<String, String>>(emptyMap())
            internal set
        var navState by mutableStateOf(PanelNavState())
            internal set
        var isLoading by mutableStateOf(false)
            internal set
        var loadError by mutableStateOf<Throwable?>(null)
            internal set
        var selectedPaths by mutableStateOf(setOf<String>())
            internal set
        var isInRecycleBin by mutableStateOf(false)
            internal set

        // ── 压缩包浏览 ──
        var archiveSession by mutableStateOf<ArchiveBrowser.ArchiveSession?>(null)
            internal set
        var archivePasswordRequest by mutableStateOf<FileEntry?>(null)
            internal set
        var archiveDebugInfo by mutableStateOf<ArchiveBrowser.ArchiveDebugInfo?>(null)
            internal set
        var archiveOpenError by mutableStateOf<com.whmdg.mczj.tools.ui.MessageDialogData?>(null)
            internal set

        // ── WebDAV ──
        var webDavClient by mutableStateOf<WebDavFileClient?>(null)
            internal set
        var webDavConfig by mutableStateOf<WebDavServerConfig?>(null)
            internal set
        var webDavCurrentPath by mutableStateOf("/")
            internal set
        val isWebDavMode: Boolean get() = webDavClient != null

        // ── 滚动 ──
        var pendingScrollTo by mutableStateOf<Triple<String, Int, Int>?>(null)
            internal set
        var pendingScrollToFile by mutableStateOf<String?>(null)
            internal set
        var currentScrollIndex by mutableIntStateOf(0)
            internal set
        var currentScrollOffset by mutableIntStateOf(0)
            internal set

        // ── 回收站路径 ──
        var recycleBinPath by mutableStateOf("")
            internal set

        internal var loadJob: Job? = null
        internal var loadMetadataJob: Job? = null
        internal var loadVersion = 0L

        fun resetTransientState() {
            selectedPaths = emptySet()
            loadError = null
        }
    }

    // ── 滚动位置保存（每个 Controller 独立，直接用 path 作 key） ──
    private val scrollPositions = HashMap<String, Pair<Int, Int>>()

    fun saveScrollPosition(path: String, index: Int, offset: Int) {
        scrollPositions[path] = index to offset
    }

    fun getScrollPosition(path: String): Pair<Int, Int>? = scrollPositions[path]

    fun clearScrollPosition(path: String) {
        scrollPositions.remove(path)
    }



    // ═══ Phase 2: Shell 工具 + 目录加载（从 FileManagerViewModel 迁入） ═══

    /**
     * 判断路径是否位于受 Scoped Storage 保护的目录下。
     */
    internal fun isProtectedPath(path: String): Boolean =
        path.contains("/Android/data") || path.contains("/Android/obb")

    /**
     * 通过 shell 检查路径是否存在（对 Android/data 等受保护路径使用 Shizuku/Root）。
     * 普通路径直接用 Java File API。
     */
    internal fun shellPathExists(path: String): Boolean {
        if (!hasShellEngine()) return File(path).exists()
        val escaped = SevenZipCommand.escape(path)
        return try {
            ShellExecutor.execute(Permission.MAX, "test -e $escaped")
            true
        } catch (_: Exception) { false }
    }

    /**
     * 通过 shell 检查路径是否为目录。
     */
    internal fun shellIsDirectory(path: String): Boolean {
        if (!hasShellEngine()) return File(path).isDirectory
        val escaped = SevenZipCommand.escape(path)
        return try {
            ShellExecutor.execute(Permission.MAX, "test -d $escaped")
            true
        } catch (_: Exception) { false }
    }

    /**
     * 检查文件是否可通过 Java API 读取。
     * Android/data 和 Android/obb 是受限目录，除自己包名外均不可读。
     */
    internal fun shellCanRead(path: String): Boolean {
        if (!hasShellEngine()) return File(path).canRead()
        if (isRestrictedAndroidDir(path)) return false
        return File(path).canRead()
    }

    /** 判断路径是否在受限的 Android/data 或 Android/obb 下（排除自身包名） */
    internal fun isRestrictedAndroidDir(path: String): Boolean {
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
        if (hasShellEngine()) return listDirChildrenViaShell(path)
        return try { File(path).listFiles()?.map { f ->
            FileEntry(f.absolutePath, f.name, f.isDirectory, "", if (f.isDirectory) 0L else f.length(), f.lastModified())
        } } catch (_: Exception) { null }
    }

    /**
     * 根据当前引擎执行 shell 命令（Root 优先，回退 Shizuku）。
     * 委托给 ShellExecutor.execute(Permission.MAX)。
     * 保留 Triple 返回类型供调用方解构使用。
     */
    internal fun executeShell(cmd: String): Triple<String, String, Int> {
        return try {
            val stdout = ShellExecutor.execute(Permission.MAX, cmd, debug = true)
            Triple(stdout, "", 0)
        } catch (e: ShellException) {
            Triple("", "${e.message}\n${e.stderr}", e.exitCode)
        } catch (e: Exception) {
            Triple("", e.message ?: "Shell 执行异常", -1)
        }
    }

    /**
     * 格式化 shell 错误信息，输出中文提示 + 原始英文报错。
     */
    internal fun formatShellError(name: String, stderr: String): String {
        val detail = stderr.trim().ifBlank { "未知错误" }
        return when {
            detail.contains("Permission denied", ignoreCase = true) -> "$name 权限不足: $detail"
            detail.contains("No such file or directory", ignoreCase = true) -> "$name 不存在: $detail"
            else -> "$name 错误: $detail"
        }
    }

    /**
     * ls -1aF 获取目录条目名称（快速，只读目录，不 stat 每个条目）。
     * 返回格式：目录带 "/" 后缀，如 "Documents/"。
     */
    internal fun listDirNamesViaLs(
        dirPath: String,
        showHidden: Boolean
    ): List<String> {
        val normalized = if (dirPath == "/") "/" else dirPath.trimEnd('/').ifEmpty { "/" }
        val escaped = SevenZipCommand.escape(normalized)
        val cmd = "ls -1aF $escaped"
        return try {
            val stdout = ShellExecutor.execute(Permission.MAX, cmd, debug = true)
            stdout.lines().filter { line ->
                val name = line.trimEnd('\r')
                if (name.isBlank()) return@filter false
                val cleanName = if (name.endsWith("/")) name.dropLast(1) else name
                if (cleanName == "." || cleanName == "..") return@filter false
                if (!showHidden && cleanName.startsWith(".")) return@filter false
                true
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 从 ls -1aF 行解析文件名和 isDir。剥离 -F 后缀：/=目录 *=可执行 @=符号链接 |=FIFO ==Socket */
    internal fun parseLsLine(line: String): Pair<String, Boolean>? {
        val trimmed = line.trimEnd('\r')
        if (trimmed.isBlank()) return null
        val isDir = trimmed.endsWith("/")
        val name = if (isDir) trimmed.dropLast(1) else trimmed.trimEnd('*', '@', '|', '=')
        if (name == "." || name == "..") return null
        return name to isDir
    }

    /**
     * 通过 shell 命令列出目录内容（Shizuku / Root / 普通 shell）。
     * 用于访问 Android/data 等受 Scoped Storage 保护的目录。
     * @return 条目列表，失败返回空列表并设置 lastShellStderr
     */
    internal fun listDirEntriesViaShell(path: String, showHidden: Boolean, longFormat: Boolean = false): List<FileEntry> {
        val normalizedPath = if (path == "/") "/" else path.trimEnd('/').ifEmpty { "/" }

        val lsLines = listDirNamesViaLs(normalizedPath, showHidden)
        if (lsLines.isEmpty()) {
            lastShellStderr = ""
            return emptyList()
        }
        lastShellStderr = ""

        val entries = mutableListOf<FileEntry>()
        for (line in lsLines) {
            val (name, isDir) = parseLsLine(line) ?: continue
            val childPath = if (normalizedPath == "/") "/$name" else "$normalizedPath/$name"
            entries.add(FileEntry(childPath, name, isDir))
        }

        // find -printf 批量填充元数据
        val escaped = SevenZipCommand.escape(normalizedPath)
        val hiddenFilter = if (showHidden) "" else " -not -name '.*'"
        val findCmd = "find $escaped -maxdepth 1 -mindepth 1$hiddenFilter -printf '%f|%s|%T@|%m|%u|%g|%M\\n'"
        val findOut = try {
            ShellExecutor.execute(Permission.MAX, findCmd, debug = true)
        } catch (_: Exception) {
            return entries
        }
        val nameToIndex = entries.withIndex().associate { (i, e) -> e.name to i }
        for (line in findOut.lines()) {
            if (line.isBlank()) continue
            val parts = line.split("|")
            if (parts.size < 7) continue
            val name = parts[0]
            val pos = nameToIndex[name] ?: continue
            val size = parts[1].toLongOrNull() ?: 0L
            val mtimeSec = parts[2].toDoubleOrNull() ?: 0.0
            val perms = parts[6]
            val isDir = perms.startsWith("d")
            entries[pos] = entries[pos].copy(
                permission = perms,
                size = if (isDir) 0L else size,
                lastModified = (mtimeSec * 1000).toLong()
            )
        }
        return entries
    }

    /**
     * 异步加载目录内容，替代同步 listDirectory() + loadExtFlagsForDir()。
     * 逐行解析 find 输出，每 BATCH_SIZE 个切 Main 更新 entries，全部完成后最终排序。
     * 路径切换在第一个条目解析成功后才执行，避免空白闪现。
     *
     * @param targetPath 目标目录路径
     * @param panel 目标面板实例
     * @param isRefresh 是否为刷新操作（刷新时先清空再重新加载当前路径）
     * @param onComplete 加载完成后回调（参数为最终路径）
     */
    internal fun loadDirectoryAsync(
        targetPath: String,
        panel: FilePaneController.VmPanelState,
        isRefresh: Boolean = false,
        onComplete: ((String) -> Unit)? = null,
        panelPath: PanelPath = PanelPath.FileSystem(targetPath)
    ) {
        panel.loadMetadataJob?.cancel()
        val myVersion = panel.loadVersion

        // 不在此处切 panel.path，等第一个条目解析成功后才切，避免空白闪现

        val job = scope.launch(Dispatchers.IO) {
            val normalized = if (targetPath == "/") "/" else targetPath.trimEnd('/').ifEmpty { "/" }
            val escaped = SevenZipCommand.escape(normalized)

            // vault 配置文件名，用于过滤
            val vaultConfigNames = if (isVaultMode) setOf(
                "vault_config.json", "vault_config.backup.json",
                "name_mappings.json", "folder_sizes.json"
            ) else emptySet()

            // ── Phase 1: ls -1aF 获取文件名（阻塞，快速） ──
            val lsCmd = "ls -1aF $escaped"
            val lsOutput = try {
                ShellExecutor.execute(Permission.MAX, lsCmd, debug = true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                withContext(Dispatchers.Main) {
                    if (myVersion == panel.loadVersion) {
                        panel.loadError = e
                        panel.isLoading = false
                    }
                }
                return@launch
            }

            // 检查版本号
            if (myVersion != panel.loadVersion) return@launch

            val snapshot = mutableListOf<FileEntry>()
            for (raw in lsOutput.lines()) {
                val line = raw.trimEnd('\r')
                if (line.isBlank()) continue
                val isDir = line.endsWith("/")
                val name = if (isDir) line.dropLast(1) else line.trimEnd('*', '@', '|', '=')
                if (name == "." || name == "..") continue
                if (!showHiddenFiles() && name.startsWith(".")) continue
                if (name in vaultConfigNames) continue
                val childPath = if (normalized == "/") "/$name" else "$normalized/$name"
                var entry = FileEntry(childPath, name, isDir)
                if (isVaultMode) {
                    val session = vaultSession
                    if (session != null && !isDir) {
                        entry = entry.copy(name = decryptVaultFileName(name, session))
                    }
                }
                snapshot.add(entry)
            }

            val sorted = sortEntries(snapshot)

            // 首批渲染：名称列表
            withContext(Dispatchers.Main) {
                if (myVersion != panel.loadVersion) return@withContext
                panel.isLoading = false
                if (!isRefresh) {
                    panel.path = panelPath
                }
                panel.entries = sorted
                onComplete?.invoke(targetPath)
            }

            // 异步加载 ext flags（不阻塞 entries 渲染）
            loadExtFlagsForDir(targetPath, panel = panel)

            // ── Phase 2: find -printf 批量获取元数据（异步，单条命令） ──
            val metadataJob = launch(Dispatchers.IO) {
                val hiddenFilter = if (showHiddenFiles()) "" else " -not -name '.*'"
                val findCmd = "find $escaped -maxdepth 1 -mindepth 1$hiddenFilter -printf '%f|%s|%T@|%m|%u|%g|%M\\n'"
                val findOut = try {
                    ShellExecutor.execute(Permission.MAX, findCmd, debug = true)
                } catch (_: Exception) {
                    return@launch
                }
                if (myVersion != panel.loadVersion) return@launch

                // 按原始磁盘文件名索引（find -printf '%f' 返回加密后的文件名，不是解密后的显示名）
                val nameToIndex = sorted.withIndex().associate { (i, e) -> e.path.substringAfterLast('/') to i }
                val enriched = sorted.toMutableList()

                for (line in findOut.lines()) {
                    if (line.isBlank()) continue
                    val parts = line.split("|")
                    if (parts.size < 7) continue
                    val name = parts[0]
                    if (name == "." || name == "..") continue
                    val pos = nameToIndex[name] ?: continue
                    val old = enriched[pos]
                    val size = parts[1].toLongOrNull() ?: 0L
                    val mtimeSec = parts[2].toDoubleOrNull() ?: 0.0
                    val perms = parts[6]
                    enriched[pos] = old.copy(
                        permission = perms,
                        size = if (old.isDirectory) 0L else size,
                        lastModified = (mtimeSec * 1000).toLong()
                    )
                }

                val finalEntries = sortEntries(enriched)
                withContext(Dispatchers.Main) {
                    if (myVersion != panel.loadVersion) return@withContext
                    panel.entries = finalEntries
                }
            }
            panel.loadMetadataJob = metadataJob
        }
        panel.loadJob = job
    }

    /**
     * 自然排序比较器：逐字符比较，连续数字按数值大小排序。
     * file2 < file10，而不是字典序的 file10 < file2。
     */
    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var numA = 0L
                while (i < a.length && a[i].isDigit()) {
                    numA = numA * 10 + (a[i] - '0')
                    i++
                }
                var numB = 0L
                while (j < b.length && b[j].isDigit()) {
                    numB = numB * 10 + (b[j] - '0')
                    j++
                }
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return a.length.compareTo(b.length)
    }

    /**
     * 对 FileEntry 列表执行排序（directories first + 当前排序字段）。
     * 复用 listDirectory() 的排序逻辑。
     */
    internal fun sortEntries(entries: List<FileEntry>): List<FileEntry> {
        // vault 模式过滤配置文件
        val filtered = if (isVaultMode) {
            entries.filter { entry ->
                val name = entry.name
                name != "vault_config.json" &&
                name != "vault_config.backup.json" &&
                name != "name_mappings.json" &&
                name != "folder_sizes.json"
            }
        } else entries

        // 填充创建时间（异步阶段，不在首次渲染时执行 NIO）
        val withCreationTime = if (sortField() == SortField.CREATED && android.os.Build.VERSION.SDK_INT >= 26) {
            filtered.map { e ->
                if (e.createdAt > 0) return@map e
                val ct = try {
                    java.nio.file.Files.readAttributes(
                        java.io.File(e.path).toPath(),
                        java.nio.file.attribute.BasicFileAttributes::class.java
                    ).creationTime().toMillis()
                } catch (_: Exception) { e.lastModified }
                e.copy(createdAt = ct)
            }
        } else filtered

        return when (sortField()) {
            SortField.NAME -> {
                val nameComparator = Comparator<FileEntry> { a, b -> naturalCompare(a.name.lowercase(), b.name.lowercase()) }
                if (sortOrder() == SortOrder.ASC)
                    withCreationTime.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.then(nameComparator))
                else
                    withCreationTime.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.then(nameComparator.reversed()))
            }
            SortField.SIZE -> {
                fun effectiveSize(entry: FileEntry): Long {
                    if (!entry.isDirectory) return entry.size
                    val cached = folderSizeDb().get(entry.path)
                    return cached?.size ?: -1L
                }
                if (sortOrder() == SortOrder.ASC)
                    withCreationTime.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { effectiveSize(it).let { s -> if (s < 0) Long.MAX_VALUE else s } })
                else
                    withCreationTime.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { effectiveSize(it).let { s -> if (s < 0) Long.MIN_VALUE else s } })
            }
            SortField.MODIFIED -> if (sortOrder() == SortOrder.ASC)
                withCreationTime.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.lastModified })
            else
                withCreationTime.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.lastModified })
            SortField.CREATED -> if (sortOrder() == SortOrder.ASC)
                withCreationTime.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.createdAt })
            else
                withCreationTime.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.createdAt })
        }
    }

    /**
     * Java File API 模式下的同步加载 + 异步赋值。
     * 用于无 shell 引擎时的本地文件系统（通常很快）。
     */
    internal fun loadDirectorySync(
        targetPath: String,
        panel: FilePaneController.VmPanelState,
        isRefresh: Boolean = false,
        onComplete: ((String) -> Unit)? = null,
        panelPath: PanelPath = PanelPath.FileSystem(targetPath)
    ) {
        val myVersion = panel.loadVersion

        // 不在此处切路径，等 entries 就绪后才切

        val job = scope.launch(Dispatchers.IO) {
            val normalized = if (targetPath == "/") "/" else targetPath.trimEnd('/').ifEmpty { "/" }
            val dir = java.io.File(normalized)
            val entries = try {
                dir.listFiles()?.map { f ->
                    FileEntry(f.absolutePath, f.name, f.isDirectory, "", if (f.isDirectory) 0L else f.length(), f.lastModified())
                }?.filter { f -> showHiddenFiles() || !f.name.startsWith(".") }
            } catch (_: Exception) { null }

            if (entries == null) {
                withContext(Dispatchers.Main) {
                    if (myVersion == panel.loadVersion) {
                        panel.loadError = RuntimeException("权限不足或目录不存在: $targetPath")
                        panel.isLoading = false
                    }
                }
                return@launch
            }

            val sorted = sortEntries(entries)
            withContext(Dispatchers.Main) {
                if (myVersion != panel.loadVersion) return@withContext
                panel.isLoading = false
                if (!isRefresh) {
                    panel.path = panelPath
                }
                panel.entries = sorted
                onComplete?.invoke(targetPath)
            }
        }
        panel.loadJob = job
    }

    /**
     * 统一的异步目录加载入口：有 shell 引擎走 streaming，否则走 Java File API。
     */
    internal fun loadDirectory(
        targetPath: String,
        panel: FilePaneController.VmPanelState,
        isRefresh: Boolean = false,
        onComplete: ((String) -> Unit)? = null,
        panelPath: PanelPath = PanelPath.FileSystem(targetPath)
    ) {
        panel.loadJob?.cancel()
        panel.loadVersion++
        panel.entries = emptyList()
        panel.isLoading = true
        panel.resetTransientState()

        if (hasShellEngine()) {
            loadDirectoryAsync(targetPath, panel, isRefresh, onComplete, panelPath)
        } else {
            loadDirectorySync(targetPath, panel, isRefresh, onComplete, panelPath)
        }
    }

    /** 通过 shell 列出目录直接子项（含文件大小），用于受保护目录 */
    internal fun listDirChildrenViaShell(dirPath: String): List<FileEntry>? {
        val normalized = if (dirPath == "/") "/" else dirPath.trimEnd('/').ifEmpty { "/" }
        val lsLines = listDirNamesViaLs(normalized, showHidden = true)
        if (lsLines.isEmpty()) return null

        val entries = mutableListOf<FileEntry>()
        for (line in lsLines) {
            val (name, isDir) = parseLsLine(line) ?: continue
            val childPath = "$normalized/$name"
            entries.add(FileEntry(childPath, name, isDir))
        }

        // find -printf 批量填充元数据
        val escaped = SevenZipCommand.escape(normalized)
        val findCmd = "find $escaped -maxdepth 1 -mindepth 1 -printf '%f|%s|%T@|%m|%u|%g|%M\\n'"
        val findOut = try {
            ShellExecutor.execute(Permission.MAX, findCmd, debug = true)
        } catch (_: Exception) {
            return entries
        }
        val nameToIndex = entries.withIndex().associate { (i, e) -> e.name to i }
        for (line in findOut.lines()) {
            if (line.isBlank()) continue
            val parts = line.split("|")
            if (parts.size < 7) continue
            val name = parts[0]
            val pos = nameToIndex[name] ?: continue
            val size = parts[1].toLongOrNull() ?: 0L
            val mtimeSec = parts[2].toDoubleOrNull() ?: 0.0
            val perms = parts[6]
            val isDir = perms.startsWith("d")
            entries[pos] = entries[pos].copy(
                permission = perms,
                size = if (isDir) 0L else size,
                lastModified = (mtimeSec * 1000).toLong()
            )
        }
        return entries
    }

    internal fun listWithFile(path: String, showHidden: Boolean, effectiveRoot: String): List<FileEntry> {
        DiagnosticLog.log("FileEngine", "listFiles($path) showHidden=$showHidden")
        val normalizedPath = if (path == "/") "/" else path.trimEnd('/').ifEmpty { "/" }
        val dir = File(normalizedPath)

        if (!dir.exists()) {
            DiagnosticLog.log("FileEngine", "目录不存在: $normalizedPath")
            state.loadError = RuntimeException("目录不存在: $normalizedPath")
            return emptyList()
        }
        if (!dir.isDirectory) {
            DiagnosticLog.log("FileEngine", "路径不是目录: $normalizedPath")
            state.loadError = RuntimeException("路径不是目录: $normalizedPath")
            return emptyList()
        }

        val children = try {
            dir.listFiles()
        } catch (e: SecurityException) {
            DiagnosticLog.log("FileEngine", "listFiles SecurityException: ${e.message}")
            state.loadError = e
            return emptyList()
        }
        if (children == null) {
            DiagnosticLog.log("FileEngine", "listFiles 返回 null（权限不足或 I/O 错误）")
            state.loadError = RuntimeException("无法列出目录（权限不足）: $normalizedPath")
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

    internal fun listWithLs(path: String, showHidden: Boolean, useRoot: Boolean, effectiveRoot: String): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        val normalizedPath = if (path == "/") "/" else path.trimEnd('/').ifEmpty { "/" }

        val lsLines = try {
            listDirNamesViaLs(normalizedPath, showHidden)
        } catch (e: Throwable) {
            val isApkAssetsNoise = e is java.io.IOException && (
                e.stackTrace.any { it.className == "android.content.res.ApkAssets" } ||
                        (e.message?.contains("Failed to load asset path") == true) ||
                        (e.message?.contains(".apk from fd") == true)
            )
            if (isApkAssetsNoise) {
                DiagnosticLog.log("LsShell", "忽略 hook 注入的 ApkAssets 噪声: ${e.message}")
                return emptyList()
            }
            DiagnosticLog.log("LsShell", "execute 抛错: ${e.javaClass.simpleName}: ${e.message}")
            state.loadError = if (e is Exception) e else RuntimeException(e)
            return emptyList()
        }

        DiagnosticLog.log("LsShell", "ls 输出 ${lsLines.size} 行")

        if (lsLines.isEmpty()) {
            val file = File(normalizedPath)
            if (!file.exists()) {
                state.loadError = SecurityException("目录不存在: $normalizedPath")
            } else if (!file.isDirectory) {
                state.loadError = SecurityException("不是目录: $normalizedPath")
            }
            return emptyList()
        }

        // Phase 1: 名称 + isDir
        for (line in lsLines) {
            val (name, isDir) = parseLsLine(line) ?: continue
            val childPath = if (normalizedPath == "/") "/$name" else "$normalizedPath/$name"
            entries.add(FileEntry(childPath, name, isDir))
        }

        // Phase 2: find -printf 批量填充元数据
        val escaped = SevenZipCommand.escape(normalizedPath)
        val hiddenFilter = if (showHidden) "" else " -not -name '.*'"
        val findCmd = "find $escaped -maxdepth 1 -mindepth 1$hiddenFilter -printf '%f|%s|%T@|%m|%u|%g|%M\\n'"
        val findOut = try {
            ShellExecutor.execute(Permission.MAX, findCmd, debug = true)
        } catch (_: Exception) {
            ""
        }
        if (findOut.isNotBlank()) {
            val nameToIndex = entries.withIndex().associate { (i, e) -> e.name to i }
            for (line in findOut.lines()) {
                if (line.isBlank()) continue
                val parts = line.split("|")
                if (parts.size < 7) continue
                val name = parts[0]
                val pos = nameToIndex[name] ?: continue
                val size = parts[1].toLongOrNull() ?: 0L
                val mtimeSec = parts[2].toDoubleOrNull() ?: 0.0
                val perms = parts[6]
                val isDir = perms.startsWith("d")
                entries[pos] = entries[pos].copy(
                    permission = perms,
                    size = if (isDir) 0L else size,
                    lastModified = (mtimeSec * 1000).toLong()
                )
            }
        }

        var dirCount = 0
        var fileCount = 0
        for (entry in entries) {
            if (entry.isDirectory) dirCount++ else fileCount++
        }
        DiagnosticLog.log("LsShell", "解析结果: dirs=$dirCount, files=$fileCount, 总 ${entries.size}")

        val sorted = entries.sortedWith(
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
        entries.clear()
        entries.addAll(sorted)
        return entries
    }

    // ── 目录列表 ──
    fun listDirectory(path: String): List<FileEntry> {
        DiagnosticLog.log("FileMgr", ">>> listDirectory START path=$path useRoot=$isRootEngine()")
        state.loadError = null
        val t0 = System.currentTimeMillis()
        val effectiveRoot = if (isRootEngine()) "/" else safeDefault

        var entries = listWithLs(path, showHiddenFiles(), useRoot = isRootEngine(), effectiveRoot = effectiveRoot)

        // vault 模式：过滤配置文件 + 文件名解密
        if (isVaultMode) {
            val session = vaultSession!!
            entries = entries.filter { entry ->
                val name = entry.name
                name != "vault_config.json" &&
                name != "vault_config.backup.json" &&
                name != "name_mappings.json" &&
                name != "folder_sizes.json"
            }.map { entry ->
                if (entry.isDirectory) {
                    entry
                } else {
                    val displayName = decryptVaultFileName(entry.name, session)
                    entry.copy(name = displayName)
                }
            }
        }

        // 填充创建时间（API 26+ 使用 NIO）
        if (sortField() == SortField.CREATED && android.os.Build.VERSION.SDK_INT >= 26) {
            entries = entries.map { e ->
                if (e.createdAt > 0) return@map e
                val ct = try {
                    Files.readAttributes(File(e.path).toPath(), BasicFileAttributes::class.java).creationTime().toMillis()
                } catch (_: Exception) { e.lastModified }
                e.copy(createdAt = ct)
            }
        }

        // 自定义排序
        entries = when (sortField()) {
            SortField.NAME -> if (sortOrder() == SortOrder.ASC)
                entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            else
                entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.name.lowercase() })
            SortField.SIZE -> {
                // 获取条目的有效大小：文件用 entry.size，已统计目录用 folderSizeDb()，未统计目录用 -1
                fun effectiveSize(entry: FileEntry): Long {
                    if (!entry.isDirectory) return entry.size
                    val cached = folderSizeDb().get(entry.path)
                    return cached?.size ?: -1L // -1 表示未统计
                }
                if (sortOrder() == SortOrder.ASC)
                    entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { effectiveSize(it).let { s -> if (s < 0) Long.MAX_VALUE else s } })
                else
                    entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { effectiveSize(it).let { s -> if (s < 0) Long.MIN_VALUE else s } })
            }
            SortField.MODIFIED -> if (sortOrder() == SortOrder.ASC)
                entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.lastModified })
            else
                entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.lastModified })
            SortField.CREATED -> if (sortOrder() == SortOrder.ASC)
                entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.createdAt })
            else
                entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenByDescending { it.createdAt })
        }

        val took = System.currentTimeMillis() - t0
        DiagnosticLog.log("FileMgr", "<<< listDirectory END path=$path entries=${entries.size} took=${took}ms err=${state.loadError?.javaClass?.simpleName}")
        return entries
    }

    internal fun decryptVaultFileName(encryptedName: String, session: VaultSession): String {
        var raw = encryptedName
        if (raw.endsWith(".whm")) {
            raw = raw.substring(0, raw.length - 4)
        }
        if (!session.record.encryptFilename) {
            return raw
        }
        return try {
            FilenameCodec.decrypt(
                encryptedName = "${raw}.whm",
                dek = session.dek,
                aad = if (session.record.customEncryption) FileConstants.aadCustomObf else null,
                lookupMapping = { session.nameMapping.get(it) }
            )
        } catch (e: Exception) {
            raw
        }
    }

    /**
     * 批量读取目录下所有文件的扩展属性（i/a），结果存入 panel.extFlagsMap。
     * 仅在有 shell 引擎时执行，否则清空对应 map。
     */
    fun loadExtFlagsForDir(dirPath: String, panel: FilePaneController.VmPanelState) {
        if (!hasShellEngine()) {
            panel.extFlagsMap = emptyMap()
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
            panel.extFlagsMap = emptyMap()
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
        panel.extFlagsMap = map
    }



    // ═══ Phase 3: 导航 + 文件操作 + 压缩包 + 回收站 + Vault ═══

    /**
     * 统一的导航函数：跳转路径 + 设置滚动位置
     * @param path 目标路径
     * @param scrollToIndex 滚动到第几个卡片（默认 0，即第一行）
     * @param scrollToOffset 滚动偏移量（默认 0）
     */

    /** 核心导航：切换路径 + 刷新列表（异步） */
    fun navigateTo(path: String, onComplete: ((String) -> Unit)? = null, onPathChanged: (() -> Unit)? = null) {
        val panel = state
        if (panel.isInRecycleBin) panel.isInRecycleBin = false
        val panelPath = PanelPath.FileSystem(path, effectiveRoot = if (isRootEngine()) "/" else safeDefault)
        if (panel.path == panelPath) return
        panel.navState = panel.navState.navigate(panelPath)
        loadDirectory(path, panel = panel, onComplete = onComplete, panelPath = panelPath)
        onPathChanged?.invoke()
    }

    fun navigateToWithScroll(path: PanelPath, scrollToIndex: Int = 0, scrollToOffset: Int = 0) {
        navigateTo(path.fileSystemPath)
        state.pendingScrollTo = Triple(path.displayPath, scrollToIndex, scrollToOffset)
    }

    /** 后退一步：更新 nav state index + 异步加载目录，返回目标路径 */
    fun goBack(): PanelPath? {
        val panel = state
        val back = panel.navState.back() ?: return null
        panel.navState = back
        val backPath = back.current
        navigateToPanelPath(backPath, panel)
        return backPath
    }

    /** 前进一步：更新 nav state index + 异步加载目录，返回目标路径 */
    fun goForward(): PanelPath? {
        val panel = state
        val fwd = panel.navState.forward() ?: return null
        panel.navState = fwd
        val fwdPath = fwd.current
        navigateToPanelPath(fwdPath, panel)
        return fwdPath
    }

    /** 根据 PanelPath 类型执行导航 */
    private fun navigateToPanelPath(panelPath: PanelPath, panel: VmPanelState) {
        when (panelPath) {
            is PanelPath.Archive -> {
                panel.path = panelPath
                panel.archiveSession?.let { session ->
                    panel.entries = session.currentEntries
                }
            }
            is PanelPath.FileSystem -> {
                loadDirectory(panelPath.path, panel = panel, panelPath = panelPath)
            }
            is PanelPath.Vault -> {
                loadDirectory(panelPath.path, panel = panel, panelPath = panelPath)
            }
        }
    }

    /** 返回上级目录，返回目标 PanelPath，null 表示已在根目录 */
    fun goUp(): PanelPath? = state.path.goUp()

    /** 当前聚焦面板是否在保险箱根目录 */
    fun isAtVaultRoot(): Boolean {
        val p = state.path
        return p is PanelPath.Vault && p.isAtRoot
    }

    fun navigateToHistoryDir(entry: HistoryEntry) {
        val panel = state
        val panelPath = PanelPath.FileSystem(entry.path, effectiveRoot = if (isRootEngine()) "/" else safeDefault)
        if (hasShellEngine()) {
            loadDirectory(entry.path, panel = panel, panelPath = panelPath)
        } else {
            val testDir = File(entry.path)
            if (testDir.exists() && testDir.canRead()) loadDirectory(entry.path, panel = panel, panelPath = panelPath)
        }
    }

    fun navigateToHistoryFile(entry: HistoryEntry) {
        val file = File(entry.path)
        val parentDir = file.parentFile ?: return
        val panel = state
        val panelPath = PanelPath.FileSystem(parentDir.absolutePath, effectiveRoot = if (isRootEngine()) "/" else safeDefault)
        if (hasShellEngine()) {
            panel.pendingScrollToFile = file.name
            loadDirectory(parentDir.absolutePath, panel = panel, panelPath = panelPath)
        } else if (parentDir.exists() && parentDir.canRead()) {
            panel.pendingScrollToFile = file.name
            loadDirectory(parentDir.absolutePath, panel = panel, panelPath = panelPath)
        }
    }

    fun navigateToBookmark(bm: BookmarkEntry) {
        val panel = state
        val panelPath = PanelPath.FileSystem(bm.path, effectiveRoot = if (isRootEngine()) "/" else safeDefault)
        if (hasShellEngine()) {
            loadDirectory(bm.path, panel = panel, panelPath = panelPath)
        } else {
            val testDir = File(bm.path)
            if (testDir.exists() && testDir.canRead()) loadDirectory(bm.path, panel = panel, panelPath = panelPath)
        }
    }

    /**
     * 进入 WebDAV 浏览模式。
     */
    fun navigateToWebDav(config: WebDavServerConfig) {
        val panel = state
        try {
            val client = WebDavFileClient(config)
            panel.webDavClient = client
            panel.webDavConfig = config
            panel.webDavCurrentPath = config.relativePath.ifEmpty { "/" }
            loadWebDavEntries(panel)
        } catch (e: Exception) {
            panel.loadError = RuntimeException("连接 WebDAV 失败: ${e.message}")
        }
    }

    /**
     * 在 WebDAV 模式下进入子目录。
     */
    fun navigateToWebDavFolder(name: String) {
        val panel = state
        val client = panel.webDavClient ?: return
        val newPath = if (panel.webDavCurrentPath.endsWith("/")) {
            "${panel.webDavCurrentPath}$name"
        } else {
            "${panel.webDavCurrentPath}/$name"
        }
        panel.webDavCurrentPath = newPath
        loadWebDavEntries(panel)
    }

    /**
     * 在 WebDAV 模式下返回上一级。
     */
    fun webDavGoBack(): Boolean {
        val panel = state
        if (panel.webDavCurrentPath == "/" || panel.webDavCurrentPath.isEmpty()) return false
        val parent = panel.webDavCurrentPath.substringBeforeLast("/", "").ifEmpty { "/" }
        panel.webDavCurrentPath = parent
        loadWebDavEntries(panel)
        return true
    }

    /**
     * 加载当前 WebDAV 路径的文件列表到指定面板。
     */
    internal fun loadWebDavEntries(panel: FilePaneController.VmPanelState = state) {
        val client = panel.webDavClient ?: return
        try {
            val files = client.listChildren(panel.webDavCurrentPath)
            if (files != null) {
                panel.entries = files.map { info ->
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
                    when (sortField()) {
                        SortField.NAME -> when (sortOrder()) {
                            SortOrder.ASC -> entries.sortedBy { it.name.lowercase() }
                            SortOrder.DESC -> entries.sortedByDescending { it.name.lowercase() }
                        }
                        SortField.SIZE -> when (sortOrder()) {
                            SortOrder.ASC -> entries.sortedBy { it.size }
                            SortOrder.DESC -> entries.sortedByDescending { it.size }
                        }
                        SortField.MODIFIED -> when (sortOrder()) {
                            SortOrder.ASC -> entries.sortedBy { it.lastModified }
                            SortOrder.DESC -> entries.sortedByDescending { it.lastModified }
                        }
                        SortField.CREATED -> entries
                    }
                }
                panel.loadError = null
            }
        } catch (e: Exception) {
            panel.loadError = RuntimeException("WebDAV 加载失败: ${e.message}")
        }
    }

    /**
     * 重命名文件或文件夹。成功返回 null，失败返回错误信息。
     */
    fun renameEntry(entry: FileEntry, newName: String): String? {
        val source = File(entry.path)
        val parent = source.parentFile ?: return "无法获取父目录"
        val dest = File(parent, newName)

        if (hasShellEngine()) {
            val escapedSrc = SevenZipCommand.escape(entry.path)
            val escapedDst = SevenZipCommand.escape(dest.absolutePath)
            val (_, err, exit) = try {
                executeShell("mv $escapedSrc $escapedDst")
            } catch (e: Exception) { return e.message ?: "重命名失败" }
            if (exit == 0) {
                if (isVaultMode) onVaultContentModified?.invoke(vaultSession!!.record.id)
                return null
            }
            return "重命名失败: $err"
        }

        if (dest.exists()) return "已存在同名文件或文件夹"
        return try {
            if (source.renameTo(dest)) {
                if (isVaultMode) onVaultContentModified?.invoke(vaultSession!!.record.id)
                null
            } else "重命名失败"
        } catch (e: Exception) { e.message ?: "重命名失败" }
    }

    /**
     * 永久删除文件或文件夹。成功返回 null，失败返回错误信息。
     */
    fun deleteEntry(entry: FileEntry): String? {
        if (hasShellEngine()) {
            val escaped = SevenZipCommand.escape(entry.path)
            val flag = if (entry.isDirectory) "-rf" else "-f"
            val (_, err, exit) = try {
                executeShell("rm $flag $escaped")
            } catch (e: Exception) { return e.message ?: "删除失败" }
            if (exit == 0) {
                if (isVaultMode) onVaultContentModified?.invoke(vaultSession!!.record.id)
                return null
            }
            return "删除失败: $err"
        }
        val file = File(entry.path)
        return try {
            if (SpecialPermissionVerifier.safeDelete(file)) {
                if (isVaultMode) onVaultContentModified?.invoke(vaultSession!!.record.id)
                null
            } else "删除失败"
        } catch (e: Exception) { e.message ?: "删除失败" }
    }

    /**
     * 创建文件或文件夹。成功返回 null，失败返回错误信息。
     */
    fun createEntry(parentPath: String, name: String, isFolder: Boolean): String? {
        val target = File(parentPath, name)

        if (hasShellEngine()) {
            val escaped = SevenZipCommand.escape(target.absolutePath)
            val cmd = if (isFolder) "mkdir $escaped" else "touch $escaped"
            val (_, err, exit) = try {
                executeShell(cmd)
            } catch (e: Exception) { return e.message ?: "创建失败" }
            if (exit == 0) {
                if (isVaultMode) onVaultContentModified?.invoke(vaultSession!!.record.id)
                return null
            }
            return "创建失败: $err"
        }

        if (target.exists()) return "已存在同名文件或文件夹"
        return try {
            val success = if (isFolder) target.mkdir() else target.createNewFile()
            if (success) {
                if (isVaultMode) onVaultContentModified?.invoke(vaultSession!!.record.id)
                null
            } else "创建失败"
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
                    val error = if (toRecycleBin) moveToRecycleBin(entry) { _, _, _ -> } else deleteEntry(entry)
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

    suspend fun getPropertyData(entry: FileEntry): FilePropertyData = withContext(Dispatchers.IO) {
        val file = File(entry.path)

        // shell 路径: stat -c 一次获取权限/用户名/组名/UID/GID
        // 非 shell 路径: Os.stat 获取全部
        var permission = ""
        var owner = ""
        var group = ""

        if (hasShellEngine()) {
            val escaped = SevenZipCommand.escape(entry.path)
            // stat -c 一次获取权限、用户名、组名、UID、GID，无需解析 ls 列对齐
            val (statOut, _, statExit) = try {
                executeShell("stat -c '%a|%U|%G|%u|%g' $escaped")
            } catch (_: Exception) { Triple("", "", -1) }
            if (statExit == 0 && statOut.isNotBlank()) {
                val parts = statOut.trim().split("|")
                if (parts.size >= 5) {
                    val modeOct = parts[0]
                    val userName = parts[1]
                    val groupName = parts[2]
                    val uid = parts[3].toIntOrNull()
                    val gid = parts[4].toIntOrNull()
                    permission = "($modeOct)"
                    owner = if (uid != null) "$userName ($uid)" else userName
                    group = if (gid != null) "$groupName ($gid)" else groupName
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
            val cached = folderSizeDb().get(entry.path)
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

        FilePropertyData(
            name = entry.name,
            directory = parentPath,
            type = type,
            sizeBytes = entry.size,
            sizeDisplay = sizeDisplay,
            modifiedTime = modifiedTime,
            permission = permission,
            owner = owner,
            group = group,
            isDirectory = entry.isDirectory
        )
    }

    /** 异步统计目录内文件和文件夹数量 */
    suspend fun countFilesInFolder(path: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val file = File(path)
        var fileCount = 0
        var folderCount = 0
        if (hasShellEngine()) {
            val escaped = SevenZipCommand.escape(path)
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
            fun countRecursive(dir: File) {
                val children = try { dir.listFiles() } catch (_: Exception) { null } ?: return
                for (child in children) {
                    if (child.isDirectory) { folderCount++; countRecursive(child) }
                    else fileCount++
                }
            }
            countRecursive(file)
        }
        Pair(folderCount, fileCount)
    }

    /** 读取扩展属性标志字符串（如 "----i----------" 或 "-a-----------"）。无 shell 引擎时返回空字符串。 */
    fun readExtFlags(path: String): String {
        if (!hasShellEngine()) return ""
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

    /** 应用扩展属性修改。传入目标标志字符集（如 "ia" 表示要设置 immutable + append-only）。成功返回 null。 */
    fun applyExtFlags(path: String, desiredFlags: Set<Char>, originalFlags: String): String? {
        if (!isRootEngine()) return "需要 Root 权限"
        val realPath = toRealPathForAttr(path)
        val escaped = SevenZipCommand.escape(realPath)
        val originalSet = originalFlags.filter { it == 'i' || it == 'a' }.toSet()
        // 需要添加的标志
        val toAdd = desiredFlags - originalSet
        // 需要移除的标志
        val toRemove = originalSet - desiredFlags
        if (toAdd.isNotEmpty()) {
            try {
                ShellExecutor.execute(Permission.ROOT, "chattr +${toAdd.joinToString("")} $escaped")
            } catch (e: Exception) { return "chattr +${toAdd.joinToString("")} 执行异常: ${e.message}\n\n${e.stackTraceToString()}" }
        }
        if (toRemove.isNotEmpty()) {
            try {
                ShellExecutor.execute(Permission.ROOT, "chattr -${toRemove.joinToString("")} $escaped")
            } catch (e: Exception) { return "chattr -${toRemove.joinToString("")} 执行异常: ${e.message}\n\n${e.stackTraceToString()}" }
        }
        return null
    }

    /**
     * 应用权限修改。成功返回 null，失败返回错误信息。
     * 返回 Pair(errorMessage, fuseRealPath)：
     * - (null, null) = 成功
     * - (errorMsg, null) = 失败
     * - (null, realPath) = 检测到 FUSE，需要用户确认后通过 shell 执行
     */
    fun applyPermissions(path: String, mode: Int, uid: Int, gid: Int, originalMode: Int, originalUid: Int, originalGid: Int): Pair<String?, String?> {
        // 先检测 FUSE
        val fuseRealPath = resolveFuseRealPath(path)
        if (fuseRealPath != null) {
            return null to fuseRealPath
        }

        // 非 FUSE：直接 Os.chmod + shell chown
        try {
            Os.chmod(path, mode and 0x1FF)
        } catch (e: ErrnoException) { return "chmod 失败: ${e.message}\n路径: $path\n\n${e.stackTraceToString()}" to null }
        catch (e: Exception) { return "chmod 异常: ${e.message}\n\n${e.stackTraceToString()}" to null }

        val escapedPath = SevenZipCommand.escape(path)
        try {
            ShellExecutor.execute(Permission.ROOT, "chown $uid:$gid $escapedPath")
        } catch (e: Exception) {
            try { Os.chmod(path, originalMode and 0x1FF) } catch (_: Exception) {}
            return "chown 执行异常: ${e.message}\n\n${e.stackTraceToString()}" to null
        }
        try {
            val stat = Os.stat(path)
            if (stat.st_uid != uid || stat.st_gid != gid) {
                return "chown 未生效: 期望 $uid:$gid, 实际 ${stat.st_uid}:${stat.st_gid}\n路径: $path" to null
            }
        } catch (e: Exception) { return "chown 验证失败: ${e.message}\n\n${e.stackTraceToString()}" to null }

        return null to null
    }

    /**
     * 后台验证权限修改是否生效。
     * 文件夹：随机抽取最多 5 个子项验证。文件：直接验证。
     * 返回未生效的路径列表（空 = 全部成功）。
     */
    fun verifyPermissions(path: String, expectedMode: Int, expectedUid: Int, expectedGid: Int): List<String> {
        if (!hasShellEngine()) return emptyList()
        val targets = mutableListOf(path)
        try {
            if (File(path).isDirectory) {
                val escaped = SevenZipCommand.escape(path)
                val (out, _, exit) = try { executeShell("ls -1A $escaped") } catch (_: Exception) { Triple("", "", -1) }
                if (exit == 0 && out.isNotBlank()) {
                    val children = out.lines().filter { it.isNotBlank() }
                    val picked = children.shuffled().take(5)
                    targets.clear()
                    targets.addAll(picked.map { "$path/$it" })
                }
            }
        } catch (_: Exception) {}

        val failed = mutableListOf<String>()
        val expectedOctal = String.format("%03o", expectedMode and 0x1FF)
        for (t in targets) {
            val escaped = SevenZipCommand.escape(t)
            val (out, _, exit) = try { executeShell("stat -c '%a|%u|%g' $escaped") } catch (_: Exception) { Triple("", "", -1) }
            if (exit != 0) { failed.add(t); continue }
            val parts = out.trim().split("|")
            if (parts.size < 3) { failed.add(t); continue }
            val (actualMode, actualUid, actualGid) = parts
            if (actualMode != expectedOctal || actualUid != expectedUid.toString() || actualGid != expectedGid.toString()) {
                failed.add(t)
            }
        }
        return failed
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
        encryptNames: Boolean = false,
        onProgress: (CompressService.ProgressInfo) -> Unit,
        onComplete: (Boolean, String?, String?) -> Unit
    ) {
        compressCancelFlag.set(false)
        compressJob?.cancel()
        compressJob = scope.launch(Dispatchers.IO) {
            val options = CompressService.CompressOptions(
                sourcePaths = entries.map { it.path },
                outputPath = outputPath,
                format = format,
                compressionLevel = level,
                password = password,
                useAes = useAes,
                encryptNames = encryptNames
            )
            CompressService.compress(
                context = context,
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

    /** 取消正在进行的解压任务 */
    fun cancelExtract() {
        extractCancelFlag.set(true)
        extractJob?.cancel()
        extractJob = null
    }

    /** 打开压缩包（首次，无密码）。若需要密码则设置 archivePasswordRequest 触发弹窗 */
    fun openArchive(entry: FileEntry) {
        val panel = state
        scope.launch(Dispatchers.IO) {
            try {
                val permLevel = permissionLevel
                val currentPathVal = panel.path.fileSystemPath
                val currentEntriesVal = panel.entries

                val passwordCheckResult = ArchiveBrowser.checkPasswordRequired(context, entry.path, permLevel)

                if (passwordCheckResult.needsPassword == null) {
                    // exitCode≠0 且未检测到加密标志 → 档案本身有问题
                    withContext(Dispatchers.Main) {
                        panel.archiveOpenError = com.whmdg.mczj.tools.ui.MessageDialogData(
                            title = "无法打开压缩包",
                            errorSummary = passwordCheckResult.errorMessage.ifEmpty { "文件损坏，无法识别为压缩格式" },
                            command = passwordCheckResult.command,
                            output = passwordCheckResult.output
                        )
                    }
                    return@launch
                }

                if (passwordCheckResult.needsPassword == true) {
                    // Encrypted = + → 需要密码
                    withContext(Dispatchers.Main) { panel.archivePasswordRequest = entry }
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
                            panel.archiveOpenError = com.whmdg.mczj.tools.ui.MessageDialogData(
                                title = "打开压缩包失败",
                                errorSummary = error.message ?: "未知错误"
                            )
                        }
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    panel.archiveOpenError = com.whmdg.mczj.tools.ui.MessageDialogData(
                        title = "打开压缩包异常",
                        errorSummary = e.message ?: "未知异常"
                    )
                }
            }
        }
    }

    /** Debug 模式：解析压缩包信息，弹出预览弹窗 */
    fun debugOpenArchive(entry: FileEntry) {
        val panel = state
        scope.launch(Dispatchers.IO) {
            val permLevel = permissionLevel
            val currentPathVal = panel.path.fileSystemPath
            val currentEntriesVal = panel.entries

            val info = ArchiveBrowser.parseArchiveDebug(
                context = context,
                archivePath = entry.path,
                archiveName = entry.name,
                permissionLevel = permLevel,
                originalPath = currentPathVal,
                originalEntries = currentEntriesVal
            )

            withContext(Dispatchers.Main) {
                panel.archiveDebugInfo = info.copy(sourceEntry = entry)
            }
        }
    }

    /** Debug 弹窗确认打开：需要密码时弹密码框，否则直接进入浏览模式 */
    fun confirmOpenArchive() {
        val panel = state
        val info = panel.archiveDebugInfo ?: return
        if (info.passwordRequired) {
            val entry = info.sourceEntry ?: return
            panel.archiveDebugInfo = null
            panel.archivePasswordRequest = entry
            return
        }
        val session = info.session ?: return
        enterArchiveMode(session)
        panel.archiveDebugInfo = null
    }

    /** 进入压缩包浏览模式（状态更新 + 通知回调） */
    fun enterArchiveMode(session: ArchiveBrowser.ArchiveSession) {
        val panel = state
        panel.entries = session.currentEntries
        panel.path = PanelPath.Archive(
            virtualPath = session.currentPath,
            archivePath = session.archivePath,
            originalPath = session.originalPath,
            isAtArchiveRoot = true
        )
        panel.archiveSession = session
        onArchiveSessionEntered?.invoke(session)
    }

    /** 在压缩包内导航到子目录（状态更新，缓存由 Coordinator 处理） */
    fun navigateInArchive(entry: FileEntry) {
        val panel = state
        val session = panel.archiveSession ?: return
        val newSession = ArchiveBrowser.navigateTo(session, entry.name)
        if (newSession == null) {
            panel.loadError = RuntimeException("无法进入压缩包子目录: ${entry.name}")
            return
        }
        panel.path = PanelPath.Archive(
            virtualPath = newSession.currentPath,
            archivePath = newSession.archivePath,
            originalPath = newSession.originalPath,
            isAtArchiveRoot = false
        )
        panel.archiveSession = newSession
        panel.entries = newSession.currentEntries
    }

    /** 压缩包内返回上一级，返回 false 表示已在根目录（状态更新，缓存由 Coordinator 处理） */
    fun archiveGoUp(): Boolean {
        val panel = state
        val session = panel.archiveSession ?: return false
        val newSession = ArchiveBrowser.navigateUp(session)
        if (newSession == null) {
            exitArchive()
            return true
        }
        panel.path = PanelPath.Archive(
            virtualPath = newSession.currentPath,
            archivePath = newSession.archivePath,
            originalPath = newSession.originalPath,
            isAtArchiveRoot = ArchiveBrowser.isAtRoot(newSession)
        )
        panel.archiveSession = newSession
        panel.entries = newSession.currentEntries
        return true
    }

    /** 退出压缩包浏览模式，恢复原始状态（状态更新，缓存由 Coordinator 处理） */
    fun exitArchive() {
        val panel = state
        val session = panel.archiveSession ?: return
        panel.path = PanelPath.FileSystem(session.originalPath, effectiveRoot = if (isRootEngine()) "/" else safeDefault)
        panel.entries = session.originalEntries.ifEmpty { listDirectory(session.originalPath) }
        panel.archiveSession = null
    }

    /** 当前是否在压缩包根目录 */
    fun isAtArchiveRoot(): Boolean {
        val session = state.archiveSession ?: return true
        return ArchiveBrowser.isAtRoot(session)
    }

    // ── 回收站工具 ──

    /** 列出回收站目录内容（排除 .meta.json） */
    fun listRecycleBinDir(dir: java.io.File): List<FileEntry> {
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

    /**
     * 移动文件到回收站。成功返回 null，失败返回错误信息。
     * @param onMetaUpdate 元数据更新回调（targetName, entry.path, entry.isDirectory）
     */
    fun moveToRecycleBin(entry: FileEntry, onMetaUpdate: (String, String, Boolean) -> Unit): String? {
        val binDir = AppDataPaths.recycleBin(context)
        if (!shellPathExists(entry.path)) return "文件不存在"

        var targetName = entry.name
        var target = java.io.File(binDir, targetName)
        if (target.exists()) {
            val ts = System.currentTimeMillis() / 1000
            val dotIdx = entry.name.lastIndexOf('.')
            targetName = if (dotIdx > 0) {
                "${entry.name.substring(0, dotIdx)}_${ts}${entry.name.substring(dotIdx)}"
            } else {
                "${entry.name}_${ts}"
            }
            target = java.io.File(binDir, targetName)
        }

        if (hasShellEngine()) {
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
            val source = java.io.File(entry.path)
            try {
                val moved = source.renameTo(target)
                if (!moved) {
                    if (entry.isDirectory) source.copyRecursively(target, overwrite = false)
                    else source.copyTo(target, overwrite = false)
                    SpecialPermissionVerifier.safeDelete(source)
                }
            } catch (e: Exception) { return e.message ?: "移动失败" }
        }

        onMetaUpdate(targetName, entry.path, entry.isDirectory)
        return null
    }

    /** 在回收站内进入子文件夹 */
    fun navigateInRecycleBin(entry: FileEntry) {
        if (!entry.isDirectory) return
        val dir = java.io.File(entry.path)
        if (!dir.exists() || !dir.canRead()) {
            Toast.makeText(context, "权限不足: ${entry.name}", Toast.LENGTH_SHORT).show()
            return
        }
        val panel = state
        panel.recycleBinPath = entry.path
        panel.entries = listRecycleBinDir(dir)
    }

    /** 在回收站内返回上一级 */
    fun goUpInRecycleBin(): Boolean {
        val panel = state
        val binRoot = AppDataPaths.recycleBin(context).absolutePath
        if (panel.recycleBinPath == binRoot) return false
        val parent = java.io.File(panel.recycleBinPath).parentFile ?: return false
        panel.recycleBinPath = parent.absolutePath
        panel.entries = listRecycleBinDir(parent)
        return true
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

    internal fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.').lowercase()
        return when (ext) {
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "zip" -> "application/zip"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            else -> "*/*"
        }
    }

    /** 规范化路径：去除连续斜杠和尾部斜杠。 */
    internal fun normalizePath(path: String): String {
        var result = path.replace(Regex("/+"), "/")
        if (result.length > 1) result = result.trimEnd('/')
        return result
    }

    /**
     * 检测路径是否在 FUSE 虚拟挂载层上。
     * 解析 /proc/self/mountinfo，找到包含该路径的 FUSE 挂载条目。
     * 对 /storage/emulated（用户内部存储），直接映射到 /data/media/{userId}/。
     * 非 FUSE 路径返回 null。
     */
    fun resolveFuseRealPath(path: String): String? {
        try {
            val mountInfo = File("/proc/self/mountinfo").readText()
            var fuseMountPoint: String? = null
            var fuseSource = ""

            for (line in mountInfo.lines()) {
                val sepIndex = line.indexOf(" - ")
                if (sepIndex < 0) continue
                val leftPart = line.substring(0, sepIndex)
                val rightPart = line.substring(sepIndex + 3)

                val leftFields = leftPart.split(" ")
                if (leftFields.size < 5) continue
                val rightFields = rightPart.split(" ")
                if (rightFields.isEmpty()) continue

                val mountPoint = leftFields[4]
                val fsType = rightFields[0]
                val source = if (rightFields.size >= 2) rightFields[1] else ""

                if (fsType != "fuse" && fsType != "fuseblk") continue
                if (mountPoint == "/") continue

                val normalized = mountPoint.trimEnd('/')
                if (!path.startsWith(normalized)) continue
                if (path.length != normalized.length && path[normalized.length] != '/') continue

                // 最长前缀匹配
                if (fuseMountPoint == null || mountPoint.length > fuseMountPoint.length) {
                    fuseMountPoint = mountPoint
                    fuseSource = source
                }
            }

            if (fuseMountPoint == null) return null

            // 非 /dev/fuse 源（真实块设备），直接用 source 拼接
            if (fuseSource.isNotEmpty() && !fuseSource.startsWith("/dev/")) {
                val remaining = path.removePrefix(fuseMountPoint.trimEnd('/'))
                return normalizePath(fuseSource.trimEnd('/') + remaining)
            }

            // /storage/emulated → /data/media/{userId}
            val emulatedRegex = Regex("^/storage/emulated/(\\d+)(.*)")
            val match = emulatedRegex.find(path)
            if (match != null) {
                val userId = match.groupValues[1]
                val subPath = match.groupValues[2]
                return "/data/media/$userId$subPath"
            }

        } catch (_: Exception) {}
        return null
    }

    /** 将 FUSE 路径转换为底层真实路径，使 chattr/lsattr 能操作 inode 标志。 */
    internal fun toRealPathForAttr(path: String): String {
        // /storage/emulated/0/xxx → /data/media/0/xxx
        val regex = Regex("^/storage/emulated/(\\d+)/")
        val match = regex.find(path)
        return if (match != null) {
            path.replaceFirst("/storage/emulated/${match.groupValues[1]}/", "/data/media/${match.groupValues[1]}/")
        } else {
            path
        }
    }

    /** 读取 /etc/passwd 解析 UID→用户名（无需 root） */
    internal fun resolveUserName(uid: Int): String {
        val name = try {
            File("/etc/passwd").readLines().firstNotNullOfOrNull { line ->
                val parts = line.split(":")
                if (parts.size >= 3 && parts[2].toIntOrNull() == uid) parts[0] else null
            }
        } catch (_: Exception) { null }
        return if (name != null) "$name ($uid)" else uid.toString()
    }

    /** 读取 /etc/group 解析 GID→组名（无需 root） */
    internal fun resolveGroupName(gid: Int): String {
        val name = try {
            File("/etc/group").readLines().firstNotNullOfOrNull { line ->
                val parts = line.split(":")
                if (parts.size >= 3 && parts[2].toIntOrNull() == gid) parts[0] else null
            }
        } catch (_: Exception) { null }
        return if (name != null) "$name ($gid)" else gid.toString()
    }

    /** 通过 UID 解析应用桌面名称（如 "艨艟战舰"） */
    internal fun resolveAppLabel(uid: Int): String {
        if (uid < 10000) return ""
        return try {
            val pm = context.packageManager
            val packages = pm.getPackagesForUid(uid)
            val pkg = packages?.firstOrNull() ?: return ""
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { "" }
    }

    // ── 系统 UID/GID 映射 ──

    data class SystemUser(val uid: Int, val username: String, val appLabel: String = "")
    data class SystemGroup(val gid: Int, val groupname: String, val appLabel: String = "")

    /** android_filesystem_config.h 完整 AID 映射（AOSP 源码） */
    private val SYSTEM_UID_MAP = mapOf(
        0 to "root", 1 to "daemon", 2 to "bin", 3 to "sys",
        1000 to "system", 1001 to "radio", 1002 to "bluetooth", 1003 to "graphics",
        1004 to "input", 1005 to "audio", 1006 to "camera", 1007 to "log",
        1008 to "compass", 1009 to "mount", 1010 to "wifi", 1011 to "adb",
        1012 to "install", 1013 to "media", 1014 to "dhcp", 1015 to "sdcard_rw",
        1016 to "vpn", 1017 to "keystore", 1018 to "usb", 1019 to "drm",
        1020 to "mdnsr", 1021 to "gps", 1023 to "media_rw", 1024 to "mtp",
        1026 to "drmrpc", 1027 to "nfc", 1028 to "sdcard_r", 1029 to "clat",
        1030 to "loop_radio", 1031 to "media_drm", 1032 to "package_info",
        1033 to "sdcard_pics", 1034 to "sdcard_av", 1035 to "sdcard_all",
        1036 to "logd", 1037 to "shared_relro", 1038 to "dbus", 1039 to "tlsdate",
        1040 to "media_ex", 1041 to "audioserver", 1042 to "metrics_coll",
        1043 to "metricsd", 1044 to "webserv", 1045 to "debuggerd",
        1046 to "media_codec", 1047 to "cameraserver", 1048 to "firewall",
        1049 to "trunks", 1050 to "nvram", 1051 to "dns", 1052 to "dns_tether",
        1053 to "webview_zygote", 1054 to "vehicle_network", 1055 to "media_audio",
        1056 to "media_video", 1057 to "media_image", 1058 to "tombstoned",
        1059 to "media_obb", 1060 to "ese", 1061 to "ota_update",
        1062 to "automotive_evs", 1063 to "lowpan", 1064 to "hsm",
        1065 to "reserved_disk", 1066 to "statd", 1067 to "incidentd",
        1068 to "secure_element", 1069 to "lmkd", 1070 to "llkd",
        1071 to "iorapd", 1072 to "gpu_service", 1073 to "network_stack",
        1074 to "gsid", 1075 to "fsverity_cert", 1076 to "credstore",
        1077 to "external_storage", 1078 to "ext_data_rw", 1079 to "ext_obb_rw",
        1080 to "context_hub", 1081 to "virtualizationservice", 1082 to "artd",
        1083 to "uwb", 1084 to "thread_network", 1085 to "diced",
        1086 to "dmesgd", 1087 to "jc_weaver", 1088 to "jc_strongbox",
        1089 to "jc_identitycred", 1090 to "sdk_sandbox",
        1091 to "security_log_writer", 1092 to "prng_seeder",
        1093 to "uprobestats", 1094 to "cros_ec", 1095 to "mmd",
        2000 to "shell", 2001 to "cache", 2002 to "diag",
        3001 to "net_bt_admin", 3002 to "net_bt", 3003 to "inet",
        3004 to "net_raw", 3005 to "net_admin", 3006 to "net_bw_stats",
        3007 to "net_bw_acct", 3009 to "readproc", 3010 to "wakelock",
        3011 to "uhid", 3012 to "readtracefs", 3013 to "virtualmachine",
        9997 to "everybody", 9998 to "misc", 9999 to "nobody"
    )

    /** 读取全部系统用户：系统 UID 映射 + pm list packages -U（应用 UID） */
    fun getSystemUsers(): List<SystemUser> {
        val result = mutableMapOf<Int, String>()

        // 1. 系统 UID（android_filesystem_config.h）
        result.putAll(SYSTEM_UID_MAP)

        // 2. 应用 UID（≥10000）：通过 pm list packages -U 获取包名+UID
        if (isRootEngine()) {
            val stdout = try {
                ShellExecutor.execute(Permission.ROOT, "pm list packages -U", debug = true)
            } catch (_: Exception) { "" }
            if (stdout.isNotBlank()) {
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

        return result.map { (uid, name) ->
            val label = resolveAppLabel(uid)
            SystemUser(uid, name, label)
        }.sortedBy { it.uid }
    }

    /** 读取全部系统用户组：系统 GID 映射 + pm list packages -G（应用 GID） */
    fun getSystemGroups(): List<SystemGroup> {
        val result = mutableMapOf<Int, String>()

        // 系统 GID（与 UID 共享同一套映射）
        SYSTEM_UID_MAP.forEach { (id, name) -> result[id] = name }

        // 应用 GID：pm list packages -G
        if (isRootEngine()) {
            val stdout = try {
                ShellExecutor.execute(Permission.ROOT, "pm list packages -G", debug = true)
            } catch (_: Exception) { "" }
            if (stdout.isNotBlank()) {
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

        return result.map { (gid, name) ->
            val label = resolveAppLabel(gid)
            SystemGroup(gid, name, label)
        }.sortedBy { it.gid }
    }

    internal fun openWithExternalApp(file: File, displayName: String) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(displayName))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            scope.launch(Dispatchers.Main) {
                state.loadError = RuntimeException("无法打开文件: $displayName\n${e.message}")
            }
        }
    }

    fun refreshCurrent() {
        val panel = state
        val idx = panel.currentScrollIndex
        val off = panel.currentScrollOffset
        if (idx != 0 || off != 0) {
            panel.pendingScrollTo = Triple(panel.path.displayPath, idx, off)
        }
        when (val p = panel.path) {
            is PanelPath.Archive -> {
                panel.archiveSession?.let { session ->
                    panel.entries = session.currentEntries
                }
            }
            is PanelPath.FileSystem -> {
                if (panel.isWebDavMode) {
                    loadWebDavEntries(panel)
                } else {
                    loadDirectory(p.path, panel = panel, isRefresh = true, panelPath = p)
                }
            }
            is PanelPath.Vault -> {
                loadDirectory(p.path, panel = panel, isRefresh = true, panelPath = p)
            }
        }
    }



    companion object {
        private const val LOAD_DIRECTORY_BATCH_SIZE = 20
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

/**
 * 面板协调者 — 唯一知道两个面板存在的角色。
 *
 * 持有两个控制器引用 + 跨面板路由。
 * 身份标签（左/右）只存在于这里，不暴露给 Controller。
 */
class PanelCoordinator(
    val left: FilePaneController,
    val right: FilePaneController,
    private val context: Context,
    private val getFocusedPanel: () -> FocusedPanel,
    private val folderSizeDb: () -> FolderSizeDb
) {
    init {
        // 注入回调：Controller 内部进入压缩包模式时，由 Coordinator 保存会话缓存
        left.onArchiveSessionEntered = { session ->
            ArchiveBrowser.saveSessionCache(context, session, PanelId.LEFT.name)
        }
        right.onArchiveSessionEntered = { session ->
            ArchiveBrowser.saveSessionCache(context, session, PanelId.RIGHT.name)
        }
    }

    // ── 焦点管理（通过 lambda 读取 VM 的 Compose 状态） ──
    val focusedPanel: FocusedPanel
        get() = getFocusedPanel()

    val focused: FilePaneController
        get() = if (focusedPanel == FocusedPanel.LEFT) left else right

    val other: FilePaneController
        get() = if (focusedPanel == FocusedPanel.LEFT) right else left

    // ── 按标签寻址 ──
    operator fun get(id: PanelId): FilePaneController =
        if (id == PanelId.LEFT) left else right

    fun sideOf(controller: FilePaneController): PanelId =
        if (controller === left) PanelId.LEFT else PanelId.RIGHT

    fun sideOfState(state: FilePaneController.VmPanelState): PanelId =
        if (state === left.state) PanelId.LEFT else PanelId.RIGHT

    fun both(): List<FilePaneController> = listOf(left, right)

    // ── 跨面板操作 ──

    /** 将聚焦面板的路径同步到非聚焦面板 */
    fun syncPaths() {
        val srcCtrl = focused
        val dstCtrl = other
        val src = srcCtrl.state
        val dst = dstCtrl.state

        // 同步 vault session：源有 session 且目标路径在 vault 内 → 注入；否则清除
        val srcPath = src.path
        if (srcPath is PanelPath.Vault) {
            dstCtrl.vaultSession = srcCtrl.vaultSession
        } else {
            dstCtrl.vaultSession?.dispose()
            dstCtrl.vaultSession = null
        }

        // 同步压缩包状态：源不在压缩包模式时，清除目标的压缩包状态
        if (srcPath !is PanelPath.Archive && dst.path is PanelPath.Archive) {
            dst.path = PanelPath.FileSystem(dst.path.fileSystemPath)
            dst.archiveSession = null
        }

        dst.navState = dst.navState.navigate(srcPath)
        dstCtrl.loadDirectory(srcPath.fileSystemPath, panel = dst, panelPath = srcPath)
    }

    fun refreshBoth() {
        for (ctrl in both()) {
            val panel = ctrl.state
            when (val p = panel.path) {
                is PanelPath.Archive -> {
                    panel.archiveSession?.let { session ->
                        panel.entries = session.currentEntries
                    }
                }
                is PanelPath.FileSystem -> {
                    if (panel.isWebDavMode) {
                        ctrl.loadWebDavEntries(panel)
                    } else {
                        ctrl.loadDirectory(p.path, panel = panel, isRefresh = true, panelPath = p)
                    }
                }
                is PanelPath.Vault -> {
                    ctrl.loadDirectory(p.path, panel = panel, isRefresh = true, panelPath = p)
                }
            }
        }
    }

    // ── 压缩包会话缓存（Coordinator 包裹，附加身份信息） ──

    /** 进入压缩包浏览模式（缓存通过 Controller 回调自动保存） */
    fun enterArchiveMode(session: ArchiveBrowser.ArchiveSession) {
        focused.enterArchiveMode(session)
    }

    /** 在压缩包内导航 + 保存会话缓存 */
    fun navigateInArchive(entry: FileEntry) {
        focused.navigateInArchive(entry)
        focused.state.archiveSession?.let {
            ArchiveBrowser.saveSessionCache(context, it, sideOf(focused).name)
        }
    }

    /** 压缩包内返回上一级 + 保存会话缓存 */
    fun archiveGoUp(): Boolean {
        val result = focused.archiveGoUp()
        if (result) {
            val session = focused.state.archiveSession
            if (session != null) {
                ArchiveBrowser.saveSessionCache(context, session, sideOf(focused).name)
            }
        }
        return result
    }

    /** 退出压缩包浏览模式 + 清除会话缓存 */
    fun exitArchive() {
        focused.exitArchive()
        ArchiveBrowser.clearSessionCache(context)
    }

    /** 压缩包是否在根目录 */
    fun isAtArchiveRoot(): Boolean {
        val session = focused.state.archiveSession ?: return true
        return ArchiveBrowser.isAtRoot(session)
    }

    // ── Vault 会话管理（Coordinator 持有源，注入到各 Controller） ──

    /** 为指定面板初始化 vault 模式 */
    fun initVaultMode(session: VaultSession, panel: PanelId = PanelId.LEFT) {
        val ctrl = this[panel]
        session.loadNameMapping(context)
        ctrl.vaultSession = session
        val vaultPath = session.vaultDir.absolutePath
        ctrl.state.path = PanelPath.Vault(vaultPath, vaultPath)
        ctrl.state.entries = ctrl.listDirectory(vaultPath)
    }

    /** 退出 vault 模式：清除所有 Controller 的会话 */
    fun exitVaultMode() {
        for (ctrl in both()) {
            ctrl.vaultSession?.dispose()
            ctrl.vaultSession = null
        }
    }

    /** 是否有任意面板处于 vault 模式 */
    val isVaultMode: Boolean get() = left.isVaultMode || right.isVaultMode

    /** 获取当前活跃的 vault session（优先左面板） */
    val activeVaultSession: VaultSession?
        get() = left.vaultSession ?: right.vaultSession

    // ── 云盘模式 ──

    /** 云盘面板控制器（仅云盘模式时非 null） */
    var cloud: CloudPaneController? by mutableStateOf(null)
        private set

    /** 是否处于云盘模式 */
    var isCloudMode: Boolean by mutableStateOf(false)
        private set

    /** 云盘模式正在初始化 */
    var isCloudLoading: Boolean by mutableStateOf(false)
        private set

    // 保存左面板挂起前的状态
    private var savedLeftPath: PanelPath? = null
    private var savedLeftNavState: PanelNavState? = null
    private var savedLeftScrollIndex: Int = 0
    private var savedLeftScrollOffset: Int = 0

    // 云端同步 UI 状态
    var cloudSyncDialogVisible by mutableStateOf(false)
        private set
    var cloudSyncPhase by mutableStateOf("")
        private set
    var cloudDiffDialogVisible by mutableStateOf(false)
        private set
    var cloudDiffFiles by mutableStateOf<List<CloudPaneController.ChangedFile>>(emptyList())
        private set
    var cloudDownloadDialogVisible by mutableStateOf(false)
        private set
    var cloudDownloadProgress by mutableStateOf<CloudPaneController.DownloadProgress?>(null)
        private set
    private var cloudDiffContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null

    /** 进入云盘模式：挂起左面板，激活云盘面板 */
    suspend fun enterCloudMode(
        webdavConfig: WebDavServerConfig,
        vaultDir: String,
        vaultId: Int,
        vaultName: String,
        recalculateFolderSize: suspend (String) -> Unit = {}
    ) {
        isCloudLoading = true
        // 保存左面板状态
        savedLeftPath = left.state.path
        savedLeftNavState = left.state.navState
        savedLeftScrollIndex = left.state.currentScrollIndex
        savedLeftScrollOffset = left.state.currentScrollOffset
        // 创建云盘面板
        val controller = CloudPaneController(
            context = context,
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
            webdavConfig = webdavConfig,
            vaultDir = vaultDir,
            vaultId = vaultId,
            vaultName = vaultName,
            folderSizeDb = folderSizeDb,
            recalculateFolderSize = recalculateFolderSize
        )

        // 云端 db 同步检查（必须在 init 之前，否则用户先看到文件列表再看到弹窗）
        cloudSyncDialogVisible = true
        cloudSyncPhase = "正在下载云端数据库..."
        val diffResult = controller.downloadAndCompareCloudDb { phase ->
            cloudSyncPhase = phase
        }
        cloudSyncDialogVisible = false

        if (diffResult.changedFiles.isNotEmpty()) {
            // 发现差异，弹窗让用户选择
            cloudDiffFiles = diffResult.changedFiles
            cloudDiffDialogVisible = true
            val userChoice = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                cloudDiffContinuation = cont
            }
            cloudDiffDialogVisible = false

            if (userChoice) {
                // 下载被更新的文件
                cloudDownloadDialogVisible = true
                controller.downloadChangedFiles(diffResult.changedFiles) { progress ->
                    cloudDownloadProgress = progress
                }
                cloudDownloadDialogVisible = false
                cloudDownloadProgress = null
            } else {
                controller.state.uploadDisabled = true
            }
        }

        // 同步检查完成后再初始化面板（加载文件列表）
        controller.init()
        cloud = controller
        isCloudMode = true
        isCloudLoading = false
    }

    /** 用户在差异弹窗中做出选择 */
    fun onCloudDiffChoice(accept: Boolean) {
        cloudDiffContinuation?.resume(accept) {}
        cloudDiffContinuation = null
    }

    /** 退出云盘模式：释放云盘面板，恢复左面板 */
    fun exitCloudMode() {
        cloud?.dispose()
        cloud = null
        isCloudMode = false
        isCloudLoading = false
        savedLeftPath?.let { panelPath ->
            left.state.path = panelPath
            savedLeftNavState?.let { left.state.navState = it }
            left.loadDirectory(panelPath.fileSystemPath, panel = left.state, panelPath = panelPath)
        }
    }

    /** 云盘面板查询入口（右面板需要时必须通过此方法） */
    fun queryCloudFileStatus(relativePath: String): SyncFileProgress? {
        return cloud?.getSyncState(relativePath)
    }

    // ── 初始化 ──

    /**
     * 初始化双面板：读取偏好、设置初始路径、加载目录。
     * 在 VM init 块中调用。
     */
    fun initialize(
        lHome: String,
        rHome: String,
        listDirectory: (String) -> List<FileEntry>
    ) {
        val lHomePath = PanelPath.FileSystem(lHome)
        val rHomePath = PanelPath.FileSystem(rHome)
        left.state.path = lHomePath
        right.state.path = rHomePath
        left.state.navState = PanelNavState(paths = listOf(lHomePath), index = 0)
        right.state.navState = PanelNavState(paths = listOf(rHomePath), index = 0)

        val cachedArchive = ArchiveBrowser.loadSessionCache(context)
        if (cachedArchive != null) {
            val (cache, sourcePanel) = cachedArchive
            try {
                val session = ArchiveBrowser.restoreSession(cache)
                val targetCtrl = if (sourcePanel == "LEFT") left else right
                val otherCtrl = if (sourcePanel == "LEFT") right else left
                val otherHome = if (sourcePanel == "LEFT") rHomePath else lHomePath
                val archivePath = PanelPath.Archive(
                    virtualPath = session.currentPath,
                    archivePath = session.archivePath,
                    originalPath = session.originalPath,
                    isAtArchiveRoot = ArchiveBrowser.isAtRoot(session)
                )
                targetCtrl.state.archiveSession = session
                targetCtrl.state.path = archivePath
                targetCtrl.state.entries = session.currentEntries
                otherCtrl.state.path = otherHome
                otherCtrl.state.entries = listDirectory(otherHome.fileSystemPath)
                ArchiveBrowser.clearSessionCache(context)
            } catch (e: Exception) {
                DiagnosticLog.log("FileMgr", "恢复压缩包会话失败: ${e.message}")
                ArchiveBrowser.clearSessionCache(context)
                left.state.entries = listDirectory(lHome)
                right.state.entries = listDirectory(rHome)
            }
        } else {
            left.state.entries = listDirectory(lHome)
            right.state.entries = listDirectory(rHome)
        }
    }
}

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

    // ── 面板控制器实例（沙箱，各自独立） ──
    // lazy：等 init 块完成后再构造，确保 permissionLevel 等已赋值
    private val controllerLeft by lazy {
        FilePaneController(
            context = getApplication(),
            scope = viewModelScope,
            hasShellEngine = { hasShellEngine },
            isRootEngine = { isRootEngine },
            permissionLevel = permissionLevel,
            safeDefault = safeDefault,
            showHiddenFiles = { showHiddenFiles },
            sortField = { sortField },
            sortOrder = { sortOrder },
            folderSizeDb = { folderSizeDb }
        )
    }
    private val controllerRight by lazy {
        FilePaneController(
            context = getApplication(),
            scope = viewModelScope,
            hasShellEngine = { hasShellEngine },
            isRootEngine = { isRootEngine },
            permissionLevel = permissionLevel,
            safeDefault = safeDefault,
            showHiddenFiles = { showHiddenFiles },
            sortField = { sortField },
            sortOrder = { sortOrder },
            folderSizeDb = { folderSizeDb }
        )
    }

    // ── 面板协调者（唯一知道两个面板存在的角色） ──
    val panels: PanelCoordinator by lazy {
        PanelCoordinator(controllerLeft, controllerRight, getApplication(), { focusedPanel }, { folderSizeDb })
    }

    // ── 面板状态引用（向后兼容：UI 通过 vm.左/vm.右 访问） ──
    val 左: FilePaneController.VmPanelState get() = controllerLeft.state
    val 右: FilePaneController.VmPanelState get() = controllerRight.state

    /** 当前聚焦面板对应的 Controller 实例 */
    private val focusedController: FilePaneController
        get() = if (focusedPanel == FocusedPanel.LEFT) controllerLeft else controllerRight

    /** 当前聚焦面板的状态实例 */
    val currentPanel: FilePaneController.VmPanelState
        get() = if (focusedPanel == FocusedPanel.LEFT) 左 else 右

    /** 非聚焦面板的状态实例 */
    val otherPanel: FilePaneController.VmPanelState
        get() = if (focusedPanel == FocusedPanel.LEFT) 右 else 左

    /** 判断 panel 是左面板还是右面板 */
    private fun panelSide(panel: FilePaneController.VmPanelState): String =
        if (panel === 左) "LEFT" else "RIGHT"

    // ── 全局状态（非面板专属） ──
    var focusedPanel by mutableStateOf(FocusedPanel.LEFT)
    var showHiddenFiles by mutableStateOf(false)
        private set
    var sortField by mutableStateOf(SortField.NAME)
        private set
    var sortOrder by mutableStateOf(SortOrder.ASC)
        private set
    var fileNameFontSize by mutableStateOf(12f)
        private set
    // 最近一次 listDirEntriesViaShell 的 stderr，用于调用方判断失败原因
    private var lastShellStderr = ""
    var folderSizeDb by mutableStateOf(FolderSizeDb())
        private set
    var refreshVersion by mutableStateOf(0L)
        private set

    // ── 异步目录加载（面板级状态已移入 VmPanelState） ──

    /** 向后兼容：任一面板正在加载时为 true */
    val isLoadingDirectory: Boolean
        get() = 左.isLoading || 右.isLoading

    /** 向后兼容：当前聚焦面板的 loadError */
    var loadError: Throwable?
        get() = currentPanel.loadError
        internal set(value) { currentPanel.loadError = value }

    // ── 压缩包浏览（面板级状态已移入 VmPanelState） ──
    /** 向后兼容：当前聚焦面板的压缩包状态 */
    val isInArchiveMode: Boolean get() = currentPanel.path is PanelPath.Archive
    val archiveSession: ArchiveBrowser.ArchiveSession? get() = currentPanel.archiveSession
    val archivePasswordRequest: FileEntry? get() = currentPanel.archivePasswordRequest
    val archiveDebugInfo: ArchiveBrowser.ArchiveDebugInfo? get() = currentPanel.archiveDebugInfo
    val archiveOpenError: com.whmdg.mczj.tools.ui.MessageDialogData? get() = currentPanel.archiveOpenError
    /** 压缩包密码缓存：archivePath → password（仅内存，进程退出即清除） */
    private val archivePasswordCache = mutableMapOf<String, String>()

    // ── 回收站（面板级路径已移入 VmPanelState） ──
    /** 向后兼容：当前聚焦面板的回收站路径 */
    val recycleBinPath: String get() = currentPanel.recycleBinPath
    /** 向后兼容：哪个面板处于回收站视图 */
    val recycleBinPanel: FocusedPanel?
        get() = when {
            左.isInRecycleBin -> FocusedPanel.LEFT
            右.isInRecycleBin -> FocusedPanel.RIGHT
            else -> null
        }
    var jxlPackZip by mutableStateOf(false)
        private set
    var pendingExternalEntry by mutableStateOf<FileEntry?>(null)
    var pendingApkEntry by mutableStateOf<FileEntry?>(null)
    var sevenZipInfo by mutableStateOf<ArchiveBrowser.SevenZipInfo?>(null)
    var sevenZipAnalyzing by mutableStateOf(false)

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

    // ── WebDAV 浏览状态（面板级状态已移入 VmPanelState） ──
    /** 向后兼容：当前聚焦面板的 WebDAV 状态 */
    val isWebDavMode: Boolean get() = currentPanel.isWebDavMode
    val webDavCurrentPath: String get() = currentPanel.webDavCurrentPath
    val webDavConfig: WebDavServerConfig? get() = currentPanel.webDavConfig
    val webDavClient: WebDavFileClient? get() = currentPanel.webDavClient

    // ── Vault 模式（委托给 Coordinator） ──
    val isVaultMode: Boolean get() = panels.isVaultMode
    val vaultSession: VaultSession? get() = panels.activeVaultSession
    private val vaultRoot: String?
        get() = vaultSession?.vaultDir?.absolutePath

    /** 判断路径是否在 vault 目录内（含根目录本身） */
    private fun isPathInVault(path: String): Boolean {
        val root = vaultRoot ?: return false
        return path == root || path.startsWith("$root/")
    }

    /** 导航后检查指定面板是否离开了 vault，若是则销毁该面板的密钥 */
    private fun checkVaultPanelExit(ctrl: FilePaneController) {
        if (!ctrl.isVaultMode) return
        if (ctrl.state.path !is PanelPath.Vault) {
            ctrl.vaultSession?.dispose()
            ctrl.vaultSession = null
            cleanupVaultTempFiles()
        }
    }

    /** 保险箱内容修改回调（由 FileManagerScreen 设置，同步注入到两个 Controller） */
    var onVaultContentModified: ((vaultId: Int) -> Unit)? = null
        set(value) {
            field = value
            controllerLeft.onVaultContentModified = value
            controllerRight.onVaultContentModified = value
        }

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
        fileNameFontSize = fmPrefs.getFloat("file_name_font_size", 12f)

        // 加载文件夹大小数据库
        folderSizeDb = FolderSizeDb.load(AppDataPaths.fileManager(context))

        // 检查是否有预加载的缓存
        val preloadCache = FileManagerPreloader.consume()

        // 初始化双面板（委托给 Coordinator）
        if (preloadCache != null && preloadCache.leftPath == lHome && preloadCache.rightPath == rHome) {
            val lHomePath = PanelPath.FileSystem(lHome)
            val rHomePath = PanelPath.FileSystem(rHome)
            左.path = lHomePath
            右.path = rHomePath
            左.navState = PanelNavState(paths = listOf(lHomePath), index = 0)
            右.navState = PanelNavState(paths = listOf(rHomePath), index = 0)
            左.entries = preloadCache.leftEntries
            右.entries = preloadCache.rightEntries
        } else {
            panels.initialize(lHome, rHome) { listDirectory(it) }
        }
        loadExtFlagsForDir(左.path.fileSystemPath, panel = 左)
        loadExtFlagsForDir(右.path.fileSystemPath, panel = 右)
    }

    // ═══ Phase 2: 委托方法（转发到 Controller） ═══

    // ═══ Phase 3: 委托方法（转发到 Controller） ═══
    fun navigateToWithScroll(path: PanelPath, scrollToIndex: Int = 0, scrollToOffset: Int = 0) = focusedController.navigateToWithScroll(path, scrollToIndex, scrollToOffset)
    fun goBack(): PanelPath? = focusedController.goBack()
    fun goForward(): PanelPath? = focusedController.goForward()
    fun goUp(): PanelPath? = focusedController.goUp()
    fun isAtVaultRoot(): Boolean = focusedController.isAtVaultRoot()
    /** String 便捷重载，供 Screen 层直接传路径字符串使用 */
    fun navigateToWithScroll(path: String, scrollToIndex: Int = 0, scrollToOffset: Int = 0) =
        focusedController.navigateToWithScroll(PanelPath.FileSystem(path), scrollToIndex, scrollToOffset)
    /** String 便捷重载 */
    fun getScrollPosition(path: String, panel: FilePaneController.VmPanelState = currentPanel): Pair<Int, Int>? =
        focusedController.getScrollPosition(path)
    fun navigateToHistoryDir(entry: HistoryEntry) {
        addHistory(entry.name, entry.path, true)
        focusedController.navigateToHistoryDir(entry)
    }
    fun navigateToHistoryFile(entry: HistoryEntry) {
        addHistory(entry.name, entry.path, false)
        focusedController.navigateToHistoryFile(entry)
    }
    fun navigateToBookmark(bm: BookmarkEntry) = focusedController.navigateToBookmark(bm)
    fun navigateToWebDav(config: WebDavServerConfig) = focusedController.navigateToWebDav(config)
    fun navigateToWebDavFolder(name: String) = focusedController.navigateToWebDavFolder(name)
    fun webDavGoBack(): Boolean = focusedController.webDavGoBack()
    private fun loadWebDavEntries(panel: FilePaneController.VmPanelState) = focusedController.loadWebDavEntries(panel)
    fun renameEntry(entry: FileEntry, newName: String): String? = focusedController.renameEntry(entry, newName)
    fun deleteEntry(entry: FileEntry): String? = focusedController.deleteEntry(entry)
    fun createEntry(parentPath: String, name: String, isFolder: Boolean): String? = focusedController.createEntry(parentPath, name, isFolder)
    fun deleteEntries(entries: List<FileEntry>): String? = focusedController.deleteEntries(entries)
    fun deleteEntriesWithProgress(entries: List<FileEntry>, toRecycleBin: Boolean, onDone: (String?) -> Unit) = focusedController.deleteEntriesWithProgress(entries, toRecycleBin, onDone)
    suspend fun getPropertyData(entry: FileEntry): FilePropertyData = focusedController.getPropertyData(entry)
    suspend fun countFilesInFolder(path: String): Pair<Int, Int> = focusedController.countFilesInFolder(path)
    fun readExtFlags(path: String): String = focusedController.readExtFlags(path)
    fun applyExtFlags(path: String, desiredFlags: Set<Char>, originalFlags: String): String? = focusedController.applyExtFlags(path, desiredFlags, originalFlags)
    fun applyPermissions(path: String, mode: Int, uid: Int, gid: Int, originalMode: Int, originalUid: Int, originalGid: Int): Pair<String?, String?> = focusedController.applyPermissions(path, mode, uid, gid, originalMode, originalUid, originalGid)
    fun verifyPermissions(path: String, expectedMode: Int, expectedUid: Int, expectedGid: Int): List<String> = focusedController.verifyPermissions(path, expectedMode, expectedUid, expectedGid)
    fun compress(entries: List<FileEntry>, outputPath: String, format: String, level: Int, password: String, useAes: Boolean, encryptNames: Boolean, onProgress: (CompressService.ProgressInfo) -> Unit, onComplete: (Boolean, String?, String?) -> Unit) = focusedController.compress(entries, outputPath, format, level, password, useAes, encryptNames, onProgress, onComplete)
    fun cancelCompress() = focusedController.cancelCompress()
    fun cancelExtract() = focusedController.cancelExtract()
    fun openArchive(entry: FileEntry) = focusedController.openArchive(entry)
    fun debugOpenArchive(entry: FileEntry) = focusedController.debugOpenArchive(entry)
    fun confirmOpenArchive() = focusedController.confirmOpenArchive()
    private fun enterArchiveMode(session: ArchiveBrowser.ArchiveSession) = panels.enterArchiveMode(session)
    fun navigateInArchive(entry: FileEntry) = panels.navigateInArchive(entry)
    fun archiveGoUp(): Boolean = panels.archiveGoUp()
    fun exitArchive() = panels.exitArchive()
    fun isAtArchiveRoot(): Boolean = panels.isAtArchiveRoot()
    fun navigateInRecycleBin(entry: FileEntry) = focusedController.navigateInRecycleBin(entry)
    fun goUpInRecycleBin(): Boolean = focusedController.goUpInRecycleBin()
    fun forceOpenExternalFile(context: Context, entry: FileEntry): String? = focusedController.forceOpenExternalFile(context, entry)
    private fun getMimeType(fileName: String): String = focusedController.getMimeType(fileName)
    private fun normalizePath(path: String): String = focusedController.normalizePath(path)
    fun resolveFuseRealPath(path: String): String? = focusedController.resolveFuseRealPath(path)
    private fun toRealPathForAttr(path: String): String = focusedController.toRealPathForAttr(path)
    private fun resolveAppLabel(uid: Int): String = focusedController.resolveAppLabel(uid)
    private fun openWithExternalApp(file: File, displayName: String) = focusedController.openWithExternalApp(file, displayName)
    fun refreshCurrent() = focusedController.refreshCurrent()
    fun syncPaths() = panels.syncPaths()
    fun refreshBoth() = panels.refreshBoth()

    /** 局部更新 FolderSizeDb 中受影响路径的大小，然后刷新两个面板 */
    fun updateFolderSizesAndRefresh(sizes: Map<String, Long>) {
        val now = System.currentTimeMillis()
        for ((path, size) in sizes) {
            folderSizeDb.put(path, com.whmdg.mczj.tools.encryption.data.FolderSizeInfo(size, now))
        }
        refreshBoth()
    }

    // Shell 工具
    private fun executeShell(cmd: String): Triple<String, String, Int> = focusedController.executeShell(cmd)
    private fun shellPathExists(path: String): Boolean = focusedController.shellPathExists(path)
    private fun shellIsDirectory(path: String): Boolean = focusedController.shellIsDirectory(path)
    private fun shellCanRead(path: String): Boolean = focusedController.shellCanRead(path)
    private fun isRestrictedAndroidDir(path: String): Boolean = focusedController.isRestrictedAndroidDir(path)
    private fun isProtectedPath(path: String): Boolean = focusedController.isProtectedPath(path)
    fun canAccessPath(path: String): Boolean = focusedController.canAccessPath(path)
    fun isDirectoryShell(path: String): Boolean = focusedController.isDirectoryShell(path)
    fun listChildrenOrNull(path: String): List<FileEntry>? = focusedController.listChildrenOrNull(path)
    private fun formatShellError(name: String, stderr: String): String = focusedController.formatShellError(name, stderr)
    
    // 目录加载
    private fun loadDirectory(targetPath: String, panel: FilePaneController.VmPanelState = currentPanel, isRefresh: Boolean = false, onComplete: ((String) -> Unit)? = null, panelPath: PanelPath = PanelPath.FileSystem(targetPath)) = focusedController.loadDirectory(targetPath, panel, isRefresh, onComplete, panelPath)
    private fun loadDirectoryAsync(targetPath: String, panel: FilePaneController.VmPanelState, isRefresh: Boolean = false, onComplete: ((String) -> Unit)? = null, panelPath: PanelPath = PanelPath.FileSystem(targetPath)) = focusedController.loadDirectoryAsync(targetPath, panel, isRefresh, onComplete, panelPath)
    private fun loadDirectorySync(targetPath: String, panel: FilePaneController.VmPanelState, isRefresh: Boolean = false, onComplete: ((String) -> Unit)? = null, panelPath: PanelPath = PanelPath.FileSystem(targetPath)) = focusedController.loadDirectorySync(targetPath, panel, isRefresh, onComplete, panelPath)
    fun listDirectory(path: String): List<FileEntry> = focusedController.listDirectory(path)
    private fun sortEntries(entries: List<FileEntry>): List<FileEntry> = focusedController.sortEntries(entries)
    private fun listDirNamesViaLs(dirPath: String, showHidden: Boolean): List<String> = focusedController.listDirNamesViaLs(dirPath, showHidden)
    private fun parseLsLine(line: String): Pair<String, Boolean>? = focusedController.parseLsLine(line)
    private fun listDirEntriesViaShell(path: String, showHidden: Boolean, longFormat: Boolean = false): List<FileEntry> = focusedController.listDirEntriesViaShell(path, showHidden, longFormat)
    private fun listDirChildrenViaShell(dirPath: String): List<FileEntry>? = focusedController.listDirChildrenViaShell(dirPath)
    private fun listWithFile(path: String, showHidden: Boolean, effectiveRoot: String): List<FileEntry> = focusedController.listWithFile(path, showHidden, effectiveRoot)
    private fun listWithLs(path: String, showHidden: Boolean, useRoot: Boolean, effectiveRoot: String): List<FileEntry> = focusedController.listWithLs(path, showHidden, useRoot, effectiveRoot)
    private fun decryptVaultFileName(encryptedName: String, session: VaultSession): String = focusedController.decryptVaultFileName(encryptedName, session)
    fun loadExtFlagsForDir(dirPath: String, panel: FilePaneController.VmPanelState = currentPanel) = focusedController.loadExtFlagsForDir(dirPath, panel)
    // ── 便捷属性（getter，跟随 focusedPanel 自动切换） ──
    val currentPath: String get() {
        val panel = currentPanel
        return if (panel.isWebDavMode) {
            panel.webDavConfig?.let { config ->
                val proto = if (config.protocol == "dav") "dav" else "davs"
                "$proto://${config.host}:${config.port}${panel.webDavCurrentPath}"
            } ?: panel.webDavCurrentPath
        } else {
            panel.path.displayPath
        }
    }
    val currentNavState: PanelNavState get() = currentPanel.navState

    // ── 滚动位置保存（按路径+面板记忆，内存中，应用关闭自动清空） ──
    private val scrollPositions = HashMap<String, Pair<Int, Int>>()

    /** 生成面板感知的 key: "L:fs:/path" 或 "R:archive:/path" */
    private fun scrollKey(panel: FilePaneController.VmPanelState, path: PanelPath): String {
        val side = if (panel === 左) "L" else "R"
        return "$side:${path.scrollKey}"
    }

    /** 保存当前聚焦面板的滚动位置 */
    fun saveScrollPosition(index: Int, offset: Int) {
        val panel = currentPanel
        scrollPositions[scrollKey(panel, panel.path)] = index to offset
    }

    /** 读取指定路径的滚动位置 */
    fun getScrollPosition(path: PanelPath, panel: FilePaneController.VmPanelState = currentPanel): Pair<Int, Int>? =
        scrollPositions[scrollKey(panel, path)]

    /** 清空指定路径的滚动位置（前进导航时使用，确保目标从第一行开始） */
    fun clearScrollPosition(path: PanelPath, panel: FilePaneController.VmPanelState = currentPanel) {
        scrollPositions.remove(scrollKey(panel, path))
    }

    // ── 异步目录加载核心 ──





    // ── 待滚动状态（面板级状态已移入 VmPanelState） ──
    /** 向后兼容：当前聚焦面板的待滚动状态 */
    val pendingScrollTo: Triple<String, Int, Int>? get() = currentPanel.pendingScrollTo


    // ── 核心导航：切换路径 + 刷新列表（异步） ──
    fun navigateTo(path: String, onComplete: ((String) -> Unit)? = null) {
        focusedController.navigateTo(path, onComplete, onPathChanged = { checkVaultPanelExit(focusedController) })
    }

    /** 软链接弹窗状态：null=不显示，FileEntry=被点击的软链接 */
    var pendingSymlinkEntry by mutableStateOf<FileEntry?>(null)

    // ── 导航操作（异步） ──
    fun navigateToFolder(entry: FileEntry, scrollToIndex: Int = 0, scrollToOffset: Int = 0) {
        if (entry.permission.startsWith("l")) {
            pendingSymlinkEntry = entry
            return
        }
        val displayPath = entry.path
        val panel = currentPanel

        if (hasShellEngine) {
            loadDirectory(displayPath, panel = panel, onComplete = { path ->
                addHistory(entry.name, path, true)
                if (scrollToIndex != 0 || scrollToOffset != 0) {
                    panel.pendingScrollTo = Triple(path, scrollToIndex, scrollToOffset)
                }
            })
        } else {
            val testDir = File(displayPath)
            val accessible = try { testDir.listFiles() } catch (_: Exception) { null }
            if (accessible != null) {
                loadDirectory(displayPath, panel = panel, onComplete = { path ->
                    addHistory(entry.name, path, true)
                    if (scrollToIndex != 0 || scrollToOffset != 0) {
                        panel.pendingScrollTo = Triple(path, scrollToIndex, scrollToOffset)
                    }
                })
            } else if (!testDir.exists()) {
                panel.loadError = RuntimeException("文件夹不存在: ${entry.name}\n路径: $displayPath")
            } else {
                panel.loadError = RuntimeException("权限不足: ${entry.name}\n路径: $displayPath")
            }
        }
    }


    /**
     * 从历史记录点击文件：导航到文件所在父目录，并记录待滚动目标文件名。
     */
    /** 向后兼容：当前聚焦面板的待滚动文件名 */
    val pendingScrollToFile: String? get() = currentPanel.pendingScrollToFile



    // ── WebDAV 浏览操作 ──




    /**
     * 退出 WebDAV 模式，恢复本地文件列表。
     */
    fun exitWebDavMode() {
        val panel = currentPanel
        panel.webDavClient = null
        panel.webDavConfig = null
        panel.webDavCurrentPath = "/"
        refreshCurrent()
    }








    // ── Vault 模式（委托给 Coordinator） ──

    fun initVaultMode(session: VaultSession) {
        panels.initVaultMode(session, PanelId.LEFT)
    }

    fun exitVaultMode() {
        val ctrl = focusedController
        ctrl.vaultSession?.dispose()
        ctrl.vaultSession = null
        val panel = ctrl.state
        panel.path = PanelPath.FileSystem(safeDefault, effectiveRoot = if (isRootEngine) "/" else safeDefault)
        panel.entries = listOf()
        cleanupVaultTempFiles()
    }

    private fun cleanupVaultTempFiles() {
        try {
            context.cacheDir.listFiles { _, name ->
                name.startsWith("vault_open_") || name.startsWith("vault_text_") || name.startsWith("vault_img_")
            }?.forEach { it.delete() }
        } catch (_: Exception) {}
    }


    /** vault 模式下打开文件：解密到临时文件后启动 ViewerActivity */
    fun openVaultFile(entry: FileEntry) {
        val session = focusedController.vaultSession ?: return

        if (entry.isDirectory) {
            navigateToFolder(entry)
            return
        }

        // 生成 sessionId 并存入 VaultKeyHolder
        val sessionId = "vault_${System.currentTimeMillis()}_${entry.name.hashCode()}"
        VaultKeyHolder.put(sessionId, VaultViewContext(
            dek = session.dek,
            vaultDir = session.vaultDir.absolutePath,
            originalEncryptedPath = entry.path,
            customEncryption = session.record.customEncryption,
            encryptMetadata = session.record.encryptMetadata,
            vaultId = session.record.id
        ))

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(context.cacheDir, "vault_open_${System.currentTimeMillis()}_${entry.name}")
                FileCodec.decrypt(
                    src = File(entry.path),
                    dst = tempFile,
                    dek = session.dek,
                    customEncryption = session.record.customEncryption
                )

                withContext(Dispatchers.Main) {
                    val ext = entry.name.substringAfterLast('.').lowercase()
                    val textExts = listOf("txt", "kt", "java", "xml", "json", "md", "py", "sh", "log", "csv", "html", "css", "js", "yml", "yaml", "toml", "ini", "cfg", "conf", "bat", "cmd", "c", "cpp", "h", "hpp", "go", "rs", "swift", "rb", "php", "sql", "r", "lua", "pl", "scala", "groovy", "properties")
                    val imageExts = listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "tiff", "tif")

                    when {
                        ext in textExts -> {
                            val textFile = File(context.cacheDir, "vault_text_${entry.name}")
                            tempFile.copyTo(textFile, overwrite = true)
                            context.startActivity(ViewerActivity.createTextIntent(context, textFile.absolutePath, sessionId))
                        }
                        ext in imageExts -> {
                            val imgFile = File(context.cacheDir, "vault_img_${entry.name}")
                            tempFile.copyTo(imgFile, overwrite = true)
                            context.startActivity(ViewerActivity.createImageIntent(context, imgFile.absolutePath, vaultSessionId = sessionId))
                        }
                        else -> {
                            VaultKeyHolder.clear(sessionId)
                            openWithExternalApp(tempFile, entry.name)
                        }
                    }
                }
            } catch (e: Exception) {
                VaultKeyHolder.clear(sessionId)
                withContext(Dispatchers.Main) {
                    loadError = e
                }
            }
        }
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
    /** 静默重新计算指定路径的文件夹大小（用于云盘列表自动修复 >100% 异常） */
    suspend fun recalculateFolderSize(absolutePath: String) {
        val saveDir = AppDataPaths.fileManager(context)
        val permission = detectMaxAvailablePermission()
        val accessor = FileAccessor.create(permission, context)
        calculateFolderSize(
            rootPath = absolutePath,
            accessor = accessor,
            db = folderSizeDb,
            onTotal = {},
            onScanned = { _, _ -> },
            onProgress = { _, _, _ -> },
            isCancelled = { false }
        )
        folderSizeDb.save(saveDir)
    }

    fun calculateFolderSizeAsync(rootPath: String, onTotalSizeReady: ((Long) -> Unit)? = null) {
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
                    cancelFlag = SizeCalcManager.cancelFlag,
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
                        onTotalSizeReady?.invoke(result.rootSize)
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
        return focusedController.moveToRecycleBin(entry) { targetName, originalPath, isDirectory ->
            recycleBinMetaList = recycleBinMetaList + RecycleBinEntry(
                binName = targetName,
                originalPath = originalPath,
                deletedAt = System.currentTimeMillis(),
                isDirectory = isDirectory
            )
            saveRecycleBinMeta()
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
        val panel = currentPanel
        panel.recycleBinPath = binDir.absolutePath
        panel.isInRecycleBin = true
        panel.entries = focusedController.listRecycleBinDir(binDir)
    }



    /** 回收站是否在根目录 */
    val isAtRecycleBinRoot: Boolean get() {
        val panel = currentPanel
        val binRoot = AppDataPaths.recycleBin(context).absolutePath
        return panel.recycleBinPath == binRoot
    }

    /**
     * 退出回收站视图，恢复到正常目录浏览。
     */
    fun exitRecycleBin() {
        val panel = currentPanel
        panel.isInRecycleBin = false
        panel.recycleBinPath = ""
        refreshCurrent()
    }

    // ── 设置 ──
    fun updateShowHiddenFiles(value: Boolean) {
        showHiddenFiles = value
        fmPrefs.edit().putBoolean("show_hidden_files", value).apply()
        panels.refreshBoth()
    }

    fun updateJxlPackZip(value: Boolean) {
        jxlPackZip = value
        fmPrefs.edit().putBoolean("jxl_pack_zip", value).apply()
    }

    fun updateFileNameFontSize(sizeSp: Float) {
        fileNameFontSize = sizeSp
        fmPrefs.edit().putFloat("file_name_font_size", sizeSp).apply()
    }

    fun updateSortField(field: SortField) {
        sortField = field
        fmPrefs.edit().putString("sort_field", field.name).apply()
        val panel = currentPanel
        if (panel.isInRecycleBin) {
            panel.entries = focusedController.listRecycleBinDir(java.io.File(recycleBinPath))
        } else {
            val lp = 左.path; loadDirectory(lp.fileSystemPath, panel = 左, isRefresh = true, panelPath = lp)
            val rp = 右.path; loadDirectory(rp.fileSystemPath, panel = 右, isRefresh = true, panelPath = rp)
        }
    }

    fun updateSortOrder(order: SortOrder) {
        sortOrder = order
        fmPrefs.edit().putString("sort_order", order.name).apply()
        val panel = currentPanel
        if (panel.isInRecycleBin) {
            panel.entries = focusedController.listRecycleBinDir(java.io.File(recycleBinPath))
        } else {
            val lp = 左.path; loadDirectory(lp.fileSystemPath, panel = 左, isRefresh = true, panelPath = lp)
            val rp = 右.path; loadDirectory(rp.fileSystemPath, panel = 右, isRefresh = true, panelPath = rp)
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


    // ── 文件操作 ──
    fun openFile(context: Context, entry: FileEntry, isDebug: Boolean = false) {
        if (entry.permission.startsWith("l")) {
            pendingSymlinkEntry = entry
            return
        }
        // vault 模式：解密后打开（仅当前聚焦面板在保险箱内才走此路径）
        if (focusedController.isVaultMode) {
            openVaultFile(entry)
            return
        }

        DiagnosticLog.log("OpenFile", "请求打开: ${entry.path}")
        if (entry.name.endsWith(".apk", ignoreCase = true)) {
            DiagnosticLog.log("OpenFile", "APK 文件，弹出信息弹窗: ${entry.name}")
            pendingApkEntry = entry
            return
        }
        if (entry.name.endsWith(".apex", ignoreCase = true)) {
            DiagnosticLog.log("OpenFile", "拒绝打开 apex: ${entry.name}")
            Toast.makeText(context, "APEX 文件无法直接打开", Toast.LENGTH_SHORT).show()
            return
        }
        if (ArchiveBrowser.isArchiveFile(entry.name)) {
            // 7z 格式：检测加密状态，无密码则正常浏览，有密码/损坏则弹信息弹窗
            if (entry.name.endsWith(".7z", ignoreCase = true)) {
                val pending = entry
                viewModelScope.launch(Dispatchers.IO) {
                    val info = ArchiveBrowser.analyze7z(getApplication(), pending.path, permissionLevel)
                    withContext(Dispatchers.Main) {
                        if (info.contentEncrypted || info.headerEncrypted || info.isCorrupted) {
                            sevenZipInfo = info
                            sevenZipAnalyzing = false
                        } else {
                            sevenZipAnalyzing = false
                            openArchive(pending)
                        }
                    }
                }
                sevenZipAnalyzing = true
                sevenZipInfo = null
                return
            }
            if (isDebug) {
                DiagnosticLog.log("OpenFile", "压缩包文件（Debug 模式），解析信息: ${entry.name}")
                debugOpenArchive(entry)
            } else {
                DiagnosticLog.log("OpenFile", "压缩包文件，进入浏览模式: ${entry.name}")
                openArchive(entry)
            }
            return
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
            context.startActivity(ViewerActivity.createTextIntent(context, entry.path))
            return
        }
        val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "jxl", "thumb")
        if (ext in imageExtensions) {
            DiagnosticLog.log("OpenFile", "内置查看器打开: ${entry.name}")
            val imagePaths = currentPanel.entries
                .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in imageExtensions }
                .map { it.path }
            val startIndex = imagePaths.indexOf(entry.path).coerceAtLeast(0)
            context.startActivity(ViewerActivity.createImageIntent(context, entry.path, imagePaths, startIndex))
            return
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
                return
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
    }


    // ── 文件夹大小（待重构） ──






    // ── 权限编辑（委托到 Controller） ──

    fun getSystemUsers(): List<FilePaneController.SystemUser> = focusedController.getSystemUsers()
    fun getSystemGroups(): List<FilePaneController.SystemGroup> = focusedController.getSystemGroups()
    fun resolveUserName(uid: Int): String = focusedController.resolveUserName(uid)
    fun resolveGroupName(gid: Int): String = focusedController.resolveGroupName(gid)

    // ── 扩展文件属性（chattr/lsattr） ──





    // ── 文件操作进度（委托到 Controller） ──
    val fileOpProgress: StateFlow<FilePaneController.FileOpProgress?> get() = focusedController.fileOpProgress
    val fileOpCancelFlag: AtomicBoolean get() = focusedController.fileOpCancelFlag
    private fun calculateTotalSize(path: String): Long = focusedController.calculateTotalSize(path)




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
        val ctrl = focusedController
        ctrl.extractCancelFlag.set(false)
        ctrl.extractJob?.cancel()
        ctrl.extractJob = viewModelScope.launch(Dispatchers.IO) {
            val permLevel = legacySp.getString("target_permission_level", "NORMAL") ?: "NORMAL"
            val context = getApplication<Application>()

            for (entry in entries) {
                if (ctrl.extractCancelFlag.get()) break

                // 优先使用调用方传入的密码，其次使用缓存密码
                val effectivePassword = password.ifEmpty { archivePasswordCache[entry.path] ?: "" }

                // 若无密码，先探测是否需要密码
                if (effectivePassword.isEmpty()) {
                    val passwordCheckResult = ArchiveBrowser.checkPasswordRequired(context, entry.path, permLevel)
                    if (passwordCheckResult.needsPassword == true) {
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
                    password = effectivePassword
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
                    password = effectivePassword,
                    fileSizes = fileSizes,
                    totalUncompressedBytes = totalBytes
                )

                CompressService.extract(
                    context = context,
                    options = options,
                    permissionLevel = permLevel,
                    cancelFlag = ctrl.extractCancelFlag,
                    callback = object : CompressService.ProgressCallback {
                        override fun onProgress(info: CompressService.ProgressInfo) {
                            onProgress(info)
                        }
                        override fun onComplete(success: Boolean, path: String?, error: String?) {
                            if (success && effectivePassword.isNotEmpty()) {
                                archivePasswordCache[entry.path] = effectivePassword
                            }
                            launch(Dispatchers.Main) { onComplete(success, path, error) }
                        }
                    }
                )
            }
        }
    }


    /** 解压完成后刷新文件列表：聚焦面板必刷，非聚焦面板仅在压缩包所在目录或解压目录时刷新 */
    fun refreshAfterExtract(outputDir: String) {
        val focused = currentPanel
        val other = otherPanel

        refreshPanel(focused)

        if (other.path.fileSystemPath == focused.path.fileSystemPath || other.path.fileSystemPath == outputDir) {
            refreshPanel(other)
        }
    }

    private fun refreshPanel(panel: FilePaneController.VmPanelState) {
        when (val p = panel.path) {
            is PanelPath.Archive -> {
                panel.archiveSession?.let { session ->
                    panel.entries = session.currentEntries
                }
            }
            is PanelPath.FileSystem -> {
                panel.entries = listDirectory(p.path)
                loadExtFlagsForDir(p.path, panel = panel)
            }
            is PanelPath.Vault -> {
                panel.entries = listDirectory(p.path)
                loadExtFlagsForDir(p.path, panel = panel)
            }
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




    /** 密码弹窗验证回调：带密码重试打开压缩包 */
    /** 带密码重试打开压缩包（挂起函数，供密码弹窗 onVerify 使用）。返回 true=成功 */
    suspend fun openArchiveWithPassword(entry: FileEntry, password: String): Boolean {
        val panel = currentPanel
        return try {
            val permLevel = legacySp.getString("target_permission_level", "NORMAL") ?: "NORMAL"
            val currentPathVal = panel.path.fileSystemPath
            val currentEntriesVal = panel.entries

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
                    archivePasswordCache[entry.path] = password
                    withContext(Dispatchers.Main) {
                        enterArchiveMode(session)
                        panel.archivePasswordRequest = null
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
                panel.loadError = RuntimeException("打开压缩包失败: ${e.message}")
                panel.archivePasswordRequest = null
            }
            false
        }
    }


    /** 从 Screen 层调用进入压缩包浏览模式（密码验证成功后） */
    fun enterArchiveModeFromScreen(session: ArchiveBrowser.ArchiveSession) {
        enterArchiveMode(session)
    }




    /**
     * 从压缩包中提取单个文件并返回对应的 Screen。
     * 供 FileManagerScreen 的 onFileClick 在 isInArchiveMode 时调用。
     * @return 提取成功后对应的 Screen，失败返回 null（由调用方 Toast 提示）
     */
    suspend fun openArchiveFile(context: Context, entry: FileEntry): ArchiveBrowser.ExtractResult {
        val session = archiveSession ?: return ArchiveBrowser.ExtractResult(
            success = false,
            errorMessage = "压缩包会话不存在"
        )
        // 构建压缩包内相对路径
        val subPath = session.currentPath.removePrefix(session.archivePath).removePrefix("/")
        val relativePath = if (subPath.isEmpty()) entry.name else "$subPath/${entry.name}"
        val password = archivePasswordCache[session.archivePath] ?: ""

        // 清理过期缓存（在打开任意压缩包时调用）
        CompressPreviewCache.cleanExpiredCaches(context)

        // 获取压缩包的元数据
        val archiveFile = java.io.File(session.archivePath)
        val lastModified = archiveFile.lastModified()
        val fileSize = archiveFile.length()

        // 检查缓存是否命中
        val cacheResult = CompressPreviewCache.checkCacheHit(
            context = context,
            archivePath = session.archivePath,
            lastModified = lastModified,
            fileSize = fileSize,
            relativePaths = listOf(relativePath)
        )

        if (cacheResult.hit && cacheResult.cacheDir != null && cacheResult.filesToExtract == null) {
            // 缓存完全命中，直接使用缓存
            val cachedFile = java.io.File(cacheResult.cacheDir, relativePath)
            DiagnosticLog.log("OpenFile", "缓存命中，直接使用: ${cachedFile.absolutePath}")
            val tempEntry = entry.copy(path = cachedFile.absolutePath, name = cachedFile.name)
            openFile(context, tempEntry)
            return ArchiveBrowser.ExtractResult(
                success = true,
                file = cachedFile,
                command = "(缓存命中)",
                output = "使用缓存文件"
            )
        }

        // 缓存未完全命中，需要解压文件
        val outputDir = cacheResult.cacheDir ?: CompressPreviewCache.getArchiveCacheDir(context, session.archivePath)
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        DiagnosticLog.log("OpenFile", "压缩包内提取: $relativePath")
        val result = ArchiveBrowser.extractSingleFile(
            context, session.archivePath, relativePath,
            outputDir.absolutePath, password, permissionLevel
        )
        if (!result.success) {
            DiagnosticLog.log("OpenFile", "提取失败: $relativePath, 错误: ${result.errorMessage}")
            return result
        }

        // 更新缓存记录
        CompressPreviewCache.updateRecord(
            context = context,
            archivePath = session.archivePath,
            lastModified = lastModified,
            fileSize = fileSize
        )

        DiagnosticLog.log("OpenFile", "提取成功: ${result.file?.absolutePath}")
        // 用提取后的临时文件构建 FileEntry，复用 openFile 的类型判断
        val extractedFile = result.file!!
        val tempEntry = entry.copy(path = extractedFile.absolutePath, name = extractedFile.name)
        openFile(context, tempEntry)
        return result
    }



    companion object {
        var MAX_HISTORY_SIZE = 100
    }
}
