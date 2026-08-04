package com.whmdg.mczj.tools.xposed

import android.util.Log
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.io.File
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

                        // 只记录当天的数据
                        val todayStart = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        if (timeStamp < todayStart) return

                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                        val sb = StringBuilder()
                        sb.appendLine("eventType=$eventType")
                        sb.appendLine("packageName=$packageName")
                        sb.appendLine("timeStamp=$timeStamp")
                        sb.appendLine("time=${sdf.format(Date(timeStamp))}")
                        sb.appendLine("className=$className")
                        sb.appendLine("---")

                        val dumpFile = File("/data/data/com.whmdg.mczj.tools/files/report_event_dump.txt")
                        // 如果文件不是今天的，清空
                        if (dumpFile.exists()) {
                            val firstLine = dumpFile.bufferedReader().use { it.readLine() ?: "" }
                            if (!firstLine.startsWith("date=${sdf.format(Date(todayStart)).substring(0, 10)}")) {
                                dumpFile.writeText("date=${sdf.format(Date(todayStart)).substring(0, 10)}\n")
                            }
                        } else {
                            dumpFile.writeText("date=${sdf.format(Date(todayStart)).substring(0, 10)}\n")
                        }
                        dumpFile.appendText(sb.toString())
                    } catch (e: Throwable) {
                        Log.e(TAG, "艨艟 reportEvent hook error: ${e.message}")
                    }
                }
            })
            Log.i(TAG, "艨艟: reportEvent hook installed")
        } catch (e: Throwable) {
            Log.e(TAG, "艨艟: hook failed: ${e.message}")
        }
    }

    // ── YukiHookAPI 接口（KSP 生成状态标记类需要，不实际调用） ──

    override fun onInit() {}

    override fun onHook() {}

    companion object {
        private const val TAG = "MCZJ_Xposed"
    }
}
