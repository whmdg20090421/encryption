package com.whmdg.mczj.tools.ui

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
import com.whmdg.mczj.tools.encryption.data.FolderSizeInfo
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.util.DiagnosticLog
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
    var folderSizeDb by mutableStateOf(FolderSizeDb())
        private set
    var refreshVersion by mutableStateOf(0L)
        private set

    // ── 回收站 ──
    var isInRecycleBin by mutableStateOf(false)
        private set
    var recycleBinPath by mutableStateOf("")
        private set
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
            if (!isRootEngine && !dir.canRead()) return safeDefault
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

        // 加载文件夹大小数据库
        folderSizeDb = FolderSizeDb.load(AppDataPaths.fileManager(context))

        // 初始加载
        leftEntries = listDirectory(lHome)
        rightEntries = listDirectory(rHome)
    }

    // ── 便捷属性 ──
    val currentPath: String get() = if (focusedPanel == FocusedPanel.LEFT) leftPath else rightPath
    val currentNavState: PanelNavState get() = if (focusedPanel == FocusedPanel.LEFT) leftNavState else rightNavState

    // ── 滚动位置保存（导航离开前调用） ──
    var leftFirstVisibleIndex by mutableStateOf(0)
        private set
    var leftFirstVisibleOffset by mutableStateOf(0)
        private set
    var rightFirstVisibleIndex by mutableStateOf(0)
        private set
    var rightFirstVisibleOffset by mutableStateOf(0)
        private set

    fun saveScrollPosition(leftIndex: Int, leftOffset: Int, rightIndex: Int, rightOffset: Int) {
        leftFirstVisibleIndex = leftIndex
        leftFirstVisibleOffset = leftOffset
        rightFirstVisibleIndex = rightIndex
        rightFirstVisibleOffset = rightOffset
    }

    // ── 核心导航：切换路径 + 刷新列表 ──
    fun navigateTo(path: String) {
        if (isInRecycleBin) isInRecycleBin = false
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
        val testDir = File(entry.path)
        val accessible = try { testDir.listFiles() } catch (_: Exception) { null }
        if (accessible != null) {
            navigateTo(entry.path)
            historyList = listOf(HistoryEntry(entry.name, entry.path, true)) + historyList
        } else {
            Toast.makeText(context, "权限不足: ${entry.name}", Toast.LENGTH_SHORT).show()
        }
    }

    fun navigateToHistoryDir(entry: HistoryEntry) {
        val testDir = File(entry.path)
        if (testDir.exists() && testDir.canRead()) {
            navigateTo(entry.path)
        }
    }

    fun navigateToBookmark(bm: BookmarkEntry) {
        val testDir = File(bm.path)
        if (testDir.exists() && testDir.canRead()) {
            navigateTo(bm.path)
        }
    }

    fun goBack(): Boolean {
        val nav = if (focusedPanel == FocusedPanel.LEFT) leftNavState else rightNavState
        val back = nav.back() ?: return false
        navigateTo(back.current)
        return true
    }

    fun goForward(): Boolean {
        val nav = if (focusedPanel == FocusedPanel.LEFT) leftNavState else rightNavState
        val fwd = nav.forward() ?: return false
        navigateTo(fwd.current)
        return true
    }

    fun goUp(): Boolean {
        val effectiveRoot = if (isRootEngine) "/" else safeDefault
        val path = if (focusedPanel == FocusedPanel.LEFT) leftPath else rightPath
        if (path == effectiveRoot || !path.contains('/')) return false
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        if (parent == path) return false
        navigateTo(parent)
        return true
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
        val source = File(entry.path)
        if (!source.exists()) return "文件不存在"

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

        try {
            val moved = source.renameTo(target)
            if (!moved) {
                // renameTo 失败，尝试 copy + delete
                if (source.isDirectory) {
                    source.copyRecursively(target, overwrite = false)
                } else {
                    source.copyTo(target, overwrite = false)
                }
                SpecialPermissionVerifier.safeDelete(source)
            }
        } catch (e: Exception) {
            return e.message ?: "移动失败"
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

    fun updateSortField(field: SortField) {
        sortField = field
        fmPrefs.edit().putString("sort_field", field.name).apply()
        leftEntries = listDirectory(leftPath)
        rightEntries = listDirectory(rightPath)
    }

    fun updateSortOrder(order: SortOrder) {
        sortOrder = order
        fmPrefs.edit().putString("sort_order", order.name).apply()
        leftEntries = listDirectory(leftPath)
        rightEntries = listDirectory(rightPath)
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
        val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
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
        return null
    }

    // ── 文件夹大小 ──
    fun refreshFolderSize(dirPath: String): FolderSizeDb {
        val baseDir = File(dirPath)
        val db = FolderSizeDb.load(AppDataPaths.fileManager(context))
        if (!baseDir.exists() || !baseDir.isDirectory) return db

        val subdirs = mutableListOf<String>()
        fun collectSubdirs(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    subdirs.add(child.absolutePath)
                    collectSubdirs(child)
                }
            }
        }
        collectSubdirs(baseDir)
        subdirs.sortByDescending { it.count { c -> c == '/' } }

        for (absPath in subdirs) {
            val dir = File(absPath)
            val currentMtime = dir.lastModified()
            val cached = db.get(absPath)
            if (cached != null && cached.lastModified == currentMtime) continue
            val size = calcDirDirectSize(db, dir)
            db.put(absPath, FolderSizeInfo(size, currentMtime))
        }

        val targetMtime = baseDir.lastModified()
        val targetSize = calcDirDirectSize(db, baseDir)
        db.put(dirPath, FolderSizeInfo(targetSize, targetMtime))
        db.save(AppDataPaths.fileManager(context))
        return db
    }

    fun applyFolderSizeDb(db: FolderSizeDb) {
        folderSizeDb = db
        leftEntries = listDirectory(leftPath)
        rightEntries = listDirectory(rightPath)
    }

    // ── 私有工具 ──

    private fun calcDirDirectSize(db: FolderSizeDb, dir: File): Long {
        val children = dir.listFiles() ?: return 0L
        var total = 0L
        for (child in children) {
            if (child.isFile) total += child.length()
            else if (child.isDirectory) {
                val childInfo = db.get(child.absolutePath)
                if (childInfo != null) total += childInfo.size
            }
        }
        return total
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
        val flags = if (showHidden) "-1Ap" else "-1p"
        val command = "ls $flags '$escapedPath'"
        val tag = if (useRoot) "LsRoot" else "LsShell"
        DiagnosticLog.log(tag, "命令: $command")

        val (stdout, stderr, exitCode) = try {
            if (useRoot) SpecialPermissionVerifier.executeRootCommandFull(command)
            else SpecialPermissionVerifier.executeShellCommandFull(command)
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
        val stat = try { Os.stat(entry.path) } catch (_: Exception) { null }

        val mode = stat?.st_mode ?: 0
        val permission = if (stat != null) {
            "${formatPermission(mode)}(${String.format("%03d", mode and 0x1FF)})"
        } else ""

        val owner = stat?.st_uid?.toString() ?: ""
        val group = stat?.st_gid?.toString() ?: ""

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

    companion object {
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
