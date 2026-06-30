package com.whmdg.mczj.tools.security

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MyAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MyAccessibilityService? = null
            private set

        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.launcher",
            "com.android.launcher3",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun extractAllText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        traverseNode(root, sb)
        return sb.toString()
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

    /**
     * 搜索所有窗口，找到包含在 supportedPkgs 中的应用窗口
     * 返回 Pair(包名, 窗口在屏幕中的矩形区域)，未找到返回 null
     */
    fun findSupportedAppWindow(supportedPkgs: Set<String>): Pair<String, Rect>? {
        val allWindows = windows ?: return null
        for (window in allWindows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (pkg in supportedPkgs) {
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return pkg to bounds
                }
            }
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
