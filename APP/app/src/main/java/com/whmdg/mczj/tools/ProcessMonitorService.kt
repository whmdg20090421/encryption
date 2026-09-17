package com.whmdg.mczj.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import com.whmdg.mczj.tools.AppDataPaths
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 独立进程的日志监控服务。
 *
 * 跑在 :monitor 进程里，主进程崩了不受影响。用 logcat 持续抓取本应用日志写入文件：
 * - 主进程与 :monitor 自身的常规日志
 * - `Audit` tag 的操作审计流（异常/冲突/主动判定），始终保留
 *
 * 生命周期策略（应用前后台联动）：
 * - 主进程通过 [ACTION_START] / [ACTION_STOP] 命令控制抓取
 * - 应用在前台：startForeground（specialUse）+ 启动 logcat
 * - 应用切后台：停止 logcat + stopForeground（释放资源，此时 Service 可被系统回收）
 * - 再次回到前台：重新启动 logcat
 * - 主进程崩溃：收全崩溃日志后自行 stopSelf 退出
 * - [workerRunning] 保证抓取线程全局唯一，避免重复启动丢日志
 * - 单文件超过 [MAX_FILE_BYTES] 自动轮转，保留最近 [MAX_FILES] 个文件
 */
class ProcessMonitorService : Service() {

    companion object {
        private const val TAG = "ProcessMonitor"

        private const val CHANNEL_ID = "process_monitor"
        private const val NOTIFICATION_ID = 2001

        /** 前台时开始抓取 */
        const val ACTION_START = "com.whmdg.mczj.tools.monitor.START"

        /** 切后台时停止抓取 */
        const val ACTION_STOP = "com.whmdg.mczj.tools.monitor.STOP"

        /** 主进程 pid，用于识别主进程崩溃 */
        const val EXTRA_MAIN_PID = "main_pid"

        /** 检测到主进程崩溃后，继续抓取的收尾时长（收全多行堆栈） */
        private const val CRASH_DRAIN_MS = 3000L

        /** 单个日志文件大小上限（16MB），超过后轮转到新文件 */
        private const val MAX_FILE_BYTES = 16L * 1024 * 1024

        /** 保留的日志文件数量 */
        private const val MAX_FILES = 5

        @Volatile
        private var logcatProcess: java.lang.Process? = null

        /** 抓取线程是否已在运行，防止重复启动 */
        private val workerRunning = AtomicBoolean(false)

        /**
         * 是否由 [stopLogcat] 主动停止。
         * 主动销毁 logcat 进程会使读取线程抛出 InterruptedIOException，
         * 这属于预期内的关闭信号，不应作为错误记录。
         */
        @Volatile
        private var stoppingIntentionally = false

        /** 当前是否已提升为前台 Service */
        @Volatile
        private var foreground = false

        /** 主进程 pid（由主进程通过 START 命令传入，<=0 表示未知） */
        @Volatile
        private var mainPid = -1

        /** 是否已检测到主进程崩溃（读取线程结束时据此决定自行退出） */
        @Volatile
        private var crashDetectedForSelfStop = false

        /** 发送开始抓取命令（应用进入前台时调用）。 */
        fun start(context: android.content.Context) {
            send(context, ACTION_START, Process.myPid())
        }

        /** 发送停止抓取命令（应用切到后台时调用）。 */
        fun stop(context: android.content.Context) {
            send(context, ACTION_STOP, -1)
        }

        private fun send(context: android.content.Context, action: String, pid: Int) {
            try {
                val intent = Intent(context, ProcessMonitorService::class.java)
                    .setAction(action)
                    .putExtra(EXTRA_MAIN_PID, pid)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // 后台启动前台 Service 在部分机型/版本会被限制，记录后忽略
                Log.e(TAG, "发送日志监控命令失败 action=$action", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 必须在前台启动时先调用 startForeground，否则系统会抛
                // ForegroundServiceDidNotStartInTimeException 崩溃。随后立即降级。
                promoteToForeground()
                stopCapture()
            }
            else -> {
                val pid = intent?.getIntExtra(EXTRA_MAIN_PID, -1) ?: -1
                if (pid > 0) mainPid = pid
                // 默认（含 ACTION_START / 系统重启）都进入抓取
                startCapture()
            }
        }
        // 启停完全由主进程的前后台生命周期驱动（见 ProcessMonitorLifecycleObserver），
        // 不用 START_STICKY，避免系统在后台重启服务时因无法 startForeground 而崩溃。
        return START_NOT_STICKY
    }

    /** 应用在前台：提升为前台 Service 并开始抓取。 */
    private fun startCapture() {
        promoteToForeground()
        startLogcat()
    }

    /** 应用切后台：停止抓取并释放前台状态。 */
    private fun stopCapture() {
        stopLogcat()
        demoteFromForeground()
    }

    /** 提升为前台 Service，降低被系统回收的概率。 */
    private fun promoteToForeground() {
        if (foreground) return
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // specialUse 类型自 API 34 起才被系统识别
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foreground = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败", e)
        }
    }

    /** 退出前台状态（切后台时调用），使 Service 可被系统正常回收。 */
    private fun demoteFromForeground() {
        if (!foreground) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopForeground 失败", e)
        } finally {
            foreground = false
        }
    }

    private fun startLogcat() {
        if (!workerRunning.compareAndSet(false, true)) {
            Log.d(TAG, "Logcat 抓取已运行，跳过重复启动")
            return
        }
        crashDetectedForSelfStop = false
        stoppingIntentionally = false

        try {
            val logDir = File(AppDataPaths.diagnostics(this), "process_monitor")
            logDir.mkdirs()
            pruneOldLogs(logDir)

            val logFile = newLogFile(logDir)

            val myPid = Process.myPid()
            // -v threadtime 输出 pid/tid；main+system 覆盖应用日志与 ANR/crash 摘要。
            // 无法用 logcat 原生参数按进程名过滤，因此读取后由 shouldKeep() 按包名/pid/Audit 过滤。
            val pb = ProcessBuilder("logcat", "-v", "threadtime", "-b", "main", "-b", "system")
            pb.redirectErrorStream(true)

            val proc = pb.start()
            logcatProcess = proc

            Thread({
                var writer: BufferedWriter? = null
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        val initial = logFile.bufferedWriter()
                        writer = initial
                        initial.write("=== ProcessMonitor started: ${logFile.name} (pid=$myPid) ===\n")
                        initial.flush()

                        // 当前写入器（非空），轮转时替换
                        var out: BufferedWriter = initial
                        var writtenBytes = logFile.length()
                        var line: String?
                        var crashDetectedAt = 0L
                        while (reader.readLine().also { line = it } != null) {
                            val l = line ?: continue

                            // 主进程崩溃：记录标志，进入收尾倒计时（继续抓取收全多行堆栈）
                            if (crashDetectedAt == 0L && isMainProcessCrash(l, mainPid)) {
                                crashDetectedAt = System.currentTimeMillis()
                                crashDetectedForSelfStop = true
                                out.write("=== ProcessMonitor: 检测到主进程崩溃，${CRASH_DRAIN_MS}ms 后停止 ===\n")
                                out.flush()
                            }

                            if (shouldKeep(l, myPid)) {
                                out.write(l)
                                out.newLine()
                                writtenBytes += l.length + 1
                                out.flush()

                                if (writtenBytes >= MAX_FILE_BYTES) {
                                    out.close()
                                    val rotated = newLogFile(logDir)
                                    val next = rotated.bufferedWriter()
                                    writer = next
                                    out = next
                                    next.write("=== ProcessMonitor rotated: ${rotated.name} ===\n")
                                    next.flush()
                                    writtenBytes = rotated.length()
                                    pruneOldLogs(logDir)
                                }
                            }

                            // 收尾窗口结束：落盘并自行退出
                            if (crashDetectedAt > 0L &&
                                System.currentTimeMillis() - crashDetectedAt >= CRASH_DRAIN_MS
                            ) {
                                out.write("=== ProcessMonitor: 主进程崩溃日志已保存，退出 ===\n")
                                out.flush()
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 主动停止时销毁 logcat 进程会中断阻塞中的 readLine()，
                    // 抛出 InterruptedIOException —— 这是预期内的关闭信号，不作为错误。
                    if (stoppingIntentionally) {
                        Log.d(TAG, "Logcat 抓取已按请求停止")
                    } else {
                        Log.e(TAG, "Logcat 读取循环异常", e)
                    }
                } finally {
                    try {
                        writer?.flush()
                        writer?.close()
                    } catch (_: Exception) {}
                    logcatProcess = null
                    workerRunning.set(false)
                    // 检测到主进程崩溃：保存完日志后自行退出，不再常驻
                    if (crashDetectedForSelfStop) {
                        stopSelf()
                    }
                }
            }, "LogcatWriter").start()

            Log.i(TAG, "Logcat capturing to ${logFile.absolutePath} (pid=$myPid)")
        } catch (e: Exception) {
            workerRunning.set(false)
            Log.e(TAG, "Failed to start logcat", e)
        }
    }

    /** 停止 logcat 抓取进程。写入线程读到 EOF 会自行收尾并把 [workerRunning] 复位。 */
    private fun stopLogcat() {
        val proc = logcatProcess
        // 先置标志再销毁进程，确保读取线程捕获中断时能识别为主动停止
        stoppingIntentionally = true
        logcatProcess = null
        try {
            proc?.destroy()
        } catch (_: Exception) {}
        // 进程已销毁，读取循环会退出；若从未启动则直接复位。
        if (proc == null) workerRunning.set(false)
    }

    /**
     * 判断一行是否标志"主进程崩溃"。
     *
     * 覆盖三类信号：
     * - Java 崩溃：`FATAL EXCEPTION`，其后若干行含 `Process: com.whmdg.mczj.tools`
     * - Native 崩溃：`Fatal signal` / libc 的 `tombstone`（无包名前缀，靠 pid 匹配）
     * - 进程死亡：`Process com.whmdg.mczj.tools ... has died` 或 `ActivityManager` 报告
     *
     * @param pid 主进程 pid；未指定（<=0）时退化为按包名匹配
     */
    private fun isMainProcessCrash(line: String, pid: Int): Boolean {
        val mentionsMain = line.contains("com.whmdg.mczj.tools") ||
                (pid > 0 && line.contains(" $pid "))
        if (!mentionsMain) return false

        return line.contains("FATAL EXCEPTION") ||
                line.contains("Fatal signal") ||
                line.contains("tombstone") ||
                line.contains("has died") ||
                line.contains("ANR in com.whmdg.mczj.tools")
    }

    /**
     * 判断一行 logcat 是否属于本应用、值得记录。
     * 行格式：`09-15 22:21:18.924  5778  5778 W j.tools:monitor: message`
     */
    private fun shouldKeep(line: String, myPid: Int): Boolean {
        // 操作审计流：按消息体前缀匹配，避免依赖 logcat 标签列的填充空格
        if (line.contains("Audit | event=")) return true
        // 本应用两个进程
        if (line.contains("whmdg.mczj.tools") || line.contains("j.tools:monitor")) return true
        // 崩溃/异常摘要
        if (line.contains("FATAL EXCEPTION") ||
            line.contains("tombstone") ||
            line.contains("ANR in")
        ) return true
        // 携带本进程 pid 的 native 日志
        return line.contains(" $myPid ")
    }

    /** 生成唯一的日志文件（毫秒时间戳，避免同秒内轮转碰撞）。 */
    private fun newLogFile(logDir: File): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(logDir, "monitor_$stamp.log")
    }

    /** 删除超出数量上限的旧日志（按文件名时间戳倒序保留最新 MAX_FILES 个）。 */
    private fun pruneOldLogs(logDir: File) {
        logDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("monitor_") }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_FILES)
            ?.forEach { it.delete() }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "运行日志监控",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "应用使用期间记录运行与操作审计日志，用于排查问题"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("日志监控运行中")
            .setContentText("正在记录应用运行日志，用于问题排查")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        // Service 销毁属主动停止，避免读取线程把中断异常记成错误
        stoppingIntentionally = true
        logcatProcess?.destroy()
        logcatProcess = null
        workerRunning.set(false)
        foreground = false
        super.onDestroy()
    }
}
