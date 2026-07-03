package com.whmdg.mczj.tools.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.whmdg.mczj.tools.util.DiagnosticLog
import com.whmdg.mczj.tools.util.FormatUtils
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

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

        // 签名检测
        val signatureStatus = detectSignature(info)

        // 加固检测
        val hardeningStatus = detectHardening(apkPath)

        // 已安装检测
        val installedInfo = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        } catch (_: Exception) { null }

        val isInstalled = installedInfo != null
        val installedVersion = if (isInstalled) {
            val iv = installedInfo!!.versionName ?: ""
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
        val uid = if (isInstalled) installedInfo!!.applicationInfo!!.uid else appInfo.uid

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

private fun detectSignature(info: android.content.pm.PackageInfo): String {
    val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        info.signatures
    }
    if (certs.isNullOrEmpty()) return "未签名"

    val cert = certs[0]
    val sha256 = MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
    val fingerprint = sha256.joinToString(":") { "%02X".format(it) }
    // 取前 8 字节作为简短指纹
    val shortFingerprint = sha256.take(8).joinToString(":") { "%02X".format(it) }

    // API 28+ 默认支持 V2 签名
    val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) "V2+" else "V1"

    return "已签名 ($version) $shortFingerprint"
}

private fun detectHardening(apkPath: String): String {
    return try {
        val zipFile = ZipFile(apkPath)
        val entries = zipFile.entries().asSequence().map { it.name }.toList()
        zipFile.close()

        when {
            entries.any { it.contains("libjiagu") } -> "360加固"
            entries.any { it.contains("libshell-super") || it.contains("libshellx") } -> "腾讯乐固"
            entries.any { it.contains("libsecexe") || it.contains("libDexHelper") } -> "梆梆加固"
            entries.any { it.contains("libexecmain") } -> "爱加密"
            entries.any { it.contains("lib360protect") } -> "360加固"
            else -> "未加固"
        }
    } catch (_: Exception) {
        "检测失败"
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── 头部：图标 + 名称 + 版本 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val iconBitmap = apkInfo.appIcon?.toBitmap(96, 96)
                    if (iconBitmap != null) {
                        Image(
                            painter = BitmapPainter(iconBitmap.asImageBitmap()),
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                    Column {
                        Text(
                            text = apkInfo.appName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = apkInfo.versionName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                )

                // ── 信息列表 ──
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ApkInfoRow("包名", apkInfo.packageName)
                    ApkInfoRow("版本号", apkInfo.versionCode.toString())
                    ApkInfoRow("安装包大小", FormatUtils.formatBytes(apkInfo.fileSize))
                    ApkInfoRow("签名状态", apkInfo.signatureStatus)
                    ApkInfoRow("加固状态", apkInfo.hardeningStatus)
                    ApkInfoRow("已安装", apkInfo.installedVersion)
                    if (apkInfo.isInstalled) {
                        ApkInfoRow("数据目录1", apkInfo.dataDir1)
                        ApkInfoRow("数据目录2", apkInfo.dataDir2)
                    }
                    ApkInfoRow("APK路径", apkInfo.apkPath)
                    if (apkInfo.isInstalled) {
                        ApkInfoRow("UID", apkInfo.uid.toString())
                    }
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                )

                // ── 底部按钮 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = { /* TODO */ }, enabled = false) {
                        Text("功能")
                    }
                    TextButton(onClick = {
                        onViewAsArchive()
                        onDismiss()
                    }) {
                        Text("查看")
                    }
                    TextButton(onClick = { /* TODO */ }, enabled = false) {
                        Text("安装")
                    }
                }
            }
        }
    }
}

@Composable
private fun ApkInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
