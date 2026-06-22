package com.whmdg.mczj.tools.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.File

class XposedInit : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, "艨艟", "模块已加载: 进程=${param.processName}, systemServer=${param.isSystemServer}")
        writeActiveFlag()
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        log(Log.INFO, "艨艟", "包已加载: ${param.packageName}, first=${param.isFirstPackage}")
        writeActiveFlag()

        // TODO: 在此处添加 hook 逻辑
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log(Log.INFO, "艨艟", "包就绪: ${param.packageName}")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, "艨艟", "system_server 启动中")
        writeActiveFlag()
    }

    private fun writeActiveFlag() {
        try {
            val flagFile = File(ACTIVE_FLAG_PATH)
            flagFile.parentFile?.mkdirs()
            flagFile.writeText("${System.currentTimeMillis()}")
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val ACTIVE_FLAG_PATH = "/data/local/tmp/mczj_xposed_active.flag"

        fun isModuleActive(): Boolean {
            try {
                Class.forName("io.github.libxposed.api.XposedContext")
                return true
            } catch (_: Throwable) {
            }
            return try {
                File(ACTIVE_FLAG_PATH).exists()
            } catch (_: Throwable) {
                false
            }
        }
    }
}
