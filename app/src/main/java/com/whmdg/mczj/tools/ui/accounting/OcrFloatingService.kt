package com.whmdg.mczj.tools.ui.accounting

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * 悬浮窗托管 Service。
 *
 * 由 OcrLifecycleObserver 在应用切后台时启动，切前台时停止。
 * 负责创建/销毁悬浮窗 View（委托给 OcrFloatingWindow）。
 */
class OcrFloatingService : Service() {

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, OcrFloatingService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OcrFloatingService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        OcrFloatingWindow.show(this)
    }

    override fun onDestroy() {
        OcrFloatingWindow.dismiss()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
