package com.whmdg.mczj.tools.xposed

import android.util.Log
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

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
    }

    override fun onInit() {}
    override fun onHook() {}

    companion object {
        private const val TAG = "MCZJ_Xposed"
        private const val MODULE_PKG = "com.whmdg.mczj.tools"
    }
}
