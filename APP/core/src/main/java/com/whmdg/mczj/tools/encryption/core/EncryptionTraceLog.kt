package com.whmdg.mczj.tools.encryption.core

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 加密流程跟踪日志。开关关闭时零开销（仅读一次 SharedPreferences）。
 *
 * 日志写入 AppDataPaths.diagnostics(context)/debug_logs/encryption_trace_<时间戳>.log
 */
object EncryptionTraceLog {

    private const val PREF_KEY = "encryption_trace_log"

    fun enabled(context: Context): Boolean {
        return context.getSharedPreferences(AppDataPaths.PREFS_ENCRYPTION, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(AppDataPaths.PREFS_ENCRYPTION, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY, enabled).apply()
    }

    private var writer: BufferedWriter? = null
    private var startTimeMs: Long = 0L
    private var totalBytes: Long = 0L

    /** 开始一次加密跟踪会话 */
    fun start(context: Context, label: String = "") {
        val dir = File(AppDataPaths.diagnostics(context), "debug_logs")
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "encryption_trace_$ts.log")
        writer = BufferedWriter(FileWriter(file))
        startTimeMs = System.currentTimeMillis()
        totalBytes = 0L
        log("=== 加密流程日志 $ts ${if (label.isNotEmpty()) "[$label]" else ""} ===")
    }

    /** 追加一行日志 */
    fun log(message: String) {
        val w = writer ?: return
        val elapsed = System.currentTimeMillis() - startTimeMs
        val thread = Thread.currentThread().name
        w.write("[$elapsed ms] $thread | $message")
        w.newLine()
    }

    /** 累加已处理字节 */
    fun addBytes(bytes: Long) {
        totalBytes += bytes
    }

    /** 结束本次跟踪，写入汇总 */
    fun finish() {
        val w = writer ?: return
        val elapsed = System.currentTimeMillis() - startTimeMs
        val speed = if (elapsed > 0) totalBytes * 1000.0 / elapsed / 1024 / 1024 else 0.0
        log("=== 完成: total=${totalBytes}B time=${elapsed}ms avg=%.1fMB/s ===".format(speed))
        w.flush()
        w.close()
        writer = null
    }
}
