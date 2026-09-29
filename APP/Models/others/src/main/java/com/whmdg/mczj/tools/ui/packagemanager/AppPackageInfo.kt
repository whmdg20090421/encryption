package com.whmdg.mczj.tools.ui.packagemanager

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.ShellExecutor
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.util.AppHardeningDetector
import com.whmdg.mczj.tools.util.AppSignatureDetector
import com.whmdg.mczj.tools.util.DiagnosticLog
import com.whmdg.mczj.tools.util.ShellEscape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 已安装应用的展示信息（列表用，保持轻量） */
data class AppPackageInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable?,
    val versionName: String,
    val totalSize: Long,
    val isSystemApp: Boolean
)

/** 应用详情（点击卡片后才解析，仅针对单个应用） */
data class AppPackageDetail(
    val versionCode: Long,
    val signatureStatus: String,
    val hardeningStatus: String,
    val internalDataDir: String,
    val externalDataDir: String,
    val apkPath: String,
    /** 分包 APK 路径（不含 base）。为空表示单 APK 应用。 */
    val splitApkPaths: List<String>,
    val uid: Int
)

/** 已安装应用列表数据源 */
object AppPackageInfoProvider {

    /**
     * 加载已安装应用列表。
     *
     * @param onlySystem true 仅系统应用，false 仅用户应用
     */
    suspend fun loadApps(context: Context, onlySystem: Boolean): List<AppPackageInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val result = mutableListOf<AppPackageInfo>()

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

                result += AppPackageInfo(
                    appName = appName,
                    packageName = appInfo.packageName,
                    icon = icon,
                    versionName = packageInfo.versionName ?: "",
                    totalSize = baseSize + splitSize,
                    isSystemApp = isSystem
                )
            } catch (_: Exception) {
                // 单个应用解析失败则跳过
            }
        }

        result.sortedBy { it.appName.lowercase() }
    }

    /** 解析单个应用的详情（点击卡片后调用） */
    suspend fun loadDetail(context: Context, info: AppPackageInfo): AppPackageDetail = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(info.packageName, 0)
        val packageInfo = pm.getPackageInfo(info.packageName, 0)

        val apkPath = appInfo.sourceDir ?: ""
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        val externalDataDir = buildExternalDataDir(context, info.packageName)

        AppPackageDetail(
            versionCode = versionCode,
            signatureStatus = AppSignatureDetector.detect(apkPath),
            hardeningStatus = if (apkPath.isNotEmpty()) AppHardeningDetector.detect(apkPath) else "检测失败",
            internalDataDir = appInfo.dataDir ?: "",
            externalDataDir = externalDataDir,
            apkPath = apkPath,
            splitApkPaths = appInfo.splitSourceDirs?.filter { it != apkPath } ?: emptyList(),
            uid = appInfo.uid
        )
    }

    /**
     * 拼接外部数据目录。若当前拥有 Root / ADB 权限则实际探测目录是否存在，
     * 并在路径后追加状态；普通权限下仅做拼接。
     */
    private fun buildExternalDataDir(context: Context, packageName: String): String {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val path = "$root/Android/data/$packageName"
        val exists = probeDirExists(context, path) ?: return path
        return if (exists) "$path（存在）" else "$path（不存在）"
    }

    /**
     * 以 Root / ADB 权限探测目录是否存在。
     *
     * @return true/false 表示探测结果；null 表示当前无特权（普通权限，未探测）
     */
    private fun probeDirExists(context: Context, path: String): Boolean? {
        // Root 需安全设置中选定 Root 档位且 su 真正可用；ADB 以 Shizuku 授权为准
        val permissionLevel = context.getSharedPreferences(
            com.whmdg.mczj.tools.AppDataPaths.PREFS_LEGACY_SPECIAL_PERMISSIONS,
            Context.MODE_PRIVATE
        ).getString("target_permission_level", "NORMAL") ?: "NORMAL"
        val permission = when {
            permissionLevel == "ROOT" && SpecialPermissionVerifier.isRootAvailable() -> Permission.ROOT
            SpecialPermissionVerifier.isShizukuAuthorized(context) -> Permission.ADB
            else -> return null
        }
        return try {
            val escaped = ShellEscape.escape(path)
            val out = ShellExecutor.execute(permission, "[ -d $escaped ] && echo Y || echo N")
            out.trim().endsWith("Y")
        } catch (e: Exception) {
            DiagnosticLog.log("PackageManager", "探测目录失败: ${e.message}")
            null
        }
    }

    // ── 签名方案检测：见 core AppSignatureDetector ────────────────

    /** 跳转到当前应用详情设置页的 Intent */
    fun buildAppSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}
