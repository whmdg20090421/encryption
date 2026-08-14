package com.whmdg.mczj.tools.fileop.sync

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 云盘同步日志工具。
 *
 * - 开关通过 SharedPreferences 持久化
 * - 日志写入外部存储：<externalFilesDir>/Android_tools/云盘日志/
 * - 每个日志文件最大 100KB，超过则新建带时间戳的文件
 * - 必须完整写入（flush + fsync）
 */
object CloudSyncLogger {

    private const val PREFS_NAME = "cloud_sync_prefs"
    private const val KEY_LOG_ENABLED = "cloud_log_enabled"
    private const val MAX_FILE_SIZE = 100 * 1024L // 100KB

    private var cachedEnabled: Boolean? = null

    /** 外部注入的日志写入器（SyncEngine 等无 context 的组件使用） */
    var externalWriter: ((tag: String, message: String) -> Unit)? = null

    /** 检查日志是否开启 */
    fun isEnabled(context: Context): Boolean {
        return cachedEnabled ?: run {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.getBoolean(KEY_LOG_ENABLED, false).also { cachedEnabled = it }
        }
    }

    /** 设置日志开关 */
    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_LOG_ENABLED, enabled).apply()
    }

    /** 获取日志目录 */
    private fun getLogDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "Android_tools/云盘日志")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 获取当前应写入的日志文件（小于 100KB 的，或新建的） */
    private fun getCurrentLogFile(context: Context): File {
        val dir = getLogDir(context)
        val existing = dir.listFiles()
            ?.filter { it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull()

        if (existing != null && existing.length() < MAX_FILE_SIZE) {
            return existing
        }

        // 新建一个带时间戳的文件
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return File(dir, "sync_${timestamp}.txt")
    }

    /**
     * 写入日志。
     * 完整写入：打开文件 → 写入 → flush → fsync → 关闭。
     * 如果当前文件超过 100KB，自动新建文件。
     */
    fun log(context: Context, tag: String, message: String) {
        if (!isEnabled(context)) return
        try {
            val file = getCurrentLogFile(context)
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
            val line = "[$timestamp][$tag] $message\n"

            file.outputStream().use { fos ->
                fos.write(line.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync() // fsync 确保写入磁盘
            }
        } catch (_: Exception) {
            // 日志写入失败不影响业务逻辑
        }
    }

    /**
     * 写入日志（带堆栈）。
     */
    fun logError(context: Context, tag: String, message: String, error: Throwable) {
        if (!isEnabled(context)) return
        try {
            val file = getCurrentLogFile(context)
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
            val sw = java.io.StringWriter()
            error.printStackTrace(java.io.PrintWriter(sw))
            val entry = buildString {
                appendLine("[$timestamp][$tag] $message")
                appendLine("  异常类型: ${error.javaClass.name}")
                appendLine("  异常信息: ${error.message}")
                appendLine("  堆栈:")
                appendLine(sw.toString())
            }

            file.outputStream().use { fos ->
                fos.write(entry.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
        } catch (_: Exception) {}
    }

    /**
     * 写入同步日志（通过 externalWriter，无需 context）。
     * 供 SyncEngine 等无 context 的组件使用。
     */
    fun logSync(tag: String, message: String) {
        externalWriter?.invoke(tag, message)
    }

    /**
     * 写入同步错误日志（通过 externalWriter，无需 context）。
     */
    fun logSyncError(tag: String, message: String, error: Throwable) {
        val sw = java.io.StringWriter()
        error.printStackTrace(java.io.PrintWriter(sw))
        externalWriter?.invoke(tag, "$message\n  异常: ${error.javaClass.name}: ${error.message}\n  堆栈:\n$sw")
    }

    /** 获取所有日志文件（按时间排序） */
    fun getLogFiles(context: Context): List<File> {
        val dir = getLogDir(context)
        return dir.listFiles()
            ?.filter { it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** 清除所有日志文件 */
    fun clearLogs(context: Context) {
        val dir = getLogDir(context)
        dir.listFiles()?.forEach { it.delete() }
    }
}
