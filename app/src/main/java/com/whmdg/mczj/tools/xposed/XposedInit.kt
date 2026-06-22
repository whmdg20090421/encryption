package com.whmdg.mczj.tools.xposed

import android.content.Context
import io.github.libxposed.api.XposedContext
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.io.File

class XposedInit(base: XposedContext, param: ModuleLoadedParam) : XposedModule(base, param) {

    init {
        writeActiveFlag()
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        log("[艨艟] 已加载: ${param.packageName} (进程: ${param.processName})")
        writeActiveFlag()

        // TODO: 在此处添加 hook 逻辑
    }

    private fun writeActiveFlag() {
        try {
            // 写入模块自身数据目录（通过模块 context 的 filesDir）
            val flagFile = File(filesDir, FLAG_FILE_NAME)
            flagFile.writeText("${System.currentTimeMillis()}")
            // 同时写入固定路径，供跨进程检测
            val fixedFile = File(FIXED_FLAG_PATH)
            fixedFile.parentFile?.mkdirs()
            fixedFile.writeText("${System.currentTimeMillis()}")
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val FLAG_FILE_NAME = "xposed_active.flag"
        private const val FIXED_FLAG_PATH = "/data/local/tmp/mczj_xposed_active.flag"

        /**
         * 检测 Xposed 模块是否生效
         * 1. 检查 libxposed API 类是否可用（Vector/LSPosed 框架注入）
         * 2. 检查固定路径标记文件（模块在任意进程加载时写入）
         */
        fun isModuleActive(context: Context): Boolean {
            // 方法 1：检查 libxposed API 是否在当前进程可用
            try {
                Class.forName("io.github.libxposed.api.XposedContext")
                return true
            } catch (_: Throwable) {
            }

            // 方法 2：检查标记文件
            return try {
                File(FIXED_FLAG_PATH).exists()
            } catch (_: Throwable) {
                false
            }
        }
    }
}
