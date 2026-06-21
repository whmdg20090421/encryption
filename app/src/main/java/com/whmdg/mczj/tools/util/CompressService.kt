package com.whmdg.mczj.tools.util

import android.content.Context
import android.util.Log
import com.whmdg.mczj.tools.security.ShizukuAuthorizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 压缩服务，通过 7zzs 静态二进制实现，支持三条权限路径（普通/Shizuku/Root）。
 */
object CompressService {

    private const val TAG = "CompressService"

    data class CompressOptions(
        val sourcePaths: List<String>,
        val outputPath: String,
        val format: String,           // zip, 7z, tar, tar.gz, tar.bz2, tar.xz
        val compressionLevel: Int,    // 0-9 (format dependent)
        val password: String = "",    // 空=不加密
        val useAes: Boolean = true,   // zip 加密方式
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
     * @param permissionLevel "NORMAL" / "SHIZUKU" / "ROOT"
     * @param cancelFlag 外部设为 true 取消任务
     */
    suspend fun compress(
        context: Context,
        options: CompressOptions,
        permissionLevel: String,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) = withContext(Dispatchers.IO) {
        // 1. 确定二进制路径（统一提取）
        val binaryPath = resolveBinary(context)
        if (binaryPath == null) {
            callback.onComplete(false, null, "无法准备压缩工具，请检查权限或重新安装应用")
            return@withContext
        }

        // 2. 预计算总字节数和文件数
        val totalBytes = options.sourcePaths.sumOf { calculateTotalBytes(it) }
        val totalFiles = options.sourcePaths.sumOf { countFiles(it) }

        // 3. 构建命令
        val cmd = SevenZipCommand.build(binaryPath, options)
        Log.d(TAG, "压缩命令: $cmd")

        // 4. 按权限路径执行
        try {
            when (permissionLevel) {
                "ROOT" -> executeWithProcessBuilder(
                    arrayOf("su", "-c", cmd), totalBytes, totalFiles, cancelFlag, callback
                )
                "SHIZUKU" -> executeViaShizuku(
                    context, cmd, totalBytes, totalFiles, cancelFlag, callback
                )
                else -> executeWithProcessBuilder(
                    arrayOf("sh", "-c", cmd), totalBytes, totalFiles, cancelFlag, callback
                )
            }
            callback.onComplete(true, options.outputPath, null)
        } catch (e: CancellationException) {
            callback.onComplete(false, null, "压缩已取消")
        } catch (e: Exception) {
            callback.onComplete(false, null, e.message ?: "压缩失败")
        }
    }

    /** 确定二进制路径（统一提取到 AppDataPaths.binaries()） */
    private fun resolveBinary(context: Context): String? {
        return try {
            BinaryExtractor.ensureExtracted(context).absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "提取二进制失败", e)
            null
        }
    }

    /**
     * ProcessBuilder 执行（普通/Root 权限）。
     * 实时逐行读取 stdout 解析进度。
     */
    private suspend fun executeWithProcessBuilder(
        command: Array<String>,
        totalBytes: Long,
        totalFiles: Int,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)  // stderr 合并到 stdout
            .start()

        try {
            // 后台线程逐行读取 stdout
            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (cancelFlag.get()) break
                            parseProgressLine(line!!, totalBytes, totalFiles, callback)
                        }
                    }
                } catch (_: Exception) {}
            }
            readerThread.start()

            // 等待进程完成（协程可取消）
            while (process.isAlive) {
                if (cancelFlag.get() || !isActive) {
                    process.destroyForcibly()
                    break
                }
                delay(100)
            }

            readerThread.join(5000)
            val exitCode = process.waitFor()

            if (cancelFlag.get()) throw CancellationException("用户取消")
            if (exitCode != 0) throw RuntimeException("7zzs 退出码: $exitCode")
        } catch (e: CancellationException) {
            process.destroyForcibly()
            throw e
        }
    }

    /**
     * Shizuku 权限执行。
     * 通过 AIDL 调用 ShellService.executeStreaming()，轮询进度文件。
     */
    private suspend fun executeViaShizuku(
        context: Context,
        cmd: String,
        totalBytes: Long,
        totalFiles: Int,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) = withContext(Dispatchers.IO) {
        val service = ShizukuAuthorizer.getShellService()
            ?: throw IllegalStateException("Shizuku UserService 未连接")

        // 进度临时文件（app 进程创建，ShellService 进程写入）
        val progressFile = File.createTempFile("compress_progress", ".txt", context.cacheDir)

        try {
            // 启动进度轮询协程
            val pollJob = launch {
                var lastKey = ""
                while (isActive && !cancelFlag.get()) {
                    delay(300)
                    try {
                        val content = progressFile.readText().trim()
                        if (content.startsWith("DONE:")) break
                        // 格式: "percent:fileNum"
                        val parts = content.split(":")
                        val percent = parts.getOrNull(0)?.toIntOrNull() ?: continue
                        val fileNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
                        val key = "$percent:$fileNum"
                        if (key != lastKey) {
                            lastKey = key
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
                            withContext(Dispatchers.Main) { callback.onProgress(info) }
                        }
                    } catch (_: Exception) {}
                }
            }

            // 同步调用 ShellService（阻塞直到完成）
            service.executeStreaming(cmd, progressFile.absolutePath)

            pollJob.cancel()

            // 读取最终结果
            val result = progressFile.readText().trim()
            val exitCode = result.removePrefix("DONE:").trim().toIntOrNull() ?: -1

            if (cancelFlag.get()) throw CancellationException("用户取消")
            if (exitCode != 0) throw RuntimeException("7zzs 退出码: $exitCode")
        } finally {
            progressFile.delete()
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
