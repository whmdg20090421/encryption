package com.whmdg.mczj.tools.xposed

import android.util.Log
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.dataChannel
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@InjectYukiHookWithXposed(
    isUsingXposedModuleStatus = true,
    modulePackageName = "com.whmdg.mczj.tools"
)
class 模块入口 : XposedModule(), IYukiHookXposedInit {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "艨艟: 模块已加载, 进程=${param.processName}, systemServer=${param.isSystemServer}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        Log.i(TAG, "艨艟: 包已加载: ${param.packageName}, first=${param.isFirstPackage}")
        if (param.packageName == "android") {
            hookReportEvent()
            registerDataChannel()
        }
    }

    private fun hookReportEvent() {
        try {
            val ussClass = Class.forName(
                "com.android.server.usage.UsageStatsService",
                false,
                ClassLoader.getSystemClassLoader()
            )
            XposedBridge.hookAllMethods(ussClass, "reportEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val event = param.args[0] ?: return
                        val cls = event.javaClass

                        fun intField(name: String): Int {
                            val f = cls.getDeclaredField(name)
                            f.isAccessible = true
                            return f.getInt(event)
                        }
                        fun longField(name: String): Long {
                            val f = cls.getDeclaredField(name)
                            f.isAccessible = true
                            return f.getLong(event)
                        }
                        fun strField(name: String): String {
                            val f = cls.getDeclaredField(name)
                            f.isAccessible = true
                            return f.get(event) as? String ?: ""
                        }

                        val eventType = intField("mEventType")
                        val packageName = strField("mPackageName")
                        val timeStamp = longField("mTimeStamp")
                        val className = try { strField("mClass") } catch (_: Exception) { "" }

                        val todayStart = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        if (timeStamp < todayStart) return

                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                        val line = "type=$eventType pkg=$packageName ts=$timeStamp time=${sdf.format(Date(timeStamp))} cls=$className"

                        synchronized(eventBuffer) {
                            if (currentDate != todayStart) {
                                eventBuffer.clear()
                                currentDate = todayStart
                            }
                            eventBuffer.add(line)
                        }
                    } catch (_: Throwable) {}
                }
            })
            Log.i(TAG, "艨艟: reportEvent hook installed")
        } catch (e: Throwable) {
            Log.e(TAG, "艨艟: hook failed: ${e.message}")
        }
    }

    private fun getSystemContext(): android.content.Context {
        // system_server 中 currentApplication() 返回 null，需多级 fallback
        val at = Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread")
            .invoke(null) ?: throw IllegalStateException("ActivityThread 为 null")

        // 优先 getApplication()，再 getSystemContext()
        val app = try {
            at.javaClass.getMethod("getApplication").invoke(at) as? android.content.Context
        } catch (_: Throwable) { null }
        if (app != null) return app

        val sysCtx = try {
            at.javaClass.getMethod("getSystemContext").invoke(at) as? android.content.Context
        } catch (_: Throwable) { null }
        if (sysCtx != null) return sysCtx

        // 最后尝试 currentApplication()
        return (Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? android.content.Context)
            ?: throw IllegalStateException("无法获取 Context: getApplication=null, getSystemContext=null, currentApplication=null")
    }

    private fun registerDataChannel() {
        try {
            val ctx = getSystemContext()
            ctx.dataChannel(MODULE_PKG).wait<String>(KEY_REQUEST) {
                val response = if (hookError.isNotEmpty()) {
                    "$ERROR_PREFIX$hookError"
                } else {
                    synchronized(eventBuffer) { eventBuffer.joinToString("\n") }
                }
                ctx.dataChannel(MODULE_PKG).put(KEY_RESPONSE, response)
            }
            Log.i(TAG, "艨艟: data channel registered, ctx=${ctx.javaClass.name}")
        } catch (e: Throwable) {
            val msg = "data channel 注册失败: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "艨艟: $msg")
            hookError = msg
        }
    }

    override fun onInit() {}
    override fun onHook() {}

    companion object {
        private const val TAG = "MCZJ_Xposed"
        private const val MODULE_PKG = "com.whmdg.mczj.tools"
        private const val KEY_REQUEST = "readMCZJUsageStatsHookData_request"
        private const val KEY_RESPONSE = "readMCZJUsageStatsHookData_response"
        private const val ERROR_PREFIX = "ERROR:"
        private val eventBuffer = mutableListOf<String>()
        private var currentDate: Long = 0
        private var hookError: String = ""
    }
}
