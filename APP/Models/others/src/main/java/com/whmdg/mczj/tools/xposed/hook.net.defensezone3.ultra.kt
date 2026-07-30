package com.whmdg.mczj.tools.xposed

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedInterface.AfterHooker
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import com.whmdg.mczj.tools.AppDataPaths
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

    /** 记录最近一次 hook 事件，供诊断保存 */
    private var lastHookEvent: String = ""

    /** 诊断文件子目录名 */
    private const val HOOK_SUBDIR = "Hook"

    fun handlePackageLoaded(module: XposedInterface, param: PackageLoadedParam) {
        if (param.packageName != TARGET) return

        val classLoader = param.classLoader
        val handler = Handler(Looper.getMainLooper())

        hookActivity(module, classLoader, handler, "com.google.android.gms.ads.AdActivity")
        hookActivity(module, classLoader, handler, "com.applovin.adview.AppLovinFullscreenActivity")
        hookAppLovinCallback(module, classLoader, handler)

        module.log(android.util.Log.INFO, TAG, "DZ3 ad hooks installed")
    }

    /**
     * hook 目标广告 Activity 的 onResume()。
     * 广告弹出后：
     *   1. 弹出 Toast 提示已跳过
     *   2. 延迟 finish()
     *   3. finish 后检测广告是否仍在屏幕上，若在则强制移除
     *   4. 若仍移除不掉，保存诊断信息到外部存储
     */
    private fun hookActivity(
        module: XposedInterface,
        classLoader: ClassLoader,
        handler: Handler,
        className: String
    ) {
        try {
            val clazz = Class.forName(className, false, classLoader)
            val onResume = clazz.getDeclaredMethod("onResume")
            module.hookAfter(onResume, 0, object : AfterHooker<Method> {
                override fun after(callback: AfterHookCallback<Method>) {
                    val activity = callback.thisObject as? Activity ?: return
                    if (activity.isFinishing) return

                    lastHookEvent = "广告弹出: $className @ ${now()}"

                    // 1. Toast 提示
                    handler.post {
                        Toast.makeText(
                            activity.applicationContext,
                            "Xposed 已跳过广告",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    // 2. 延迟 100ms 后 finish
                    handler.postDelayed({
                        if (!activity.isFinishing) {
                            activity.finish()
                            module.log(android.util.Log.INFO, TAG, "$className finished")
                        }

                        // 3. 再延迟 300ms 检测广告是否仍在屏幕上
                        handler.postDelayed({
                            checkAndForceRemove(activity, module, className, handler)
                        }, 300)
                    }, 100)
                }
            })
            module.log(android.util.Log.INFO, TAG, "hooked $className.onResume")

            // 首次 hook 成功时也弹提示（通过 hook 应用主 Activity）
            hookAppMainActivity(module, classLoader, handler, className)
        } catch (t: Throwable) {
            module.log(android.util.Log.WARN, TAG, "hook $className failed: ${t.message}")
        }
    }

    /**
     * hook 游戏主 Activity 的 onResume，首次弹出"模块已初始化"提示。
     */
    private fun hookAppMainActivity(
        module: XposedInterface,
        classLoader: ClassLoader,
        handler: Handler,
        adClassName: String
    ) {
        // 只在第一个广告 hook 成功时执行一次
        if (adClassName != "com.google.android.gms.ads.AdActivity") return
        try {
            val mainClass = Class.forName(
                "com.unity3d.player.UnityPlayerActivity",
                false,
                classLoader
            )
            val onResume = mainClass.getDeclaredMethod("onResume")
            var shown = false
            module.hookAfter(onResume, 0, object : AfterHooker<Method> {
                override fun after(callback: AfterHookCallback<Method>) {
                    if (shown) return
                    shown = true
                    val activity = callback.thisObject as? Activity ?: return
                    handler.post {
                        Toast.makeText(
                            activity.applicationContext,
                            "Xposed 广告跳过模块已初始化",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })
            module.log(android.util.Log.INFO, TAG, "hooked UnityPlayerActivity for init toast")
        } catch (t: Throwable) {
            module.log(android.util.Log.WARN, TAG, "hook main activity failed: ${t.message}")
        }
    }

    /**
     * 检测广告 Activity 是否仍在屏幕上。
     * 如果 activity.isFinishing 但仍可见，尝试 WindowManager 强制移除。
     * 若仍无法关闭，保存诊断信息。
     */
    private fun checkAndForceRemove(
        activity: Activity,
        module: XposedInterface,
        className: String,
        handler: Handler
    ) {
        try {
            if (activity.isFinishing || activity.isDestroyed) {
                module.log(android.util.Log.INFO, TAG, "广告已正常关闭")
                return
            }

            // 广告仍在屏幕上，尝试再次 finish
            activity.finish()
            module.log(android.util.Log.WARN, TAG, "广告仍在屏幕上，再次 finish()")

            // 再等 200ms 检测
            handler.postDelayed({
                if (!activity.isFinishing && !activity.isDestroyed) {
                    // 仍然在屏幕上，尝试移除 Window
                    try {
                        val window = activity.window
                        if (window != null) {
                            val wm = activity.getSystemService(Context.WINDOW_SERVICE)
                                as android.view.WindowManager
                            val decorView = window.decorView
                            if (decorView.isAttachedToWindow) {
                                wm.removeView(decorView)
                                module.log(android.util.Log.WARN, TAG, "WindowManager 强制移除广告窗口")
                            }
                        }
                    } catch (wmError: Throwable) {
                        module.log(android.util.Log.WARN, TAG, "WindowManager 移除失败: ${wmError.message}")
                    }

                    // 最终检测：若仍无法关闭，保存诊断信息
                    handler.postDelayed({
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            saveDiagnostics(activity, module, className)
                        }
                    }, 200)
                }
            }, 200)
        } catch (t: Throwable) {
            module.log(android.util.Log.ERROR, TAG, "checkAndForceRemove error: ${t.message}")
        }
    }

    /**
     * 保存诊断信息到 /sdcard/Hook/net.defensezone3.ultra/
     */
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
            module.log(android.util.Log.WARN, TAG, "诊断信息已保存: ${file.absolutePath}")
        } catch (t: Throwable) {
            module.log(android.util.Log.ERROR, TAG, "保存诊断信息失败: ${t.message}")
        }
    }

    /**
     * hook AppLovin MaxRewardedAdListener.onAdDisplayed()，
     * 延迟后触发 onAdHidden() 关闭广告（兜底方案）。
     */
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
            module.hookAfter(onDisplayed, 0, object : AfterHooker<Method> {
                override fun after(callback: AfterHookCallback<Method>) {
                    val listener = callback.thisObject
                    val ad = callback.args[0]
                    handler.postDelayed({
                        try {
                            val onHidden = listenerClass.getDeclaredMethod("onAdHidden", adClass)
                            onHidden.invoke(listener, ad)
                            module.log(android.util.Log.INFO, TAG, "AppLovin onAdHidden called")
                        } catch (t: Throwable) {
                            module.log(android.util.Log.WARN, TAG, "onAdHidden failed: ${t.message}")
                        }
                    }, 200)
                }
            })
            module.log(android.util.Log.INFO, TAG, "hooked AppLovin onAdDisplayed")
        } catch (t: Throwable) {
            module.log(android.util.Log.WARN, TAG, "hook AppLovin callback failed: ${t.message}")
        }
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
