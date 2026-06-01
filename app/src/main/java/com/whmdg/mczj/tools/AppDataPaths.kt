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
 * {externalFilesDir}/艨艟战舰工具箱数据/
 * ├── 文件管理器/     ← file_manager_prefs / 缓存等
 * ├── 加密模块/       ← vault 相关
 * └── 安全设置/       ← 权限 / root 引擎等
 * ```
 *
 * SharedPreferences 虽由 Android 系统管理（internal shared_prefs/），
 * 但命名上也按此模块分层，一一对应。
 */
object AppDataPaths {

    /** 统一数据根目录（外部应用专属目录下） */
    fun root(context: Context): File {
        val base = context.getExternalFilesDir(null)
            ?: context.filesDir // 降级到内部
        val dir = File(base, "艨艟战舰工具箱数据")
        if (!dir.exists()) dir.mkdirs()
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

    /** RP-Hub 模块目录（内部存储，不可被其他应用访问） */
    fun rpHub(context: Context): File {
        val dir = File(context.filesDir, "RP-Hub")
        if (!dir.exists()) dir.mkdirs()
        migrateRpHubIfNeeded(context, dir)
        return dir
    }

    /** 旧版 RP-Hub 数据位于外部存储，迁移到内部存储后删除旧目录 */
    private fun migrateRpHubIfNeeded(context: Context, newDir: File) {
        val oldDir = File(root(context), "RP-Hub")
        if (!oldDir.exists() || !oldDir.isDirectory) return
        val oldFiles = oldDir.listFiles()
        if (oldFiles.isNullOrEmpty()) {
            oldDir.delete()
            return
        }
        Log.i("AppDataPaths", "迁移 RP-Hub 数据: ${oldDir.absolutePath} → ${newDir.absolutePath}")
        for (file in oldFiles) {
            val target = File(newDir, file.name)
            if (target.exists()) continue
            file.renameTo(target)
        }
        if (oldDir.listFiles().isNullOrEmpty()) oldDir.delete()
        Log.i("AppDataPaths", "RP-Hub 数据迁移完成")
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
}
