package com.whmdg.mczj.tools.util

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import java.io.File

/**
 * 7zzs 二进制文件管理。
 * 从 nativeLibraryDir 提取到 AppDataPaths.binaries() 并设置可执行权限。
 * 所有权限级别统一使用提取后的路径，不直接使用 nativeLibraryDir。
 */
object BinaryExtractor {
    private const val TAG = "BinaryExtractor"

    /** 提取到 AppDataPaths.binaries() 并 chmod 755，返回目标文件 */
    fun ensureExtracted(context: Context): File {
        val target = File(AppDataPaths.binaries(context), "7zzs")
        if (target.exists() && target.canExecute() && target.length() > 0) return target

        val src = File("${context.applicationInfo.nativeLibraryDir}/lib7zzs.so")
        if (!src.exists()) throw IllegalStateException("7zzs 二进制缺失，请重新安装应用")

        src.copyTo(target, overwrite = true)
        val exitCode = Runtime.getRuntime()
            .exec(arrayOf("chmod", "755", target.absolutePath))
            .waitFor()
        if (exitCode != 0) throw IllegalStateException("无法准备压缩工具")

        return target
    }
}
