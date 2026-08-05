package com.whmdg.mczj.tools.xposed

import android.app.usage.UsageEvents
import android.os.IBinder
import android.util.Log
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.io.File

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

        if (param.packageName == MODULE_PKG) {
            bypassHiddenApiForSelf()
            reportEventForSelf()
        }
    }

    /**
     * 在本 App 进程内调用 setHiddenApiExemptions({"L"})，
     * 解除隐藏 API 限制（LSPosed 对模块代码免除 hidden API 检查）。
     */
    private fun bypassHiddenApiForSelf() {
        try {
            val vmCls = Class.forName("dalvik.system.VMRuntime")
            val runtime = vmCls.getMethod("getRuntime").invoke(null)
            val exemptions = vmCls.getMethod("setHiddenApiExemptions", Array<String>::class.java)
            exemptions.invoke(runtime, arrayOf("L"))
            Log.i(TAG, "bypassHiddenApi: 成功")
            writeFile(BYPASS_FILE, "ok")
        } catch (e: Throwable) {
            Log.e(TAG, "bypassHiddenApi: 失败", e)
            writeFile(BYPASS_FILE, "fail\n${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Hook 代为执行 reportEvent，结果写入文件供 App 读取。
     */
    private fun reportEventForSelf() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getMethod("getService", String::class.java).invoke(null, "usagestats") as? IBinder
            if (binder == null) {
                writeFile(RESULT_FILE, "fail\n无法获取 usagestats binder")
                return
            }
            val stubClass = Class.forName("android.app.usage.IUsageStatsManager\$Stub")
            val proxy = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)

            val event = UsageEvents.Event()
            // 动态查找字段（字段名因设备/Android 版本而异）
            for (f in UsageEvents.Event::class.java.declaredFields) {
                f.isAccessible = true
                when {
                    f.type == Int::class.javaPrimitiveType && f.getInt(event) == 0 ->
                        f.setInt(event, UsageEvents.Event.USER_INTERACTION)
                    f.type == Long::class.javaPrimitiveType && f.getLong(event) == 0L ->
                        f.setLong(event, System.currentTimeMillis())
                    f.type == String::class.java && f.get(event) == null ->
                        f.set(event, MODULE_PKG)
                }
            }

            val method = proxy.javaClass.getMethod("reportEvent", UsageEvents.Event::class.java, Int::class.java)
            method.invoke(proxy, event, 0)
            Log.i(TAG, "reportEvent: 成功")
            writeFile(RESULT_FILE, "ok\nreportEvent 调用成功（Hook 代为执行）")
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            Log.e(TAG, "reportEvent: 失败", cause)
            writeFile(RESULT_FILE, "fail\n${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    private fun writeFile(name: String, content: String) {
        try {
            val dir = File("/data/data/$MODULE_PKG/files")
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeText(content)
        } catch (_: Throwable) {}
    }

    override fun onInit() {}
    override fun onHook() {}

    companion object {
        private const val TAG = "MCZJ_Xposed"
        private const val MODULE_PKG = "com.whmdg.mczj.tools"
        private const val BYPASS_FILE = "hook_hidden_api_status"
        private const val RESULT_FILE = "hook_report_event_result"
    }
}
