package com.whmdg.mczj.tools.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.whmdg.mczj.tools.util.AppHardeningDetector
import com.whmdg.mczj.tools.util.AppSignatureDetector
import com.whmdg.mczj.tools.util.DiagnosticLog
import com.whmdg.mczj.tools.util.FormatUtils
import java.io.File

/** APK 解析信息 */
data class ApkInfo(
    val appIcon: Drawable?,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val fileSize: Long,
    val signatureStatus: String,
    val hardeningStatus: String,
    val isInstalled: Boolean,
    val installedVersion: String,
    val dataDir1: String,
    val dataDir2: String,
    val apkPath: String,
    val uid: Int
)

/** 从 APK 文件解析信息 */
fun loadApkInfo(context: Context, apkPath: String): ApkInfo? {
    return try {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(apkPath, flags) ?: return null

        val appInfo = info.applicationInfo ?: return null
        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath

        val appIcon = pm.getApplicationIcon(appInfo)
        val appName = appInfo.loadLabel(pm).toString()
        val packageName = info.packageName
        val versionName = info.versionName ?: ""
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        val fileSize = File(apkPath).length()

        // 签名检测（统一为方案组合）
        val signatureStatus = AppSignatureDetector.detect(apkPath)

        // 加固检测（MT 口径：可报伪加固）
        val hardeningStatus = AppHardeningDetector.detect(apkPath)

        // 已安装检测
        val installedInfo = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        } catch (_: Exception) { null }

        val isInstalled = installedInfo != null
        val installedVersion = if (installedInfo != null) {
            val iv = installedInfo.versionName ?: ""
            val ic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                installedInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                installedInfo.versionCode.toLong()
            }
            "$iv ($ic)"
        } else "未安装"

        val dataDir1 = appInfo.dataDir ?: ""
        val dataDir2 = appInfo.sourceDir ?: ""
        val uid = if (installedInfo != null) installedInfo.applicationInfo?.uid ?: appInfo.uid else appInfo.uid

        ApkInfo(
            appIcon = appIcon,
            appName = appName,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            fileSize = fileSize,
            signatureStatus = signatureStatus,
            hardeningStatus = hardeningStatus,
            isInstalled = isInstalled,
            installedVersion = installedVersion,
            dataDir1 = dataDir1,
            dataDir2 = dataDir2,
            apkPath = apkPath,
            uid = uid
        )
    } catch (e: Exception) {
        DiagnosticLog.log("ApkInfo", "解析失败: ${e.message}")
        null
    }
}

/** APK 信息弹窗 */
@Composable
fun ApkInfoDialog(
    apkPath: String,
    onDismiss: () -> Unit,
    onViewAsArchive: () -> Unit = {}
) {
    val context = LocalContext.current
    val apkInfo = remember(apkPath) { loadApkInfo(context, apkPath) }

    if (apkInfo == null) {
        onDismiss()
        return
    }

    val rows = buildList {
        add(AppInfoRowData("包名", apkInfo.packageName))
        add(AppInfoRowData("版本号", apkInfo.versionCode.toString()))
        add(AppInfoRowData("安装包大小", FormatUtils.formatBytes(apkInfo.fileSize)))
        add(AppInfoRowData("签名状态", apkInfo.signatureStatus))
        add(AppInfoRowData("加固状态", apkInfo.hardeningStatus))
        add(AppInfoRowData("已安装", apkInfo.installedVersion))
        if (apkInfo.isInstalled) {
            add(AppInfoRowData("内部数据目录", apkInfo.dataDir1))
            add(AppInfoRowData("外部数据目录", apkInfo.dataDir2))
        }
        add(AppInfoRowData("APK 路径", apkInfo.apkPath))
        if (apkInfo.isInstalled) {
            add(AppInfoRowData("UID", apkInfo.uid.toString()))
        }
    }

    AppInfoDialog(
        icon = apkInfo.appIcon,
        appName = apkInfo.appName,
        versionName = apkInfo.versionName,
        rows = rows,
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = {}, enabled = false) { Text("功能") }
            TextButton(onClick = {
                onViewAsArchive()
                onDismiss()
            }) { Text("查看") }
            TextButton(onClick = {}, enabled = false) { Text("安装") }
        }
    )
}
