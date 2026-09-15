package com.whmdg.mczj.tools.util

import android.util.Log

/**
 * 操作审计日志门面（仅记录"异常 / 冲突 / 程序主动判定"）。
 *
 * 设计原则：
 * - **正常成功执行不记录**。只有程序自身判定出现错误、冲突、异常分支时才输出，
 *   并附带判定所依据的详细数据（如冲突双方的大小/时间、判定规则命中项）。
 * - 输出为**单行结构化格式**，保证经 logcat 传输后不被拆行、可被 monitor 进程完整抓取：
 *   `Audit | event=<事件> | key=value | ... | msg=<描述>`
 * - 走 [Log.i] 的 `Audit` tag，由独立进程的 ProcessMonitorService 持久化。
 *
 * 注意：本类与 [DiagnosticLog] 定位不同。DiagnosticLog 是崩溃时导出当前会话的诊断快照，
 * 会随 beginSession 清空；AuditLog 是操作异常审计流，语义上只关注"出问题的地方"。
 */
object AuditLog {

    private const val TAG = "Audit"

    /** logcat 单条消息安全上限（系统约 4000 字节，留出余量）。 */
    private const val MAX_LINE = 3500

    /**
     * 记录一条审计事件。
     *
     * @param event 事件名（简短英文/标识，如 "file.conflict"、"sync.download.failed"）
     * @param detail 判定依据的键值对，顺序即输出顺序（如 mapOf("src" to a, "dst" to b)）
     * @param message 人类可读的一句话说明（结论）
     */
    fun event(
        event: String,
        detail: Map<String, Any?> = emptyMap(),
        message: String? = null
    ) {
        Log.i(TAG, build(event, detail, message))
    }

    /**
     * 记录一个异常（自动提取类型/消息/首个业务栈帧），用于"执行命令报错"等场景。
     *
     * @param event 事件名
     * @param error 异常对象
     * @param detail 额外判定依据
     */
    fun error(
        event: String,
        error: Throwable,
        detail: Map<String, Any?> = emptyMap()
    ) {
        val firstFrame = error.stackTrace.firstOrNull()?.let {
            "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }
        val merged = LinkedHashMap<String, Any?>()
        merged["errorType"] = error.javaClass.simpleName
        merged["errorMsg"] = error.message
        if (firstFrame != null) merged["at"] = firstFrame
        merged.putAll(detail)
        Log.i(TAG, build(event, merged, null))
    }

    private fun build(event: String, detail: Map<String, Any?>, message: String?): String {
        val sb = StringBuilder("Audit | event=")
        sb.append(event)
        for ((k, v) in detail) {
            if (v == null) continue
            sb.append(" | ").append(k).append('=')
            sb.append(sanitize(v.toString()))
        }
        if (!message.isNullOrBlank()) {
            sb.append(" | msg=").append(sanitize(message))
        }
        // logcat 单条消息上限约 4000 字节，超长会被系统拆行，破坏单行结构化解析。
        return if (sb.length > MAX_LINE) sb.substring(0, MAX_LINE) + "..." else sb.toString()
    }

    /** 保证单行输出：替换换行/竖线，防止结构化字段被破坏。 */
    private fun sanitize(s: String): String {
        if (s.length > 512) {
            return s.replace('\n', ' ').replace('\r', ' ').replace('|', '/').take(512) + "..."
        }
        return s.replace('\n', ' ').replace('\r', ' ').replace('|', '/')
    }
}
