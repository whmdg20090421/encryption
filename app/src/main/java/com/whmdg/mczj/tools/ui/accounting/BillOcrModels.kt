package com.whmdg.mczj.tools.ui.accounting

import android.content.Context
import java.util.UUID

/**
 * 识别的账单结果（暂存，不导入）
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
 * 账单识别配置（通过 AccountingRepository settings 表持久化）
 */
object BillOcrConfig {
    private const val KEY_ENABLED = "bill_ocr_enabled"

    fun isEnabled(context: Context): Boolean =
        AccountingRepository.getSetting(context, KEY_ENABLED) == "true"

    fun setEnabled(context: Context, enabled: Boolean) =
        AccountingRepository.setSetting(context, KEY_ENABLED, enabled.toString())

    /** 支持自动识别的 App（仅用于结果展示） */
    val supportedApps = setOf(
        "com.eg.android.AlipayGphone",  // 支付宝
        "com.tencent.mobileqq",         // QQ
        "com.tencent.mm"                // 微信
    )

    fun getAppName(pkg: String): String = when (pkg) {
        "com.eg.android.AlipayGphone" -> "支付宝"
        "com.tencent.mobileqq" -> "QQ"
        "com.tencent.mm" -> "微信"
        else -> pkg
    }
}
