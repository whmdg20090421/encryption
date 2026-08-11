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
            migrateFolderSizeDb(context)
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

    /** 云盘同步模块目录 */
    fun cloudSync(context: Context): File {
        val dir = File(root(context), "云盘")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 日记模块目录 */
    fun diary(context: Context): File {
        val dir = File(root(context), "日记")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 记账本模块目录 */
    fun accounting(context: Context): File {
        val dir = File(root(context), "记账本")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 记账本附件目录 */
    fun accountingAttachments(context: Context): File {
        val dir = File(accounting(context), "附件")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 二进制工具目录（7za 等） */
    fun binaries(context: Context): File {
        val dir = File(root(context), "bin")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 回收站目录（外部 media 目录，卸载后保留） */
    fun recycleBin(context: Context): File {
        val mediaDir = context.getExternalMediaDirs().firstOrNull()
            ?: File(context.getExternalFilesDir(null), "recycle_bin")
        val dir = File(mediaDir, "recycle_bin")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 回收站元数据文件 */
    fun recycleBinMeta(context: Context): File {
        return File(recycleBin(context), ".meta.json")
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

    /** 文件管理器快捷访问 SharedPreferences */
    const val PREFS_QUICK_ACCESS = "quick_access_prefs"

    /** 安全设置 SharedPreferences（已废弃，统一使用 PREFS_LEGACY_SPECIAL_PERMISSIONS） */
    const val PREFS_SECURITY = "special_permissions"

    /** 加密设置 SharedPreferences */
    const val PREFS_ENCRYPTION = "encryption_settings"

    /** 批量下载器 SharedPreferences */
    const val PREFS_BATCH_DOWNLOADER = "batch_downloader_prefs"

    /** RP-Hub SharedPreferences */
    const val PREFS_RP_HUB = "rp_hub_prefs"

    /** 主题设置 SharedPreferences */
    const val PREFS_THEME = "theme_prefs"

    /** TEE 生物识别 SharedPreferences */
    const val PREFS_TEE = "tee_passwords"

    /** 保险箱锁定 SharedPreferences */
    const val PREFS_VAULT_LOCK = "vault_lock_prefs"

    /** 权限管理模式 SharedPreferences */
    const val PREFS_PERMISSION_MANAGEMENT = "permission_management_mode"

    /** 特殊权限 SharedPreferences（多模块共享的唯一权限配置源） */
    const val PREFS_LEGACY_SPECIAL_PERMISSIONS = "special_permissions"

    /** FA 下载缓存 SharedPreferences */
    const val PREFS_FA_CACHE = "fa_download_cache"

    /** FA 授权历史 SharedPreferences */
    const val PREFS_FA_HISTORY = "fa_author_history"

    /** WiFi 密码 SharedPreferences */
    const val PREFS_WIFI_PASSWORDS = "wifi_passwords"

    /** WiFi 免责声明 SharedPreferences */
    const val PREFS_WIFI_DISCLAIMER = "wifi_disclaimer"

    /** 认证 Token SharedPreferences */
    const val PREFS_AUTH_TOKEN = "auth_token_store_v1"

    /** WebDAV 服务器配置 SharedPreferences */
    const val PREFS_WEBDAV_SERVERS = "webdav_servers"

    /** 记账本 SharedPreferences */
    const val PREFS_ACCOUNTING = "accounting_prefs"

    /** Hook 模块 SharedPreferences */
    const val PREFS_HOOK = "hook_prefs"

    /** 云盘同步 SharedPreferences */
    const val PREFS_CLOUD_SYNC = "cloud_sync_prefs"

    /** 分类图标主题色 SharedPreferences Key（十六进制颜色值，如 "#00BCD4"） */
    const val PREF_KEY_ICON_COLOR = "category_icon_color"

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

    /**
     * 迁移 folder_sizes.json（文件管理器 FolderSizeDb）。
     * 独立于 scattered_migrated，因为该文件在后期才加入 AppDataPaths 体系。
     */
    private fun migrateFolderSizeDb(context: Context) {
        val prefs = context.getSharedPreferences("app_data_migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean("folder_size_migrated", false)) return
        val src = File(context.filesDir, "folder_sizes.json")
        if (src.exists()) {
            val dst = File(fileManager(context), "folder_sizes.json")
            if (!dst.exists()) src.renameTo(dst)
        }
        prefs.edit().putBoolean("folder_size_migrated", true).apply()
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
