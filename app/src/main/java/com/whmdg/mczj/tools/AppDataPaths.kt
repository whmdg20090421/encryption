package com.whmdg.mczj.tools

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * 统一数据根目录，所有模块的数据全部收拢在此树下，方便管理与备份。
 *
 * 结构：
 * ```
 * {filesDir}/艨艟战舰工具箱数据/
 * ├── 文件管理器/     ← file_manager_prefs / 缓存等
 * ├── 加密模块/       ← vault 相关
 * ├── 安全设置/       ← 权限 / root 引擎等
 * ├── 批量下载器/
 * └── RP-Hub/
 * ```
 *
 * SharedPreferences 虽由 Android 系统管理（internal shared_prefs/），
 * 但命名上也按此模块分层，一一对应。
 */
object AppDataPaths {

    private const val DIR_NAME = "艨艟战舰工具箱数据"
    private var migrated = false

    /** 统一数据根目录（内部存储，不可被其他应用访问） */
    fun root(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        if (!migrated) {
            migrated = true
            migrateFromExternal(context, dir)
            migrateScatteredFiles(context)
        }
        return dir
    }

    /** 文件管理器模块目录 */
    fun fileManager(context: Context): File {
        val dir = File(root(context), "文件管理器")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 加密模块目录 */
    fun encryption(context: Context): File {
        val dir = File(root(context), "加密模块")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 安全设置目录 */
    fun security(context: Context): File {
        val dir = File(root(context), "安全设置")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 批量下载器模块目录 */
    fun batchDownloader(context: Context): File {
        val dir = File(root(context), "批量下载器")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** RP-Hub 模块目录（WebView 实际数据位于 app_webview-app/） */
    fun rpHub(context: Context): File {
        val dir = File(root(context), "RP-Hub")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * WebView 数据目录（由 setDataDirectorySuffix("app") 决定）。
     * 包含：RP-Hub IndexedDB/localStorage、FA 登录 Cookie 等。
     */
    fun webViewData(context: Context): File {
        return File(context.filesDir.parentFile, "app_webview-app")
    }

    /** 诊断日志目录（crash_reports + debug_logs） */
    fun diagnostics(context: Context): File {
        val dir = File(root(context), "诊断日志")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ── SharedPreferences 名称常量 ──

    /** 文件管理器 SharedPreferences（同一个界面的设置存在同一个 XML） */
    const val PREFS_FILE_MANAGER = "file_manager_prefs"

    /** 安全设置 SharedPreferences */
    const val PREFS_SECURITY = "security_prefs"

    /** 加密设置 SharedPreferences */
    const val PREFS_ENCRYPTION = "encryption_settings"

    /** 批量下载器 SharedPreferences */
    const val PREFS_BATCH_DOWNLOADER = "batch_downloader_prefs"

    /**
     * 将 SAF tree URI 转换为绝对文件路径。
     * 解析 content://com.android.externalstorage.documents/tree/primary:加密/TF图
     * → /storage/emulated/0/加密/TF图
     *
     * @return 绝对路径，解析失败返回 null
     */
    fun safUriToAbsolutePath(context: Context, uri: Uri): String? {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            // docId 格式: "primary:加密/TF图" 或 "XXXX-XXXX:加密/TF图"
            val parts = docId.split(":", limit = 2)
            if (parts.size != 2) return null
            val volume = parts[0]
            val subPath = parts[1]
            val basePath = when (volume) {
                "primary" -> Environment.getExternalStorageDirectory().absolutePath
                else -> "/storage/$volume"
            }
            return "$basePath/$subPath"
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * 首次调用时将旧版外部存储数据迁移到内部存储。
     * 旧路径: {externalFilesDir}/艨艟战舰工具箱数据/
     * 新路径: {filesDir}/艨艟战舰工具箱数据/
     */
    private fun migrateFromExternal(context: Context, newRoot: File) {
        val oldBase = context.getExternalFilesDir(null) ?: return
        val oldRoot = File(oldBase, DIR_NAME)
        if (!oldRoot.exists() || !oldRoot.isDirectory) return

        val oldModules = oldRoot.listFiles() ?: return
        if (oldModules.isEmpty()) {
            oldRoot.delete()
            return
        }

        Log.i("AppDataPaths", "开始迁移数据: ${oldRoot.absolutePath} → ${newRoot.absolutePath}")
        for (oldModule in oldModules) {
            if (!oldModule.isDirectory) continue
            val newModule = File(newRoot, oldModule.name)
            if (!newModule.exists()) newModule.mkdirs()
            migrateDirectory(oldModule, newModule)
            if (oldModule.listFiles().isNullOrEmpty()) oldModule.delete()
        }
        if (oldRoot.listFiles().isNullOrEmpty()) oldRoot.delete()
        Log.i("AppDataPaths", "数据迁移完成")
    }

    private fun migrateDirectory(src: File, dst: File) {
        for (file in src.listFiles() ?: emptyArray()) {
            val target = File(dst, file.name)
            if (file.isDirectory) {
                if (!target.exists()) target.mkdirs()
                migrateDirectory(file, target)
                if (file.listFiles().isNullOrEmpty()) file.delete()
            } else if (!target.exists()) {
                file.renameTo(target)
            }
        }
    }

    /**
     * 将 filesDir 根目录下散落的数据文件迁移到 AppDataPaths 子目录。
     * 一次性执行，迁移完成后记录标记。
     */
    private fun migrateScatteredFiles(context: Context) {
        val prefs = context.getSharedPreferences("app_data_migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean("scattered_migrated", false)) return

        val fd = context.filesDir
        val encDir = File(context.filesDir, "$DIR_NAME/加密模块").apply { mkdirs() }
        val diagDir = File(context.filesDir, "$DIR_NAME/诊断日志").apply { mkdirs() }

        // 加密模块文件
        moveFile(File(fd, "vault_db.json"), File(encDir, "vault_db.json"))
        moveFile(File(fd, "encryption_tasks.json"), File(encDir, "encryption_tasks.json"))
        moveFile(File(fd, "encryption_history.json"), File(encDir, "encryption_history.json"))
        moveDir(File(fd, "vault"), File(encDir, "vault"))
        moveDir(File(fd, ".vault_private_backup"), File(encDir, ".vault_private_backup"))

        // 诊断日志
        moveDir(File(fd, "crash_reports"), File(diagDir, "crash_reports"))
        moveDir(File(fd, "debug_logs"), File(diagDir, "debug_logs"))

        prefs.edit().putBoolean("scattered_migrated", true).apply()
        Log.i("AppDataPaths", "散落文件迁移完成")
    }

    private fun moveFile(src: File, dst: File) {
        if (!src.exists() || dst.exists()) return
        dst.parentFile?.mkdirs()
        src.renameTo(dst)
    }

    private fun moveDir(src: File, dst: File) {
        if (!src.exists() || !src.isDirectory) return
        if (!dst.exists()) dst.mkdirs()
        for (file in src.listFiles() ?: emptyArray()) {
            val target = File(dst, file.name)
            if (file.isDirectory) {
                moveDir(file, target)
                if (file.listFiles().isNullOrEmpty()) file.delete()
            } else if (!target.exists()) {
                file.renameTo(target)
            }
        }
        if (src.listFiles().isNullOrEmpty()) src.delete()
    }
}
