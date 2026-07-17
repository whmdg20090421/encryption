package com.whmdg.mczj.tools.fileop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.whmdg.mczj.tools.util.DiagnosticLog
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 前台 Service + 线程池，管理文件操作任务的执行。
 *
 * 所有任务在 CachedThreadPool 中并发执行。
 * 前台通知防止 Service 被系统杀死导致操作中断。
 */
class FileOperationService : Service() {

    private val executorService = Executors.newCachedThreadPool()
    private val runningJobs = mutableMapOf<FileOperationJob, Future<*>>()
    private val watchdogThreads = mutableMapOf<FileOperationJob, Thread>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        instance = this
        // 处理 Service 启动前排队的任务
        while (pendingJobs.isNotEmpty()) {
            startJob(pendingJobs.removeFirst())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startJob(job: FileOperationJob) {
        synchronized(runningJobs) {
            val future = executorService.submit {
                try {
                    job.run()
                } catch (_: Exception) {
                    // 异常由 Job 内部处理
                } finally {
                    synchronized(runningJobs) {
                        runningJobs.remove(job)
                        watchdogThreads.remove(job)?.interrupt()
                        if (runningJobs.isEmpty()) {
                            stopSelf()
                        }
                    }
                }
            }
            runningJobs[job] = future

            // 启动看门狗线程，检测任务是否卡住
            val watchdog = Thread {
                try {
                    while (!job.cancelFlag.get() && runningJobs.containsKey(job)) {
                        Thread.sleep(WATCHDOG_CHECK_INTERVAL_MS)
                        val elapsed = job.millisSinceLastActivity()
                        if (elapsed > WATCHDOG_TIMEOUT_MS) {
                            val step = job.currentStep
                            DiagnosticLog.log("FileOperationService",
                                "任务超时: step=$step, elapsed=${elapsed}ms, jobId=${job.id}")
                            job.cancelFlag.set(true)
                            // 通过 manager 报告超时错误，触发 UI 弹窗
                            FileOperationManager.reportTimeoutError(step)
                            break
                        }
                    }
                } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
            watchdogThreads[job] = watchdog
        }
    }

    private fun cancelJob(id: Int) {
        synchronized(runningJobs) {
            val entry = runningJobs.entries.find { it.key.id == id }
            if (entry != null) {
                entry.key.cancelFlag.set(true)
                entry.value.cancel(true)
                watchdogThreads.remove(entry.key)?.interrupt()
                runningJobs.remove(entry.key)
            }
            if (runningJobs.isEmpty()) {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        synchronized(runningJobs) {
            for ((job, future) in runningJobs) {
                job.cancelFlag.set(true)
                future.cancel(true)
                watchdogThreads.remove(job)?.interrupt()
            }
            runningJobs.clear()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "文件操作",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "文件复制/移动/删除操作进度"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
            .setContentTitle("文件操作中")
            .setContentText("正在执行文件操作...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "file_operation"
        private const val NOTIFICATION_ID = 1001
        /** 看门狗检查间隔（毫秒） */
        private const val WATCHDOG_CHECK_INTERVAL_MS = 1000L
        /** 任务超时时间（毫秒）：5 秒无活动视为卡住 */
        private const val WATCHDOG_TIMEOUT_MS = 5000L

        private var instance: FileOperationService? = null
        private val pendingJobs = mutableListOf<FileOperationJob>()

        /**
         * 提交任务。Service 未运行时先启动 Service，任务排队等待。
         */
        fun submit(job: FileOperationJob, context: Context) {
            val inst = instance
            if (inst != null) {
                inst.startJob(job)
            } else {
                pendingJobs.add(job)
                context.startService(Intent(context, FileOperationService::class.java))
            }
        }

        /**
         * 取消指定任务。
         */
        fun cancelJob(id: Int) {
            instance?.cancelJob(id) ?: run {
                pendingJobs.removeAll { it.id == id }
            }
        }

        /**
         * 取消所有正在运行的任务。
         */
        fun cancelAll() {
            instance?.let { service ->
                synchronized(service.runningJobs) {
                    for ((job, future) in service.runningJobs) {
                        job.cancelFlag.set(true)
                        future.cancel(true)
                        service.watchdogThreads.remove(job)?.interrupt()
                    }
                    service.runningJobs.clear()
                    service.stopSelf()
                }
            } ?: run {
                pendingJobs.clear()
            }
        }

        /**
         * 优雅取消所有任务：设 cancelFlag 但不 interrupt，让当前文件完成后再停止。
         */
        fun gracefulCancelAll() {
            instance?.let { service ->
                synchronized(service.runningJobs) {
                    for ((job, _) in service.runningJobs) {
                        job.cancelFlag.set(true)
                    }
                }
            }
        }
    }
}
