package com.whmdg.mczj.tools.ui.accounting

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * 通过 ProcessLifecycleOwner 监听应用前后台切换。
 *
 * - onStop：应用切到后台 → 延迟 300ms 显示悬浮窗（防 Dialog 误触）
 * - onStart：应用回到前台 → 隐藏悬浮窗（但不停止 Service，保持通知栏）
 */
class OcrLifecycleObserver(private val context: Context) : DefaultLifecycleObserver {

    companion object {
        private const val DELAY_MS = 300L
        /** 应用是否在前台，供 OcrFloatingService.toggleReceiver 查询 */
        @Volatile
        var isAppInForeground = true
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingShow: Runnable? = null
    private var pendingHide: Runnable? = null

    override fun onStart(owner: LifecycleOwner) {
        isAppInForeground = true
        // 应用回到前台：取消待显示，延迟隐藏悬浮窗
        cancelPendingShow()
        pendingHide = Runnable {
            OcrFloatingWindow.dismiss()
        }.also { handler.postDelayed(it, DELAY_MS) }
    }

    override fun onStop(owner: LifecycleOwner) {
        isAppInForeground = false
        // 应用切到后台：取消待隐藏，延迟显示悬浮窗
        cancelPendingHide()
        if (!BillOcrConfig.isEnabled(context)) return

        if (com.whmdg.mczj.tools.security.SpecialPermissionVerifier.isAccessibilityEnabled(context)) {
            pendingShow = Runnable {
                OcrFloatingWindow.show(context)
            }.also { handler.postDelayed(it, DELAY_MS) }
        }
    }

    private fun cancelPendingShow() {
        pendingShow?.let { handler.removeCallbacks(it) }
        pendingShow = null
    }

    private fun cancelPendingHide() {
        pendingHide?.let { handler.removeCallbacks(it) }
        pendingHide = null
    }

}
