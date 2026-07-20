package com.whmdg.mczj.tools

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import okio.Path.Companion.toPath
import com.topjohnwu.superuser.Shell
import com.whmdg.mczj.tools.util.DiagnosticLog
import com.whmdg.mczj.tools.util.AppIconHelper
import com.whmdg.mczj.tools.security.ShellExecutor
import com.whmdg.mczj.tools.AppDataPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ToolsApp : Application(), SingletonImageLoader.Factory {

    companion object {
        lateinit var instance: ToolsApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ShellExecutor.init(this)
        // libsu: 配置全局 root shell（首次 Shell.cmd() 时懒创建，后续复用）
        Shell.setDefaultBuilder(Shell.Builder.create()
            .setFlags(Shell.FLAG_MOUNT_MASTER)
            .setTimeout(10))
        installGlobalCrashHandler()
        startAnrWatchdog()
        migrateWebViewData()
        AppIconHelper.init(this)
        WebView.setDataDirectorySuffix("app")

        // OCR 悬浮窗：监听应用前后台切换
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            com.whmdg.mczj.tools.ui.accounting.OcrLifecycleObserver(this)
        )

        // WebDAV: 初始化 Client 认证器
        com.whmdg.mczj.tools.fileop.webdav.client.Client.authenticator =
            com.whmdg.mczj.tools.fileop.webdav.WebDavAuthenticator
        com.whmdg.mczj.tools.fileop.webdav.WebDavAuthenticator.setPersistentServers(
            com.whmdg.mczj.tools.fileop.webdav.WebDavServerStore.getAll(this)
        )
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        val diskCacheDir = context.getExternalFilesDir(null)?.resolve("image_cache")
            ?: context.cacheDir.resolve("image_cache")
        return ImageLoader.Builder(context)
            .diskCache {
                DiskCache.Builder()
                    .directory(diskCacheDir.absolutePath.toPath())
                    .maxSizeBytes(100L * 1024 * 1024) // 100MB
                    .build()
            }
            .components {
                add(com.whmdg.mczj.tools.util.JxlDecoderFactory())
                add(coil3.svg.SvgDecoder.Factory())
            }
            .build()
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
                val crashDir = File(AppDataPaths.diagnostics(appCtx), "crash_tmp").apply { mkdirs() }
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

    // ── ANR 看门狗 ──

    /**
     * 主线程 ANR 看门狗。
     * 守护线程每秒 ping 主线程一次，若 3 秒未响应则判定卡死，
     * 启动持续采样线程记录主线程栈到 anr_reports/ 目录。
     */
    private fun startAnrWatchdog() {
        val appCtx = applicationContext
        val mainHandler = Handler(Looper.getMainLooper())
        val pingReceived = AtomicBoolean(true)

        Thread({
            while (true) {
                Thread.sleep(1000)
                pingReceived.set(false)
                mainHandler.post { pingReceived.set(true) }
                Thread.sleep(3000)
                if (!pingReceived.get()) {
                    Toast.makeText(appCtx, "检测到主线程无响应，日志已记录", Toast.LENGTH_LONG).show()
                    startAnrSampling(Looper.getMainLooper().thread)
                    break
                }
            }
        }, "ANR-Watchdog").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 持续采样主线程栈，增量追加写入文件，直到进程被杀。
     * 每 500ms 采样一次，每次立即 flush 到磁盘。
     */
    private fun startAnrSampling(mainThread: Thread) {
        val appCtx = applicationContext
        Thread({
            val dir = File(AppDataPaths.diagnostics(appCtx), "ANR日志").apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "anr_$ts.txt")
            var sampleCount = 0

            cleanupOldAnrReports(dir)

            file.appendText("=== ANR 检测开始: $ts ===\n")
            file.appendText("主线程: ${mainThread.name} (id=${mainThread.id})\n\n")

            while (true) {
                try {
                    sampleCount++
                    val now = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                    val stack = mainThread.stackTrace
                    val sb = StringBuilder()
                    sb.appendLine("--- 采样 #$sampleCount [$now] ---")
                    if (stack.isEmpty()) {
                        sb.appendLine("  (栈为空)")
                    } else {
                        stack.forEach { f ->
                            sb.appendLine("  at ${f.className}.${f.methodName}(${f.fileName ?: "?"}:${f.lineNumber})")
                        }
                    }
                    sb.appendLine()
                    file.appendText(sb.toString())
                } catch (_: Exception) {
                    break
                }
                Thread.sleep(500)
            }
        }, "ANR-Sampler").apply {
            isDaemon = true
            start()
        }
    }

    private fun cleanupOldAnrReports(dir: File) {
        try {
            val files = dir.listFiles { f -> f.extension == "txt" }
                ?.sortedByDescending { it.name } ?: return
            if (files.size > 10) {
                files.drop(10).forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }
}
