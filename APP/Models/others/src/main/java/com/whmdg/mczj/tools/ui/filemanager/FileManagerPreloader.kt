package com.whmdg.mczj.tools.ui.filemanager

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.ShellExecutor
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.ui.FileEntry
import com.whmdg.mczj.tools.util.SevenZipCommand
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 文件管理器预加载器。
 *
 * 在主界面渲染时触发后台预加载，用户点击文件管理器时可直接使用缓存数据。
 * 与主界面渲染同时启动，不拖慢应用启动。
 */
object FileManagerPreloader {

    /** 预加载的左面板数据 */
    @Volatile
    var leftEntries: List<FileEntry>? = null
        private set

    /** 预加载的右面板数据 */
    @Volatile
    var rightEntries: List<FileEntry>? = null
        private set

    /** 预加载的左面板路径 */
    @Volatile
    var leftPath: String? = null
        private set

    /** 预加载的右面板路径 */
    @Volatile
    var rightPath: String? = null
        private set

    private val isPreloading = AtomicBoolean(false)
    private var preloadJob: Job? = null

    /**
     * 触发预加载。在主界面渲染时调用。
     * 如果已经在加载中或已完成，不会重复触发。
     */
    fun preload(context: Context) {
        // 已有缓存，不需要再加载
        if (leftEntries != null && rightEntries != null) return
        // 正在加载中，不重复触发
        if (!isPreloading.compareAndSet(false, true)) return

        preloadJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val fmPrefs = context.getSharedPreferences(AppDataPaths.PREFS_FILE_MANAGER, Context.MODE_PRIVATE)
                val legacySp = context.getSharedPreferences(AppDataPaths.PREFS_LEGACY_SPECIAL_PERMISSIONS, Context.MODE_PRIVATE)
                val safeDefault = "/storage/emulated/0"

                val hasShellEngine = SpecialPermissionVerifier.isRootAvailable() ||
                        SpecialPermissionVerifier.isShizukuAuthorized(context)

                // 读取主目录
                fun resolveHome(saved: String?): String {
                    val dir = File(saved ?: return safeDefault)
                    if (!dir.exists() || !dir.isDirectory) return safeDefault
                    if (!hasShellEngine && !dir.canRead()) return safeDefault
                    return saved
                }

                val lHome = resolveHome(
                    fmPrefs.getString("left_home_directory", null)
                        ?: legacySp.getString("left_home_directory", safeDefault)
                )
                val rHome = resolveHome(
                    fmPrefs.getString("right_home_directory", null)
                        ?: legacySp.getString("right_home_directory", safeDefault)
                )

                val showHidden = fmPrefs.getBoolean("show_hidden_files", false)

                // 预加载两个面板
                val lEntries = loadDirectoryForPreload(context, lHome, showHidden, hasShellEngine)
                val rEntries = loadDirectoryForPreload(context, rHome, showHidden, hasShellEngine)

                // 写入缓存
                leftPath = lHome
                leftEntries = lEntries
                rightPath = rHome
                rightEntries = rEntries

                // 预热 P7zipClient daemon，避免首次点击压缩包时等待启动
                try {
                    com.whmdg.mczj.tools.util.P7zipClient.ensureDaemonOrThrow()
                } catch (e: Exception) {
                    com.whmdg.mczj.tools.util.DiagnosticLog.log("FilePreload", "P7zipClient 预热失败: ${e.message}")
                }
            } catch (e: Exception) {
                // 预加载失败不影响正常使用
                com.whmdg.mczj.tools.util.DiagnosticLog.log("FilePreload", "预加载失败: ${e.message}")
            } finally {
                isPreloading.set(false)
            }
        }
    }

    /**
     * 消费缓存数据。返回后清除缓存，确保只使用一次。
     */
    fun consume(): PreloadCache? {
        val l = leftEntries ?: return null
        val r = rightEntries ?: return null
        val lp = leftPath ?: return null
        val rp = rightPath ?: return null

        // 清除缓存
        leftEntries = null
        rightEntries = null
        leftPath = null
        rightPath = null

        return PreloadCache(lp, l, rp, r)
    }

    /**
     * 检查是否有缓存可用
     */
    fun hasCache(): Boolean {
        return leftEntries != null && rightEntries != null
    }

    /**
     * 取消正在进行的预加载
     */
    fun cancel() {
        preloadJob?.cancel()
        isPreloading.set(false)
    }

    private fun loadDirectoryForPreload(
        context: Context,
        path: String,
        showHidden: Boolean,
        hasShellEngine: Boolean
    ): List<FileEntry> {
        if (hasShellEngine) {
            return loadWithShell(path, showHidden)
        }
        return loadWithFileApi(path, showHidden)
    }

    private fun loadWithShell(path: String, showHidden: Boolean): List<FileEntry> {
        val normalized = if (path == "/") "/" else path.trimEnd('/').ifEmpty { "/" }
        val escaped = SevenZipCommand.escape(normalized)

        // Phase 1: ls -1aF
        val lsCmd = "ls -1aF $escaped"
        val lsOutput = try {
            ShellExecutor.execute(Permission.MAX, lsCmd)
        } catch (_: Exception) {
            return emptyList()
        }

        val entries = mutableListOf<FileEntry>()
        for (raw in lsOutput.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val isDir = line.endsWith("/")
            val name = if (isDir) line.dropLast(1) else line.trimEnd('*', '@', '|', '=')
            if (name == "." || name == "..") continue
            if (!showHidden && name.startsWith(".")) continue
            val childPath = if (normalized == "/") "/$name" else "$normalized/$name"
            entries.add(FileEntry(childPath, name, isDir))
        }

        // Phase 2: find -printf 获取元数据
        val hiddenFilter = if (showHidden) "" else " -not -name '.*'"
        val findCmd = "find $escaped -maxdepth 1 -mindepth 1$hiddenFilter -printf '%f|%s|%T@|%m|%u|%g|%M\\n'"
        val findOut = try {
            ShellExecutor.execute(Permission.MAX, findCmd)
        } catch (_: Exception) {
            return entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
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

        return entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    private fun loadWithFileApi(path: String, showHidden: Boolean): List<FileEntry> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            return emptyList()
        } ?: return emptyList()

        return children
            .filter { showHidden || !it.name.startsWith(".") }
            .map { f ->
                FileEntry(f.absolutePath, f.name, f.isDirectory, "", if (f.isDirectory) 0L else f.length(), f.lastModified())
            }
            .sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }
}

data class PreloadCache(
    val leftPath: String,
    val leftEntries: List<FileEntry>,
    val rightPath: String,
    val rightEntries: List<FileEntry>
)
