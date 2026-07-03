package com.whmdg.mczj.tools.security

/**
 * 无障碍服务桥接接口，打破 security ↔ accounting 循环依赖。
 * 由 MyAccessibilityService 在 accounting 模块中实现。
 */
interface AccessibilityServiceBridge {
    fun extractAllTexts(): List<String>
    fun getTopPackageFromWindow(): String?
    fun suppressAutoRecognize()
    /** 最近一次 AccessibilityEvent 的 className */
    val currentLastClassName: String?
    companion object {
        @Volatile var implementation: AccessibilityServiceBridge? = null
    }
}
