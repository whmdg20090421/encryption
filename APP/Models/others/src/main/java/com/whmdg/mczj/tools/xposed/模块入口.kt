package com.whmdg.mczj.tools.xposed

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

    companion object {
        private const val TAG = "MCZJ_Xposed"
        private const val 激活属性名 = "mczj.xposed.active"
    }
}
