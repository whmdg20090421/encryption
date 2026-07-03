package com.whmdg.mczj.tools.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.whmdg.mczj.tools.AppDataPaths
import kotlin.system.exitProcess

/**
 * 安全执行器：当业务层权限检查被触发时，说明第一层防御已失效。
 * 此时弹回主界面并自杀应用，防止进一步的安全风险。
 */
object SecurityEnforcer {

    private const val TAG = "SecurityEnforcer"

    /**
     * 检查权限，如果无权限则执行安全自杀。
     *
     * @param context 应用上下文
     * @param feature 需要的权限特性
     * @param caller 调用者名称（用于日志）
     * @return true 如果有权限，false 如果无权限（此时应用即将自杀）
     */
    fun checkOrDie(context: Context, feature: Feature, caller: String): Boolean {
        if (PermissionManager.has(feature)) {
            return true
        }

        // 第一层防御已失效，记录安全事件
        Log.e(TAG, "SECURITY BREACH: 业务层权限检查被触发！" +
                " feature=$feature, caller=$caller" +
                " 第一层防御（UI 门控）已被绕过！")

        // 在主线程执行安全自杀
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            try {
                // 清除授权状态
                // 注意：这里不能调用 suspend 函数，所以直接清除存储
                val sp = context.getSharedPreferences(AppDataPaths.PREFS_AUTH_TOKEN, Context.MODE_PRIVATE)
                sp.edit().clear().apply()

                // 尝试删除 Keystore 密钥
                try {
                    KeystoreMaster.deleteKey()
                } catch (e: Exception) {
                    Log.w(TAG, "删除 Keystore 密钥失败", e)
                }

                // 重置权限管理器状态
                // 由于 PermissionManager 是 object，需要通过反射或直接访问
                // 这里我们发送广播或使用其他机制通知 UI 层

                // 获取当前 Activity 并弹回主界面
                val activity = context as? Activity
                if (activity != null) {
                    // 清除所有 Activity 栈，回到主界面
                    val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                    activity.finish()
                }

                // 延迟自杀，确保 UI 更新完成
                mainHandler.postDelayed({
                    Log.w(TAG, "执行安全自杀")
                    exitProcess(0)
                }, 500)

            } catch (e: Exception) {
                Log.e(TAG, "安全自杀过程中发生异常", e)
                // 即使出错也要自杀
                exitProcess(0)
            }
        }

        return false
    }
}
