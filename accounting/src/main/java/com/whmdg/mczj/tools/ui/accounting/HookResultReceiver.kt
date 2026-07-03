package com.whmdg.mczj.tools.ui.accounting

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 接收 Xposed hook 层发送的微信账单广播。
 *
 * 在 Manifest 中注册，运行在主应用进程。
 * 收到账单数据后存入内存队列，供悬浮窗读取展示。
 */
class HookResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HOOK_BILL) return

        val json = intent.getStringExtra(EXTRA_BILL_JSON) ?: return
        val result = parseBillJson(json) ?: return

        // 去重
        val hash = simpleHash("${result.amount}_${result.merchant}_${result.time}")
        if (recentHashes.contains(hash)) return
        recentHashes.add(hash)
        while (recentHashes.size > MAX_HASHES) recentHashes.removeFirst()

        // 存入队列
        pendingResults.add(result)
        while (pendingResults.size > MAX_RESULTS) pendingResults.removeAt(0)

        // 通知悬浮窗刷新
        onNewResult?.invoke(result)
    }

    private fun parseBillJson(json: String): OcrBillResult? {
        return try {
            val obj = JSONObject(json)
            OcrBillResult(
                id = UUID.randomUUID().toString(),
                type = obj.optString("type", "支出"),
                amount = obj.optDouble("amount", 0.0),
                merchant = obj.optString("merchant", "未知商户"),
                time = obj.optLong("time", System.currentTimeMillis()),
                sourceApp = obj.optString("sourceApp", "com.tencent.mm"),
                rawText = obj.optString("rawText", ""),
                confidence = obj.optDouble("confidence", 0.9).toFloat(),
                matchedRule = obj.optString("matchedRule", "Hook")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun simpleHash(input: String): String {
        var h = 0L
        for (c in input) h = h * 31 + c.code
        return h.toString(16)
    }

    companion object {
        const val ACTION_HOOK_BILL = "com.whmdg.mczj.tools.ACTION_HOOK_BILL"
        const val EXTRA_BILL_JSON = "bill_json"

        private const val MAX_RESULTS = 20
        private const val MAX_HASHES = 100

        /** 待展示的账单队列（线程安全） */
        val pendingResults = CopyOnWriteArrayList<OcrBillResult>()

        private val recentHashes = ArrayDeque<String>()

        /** 新账单到达时的回调（悬浮窗注册） */
        var onNewResult: ((OcrBillResult) -> Unit)? = null

        /** 获取最近 N 条结果（不移除） */
        fun getRecent(count: Int): List<OcrBillResult> {
            val size = pendingResults.size
            return pendingResults.subList(maxOf(0, size - count), size).toList()
        }

        /** 清空所有结果 */
        fun clear() {
            pendingResults.clear()
        }
    }
}
