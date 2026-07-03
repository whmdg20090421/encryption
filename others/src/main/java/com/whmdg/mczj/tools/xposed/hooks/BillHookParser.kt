package com.whmdg.mczj.tools.xposed.hooks

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 解析微信支付 WebView 返回的账单详情 JSON。
 *
 * 输入：respbuf 解析后的 JSONObject（含 header + preview 数组）
 * 输出：Map<String, String> 格式的账单数据（可直接通过 Intent 传输）
 */
object BillHookParser {

    /**
     * 解析账单 JSON，返回 null 表示解析失败。
     */
    fun parse(respJson: JSONObject): Map<String, String>? {
        val header = respJson.optJSONObject("header") ?: return null
        val nickname = header.optString("nickname", "")
        val fee = header.optString("fee", "")

        if (nickname.isEmpty() && fee.isEmpty()) return null

        val preview = respJson.optJSONArray("preview")

        // 从 preview 数组提取各字段
        var status = ""
        var description = ""
        var timestamp = ""
        var orderNo = ""

        if (preview != null) {
            for (i in 0 until preview.length()) {
                val item = preview.optJSONObject(i) ?: continue
                val label = item.optJSONObject("label")?.optString("name", "") ?: ""
                val valueStr = extractStringValue(item)

                when {
                    label.contains("当前状态") -> status = valueStr
                    label.contains("说明") || label.contains("备注") -> description = valueStr
                    label.contains("时间") && !label.contains("到账") -> timestamp = valueStr
                    label.contains("单号") -> orderNo = valueStr
                }
            }
        }

        // 解析金额：从 fee 字段（格式 "+0.01" / "-25.00" / "¥100.00"）
        val amount = parseAmount(fee)
        if (amount == 0.0 && nickname.isEmpty()) return null

        // 判断收支类型
        val type = determineType(nickname, description, fee, status)

        // 解析时间
        val timeMs = parseTimestamp(timestamp)

        // 商户名：从 nickname 提取（去掉 "-来自微信支付" 后缀）
        val merchant = extractMerchant(nickname)

        return mapOf(
            "type" to type,
            "amount" to amount.toString(),
            "merchant" to merchant,
            "time" to timeMs.toString(),
            "sourceApp" to "com.tencent.mm",
            "rawText" to respJson.toString().take(500),
            "confidence" to "0.9",
            "matchedRule" to "Hook",
            "orderNo" to orderNo,
            "status" to status,
            "description" to description
        )
    }

    private fun extractStringValue(item: JSONObject): String {
        val value = item.opt("value") ?: return ""
        return when (value) {
            is String -> value
            is org.json.JSONArray -> {
                val first = value.optJSONObject(0) ?: return ""
                first.optString("name", "")
            }
            else -> value.toString()
        }
    }

    private fun parseAmount(fee: String): Double {
        val cleaned = fee.replace("[¥￥+,，]".toRegex(), "").trim()
        return cleaned.toDoubleOrNull() ?: 0.0
    }

    private fun determineType(
        nickname: String,
        description: String,
        fee: String,
        status: String
    ): String {
        // 负金额 → 支出
        if (fee.startsWith("-")) return "支出"
        // 正金额 → 收入
        if (fee.startsWith("+")) return "收入"

        val text = "$nickname $description $status"
        return when {
            text.contains("收入") || text.contains("收款") || text.contains("到账")
                || text.contains("转入") || text.contains("已存入") -> "收入"
            text.contains("支出") || text.contains("付款") || text.contains("消费")
                || text.contains("转出") -> "支出"
            text.contains("转账") -> "收入"  // 转账默认收入（对方发来的）
            else -> "支出"
        }
    }

    private fun parseTimestamp(timestamp: String): Long {
        if (timestamp.isEmpty()) return System.currentTimeMillis()
        // 尝试秒级时间戳
        val ts = timestamp.toLongOrNull()
        if (ts != null) {
            return if (ts < 10000000000L) ts * 1000 else ts
        }
        // 尝试常见日期格式
        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "MM-dd HH:mm"
        )
        for (fmt in formats) {
            try {
                return SimpleDateFormat(fmt, Locale.CHINA).parse(timestamp)?.time
                    ?: continue
            } catch (_: Exception) {}
        }
        return System.currentTimeMillis()
    }

    private fun extractMerchant(nickname: String): String {
        // 去掉常见后缀
        return nickname
            .replace("-来自微信支付", "")
            .replace("来自微信支付", "")
            .replace("-来自零钱", "")
            .trim()
            .ifEmpty { "未知商户" }
    }
}
