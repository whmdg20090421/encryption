package com.whmdg.mczj.tools

import android.content.Context
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

    /** 主题模块目录（自定义背景图等） */
    fun theme(context: Context): File {
        val dir = File(root(context), "主题")
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
}
