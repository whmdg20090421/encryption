package com.whmdg.mczj.tools

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.whmdg.mczj.tools.util.AppDataPaths
import com.whmdg.mczj.tools.util.DiagnosticLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 增强版事件驱动 ANR 看门狗。
 *
 * 设计参考 Android 系统 ANR 检测机制，适配普通应用权限（无 SYSTEM/ROOT）。
 *
 * ── 检测机制（双层冗余）──
 *
 * 【主检测】Looper 消息级观察（参考 InputDispatcher 事件派发超时 + BlockCanary）
 *   通过 Looper.setMessageLogging 监控每条消息的派发/完成时间。
 *   消息处理超过 TIMEOUT_MS → 判定 ANR。
 *   优势：精确到单条消息级别，可定位具体是哪个 Handler 的哪条消息卡死。
 *
 * 【辅助检测】心跳超时（参考系统 Watchdog 的 HandlerChecker）
 *   定时向主线程投递心跳任务并等待回应，作为 Looper 观察者的兜底。
 *   若 Looper 完全停止调度（极端情况），心跳仍能检出。
 *
 * ── 诊断采集（参考 AppErrors.appNotResponding）──
 * ANR 触发后收集：
 *   - 主线程 + 全线程调用栈（含线程状态、是否 daemon）
 *   - CPU 使用率（进程级 + 线程级，读 /proc/self/task/<tid>/stat）
 *   - 系统负载（/proc/loadavg）
 *   - 内存压力状态（ActivityManager.getMemoryInfo + Runtime 堆信息）
 *   - 死锁检测（分析 BLOCKED 线程的 Monitor 争用）
 *   - 阻塞消息信息（哪个 Handler / what / obj）
 *   - 进程启动至今时间（uptime，排除冷启动误判）
 *
 * ── 权限过滤 ──
 * 系统功能                          普通应用替代方案
 * ──────────────────────────────── ────────────────────────
 * ProcessCpuTracker (SYSTEM)       /proc/self/task/<tid>/stat
 * SIGQUIT dump (系统发送信号)       Thread.getAllStackTraces()
 * dumpsys meminfo (SHELL)          ActivityManager.getMemoryInfo()
 * InputDispatcher (Native)         Looper.setMessageLogging
 * MonitorInfo (JVMTI)              线程状态启发式分析
 */
object AnrWatchdog {

    private const val TAG = "ANR-Watchdog"

    /**
     * 主线程消息处理超时（毫秒）。
     * 系统 InputDispatcher 超时 5 秒，这里设为 4 秒确保先于系统触发。
     */
    private const val TIMEOUT_MS = 4000L

    /** 心跳间隔（毫秒） */
    private const val HEARTBEAT_INTERVAL_MS = 800L

    /** ANR 后持续采样间隔（毫秒） */
    private const val SAMPLE_INTERVAL_MS = 500L

    /** 最大 ANR 日志文件数 */
    private const val MAX_ANR_REPORTS = 10

    /** 慢消息告警阈值（毫秒）— 不触发 ANR，仅记录 */
    private const val SLOW_MSG_THRESHOLD_MS = 200L

    @Volatile
    private var running = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainThread: Thread get() = Looper.getMainLooper().thread

    // ── Looper 消息派发观察状态 ──
    private var savedPrinter: Looper.Printer? = null
    private val dispatchStartTime = AtomicLong(0L)
    private val currentDispatchMsg = AtomicReference<String?>(null)

    /**
     * 启动看门狗。应在 Application.onCreate() 中调用，仅调用一次。
     */
    fun start(app: Application) {
        if (running) return
        running = true

        installLooperObserver()
        startHeartbeat(app)

        DiagnosticLog.log(TAG, "看门狗已启动（双层检测），超时=${TIMEOUT_MS}ms")
    }

    // ═══════════════════════════════════════════════════════════════
    // 层 1：Looper 消息级观察（主检测）
    //
    // 原理：Looper 在处理每条消息前后会调用 Printer.println()，
    // 通过测量 ">>>>> Dispatching" 到 "<<<<< Finished" 的时间差，
    // 精确检测单条消息是否超时。
    //
    // 参考：InputDispatcher 的事件派发超时机制
    //       BlockCanary 的 Looper Printer 方案
    // ═══════════════════════════════════════════════════════════════

    private fun installLooperObserver() {
        val looper = Looper.getMainLooper()

        // 保存已有的 Printer（可能被其他库设置，如 BlockCanary / LeakCanary）
        savedPrinter = try {
            looper::class.java.getDeclaredField("mLogging")
                .apply { isAccessible = true }
                .get(looper) as? Looper.Printer
        } catch (_: Exception) {
            null
        }

        looper.setMessageLogging { msg ->
            if (msg.startsWith(">>>>> Dispatching")) {
                dispatchStartTime.set(SystemClock.uptimeMillis())
                currentDispatchMsg.set(msg)
                savedPrinter?.println(msg)
            } else if (msg.startsWith("<<<<< Finished")) {
                val start = dispatchStartTime.getAndSet(0L)
                val dispatchInfo = currentDispatchMsg.getAndSet(null)
                if (start > 0) {
                    val duration = SystemClock.uptimeMillis() - start
                    if (duration >= TIMEOUT_MS) {
                        onLooperAnr(duration, dispatchInfo ?: msg)
                    } else if (duration >= SLOW_MSG_THRESHOLD_MS) {
                        DiagnosticLog.log(TAG, "慢消息 ${duration}ms: ${msg.take(200)}")
                    }
                }
                savedPrinter?.println(msg)
            } else {
                savedPrinter?.println(msg)
            }
        }
    }

    private fun onLooperAnr(duration: Long, blockingMsg: String) {
        if (Debug.isDebuggerConnected()) return

        Thread({
            val report = collectAnrReport("Looper消息派发超时", duration, blockingMsg)
            reportAndLaunch(report)
            startAnrSampling()
        }, "ANR-Reporter").apply {
            isDaemon = true
            start()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 层 2：心跳超时（辅助检测）
    //
    // 原理：参考系统 Watchdog 的 HandlerChecker 模式。
    // 向主线程 Handler 投递 CountDownLatch.countDown() 任务，
    // 在看门狗线程上阻塞等待 TIMEOUT_MS。
    // 若主线程在超时内处理了该任务 → 健康；否则 → ANR。
    //
    // 与旧版轮询的根本区别：不是 sleep 后检查结果，
    // 而是阻塞等待回执，主线程在等待期间任何时刻恢复都能感知。
    // ═══════════════════════════════════════════════════════════════

    private fun startHeartbeat(app: Application) {
        Thread({
            while (running) {
                val latch = CountDownLatch(1)
                val postTime = System.nanoTime()

                mainHandler.post { latch.countDown() }

                val ok = latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)

                if (!ok) {
                    if (Debug.isDebuggerConnected()) {
                        Thread.sleep(TIMEOUT_MS)
                        continue
                    }
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - postTime)
                    val report = collectAnrReport("心跳超时", elapsedMs, currentDispatchMsg.get())
                    reportAndLaunch(report)
                    startAnrSampling()
                    break
                }

                Thread.sleep(HEARTBEAT_INTERVAL_MS)
            }
        }, "ANR-Heartbeat").apply {
            isDaemon = true
            start()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 诊断采集（参考 AppErrors.appNotResponding）
    //
    // 系统在 appNotResponding 中采集：
    //   1. CPU 使用率（ProcessCpuTracker，需 SYSTEM 权限读其他进程）
    //   2. 内存信息（ActivityManager.getMemoryInfo）
    //   3. 线程栈（SIGQUIT → /data/anr/traces.txt）
    //   4. 进程信息（ProcessRecord）
    //
    // 普通应用可采集：
    //   ✅ /proc/self/task/<tid>/stat → 线程 CPU 时间
    //   ✅ /proc/loadavg → 系统负载
    //   ✅ ActivityManager.getMemoryInfo() → 内存压力
    //   ✅ Runtime.getRuntime() → 堆内存
    //   ✅ Thread.getAllStackTraces() → 全线程栈
    //   ✅ Debug.threadCpuTimeNanos() → 当前线程 CPU 时间
    //   ❌ ProcessCpuTracker → 需 SYSTEM
    //   ❌ SIGQUIT dump → 需系统发信号
    //   ❌ dumpsys meminfo → 需 SHELL
    //   ❌ MonitorInfo → 需 JVMTI
    // ═══════════════════════════════════════════════════════════════

    private fun collectAnrReport(source: String, durationMs: Long, blockingMsg: String?): String {
        val sb = StringBuilder()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val uptimeMs = SystemClock.uptimeMillis()

        sb.appendLine("═".repeat(60))
        sb.appendLine("ANR 报告")
        sb.appendLine("═".repeat(60))
        sb.appendLine()
        sb.appendLine("检测来源:  $source")
        sb.appendLine("检测时间:  $ts")
        sb.appendLine("无响应时长: ${durationMs}ms (阈值 ${TIMEOUT_MS}ms)")
        sb.appendLine("进程运行时间: ${uptimeMs / 1000}s (排除冷启动)")
        sb.appendLine("主线程:    ${mainThread.name} (id=${mainThread.id})")
        sb.appendLine()

        // ── 阻塞消息 ──
        if (blockingMsg != null) {
            sb.appendLine("── 阻塞消息 ──")
            sb.appendLine("  ${blockingMsg.take(500)}")
            sb.appendLine()
        }

        // ── CPU 信息 ──
        sb.appendLine("── CPU 信息 ──")
        collectCpuInfo(sb)
        sb.appendLine()

        // ── 内存信息 ──
        sb.appendLine("── 内存信息 ──")
        collectMemoryInfo(sb)
        sb.appendLine()

        // ── 主线程调用栈 ──
        sb.appendLine("── 主线程调用栈 ──")
        appendStack(sb, mainThread.stackTrace)
        sb.appendLine()

        // ── 全线程调用栈 ──
        val allThreads = try {
            Thread.getAllStackTraces()
        } catch (_: Exception) {
            emptyMap()
        }
        sb.appendLine("── 全线程调用栈 (${allThreads.size} 个线程) ──")
        for ((thread, stack) in allThreads.entries.sortedBy { it.key.name }) {
            sb.appendLine()
            val tag = if (thread == mainThread) " [主线程]" else ""
            sb.appendLine("[${thread.name}] id=${thread.id} state=${thread.state} daemon=${thread.isDaemon}$tag")
            appendStack(sb, stack)
        }
        sb.appendLine()

        // ── 死锁检测 ──
        val deadlockInfo = detectDeadlock(allThreads)
        if (deadlockInfo != null) {
            sb.appendLine("── 死锁检测 ──")
            sb.appendLine(deadlockInfo)
            sb.appendLine()
        }

        // ── 子线程状态概览 ──
        sb.appendLine("── 子线程状态概览 ──")
        for ((thread, _) in allThreads.entries.sortedBy { it.key.name }) {
            if (thread == mainThread) continue
            val state = thread.state
            val warn = when (state) {
                Thread.State.BLOCKED -> " [BLOCKED - 锁争用]"
                Thread.State.WAITING -> " [WAITING]"
                Thread.State.TIMED_WAITING -> " [TIMED_WAITING]"
                else -> ""
            }
            sb.appendLine("  ${thread.name} (${state})$warn")
        }
        sb.appendLine()

        sb.appendLine("═".repeat(60))
        return sb.toString()
    }

    /**
     * CPU 信息采集。
     * 系统用 ProcessCpuTracker（需 SYSTEM 权限读其他进程），
     * 这里仅采集本进程可用的信息。
     */
    private fun collectCpuInfo(sb: StringBuilder) {
        try {
            // 进程 CPU 时间
            val cpuNs = Debug.threadCpuTimeNanos()
            sb.appendLine("  当前线程 CPU 时间: ${TimeUnit.NANOSECONDS.toMillis(cpuNs)}ms")

            // 主线程 CPU 时间（/proc/self/task/<tid>/stat）
            val mainTid = mainThread.id.toInt()
            try {
                val stat = File("/proc/self/task/$mainTid/stat").readText()
                val parts = stat.split(" ")
                if (parts.size >= 14) {
                    val utime = parts[13].toLongOrNull() ?: 0L
                    val stime = parts[14].toLongOrNull() ?: 0L
                    val cpuMs = (utime + stime) * 10 // 100Hz → 10ms/tick
                    sb.appendLine("  主线程 CPU 时间: ${cpuMs}ms (utime=$utime stime=$stime ticks)")
                }
            } catch (_: Exception) {}

            // 系统负载
            try {
                val loadAvg = File("/proc/loadavg").readText().trim()
                sb.appendLine("  系统负载: $loadAvg")
            } catch (_: Exception) {}

            // CPU 核心数
            sb.appendLine("  CPU 核心数: ${Runtime.getRuntime().availableProcessors()}")
        } catch (e: Exception) {
            sb.appendLine("  (采集失败: ${e.message})")
        }
    }

    /**
     * 内存信息采集。
     * 系统用 dumpsys meminfo（需 SHELL 权限），
     * 普通应用用 ActivityManager.getMemoryInfo() + Runtime。
     */
    private fun collectMemoryInfo(sb: StringBuilder) {
        try {
            val app = ToolsApp.instance
            val am = app.getSystemService(Application.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)

            val availMB = memInfo.availMem / (1024 * 1024)
            val totalMB = memInfo.totalMem / (1024 * 1024)
            val thresholdMB = memInfo.threshold / (1024 * 1024)
            sb.appendLine("  可用内存: ${availMB}MB / ${totalMB}MB")
            sb.appendLine("  低内存阈值: ${thresholdMB}MB")
            if (memInfo.lowMemory) {
                sb.appendLine("  ⚠ 系统处于低内存状态，可能影响性能")
            }

            // 进程堆内存
            val rt = Runtime.getRuntime()
            val usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val maxMB = rt.maxMemory() / (1024 * 1024)
            sb.appendLine("  堆内存: ${usedMB}MB / ${maxMB}MB")

            // Native 内存（普通应用仅能读自身 PSS）
            try {
                val pidMem = Debug.getNativeHeapAllocatedSize()
                sb.appendLine("  Native 堆: ${pidMem / (1024 * 1024)}MB")
            } catch (_: Exception) {}
        } catch (e: Exception) {
            sb.appendLine("  (采集失败: ${e.message})")
        }
    }

    /**
     * 死锁检测（启发式分析）。
     *
     * 系统 Watchdog 使用 JVMTI 获取完整的 Monitor 争用信息，
     * 普通应用无法获取，改用线程状态 + 调用栈模式匹配。
     */
    private fun detectDeadlock(allThreads: Map<Thread, Array<StackTraceElement>>): String? {
        val blockedThreads = allThreads.entries.filter { it.key.state == Thread.State.BLOCKED }
        if (blockedThreads.size < 2) return null

        val sb = StringBuilder()
        sb.appendLine("检测到 ${blockedThreads.size} 个 BLOCKED 线程，可能存在死锁:")
        sb.appendLine()

        for ((thread, stack) in blockedThreads) {
            sb.appendLine("  线程 [${thread.name}] (id=${thread.id})")
            val lockFrame = stack.firstOrNull {
                it.methodName in setOf("wait", "park", "lock", "tryLock", "acquire") ||
                it.className.contains("ReentrantLock") ||
                it.className.contains("ReentrantReadWriteLock") ||
                it.className.contains("synchronized")
            }
            if (lockFrame != null) {
                sb.appendLine("    等待锁: ${lockFrame.className}.${lockFrame.methodName}(${lockFrame.fileName ?: "?"}:${lockFrame.lineNumber})")
            }
            stack.take(5).forEach { f ->
                sb.appendLine("    at ${f.className}.${f.methodName}(${f.fileName ?: "?"}:${f.lineNumber})")
            }
            sb.appendLine()
        }

        val allBlockedOnLock = blockedThreads.all { (_, stack) ->
            stack.any {
                it.methodName in setOf("wait", "park", "lock", "tryLock", "acquire") ||
                it.className.contains("ReentrantLock")
            }
        }
        if (allBlockedOnLock && blockedThreads.size >= 2) {
            sb.appendLine("  ⚠ 所有 BLOCKED 线程都在等待锁，高度疑似死锁")
        }

        return sb.toString()
    }

    private fun appendStack(sb: StringBuilder, stack: Array<StackTraceElement>) {
        if (stack.isEmpty()) {
            sb.appendLine("  (栈为空)")
        } else {
            stack.forEachIndexed { i, f ->
                sb.appendLine("  #${i.toString().padStart(2, '0')}  ${f.className}.${f.methodName}(${f.fileName ?: "?"}:${f.lineNumber})")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 报告输出 + 错误页面
    // ═══════════════════════════════════════════════════════════════

    private fun reportAndLaunch(report: String) {
        val app = ToolsApp.instance

        DiagnosticLog.log(TAG, report)

        try {
            val crashDir = File(AppDataPaths.diagnostics(app), "crash_tmp").apply { mkdirs() }
            File(crashDir, "latest_crash.txt").writeText(report)
        } catch (_: Exception) {}

        try {
            val anrDir = File(AppDataPaths.diagnostics(app), "ANR日志").apply { mkdirs() }
            cleanupOldReports(anrDir)
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            File(anrDir, "anr_$ts.txt").writeText(report)
        } catch (_: Exception) {}

        try {
            val intent = Intent(app, ErrorReportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            app.startActivity(intent)
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // ANR 后持续采样（参考系统 dumpStackTraces 的持续采样逻辑）
    // ═══════════════════════════════════════════════════════════════

    private fun startAnrSampling() {
        Thread({
            val app = ToolsApp.instance
            val dir = File(AppDataPaths.diagnostics(app), "ANR日志").apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "anr_$ts.txt")
            var count = 0

            file.appendText("\n=== 持续采样开始 ===\n\n")

            while (true) {
                try {
                    count++
                    val now = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                    val stack = mainThread.stackTrace
                    val sb = StringBuilder()
                    sb.appendLine("--- 采样 #$count [$now] ---")
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
                Thread.sleep(SAMPLE_INTERVAL_MS)
            }
        }, "ANR-Sampler").apply {
            isDaemon = true
            start()
        }
    }

    private fun cleanupOldReports(dir: File) {
        try {
            val files = dir.listFiles { f -> f.extension == "txt" }
                ?.sortedByDescending { it.name } ?: return
            if (files.size > MAX_ANR_REPORTS) {
                files.drop(MAX_ANR_REPORTS).forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }
}
