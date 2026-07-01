package com.whmdg.mczj.tools.ui.accounting

import com.whmdg.mczj.tools.security.MyAccessibilityService
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 账单识别引擎（通过无障碍节点树提取文字，本地规则匹配）。
 *
 * 参照一木记账（com.wangc.bill）的无障碍识别模式实现：
 * 1. 遍历节点树提取所有文本
 * 2. 使用 HashMap 快速查找关键词
 * 3. 根据关键词组合判断页面类型
 * 4. 上下文关联提取金额、备注等信息
 */
object BillOcrEngine {

    /** 识别结果：成功或带原因的失败 */
    data class RecognizeResult(
        val bill: OcrBillResult?,
        val error: String? = null,
        val debugText: String? = null
    )

    /** 页面类型枚举 */
    enum class BillPageType {
        PAY_SUCCESS,      // 支付成功
        TRANSFER,         // 转账
        RED_PACKET,       // 红包
        RECEIPT,          // 收款
        RECHARGE,         // 充值
        WITHDRAW,         // 提现
        DETAIL,           // 详情页（不提取）
        UNKNOWN
    }

    // ── 关键词集合（参照一木记账 action.p 和 action.b） ──

    /** 支付成功关键词 */
    private val PAY_SUCCESS_KEYWORDS = setOf(
        "支付成功", "付款成功", "转账成功", "代付成功",
        "交易成功", "充值成功", "还款成功", "退款成功",
        "收款成功", "已收款", "已转账", "有退款",
        "已全额退款", "缴费中", "派遣中"
    )

    /** 红包关键词 */
    private val RED_PACKET_KEYWORDS = setOf(
        "的红包", "红包金额", "查看红包记录", "领取成功",
        "查看红包", "支付宝红包"
    )

    /** 转账关键词 */
    private val TRANSFER_KEYWORDS = setOf(
        "转账成功", "已转入", "已转出", "零钱通转出",
        "零钱通转入", "余额宝", "提现", "充值成功",
        "余额转入", "转出成功", "转入成功"
    )

    /** 收款关键词 */
    private val RECEIPT_KEYWORDS = setOf(
        "已收款", "收款成功", "你已收款", "资金待入账",
        "已收齐", "二维码收款"
    )

    /** 详情页关键词（排除，不提取） */
    private val DETAIL_KEYWORDS = setOf(
        "当前状态", "交易单号", "账单详情", "交易详情",
        "账单管理", "余额明细详情", "订单详情", "商品图片",
        "查看账单详情", "常见问题"
    )

    /** 时间关键词 */
    private val TIME_KEYWORDS = listOf(
        "支付时间", "转账时间", "收款时间", "交易时间",
        "充值时间", "到账时间", "退款时间", "创建时间",
        "订单时间", "申请时间"
    )

    // ── 主入口 ──

    /** 通过节点树识别 */
    fun recognizeNow(service: MyAccessibilityService): RecognizeResult {
        val texts = service.extractAllTexts()
        if (texts.isEmpty()) {
            return RecognizeResult(null, "未获取到页面文字")
        }

        val pkg = service.getTopPackageFromWindow() ?: "未知"

        // 构建 HashMap 用于快速查找（参照一木记账的模式）
        val textMap = HashMap<String, Boolean>()
        for (text in texts) {
            textMap[text] = true
        }

        // 判断页面类型
        val pageType = detectPageType(texts, textMap)
        if (pageType == BillPageType.DETAIL) {
            return RecognizeResult(null, "详情页，跳过识别", texts.take(5).joinToString("\n"))
        }
        if (pageType == BillPageType.UNKNOWN) {
            return RecognizeResult(null, "未识别到账单页面", texts.take(5).joinToString("\n"))
        }

        // 提取金额
        val amountStr = extractAmount(texts, textMap)
        if (amountStr == null) {
            return RecognizeResult(null, "未提取到金额", texts.take(10).joinToString("\n"))
        }
        val amount = amountStr.replace(",", "").toDoubleOrNull()
        if (amount == null || amount <= 0) {
            return RecognizeResult(null, "金额格式错误: $amountStr")
        }

        // 提取备注/商户名
        val remark = extractRemark(texts, textMap, pageType)

        // 提取支付方式
        val payMethod = extractPayMethod(texts)

        // 提取时间
        val time = extractTime(texts) ?: System.currentTimeMillis()

        // 判断收支类型
        val type = determineType(texts, textMap, pageType)

        return RecognizeResult(
            OcrBillResult(
                type = type,
                amount = amount,
                merchant = remark,
                time = time,
                sourceApp = pkg,
                rawText = texts.joinToString("\n").take(500),
                confidence = if (remark != "未知商户") 0.9f else 0.6f,
                matchedRule = "${BillOcrConfig.getAppName(pkg)}-${pageType.name}"
            )
        )
    }

    // ── 页面类型判断 ──

    private fun detectPageType(texts: List<String>, textMap: HashMap<String, Boolean>): BillPageType {
        // 排除详情页（参照一木记账：有"当前状态"、"交易单号"的是详情页）
        if (DETAIL_KEYWORDS.any { textMap.containsKey(it) }) {
            // 但如果同时有"支付成功"等关键词，可能是支付结果页
            val hasPaySuccess = PAY_SUCCESS_KEYWORDS.any { textMap.containsKey(it) }
            if (!hasPaySuccess) {
                return BillPageType.DETAIL
            }
        }

        // 红包
        if (RED_PACKET_KEYWORDS.any { textMap.containsKey(it) }) {
            return BillPageType.RED_PACKET
        }

        // 转账
        if (TRANSFER_KEYWORDS.any { textMap.containsKey(it) }) {
            return BillPageType.TRANSFER
        }

        // 收款
        if (RECEIPT_KEYWORDS.any { textMap.containsKey(it) }) {
            return BillPageType.RECEIPT
        }

        // 支付成功
        if (PAY_SUCCESS_KEYWORDS.any { textMap.containsKey(it) }) {
            return BillPageType.PAY_SUCCESS
        }

        // 兜底：有 ¥ 或 ￥ 符号，且有"元"字，可能是账单
        val hasAmountSymbol = texts.any { it.contains("¥") || it.contains("￥") }
        val hasYuan = texts.any { it.contains("元") }
        if (hasAmountSymbol && hasYuan) {
            return BillPageType.PAY_SUCCESS
        }

        return BillPageType.UNKNOWN
    }

    // ── 金额提取（参照一木记账的多种格式支持） ──

    private fun extractAmount(texts: List<String>, textMap: HashMap<String, Boolean>): String? {
        // 方式1：直接找 ¥ 或 ￥ 符号（最常见）
        for (text in texts) {
            if (text.contains("¥") || text.contains("￥")) {
                val amount = text.replace("¥", "").replace("￥", "")
                    .replace(",", "").replace("+", "").replace("-", "")
                    .replace("元", "").replace("支出", "").trim()
                if (amount.matches(Regex("^\\d+\\.?\\d*$"))) {
                    return amount
                }
            }
        }

        // 方式2：找 "支付成功"/"收款成功" 后面的金额
        for (i in texts.indices) {
            if (texts[i] in PAY_SUCCESS_KEYWORDS || texts[i] in RECEIPT_KEYWORDS) {
                // 金额通常在下一行
                if (i + 1 < texts.size) {
                    val amount = texts[i + 1].replace("¥", "").replace("￥", "")
                        .replace(",", "").replace("元", "").replace("支出", "").trim()
                    if (amount.matches(Regex("^\\d+\\.?\\d*$"))) {
                        return amount
                    }
                }
                // 也可能在上一行
                if (i - 1 >= 0) {
                    val amount = texts[i - 1].replace("¥", "").replace("￥", "")
                        .replace(",", "").replace("元", "").replace("支出", "").trim()
                    if (amount.matches(Regex("^\\d+\\.?\\d*$"))) {
                        return amount
                    }
                }
            }
        }

        // 方式3：找包含"元"的文本
        for (text in texts) {
            if (text.contains("元") && !text.contains("元素") && !text.contains("元宝")) {
                val amount = text.replace("¥", "").replace("￥", "")
                    .replace(",", "").replace("元", "").replace("支出", "")
                    .replace("+", "").replace("-", "").trim()
                if (amount.matches(Regex("^\\d+\\.?\\d*$"))) {
                    return amount
                }
            }
        }

        return null
    }

    // ── 备注/商户名提取（参照一木记账的上下文关联） ──

    private fun extractRemark(
        texts: List<String>,
        textMap: HashMap<String, Boolean>,
        pageType: BillPageType
    ): String {
        when (pageType) {
            BillPageType.PAY_SUCCESS -> {
                // 微信：找 "付款给 XXX"
                for (text in texts) {
                    if (text.startsWith("付款给 ")) {
                        return text.removePrefix("付款给 ").trim()
                    }
                    if (text.startsWith("向") && text.contains("付款")) {
                        return text.removePrefix("向").substringBefore("付款").trim()
                    }
                }
                // 支付宝：找 "收款方" 后面的内容
                for (i in texts.indices) {
                    if (texts[i] == "收款方" && i + 1 < texts.size) {
                        return texts[i + 1]
                    }
                }
                // 支付宝：找 "付款给" 后面的内容
                for (i in texts.indices) {
                    if (texts[i].startsWith("付款给") && i + 1 < texts.size) {
                        return texts[i + 1]
                    }
                }
                // 通用：找 "收款方" 或 "商户" 后面的内容
                for (i in texts.indices) {
                    if ((texts[i] == "收款方" || texts[i].startsWith("商户") ||
                                texts[i] == "付款方") && i + 1 < texts.size
                    ) {
                        return texts[i + 1]
                    }
                }
                // 微信：找 "支付成功" 后面的第二个文本（第一个是金额）
                for (i in texts.indices) {
                    if (texts[i] in PAY_SUCCESS_KEYWORDS) {
                        if (i + 2 < texts.size) {
                            val remark = texts[i + 2]
                            if (!remark.contains("¥") && !remark.contains("￥") &&
                                !remark.contains("元") && remark.length < 50
                            ) {
                                return remark
                            }
                        }
                    }
                }
            }

            BillPageType.TRANSFER -> {
                // 找 "转账说明" 或 "转账给" 后面的内容
                for (i in texts.indices) {
                    if (texts[i] == "转账说明" && i + 1 < texts.size) {
                        return texts[i + 1]
                    }
                    if (texts[i].startsWith("转账给 ")) {
                        return texts[i].removePrefix("转账给 ").trim()
                    }
                    if (texts[i] == "转账给" && i + 1 < texts.size) {
                        return texts[i + 1]
                    }
                }
                // 支付宝：找 "收款方" 后面的内容
                for (i in texts.indices) {
                    if (texts[i] == "收款方" && i + 1 < texts.size) {
                        return texts[i + 1]
                    }
                }
            }

            BillPageType.RED_PACKET -> {
                // 找 "XXX的红包"
                for (text in texts) {
                    if (text.contains("的红包")) {
                        return text
                    }
                }
            }

            BillPageType.RECEIPT -> {
                // 找 "付款方" 或 "转账说明"
                for (i in texts.indices) {
                    if (texts[i] == "付款方" && i + 1 < texts.size) {
                        return texts[i + 1]
                    }
                    if (texts[i] == "转账说明" && i + 1 < texts.size) {
                        return texts[i + 1]
                    }
                }
            }

            else -> {}
        }

        return "未知商户"
    }

    // ── 支付方式提取 ──

    private fun extractPayMethod(texts: List<String>): String? {
        for (i in texts.indices) {
            if ((texts[i] == "付款方式" || texts[i] == "支付方式" ||
                        texts[i] == "收款方式" || texts[i] == "退款方式") && i + 1 < texts.size
            ) {
                return texts[i + 1]
            }
        }
        return null
    }

    // ── 时间提取 ──

    private fun extractTime(texts: List<String>): Long? {
        for (i in texts.indices) {
            for (keyword in TIME_KEYWORDS) {
                if (texts[i].startsWith(keyword)) {
                    val timeStr = texts[i].removePrefix(keyword).trim()
                    val time = parseTimeString(timeStr)
                    if (time != null) return time

                    // 时间可能在下一行
                    if (i + 1 < texts.size) {
                        val time2 = parseTimeString(texts[i + 1])
                        if (time2 != null) return time2
                    }
                }
            }
        }
        return null
    }

    /** 解析时间字符串 */
    private fun parseTimeString(timeStr: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy年MM月dd日 HH:mm:ss",
            "yyyy-MM-dd HH点mm分ss秒",
            "yyyy-MM-dd HH:mm",
            "yyyy年MM月dd日 HH:mm"
        )

        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.CHINA)
                val date = sdf.parse(timeStr)
                if (date != null) return date.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    // ── 收支类型判断 ──

    private fun determineType(
        texts: List<String>,
        textMap: HashMap<String, Boolean>,
        pageType: BillPageType
    ): String {
        return when (pageType) {
            BillPageType.RECEIPT, BillPageType.RED_PACKET -> "收入"
            BillPageType.TRANSFER -> {
                // 根据关键词判断
                if (textMap.containsKey("已转入") || textMap.containsKey("收款") ||
                    textMap.containsKey("转入成功") || textMap.containsKey("余额转入")
                ) {
                    "收入"
                } else {
                    "支出"
                }
            }

            BillPageType.PAY_SUCCESS -> {
                // 检查是否有收入标识
                if (textMap.containsKey("收款") || textMap.containsKey("到账") ||
                    textMap.containsKey("收入") || textMap.containsKey("转入")
                ) {
                    "收入"
                } else {
                    "支出"
                }
            }

            else -> "支出"
        }
    }
}
