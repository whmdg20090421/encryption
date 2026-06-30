package com.whmdg.mczj.tools.security

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.whmdg.mczj.tools.ui.accounting.BillOcrConfig
import com.whmdg.mczj.tools.ui.accounting.OcrFloatingWindow

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
        // 如果 OCR 开关已开启，显示悬浮窗
        if (BillOcrConfig.isEnabled(this)) {
            OcrFloatingWindow.show(this)
        }
    }

    override fun onDestroy() {
        OcrFloatingWindow.dismiss()
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

    /**
     * 遍历当前窗口节点树，提取所有可见文字。
     * 用于账单 OCR 识别：读取支付宝/QQ 账单页面的文字内容。
     */
    fun extractAllText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        traverseNode(root, sb)
        return sb.toString()
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
}
