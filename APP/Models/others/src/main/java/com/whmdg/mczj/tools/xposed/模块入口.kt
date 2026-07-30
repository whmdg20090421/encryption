package com.whmdg.mczj.tools.xposed

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class 模块入口 : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "艨艟: 模块已加载, 进程=${param.processName}, systemServer=${param.isSystemServer}")
        设置激活属性()
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        log(Log.INFO, TAG, "艨艟: 包已加载: ${param.packageName}, first=${param.isFirstPackage}")
        if (param.packageName == "net.defensezone3.ultra") {
            if (isScopeEnabled("net.defensezone3.ultra")) {
                DefenseZone3AdHook.handlePackageLoaded(this, param)
            } else {
                log(Log.INFO, TAG, "艨艟: DZ3 作用域未启用，跳过注入")
            }
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, TAG, "艨艟: system_server 已加载")
        设置激活属性()
    }

    private fun 设置激活属性() {
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val setMethod = clazz.getMethod("set", String::class.java, String::class.java)
            setMethod.invoke(null, 激活属性名, "${System.currentTimeMillis()}")
        } catch (_: Throwable) {
        }
    }

    private fun isScopeEnabled(packageName: String): Boolean {
        return try {
            val prefs = getSharedPreferences("hook_prefs", Context.MODE_PRIVATE)
            prefs.getBoolean("${packageName}_SCOPE", false)
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "读取 hook 作用域开关失败: ${t.message}")
            false
        }
    }

    companion object {
        private const val TAG = "MCZJ_Xposed"
        private const val 激活属性名 = "mczj.xposed.active"
    }
}
