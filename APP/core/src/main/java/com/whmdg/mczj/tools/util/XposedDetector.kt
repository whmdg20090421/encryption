package com.whmdg.mczj.tools.util

import android.os.IBinder
import android.util.Log

/**
 * 模块激活状态检测。
 *
 * 通过 LSPosed binder 链查询模块作用域：启用的模块必须至少有一个作用域（LSPosed 规则）。
 */
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
