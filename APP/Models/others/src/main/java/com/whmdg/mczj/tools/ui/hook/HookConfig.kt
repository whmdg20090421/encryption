package com.whmdg.mczj.tools.ui.hook

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.whmdg.mczj.tools.AppDataPaths

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

    /** 所有已支持的 hook 目标 */
    val TARGETS: List<HookTarget> = listOf(
        HookTarget(
            packageName = "net.defensezone3.ultra",
            displayName = "Defense Zone 3 Ultra",
            description = "塔防游戏 — 广告跳过",
            hookFeatures = listOf(HookFeature.SKIP_AD)
        )
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(AppDataPaths.PREFS_HOOK, Context.MODE_PRIVATE)

    // ── L1 作用域开关（控制是否注入 hook） ──

    /** 某个应用的作用域开关是否启用 */
    fun isScopeEnabled(context: Context, packageName: String): Boolean =
        prefs(context).getBoolean("${packageName}_SCOPE", false)

    /** 设置作用域开关状态 */
    fun setScopeEnabled(context: Context, packageName: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("${packageName}_SCOPE", enabled).apply()
    }

    // ── L2 功能开关（控制注入后具体 hook 是否生效） ──

    /** 某个应用的某个功能开关是否启用 */
    fun isFeatureEnabled(context: Context, packageName: String, feature: HookFeature): Boolean =
        prefs(context).getBoolean("${packageName}_${feature.name}", false)

    /** 设置功能开关状态 */
    fun setFeatureEnabled(context: Context, packageName: String, feature: HookFeature, enabled: Boolean) {
        prefs(context).edit().putBoolean("${packageName}_${feature.name}", enabled).apply()
    }

    // ── 应用信息查询 ──

    /** 检查应用是否已安装 */
    fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** 获取应用版本名 */
    fun getVersionName(context: Context, packageName: String): String? {
        return try {
            context.packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
