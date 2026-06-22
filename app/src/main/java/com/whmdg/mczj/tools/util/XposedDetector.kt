package com.whmdg.mczj.tools.util

import java.io.File

/**
 * Xposed 模块生效检测（纯 Java，不依赖 libxposed API）
 * 避免 compileOnly 依赖在运行时引发 NoClassDefFoundError
 */
object XposedDetector {

    private const val ACTIVE_FLAG_PATH = "/data/local/tmp/mczj_xposed_active.flag"

    /**
     * 检测 Xposed 模块是否生效
     * 1. 反射检查 libxposed API 类是否被框架注入当前进程
     * 2. 检查标记文件（模块在任意进程加载时写入）
     */
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
