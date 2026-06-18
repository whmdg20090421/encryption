package com.whmdg.mczj.tools.util

import android.content.Context
import android.os.Build
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import java.io.File

/**
 * 管理打包在 APK 中的 7za 静态二进制。
 * 首次使用时从 lib 目录复制到 filesDir/bin/，chmod 赋予可执行权限。
 */
object RootBinaryManager {
    private var binaryPath: String? = null
    private var initTried = false

    /**
     * 初始化二进制。返回可执行路径，失败返回 null。
     */
    fun init(context: Context): String? {
        if (binaryPath != null) return binaryPath
        if (initTried) return null
        initTried = true

        val target = File(context.filesDir, "bin/7zzs")

        // 检查是否已存在且可执行
        if (target.exists() && target.canExecute() && target.length() > 0) {
            binaryPath = target.absolutePath
            return target.absolutePath
        }

        // 从 APK lib 目录复制
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        val source = File("/data/app/${context.packageName}/lib/$abi/lib7zzs.so")
        if (!source.exists()) {
            // 部分设备路径不同，尝试 base 路径
            val altSource = File("/data/app/${context.packageName}==/lib/$abi/lib7zzs.so")
            if (!altSource.exists()) return null
            return doCopy(altSource, target, context)
        }
        return doCopy(source, target, context)
    }

    private fun doCopy(source: File, target: File, context: Context): String? {
        try {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            // 先尝试 Java API 设置可执行权限（app 自有目录通常可行）
            if (!target.setExecutable(true, false)) {
                // Java API 失败，尝试 shell chmod（root 或 Shizuku）
                val chmodCmd = "chmod 755 '${target.absolutePath}'"
                if (SpecialPermissionVerifier.isRootAvailable()) {
                    SpecialPermissionVerifier.executeRootCommandFull(chmodCmd)
                } else if (SpecialPermissionVerifier.isShizukuAuthorized(context)) {
                    SpecialPermissionVerifier.executeShizukuCommand(chmodCmd)
                }
            }
            if (!target.exists() || target.length() == 0L) return null
            binaryPath = target.absolutePath
            return target.absolutePath
        } catch (e: Exception) {
            return null
        }
    }

    fun getBinaryPath(): String =
        binaryPath ?: throw IllegalStateException("7za binary not initialized")

    fun isReady(): Boolean = binaryPath != null
}
