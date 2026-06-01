package com.whmdg.mczj.tools

import android.app.Application
import android.content.Intent
import android.util.Log
import android.webkit.WebView
import com.whmdg.mczj.tools.util.DiagnosticLog
import java.io.File

class ToolsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installGlobalCrashHandler()
        migrateWebViewData()
        WebView.setDataDirectorySuffix("app")
    }

    /**
     * 进程级全局未捕获异常拦截器。
     * 安装在 Application.onCreate()，是最早也是最后的兜底——
     * 不管异常来自哪个 Activity、哪个线程，只要逃逸到顶层就被拦截。
     */
    private fun installGlobalCrashHandler() {
        val appCtx = applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val msg = throwable.message ?: ""

            // ApkAssets 系统层崩溃 — 无法通过 UI 恢复，吞噬后让进程静默退出
            val isApkAssetsCrash =
                throwable.stackTrace.any {
                    it.className == "android.content.res.ApkAssets" ||
                            it.methodName == "nativeLoadFd" ||
                            it.methodName == "nativeLoad"
                } || msg.contains("Failed to load asset path") || msg.contains(".apk from fd")

            // 写诊断日志（始终执行）
            DiagnosticLog.log("GlobalCrash", "未捕获异常 thread='${thread.name}' type=${throwable.javaClass.simpleName} msg=$msg")
            val ctx = """
                |线程名:       ${thread.name}
                |线程 ID:      ${thread.id}
                |线程组:       ${thread.threadGroup?.name}
                |线程优先级:   ${thread.priority}
                |线程类:       ${thread.javaClass.name}
                |是否守护线程: ${thread.isDaemon}
                |是否吞噬:     $isApkAssetsCrash
            """.trimMargin()
            DiagnosticLog.exportCrashReport(appCtx, throwable, ctx)
            Log.e("GlobalCrashHandler", "未捕获异常", throwable)

            if (isApkAssetsCrash) {
                Log.w("GlobalCrashHandler", "已吞噬 ApkAssets 系统层崩溃")
                return@setDefaultUncaughtExceptionHandler
            }

            // 写完整异常信息到临时文件（供 ErrorReportActivity 读取）
            try {
                val crashDir = File(appCtx.filesDir, "crash_tmp").apply { mkdirs() }
                val crashFile = File(crashDir, "latest_crash.txt")
                crashFile.writeText(buildCrashText(thread, throwable))
            } catch (_: Exception) {}

            // 启动错误报告 Activity（不杀进程）
            try {
                val intent = Intent(appCtx, ErrorReportActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                appCtx.startActivity(intent)
            } catch (_: Exception) {
                // 启动失败，回退到默认 handler
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        DiagnosticLog.log("ToolsApp", "GlobalCrashHandler 已安装")
    }

    private fun buildCrashText(thread: Thread, throwable: Throwable): String {
        val sb = StringBuilder()
        sb.appendLine("异常类型: ${throwable.javaClass.name}")
        sb.appendLine("消息: ${throwable.message ?: "(无消息)"}")
        sb.appendLine("线程: ${thread.name} (id=${thread.id})")
        sb.appendLine()
        sb.appendLine("--- 调用栈 ---")
        throwable.stackTrace.forEachIndexed { i, f ->
            sb.appendLine("  #${i.toString().padStart(2, '0')}  ${f.className}.${f.methodName}(${f.fileName ?: "?"}:${f.lineNumber})")
        }
        var cause: Throwable? = throwable.cause
        var depth = 1
        while (cause != null && cause !== throwable) {
            sb.appendLine()
            sb.appendLine("--- 原因 #$depth: ${cause.javaClass.name} ---")
            sb.appendLine("消息: ${cause.message ?: "(无)"}")
            cause.stackTrace.forEachIndexed { i, f ->
                sb.appendLine("  #${i.toString().padStart(2, '0')}  ${f.className}.${f.methodName}(${f.fileName ?: "?"}:${f.lineNumber})")
            }
            cause = cause.cause
            depth++
        }
        return sb.toString()
    }

    /**
     * 首次升级时将旧 WebView 数据目录 (app_webview/) 迁移到新目录 (app_webview-app/)。
     * setDataDirectorySuffix() 必须在任何 WebView 创建前调用，
     * 且新目录必须不存在，所以先重命名旧目录。
     */
    private fun migrateWebViewData() {
        val dataDir = File(filesDir.parentFile, "app_webview")
        val newDir = File(filesDir.parentFile, "app_webview-app")
        if (dataDir.exists() && !newDir.exists()) {
            if (dataDir.renameTo(newDir)) {
                Log.i("ToolsApp", "WebView 数据已迁移: app_webview/ → app_webview-app/")
            } else {
                Log.w("ToolsApp", "WebView 数据迁移失败（renameTo 返回 false）")
            }
        }
    }
}
