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
import com.whmdg.mczj.tools.util.DiagnosticLog
import com.whmdg.mczj.tools.util.ShellEscape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

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
            signatureStatus = detectSignature(apkPath),
            hardeningStatus = if (apkPath.isNotEmpty()) AppHardeningDetector.detect(apkPath) else "检测失败",
            internalDataDir = appInfo.dataDir ?: "",
            externalDataDir = externalDataDir,
            apkPath = apkPath,
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

    // ── 签名方案检测 ──────────────────────────────────────────────

    private const val MAGIC = "APK Sig Block 42"
    private const val MAGIC_SIZE = 16
    private const val TRAILER_SIZE = 24
    private const val MAX_BLOCK_SIZE = 20_000_000L
    private const val ID_V2 = 0x7109871aL
    private const val ID_V3 = 0xf05368c0L

    /**
     * 检测 APK 的签名方案，返回如 "V1 + V2" / "未签名"。
     */
    private fun detectSignature(apkPath: String): String {
        if (apkPath.isEmpty()) return "未签名"
        val schemes = mutableListOf<String>()

        // V1：META-INF 下的 .RSA/.DSA/.EC 签名文件
        try {
            ZipFile(apkPath).use { zip ->
                val hasV1 = zip.entries().asSequence().any {
                    val n = it.name.uppercase()
                    n.startsWith("META-INF/") &&
                        (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))
                }
                if (hasV1) schemes.add("V1")
            }
        } catch (_: Exception) {
        }

        // V2 / V3：APK Signing Block
        try {
            val ids = readSigningBlockIds(apkPath)
            if (ids.contains(ID_V2)) schemes.add("V2")
            if (ids.contains(ID_V3)) schemes.add("V3")
        } catch (_: Exception) {
        }

        return if (schemes.isEmpty()) "未签名" else schemes.joinToString(" + ")
    }

    private fun readSigningBlockIds(apkPath: String): Set<Long> {
        RandomAccessFile(apkPath, "r").use { file ->
            val fileLength = file.length()
            val eocdOffset = findEocdOffset(file, fileLength) ?: return emptySet()
            if (eocdOffset < 4) return emptySet()
            val centralDirOffset = readUInt32LE(file, eocdOffset + 16) ?: return emptySet()
            if (centralDirOffset < MAGIC_SIZE + TRAILER_SIZE || centralDirOffset > fileLength) return emptySet()

            val magicBytes = ByteArray(MAGIC_SIZE)
            file.seek(centralDirOffset - MAGIC_SIZE)
            file.readFully(magicBytes)
            if (String(magicBytes, Charsets.US_ASCII) != MAGIC) return emptySet()

            val blockSize = readLongLE(file, centralDirOffset - TRAILER_SIZE)
            val blockStart = centralDirOffset - 8 - blockSize
            if (blockSize !in 1..MAX_BLOCK_SIZE || blockStart < 0) return emptySet()
            if (readLongLE(file, blockStart) != blockSize) return emptySet()

            val payloadStart = blockStart + 8
            val payloadEnd = centralDirOffset - TRAILER_SIZE
            val payloadLength = payloadEnd - payloadStart
            if (payloadLength !in 0..MAX_BLOCK_SIZE) return emptySet()

            val payload = ByteArray(payloadLength.toInt())
            file.seek(payloadStart)
            file.readFully(payload)

            val ids = mutableSetOf<Long>()
            var offset = 0
            while (offset + 8 <= payload.size) {
                val pairLength = readLongLE(payload, offset)
                val idOffset = offset + 8
                if (pairLength < 4 || idOffset + pairLength > payload.size) break
                ids.add(readUInt32LE(payload, idOffset))
                offset += (8 + pairLength).toInt()
            }
            return ids
        }
    }

    private fun findEocdOffset(file: RandomAccessFile, fileLength: Long): Long? {
        val minSize = 22
        val maxComment = 0xFFFF
        if (fileLength < minSize) return null
        val searchSize = minOf(fileLength, (minSize + maxComment).toLong()).toInt()
        val buffer = ByteArray(searchSize)
        file.seek(fileLength - searchSize)
        file.readFully(buffer)
        for (pos in (searchSize - minSize) downTo 0) {
            if (readUInt32LE(buffer, pos) == 0x06054b50L) {
                val commentLength = readUInt16LE(buffer, pos + 20)
                if (pos + minSize + commentLength == searchSize) {
                    return fileLength - searchSize + pos
                }
            }
        }
        return null
    }

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun readLongLE(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = result or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return result
    }

    private fun readLongLE(file: RandomAccessFile, offset: Long): Long {
        val bytes = ByteArray(8)
        file.seek(offset)
        file.readFully(bytes)
        return readLongLE(bytes, 0)
    }

    private fun readUInt32LE(file: RandomAccessFile, offset: Long): Long? {
        val bytes = ByteArray(4)
        file.seek(offset)
        file.readFully(bytes)
        val value = readUInt32LE(bytes, 0)
        // ZIP64 哨兵值表示真实偏移在 ZIP64 EOCD 中，此处不支持
        return if (value == 0xFFFFFFFFL) null else value
    }

    /** 跳转到当前应用详情设置页的 Intent */
    fun buildAppSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}
