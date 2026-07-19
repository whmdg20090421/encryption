package com.whmdg.mczj.tools.xposed.hooks

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 微信账单 Hook 入口。
 *
 * 由 XposedInit.onPackageLoaded("com.tencent.mm") 调用，
 * 负责注册所有微信相关的 hook 点。
 *
 * Hook 微信 WebView 的 evaluateJavascript，拦截支付账单详情。
 * 当用户在微信中打开账单详情页时，WebView 会通过 evaluateJavascript 调用
 * WeixinJSBridge._handleMessageFromWeixin()，其中包含完整的账单 JSON。
 */
object WechatBillHooker {

    /** 跨进程通信时使用本应用包名 */
    const val APP_PACKAGE = "com.whmdg.mczj.tools"

    /** 在微信进程中缓存的 Application 实例 */
    @Volatile
    var application: Application? = null
        private set

    private const val ACTION_HOOK_BILL = "com.whmdg.mczj.tools.ACTION_HOOK_BILL"
    private const val EXTRA_BILL_JSON = "bill_json"

    /** MD5 去重队列 */
    private val recentHashes = ConcurrentLinkedQueue<String>()
    private const val MAX_HASHES = 100

    fun register(module: XposedModule, param: PackageLoadedParam) {
        try {
            // 1. Hook Instrumentation.callApplicationOnCreate 获取可靠的 Application 实例
            hookApplication(module)
            // 2. 禁用 Tinker 热更新框架（否则 hook 可能失效）
            disableTinker(module, param.classLoader)
            // 3. Hook WebView.evaluateJavascript
            hookWebView(module, param.classLoader)
            module.log("艨艟: 微信账单 Hook 已注册")
        } catch (e: Throwable) {
            module.log("艨艟: 微信账单 Hook 注册失败: ${e.message}")
        }
    }

    /**
     * Hook Instrumentation.callApplicationOnCreate 获取 Application 实例。
     *
     * onPackageLoaded 时 Application 尚未创建，
     * ActivityThread.currentApplication() 返回 null。
     * 通过 Hook 生命周期方法确保在 Application 创建后拿到实例。
     */
    private fun hookApplication(module: XposedModule) {
        try {
            val method = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate",
                Application::class.java
            )
            module.hook(method, object : XposedInterface.Hooker<java.lang.reflect.Method> {
                override fun before(callback: XposedInterface.BeforeHookCallback<java.lang.reflect.Method>) {}
                override fun after(callback: XposedInterface.AfterHookCallback<java.lang.reflect.Method>) {
                    if (application != null) return
                    val app = callback.getArgs()[0] as? Application ?: return
                    application = app
                    module.log("艨艟: 获取到 Application: ${app.packageName}")
                }
            })
        } catch (e: Throwable) {
            module.log("艨艟: Hook Application 失败: ${e.message}")
        }
    }

    /**
     * 禁用腾讯 Tinker 热更新框架。
     *
     * Tinker 会在运行时替换 ClassLoader 和类定义，导致在 onPackageLoaded 时
     * 注册的 hook 在 Tinker 加载后失效。参考 AutoAccounting 的处理方式。
     */
    private fun disableTinker(module: XposedModule, classLoader: ClassLoader) {
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
            // Tinker 不存在或处理失败，不影响后续 hook
            module.log("艨艟: Tinker 处理跳过: ${e.message}")
        }
    }

    /**
     * Hook WebView.evaluateJavascript，拦截支付账单详情。
     */
    private fun hookWebView(module: XposedModule, classLoader: ClassLoader) {
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
                    handleEvaluateJavascript(js, module)
                } catch (_: Throwable) {}
            }
        })
    }

    private fun handleEvaluateJavascript(js: String, module: XposedModule) {
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
        val orderNo = extractOrderNo(respJson)
        val hash = simpleHash("${fee}_${orderNo}")
        if (recentHashes.contains(hash)) return
        addHash(hash)

        // 解析并发送
        val result = BillHookParser.parse(respJson) ?: return
        module.log("艨艟: Hook 到微信账单: ${result["amount"]} ${result["merchant"]}")
        sendBroadcast(result)
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

    private fun sendBroadcast(result: Map<String, String>) {
        try {
            val context = getContext() ?: return
            val json = JSONObject(result as Map<*, *>).toString()
            val intent = Intent(ACTION_HOOK_BILL).apply {
                // 必须硬编码我们应用的包名，不能用 context.packageName（那是微信的包名）
                setPackage(APP_PACKAGE)
                putExtra(EXTRA_BILL_JSON, json)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
        } catch (_: Throwable) {}
    }

    private fun getContext(): Context? {
        // 优先使用通过 Hook Instrumentation 获取的 Application 实例
        application?.let { return it }
        // 回退到 ActivityThread.currentApplication()
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
        for (c in input) h = h * 31 + c.code
        return h.toString(16)
    }

    private fun addHash(hash: String) {
        recentHashes.add(hash)
        while (recentHashes.size > MAX_HASHES) {
            recentHashes.poll()
        }
    }
}
