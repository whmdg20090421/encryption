package com.whmdg.mczj.tools.util

object XposedDetector {

    private const val PROP_ACTIVE = "mczj.xposed.active"

    fun isModuleActive(): Boolean {
        // 方法一：反射检查 libxposed API 类是否被框架注入当前进程
        try {
            Class.forName("io.github.libxposed.api.XposedInterface")
            return true
        } catch (_: Throwable) {
        }
        // 方法二：读取 system_server 设置的系统属性
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java, String::class.java)
            val value = getMethod.invoke(null, PROP_ACTIVE, "") as String
            value.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }
}
