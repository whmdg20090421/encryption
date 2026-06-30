package com.whmdg.mczj.tools.ui.accounting

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.whmdg.mczj.tools.util.XposedDetector

/**
 * 通过 ProcessLifecycleOwner 监听应用前后台切换。
 *
 * - onStop：应用切到后台 → 延迟 300ms 启动悬浮窗 Service（防 Dialog 误触）
 * - onStart：应用回到前台 → 取消延迟 / 停止 Service
 *
 * 根据 [BillOcrConfig.getOcrMode] 决定启动 OCR 还是 Hook 模式的 Service。
 */
class OcrLifecycleObserver(private val context: Context) : DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingStart: Runnable? = null
    private var pendingStop: Runnable? = null

    override fun onStart(owner: LifecycleOwner) {
        // 应用回到前台：取消待启动，延迟停止 Service
        cancelPendingStart()
        pendingStop = Runnable {
            OcrFloatingService.stop(context)
            HookFloatingService.stop(context)
        }.also { handler.postDelayed(it, DELAY_MS) }
    }

    override fun onStop(owner: LifecycleOwner) {
        // 应用切到后台：取消待停止，延迟启动 Service
        cancelPendingStop()
        if (!BillOcrConfig.isEnabled(context)) return

        when (BillOcrConfig.getOcrMode(context)) {
            BillOcrConfig.MODE_HOOK -> {
                // Hook 模式：仅需 Xposed 模块激活
                if (XposedDetector.isModuleActive()) {
                    pendingStart = Runnable {
                        HookFloatingService.start(context)
                    }.also { handler.postDelayed(it, DELAY_MS) }
                }
            }
            else -> {
                // OCR 模式：需要无障碍服务
                if (com.whmdg.mczj.tools.security.SpecialPermissionVerifier.isAccessibilityEnabled(context)) {
                    pendingStart = Runnable {
                        OcrFloatingService.start(context)
                    }.also { handler.postDelayed(it, DELAY_MS) }
                }
            }
        }
    }

    private fun cancelPendingStart() {
        pendingStart?.let { handler.removeCallbacks(it) }
        pendingStart = null
    }

    private fun cancelPendingStop() {
        pendingStop?.let { handler.removeCallbacks(it) }
        pendingStop = null
    }

    companion object {
        private const val DELAY_MS = 300L
    }
}
