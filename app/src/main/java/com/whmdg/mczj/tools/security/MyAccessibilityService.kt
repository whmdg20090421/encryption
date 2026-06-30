package com.whmdg.mczj.tools.security

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MyAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MyAccessibilityService? = null
            private set
    }

    /** 最近一次检测到的前台应用包名 */
    @Volatile
    var topPackage: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != packageName) {
                topPackage = pkg
            }
        }
    }

    override fun onInterrupt() {}

    fun extractAllText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        traverseNode(root, sb)
        return sb.toString()
    }

    /** 从当前活跃窗口获取前台应用包名 */
    fun getTopPackageFromWindow(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    private fun traverseNode(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.let { if (it.isNotBlank()) sb.appendLine(it) }
        node.contentDescription?.let { if (it.isNotBlank()) sb.appendLine(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, sb)
            child.recycle()
        }
    }

    /**
     * 截取当前屏幕（Android 11+）
     * 截图数据在内存中，不保存到文件
     * 注意：返回的是硬件位图，需要在 IO 线程调用 toSoftwareBitmap() 转换
     */
    suspend fun takeScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return suspendCancellableCoroutine { continuation ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            result.hardwareBuffer,
                            result.colorSpace
                        )
                        result.hardwareBuffer.close()
                        if (continuation.isActive) {
                            continuation.resume(hardwareBitmap)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            )
        }
    }

    /**
     * 将硬件位图转换为软件位图（ARGB_8888）
     * 使用 Bitmap.copy() 方法，标准规范写法
     * 必须在 IO 线程调用
     */
    fun Bitmap.toSoftwareBitmap(): Bitmap {
        if (config != Bitmap.Config.HARDWARE) return this
        // 使用 copy 方法将硬件位图转为软件位图
        val softwareBitmap = copy(Bitmap.Config.ARGB_8888, false)
        // 硬件位图占用显存，用完必须回收
        recycle()
        return softwareBitmap ?: throw IllegalStateException("Failed to convert hardware bitmap to software bitmap")
    }
}
