package com.whmdg.mczj.tools.ui.accounting

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder

/**
 * Hook 模式悬浮窗托管 Service。
 *
 * 由 OcrLifecycleObserver 在应用切后台且模式为 Hook 时启动。
 * 负责创建/销毁悬浮窗 View，以及注册广播接收器。
 */
class HookFloatingService : Service() {

    private var receiver: HookResultReceiver? = null

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, HookFloatingService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HookFloatingService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 注册广播接收器（跨进程接收微信 Hook 数据，需要 RECEIVER_EXPORTED）
        receiver = HookResultReceiver()
        val filter = IntentFilter(HookResultReceiver.ACTION_HOOK_BILL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        // 显示悬浮窗
        HookFloatingWindow.show(this)
    }

    override fun onDestroy() {
        HookFloatingWindow.dismiss()
        // 注销广播接收器
        receiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
