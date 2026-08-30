package com.whmdg.mczj.tools

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.whmdg.mczj.tools.AppDataPaths
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 独立进程的日志监控服务。
 * 跑在 :monitor 进程里，主进程崩了不受影响。
 * 用 logcat 持续抓取应用日志写入文件，供崩溃后分析。
 */
class ProcessMonitorService : Service() {

    companion object {
        private const val TAG = "ProcessMonitor"
        private var logcatProcess: Process? = null

        fun start(context: android.content.Context) {
            context.startService(Intent(context, ProcessMonitorService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startLogcat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startLogcat()
        return START_STICKY
    }

    private fun startLogcat() {
        // 杀掉旧的 logcat 进程避免重复
        logcatProcess?.destroy()
        logcatProcess = null

        try {
            val logDir = File(AppDataPaths.diagnostics(this), "process_monitor")
            logDir.mkdirs()
            val logFile = File(logDir, "monitor_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.log")

            // 清空旧日志（只保留最近 5 个）
            logDir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }

            val pb = ProcessBuilder("logcat", "-v", "time", "-v", "threadtime")
            pb.redirectErrorStream(true)
            pb.environment()["ANDROID_LOG_TAGS"] = "*:V"

            val proc = pb.start()
            logcatProcess = proc

            Thread({
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        logFile.bufferedWriter().use { writer ->
                            writer.write("=== ProcessMonitor started: ${logFile.name} ===\n")
                            writer.flush()
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                writer.write(line)
                                writer.newLine()
                                writer.flush()  // ponytail: flush every line, add buffered flush when throughput matters
                            }
                        }
                    }
                } catch (_: Exception) {} finally {
                    logcatProcess = null
                }
            }, "LogcatWriter").start()

            Log.i(TAG, "Logcat capturing to ${logFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start logcat", e)
        }
    }

    override fun onDestroy() {
        logcatProcess?.destroy()
        logcatProcess = null
        super.onDestroy()
    }
}
