package com.whmdg.mczj.tools.util

import android.content.Context
import java.io.File

/**
 * PV (Pipe Viewer) 二进制文件管理。
 * 直接使用系统从 APK 解压到 nativeLibraryDir 的路径，无需额外复制。
 * APK 构建配置需保证 jniLibs.useLegacyPackaging = true，否则系统不解压到磁盘。
 */
object PvExtractor {
    private const val TAG = "PvExtractor"

    /** 返回 nativeLibraryDir 中的 pv 路径 */
    fun ensureExtracted(context: Context): File {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
            ?: throw IllegalStateException("nativeLibraryDir 为 null，请重新安装应用")
        val src = File(nativeLibDir, "libpv.so")
        if (!src.exists()) {
            throw IllegalStateException(
                "PV 二进制缺失（路径=${src.absolutePath}，nativeLibDir=$nativeLibDir），请重新安装应用"
            )
        }
        return src
    }
}
