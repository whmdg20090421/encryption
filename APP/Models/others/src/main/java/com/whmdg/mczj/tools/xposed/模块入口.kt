package com.whmdg.mczj.tools.xposed

import io.github.libxposed.api.XposedContext
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerLoadedParam

class 模块入口(base: XposedContext, param: ModuleLoadedParam) : XposedModule(base, param) {

    init {
        log("艨艟: 模块已加载, 进程=${param.processName}, systemServer=${param.isSystemServer}")
        设置激活属性()
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        log("艨艟: 包已加载: ${param.packageName}, first=${param.isFirstPackage}")
    }

    override fun onSystemServerLoaded(param: SystemServerLoadedParam) {
        log("艨艟: system_server 已加载")
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
        private const val 激活属性名 = "mczj.xposed.active"
    }
}
