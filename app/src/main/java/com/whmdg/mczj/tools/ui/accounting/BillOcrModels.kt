package com.whmdg.mczj.tools.ui.accounting

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import java.util.UUID

/**
 * OCR 识别的账单结果（暂存，不导入）
 */
data class OcrBillResult(
    val id: String = UUID.randomUUID().toString(),
    val type: String,           // "支出" / "收入"
    val amount: Double,
    val merchant: String,
    val time: Long,
    val sourceApp: String,      // 包名
    val rawText: String,        // 提取的原始文本
    val confidence: Float,      // 0.0 ~ 1.0
    val matchedRule: String     // 命中的规则描述
)

/**
 * 账单 OCR 配置持久化
 */
object BillOcrConfig {
    private const val PREFS = AppDataPaths.PREFS_ACCOUNTING

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("bill_ocr_enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("bill_ocr_enabled", enabled).apply()

    /** 支持自动识别的 App */
    val supportedApps = setOf(
        "com.eg.android.AlipayGphone",  // 支付宝
        "com.tencent.mobileqq"          // QQ
    )

    fun getAppName(pkg: String): String = when (pkg) {
        "com.eg.android.AlipayGphone" -> "支付宝"
        "com.tencent.mobileqq" -> "QQ"
        else -> pkg
    }
}
