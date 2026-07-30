package com.whmdg.mczj.tools.xposed

import android.os.IBinder
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class 模块入口 : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "艨艟: 模块已加载, 进程=${param.processName}, systemServer=${param.isSystemServer}")
        设置激活属性()
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        log(Log.INFO, TAG, "艨艟: 包已加载: ${param.packageName}, first=${param.isFirstPackage}")
        if (param.packageName == "net.defensezone3.ultra") {
            if (isScopeEnabled("net.defensezone3.ultra")) {
                DefenseZone3AdHook.handlePackageLoaded(this, param)
            } else {
                log(Log.INFO, TAG, "艨艟: DZ3 作用域未启用，跳过注入")
            }
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, TAG, "艨艟: system_server 已加载")
        设置激活属性()
    }

    private fun 设置激活属性() {
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val setMethod = clazz.getMethod("set", String::class.java, String::class.java)
            setMethod.invoke(null, 激活属性名, "${System.currentTimeMillis()}")
        } catch (_: Throwable) {
        }
    }

    /** 通过 LSPosed binder 链实时查询模块作用域 */
    private fun isScopeEnabled(packageName: String): Boolean {
        return try {
            // 1. BridgeService.getService() → ILSPosedService binder
            val bridgeClass = Class.forName("org.lsposed.lspd.service.BridgeService")
            val getService = bridgeClass.getMethod("getService")
            val lspdBinder = getService.invoke(null) as? IBinder
            if (lspdBinder == null) {
                log(Log.WARN, TAG, "BridgeService.getService() 返回 null")
                return false
            }

            // 2. requestApplicationService → ILSPApplicationService
            val lspdStub = Class.forName("org.lsposed.lspd.service.ILSPosedService\$Stub")
            val asInterface = lspdStub.getMethod("asInterface", IBinder::class.java)
            val lspd = asInterface.invoke(null, lspdBinder)

            val reqAppSvc = lspd.javaClass.getMethod(
                "requestApplicationService",
                Int::class.java, Int::class.java, String::class.java, IBinder::class.java
            )
            val heartbeat = object : android.os.Binder() {}
            val appService = reqAppSvc.invoke(
                lspd,
                android.os.Process.myUid(),
                android.os.Process.myPid(),
                android.app.ActivityThread.currentProcessName(),
                heartbeat
            ) as? IBinder
            if (appService == null) {
                log(Log.WARN, TAG, "requestApplicationService 返回 null")
                return false
            }

            // 3. requestInjectedManagerBinder → ILSPManagerService
            val appStub = Class.forName("org.lsposed.lspd.service.ILSPApplicationService\$Stub")
            val appAsInterface = appStub.getMethod("asInterface", IBinder::class.java)
            val appSvc = appAsInterface.invoke(null, appService)

            val reqMgrBinder = appSvc.javaClass.getMethod(
                "requestInjectedManagerBinder", java.util.List::class.java
            )
            val binders = java.util.ArrayList<IBinder>()
            val mgrBinder = reqMgrBinder.invoke(appSvc, binders) as? IBinder
            if (mgrBinder == null) {
                log(Log.WARN, TAG, "requestInjectedManagerBinder 返回 null")
                return false
            }

            val mgrStub = Class.forName("org.lsposed.lspd.ILSPManagerService\$Stub")
            val mgrAsInterface = mgrStub.getMethod("asInterface", IBinder::class.java)
            val manager = mgrAsInterface.invoke(null, mgrBinder)

            // 4. getModuleScope → 查询作用域
            val getScope = manager.javaClass.getMethod("getModuleScope", String::class.java)
            @Suppress("UNCHECKED_CAST")
            val scope = getScope.invoke(manager, MODULE_PKG) as? List<Any> ?: return false
            val enabled = scope.any {
                try {
                    it.javaClass.getField("packageName").get(it) == packageName
                } catch (_: Throwable) {
                    false
                }
            }
            log(Log.INFO, TAG, "作用域查询: $packageName = $enabled")
            enabled
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "isScopeEnabled 异常: ${t.message}")
            false
        }
    }

    companion object {
        private const val TAG = "MCZJ_Xposed"
        private const val MODULE_PKG = "com.whmdg.mczj.tools"
        private const val 激活属性名 = "mczj.xposed.active"
    }
}
