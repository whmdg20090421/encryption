package com.whmdg.mczj.tools.xposed.hooks

import android.content.Context
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Hook 微信 WebView 的 evaluateJavascript，拦截支付账单详情。
 *
 * 当用户在微信中打开账单详情页时，WebView 会通过 evaluateJavascript 调用
 * WeixinJSBridge._handleMessageFromWeixin()，其中包含完整的账单 JSON。
 */
object WebViewBillHooker {

    private const val ACTION_HOOK_BILL = "com.whmdg.mczj.tools.ACTION_HOOK_BILL"
    private const val EXTRA_BILL_JSON = "bill_json"

    /** MD5 去重队列（30 秒内相同数据不重复发送） */
    private val recentHashes = ConcurrentLinkedQueue<String>()
    private const val MAX_HASHES = 100

    fun hook(classLoader: ClassLoader) {
        val webViewClass = XposedHelpers.findClass(
            "com.tencent.xweb.WebView", classLoader
        )

        XposedHelpers.findAndHookMethod(
            webViewClass,
            "evaluateJavascript",
            String::class.java,
            android.webkit.ValueCallback::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val js = param.args[0] as? String ?: return
                        handleEvaluateJavascript(js, param)
                    } catch (_: Throwable) {}
                }
            }
        )
    }

    private fun handleEvaluateJavascript(js: String, param: MethodHookParam) {
        if (!js.contains("nativeWXPayCgiTunnel:ok")) return

        val jsonStr = js.substring(
            "javascript:WeixinJSBridge._handleMessageFromWeixin(".length,
            js.length - 1
        )

        val jsonObject = JSONObject(jsonStr)
        val params = jsonObject
            .getJSONObject("__json_message")
            .getJSONObject("__params")
        val respbuf = params.getString("respbuf")
        val respJson = JSONObject(respbuf)

        // 去重：用 header.fee + preview 中的转账单号
        val header = respJson.optJSONObject("header")
        val fee = header?.optString("fee", "") ?: ""
        val orderNo = extractOrderNo(respJson)
        val hash = simpleHash("${fee}_${orderNo}")
        if (recentHashes.contains(hash)) return
        addHash(hash)

        // 解析并发送
        val result = BillHookParser.parse(respJson) ?: return
        sendBroadcast(param.thisObject, result)
    }

    private fun extractOrderNo(respJson: JSONObject): String {
        val preview = respJson.optJSONArray("preview") ?: return ""
        for (i in 0 until preview.length()) {
            val item = preview.getJSONObject(i)
            val label = item.optJSONObject("label")?.optString("name", "") ?: ""
            if (label.contains("单号")) {
                val value = item.opt("value")
                return when (value) {
                    is org.json.JSONArray -> value.optJSONObject(0)?.optString("name", "") ?: ""
                    is String -> value
                    else -> ""
                }
            }
        }
        return ""
    }

    private fun sendBroadcast(source: Any, result: Map<String, String>) {
        try {
            val context = getContext(source) ?: return
            val json = JSONObject(result as Map<*, *>).toString()
            val intent = Intent(ACTION_HOOK_BILL).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_BILL_JSON, json)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
        } catch (_: Throwable) {}
    }

    private fun getContext(source: Any): Context? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val method = activityThread.getMethod("currentApplication")
            method.invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    private fun simpleHash(input: String): String {
        var h = 0L
        for (c in input) {
            h = h * 31 + c.code
        }
        return h.toString(16)
    }

    private fun addHash(hash: String) {
        recentHashes.add(hash)
        while (recentHashes.size > MAX_HASHES) {
            recentHashes.poll()
        }
    }
}
