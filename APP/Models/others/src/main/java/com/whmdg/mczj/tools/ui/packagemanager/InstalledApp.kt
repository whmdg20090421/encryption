package com.whmdg.mczj.tools.ui.packagemanager

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 已安装应用的展示信息 */
data class InstalledApp(
    val appName: String,
    val packageName: String,
    val icon: Drawable?,
    val versionName: String,
    val apkSize: Long,
    val splitSize: Long,
    val isSystemApp: Boolean
)

/** 已安装应用列表数据源 */
object InstalledAppProvider {

    /**
     * 加载已安装应用列表。
     *
     * @param onlySystem true 仅系统应用，false 仅用户应用
     */
    suspend fun loadApps(context: Context, onlySystem: Boolean): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val result = mutableListOf<InstalledApp>()

        val installed = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            emptyList()
        }

        for (appInfo in installed) {
            try {
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem != onlySystem) continue

                val packageInfo = pm.getPackageInfo(appInfo.packageName, 0)
                val appName = pm.getApplicationLabel(appInfo).toString()
                val icon = try { pm.getApplicationIcon(appInfo) } catch (_: Exception) { null }

                val baseSize = appInfo.sourceDir?.let { File(it).length() } ?: 0L
                val splitSize = appInfo.splitSourceDirs
                    ?.sumOf { File(it).length() }
                    ?: 0L

                result += InstalledApp(
                    appName = appName,
                    packageName = appInfo.packageName,
                    icon = icon,
                    versionName = packageInfo.versionName ?: "",
                    apkSize = baseSize,
                    splitSize = splitSize,
                    isSystemApp = isSystem
                )
            } catch (_: Exception) {
                // 单个应用解析失败则跳过
            }
        }

        result.sortedBy { it.appName.lowercase() }
    }

    /** 跳转到当前应用详情设置页的 Intent */
    fun buildAppSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}
