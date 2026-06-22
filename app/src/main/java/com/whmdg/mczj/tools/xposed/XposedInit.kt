package com.whmdg.mczj.tools.xposed

import io.github.libxposed.api.XposedContext
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerLoadedParam

class XposedInit(base: XposedContext, param: ModuleLoadedParam) : XposedModule(base, param) {

    init {
        log("艨艟: 模块已加载, 进程=${param.processName}, systemServer=${param.isSystemServer}")
        setActiveProperty()
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        log("艨艟: 包已加载: ${param.packageName}, first=${param.isFirstPackage}")

        // TODO: 在此处添加 hook 逻辑
    }

    override fun onSystemServerLoaded(param: SystemServerLoadedParam) {
        log("艨艟: system_server 已加载")
        setActiveProperty()
    }

    private fun setActiveProperty() {
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val setMethod = clazz.getMethod("set", String::class.java, String::class.java)
            setMethod.invoke(null, PROP_ACTIVE, "${System.currentTimeMillis()}")
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val PROP_ACTIVE = "mczj.xposed.active"
    }
}
