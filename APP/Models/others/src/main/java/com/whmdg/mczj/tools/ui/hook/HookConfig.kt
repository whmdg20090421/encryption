package com.whmdg.mczj.tools.ui.hook

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log

/**
 * 已支持 hook 的应用注册表。
 * 新增 hook 目标只需在 [TARGETS] 中添加一项。
 */
data class HookTarget(
    val packageName: String,
    val displayName: String,
    val description: String,
    val hookFeatures: List<HookFeature> = listOf(HookFeature.SKIP_AD)
)

enum class HookFeature(val label: String, val description: String) {
    SKIP_AD("跳过广告", "点击观看广告时自动跳过并触发奖励回调")
}

object HookConfig {

    private const val TAG = "HookConfig"
    private const val MODULE_PKG = "com.whmdg.mczj.tools"

    /** 所有已支持的 hook 目标（新增 hook 目标在此添加） */
    val TARGETS: List<HookTarget> = emptyList()

    // ── L1 作用域（通过 ILSPManagerService 实时查询） ──

    /** 从 LSPosed 实时查询模块作用域列表 */
    fun getScopeList(): List<String> {
        return try {
            val manager = getManagerService() ?: return emptyList()
            val getScope = manager.javaClass.getMethod("getModuleScope", String::class.java)
            @Suppress("UNCHECKED_CAST")
            val scope = getScope.invoke(manager, MODULE_PKG) as? List<Any> ?: return emptyList()
            scope.mapNotNull { app ->
                try {
                    app.javaClass.getField("packageName").get(app) as? String
                } catch (_: Throwable) {
                    null
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getScopeList failed: ${t.message}")
            emptyList()
        }
    }

    /** 检查指定应用是否在模块作用域中 */
    fun isScopeEnabled(packageName: String): Boolean {
        return getScopeList().contains(packageName)
    }

    /** 向 LSPosed 申请添加作用域 */
    fun addScope(packageName: String): Boolean {
        return try {
            val manager = getManagerService() ?: return false
            val appClass = Class.forName("org.lsposed.lspd.models.Application")
            val app = appClass.getDeclaredConstructor().newInstance()
            appClass.getField("packageName").set(app, packageName)
            appClass.getField("userId").set(app, 0)

            val getScope = manager.javaClass.getMethod("getModuleScope", String::class.java)
            @Suppress("UNCHECKED_CAST")
            val scope = getScope.invoke(manager, MODULE_PKG) as MutableList<Any>

            if (scope.any { appClass.getField("packageName").get(it) == packageName }) return true

            scope.add(app)
            val setScope = manager.javaClass.getMethod(
                "setModuleScope", String::class.java, java.util.List::class.java
            )
            setScope.invoke(manager, MODULE_PKG, scope) as Boolean
        } catch (t: Throwable) {
            Log.w(TAG, "addScope failed: ${t.message}")
            false
        }
    }

    /** 从 LSPosed 移除作用域 */
    fun removeScope(packageName: String): Boolean {
        return try {
            val manager = getManagerService() ?: return false
            val appClass = Class.forName("org.lsposed.lspd.models.Application")

            val getScope = manager.javaClass.getMethod("getModuleScope", String::class.java)
            @Suppress("UNCHECKED_CAST")
            val scope = getScope.invoke(manager, MODULE_PKG) as MutableList<Any>

            scope.removeAll { appClass.getField("packageName").get(it) == packageName }

            val setScope = manager.javaClass.getMethod(
                "setModuleScope", String::class.java, java.util.List::class.java
            )
            setScope.invoke(manager, MODULE_PKG, scope) as Boolean
        } catch (t: Throwable) {
            Log.w(TAG, "removeScope failed: ${t.message}")
            false
        }
    }

    /**
     * 通过 LSPosed binder 链获取 ILSPManagerService：
     * BridgeService.getService() → ILSPosedService
     *   → requestApplicationService() → ILSPApplicationService
     *     → requestInjectedManagerBinder() → ILSPManagerService
     */
    private fun getManagerService(): Any? {
        return try {
            // 1. BridgeService.getService() → ILSPosedService
            val bridgeClass = Class.forName("org.lsposed.lspd.service.BridgeService")
            val getService = bridgeClass.getMethod("getService")
            val lspdService = getService.invoke(null) as? IBinder ?: return null

            // 2. requestApplicationService → ILSPApplicationService
            val lspdStub = Class.forName("org.lsposed.lspd.service.ILSPosedService\$Stub")
            val asInterface = lspdStub.getMethod("asInterface", IBinder::class.java)
            val lspd = asInterface.invoke(null, lspdService)

            val pid = android.os.Process.myPid()
            val uid = android.os.Process.myUid()
            val processName = getProcessName()
            val heartbeat = object : android.os.Binder() {}

            val reqAppSvc = lspd.javaClass.getMethod(
                "requestApplicationService",
                Int::class.java, Int::class.java, String::class.java, IBinder::class.java
            )
            val appService = reqAppSvc.invoke(lspd, uid, pid, processName, heartbeat) as? IBinder
                ?: return null

            // 3. requestInjectedManagerBinder → ILSPManagerService
            val appStub = Class.forName("org.lsposed.lspd.service.ILSPApplicationService\$Stub")
            val appAsInterface = appStub.getMethod("asInterface", IBinder::class.java)
            val appSvc = appAsInterface.invoke(null, appService)

            val reqMgrBinder = appSvc.javaClass.getMethod(
                "requestInjectedManagerBinder", java.util.List::class.java
            )
            val binders = java.util.ArrayList<IBinder>()
            val mgrBinder = reqMgrBinder.invoke(appSvc, binders) as? IBinder ?: return null

            val mgrStub = Class.forName("org.lsposed.lspd.ILSPManagerService\$Stub")
            val mgrAsInterface = mgrStub.getMethod("asInterface", IBinder::class.java)
            mgrAsInterface.invoke(null, mgrBinder)
        } catch (t: Throwable) {
            Log.w(TAG, "getManagerService failed: ${t.message}")
            null
        }
    }

    private fun getProcessName(): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.app.Application.getProcessName()
            } else {
                // ponytail: API < 28 无原生 getProcessName，用包名代替（主进程=包名）
                MODULE_PKG
            }
        } catch (_: Throwable) {
            MODULE_PKG
        }
    }

    // ── L2 功能开关（本地 SharedPreferences） ──

    private fun prefs(context: Context) =
        context.getSharedPreferences("hook_prefs", Context.MODE_PRIVATE)

    fun isFeatureEnabled(context: Context, packageName: String, feature: HookFeature): Boolean =
        prefs(context).getBoolean("${packageName}_${feature.name}", false)

    fun setFeatureEnabled(context: Context, packageName: String, feature: HookFeature, enabled: Boolean) {
        prefs(context).edit().putBoolean("${packageName}_${feature.name}", enabled).commit()
    }

    // ── 应用信息查询 ──

    fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun getVersionName(context: Context, packageName: String): String? {
        return try {
            context.packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
