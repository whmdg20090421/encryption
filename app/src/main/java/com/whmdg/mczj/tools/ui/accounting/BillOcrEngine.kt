package com.whmdg.mczj.tools.ui.accounting

import android.os.Handler
import android.os.Looper
import com.whmdg.mczj.tools.security.MyAccessibilityService

/**
 * 账单 OCR 引擎（本地规则匹配，不联网）。
 *
 * 流程：前台应用变化 → 节点树提取文字 → 关键词过滤 → 正则匹配 → 缓存结果。
 * 参考 AutoAccounting 的 AnalysisUtils.inWhitelist() 和 BillService.parseBillInfo()。
 */
object BillOcrEngine {

    private var lastOcrTime = 0L
    private var cachedResult: OcrBillResult? = null
    private const val COOLDOWN_MS = 5000L // 防抖 5 秒

    /** 前台应用变化回调（由 MyAccessibilityService.onAccessibilityEvent 调用） */
    fun onForegroundChanged(service: MyAccessibilityService, packageName: String) {
        if (!BillOcrConfig.isEnabled(service)) return
        if (packageName !in BillOcrConfig.supportedApps) return
        if (System.currentTimeMillis() - lastOcrTime < COOLDOWN_MS) return

        // 延迟 1.5 秒等待页面渲染完成
        Handler(Looper.getMainLooper()).postDelayed({
            processScreen(service, packageName)
        }, 1500)
    }

    private fun processScreen(service: MyAccessibilityService, app: String) {
        val text = service.extractAllText()
        if (text.isBlank()) return

        // 关键词过滤：不是账单页面则跳过
        if (!matchesBillKeywords(text)) return

        // 正则匹配
        val result = parseBill(text, app) ?: return
        cachedResult = result
        lastOcrTime = System.currentTimeMillis()
    }

    // ── 关键词过滤（参考 AutoAccounting AnalysisUtils.inWhitelist） ──
    private fun matchesBillKeywords(text: String): Boolean {
        val keywords = listOf(
            "付款", "收款", "¥", "￥", "支付", "转账", "红包",
            "账单", "交易", "消费", "收入", "到账", "充值",
            "零钱", "余额", "银行卡"
        )
        return keywords.any { text.contains(it) }
    }

    // ── 正则规则匹配（参考 AutoAccounting BillService.parseBillInfo） ──
    private fun parseBill(text: String, app: String): OcrBillResult? {
        // 金额提取：¥123.45 / ￥123.45 / 123.45元 / 支付 123.45
        val amountRegex = Regex(
            "[¥￥]\\s*(\\d+\\.?\\d*)" +
            "|(\\d+\\.\\d{2})\\s*元" +
            "|(?:支付|付款|收款|转账|消费)\\s*(\\d+\\.?\\d*)"
        )
        val amount = amountRegex.find(text)?.groupValues?.drop(1)
            ?.firstOrNull { it.isNotEmpty() }?.toDoubleOrNull() ?: return null

        if (amount <= 0) return null

        // 商户名提取
        val merchantRegex = Regex(
            "付款给\\s*(.+?)[\\s\\n]" +
            "|向\\s*(.+?)\\s*付款" +
            "|商户[：:]\\s*(.+?)\\s*[\\n\$]" +
            "|收款方[：:]\\s*(.+?)\\s*[\\n\$]"
        )
        val merchant = merchantRegex.find(text)?.groupValues?.drop(1)
            ?.firstOrNull { !it.isNullOrBlank() }?.trim() ?: ""

        // 类型判断：包含收款/到账/收入 → 收入，否则 → 支出
        val type = if (text.contains("收款") || text.contains("到账")
            || text.contains("收入") || text.contains("转入"))
            "收入" else "支出"

        return OcrBillResult(
            type = type,
            amount = amount,
            merchant = merchant.ifEmpty { "未知商户" },
            time = System.currentTimeMillis(),
            sourceApp = app,
            rawText = text.take(500),
            confidence = if (merchant.isNotEmpty()) 0.8f else 0.5f,
            matchedRule = "本地规则"
        )
    }

    /** 获取并清除缓存结果（切回应用时调用） */
    fun consumeResult(): OcrBillResult? {
        val r = cachedResult
        cachedResult = null
        return r
    }
}
