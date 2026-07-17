package com.whmdg.mczj.tools.fileop

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 文件操作诊断日志收集器。
 *
 * 仅当 Debug 模式启用时激活，记录 copy/move 操作的完整执行链路：
 * - PFD 获取阶段（su 进程启动、管道建立耗时）
 * - 数据传输阶段（Os.read/write 调用序号、字节数、耗时）
 * - 阶段汇总（总耗时、吞吐量）
 */
object FileOpDiagnostics {

    private val entries = CopyOnWriteArrayList<DiagEntry>()
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    @Volatile
    private var enabled = false

    /** 启用/禁用诊断采集 */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) entries.clear()
    }

    fun isEnabled(): Boolean = enabled

    /** 清空日志 */
    fun clear() {
        entries.clear()
    }

    /** 记录诊断事件 */
    fun log(phase: String, detail: String, elapsedMs: Long = 0) {
        if (!enabled) return
        entries.add(DiagEntry(
            timestamp = timestampFormat.format(Date()),
            phase = phase,
            detail = detail,
            elapsedMs = elapsedMs
        ))
    }

    /** 记录 PFD 获取阶段 */
    fun logPfdOpen(type: String, path: String, elapsedMs: Long) {
        log("PFD_OPEN", "$type: $path", elapsedMs)
    }

    /** 记录数据传输阶段 */
    fun logTransfer(chunkIndex: Int, bytesRead: Long, totalBytes: Long, elapsedMs: Long) {
        log("TRANSFER", "chunk=#$chunkIndex read=$bytesRead total=$totalBytes", elapsedMs)
    }

    /** 记录阶段完成 */
    fun logPhaseComplete(phase: String, totalBytes: Long, elapsedMs: Long) {
        val throughput = if (elapsedMs > 0) totalBytes * 1000 / elapsedMs else 0
        log("PHASE_DONE", "$phase: totalBytes=$totalBytes elapsed=${elapsedMs}ms throughput=${throughput}B/s")
    }

    /** 导出为文本 */
    fun export(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 文件操作诊断报告 ===")
        sb.appendLine("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine("条目数: ${entries.size}")
        sb.appendLine()
        entries.forEach { entry ->
            sb.appendLine("[${entry.timestamp}] ${entry.phase} (${entry.elapsedMs}ms) ${entry.detail}")
        }
        return sb.toString()
    }

    data class DiagEntry(
        val timestamp: String,
        val phase: String,
        val detail: String,
        val elapsedMs: Long
    )
}
