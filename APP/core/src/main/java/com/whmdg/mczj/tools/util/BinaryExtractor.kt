package com.whmdg.mczj.tools.util

import android.content.Context
import java.io.File

/**
 * 7zzs 二进制文件管理。
 * 直接使用系统从 APK 解压到 nativeLibraryDir 的路径，无需额外复制。
 * APK 构建配置需保证 jniLibs.useLegacyPackaging = true，否则系统不解压到磁盘。
 */
object BinaryExtractor {
    private const val TAG = "BinaryExtractor"

    /** 返回 nativeLibraryDir 中的 7zzs 路径 */
    fun ensureExtracted(context: Context): File {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
            ?: throw IllegalStateException("nativeLibraryDir 为 null，请重新安装应用")
        val src = File(nativeLibDir, "lib7zzs.so")
        if (!src.exists()) {
            throw IllegalStateException(
                "7zzs 二进制缺失（路径=${src.absolutePath}，nativeLibraryDir=$nativeLibDir），请重新安装应用"
            )
        }
        return src
    }
}
