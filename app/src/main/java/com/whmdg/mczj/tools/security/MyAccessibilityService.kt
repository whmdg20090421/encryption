package com.whmdg.mczj.tools.security

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class MyAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 无需处理事件，作为特殊权限挂载组件
    }

    override fun onInterrupt() {
        // 无需处理打断，作为特殊权限挂载组件
    }
}
