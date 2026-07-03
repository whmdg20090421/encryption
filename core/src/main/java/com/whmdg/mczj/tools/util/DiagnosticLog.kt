package com.whmdg.mczj.tools.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.whmdg.mczj.tools.AppDataPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 按"会话"追踪诊断日志。
 *
 * 设计思路：一个用户意图 = 一个 Session（如"进入文件管理器""点击文件夹 Foo"），
 * 每开新会话即清空旧事件。导出报告时只包含当前会话的事件，不会污染上下文。
 *
 * - 没有崩溃时不会留下任何文件
 * - 崩溃时只写当前 session 的事件 + 异常 + 设备信息 + 全线程栈
 */
object DiagnosticLog {

    data class Entry(val timeMs: Long, val thread: String, val tag: String, val message: String)
    private data class Session(
        val name: String,
        val startMs: Long,
        val events: ConcurrentLinkedDeque<Entry> = ConcurrentLinkedDeque()
    )

    @Volatile
    private var session: Session? = null

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    private val fileTimeFmt = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT)
    private val fullTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    /** 开启新会话。旧会话事件被清掉（如果旧会话没出错就直接丢弃）。 */
    fun beginSession(name: String) {
        session = Session(name, System.currentTimeMillis())
        Log.d("DiagSession", "▶ 开始: $name")
    }

    /** 给当前会话追加一条事件。无 session 时只走 logcat。 */
    fun log(tag: String, message: String) {
        val s = session
        if (s != null) {
            s.events.addLast(
                Entry(
                    timeMs = System.currentTimeMillis(),
                    thread = Thread.currentThread().name,
                    tag = tag,
                    message = message
                )
            )
        }
        Log.d(tag, message)
    }

    /**
     * 把 [error] + 当前 session 写到磁盘。同时尝试外部和内部目录，任意一个成功就返回。
     * 优先返回外部路径（用户可见），失败时退回内部路径。
     */
    fun exportCrashReport(
        context: Context,
        error: Throwable?,
        extraContext: String? = null
    ): File? {
        val report = try {
            buildReport(error, extraContext)
        } catch (e: Throwable) {
            Log.e("DiagnosticLog", "构建报告失败", e)
            return null
        }
        val timestamp = fileTimeFmt.format(Date())
        val fileName = "crash_$timestamp.txt"

        // 1) 写外部目录
        var externalFile: File? = null
        try {
            val ext = context.getExternalFilesDir(null)
            if (ext != null) {
                val dir = File(ext, "debug_logs").apply { mkdirs() }
                val f = File(dir, fileName)
                f.writeText(report)
                externalFile = f
                Log.i("DiagnosticLog", "[external] 已写入: ${f.absolutePath}")
            } else {
                Log.w("DiagnosticLog", "[external] getExternalFilesDir 返回 null")
            }
        } catch (e: Throwable) {
            Log.e("DiagnosticLog", "[external] 写入失败", e)
        }

        // 2) 写内部目录（始终写，作为兜底）
        var internalFile: File? = null
        try {
            val dir = File(AppDataPaths.diagnostics(context), "debug_logs").apply { mkdirs() }
            val f = File(dir, fileName)
            f.writeText(report)
            internalFile = f
            Log.i("DiagnosticLog", "[internal] 已写入: ${f.absolutePath}")
        } catch (e: Throwable) {
            Log.e("DiagnosticLog", "[internal] 写入失败", e)
        }

        val result = externalFile ?: internalFile
        if (result != null) {
            log("DiagnosticLog", "崩溃报告就绪: ${result.absolutePath}")
        } else {
            log("DiagnosticLog", "崩溃报告写入完全失败（外部+内部都失败）")
        }
        return result
    }

    private fun buildReport(error: Throwable?, extra: String?): String {
        val sb = StringBuilder()
        val s = session
        sb.appendLine("═══════════════════════════════════════════════")
        sb.appendLine("艨艟战舰工具箱 · 崩溃诊断报告")
        sb.appendLine("生成时间: ${fullTimeFmt.format(Date())}")
        sb.appendLine("当前会话: ${s?.name ?: "(无会话)"}")
        if (s != null) {
            val durMs = System.currentTimeMillis() - s.startMs
            sb.appendLine("会话开始: ${fullTimeFmt.format(Date(s.startMs))} (${durMs} ms 前)")
        }
        sb.appendLine("═══════════════════════════════════════════════")
        sb.appendLine()

        // 设备信息
        sb.appendLine("【设备信息】")
        sb.appendLine("制造商:       ${Build.MANUFACTURER}")
        sb.appendLine("品牌:         ${Build.BRAND}")
        sb.appendLine("型号:         ${Build.MODEL}")
        sb.appendLine("Device:       ${Build.DEVICE}")
        sb.appendLine("Product:      ${Build.PRODUCT}")
        sb.appendLine("Hardware:     ${Build.HARDWARE}")
        sb.appendLine("Android 版本: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("ROM 显示名:   ${Build.DISPLAY}")
        sb.appendLine("Build ID:     ${Build.ID}")
        sb.appendLine("Fingerprint:  ${Build.FINGERPRINT}")
        sb.appendLine("Tags:         ${Build.TAGS}")
        sb.appendLine("Type:         ${Build.TYPE}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sb.appendLine("SoC 制造商:   ${Build.SOC_MANUFACTURER}")
            sb.appendLine("SoC 型号:     ${Build.SOC_MODEL}")
        }
        sb.appendLine("ABI:          ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine()

        // 运行时
        sb.appendLine("【运行时】")
        val rt = Runtime.getRuntime()
        sb.appendLine("最大内存:     ${rt.maxMemory() / 1024 / 1024} MB")
        sb.appendLine("已分配内存:   ${rt.totalMemory() / 1024 / 1024} MB")
        sb.appendLine("空闲内存:     ${rt.freeMemory() / 1024 / 1024} MB")
        sb.appendLine("处理器数:     ${rt.availableProcessors()}")
        sb.appendLine("活动线程数:   ${Thread.activeCount()}")
        sb.appendLine()

        // 异常信息
        if (error != null) {
            sb.appendLine("【异常信息】")
            sb.appendLine("类型: ${error.javaClass.name}")
            sb.appendLine("消息: ${error.message ?: "(无)"}")
            sb.appendLine()
            sb.appendLine("--- 完整调用栈 ---")
            error.stackTrace.forEachIndexed { i, f ->
                sb.appendLine("  #${i.toString().padStart(2)}  ${f.className}.${f.methodName}(${f.fileName ?: "?"}:${f.lineNumber})")
            }
            var cause: Throwable? = error.cause
            var depth = 1
            while (cause != null && cause !== error) {
                sb.appendLine()
                sb.appendLine("--- 原因 #$depth: ${cause.javaClass.name} ---")
                sb.appendLine("消息: ${cause.message ?: "(无)"}")
                cause.stackTrace.forEachIndexed { i, f ->
                    sb.appendLine("  #${i.toString().padStart(2)}  ${f.className}.${f.methodName}(${f.fileName ?: "?"}:${f.lineNumber})")
                }
                cause = cause.cause
                depth++
            }
            sb.appendLine()
        }

        // 触发上下文
        if (extra != null) {
            sb.appendLine("【触发上下文】")
            sb.appendLine(extra)
            sb.appendLine()
        }

        // 本次会话的事件追踪（只此一段，不掺杂其他点击的历史）
        val events = s?.events?.toList().orEmpty()
        sb.appendLine("【本次会话事件追踪 · 共 ${events.size} 条】")
        if (events.isEmpty()) {
            sb.appendLine("  (本次会话无事件)")
        } else {
            for (e in events) {
                sb.appendLine("${timeFmt.format(Date(e.timeMs))} [${e.thread}] ${e.tag}: ${e.message}")
            }
        }
        sb.appendLine()

        // 全线程栈快照
        sb.appendLine("【全线程栈快照】")
        try {
            val threadStacks = Thread.getAllStackTraces().toSortedMap(compareBy { it.name })
            for ((thread, stack) in threadStacks) {
                sb.appendLine("─── ${thread.name} " +
                        "(id=${thread.id}, state=${thread.state}, " +
                        "daemon=${thread.isDaemon}, prio=${thread.priority}, " +
                        "group=${thread.threadGroup?.name}) ───")
                if (stack.isEmpty()) {
                    sb.appendLine("  (空栈)")
                } else {
                    stack.forEachIndexed { i, f ->
                        sb.appendLine("  #${i.toString().padStart(2)}  ${f.className}.${f.methodName}(${f.fileName ?: "?"}:${f.lineNumber})")
                    }
                }
            }
        } catch (e: Exception) {
            sb.appendLine("  (无法获取线程栈: ${e.message})")
        }
        sb.appendLine()

        sb.appendLine("═══════════════════ 报告结束 ═══════════════════")
        return sb.toString()
    }
}
