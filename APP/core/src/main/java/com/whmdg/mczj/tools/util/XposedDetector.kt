package com.whmdg.mczj.tools.util

import android.os.IBinder
import android.util.Log

/**
 * 模块激活状态检测。
 *
 * 通过 LSPosed binder 链查询模块作用域：启用的模块必须至少有一个作用域（LSPosed 规则）。
 */
data class XposedDebugInfo(
    val moduleActive: Boolean,
    val scopeList: List<String>,
    val steps: List<Pair<String, String>>,  // step name → "OK" or error message
    val exception: String? = null
)

object XposedDetector {

    private const val TAG = "XposedDetector"
    private const val MODULE_PKG = "com.whmdg.mczj.tools"

    fun isModuleActive(): Boolean {
        return try {
            val manager = getManagerService() ?: return false
            val getScope = manager.javaClass.getMethod("getModuleScope", String::class.java)
            val scope = getScope.invoke(manager, MODULE_PKG) as? List<*> ?: return false
            scope.isNotEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "isModuleActive failed: ${t.message}")
            false
        }
    }

    fun collectDebugInfo(): XposedDebugInfo {
        val steps = mutableListOf<Pair<String, String>>()
        var scopeList = emptyList<String>()
        try {
            // Step 1: BridgeService.getService()
            val bridgeClass = Class.forName("org.lsposed.lspd.service.BridgeService")
            val lspdService = try {
                bridgeClass.getMethod("getService").invoke(null) as? IBinder
            } catch (t: Throwable) {
                steps.add("BridgeService.getService()" to "FAIL: ${t.message}")
                return XposedDebugInfo(false, emptyList(), steps, t.message)
            }
            if (lspdService == null) {
                steps.add("BridgeService.getService()" to "FAIL: returned null")
                return XposedDebugInfo(false, emptyList(), steps)
            }
            steps.add("BridgeService.getService()" to "OK (binder=${lspdService.interfaceDescriptor})")

            // Step 2: ILSPosedService.asInterface()
            val lspdStub = Class.forName("org.lsposed.lspd.service.ILSPosedService\$Stub")
            val lspd = lspdStub.getMethod("asInterface", IBinder::class.java).invoke(null, lspdService)
            steps.add("ILSPosedService.asInterface()" to "OK")

            // Step 3: requestApplicationService()
            val processName = try { android.app.Application.getProcessName() } catch (_: Throwable) { MODULE_PKG }
            val heartbeat = object : android.os.Binder() {}
            val appService = lspd.javaClass.getMethod(
                "requestApplicationService",
                Int::class.java, Int::class.java, String::class.java, IBinder::class.java
            ).invoke(lspd, android.os.Process.myUid(), android.os.Process.myPid(), processName, heartbeat) as? IBinder
            if (appService == null) {
                steps.add("requestApplicationService()" to "FAIL: returned null (module not loaded by LSPosed?)")
                return XposedDebugInfo(false, emptyList(), steps)
            }
            steps.add("requestApplicationService()" to "OK")

            // Step 4: ILSPApplicationService.asInterface()
            val appStub = Class.forName("org.lsposed.lspd.service.ILSPApplicationService\$Stub")
            val appSvc = appStub.getMethod("asInterface", IBinder::class.java).invoke(null, appService)
            steps.add("ILSPApplicationService.asInterface()" to "OK")

            // Step 5: requestInjectedManagerBinder()
            val mgrBinder = appSvc.javaClass.getMethod(
                "requestInjectedManagerBinder", java.util.List::class.java
            ).invoke(appSvc, ArrayList<IBinder>()) as? IBinder
            if (mgrBinder == null) {
                steps.add("requestInjectedManagerBinder()" to "FAIL: returned null")
                return XposedDebugInfo(false, emptyList(), steps)
            }
            steps.add("requestInjectedManagerBinder()" to "OK")

            // Step 6: ILSPManagerService.asInterface()
            val mgrStub = Class.forName("org.lsposed.lspd.ILSPManagerService\$Stub")
            val manager = mgrStub.getMethod("asInterface", IBinder::class.java).invoke(null, mgrBinder)
            steps.add("ILSPManagerService.asInterface()" to "OK")

            // Step 7: getModuleScope()
            val getScope = manager.javaClass.getMethod("getModuleScope", String::class.java)
            val scope = getScope.invoke(manager, MODULE_PKG) as? List<*>
            if (scope == null) {
                steps.add("getModuleScope()" to "FAIL: returned null")
                return XposedDebugInfo(false, emptyList(), steps)
            }
            steps.add("getModuleScope()" to "OK (${scope.size} scopes)")

            // Extract package names
            scopeList = scope.mapNotNull { app ->
                try {
                    val field = app?.javaClass?.getField("packageName") ?: return@mapNotNull null
                    field.get(app) as? String
                } catch (_: Throwable) { null }
            }
            steps.add("解析 scope packageName" to "OK (${scopeList.size} apps)")

            return XposedDebugInfo(scopeList.isNotEmpty(), scopeList, steps)
        } catch (t: Throwable) {
            steps.add("EXCEPTION" to "${t.javaClass.simpleName}: ${t.message}")
            return XposedDebugInfo(false, scopeList, steps, t.message)
        }
    }

    private fun getManagerService(): Any? {
        return try {
            val bridgeClass = Class.forName("org.lsposed.lspd.service.BridgeService")
            val lspdService = bridgeClass.getMethod("getService").invoke(null) as? IBinder
                ?: return null

            val lspdStub = Class.forName("org.lsposed.lspd.service.ILSPosedService\$Stub")
            val lspd = lspdStub.getMethod("asInterface", IBinder::class.java).invoke(null, lspdService)

            val processName = try {
                android.app.Application.getProcessName()
            } catch (_: Throwable) {
                MODULE_PKG
            }
            val heartbeat = object : android.os.Binder() {}

            val appService = lspd.javaClass.getMethod(
                "requestApplicationService",
                Int::class.java, Int::class.java, String::class.java, IBinder::class.java
            ).invoke(lspd, android.os.Process.myUid(), android.os.Process.myPid(), processName, heartbeat) as? IBinder
                ?: return null

            val appStub = Class.forName("org.lsposed.lspd.service.ILSPApplicationService\$Stub")
            val appSvc = appStub.getMethod("asInterface", IBinder::class.java).invoke(null, appService)

            val mgrBinder = appSvc.javaClass.getMethod(
                "requestInjectedManagerBinder", java.util.List::class.java
            ).invoke(appSvc, ArrayList<IBinder>()) as? IBinder ?: return null

            val mgrStub = Class.forName("org.lsposed.lspd.ILSPManagerService\$Stub")
            mgrStub.getMethod("asInterface", IBinder::class.java).invoke(null, mgrBinder)
        } catch (t: Throwable) {
            Log.w(TAG, "getManagerService failed: ${t.message}")
            null
        }
    }
}
