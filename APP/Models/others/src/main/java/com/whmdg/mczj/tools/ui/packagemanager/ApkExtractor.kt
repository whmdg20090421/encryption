package com.whmdg.mczj.tools.ui.packagemanager

import com.whmdg.mczj.tools.util.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 安装包提取器。
 *
 * 把已安装应用的 base APK（以及分包 split APK，若有）提取到目标目录：
 *  - 无分包：单文件复制，扩展名 `.apk`；
 *  - 有分包：打包为 ZIP 归档，扩展名 `.apks`（base + splits，条目保留原文件名）。
 *
 * 提取前若目标同名文件已存在，先回调 [onConflict] 由调用方决定覆盖 / 跳过 / 取消，
 * 用户做出选择后才真正生成文件。
 *
 * 源 APK 位于 `/data/app/` 下，普通进程按绝对路径即可读取，无需 root / Shizuku；
 * 目标目录（用户存储）写入需要「所有文件访问」权限，由 UI 层保证。
 */
object ApkExtractor {

    /** 冲突处理决策。 */
    enum class ConflictDecision { OVERWRITE, SKIP, CANCEL }

    /** 提取进度（按源字节数累加）。 */
    data class Progress(
        val bytesWritten: Long,
        val totalBytes: Long,
        val currentFile: String
    ) {
        val fraction: Float
            get() = if (totalBytes > 0) (bytesWritten.toFloat() / totalBytes) else 0f
    }

    /** 提取结果。 */
    sealed class Result {
        data class Success(
            val outputPath: String,
            val isBundle: Boolean,
            val bytesWritten: Long
        ) : Result()

        /** 用户选择跳过（目标已存在）。 */
        object Skipped : Result()

        /** 用户取消。 */
        object Cancelled : Result()

        data class Failed(val message: String) : Result()
    }

    private const val BUFFER_SIZE = 256 * 1024

    /** 文件名非法字符（Windows/Android 文件系统通用）。 */
    private const val ILLEGAL_CHARS = "/\\:*?\"<>|"

    /**
     * 生成提取文件名：`{应用名}_{版本名}.{apk|apks}`。
     * 版本名为空时仅用应用名；非法字符与空白字符统一替换为 `_`。
     */
    fun buildFileName(appName: String, versionName: String, hasSplits: Boolean): String {
        val name = sanitize(appName)
        val version = sanitize(versionName)
        val base = if (version.isEmpty()) name else "${name}_$version"
        return "$base.${if (hasSplits) "apks" else "apk"}"
    }

    private fun sanitize(raw: String): String {
        val cleaned = raw.map { c ->
            if (c in ILLEGAL_CHARS || c.isWhitespace()) '_' else c
        }.joinToString("")
        return cleaned.trim('_').ifEmpty { "app" }
    }

    /**
     * 执行提取。
     *
     * @param outputDir 目标目录（需已存在或可创建）
     * @param appName 应用名称（用于文件名）
     * @param versionName 版本名称（可为空）
     * @param baseApkPath base APK 绝对路径
     * @param splitApkPaths 分包 APK 路径（不含 base）
     * @param onConflict 目标同名时的冲突回调，挂起等待用户选择
     * @param onProgress 进度回调（在 IO 线程触发）
     * @param cancelFlag 取消标志
     */
    suspend fun extract(
        outputDir: File,
        appName: String,
        versionName: String,
        baseApkPath: String,
        splitApkPaths: List<String>,
        onConflict: suspend (fileName: String) -> ConflictDecision,
        onProgress: (Progress) -> Unit,
        cancelFlag: AtomicBoolean
    ): Result = withContext(Dispatchers.IO) {
        if (baseApkPath.isEmpty() || !File(baseApkPath).exists()) {
            return@withContext Result.Failed("找不到应用安装包：$baseApkPath")
        }

        val splits = splitApkPaths.filter { it.isNotEmpty() && File(it).exists() }
        val isBundle = splits.isNotEmpty()
        val fileName = buildFileName(appName, versionName, isBundle)

        if (!outputDir.exists() && !outputDir.mkdirs()) {
            return@withContext Result.Failed("无法创建目标目录：${outputDir.absolutePath}")
        }

        val target = File(outputDir, fileName)

        // 冲突检查：先问用户，再生成
        if (target.exists()) {
            when (onConflict(fileName)) {
                ConflictDecision.SKIP -> return@withContext Result.Skipped
                ConflictDecision.CANCEL -> return@withContext Result.Cancelled
                ConflictDecision.OVERWRITE -> {
                    if (target.isDirectory || !target.delete()) {
                        return@withContext Result.Failed("无法覆盖已存在的文件：${target.absolutePath}")
                    }
                }
            }
        }

        val sources = buildList {
            add(File(baseApkPath))
            splits.forEach { add(File(it)) }
        }
        val totalBytes = sources.sumOf { it.length() }
        var written = 0L

        try {
            if (isBundle) {
                FileOutputStream(target).use { fos ->
                    ZipOutputStream(fos).use { zos ->
                        for (src in sources) {
                            zos.putNextEntry(ZipEntry(src.name))
                            written = copyStream(src, zos, written, totalBytes, src.name, onProgress, cancelFlag)
                            zos.closeEntry()
                        }
                    }
                }
            } else {
                FileOutputStream(target).use { fos ->
                    written = copyStream(
                        sources.first(), fos, written, totalBytes,
                        sources.first().name, onProgress, cancelFlag
                    )
                }
            }

            onProgress(Progress(written, totalBytes, fileName))
            Result.Success(target.absolutePath, isBundle, written)
        } catch (_: CancelledException) {
            target.delete()
            Result.Cancelled
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程被取消（如宿主界面销毁）：清理残留文件并向上传播，维护结构化并发
            target.delete()
            throw e
        } catch (e: Exception) {
            target.delete()
            DiagnosticLog.log("ApkExtractor", "提取失败: ${e.message}")
            Result.Failed(e.message ?: "提取失败")
        }
    }

    /**
     * 把 [src] 的内容复制到 [out]，返回累加后的已写字节数。
     * 若 [cancelFlag] 被置位则抛出 [CancelledException] 由上层处理。
     */
    private fun copyStream(
        src: File,
        out: java.io.OutputStream,
        alreadyWritten: Long,
        totalBytes: Long,
        currentName: String,
        onProgress: (Progress) -> Unit,
        cancelFlag: AtomicBoolean
    ): Long {
        var written = alreadyWritten
        FileInputStream(src).use { fis ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                if (cancelFlag.get()) throw CancelledException()
                val read = fis.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
                written += read
                onProgress(Progress(written, totalBytes, currentName))
            }
        }
        return written
    }

    private class CancelledException : Exception()
}
