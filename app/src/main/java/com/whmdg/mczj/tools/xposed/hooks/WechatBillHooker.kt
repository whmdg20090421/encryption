package com.whmdg.mczj.tools.xposed.hooks

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 微信账单 Hook 入口。
 *
 * 由 XposedInit.onPackageLoaded("com.tencent.mm") 调用，
 * 负责注册所有微信相关的 hook 点。
 */
object WechatBillHooker {

    fun register(module: XposedModule, param: PackageLoadedParam) {
        try {
            WebViewBillHooker.hook(param.classLoader)
            module.log("艨艟: 微信账单 Hook 已注册")
        } catch (e: Throwable) {
            module.log("艨艟: 微信账单 Hook 注册失败: ${e.message}")
        }
    }
}
