package com.whmdg.mczj.tools.ui.accounting

import com.whmdg.mczj.tools.security.MyAccessibilityService

/**
 * 账单 OCR 引擎（本地规则匹配，不联网）。
 *
 * 由悬浮窗主动调用 recognizeNow()，同步提取当前页面文字并匹配账单信息。
 */
object BillOcrEngine {

    /** 识别结果：成功或带原因的失败 */
    data class RecognizeResult(
        val bill: OcrBillResult?,
        val error: String? = null
    )

    /** 同步识别当前屏幕 */
    fun recognizeNow(service: MyAccessibilityService): RecognizeResult {
        val text = service.extractAllText()
        if (text.isBlank()) {
            return RecognizeResult(null, "未获取到页面文字")
        }

        // 优先使用 topPackage（事件更新），回退到从窗口节点获取
        val pkg = service.topPackage ?: service.getTopPackageFromWindow()
        if (pkg == null) {
            return RecognizeResult(null, "未检测到前台应用")
        }
        if (pkg !in BillOcrConfig.supportedApps) {
            return RecognizeResult(null, "当前应用不支持：${BillOcrConfig.getAppName(pkg)}")
        }

        if (!matchesBillKeywords(text)) {
            return RecognizeResult(null, "未匹配到账单关键词")
        }

        val bill = parseBill(text, pkg)
        if (bill == null) {
            return RecognizeResult(null, "未解析到金额信息")
        }

        return RecognizeResult(bill)
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
}
