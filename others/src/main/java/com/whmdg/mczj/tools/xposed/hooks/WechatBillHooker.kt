package com.whmdg.mczj.tools.xposed.hooks

import android.app.Application
import android.app.Instrumentation
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 微信账单 Hook 入口。
 *
 * 由 XposedInit.onPackageLoaded("com.tencent.mm") 调用，
 * 负责注册所有微信相关的 hook 点。
 */
object WechatBillHooker {

    /** 跨进程通信时使用本应用包名 */
    const val APP_PACKAGE = "com.whmdg.mczj.tools"

    /** 在微信进程中缓存的 Application 实例 */
    @Volatile
    var application: Application? = null
        private set

    fun register(module: XposedModule, param: PackageLoadedParam) {
        try {
            // 1. Hook Instrumentation.callApplicationOnCreate 获取可靠的 Application 实例
            hookApplication(module)
            // 2. 禁用 Tinker 热更新框架（否则 hook 可能失效）
            disableTinker(module, param.classLoader)
            // 3. Hook WebView.evaluateJavascript
            WebViewBillHooker.hook(module, param.classLoader)
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
}
