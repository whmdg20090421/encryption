package com.whmdg.mczj.tools.util

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shell 命令诊断日志。
 * 写入外部存储，受 debug_mode 开关控制。
 * 用于排查 Shizuku UserService 命令执行问题。
 */
object ShellDebugLog {

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    @Volatile
    private var enabled: Boolean? = null

    /** 检查 debug 模式是否开启（缓存 5 秒） */
    private fun isEnabled(context: Context): Boolean {
        enabled?.let { return it }
        val v = context.getSharedPreferences(AppDataPaths.PREFS_RP_HUB, Context.MODE_PRIVATE)
            .getBoolean("debug_mode", false)
        enabled = v
        // 5 秒后重新读取
        Thread { try { Thread.sleep(5000) } finally { enabled = null } }.start()
        return v
    }

    /** 写入一行日志到外部存储 */
    fun log(context: Context, tag: String, message: String) {
        if (!isEnabled(context)) return
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val logFile = File(dir, "shell_debug.log")
            val ts = timeFmt.format(Date())
            val thread = Thread.currentThread().name
            logFile.appendText("[$ts][$thread][$tag] $message\n")
        } catch (_: Exception) {}
    }

    /** 清空日志文件 */
    fun clear(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, "shell_debug.log").delete()
        } catch (_: Exception) {}
    }

    /** 获取日志文件路径 */
    fun getLogFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, "shell_debug.log")
    }
}
