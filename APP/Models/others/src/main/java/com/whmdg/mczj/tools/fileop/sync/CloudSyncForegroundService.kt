package com.whmdg.mczj.tools.fileop.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.whmdg.mczj.tools.util.FormatUtils

/**
 * 云盘上传/下载的前台 Service（dataSync）+ 部分唤醒锁。
 *
 * 职责：为长时间运行的云同步提供前台身份与 CPU 唤醒，避免切后台后被系统冻结导致
 * socket 停顿（触发 OkHttp 写超时）或进程被杀。进度展示走持续通知，复用同步任务
 * 已有的每秒节流更新。
 *
 * 无 Binder：进度通过 [update] 静态方法直接刷新通知，进程内单实例。
 * 通知权限缺失时不显示通知，前台身份依旧生效（保活不受影响）。
 */
class CloudSyncForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "cloud_sync"
        private const val NOTIFICATION_ID = 1002
        private const val WAKE_LOCK_TAG = "CloudSync:transfer"
        /** 唤醒锁单次持有上限，超时自动释放防止泄漏。 */
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        /** 通知刷新最小间隔：避免每个 128KB 分块都刷一次通知。 */
        private const val UPDATE_THROTTLE_MS = 100L

        @Volatile
        private var instance: CloudSyncForegroundService? = null

        @Volatile
        private var lastUpdateMs = 0L

        /** 启动/切换到前台并持有唤醒锁。可重复调用（幂等）。 */
        fun start(context: Context, title: String) {
            lastUpdateMs = 0L
            val intent = Intent(context, CloudSyncForegroundService::class.java)
            intent.putExtra(EXTRA_TITLE, title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 刷新通知进度。非主线程安全：内部切主线程。
         *
         * 节流：距上次刷新不足 [UPDATE_THROTTLE_MS] 的调用直接丢弃。
         * [force] 为 true 时无视节流（用于完成/取消/失败等终态，保证不停在中间值）。
         */
        fun update(
            percent: Float,
            uploaded: Long,
            total: Long,
            elapsedMs: Long,
            avgSpeed: Long,
            force: Boolean = false
        ) {
            val inst = instance ?: return
            val now = System.currentTimeMillis()
            if (!force && now - lastUpdateMs < UPDATE_THROTTLE_MS) return
            lastUpdateMs = now
            val payload = ProgressPayload(percent, uploaded, total, elapsedMs, avgSpeed)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                inst.lastPayload = payload
                inst.updateNotification()
            }
        }

        /**
         * 结束并清空通知：
         * - [success] 为 true 时取消通知后再停前台（任务完成，提示无意义）；
         * - 其余情况（取消/失败）保留最终态。
         * 随后释放唤醒锁、停止前台。
         */
        fun finish(context: Context, success: Boolean) {
            // 先停前台（系统随之移除通知），再显式 cancel 兜底，避免残留
            stop(context)
            if (success) {
                try {
                    val nm = context.getSystemService(NotificationManager::class.java)
                    nm?.cancel(NOTIFICATION_ID)
                } catch (_: Exception) {
                }
            }
        }

        /** 停止前台并释放唤醒锁。任务结束/取消/异常都必须调用。 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CloudSyncForegroundService::class.java))
            } catch (_: Exception) {
            }
        }

        private const val EXTRA_TITLE = "cloud_sync_title"

        private data class ProgressPayload(
            val percent: Float,
            val uploaded: Long,
            val total: Long,
            val elapsedMs: Long,
            val avgSpeed: Long
        )
    }

    private var title: String = "云盘同步"
    private var lastPayload: ProgressPayload? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            intent.getStringExtra(EXTRA_TITLE)?.let { title = it }
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        val p = lastPayload
        val contentTitle = title
        val contentText: String
        val subText: String
        if (p == null) {
            contentText = "正在准备…"
            subText = ""
        } else {
            val pct = (p.percent * 100).coerceIn(0f, 100f)
            val uploaded = FormatUtils.formatBytes(p.uploaded)
            val total = FormatUtils.formatBytes(p.total)
            val elapsed = formatDuration(p.elapsedMs)
            val speed = FormatUtils.formatBytes(p.avgSpeed) + "/s"
            // 第一行：百分比 · 已传/总量；第二行：已用时间 · 平均速度
            contentText = "${"%.2f".format(pct)}%  ·  $uploaded / $total"
            subText = "已用 $elapsed  ·  平均 $speed"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSubText(subText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$contentText\n$subText"))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((p?.percent ?: 0f) * 100).toInt().coerceIn(0, 100), p == null)
            .setContentIntent(buildContentIntent())
            .build()
    }

    private fun buildContentIntent(): PendingIntent? {
        return try {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch == null) null else PendingIntent.getActivity(
                this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "云盘同步",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "云盘上传/下载进度"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
            }
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = lock
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0秒"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${s}秒"
            else -> "${s}秒"
        }
    }
}
