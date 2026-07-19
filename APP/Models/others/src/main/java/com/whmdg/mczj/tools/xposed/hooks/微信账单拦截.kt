package com.whmdg.mczj.tools.xposed.hooks

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 微信账单钩子入口。
 *
 * 由 模块入口.onPackageLoaded("com.tencent.mm") 调用，
 * 负责注册所有微信相关的钩子点。
 *
 * 拦截微信 WebView 的 evaluateJavascript，获取支付账单详情。
 * 当用户在微信中打开账单详情页时，WebView 会通过 evaluateJavascript 调用
 * WeixinJSBridge._handleMessageFromWeixin()，其中包含完整的账单 JSON。
 */
object 微信账单拦截 {

    /** 跨进程通信时使用本应用包名 */
    const val 应用包名 = "com.whmdg.mczj.tools"

    /** 在微信进程中缓存的 Application 实例 */
    @Volatile
    var 应用实例: Application? = null
        private set

    private const val ACTION_拦截账单 = "com.whmdg.mczj.tools.ACTION_HOOK_BILL"
    private const val 额外账单JSON = "bill_json"

    /** 去重队列 */
    private val 最近哈希 = ConcurrentLinkedQueue<String>()
    private const val 最大哈希数 = 100

    fun 注册(module: XposedModule, param: PackageLoadedParam) {
        try {
            // 1. 挂钩 Instrumentation.callApplicationOnCreate 获取可靠的 Application 实例
            挂应用钩子(module)
            // 2. 禁用 Tinker 热更新框架（否则钩子可能失效）
            禁用Tinker(module, param.classLoader)
            // 3. 拦截 WebView.evaluateJavascript
            挂网页钩子(module, param.classLoader)
            module.log("艨艟: 微信账单钩子已注册")
        } catch (e: Throwable) {
            module.log("艨艟: 微信账单钩子注册失败: ${e.message}")
        }
    }

    /**
     * 挂钩 Instrumentation.callApplicationOnCreate 获取 Application 实例。
     *
     * onPackageLoaded 时 Application 尚未创建，
     * ActivityThread.currentApplication() 返回 null。
     * 通过挂钩生命周期方法确保在 Application 创建后拿到实例。
     */
    private fun 挂应用钩子(module: XposedModule) {
        try {
            val method = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate",
                Application::class.java
            )
            module.hook(method, object : XposedInterface.Hooker<java.lang.reflect.Method> {
                override fun before(callback: XposedInterface.BeforeHookCallback<java.lang.reflect.Method>) {}
                override fun after(callback: XposedInterface.AfterHookCallback<java.lang.reflect.Method>) {
                    if (应用实例 != null) return
                    val app = callback.getArgs()[0] as? Application ?: return
                    应用实例 = app
                    module.log("艨艟: 获取到 Application: ${app.packageName}")
                }
            })
        } catch (e: Throwable) {
            module.log("艨艟: 挂钩 Application 失败: ${e.message}")
        }
    }

    /**
     * 禁用腾讯 Tinker 热更新框架。
     *
     * Tinker 会在运行时替换 ClassLoader 和类定义，导致在 onPackageLoaded 时
     * 注册的钩子在 Tinker 加载后失效。参考 AutoAccounting 的处理方式。
     */
    private fun 禁用Tinker(module: XposedModule, classLoader: ClassLoader) {
        try {
            val tinkerHelper = Class.forName(
                "com.tencent.tinker.lib.tinker.TinkerApplicationHelper",
                false, classLoader
            )
            val applicationLike = tinkerHelper
                .getMethod("getTinkerApplicationLike")
                .invoke(null) ?: return

            val shareTinkerInternals = Class.forName(
                "com.tencent.tinker.loader.shareutil.ShareTinkerInternals",
                false, classLoader
            )

            val app = applicationLike.javaClass
                .getMethod("getApplication")
                .invoke(applicationLike)

            // 杀死其他进程
            shareTinkerInternals
                .getMethod("killAllOtherProcess", android.content.Context::class.java)
                .invoke(null, app)
            // 清除补丁
            shareTinkerInternals
                .getMethod("cleanPatch", applicationLike.javaClass)
                .invoke(null, applicationLike)
            // 禁用 Tinker
            shareTinkerInternals
                .getMethod("setTinkerDisableWithSharedPreferences", android.content.Context::class.java)
                .invoke(null, app)

            module.log("艨艟: Tinker 已禁用")
        } catch (e: Throwable) {
            // Tinker 不存在或处理失败，不影响后续钩子
            module.log("艨艟: Tinker 处理跳过: ${e.message}")
        }
    }

    /**
     * 拦截 WebView.evaluateJavascript，获取支付账单详情。
     */
    private fun 挂网页钩子(module: XposedModule, classLoader: ClassLoader) {
        val webViewClass = classLoader.loadClass("com.tencent.xweb.WebView")
        val evaluateMethod = webViewClass.getDeclaredMethod(
            "evaluateJavascript",
            String::class.java,
            android.webkit.ValueCallback::class.java
        )

        module.hook(evaluateMethod, object : XposedInterface.Hooker<java.lang.reflect.Method> {
            override fun before(callback: XposedInterface.BeforeHookCallback<java.lang.reflect.Method>) {}

            override fun after(callback: XposedInterface.AfterHookCallback<java.lang.reflect.Method>) {
                val js = callback.getArgs()[0] as? String ?: return
                try {
                    拦截JS调用(js, module)
                } catch (_: Throwable) {}
            }
        })
    }

    private fun 拦截JS调用(js: String, module: XposedModule) {
        if (!js.contains("nativeWXPayCgiTunnel:ok")) return

        val start = js.indexOf("javascript:WeixinJSBridge._handleMessageFromWeixin(")
        if (start < 0) return
        val jsonStr = js.substring(
            start + "javascript:WeixinJSBridge._handleMessageFromWeixin(".length,
            js.length - 1
        )

        val jsonObject = JSONObject(jsonStr)
        val params = jsonObject
            .getJSONObject("__json_message")
            .getJSONObject("__params")
        val respbuf = params.getString("respbuf")
        val respJson = JSONObject(respbuf)

        // 去重
        val header = respJson.optJSONObject("header")
        val fee = header?.optString("fee", "") ?: ""
        val orderNo = 提取单号(respJson)
        val hash = 简单哈希("${fee}_${orderNo}")
        if (最近哈希.contains(hash)) return
        添加哈希(hash)

        // 解析并发送
        val result = 解析账单(respJson) ?: return
        module.log("艨艟: 拦截到微信账单: ${result["amount"]} ${result["merchant"]}")
        发送广播(result)
    }

    // ── 账单解析 ──

    /**
     * 解析微信支付 WebView 返回的账单详情 JSON。
     *
     * 输入：respbuf 解析后的 JSONObject（含 header + preview 数组）
     * 输出：Map<String, String> 格式的账单数据（可直接通过 Intent 传输）
     */
    private fun 解析账单(respJson: JSONObject): Map<String, String>? {
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
                val valueStr = 提取字符串值(item)

                when {
                    label.contains("当前状态") -> status = valueStr
                    label.contains("说明") || label.contains("备注") -> description = valueStr
                    label.contains("时间") && !label.contains("到账") -> timestamp = valueStr
                    label.contains("单号") -> orderNo = valueStr
                }
            }
        }

        // 解析金额：从 fee 字段（格式 "+0.01" / "-25.00" / "¥100.00"）
        val amount = 解析金额(fee)
        if (amount == 0.0 && nickname.isEmpty()) return null

        // 判断收支类型
        val type = 判断类型(nickname, description, fee, status)

        // 解析时间
        val timeMs = 解析时间(timestamp)

        // 商户名：从 nickname 提取（去掉 "-来自微信支付" 后缀）
        val merchant = 提取商户(nickname)

        return mapOf(
            "type" to type,
            "amount" to amount.toString(),
            "merchant" to merchant,
            "time" to timeMs.toString(),
            "sourceApp" to "com.tencent.mm",
            "rawText" to respJson.toString().take(500),
            "confidence" to "0.9",
            "matchedRule" to "钩子",
            "orderNo" to orderNo,
            "status" to status,
            "description" to description
        )
    }

    private fun 提取字符串值(item: JSONObject): String {
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

    private fun 解析金额(fee: String): Double {
        val cleaned = fee.replace("[¥￥+,，]".toRegex(), "").trim()
        return cleaned.toDoubleOrNull() ?: 0.0
    }

    private fun 判断类型(
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

    private fun 解析时间(timestamp: String): Long {
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

    private fun 提取商户(nickname: String): String {
        // 去掉常见后缀
        return nickname
            .replace("-来自微信支付", "")
            .replace("来自微信支付", "")
            .replace("-来自零钱", "")
            .trim()
            .ifEmpty { "未知商户" }
    }

    // ── 工具方法 ──

    private fun 提取单号(respJson: JSONObject): String {
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

    private fun 发送广播(result: Map<String, String>) {
        try {
            val context = 获取上下文() ?: return
            val json = JSONObject(result as Map<*, *>).toString()
            val intent = Intent(ACTION_拦截账单).apply {
                // 必须硬编码我们应用的包名，不能用 context.packageName（那是微信的包名）
                setPackage(应用包名)
                putExtra(额外账单JSON, json)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.发送广播(intent)
        } catch (_: Throwable) {}
    }

    private fun 获取上下文(): Context? {
        // 优先使用通过挂钩 Instrumentation 获取的 Application 实例
        应用实例?.let { return it }
        // 回退到 ActivityThread.currentApplication()
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val method = activityThread.getMethod("currentApplication")
            method.invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    private fun 简单哈希(input: String): String {
        var h = 0L
        for (c in input) h = h * 31 + c.code
        return h.toString(16)
    }

    private fun 添加哈希(hash: String) {
        最近哈希.add(hash)
        while (最近哈希.size > 最大哈希数) {
            最近哈希.poll()
        }
    }
}
