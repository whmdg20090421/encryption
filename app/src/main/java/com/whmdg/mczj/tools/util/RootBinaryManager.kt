package com.whmdg.mczj.tools.util

import android.content.Context
import android.util.Log
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import java.io.File

/**
 * 管理打包在 APK 中的 7za 静态二进制。
 * 首次使用时从 lib 目录复制到 filesDir/bin/，chmod 赋予可执行权限。
 */
object RootBinaryManager {
    private var binaryPath: String? = null

    private const val TAG = "RootBinaryManager"
    private const val BINARY_NAME = "lib7zzs.so"
    private const val TARGET_NAME = "7zzs"

    /**
     * 初始化二进制。返回可执行路径，失败返回 null。
     * 可多次调用，不会因首次失败而阻止重试。
     */
    fun init(context: Context): String? {
        if (binaryPath != null) return binaryPath

        val target = File(AppDataPaths.binaries(context), TARGET_NAME)

        // 检查是否已存在且可执行
        if (target.exists() && target.canExecute() && target.length() > 0) {
            binaryPath = target.absolutePath
            Log.d(TAG, "二进制已存在: ${target.absolutePath}")
            return target.absolutePath
        }

        // 从 nativeLibraryDir 复制（系统分配的真实路径）
        val libDir = context.applicationInfo.nativeLibraryDir
        if (libDir == null) {
            Log.e(TAG, "nativeLibraryDir 为 null")
            return tryAlternativePaths(context, target)
        }
        val source = File(libDir, BINARY_NAME)
        if (source.exists()) {
            Log.d(TAG, "从 nativeLibraryDir 复制: ${source.absolutePath}")
            return doCopy(source, target, context)
        }

        Log.w(TAG, "nativeLibraryDir 中未找到 $BINARY_NAME, libDir=$libDir")
        return tryAlternativePaths(context, target)
    }

    /** 尝试备用路径查找二进制 */
    private fun tryAlternativePaths(context: Context, target: File): String? {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        val candidates = listOf(
            "/data/app/${context.packageName}/lib/$abi/$BINARY_NAME",
            "/data/app/${context.packageName}==/lib/$abi/$BINARY_NAME",
            "/data/app/${context.packageName}-${context.packageName}/lib/$abi/$BINARY_NAME"
        )
        for (path in candidates) {
            val f = File(path)
            if (f.exists()) {
                Log.d(TAG, "从备用路径复制: $path")
                return doCopy(f, target, context)
            }
        }
        Log.e(TAG, "所有路径均未找到 $BINARY_NAME, 尝试过的路径: $candidates")
        return null
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
            if (!target.exists() || target.length() == 0L) {
                Log.e(TAG, "复制后目标文件无效: exists=${target.exists()} size=${target.length()}")
                return null
            }
            binaryPath = target.absolutePath
            Log.i(TAG, "二进制初始化成功: ${target.absolutePath} (${target.length()} bytes)")
            return target.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "复制失败: ${e.message}", e)
            return null
        }
    }

    fun getBinaryPath(): String =
        binaryPath ?: throw IllegalStateException("7za binary not initialized")

    fun isReady(): Boolean = binaryPath != null
}
