package com.whmdg.mczj.tools.ui.accounting

import android.view.accessibility.AccessibilityNodeInfo
import com.whmdg.mczj.tools.security.MyAccessibilityService
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 识别的账单结果（对应原版 BillInfo）
 */
data class OcrBillInfo(
    var origin: String = "",        // "微信" / "支付宝"
    var number: String = "",        // 金额（字符串）
    var remark: String = "",        // 备注
    var shopName: String = "",      // 商家名
    var asset: String = "",         // 支付方式
    var income: Boolean = false,    // 是否收入
    var transfer: Boolean = false,  // 是否转账
    var fromAsset: String = "",     // 转出账户
    var toAsset: String = "",       // 转入账户
    var time: Long = 0L,            // 时间戳
    var discount: String = "",      // 优惠金额
    var serviceCharge: Double = 0.0, // 服务费
    var transactionId: String = "",  // 交易单号（自动记账专用）
    var merchantOrderId: String = "" // 商户单号（自动记账专用）
)

/**
 * 无障碍账单识别引擎（完全复刻一木记账反编译代码逻辑）
 *
 * 对应关系：
 * - 基础工具方法 → c.java
 * - 微信处理器 → p.java
 * - 支付宝处理器 → b.java
 * - 金额判断 → g2.java
 */
object BillOcrEngine {

    // ════════════════════════════════════════════════════════════════
    // 基础工具方法（对应 c.java）
    // ════════════════════════════════════════════════════════════════

    /** 对应 c.a()：检查列表中是否包含关键词 */
    private fun containsText(list: List<String>, keyword: String, exact: Boolean): Boolean {
        for (item in list) {
            if (item == null) continue
            if (exact) {
                if (item == keyword) return true
            } else {
                if (item.contains(keyword)) return true
            }
        }
        return false
    }

    /** 对应 c.b()：检查 hashMap 中是否包含 list 中的任意一个 key */
    private fun hashMapContainsAnyKey(hashMap: HashMap<String, Boolean>, list: List<String>): Boolean {
        for (item in list) {
            if (hashMap.containsKey(item)) return true
        }
        return false
    }

    /** 对应 c.d()：查找第一次出现的索引 */
    private fun findIndex(list: List<String>, keyword: String, exact: Boolean): Int {
        for (i in list.indices) {
            val item = list[i]
            if (exact) {
                if (item == keyword) return i
            } else {
                if (item.contains(keyword)) return i
            }
        }
        return -1
    }

    /** 对应 c.f()：查找最后一次出现的索引 */
    private fun findLastIndex(list: List<String>, keyword: String, exact: Boolean): Int {
        for (i in list.indices.reversed()) {
            val item = list[i]
            if (exact) {
                if (item == keyword) return i
            } else {
                if (item.contains(keyword)) return i
            }
        }
        return -1
    }

    // ── 节点遍历方法 ──

    /**
     * 对应 c.g() + c.i()：仅提取 text（支付宝处理器使用）
     * 限制：最多 99 条文本，最多 999 层递归
     */
    private fun extractTextsOnly(node: AccessibilityNodeInfo?): List<String> {
        var depth = 0
        fun traverse(current: AccessibilityNodeInfo, texts: MutableList<String>): MutableList<String> {
            depth++
            for (i in 0 until current.childCount) {
                val child = current.getChild(i) ?: continue
                if (child.childCount > 0) {
                    if (texts.size <= 99 && depth <= 999) {
                        if (child != current) {
                            traverse(child, texts)
                        }
                    } else {
                        return texts
                    }
                }
                if (child.text != null && child.text.isNotEmpty()) {
                    texts.add(child.text.toString())
                }
            }
            return texts
        }
        if (node == null) return emptyList()
        val result = mutableListOf<String>()
        traverse(node, result)
        return result
    }

    /**
     * 对应 c.m() + c.n()：提取 text + contentDescription（微信处理器使用）
     * 限制：最多 99 条文本，最多 999 层递归
     */
    private fun extractTextsAndDescriptions(node: AccessibilityNodeInfo?): List<String> {
        var depth = 0
        fun traverse(current: AccessibilityNodeInfo, texts: MutableList<String>): MutableList<String> {
            depth++
            for (i in 0 until current.childCount) {
                val child = current.getChild(i) ?: continue
                if (child.childCount > 0) {
                    if (texts.size <= 99 && depth <= 999) {
                        if (child != current) {
                            traverse(child, texts)
                        }
                    } else {
                        return texts
                    }
                }
                if (child.text != null && child.text.isNotEmpty()) {
                    texts.add(child.text.toString())
                } else if (child.contentDescription != null && child.contentDescription.isNotEmpty()) {
                    texts.add(child.contentDescription.toString())
                }
            }
            return texts
        }
        if (node == null) return emptyList()
        val result = mutableListOf<String>()
        traverse(node, result)
        return result
    }

    // ── 金额相关工具方法（对应 g2.java）──

    /** 对应 g2.I()：判断字符串是否是有效金额 */
    private fun isAmount(text: String): Boolean {
        if (text.matches(Regex("^-?([1-9]\\d*\\.\\d*|0\\.\\d*[1-9]\\d*|0?\\.0+|0)$"))) return true
        if (text.matches(Regex("^(-?[1-9]\\d*)|0$"))) return true
        // 对应 g2.N()：尝试 Double.parseDouble
        return try {
            text.toDouble()
            true
        } catch (_: NumberFormatException) {
            false
        }
    }

    /** 对应 g2.R()：字符串转 Double（逗号替换为点号） */
    private fun toDouble(text: String): Double {
        return try {
            text.replace(",", ".").toDouble()
        } catch (_: NumberFormatException) {
            0.0
        }
    }

    /** 对应 g2.s()：格式化折扣金额（#.00） */
    private fun formatDiscount(value: Double): String {
        if (value != 0.0) {
            val df = DecimalFormat("#.00")
            df.roundingMode = RoundingMode.HALF_UP
            val result = df.format(value)
            return when {
                result.startsWith(".") -> "0$result"
                result.startsWith("-.") -> "-0${result.replace("-", "")}"
                else -> result
            }
        }
        return "0.00"
    }

    /** 构建 HashMap<String, Boolean>（对应原版 textMap 构建逻辑） */
    private fun buildTextMap(list: List<String>): HashMap<String, Boolean> {
        val map = HashMap<String, Boolean>()
        for (item in list) {
            map[item] = true
        }
        return map
    }

    // ── 时间解析 ──

    /** 解析时间字符串，支持多种格式（对应 p1.X0()） */
    private fun parseTime(text: String): Long {
        // 格式顺序与反编译代码 p1.X0() 一致
        val formats = listOf(
            "yyyy年MM月dd日 HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy年MM月dd日 HH点mm分ss秒"
        )
        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.CHINA)
                val date = sdf.parse(text)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return -1L
    }

    // ════════════════════════════════════════════════════════════════
    // 微信处理器（对应 p.java）
    // ════════════════════════════════════════════════════════════════

    // ── 微信状态变量 ──
    private var wechatPageType = 0           // this.a：页面类型
    private var wechatNeedRecognize = false   // this.b：是否需要识别
    private var wechatPayMethod: String? = null    // this.e：支付方式
    private var wechatRechargeMethod: String? = null // this.f：充值方式
    private var wechatInRecognizeMode = false // this.g：是否进入识别模式
    private var wechatIsGroupCollect = false  // this.h：是否群收款模式
    private var wechatIsMiniProgram = false   // this.i：是否小程序模式
    private var wechatScanTime = 0L          // this.j：扫码时间戳
    private var wechatIsScanMode = false     // this.k：是否扫一扫模式

    /** 使用已提取文本的微信处理入口（自动识别用） */
    private fun wechatProcessWithTexts(
        service: MyAccessibilityService,
        className: String,
        texts: List<String>
    ): OcrBillInfo? {
        // 简化 className 匹配：仅设置 g=true，跳过需要 rootInActiveWindow 的检测
        val directRecognizeActivities = listOf(
            "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI",
            "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
            "com.tencent.mm.framework.app.UIPageFragmentActivity",
            "com.tencent.mm.plugin.remittance.ui.RemittanceBusiUI",
            "com.tencent.mm.plugin.remittance.ui.RemittanceUI",
            "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI",
            "com.tencent.mm.plugin.wallet_index.ui.WalletBrandUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyPrepareUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI",
            "com.tencent.mm.plugin.wallet_index.ui.OrderHandlerUI",
            "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI",
            "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI",
            "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI",
            "com.tencent.mm.plugin.aa.ui.PaylistAAUI",
            "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00"
        )

        if (className in directRecognizeActivities || className == "com.tencent.mm.ui.LauncherUI") {
            wechatInRecognizeMode = true
        }

        if (!wechatInRecognizeMode) return null

        val textMap = buildTextMap(texts)

        // 页面类型判断（严格按反编译代码顺序）
        // 1. 转账详情页判断
        if ((textMap.containsKey("当前状态") &&
                (textMap.containsKey("提现单号") || textMap.containsKey("充值完成") || textMap.containsKey("还款成功"))) &&
            !textMap.containsKey("查看账单详情") && !textMap.containsKey("常见问题")) {
            wechatPageType = 11
            wechatNeedRecognize = true
        }
        // 2. 详情页判断（不提取）
        else if (textMap.containsKey("账单详情") ||
            (textMap.containsKey("商品名称") && textMap.containsKey("交易状态")) ||
            (textMap.containsKey("当前状态") &&
                (textMap.containsKey("转账单号") || textMap.containsKey("交易单号") ||
                    textMap.containsKey("付款单号") || textMap.containsKey("收款单号"))) ||
            (textMap.containsKey("退款状态") && textMap.containsKey("退款单号")) ||
            (textMap.containsKey("付款商家") && textMap.containsKey("付款单号")) ||
            (textMap.containsKey("详情") && containsText(texts, "零钱通余额", false)) ||
            (textMap.containsKey("零钱通余额") && textMap.containsKey("交易单号"))
        ) {
            wechatPageType = 3
            wechatNeedRecognize = true
        }
        // 3. 群收款判断
        else if (wechatIsGroupCollect && containsText(texts, "发起的群收款", false)) {
            wechatPageType = 4
            wechatNeedRecognize = true
        }
        // 4. 支付成功页判断
        else if ((className == "com.tencent.mm.plugin.remittance.ui.RemittanceBusiUI" ||
                className == "com.tencent.mm.plugin.remittance.ui.RemittanceUI" ||
                className == "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI" ||
                className == "com.tencent.mm.plugin.wallet_index.ui.WalletBrandUI" ||
                className == "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyPrepareUI" ||
                className == "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI" ||
                className == "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI" ||
                className == "com.tencent.mm.plugin.wallet_index.ui.OrderHandlerUI" ||
                className == "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI") ||
            ((textMap.containsKey("支付成功") || textMap.containsKey("付款成功")) &&
                !textMap.containsKey("当前状态") && !textMap.containsKey("查看账单详情") &&
                !textMap.containsKey("常见问题") && !textMap.containsKey("订单支付成功通知") &&
                !textMap.containsKey("交易单号")) ||
            (textMap.containsKey("二维码收款") &&
                (textMap.containsKey("优先付款方式") ||
                    textMap.containsKey("优先使用此支付方式付款") ||
                    textMap.containsKey("优先使用此付款方式"))) ||
            (textMap.containsKey("付款方式") &&
                (textMap.containsKey("使用密码") || textMap.containsKey("使用指纹") ||
                    textMap.containsKey("请输入支付密码") ||
                    (textMap.containsKey("更改") && textMap.containsKey("1") && textMap.containsKey("2"))))
        ) {
            wechatPageType = 1
            wechatNeedRecognize = true
        }
        // 5. 充值/提现页判断
        else if ((textMap.containsKey("充值成功") && textMap.containsKey("完成")) ||
            (textMap.containsKey("零钱提现") && textMap.containsKey("到账成功")) ||
            (textMap.containsKey("已转入") && textMap.containsKey("完成")) ||
            (textMap.containsKey("已转出") && textMap.containsKey("完成")) ||
            (textMap.containsKey("转出金额") && textMap.containsKey("完成"))
        ) {
            wechatPageType = 10
            wechatNeedRecognize = true
        }

        // 记录扫码时间（s.i() 对应的逻辑，这里简化处理）
        if (!textMap.containsKey("支付成功") &&
            (textMap.containsKey("优先使用此付款方式") && textMap.containsKey("二维码收款") ||
                textMap.containsKey("优先付款方式") && textMap.containsKey("二维码收款") ||
                textMap.containsKey("优先使用此支付方式付款") && textMap.containsKey("二维码收款") ||
                textMap.containsKey("付款方式") &&
                (textMap.containsKey("使用密码") || textMap.containsKey("使用指纹") ||
                    textMap.containsKey("请输入支付密码") ||
                    (textMap.containsKey("更改") && textMap.containsKey("付款方式"))))
        ) {
            wechatScanTime = System.currentTimeMillis()
        }

        // 提取支付方式（类型1时）
        if (wechatPageType == 1) {
            // "付款方式" 后面的文本
            if (textMap.containsKey("付款方式")) {
                val idx = findLastIndex(texts, "付款方式", true)
                if (idx < texts.size - 2) {
                    wechatPayMethod = texts[idx + 1]
                    if ("更改" == wechatPayMethod && idx < texts.size - 3) {
                        wechatPayMethod = texts[idx + 2]
                        if ("更改" == wechatPayMethod && idx < texts.size - 4) {
                            wechatPayMethod = texts[idx + 3]
                        }
                    }
                }
            }
            // "优先使用此支付方式付款" 前面的文本
            if (textMap.containsKey("优先使用此支付方式付款")) {
                val idx = texts.indexOf("优先使用此支付方式付款")
                if (idx > 0) {
                    wechatPayMethod = texts[idx - 1]
                }
            } else if (textMap.containsKey("优先使用此付款方式")) {
                val idx = texts.indexOf("优先使用此付款方式")
                if (idx > 0) {
                    wechatPayMethod = texts[idx - 1]
                }
            } else if (textMap.containsKey("优先付款方式")) {
                val idx = texts.indexOf("优先付款方式")
                if (idx < texts.size - 2) {
                    wechatPayMethod = texts[idx + 1]
                    if ("更改" == wechatPayMethod) {
                        wechatPayMethod = texts[idx + 2]
                    }
                }
            }
        }

        // 提取充值方式（类型10时）
        if (wechatPageType == 10) {
            if (textMap.containsKey("充值方式")) {
                val idx = texts.indexOf("充值方式")
                if (idx < texts.size - 1) {
                    wechatRechargeMethod = texts[idx + 1]
                }
            } else if (textMap.containsKey("到账银行卡")) {
                val idx = texts.indexOf("到账银行卡")
                if (idx < texts.size - 1) {
                    wechatRechargeMethod = texts[idx + 1]
                }
            }
        }

        // 判断是否是收款
        val isReceipt = textMap.containsKey("已收款") || textMap.containsKey("资金待入账") ||
            containsText(texts, "你已收款", false) || containsText(texts, "已收款", false)

        // 判断是否是红包
        val isRedPacket = containsText(texts, "的红包", false)

        // 分发到具体处理器（对应 p.s() 逻辑）
        // 反编译代码 s() 中：
        // - n10=1 时分发：pageType==1 需检查支付成功条件，其他 pageType 直接分发
        // - n10=0 时跳过，走收款/红包分支
        var result: OcrBillInfo? = null

        if (wechatNeedRecognize) {
            val shouldDispatch = if (wechatPageType == 1) {
                // pageType 1 需检查支付成功条件（对应 s() 中 n10 的赋值）
                (textMap.containsKey("付款成功") || textMap.containsKey("支付成功")) &&
                    !textMap.containsKey("当前状态")
            } else {
                // pageType 3,4,10,11 直接分发（对应 s() 中 n10 默认为 1）
                true
            }

            if (shouldDispatch) {
                result = when (wechatPageType) {
                    1 -> wechatPaySuccess(texts, wechatPayMethod)
                    3 -> wechatDetail(texts)
                    4 -> wechatGroupCollect(texts)
                    10 -> wechatRecharge(texts, wechatRechargeMethod)
                    11 -> wechatTransfer(texts)
                    else -> null
                }
            }
        }

        // 收款检测（对应 s() 中 n11 != 0 的分支）
        if (result == null && isReceipt) {
            result = wechatReceipt(texts)
        }

        // 红包检测（对应 s() 中 bl2 的分支）
        if (result == null && isRedPacket) {
            result = wechatRedPacket(texts)
            if (result == null) {
                result = wechatRedPacketFallback(texts)
            }
        }

        if (result != null) {
            wechatRechargeMethod = null
            wechatNeedRecognize = false
            wechatIsScanMode = false
            wechatIsMiniProgram = false
        }

        return result
    }

    /** 对应 p.x()：微信支付成功页提取 */
    private fun wechatPaySuccess(texts: List<String>, payMethod: String?): OcrBillInfo? {
        if (texts.isEmpty()) return null

        val bill = OcrBillInfo()
        bill.origin = "微信"

        // 对应 x() 开头的时间设置：如果 s.i() 为 true，使用扫码时间或当前时间
        if (wechatScanTime > 0) {
            bill.time = wechatScanTime
        }

        var n10 = 0 // 0=首次金额，1=非首次

        for (i in texts.indices) {
            val text = texts[i]

            // ── 单独的 ¥ 或 ￥ 符号 ──
            if ("￥" == text || "¥" == text) {
                if (i < texts.size - 1) {
                    val nextClean = texts[i + 1].replace(",", "")
                    if (isAmount(nextClean)) {
                        // 对应 block31-32：设置金额
                        if (n10 == 0 && bill.number.isNotEmpty()) {
                            // n10==0 且已有金额 → 跳过（对应 block33 条件前半段）
                        } else {
                            bill.number = nextClean
                            n10 = 1
                        }
                    }
                }
                continue
            }

            val cleanText = text.replace("¥", "").replace("￥", "").replace(",", "")

            // ── 包含 ¥ 或 ￥ 的文本 ──
            if (text.contains("¥") || text.contains("￥")) {
                if (isAmount(cleanText)) {
                    if (n10 == 0 && bill.number.isNotEmpty()) {
                        // 首次金额标记但已有金额 → 跳过金额，继续处理其他字段
                    } else {
                        bill.number = cleanText
                        if (n10 != 0 && i > 0) {
                            // 非首次：前一个文本作为备注（对应 block34）
                            bill.remark = texts[i - 1]
                            bill.shopName = bill.remark
                        }
                        n10 = 1
                    }
                }
                // 兜底：bill.number 为空时尝试提取
                if (bill.number.isEmpty() && isAmount(cleanText)) {
                    bill.number = cleanText
                } else if (bill.number.isEmpty() && texts.contains("订单状态") && isAmount(cleanText)) {
                    bill.number = cleanText
                }
                continue
            }

            // ── "优惠" ──
            if ("优惠" == text && i < texts.size - 1) {
                var nextText: String = texts[i + 1]
                // 如果下一个是"支付成功"，跳过看再下一个
                if ("支付成功" == nextText && i < texts.size - 2) {
                    nextText = texts[i + 2]
                }
                if (nextText.contains("¥")) {
                    val discountStr = nextText.substring(nextText.indexOf("¥"))
                    if (discountStr.isNotEmpty()) {
                        val discountClean = discountStr.replace(",", "").replace("¥", "")
                        if (isAmount(discountClean)) {
                            bill.discount = discountClean
                        }
                    }
                }
                continue
            }

            // ── "收款方" ──
            if ("收款方" == text && i < texts.size - 1) {
                bill.remark = texts[i + 1]
                bill.shopName = bill.remark
                continue
            }

            // ── "支付成功" / "付款成功" ──
            if (("支付成功" == text || "付款成功" == text) && i < texts.size - 1) {
                val nextText = texts[i + 1]
                // 对应 block37：如果下一个不包含 ¥，设为备注
                if (!nextText.contains("¥") && !nextText.contains("￥")) {
                    bill.remark = nextText
                    bill.shopName = bill.remark
                    // 特殊处理："待xxx确认收款" → "转账给xxx"
                    if (bill.remark.startsWith("待") && bill.remark.endsWith("确认收款")) {
                        bill.remark = "转账给" + bill.remark.substring(1, bill.remark.length - 4)
                        bill.shopName = bill.remark
                    }
                }
                continue
            }

            // ── "保存收款码" ──
            if ("保存收款码" == text) {
                bill.income = true
                n10 = 1
                continue
            }

            // ── 交易单号 / 商户单号 ──
            if ("交易单号" == text && i < texts.size - 1) {
                bill.transactionId = texts[i + 1]
                continue
            }
            if ("商户单号" == text && i < texts.size - 1) {
                bill.merchantOrderId = texts[i + 1]
                continue
            }
        }

        // 设置支付方式
        if (!payMethod.isNullOrEmpty()) {
            bill.asset = payMethod
        }

        // 零钱 → 微信钱包
        if ("零钱" == bill.asset) {
            bill.asset = "微信钱包"
        }

        // 验证：必须有金额和备注
        if (bill.remark.isNotEmpty() && bill.number.isNotEmpty()) {
            return bill
        }
        return null
    }

    /** 对应 p.y()：微信收款页提取 */
    private fun wechatReceipt(texts: List<String>): OcrBillInfo? {
        if (texts.isEmpty()) return null

        val bill = OcrBillInfo()
        bill.origin = "微信"
        bill.asset = "微信"
        var hasIncome = false

        for (i in texts.indices) {
            val text = texts[i]

            // "已收款" / "你已收款"
            if ((text.contains("已收款") || text.contains("你已收款")) && i < texts.size - 2) {
                val amountText = texts[i + 1]
                    .replace("¥", "").replace("￥", "").replace("元", "").replace(",", "")
                if (isAmount(amountText)) {
                    bill.number = amountText
                }
                if (text.contains("你已收款")) {
                    hasIncome = true
                }
            }

            // "收款时间"
            if (hasIncome && "收款时间" == text && i < texts.size - 1) {
                val time = parseTime(texts[i + 1])
                if (time != -1L) {
                    bill.time = time
                }
            }

            // "转账时间"
            if (!hasIncome && "转账时间" == text && i < texts.size - 1) {
                val time = parseTime(texts[i + 1])
                if (time != -1L) {
                    bill.time = time
                }
            }

            // "转账说明"
            if ("转账说明" == text && i < texts.size - 1) {
                bill.remark = texts[i + 1]
            }
        }

        if (bill.number.isNotEmpty()) {
            if (bill.remark.isEmpty()) {
                bill.remark = if (hasIncome) "微信收款" else "微信转账"
            }
            bill.income = hasIncome
            bill.shopName = bill.remark
            return bill
        }
        return null
    }

    /** 对应 p.A()：微信转账页提取 */
    private fun wechatTransfer(texts: List<String>): OcrBillInfo? {
        if (texts.isEmpty()) return null

        val bill = OcrBillInfo()
        bill.transfer = true
        bill.origin = "微信"

        // 判断转账类型
        if (containsText(texts, "零钱提现", false)) {
            bill.fromAsset = "微信钱包"
        } else if (containsText(texts, "零钱充值", false)) {
            bill.toAsset = "微信钱包"
        } else if (containsText(texts, "转入零钱通", false)) {
            bill.toAsset = "零钱通"
        }

        for (i in texts.indices) {
            val text = texts[i]

            // "提现金额"
            if ("提现金额" == text && i < texts.size - 1) {
                val amountText = texts[i + 1]
                    .replace("¥", "").replace("￥", "").replace(",", "")
                if (bill.number.isEmpty() && isAmount(amountText)) {
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                }
            }

            // "服务费"
            if ("服务费" == text && i < texts.size - 1) {
                val feeText = texts[i + 1]
                    .replace("¥", "").replace("￥", "").replace(",", "")
                if (isAmount(feeText)) {
                    bill.serviceCharge = Math.abs(toDouble(feeText))
                }
            }

            // 包含 "收入¥" 的文本
            if (bill.number.isEmpty() && text.contains("收入￥")) {
                val amountText = text.substring(text.indexOf("收入￥") + 3).replace(",", "")
                if (isAmount(amountText)) {
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                    bill.income = true
                    bill.remark = text.substring(0, text.indexOf("收入￥"))
                    bill.shopName = bill.remark
                }
            }

            // 下一个是"当前状态"的数字
            if (bill.number.isEmpty() && i < texts.size - 1 && "当前状态" == texts[i + 1]) {
                val amountText = text.replace(",", "")
                if (isAmount(amountText)) {
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                    if (i > 0) {
                        bill.remark = texts[i - 1]
                        bill.shopName = bill.remark
                    }
                }
            }

            // "转入方式"
            if (text.contains("转入方式")) {
                bill.fromAsset = text.replace("转入方式", "").trim()
            }

            // "提现银行"
            if ("提现银行" == text && i < texts.size - 1) {
                bill.toAsset = texts[i + 1]
            }

            // "支付方式"
            if ("支付方式" == text && i < texts.size - 1) {
                bill.fromAsset = texts[i + 1]
            }

            // 时间字段
            if (("申请时间" == text || "到账时间" == text || "充值时间" == text || "支付时间" == text) &&
                i < texts.size - 1
            ) {
                var time = parseTime(texts[i + 1])
                if (time == -1L) {
                    time = parseTime(texts[i + 1].replace("交易时间 ", ""))
                }
                if (time != -1L) {
                    bill.time = time
                }
            }

            // "交易时间" 内嵌在文本中
            if (text.contains("交易时间")) {
                val timeStr = text.replace("交易时间 ", "")
                val time = parseTime(timeStr)
                if (time != -1L) {
                    bill.time = time
                }
            }

            // 包含逗号和元的金额
            if (text.contains(",") && text.contains("元")) {
                val amountText = text.substring(
                    text.lastIndexOf(",") + 1,
                    text.length - 1
                )
                if (isAmount(amountText)) {
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                }
            }

            // "零钱充值-"
            if (text.contains("零钱充值-") && i < texts.size - 1) {
                val amountText = texts[i + 1].replace("元", "").replace(",", "")
                if (bill.number.isEmpty() && isAmount(amountText)) {
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                }
            }

            // "转入零钱通-"
            if (text.contains("转入零钱通-") && i < texts.size - 1) {
                val amountText = texts[i + 1].replace("元", "").replace(",", "")
                if (bill.number.isEmpty() && isAmount(amountText)) {
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                }
            }
        }

        // 零钱 → 微信钱包
        if ("零钱" == bill.asset) {
            bill.asset = "微信钱包"
        }

        if (bill.number.isNotEmpty()) {
            return bill
        }
        return null
    }

    /** 对应 p.z()：微信充值/提现页提取 */
    private fun wechatRecharge(texts: List<String>, rechargeMethod: String?): OcrBillInfo? {
        if (texts.isEmpty()) return null

        val bill = OcrBillInfo()
        bill.transfer = true
        bill.origin = "微信"

        // 判断转入/转出类型
        when {
            containsText(texts, "充值成功", true) -> {
                bill.fromAsset = rechargeMethod ?: ""
                bill.toAsset = "微信钱包"
            }
            containsText(texts, "已转入", true) -> {
                bill.fromAsset = "微信钱包"
                bill.toAsset = "零钱通"
            }
            containsText(texts, "已转出", true) -> {
                bill.fromAsset = "零钱通"
                bill.toAsset = "微信钱包"
            }
            containsText(texts, "零钱提现", true) -> {
                bill.fromAsset = "微信钱包"
                bill.toAsset = rechargeMethod ?: ""
            }
            containsText(texts, "转出金额", true) -> {
                bill.fromAsset = "零钱通"
                bill.toAsset = rechargeMethod ?: ""
            }
        }

        for (i in texts.indices) {
            val text = texts[i]

            // "充值成功" / "提现金额" / "转入成功"
            if (("充值成功" == text || "提现金额" == text || "转入成功" == text) && i < texts.size - 1) {
                val amountText = texts[i + 1]
                    .replace("¥", "").replace("￥", "").replace(",", "").replace("支出", "").replace("元", "")
                if (bill.number.isEmpty() && isAmount(amountText)) {
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                }
            }

            // "已转入" / "已转出" +2 位置的金额
            if (("已转入" == text || "已转出" == text) && i < texts.size - 2) {
                val amountText = texts[i + 2]
                    .replace("¥", "").replace("￥", "").replace(",", "")
                if (bill.number.isEmpty() && isAmount(amountText)) {
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                }
            }

            // "转出金额"
            if ("转出金额" == text && i < texts.size - 1) {
                val amountText = texts[i + 1]
                    .replace("¥", "").replace("￥", "").replace(",", "")
                if (bill.number.isEmpty() && isAmount(amountText)) {
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                }
            }

            // "成功转出XX元至XX"
            if (text.startsWith("成功转出") && text.contains("元至")) {
                val amountText = text.substring(
                    text.indexOf("成功转出") + 4,
                    text.indexOf("元至")
                ).replace(",", "")
                if (bill.number.isEmpty() && isAmount(amountText)) {
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                }
                val toAsset = text.substring(text.indexOf("元至") + 2).replace("。", "")
                bill.toAsset = if ("支付宝账户余额" == toAsset) "支付宝" else toAsset
            }

            // "交易成功" / "还款成功"
            if (("交易成功" == text || "还款成功" == text) && i > 0) {
                val amountText = texts[i - 1]
                    .replace("¥", "").replace("￥", "").replace(",", "").replace("支出", "").replace("元", "")
                if (bill.number.isEmpty() && isAmount(amountText)) {
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                }
            }

            // "成功转入XX元"
            if (text.contains("成功转入")) {
                val amountText = text.replace("成功转入", "").replace(",", "").replace("元", "")
                if (bill.number.isEmpty() && isAmount(amountText)) {
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                }
            }

            // "到账银行卡" / "提现到" / "还款到" / "到账账户"
            if (("到账银行卡" == text || "提现到" == text || "还款到" == text || "到账账户" == text) &&
                i < texts.size - 1
            ) {
                bill.toAsset = texts[i + 1]
            }

            // "付款方式" / "对方账户"
            if (("付款方式" == text || "对方账户" == text) && i < texts.size - 1) {
                bill.fromAsset = texts[i + 1]
            }

            // "服务费"
            if ("服务费" == text && i < texts.size - 1) {
                val feeText = texts[i + 1].replace("¥", "").replace(",", "")
                if (isAmount(feeText)) {
                    bill.serviceCharge = Math.abs(toDouble(feeText))
                }
            }

            // 时间字段
            if ("创建时间" == text && i < texts.size - 1) {
                val timeStr = texts[i + 1].replace("创建时间", "")
                val time = parseTime(timeStr)
                if (time != -1L) {
                    bill.time = time
                }
            }

            // 备注字段
            if (("提现说明" == text || "转账备注" == text || "充值说明" == text || "商品说明" == text) &&
                i < texts.size - 1
            ) {
                bill.remark = texts[i + 1]
            }

            // "转出说明"
            if ("转出说明" == text && i < texts.size - 1) {
                val desc = texts[i + 1]
                if (desc.contains("-")) {
                    desc.substring(0, desc.indexOf("-"))
                }
                if (desc.isNotEmpty() && desc.contains("转出到")) {
                    val parts = desc.split("转出到")
                    if (parts.size == 2) {
                        bill.fromAsset = parts[0].replace("转出说明", "")
                        bill.toAsset = parts[1]
                    }
                }
            }

            // "转入账户"
            if ("转入账户" == text && i < texts.size - 1) {
                bill.toAsset = texts[i + 1]
            }
        }

        // 清理资产名称
        if (bill.fromAsset.isNotEmpty()) bill.fromAsset = bill.fromAsset.trim()
        if (bill.toAsset.isNotEmpty()) bill.toAsset = bill.toAsset.trim()
        if ("账户余额" == bill.fromAsset || "余额" == bill.fromAsset) {
            bill.fromAsset = "支付宝"
        }
        if ("账户余额" == bill.toAsset || "余额" == bill.toAsset) {
            bill.toAsset = "支付宝"
        }

        if (bill.number.isNotEmpty()) {
            return bill
        }
        return null
    }

    /** 对应 p.w()：微信详情页提取（零钱通转出、信用卡还款等） */
    private fun wechatDetail(texts: List<String>): OcrBillInfo? {
        if (texts.isEmpty()) return null

        val bill = OcrBillInfo()
        bill.origin = "微信"

        for (i in texts.indices) {
            val text = texts[i]

            // 包含 ",支出" 和 "元"
            if (text.contains(",支出") && text.contains("元")) {
                val amountText = text.substring(
                    text.indexOf(",支出") + 3,
                    text.length - 1
                ).replace(",", "")
                val remark = text.substring(0, text.indexOf(",支出"))
                if (isAmount(amountText)) {
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                    bill.remark = remark
                    bill.shopName = remark
                    bill.income = false
                }
            }

            // 包含 ",收入" 和 "元"
            if (text.contains(",收入") && text.contains("元")) {
                val amountText = text.substring(
                    text.indexOf(",收入") + 3,
                    text.length - 1
                ).replace(",", "")
                val remark = text.substring(0, text.indexOf(",收入"))
                if (isAmount(amountText)) {
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                    bill.remark = remark
                    bill.shopName = remark
                    bill.income = true
                }
            }

            // "零钱通转出-到"
            if (text.contains("零钱通转出-到") && i < texts.size - 1) {
                var amountSource = text
                if (!text.contains("支出￥")) {
                    amountSource = texts[i + 1]
                }
                if (amountSource.contains("支出￥") && isAmount(
                        amountSource.substring(amountSource.indexOf("支出￥") + 3).replace(",", "")
                    )
                ) {
                    val amountText = amountSource.substring(amountSource.indexOf("支出￥") + 3).replace(",", "")
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                    bill.remark = "零钱通转出"
                    bill.transfer = true
                    bill.fromAsset = "零钱通"
                    bill.toAsset = "微信零钱"
                }
            }

            // "信用卡还款-"
            if (text.contains("信用卡还款-") && i < texts.size - 1) {
                val nextText = texts[i + 1]
                if (isAmount(nextText)) {
                    val num = Math.abs(toDouble(nextText))
                    bill.number = "$num"
                    bill.remark = "信用卡还款"
                    bill.transfer = true
                    bill.fromAsset = "零钱通"
                    bill.toAsset = text.replace("信用卡还款-", "").replace("还款", "")
                }
            }

            // 包含 "收入￥"
            if (bill.number.isEmpty() && text.contains("收入￥")) {
                val amountText = text.substring(text.indexOf("收入￥") + 3).replace(",", "")
                if (isAmount(amountText)) {
                    val num = Math.abs(toDouble(amountText))
                    bill.number = "$num"
                    bill.income = true
                    bill.remark = text.substring(0, text.indexOf("收入￥"))
                    bill.shopName = bill.remark
                }
            }

            // 包含 -/+/¥ 的金额
            if (bill.number.isEmpty() && (text.contains("-") || text.contains("+") || text.contains("¥"))) {
                val cleanText = text.replace("+", "").replace("-", "").replace("¥", "").replace(",", "")
                if (isAmount(cleanText) && toDouble(cleanText) > 0) {
                    val num = Math.abs(toDouble(cleanText))
                    bill.number = "$num"
                    if (i > 0) {
                        bill.remark = texts[i - 1]
                        bill.shopName = bill.remark
                    }
                    if (text.contains("+")) {
                        bill.income = true
                    }
                }
            }

            // 时间字段
            if (("支付时间" == text || "转账时间" == text || "收款时间" == text ||
                    "到账时间" == text || "退款时间" == text || "交易时间" == text) &&
                i < texts.size - 1
            ) {
                if ("收款时间" == text && !bill.income && bill.time != 0L) continue
                val nextText = texts[i + 1]
                var time = parseTime(nextText)
                if (time == -1L) {
                    time = parseTime(nextText.replace("交易时间 ", ""))
                }
                if (time != -1L) {
                    bill.time = time
                }
            }

            // "交易时间" 内嵌在文本中
            if (text.contains("交易时间")) {
                val timeStr = text.replace("交易时间 ", "")
                val time = parseTime(timeStr)
                if (time != -1L) {
                    bill.time = time
                }
            }

            // "优惠"
            if ("优惠" == text && i < texts.size - 1) {
                var nextText: String = texts[i + 1]
                if ("支付成功" == nextText && i < texts.size - 2) {
                    nextText = texts[i + 2]
                }
                if (nextText.contains("¥")) {
                    val discountText = nextText.substring(nextText.indexOf("¥"))
                        .replace(",", "").replace("¥", "")
                    if (isAmount(discountText)) {
                        bill.discount = discountText
                    }
                }
            }

            // 支付方式/收款方方式/退款方式
            if (("支付方式" == text || "收款方方式" == text || "退款方式" == text) && i < texts.size - 1) {
                bill.asset = texts[i + 1]
                if (bill.transfer) {
                    if ("零钱" == bill.asset) {
                        bill.fromAsset = "微信钱包"
                    } else {
                        bill.fromAsset = bill.asset
                    }
                }
            }

            // 备注字段
            if (("商品" == text || "商品名称" == text || "付款备注" == text ||
                    "转账说明" == text || "付款方留言" == text || "支付场景" == text) &&
                i < texts.size - 1
            ) {
                bill.remark = texts[i + 1]
            }

            // "收款账号"
            if (text.contains("收款账号") && bill.transfer) {
                bill.toAsset = text.replace("收款账号", "").trim()
            }

            // "收款方备注"
            if ("收款方备注" == text && i < texts.size - 1) {
                val nextText = texts[i + 1]
                if ("二维码收款" != nextText || bill.remark.isEmpty()) {
                    bill.remark = nextText
                }
                if ("二维码收款" != nextText || bill.asset.isEmpty()) {
                    bill.asset = "微信"
                }
            }

            // "已存入经营账户"
            if ("已存入经营账户" == text) {
                bill.asset = "微信经营账户"
            }

            // "已存入零钱"
            if ("已存入零钱" == text) {
                bill.asset = "微信钱包"
            }

            // "优惠" 包含 ¥
            if ("优惠" == text && i < texts.size - 1) {
                val nextText = texts[i + 1]
                if (nextText.contains("¥")) {
                    val parts = nextText.split("¥")
                    if (parts.isNotEmpty()) {
                        val discountText = parts.last().replace("¥", "").replace(",", "")
                        if (isAmount(discountText)) {
                            bill.discount = discountText
                        }
                    }
                }
            }
        }

        // 零钱 → 微信钱包
        if ("零钱" == bill.asset) {
            bill.asset = "微信钱包"
        }

        if (bill.remark.isNotEmpty() && bill.number.isNotEmpty()) {
            return bill
        }
        return null
    }

    /** 对应 p.v()：微信群收款提取 */
    private fun wechatGroupCollect(texts: List<String>): OcrBillInfo? {
        if (texts.isEmpty()) return null

        val bill = OcrBillInfo()
        bill.origin = "微信"
        bill.asset = "微信"

        for (i in texts.indices) {
            val text = texts[i]

            if (text.contains("￥")) {
                if (text.contains("已收到")) {
                    bill.income = true
                    val amountText = text.replace("已收到￥", "").replace(",", "")
                    if (isAmount(amountText)) {
                        bill.number = amountText
                        if (i > 0) {
                            bill.remark = texts[i - 1]
                            bill.shopName = bill.remark
                        }
                    }
                } else if (text.contains("已支付") && !texts.contains("你需支付")) {
                    bill.income = false
                    val amountText = text.replace("已支付￥", "").replace(",", "")
                    if (isAmount(amountText)) {
                        bill.number = amountText
                        if (i > 0) {
                            bill.remark = texts[i - 1]
                            bill.shopName = bill.remark
                        }
                    }
                } else if (text.contains("收到")) {
                    bill.income = true
                    val amountText = text.replace("收到￥", "").replace(",", "")
                    if (isAmount(amountText)) {
                        bill.number = amountText
                    }
                }
            } else if (text.contains("已收齐") && i > 0) {
                bill.remark = texts[i - 1]
                bill.shopName = bill.remark
            }

            if (bill.number.isNotEmpty()) return bill
        }
        return null
    }

    /** 对应 p.t()：微信红包提取（静态方法） */
    private fun wechatRedPacket(texts: List<String>): OcrBillInfo? {
        val bill = OcrBillInfo()
        bill.origin = "微信"

        for (i in texts.indices) {
            val text = texts[i]

            // "红包金额"
            if (text.contains("红包金额")) {
                val yuanIdx = text.indexOf("元")
                if (yuanIdx > 4) {
                    val amountText = text.substring(0, yuanIdx)
                        .replace("红包金额", "").replace(",", "")
                    if (isAmount(amountText)) {
                        bill.number = amountText
                        bill.remark = "发送微信红包"
                        bill.shopName = bill.remark
                        return bill
                    }
                }
            }

            // "个红包共"
            if (text.contains("个红包共")) {
                val yuanIdx = text.indexOf("元")
                if (yuanIdx > 4) {
                    val amountText = text.substring(
                        text.indexOf("个红包共") + 4,
                        yuanIdx
                    ).replace(",", "")
                    if (isAmount(amountText)) {
                        bill.number = amountText
                        bill.remark = "发送微信红包"
                        bill.shopName = bill.remark
                        return bill
                    }
                }
            }

            // "人已领取"
            if (text.contains("人已领取")) {
                val yuanIdx = text.indexOf("元")
                if (yuanIdx > 4) {
                    val amountText = text.substring(
                        text.indexOf("人已领取") + 6,
                        yuanIdx
                    ).replace(",", "")
                    if (isAmount(amountText)) {
                        bill.number = amountText
                        bill.remark = "发送微信红包"
                        bill.shopName = bill.remark
                        return bill
                    }
                }
            }

            // "个，共XX元"
            if (text.contains("个，共")) {
                val yuanIdx = text.indexOf("元")
                if (yuanIdx > 4) {
                    val amountText = text.substring(
                        text.lastIndexOf("/") + 1,
                        yuanIdx
                    ).replace(",", "")
                    if (isAmount(amountText)) {
                        bill.number = amountText
                        bill.remark = "发送微信红包"
                        bill.shopName = bill.remark
                        return bill
                    }
                }
            }
        }
        return null
    }

    /** 对应 p.r()：微信红包回退提取 */
    private fun wechatRedPacketFallback(texts: List<String>): OcrBillInfo? {
        if (texts.isEmpty()) return null

        val bill = OcrBillInfo()
        bill.origin = "微信"
        bill.asset = "微信"
        bill.income = true

        for (i in texts.indices) {
            val text = texts[i].replace("元", "").replace(",", "")
            if (!isAmount(text)) continue
            bill.number = text
            if (i < texts.size - 1 && (texts[i].contains("元") || texts[i + 1].contains("元"))) {
                if (i > 1 && texts[i - 2].contains("的红包")) {
                    bill.remark = texts[i - 2]
                    bill.shopName = bill.remark
                    return bill
                }
                if (i > 0 && texts[i - 1].contains("的红包")) {
                    bill.remark = texts[i - 1]
                    bill.shopName = bill.remark
                    return bill
                }
            }
        }
        return null
    }

    // ════════════════════════════════════════════════════════════════
    // 支付宝处理器（对应 b.java）
    // ════════════════════════════════════════════════════════════════

    // ── 支付宝状态变量 ──
    private var alipayPageType = 0           // this.a
    private var alipayNeedRecognize = false   // this.b
    private var alipayScanTime = 0L          // this.e
    private var alipayIsNfcMode = false       // this.f
    private val alipayDetailKeywords = listOf(  // this.g
        "提现说明", "转出说明", "余额转入", "充值说明",
        "网商银行转账", "还款成功", "余额宝-单次转入", "余额宝-转出到余额"
    )

    private fun alipayProcessWithTexts(
        service: MyAccessibilityService,
        className: String,
        texts: List<String>
    ): OcrBillInfo? {
        // className 状态管理（对应 b.w() 开头的匹配逻辑）
        if (className == "com.eg.android.AlipayGphone.AlipayLogin") {
            alipayPageType = 0
            alipayNeedRecognize = false
            alipayIsNfcMode = false
            return null
        }

        if (!alipayIsNfcMode &&
            (className == "com.alipay.android.msp.ui.views.MspContainerActivity" ||
                className == "com.alipay.android.msp.ui.views.MspUniRenderActivity" ||
                className == "com.alipay.android.phone.discovery.envelope.get.SnsCouponDetailActivity")
        ) {
            alipayNeedRecognize = true
            return null
        }

        when (className) {
            "com.alipay.android.phone.nfcpay.ui.NfcPayActivity",
            "com.alipay.android.phone.businesscommon.ucdp.nfc.activity.NResPageActivity" -> {
                alipayIsNfcMode = true
            }
            "com.alipay.mobile.scan.as.main.MainCaptureActivity",
            "com.alipay.mobile.onsitepay.merge.OnsitepayActivity" -> {
                alipayIsNfcMode = false
            }
        }

        if (texts.isEmpty()) return null
        val textMap = buildTextMap(texts)

        // NFC 模式检测
        if (!alipayIsNfcMode &&
            textMap.containsKey("碰一下，支付更便捷") &&
            textMap.containsKey("确认即付款")
        ) {
            alipayIsNfcMode = true
        }

        if (!alipayIsNfcMode &&
            className == "com.alipay.mobile.quinox.SchemeLauncherActivity" &&
            textMap.containsKey("使用说明")
        ) {
            alipayIsNfcMode = true
        }

        // 支付成功页检测
        if (textMap.containsKey("向商家付款") ||
            (!alipayIsNfcMode && textMap.containsKey("支付成功") && textMap.containsKey("交易方式")) ||
            (!alipayIsNfcMode &&
                (textMap.containsKey("支付成功") || textMap.containsKey("代付成功") || textMap.containsKey("转账成功")) &&
                (textMap.containsKey("付款方式") || textMap.containsKey("交易方式") || textMap.containsKey("完成")) &&
                !textMap.containsKey("账单详情")) ||
            (alipayIsNfcMode && textMap.containsKey("支付成功") &&
                !textMap.containsKey("账单详情") && !textMap.containsKey("支付时间") &&
                !textMap.containsKey("计算中") &&
                (textMap.containsKey("付款方式") || textMap.containsKey("交易方式")) &&
                (textMap.containsKey("¥") || textMap.containsKey("￥"))) ||
            (textMap.containsKey("充值成功") && textMap.containsKey("话费已到账"))
        ) {
            alipayPageType = 2
            alipayNeedRecognize = true
        }
        // 充值/提现页检测
        else if ((textMap.containsKey("结果详情") && textMap.containsKey("提现金额")) ||
            (textMap.containsKey("充值成功") &&
                !textMap.containsKey("充值号码") && !textMap.containsKey("手机充值") &&
                !textMap.containsKey("话费已到账")) ||
            textMap.containsKey("提现成功") ||
            textMap.containsKey("转入成功") ||
            textMap.containsKey("转出成功") ||
            (textMap.containsKey("创建时间") &&
                !textMap.containsKey("充值号码") && !textMap.containsKey("手机充值") &&
                !textMap.containsKey("话费已到账") &&
                hashMapContainsAnyKey(textMap, alipayDetailKeywords))
        ) {
            alipayPageType = 9
            alipayNeedRecognize = true
        }
        // 转账页检测
        else if (textMap.containsKey("还款信用卡") && textMap.containsKey("银行处理中")) {
            alipayPageType = 10
            alipayNeedRecognize = true
        }
        // 详情页检测
        else if ((textMap.containsKey("账单详情") && !textMap.containsKey("交易详情") && !textMap.containsKey("商品图片")) ||
            (textMap.containsKey("账单详情") && textMap.containsKey("退款成功")) ||
            textMap.containsKey("余额明细详情") ||
            (textMap.containsKey("交易成功") && !textMap.containsKey("交易详情") &&
                !textMap.containsKey("商品图片") && textMap.containsKey("付款方式") &&
                textMap.containsKey("支付时间")) ||
            ((textMap.containsKey("账单管理") || textMap.containsKey("账单详情")) &&
                (textMap.containsKey("交易详情") || textMap.containsKey("商品图片")))
        ) {
            alipayPageType = 6
            alipayNeedRecognize = true
            if (textMap.containsKey("交易详情")) {
                val idx = texts.indexOf("交易详情")
                if (idx > 0 && idx < texts.size - 1) {
                    val next = texts[idx + 1]
                    if ("更多" == next || "推荐服务" == next || "服务推荐" == next) {
                        alipayNeedRecognize = false
                    }
                }
            }
        }
        // 收款页检测
        else if (textMap.containsKey("收款时间") && textMap.containsKey("已收款")) {
            alipayPageType = 7
            alipayNeedRecognize = true
        }
        // 红包详情检测
        else if ((textMap.containsKey("红包详情") || textMap.containsKey("领取成功，已存入余额")) &&
            textMap.containsKey("查看红包记录")
        ) {
            alipayPageType = 6
            alipayNeedRecognize = true
        }
        // 收益页检测
        else if (textMap.containsKey("开始计算收益") && textMap.containsKey("收益到账") &&
            textMap.containsKey("付款方式")
        ) {
            alipayPageType = 9
            alipayNeedRecognize = true
        }

        // 记录扫码时间
        if (textMap.containsKey("优先使用此付款方式") && textMap.containsKey("向商家付款") ||
            textMap.containsKey("确认付款") && textMap.containsKey("使用密码") ||
            textMap.containsKey("更改付款方式") && textMap.containsKey("极速付款") ||
            textMap.containsKey("请输入支付密码") && textMap.containsKey("使用指纹") ||
            textMap.containsKey("扫描二维码/条形码/小程序码") && textMap.containsKey("收付款") ||
            alipayIsNfcMode
        ) {
            alipayScanTime = System.currentTimeMillis()
        }

        if (!alipayNeedRecognize) return null

        var shouldProcess = true
        if (alipayPageType == 2) {
            val hasSuccessKeyword = textMap.containsKey("支付成功") || textMap.containsKey("转账成功") ||
                textMap.containsKey("代付成功") || textMap.containsKey("交易成功") ||
                textMap.containsKey("充值成功") || textMap.containsKey("还款成功") ||
                textMap.containsKey("退款成功") || textMap.containsKey("赔偿成功") ||
                textMap.containsKey("自动扣款成功") || textMap.containsKey("有退款") ||
                textMap.containsKey("已全额退款") || textMap.containsKey("缴费中") ||
                textMap.containsKey("派遣中") || containsText(texts, "亲情卡付款成功", false) ||
                containsText(texts, "付款成功", false)
            if (!hasSuccessKeyword) {
                shouldProcess = false
            }
        }

        var result: OcrBillInfo? = null

        if (shouldProcess) {
            result = when (alipayPageType) {
                2, 6, 7 -> alipayPaySuccess(texts)
                9 -> alipayRecharge(texts)
                10 -> alipayTransfer(texts)
                11 -> alipayTransferDetail(texts)
                else -> null
            }
        }

        if (result == null && textMap.containsKey("转账成功")) {
            result = alipayTransfer(texts)
        }

        if (result == null && alipayPageType == 2 &&
            (textMap.containsKey("已收款") || textMap.containsKey("资金待入账") || textMap.containsKey("你已收款"))
        ) {
            result = alipayPaySuccess(texts)
        }

        if (result == null) {
            val isRedPacket = textMap.containsKey("支付宝红包") || textMap.containsKey("查看红包记录") ||
                textMap.containsKey("查看红包") || containsText(texts, "红包金额", false) ||
                containsText(texts, "个红包共", false) || containsText(texts, "发的红包", false) ||
                containsText(texts, "领取成功", false) || textMap.containsKey("查看红包")
            if (isRedPacket) {
                result = alipayRedPacket(texts)
                if (result == null) {
                    result = alipayRedPacketFallback(texts)
                }
            }
        }

        if (result != null) {
            alipayNeedRecognize = false
            alipayIsNfcMode = false
        }
        return result
    }

    /** 对应 b.r()：支付宝支付成功页提取 */
    private fun alipayPaySuccess(texts: List<String>): OcrBillInfo? {
        if (texts.isEmpty()) return null

        val bill = OcrBillInfo()
        bill.origin = "支付宝"

        // 记录扫码时间
        if (alipayScanTime > 0) {
            bill.time = alipayScanTime
        }

        for (i in texts.indices) {
            val text = texts[i]

            // "收款方"
            if ("收款方" == text && i < texts.size - 1) {
                bill.remark = texts[i + 1]
                bill.shopName = bill.remark
            }

            // a==2 时的 "支付成功"/"充值成功"/"代付成功"/"转账成功"
            if (alipayPageType == 2 &&
                ("支付成功" == text || "充值成功" == text || "代付成功" == text || "转账成功" == text) &&
                i < texts.size - 2
            ) {
                val idx1 = i + 1
                val idx2 = i + 2
                val clean1 = texts[idx1]
                    .replace("¥", "").replace("￥", "").replace("元", "").replace(",", "").replace("支出", "")
                val clean2 = texts[idx2]
                    .replace("¥", "").replace("￥", "").replace("元", "").replace(",", "").replace("支出", "")

                if (isAmount(clean1)) {
                    bill.number = clean1
                }

                if (!isAmount(clean1) && isAmount(clean2)) {
                    bill.number = clean2
                    if (alipayIsNfcMode) {
                        if (("￥" == texts[idx1] || "¥" == texts[idx1]) && i < texts.size - 3) {
                            bill.remark = texts[i + 3]
                        } else {
                            bill.remark = clean1
                        }
                        bill.shopName = bill.remark
                    }
                } else if ("付款方式" != clean2) {
                    if (!alipayIsNfcMode) {
                        // 查找 ¥ 符号
                        val yenIdx = findIndex(texts, "¥", false)
                        val yenClean = if (yenIdx > 0) {
                            texts[yenIdx].replace("¥", "").replace("￥", "").replace(",", "")
                        } else null

                        var remark = clean2
                        if (yenIdx > 1 && yenClean != null && isAmount(yenClean)) {
                            remark = texts[yenIdx - 1]
                            if (isAmount(bill.number)) {
                                bill.discount = formatDiscount(toDouble(yenClean) - toDouble(bill.number))
                            }
                        }
                        bill.remark = remark
                        bill.shopName = remark
                    }
                }
            }

            // "有退款"/"自动扣款成功"/"已全额退款" 等成功关键词
            val successKeywords = listOf(
                "有退款", "自动扣款成功", "已全额退款",
                "支付成功", "充值成功", "退款成功", "赔偿成功",
                "交易成功", "还款成功", "代付成功", "派遣中",
                "领取中", "等待对方发货", "等待确认收货",
                "交易成功(代付成功)", "退回成功",
                "支付成功(代付成功)", "缴费中"
            )
            val containsSuccessKeyword = successKeywords.any { text.contains(it) } ||
                text.contains("支付宝小荷包付款成功") || text.contains("已退款(") ||
                text.contains("亲情卡付款成功") || text.contains("免密支付成功") ||
                text.contains("自动续费成功") || text.contains("充值成功") ||
                text.contains("收款成功") || text == "等待对方确认收货"

            if (containsSuccessKeyword && i > 0) {
                val cleanPrev = texts[i - 1]
                    .replace("¥", "").replace("￥", "").replace(",", "")
                    .replace("+", "").replace("元", "").replace("-", "").replace("支出", "")

                if (isAmount(cleanPrev)) {
                    bill.number = cleanPrev
                    val prevText = texts[i - 1]
                    if (prevText.contains("+") || (!prevText.contains("-") && !prevText.contains("支出"))) {
                        // 收入
                    } else if ("退回成功" == text) {
                        bill.income = true
                    }
                    if (text.contains("扣款成功")) {
                        bill.income = false
                    }
                    if (i > 1) {
                        bill.remark = texts[i - 2]
                    }
                    if ("退款成功" == text) {
                        bill.remark = "退款-来自${bill.remark}"
                        bill.income = true
                    }
                    bill.shopName = bill.remark
                } else if (cleanPrev == "NaN") {
                    bill.number = "0"
                }
            }

            // 包含 ¥/￥/-/+/支出 的金额
            if (bill.number.isEmpty() &&
                (text.contains("¥") || text.contains("￥") || text.contains("-") ||
                    text.contains("+") || text.contains("支出"))
            ) {
                val cleanText = text
                    .replace("¥", "").replace("￥", "").replace(",", "")
                    .replace("+", "").replace("支出", "").replace("元", "")

                var amountText = cleanText
                var sourceText = text

                if (!isAmount(cleanText) && i < texts.size - 1) {
                    sourceText = texts[i + 1]
                    amountText = sourceText.replace("¥", "").replace("￥", "").replace(",", "").replace("+", "")
                }

                if (isAmount(amountText)) {
                    bill.number = "${Math.abs(toDouble(amountText))}"
                    if (sourceText.contains("+")) {
                        bill.remark = "支付宝收款"
                        bill.shopName = bill.remark
                        bill.income = true
                    }
                }

                if (bill.remark.isEmpty() && i > 0) {
                    bill.remark = texts[i - 1]
                    bill.shopName = bill.remark
                }
            }

            // "收款理由"
            if ("收款理由" == text && i < texts.size - 1) {
                bill.remark = texts[i + 1].replaceFirst("收款理由", "")
            }

            // "付款方式"/"付款信息"/"交易方式"/"退款方式"
            if (("付款方式" == text || "付款信息" == text || "交易方式" == text || "退款方式" == text) &&
                i < texts.size - 1
            ) {
                bill.asset = texts[i + 1]
                if ("帮助" == bill.asset && i < texts.size - 2) {
                    bill.asset = texts[i + 2]
                }
                if (bill.asset.isNotEmpty() && bill.asset.contains("亲情卡") && bill.income) {
                    bill.income = false
                }
            }

            // "创建时间"
            if ("创建时间" == text && i < texts.size - 1) {
                val timeStr = texts[i + 1].replace("创建时间", "")
                val time = parseTime(timeStr)
                if (time != -1L) {
                    bill.time = time
                }
            }

            // "支付时间"
            if ("支付时间" == text && i < texts.size - 1) {
                val timeStr = texts[i + 1].replace("支付时间", "")
                if (timeStr.contains("-")) {
                    val time = parseTime(timeStr)
                    if (time != -1L) {
                        bill.time = time
                    }
                } else if (timeStr.contains("年")) {
                    val time = parseTime(timeStr)
                    if (time != -1L) {
                        bill.time = time
                    }
                }
            }

            // "收款时间"
            if ("收款时间" == text && i < texts.size - 1) {
                val time = parseTime(texts[i + 1])
                if (time != -1L) {
                    bill.time = time
                    bill.income = true
                }
            }

            // a==7 时的 "订单金额"
            if (alipayPageType == 7 && "订单金额" == text && i < texts.size - 1) {
                val cleanText = texts[i + 1]
                    .replace("¥", "").replace("￥", "").replace(",", "")
                    .replace("+", "").replace("元", "").replace("-", "")
                if (isAmount(cleanText)) {
                    bill.number = cleanText
                }
            }

            // a==7 时的 "花呗收钱服务费"/"平台服务费"
            if (alipayPageType == 7 &&
                ("花呗收钱服务费" == text || "平台服务费" == text) &&
                i < texts.size - 1
            ) {
                val cleanText = texts[i + 1]
                    .replace("¥", "").replace("￥", "").replace(",", "")
                    .replace("+", "").replace("元", "").replace("-", "")
                if (isAmount(cleanText)) {
                    bill.number = "${toDouble(bill.number) - toDouble(cleanText)}"
                }
            }

            // "商品说明"
            if ("商品说明" == text && i < texts.size - 1) {
                bill.remark = texts[i + 1].replaceFirst("商品说明", "")
                if (bill.remark.contains("余额宝") && bill.remark.contains("收益发放")) {
                    bill.asset = "余额宝"
                }
            }

            // "订单详情"
            if ("订单详情" == text && i < texts.size - 1) {
                val idx = i + 1
                if ("支付成功" != texts[idx]) {
                    bill.remark = texts[idx]
                }
                if ("商品图片" == bill.remark) {
                    bill.remark = texts[i + 2]
                }
            }

            // "交易详情"
            if ("交易详情" == text && i < texts.size - 1) {
                val nextText = texts[i + 1]
                if (nextText.isNotEmpty() && "更多" != nextText &&
                    "推荐服务" != nextText && "服务推荐" != nextText &&
                    i < texts.size - 2
                ) {
                    bill.remark = texts[i + 2]
                }
            }

            // "缴费说明"/"充值说明"/"付款备注"/"红包说明"/"理由"/"转账备注"
            val remarkFields = listOf(
                "缴费说明", "充值说明", "付款备注", "红包说明", "理由", "转账备注"
            )
            for (field in remarkFields) {
                if (field == text && i < texts.size - 1) {
                    bill.remark = texts[i + 1].replaceFirst(field, "")
                }
            }

            // "转账备注" 特殊处理
            if ("转账备注" == text && i < texts.size - 1) {
                val idx = i + 1
                if ("转账" == texts[idx]) {
                    bill.remark = if (bill.income) {
                        "收到${bill.shopName}转账"
                    } else {
                        "转账给${bill.shopName}"
                    }
                } else {
                    bill.remark = texts[idx]
                }
                bill.shopName = if (bill.income) {
                    "收到${bill.shopName}转账"
                } else {
                    "转账给${bill.shopName}"
                }
            }

            // 优惠相关
            if (bill.discount.isEmpty() &&
                (alipayIsNfcMode && text.startsWith("-¥") ||
                    text == "碰一下支付立减" || text == "支付宝随机立减" ||
                    text == "碰一下立减" || text == "碰一下共减" ||
                    text == "视频红包" || text == "优惠") &&
                i < texts.size - 1
            ) {
                val nextText = texts[i + 1]
                var discountSource = nextText
                if ("￥" == nextText && i < texts.size - 2) {
                    discountSource = texts[i + 2]
                }
                val cleanDiscount = discountSource
                    .replace("¥", "").replace("￥", "").replace(",", "")
                    .replace("+", "").replace("元", "").replace("-", "")
                if (isAmount(cleanDiscount)) {
                    bill.discount = cleanDiscount
                }
            }

            // a!=7 时的 "订单金额"
            if (alipayPageType != 7 && "订单金额" == text && i < texts.size - 1) {
                val cleanText = texts[i + 1]
                    .replace("¥", "").replace("￥", "").replace(",", "")
                    .replace("+", "").replace("元", "").replace("-", "")
                if (isAmount(cleanText) && bill.number.isNotEmpty()) {
                    bill.discount = formatDiscount(toDouble(cleanText) - toDouble(bill.number))
                }
            }

            // ── 交易单号 / 商户单号（支付宝）──
            if (("交易订单号" == text || "交易单号" == text) && i < texts.size - 1) {
                bill.transactionId = texts[i + 1]
                continue
            }
            if (("商户订单号" == text || "商户单号" == text) && i < texts.size - 1) {
                bill.merchantOrderId = texts[i + 1]
                continue
            }
        }

        // 默认备注
        if (bill.number.isNotEmpty() && bill.remark.isEmpty()) {
            if (bill.income) {
                bill.remark = "支付宝收款"
                bill.shopName = bill.remark
            } else {
                bill.remark = "支付宝付款"
                bill.shopName = bill.remark
            }
        }

        // 收入时默认资产
        if (bill.income && bill.asset.isEmpty()) {
            bill.asset = "支付宝"
        }

        // 清理资产名称
        if (bill.asset.isNotEmpty()) bill.asset = bill.asset.trim()
        if ("账户余额" == bill.asset || "余额" == bill.asset || "可用余额" == bill.asset) {
            bill.asset = "支付宝"
        }

        if (bill.remark.isNotEmpty() && bill.number.isNotEmpty()) {
            return bill
        }
        return null
    }

    /** 对应 b.v()：支付宝转账页提取 */
    private fun alipayTransfer(texts: List<String>): OcrBillInfo? {
        if (texts.isEmpty()) return null

        val bill = OcrBillInfo()
        bill.transfer = true
        bill.origin = "支付宝"

        for (i in texts.indices) {
            val text = texts[i]

            // "实付金额"
            if ("实付金额" == text && i < texts.size - 2) {
                val cleanText = texts[i + 2]
                    .replace("￥", "").replace(",", "").replace("支出", "").replace("元", "")
                if (isAmount(cleanText)) {
                    bill.number = "${Math.abs(toDouble(cleanText))}"
                }
            }

            // "还款信用卡"
            if ("还款信用卡" == text && i < texts.size - 1) {
                val cardText = texts[i + 1]
                val toAsset = if (cardText.contains("(") && cardText.contains(")")) {
                    cardText.substring(cardText.indexOf("(") + 1, cardText.lastIndexOf(")")).replace(".", "")
                } else {
                    cardText.replace(".", "")
                }
                bill.toAsset = toAsset
                bill.fromAsset = "支付宝"
            }
        }

        if (bill.fromAsset.isNotEmpty()) bill.fromAsset = bill.fromAsset.trim()
        if (bill.toAsset.isNotEmpty()) bill.toAsset = bill.toAsset.trim()
        if ("账户余额" == bill.fromAsset || "余额" == bill.fromAsset) {
            bill.fromAsset = "支付宝"
        }
        if ("账户余额" == bill.toAsset || "余额" == bill.toAsset) {
            bill.toAsset = "支付宝"
        }

        if (bill.number.isNotEmpty()) {
            return bill
        }
        return null
    }

    /** 对应 b.u()：支付宝充值/提现页提取 */
    private fun alipayRecharge(texts: List<String>): OcrBillInfo? {
        if (texts.isEmpty()) return null

        val bill = OcrBillInfo()
        bill.transfer = true
        bill.origin = "支付宝"

        // 判断转入/转出类型
        when {
            containsText(texts, "充值成功", true) -> bill.toAsset = "支付宝"
            containsText(texts, "余额转入", true) -> bill.toAsset = "余额宝"
            containsText(texts, "充值说明", true) -> bill.toAsset = "支付宝"
            containsText(texts, "提现说明", true) || containsText(texts, "转出说明", true) ||
                containsText(texts, "余额转入", true) -> bill.fromAsset = "支付宝"
            containsText(texts, "网商银行转账", true) -> bill.toAsset = "网商银行余利宝"
            containsText(texts, "余额宝-单次转入", true) -> bill.toAsset = "余额宝"
            containsText(texts, "余额宝-转出到余额", true) -> {
                bill.fromAsset = "余额宝"
                bill.toAsset = "支付宝"
            }
            containsText(texts, "开始计算收益", true) && containsText(texts, "转入成功", false) -> {
                bill.toAsset = "余额宝"
            }
            containsText(texts, "转出成功", true) -> bill.fromAsset = "余额宝"
        }

        for (i in texts.indices) {
            val text = texts[i]

            // "提现金额"/"充值成功"/"转入成功"
            if (("提现金额" == text || "充值成功" == text || "转入成功" == text) && i < texts.size - 1) {
                val cleanText = texts[i + 1]
                    .replace("￥", "").replace(",", "").replace("支出", "").replace("元", "")
                if (bill.number.isEmpty() && isAmount(cleanText)) {
                    bill.number = "${Math.abs(toDouble(cleanText))}"
                }
            }

            // "提现金额¥XX" 内嵌在文本中
            if (text.contains("提现金额￥")) {
                val cleanText = text.replace("提现金额￥", "").replace(",", "").replace("支出", "").replace("元", "")
                if (bill.number.isEmpty() && isAmount(cleanText)) {
                    bill.number = "${Math.abs(toDouble(cleanText))}"
                }
            }

            // "转出成功" + ￥ 在 +2 位置
            if ("转出成功" == text && i < texts.size - 2 && "￥" == texts[i + 1]) {
                val cleanText = texts[i + 2].replace(",", "")
                if (bill.number.isEmpty() && isAmount(cleanText)) {
                    bill.number = "${Math.abs(toDouble(cleanText))}"
                }
            }

            // "成功转出XX元至XX"
            if (text.startsWith("成功转出") && text.contains("元至")) {
                val amountText = text.substring(
                    text.indexOf("成功转出") + 4,
                    text.indexOf("元至")
                ).replace(",", "")
                if (bill.number.isEmpty() && isAmount(amountText)) {
                    bill.number = "${Math.abs(toDouble(amountText))}"
                }
                val toAsset = text.substring(text.indexOf("元至") + 2).replace("。", "")
                bill.toAsset = if ("支付宝账户余额" == toAsset) "支付宝" else toAsset
            }

            // "交易成功"/"还款成功"
            if (("交易成功" == text || "还款成功" == text) && i > 0) {
                val cleanText = texts[i - 1]
                    .replace("￥", "").replace(",", "").replace("支出", "").replace("元", "")
                if (bill.number.isEmpty() && isAmount(cleanText)) {
                    bill.number = "${Math.abs(toDouble(cleanText))}"
                }
            }

            // "成功转入XX元"
            if (text.contains("成功转入")) {
                val cleanText = text.replace("成功转入", "").replace(",", "").replace("元", "")
                if (bill.number.isEmpty() && isAmount(cleanText)) {
                    bill.number = "${Math.abs(toDouble(cleanText))}"
                }
            }

            // "到账银行卡"/"提现到"/"还款到"/"到账账户"
            if (("到账银行卡" == text || "提现到" == text || "还款到" == text || "到账账户" == text) &&
                i < texts.size - 1
            ) {
                bill.toAsset = texts[i + 1]
            }

            // "付款方式"/"对方账户"
            if (("付款方式" == text || "对方账户" == text) && i < texts.size - 1) {
                bill.fromAsset = texts[i + 1]
            }

            // "服务费"
            if ("服务费" == text && i < texts.size - 1) {
                val cleanText = texts[i + 1].replace("￥", "").replace(",", "")
                if (isAmount(cleanText)) {
                    bill.serviceCharge = Math.abs(toDouble(cleanText))
                }
            }

            // "创建时间"
            if ("创建时间" == text && i < texts.size - 1) {
                val timeStr = texts[i + 1].replace("创建时间", "")
                val time = parseTime(timeStr)
                if (time != -1L) {
                    bill.time = time
                }
            }

            // 备注字段
            if (("提现说明" == text || "转账备注" == text || "充值说明" == text || "商品说明" == text) &&
                i < texts.size - 1
            ) {
                bill.remark = texts[i + 1]
            }

            // "转出说明"
            if ("转出说明" == text && i < texts.size - 1) {
                val desc = texts[i + 1]
                if (desc.contains("-")) {
                    desc.substring(0, desc.indexOf("-"))
                }
                if (desc.isNotEmpty() && desc.contains("转出到")) {
                    val parts = desc.split("转出到")
                    if (parts.size == 2) {
                        bill.fromAsset = parts[0].replace("转出说明", "")
                        bill.toAsset = parts[1]
                    }
                }
            }

            // "转入账户"
            if ("转入账户" == text && i < texts.size - 1) {
                bill.toAsset = texts[i + 1]
            }
        }

        // 清理资产名称
        if (bill.fromAsset.isNotEmpty()) bill.fromAsset = bill.fromAsset.trim()
        if (bill.toAsset.isNotEmpty()) bill.toAsset = bill.toAsset.trim()
        if ("账户余额" == bill.fromAsset || "余额" == bill.fromAsset) {
            bill.fromAsset = "支付宝"
        }
        if ("账户余额" == bill.toAsset || "余额" == bill.toAsset) {
            bill.toAsset = "支付宝"
        }

        if (bill.number.isNotEmpty()) {
            return bill
        }
        return null
    }

    /** 对应 b.z()：支付宝红包提取 */
    private fun alipayRedPacket(texts: List<String>): OcrBillInfo? {
        val bill = OcrBillInfo()
        bill.origin = "支付宝"

        for (i in texts.indices) {
            val text = texts[i]

            // "红包金额"
            if (text.contains("红包金额")) {
                val yuanIdx = text.indexOf("元")
                if (yuanIdx > 4) {
                    val amountText = text.substring(0, yuanIdx)
                        .replace("红包金额", "").replace(",", "")
                    if (isAmount(amountText)) {
                        bill.number = amountText
                        bill.remark = "发送支付宝红包"
                        bill.shopName = bill.remark
                        return bill
                    }
                }
            }

            // "个红包共"
            if (text.contains("个红包共")) {
                val yuanIdx = text.indexOf("元")
                if (yuanIdx > 4) {
                    val amountText = text.substring(
                        text.indexOf("个红包共") + 4,
                        yuanIdx
                    ).replace(",", "")
                    if (isAmount(amountText)) {
                        bill.number = amountText
                        bill.remark = "发送支付宝红包"
                        bill.shopName = bill.remark
                        return bill
                    }
                }
            }

            // "已领取XX元"
            if (text.contains("已领取")) {
                val yuanIdx = text.indexOf("元")
                if (yuanIdx > 4) {
                    val amountText = text.substring(
                        text.lastIndexOf("/") + 1,
                        yuanIdx
                    ).replace(",", "")
                    if (isAmount(amountText)) {
                        bill.number = amountText
                        bill.remark = "发送支付宝红包"
                        bill.shopName = bill.remark
                        return bill
                    }
                }
            }
        }
        return null
    }

    /** 对应 b.x()：支付宝红包回退提取 */
    private fun alipayRedPacketFallback(texts: List<String>): OcrBillInfo? {
        if (texts.isEmpty()) return null

        val bill = OcrBillInfo()
        bill.origin = "支付宝"
        bill.asset = "支付宝"
        bill.income = true

        for (i in texts.indices) {
            val text = texts[i].replace("元", "").replace(",", "")
            if (!isAmount(text)) continue
            bill.number = text
            if (i < texts.size - 1 && (texts[i].contains("元") || texts[i + 1].contains("元"))) {
                if (i > 1 && texts[i - 2].contains("的红包")) {
                    bill.remark = texts[i - 2].replace("送你", "")
                    bill.shopName = bill.remark
                    return bill
                }
                if (i > 0 && texts[i - 1].contains("的红包")) {
                    bill.remark = texts[i - 1].replace("送你", "")
                    bill.shopName = bill.remark
                    return bill
                }
            }
        }
        return null
    }

    /** 支付宝转账详情页提取 */
    private fun alipayTransferDetail(texts: List<String>): OcrBillInfo? {
        // 转账详情页逻辑与转账页基本相同
        return alipayTransfer(texts)
    }

    // ════════════════════════════════════════════════════════════════
    // 主入口
    // ════════════════════════════════════════════════════════════════

    /**
     * 主入口：直接从无障碍节点树提取文本，搜索关键词识别账单
     * 不做包名匹配，对应原版 SelectToSpeakService 的处理逻辑
     */
    fun recognizeNow(service: MyAccessibilityService): OcrBillInfo? {
        val root = service.rootInActiveWindow ?: return null
        val packageName = root.packageName?.toString() ?: return null
        val className = MyAccessibilityService.lastClassName ?: ""

        return when (packageName) {
            "com.tencent.mm" -> {
                // 微信处理器使用 text + contentDescription（对应 c.m() → c.n()）
                val texts = extractTextsAndDescriptions(root)
                if (texts.isEmpty()) return null
                wechatProcessWithTexts(service, className, texts)
            }
            "com.eg.android.AlipayGphone" -> {
                // 支付宝处理器仅使用 text（对应 c.g() → c.i()）
                val texts = extractTextsOnly(root)
                if (texts.isEmpty()) return null
                alipayProcessWithTexts(service, className, texts)
            }
            else -> null
        }
    }

    /**
     * 自动识别入口：使用已提取的文本进行识别（避免重复提取）
     * 用于无障碍事件自动触发的场景
     */
    fun recognizeNowWithTexts(
        service: MyAccessibilityService,
        packageName: String,
        texts: List<String>
    ): OcrBillInfo? {
        if (texts.isEmpty()) return null
        val className = MyAccessibilityService.lastClassName ?: ""

        return when (packageName) {
            "com.tencent.mm" -> wechatProcessWithTexts(service, className, texts)
            "com.eg.android.AlipayGphone" -> alipayProcessWithTexts(service, className, texts)
            else -> null
        }
    }

    /** 重置所有状态（用于测试或手动重置） */
    fun resetState() {
        wechatPageType = 0
        wechatNeedRecognize = false
        wechatPayMethod = null
        wechatRechargeMethod = null
        wechatInRecognizeMode = false
        wechatIsGroupCollect = false
        wechatIsMiniProgram = false
        wechatScanTime = 0
        wechatIsScanMode = false

        alipayPageType = 0
        alipayNeedRecognize = false
        alipayScanTime = 0
        alipayIsNfcMode = false
    }
}
