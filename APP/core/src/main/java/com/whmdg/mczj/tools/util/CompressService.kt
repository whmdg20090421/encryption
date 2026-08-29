package com.whmdg.mczj.tools.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 压缩服务，通过 P7zipDaemon 持久守护进程实现。
 * 自动使用最高可用权限（Permission.MAX）。
 */
object CompressService {

    private const val TAG = "CompressService"

    data class CompressOptions(
        val sourcePaths: List<String>,
        val outputPath: String,
        val format: String,           // zip, 7z, tar, tar.gz, tar.bz2, tar.xz
        val compressionLevel: Int,    // 0-9 (format dependent)
        val password: String = "",    // 空=不加密
        val useAes: Boolean = false,  // zip 加密方式
        val encryptNames: Boolean = false, // 7z 加密文件名（-mhe=on）
    )

    data class ExtractOptions(
        val archivePath: String,
        val outputDir: String,
        val password: String = "",
        val fileSizes: List<Long>,       // 每个文件的原始大小（从 7zzs l 获取，顺序与 -bsp1 一致）
        val totalUncompressedBytes: Long  // 解压后总字节数
    )

    data class ProgressInfo(
        val currentFile: Int,
        val totalFiles: Int,
        val progress: Float,          // 0.0 - 1.0
        val currentFileName: String = "",
        val bytesProcessed: Long = 0,
        val totalBytes: Long = 0
    )

    interface ProgressCallback {
        fun onProgress(info: ProgressInfo)
        fun onComplete(success: Boolean, outputPath: String?, error: String?)
    }

    /**
     * 压缩入口（挂起函数，在 Dispatchers.IO 上执行）。
     * 通过 P7zipClient 流式执行，自动使用最高可用权限。
     */
    suspend fun compress(
        context: Context,
        options: CompressOptions,
        permissionLevel: String,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) = withContext(Dispatchers.IO) {
        val totalBytes = options.sourcePaths.sumOf { calculateTotalBytes(it) }
        val totalFiles = options.sourcePaths.sumOf { countFiles(it) }

        try {
            val result = P7zipClient.compressStream(
                sourcePaths = options.sourcePaths,
                outputPath = options.outputPath,
                format = options.format,
                level = options.compressionLevel,
                password = options.password,
                useAes = options.useAes,
                encryptNames = options.encryptNames
            ) { line ->
                if (!cancelFlag.get()) {
                    parseProgressLine(line, totalBytes, totalFiles, callback)
                }
            }
            result.fold(
                onSuccess = { callback.onComplete(true, options.outputPath, null) },
                onFailure = { e -> callback.onComplete(false, null, e.message ?: "压缩失败") }
            )
        } catch (e: CancellationException) {
            callback.onComplete(false, null, "压缩已取消")
        } catch (e: Exception) {
            callback.onComplete(false, null, e.message ?: "压缩失败")
        }
    }

    /**
     * 解压入口（挂起函数，在 Dispatchers.IO 上执行）。
     * 通过 P7zipClient 流式执行，自动使用最高可用权限。
     */
    suspend fun extract(
        context: Context,
        options: ExtractOptions,
        permissionLevel: String,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) = withContext(Dispatchers.IO) {
        try {
            val result = P7zipClient.extractStream(
                archivePath = options.archivePath,
                outputDir = options.outputDir,
                password = options.password
            ) { line ->
                if (!cancelFlag.get()) {
                    parseExtractProgressLine(line, options.fileSizes, options.totalUncompressedBytes, callback)
                }
            }
            result.fold(
                onSuccess = { callback.onComplete(true, options.outputDir, null) },
                onFailure = { e -> callback.onComplete(false, null, e.message ?: "解压失败") }
            )
        } catch (e: CancellationException) {
            callback.onComplete(false, null, "解压已取消")
        } catch (e: Exception) {
            callback.onComplete(false, null, e.message ?: "解压失败")
        }
    }

    /** 解析 7zzs -bsp1 输出的进度行，计算真实总体进度 */
    private fun parseProgressLine(
        line: String,
        totalBytes: Long,
        totalFiles: Int,
        callback: ProgressCallback
    ) {
        // 匹配 "  75%  1" 格式（百分比 + 文件序号）
        val match = Regex("""\s*(\d+)%\s+(\d+)""").find(line) ?: return
        val percent = match.groupValues[1].toIntOrNull() ?: return
        val fileNum = match.groupValues[2].toIntOrNull() ?: return
        if (percent < 0 || percent > 100) return

        // 真实总体进度：
        // 单文件: 直接用 percent
        // 多文件: (当前文件序号-1 + 当前文件进度/100) / 总文件数
        val overallProgress = if (totalFiles <= 1) {
            percent / 100f
        } else {
            ((fileNum - 1).coerceAtLeast(0) + percent / 100f) / totalFiles
        }.coerceIn(0f, 1f)

        val info = ProgressInfo(
            currentFile = fileNum,
            totalFiles = totalFiles,
            progress = overallProgress,
            bytesProcessed = (totalBytes * overallProgress).toLong(),
            totalBytes = totalBytes
        )
        callback.onProgress(info)
    }

    /**
     * 解压专用进度解析，基于 fileSizes 计算真实已解压字节数。
     * bytesProcessed = 已完成文件字节和 + 当前文件大小 * 当前百分比
     */
    private fun parseExtractProgressLine(
        line: String,
        fileSizes: List<Long>,
        totalBytes: Long,
        callback: ProgressCallback
    ) {
        val match = Regex("""\s*(\d+)%\s+(\d+)""").find(line) ?: return
        val percent = match.groupValues[1].toIntOrNull() ?: return
        val fileNum = match.groupValues[2].toIntOrNull() ?: return
        if (percent < 0 || percent > 100 || fileNum < 1) return

        var completedBytes = 0L
        for (i in 0 until (fileNum - 1).coerceAtMost(fileSizes.size)) {
            completedBytes += fileSizes[i]
        }
        val currentFileIdx = (fileNum - 1).coerceIn(0, fileSizes.size - 1)
        val currentFilePartial = fileSizes[currentFileIdx] * percent / 100L
        val bytesProcessed = (completedBytes + currentFilePartial).coerceAtMost(totalBytes)
        val overallProgress = if (totalBytes > 0) {
            (bytesProcessed.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            percent / 100f
        }

        val info = ProgressInfo(
            currentFile = fileNum,
            totalFiles = fileSizes.size,
            progress = overallProgress,
            bytesProcessed = bytesProcessed,
            totalBytes = totalBytes
        )
        callback.onProgress(info)
    }

    /** 计算路径总字节数 */
    private fun calculateTotalBytes(path: String): Long {
        val f = File(path)
        return if (f.isDirectory) {
            f.walkTopDown().filter { it.isFile && !it.isHidden }.sumOf { it.length() }
        } else {
            f.length()
        }
    }

    /** 计算路径总文件数 */
    private fun countFiles(path: String): Int {
        val f = File(path)
        return if (f.isDirectory) {
            f.walkTopDown().filter { it.isFile && !it.isHidden }.count()
        } else {
            1
        }
    }

    fun getSuffix(format: String): String = when (format) {
        "zip" -> ".zip"
        "7z" -> ".7z"
        "tar" -> ".tar"
        "tar.gz" -> ".tar.gz"
        "tar.bz2" -> ".tar.bz2"
        "tar.xz" -> ".tar.xz"
        else -> ".zip"
    }

    fun getLevelRange(format: String): IntRange? = when (format) {
        "zip", "7z", "tar.gz", "tar.xz" -> 0..9
        "tar.bz2" -> 1..9
        "tar" -> null
        else -> 0..9
    }

    fun getDefaultLevel(format: String): Int = 5
}
