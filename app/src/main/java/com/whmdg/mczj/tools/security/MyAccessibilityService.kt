package com.whmdg.mczj.tools.security

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.whmdg.mczj.tools.ui.accounting.BillOcrEngine
import com.whmdg.mczj.tools.ui.accounting.OcrFloatingWindow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MyAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MyAccessibilityService? = null
            private set

        /** 缓存最新 AccessibilityEvent 的 className，供 BillOcrEngine 使用 */
        var lastClassName: String? = null

        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.launcher",
            "com.android.launcher3",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox"
        )

        private val BILL_PACKAGES = setOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone"
        )

        /** 防抖：上次自动识别的时间戳 */
        private var lastAutoRecognizeTime = 0L
        private const val AUTO_RECOGNIZE_COOLDOWN_MS = 5000L

        /** 手动识别时调用，抑制自动识别 */
        fun suppressAutoRecognize() {
            lastAutoRecognizeTime = System.currentTimeMillis()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.className?.toString()?.let { lastClassName = it }

        // 自动检测：悬浮窗可见时，检测到微信/支付宝页面变化自动识别
        if (event != null && OcrFloatingWindow.isVisible()) {
            val pkg = event.packageName?.toString()
            if (pkg in BILL_PACKAGES) {
                val now = System.currentTimeMillis()
                if (now - lastAutoRecognizeTime > AUTO_RECOGNIZE_COOLDOWN_MS) {
                    lastAutoRecognizeTime = now
                    Handler(Looper.getMainLooper()).post {
                        pkg?.let { autoRecognize(it) }
                    }
                }
            }
        }
    }

    /** 自动识别：从 windows 列表获取目标应用根节点，执行识别 */
    private fun autoRecognize(packageName: String) {
        val root = getRootForPackage(packageName) ?: return
        val texts = mutableListOf<String>()
        traverseNode(root, texts)
        if (texts.isEmpty()) return

        val result = BillOcrEngine.recognizeNowWithTexts(this, packageName, texts)
        if (result != null) {
            OcrFloatingWindow.showAutoResult(this, result)
        }
    }

    override fun onInterrupt() {}

    /** 提取当前窗口所有文本（保留顺序，用于账单识别） */
    fun extractAllTexts(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        traverseNode(root, texts)
        return texts
    }

    /** 从窗口列表获取前台应用包名（优先 TYPE_APPLICATION 窗口，过滤系统包名） */
    fun getTopPackageFromWindow(): String? {
        // 优先遍历窗口列表，找 TYPE_APPLICATION 类型的非系统应用窗口
        val allWindows = windows
        if (allWindows != null) {
            for (window in allWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val pkg = window.root?.packageName?.toString()
                    if (pkg != null && pkg != packageName && pkg !in SYSTEM_PACKAGES) {
                        return pkg
                    }
                }
            }
        }
        // 回退：rootInActiveWindow
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        if (pkg != null && pkg != packageName && pkg !in SYSTEM_PACKAGES) {
            return pkg
        }
        return null
    }

    /** 从 windows 列表获取指定包名的根节点（避免获取到自身悬浮窗节点） */
    fun getRootForPackage(targetPackage: String): AccessibilityNodeInfo? {
        val allWindows = windows
        if (allWindows != null) {
            for (window in allWindows) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString()
                if (pkg == targetPackage) {
                    return root
                }
            }
        }
        // 回退：如果 rootInActiveWindow 的包名匹配
        val fallback = rootInActiveWindow
        if (fallback != null && fallback.packageName?.toString() == targetPackage) {
            return fallback
        }
        return null
    }

    /** 窗口信息 */
    data class WindowInfo(
        val packageName: String,
        val bounds: Rect,
        val type: Int
    )

    /** 获取当前屏幕所有可见窗口的信息 */
    fun getAllWindowsInfo(): List<WindowInfo> {
        val allWindows = windows ?: return emptyList()
        return allWindows.mapNotNull { window ->
            val root = window.root ?: return@mapNotNull null
            val pkg = root.packageName?.toString() ?: return@mapNotNull null
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                WindowInfo(pkg, bounds, window.type)
            } else {
                null
            }
        }
    }

    /** 遍历节点树，提取文本到列表（保留顺序） */
    private fun traverseNode(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        node.text?.let { if (it.isNotBlank()) texts.add(it.toString()) }
        node.contentDescription?.let { if (it.isNotBlank()) texts.add(it.toString()) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, texts)
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
