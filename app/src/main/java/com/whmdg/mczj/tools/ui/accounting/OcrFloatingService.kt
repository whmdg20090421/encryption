package com.whmdg.mczj.tools.ui.accounting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import com.whmdg.mczj.tools.R

/**
 * 悬浮窗托管 Service（前台服务 + 通知栏控制）。
 *
 * 由 OcrLifecycleObserver 在应用切后台时启动，切前台时停止。
 * 通知栏显示"自动记账已运行"，点击切换为"自动记账已暂停"并禁用功能。
 */
class OcrFloatingService : Service() {

    companion object {
        private const val CHANNEL_ID = "AutoAccounting"
        private const val NOTIFICATION_ID = 5537
        const val ACTION_TOGGLE = "com.whmdg.mczj.tools.ACTION_TOGGLE_AUTO_ACCOUNTING"

        fun start(context: Context) {
            val intent = Intent(context, OcrFloatingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OcrFloatingService::class.java)
            context.stopService(intent)
        }
    }

    private var toggleReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerToggleReceiver()
        showNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        OcrFloatingWindow.dismiss()
        unregisterToggleReceiver()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "自动记账",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun showNotification() {
        val isEnabled = BillOcrConfig.isEnabled(this)
        val text = if (isEnabled) "自动记账已运行" else "自动记账已暂停"

        val toggleIntent = Intent(ACTION_TOGGLE)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun registerToggleReceiver() {
        toggleReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_TOGGLE) {
                    val wasEnabled = BillOcrConfig.isEnabled(context)
                    BillOcrConfig.setEnabled(context, !wasEnabled)
                    if (wasEnabled) {
                        // 禁用：关闭悬浮窗
                        OcrFloatingWindow.dismiss()
                    } else {
                        // 启用：显示悬浮窗
                        OcrFloatingWindow.show(context)
                    }
                    updateNotification()
                }
            }
        }
        val filter = IntentFilter(ACTION_TOGGLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(toggleReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(toggleReceiver, filter)
        }
    }

    private fun unregisterToggleReceiver() {
        toggleReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        toggleReceiver = null
    }

    private fun updateNotification() {
        val isEnabled = BillOcrConfig.isEnabled(this)
        val text = if (isEnabled) "自动记账已运行" else "自动记账已暂停"

        val toggleIntent = Intent(ACTION_TOGGLE)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }
}
