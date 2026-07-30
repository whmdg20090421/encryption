package com.whmdg.mczj.tools.xposed

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.whmdg.mczj.tools.AppDataPaths
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.io.File
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Defense Zone 3 Ultra 广告 Hook
 *
 * 策略：hook 广告 Activity 的 onResume()，广告弹出后立即 finish()。
 * SDK 检测到 Activity 结束后自然触发回调链 → 游戏增加金币。
 */
object DefenseZone3AdHook {

    private const val TAG = "MCZJ_DZ3_AdHook"
    private const val TARGET = "net.defensezone3.ultra"
    private const val HOOK_SUBDIR = "Hook"

    /** 记录最近一次 hook 事件，供诊断保存 */
    private var lastHookEvent: String = ""

    fun handlePackageLoaded(module: XposedInterface, param: PackageLoadedParam) {
        if (param.packageName != TARGET) return

        val classLoader = param.defaultClassLoader
        val handler = Handler(Looper.getMainLooper())

        hookActivity(module, classLoader, handler, "com.google.android.gms.ads.AdActivity")
        hookActivity(module, classLoader, handler, "com.applovin.adview.AppLovinFullscreenActivity")
        hookAppLovinCallback(module, classLoader, handler)

        module.log(Log.INFO, TAG, "DZ3 ad hooks installed")
    }

    private fun hookActivity(
        module: XposedInterface,
        classLoader: ClassLoader,
        handler: Handler,
        className: String
    ) {
        try {
            val clazz = Class.forName(className, false, classLoader)
            val onResume = clazz.getDeclaredMethod("onResume")

            module.hook(onResume).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    chain.proceed()
                    val activity = chain.thisObject as? Activity ?: return null
                    if (activity.isFinishing) return null

                    lastHookEvent = "广告弹出: $className @ ${now()}"

                    handler.post {
                        Toast.makeText(
                            activity.applicationContext,
                            "Xposed 已跳过广告",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    handler.postDelayed({
                        if (!activity.isFinishing) {
                            activity.finish()
                            module.log(Log.INFO, TAG, "$className finished")
                        }
                        handler.postDelayed({
                            checkAndForceRemove(activity, module, className, handler)
                        }, 300)
                    }, 100)

                    return null
                }
            })
            module.log(Log.INFO, TAG, "hooked $className.onResume")

            hookAppMainActivity(module, classLoader, handler, className)
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "hook $className failed: ${t.message}")
        }
    }

    private fun hookAppMainActivity(
        module: XposedInterface,
        classLoader: ClassLoader,
        handler: Handler,
        adClassName: String
    ) {
        if (adClassName != "com.google.android.gms.ads.AdActivity") return
        try {
            val mainClass = Class.forName(
                "com.unity3d.player.UnityPlayerActivity",
                false,
                classLoader
            )
            val onResume = mainClass.getDeclaredMethod("onResume")
            var shown = false
            module.hook(onResume).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    chain.proceed()
                    if (shown) return null
                    shown = true
                    val activity = chain.thisObject as? Activity ?: return null
                    handler.post {
                        Toast.makeText(
                            activity.applicationContext,
                            "Xposed 广告跳过模块已初始化",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return null
                }
            })
            module.log(Log.INFO, TAG, "hooked UnityPlayerActivity for init toast")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "hook main activity failed: ${t.message}")
        }
    }

    private fun checkAndForceRemove(
        activity: Activity,
        module: XposedInterface,
        className: String,
        handler: Handler
    ) {
        try {
            if (activity.isFinishing || activity.isDestroyed) {
                module.log(Log.INFO, TAG, "广告已正常关闭")
                return
            }

            activity.finish()
            module.log(Log.WARN, TAG, "广告仍在屏幕上，再次 finish()")

            handler.postDelayed({
                if (!activity.isFinishing && !activity.isDestroyed) {
                    try {
                        val window = activity.window
                        if (window != null) {
                            val wm = activity.getSystemService(Context.WINDOW_SERVICE)
                                as android.view.WindowManager
                            val decorView = window.decorView
                            if (decorView.isAttachedToWindow) {
                                wm.removeView(decorView)
                                module.log(Log.WARN, TAG, "WindowManager 强制移除广告窗口")
                            }
                        }
                    } catch (wmError: Throwable) {
                        module.log(Log.WARN, TAG, "WindowManager 移除失败: ${wmError.message}")
                    }

                    handler.postDelayed({
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            saveDiagnostics(activity, module, className)
                        }
                    }, 200)
                }
            }, 200)
        } catch (t: Throwable) {
            module.log(Log.ERROR, TAG, "checkAndForceRemove error: ${t.message}")
        }
    }

    private fun saveDiagnostics(activity: Activity, module: XposedInterface, className: String) {
        try {
            val diagRoot = AppDataPaths.diagnostics(activity.applicationContext)
            val dir = File(diagRoot, "$HOOK_SUBDIR/$TARGET")
            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "ad_stuck_${timestamp}.txt")

            val info = buildString {
                appendLine("=== Defense Zone 3 Ad Hook Diagnostics ===")
                appendLine("Time: ${now()}")
                appendLine("Hook event: $lastHookEvent")
                appendLine("Activity class: $className")
                appendLine("Activity hash: ${activity.hashCode()}")
                appendLine("isFinishing: ${activity.isFinishing}")
                appendLine("isDestroyed: ${activity.isDestroyed}")
                appendLine("isChangingConfigurations: ${activity.isChangingConfigurations}")
                appendLine("Task id: ${activity.taskId}")
                appendLine("Window type: ${activity.window?.attributes?.type}")
                appendLine("")
                appendLine("=== Activity Stack ===")
                try {
                    val am = activity.getSystemService(Context.ACTIVITY_SERVICE)
                        as android.app.ActivityManager
                    @Suppress("DEPRECATION")
                    val tasks = am.getRunningTasks(10)
                    for (task in tasks) {
                        appendLine("  Task ${task.id}: ${task.topActivity}")
                    }
                } catch (_: Throwable) {
                    appendLine("  (无法获取 Activity 栈)")
                }
                appendLine("")
                appendLine("=== Device Info ===")
                appendLine("SDK: ${android.os.Build.VERSION.SDK_INT}")
                appendLine("Model: ${android.os.Build.MODEL}")
                appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
            }

            file.writeText(info)
            module.log(Log.WARN, TAG, "诊断信息已保存: ${file.absolutePath}")
        } catch (t: Throwable) {
            module.log(Log.ERROR, TAG, "保存诊断信息失败: ${t.message}")
        }
    }

    private fun hookAppLovinCallback(
        module: XposedInterface,
        classLoader: ClassLoader,
        handler: Handler
    ) {
        try {
            val listenerClass = Class.forName(
                "com.applovin.mediation.MaxRewardedAdListener",
                false,
                classLoader
            )
            val adClass = Class.forName(
                "com.applovin.mediation.ads.MaxAd",
                false,
                classLoader
            )
            val onDisplayed = listenerClass.getDeclaredMethod("onAdDisplayed", adClass)

            module.hook(onDisplayed).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    chain.proceed()
                    val listener = chain.thisObject
                    val ad = chain.getArgs()[0]
                    handler.postDelayed({
                        try {
                            val onHidden = listenerClass.getDeclaredMethod("onAdHidden", adClass)
                            onHidden.invoke(listener, ad)
                            module.log(Log.INFO, TAG, "AppLovin onAdHidden called")
                        } catch (t: Throwable) {
                            module.log(Log.WARN, TAG, "onAdHidden failed: ${t.message}")
                        }
                    }, 200)
                    return null
                }
            })
            module.log(Log.INFO, TAG, "hooked AppLovin onAdDisplayed")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "hook AppLovin callback failed: ${t.message}")
        }
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
