package com.whmdg.mczj.tools.util

import android.util.Log
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 压缩服务，支持多种格式、进度回调、取消。
 */
object CompressService {

    private const val TAG = "CompressService"
    private const val BUFFER_SIZE = 8192

    data class CompressOptions(
        val sourcePath: String,
        val outputPath: String,
        val format: String,           // zip, 7z, tar, tar.gz, tar.bz2, tar.xz, rar
        val compressionLevel: Int,    // 0-9 (format dependent)
        val password: String = "",    // 空=不加密
        val useAes: Boolean = true,   // zip 加密方式
    )

    data class ProgressInfo(
        val currentFile: Int,
        val totalFiles: Int,
        val progress: Float,          // 0.0 - 1.0
        val currentFileName: String = ""
    )

    interface ProgressCallback {
        fun onProgress(info: ProgressInfo)
        fun onComplete(success: Boolean, outputPath: String?, error: String?)
    }

    /**
     * 压缩入口。
     * @param cancelFlag 外部可设为 true 取消任务
     */
    fun compress(
        options: CompressOptions,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) {
        try {
            when (options.format) {
                "zip" -> compressZip(options, cancelFlag, callback)
                "7z" -> compress7z(options, cancelFlag, callback)
                "tar" -> compressTar(options, cancelFlag, callback, null)
                "tar.gz" -> compressTar(options, cancelFlag, callback, "gz")
                "tar.bz2" -> compressTar(options, cancelFlag, callback, "bz2")
                "tar.xz" -> compressTar(options, cancelFlag, callback, "xz")
                else -> callback.onComplete(false, null, "不支持的格式: ${options.format}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "压缩失败", e)
            // 清理残余文件
            try { File(options.outputPath).delete() } catch (_: Exception) {}
            callback.onComplete(false, null, e.message ?: "未知错误")
        }
    }

    // ── ZIP 压缩 ──

    private fun compressZip(
        options: CompressOptions,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) {
        val source = File(options.sourcePath)
        val files = collectFiles(source)
        val total = files.size.coerceAtLeast(1)
        val counter = AtomicInteger(0)

        if (options.password.isNotEmpty()) {
            // zip4j 加密压缩
            compressZip4j(options, source, files, total, counter, cancelFlag, callback)
        } else {
            // 标准 java.util.zip（无密码时更轻量）
            compressZipStandard(options, source, files, total, counter, cancelFlag, callback)
        }
    }

    private fun compressZip4j(
        options: CompressOptions,
        source: File,
        files: List<File>,
        total: Int,
        counter: AtomicInteger,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) {
        val zipFile = ZipFile(options.outputPath, options.password.toCharArray())
        val params = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = if (options.useAes) EncryptionMethod.AES else EncryptionMethod.ZIP_STANDARD
            aesKeyStrength = if (options.useAes) AesKeyStrength.KEY_STRENGTH_256 else null
            compressionLevel = zip4jLevel(options.compressionLevel)
        }

        if (source.isDirectory) {
            zipFile.addFolder(source, params)
            callback.onProgress(ProgressInfo(total, total, 1f))
        } else {
            zipFile.addFile(source, params)
            callback.onProgress(ProgressInfo(1, 1, 1f))
        }

        if (cancelFlag.get()) {
            File(options.outputPath).delete()
            callback.onComplete(false, null, "已取消")
            return
        }

        callback.onComplete(true, options.outputPath, null)
    }

    private fun compressZipStandard(
        options: CompressOptions,
        source: File,
        files: List<File>,
        total: Int,
        counter: AtomicInteger,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) {
        val baseDir = if (source.isDirectory) source.parentFile else source.parentFile
        ZipOutputStream(BufferedOutputStream(FileOutputStream(options.outputPath))).use { zos ->
            zos.setLevel(options.compressionLevel.coerceIn(0, 9))

            for (file in files) {
                if (cancelFlag.get()) {
                    File(options.outputPath).delete()
                    callback.onComplete(false, null, "已取消")
                    return
                }

                val entryName = baseDir?.let { file.absolutePath.removePrefix(it.absolutePath).removePrefix("/") }
                    ?: file.name
                val entry = ZipEntry(entryName)
                if (file.isDirectory) {
                    entry.isDirectory
                }
                zos.putNextEntry(entry)

                if (file.isFile) {
                    FileInputStream(file).use { fis ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var len: Int
                        while (fis.read(buf).also { len = it } != -1) {
                            if (cancelFlag.get()) {
                                File(options.outputPath).delete()
                                callback.onComplete(false, null, "已取消")
                                return
                            }
                            zos.write(buf, 0, len)
                        }
                    }
                }
                zos.closeEntry()

                val count = counter.incrementAndGet()
                callback.onProgress(ProgressInfo(count, total, count.toFloat() / total, file.name))
            }
        }
        callback.onComplete(true, options.outputPath, null)
    }

    // ── TAR / TAR.GZ / TAR.BZ2 / TAR.XZ 压缩 ──

    private fun compressTar(
        options: CompressOptions,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback,
        compression: String?  // null=tar, "gz", "bz2", "xz"
    ) {
        val source = File(options.sourcePath)
        val files = collectFiles(source)
        val total = files.size.coerceAtLeast(1)
        val counter = AtomicInteger(0)
        val baseDir = if (source.isDirectory) source.parentFile else source.parentFile

        val fos = FileOutputStream(options.outputPath)
        val buffered = BufferedOutputStream(fos)

        val compressedOut = when (compression) {
            "gz" -> {
                val params = GzipParameters().apply { compressionLevel = options.compressionLevel }
                GzipCompressorOutputStream(buffered, params)
            }
            "bz2" -> BZip2CompressorOutputStream(buffered, options.compressionLevel)
            "xz" -> XZCompressorOutputStream(buffered, options.compressionLevel)
            else -> null
        }

        val tarOut = TarArchiveOutputStream(compressedOut ?: buffered)
        tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        tarOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

        try {
            for (file in files) {
                if (cancelFlag.get()) {
                    File(options.outputPath).delete()
                    callback.onComplete(false, null, "已取消")
                    return
                }

                val entryName = baseDir?.let { file.absolutePath.removePrefix(it.absolutePath).removePrefix("/") }
                    ?: file.name
                val entry = TarArchiveEntry(file, entryName)
                tarOut.putArchiveEntry(entry)

                if (file.isFile) {
                    FileInputStream(file).use { fis ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var len: Int
                        while (fis.read(buf).also { len = it } != -1) {
                            if (cancelFlag.get()) {
                                File(options.outputPath).delete()
                                callback.onComplete(false, null, "已取消")
                                return
                            }
                            tarOut.write(buf, 0, len)
                        }
                    }
                }
                tarOut.closeArchiveEntry()

                val count = counter.incrementAndGet()
                callback.onProgress(ProgressInfo(count, total, count.toFloat() / total, file.name))
            }
        } finally {
            tarOut.finish()
            tarOut.close()
            if (compressedOut != null) {
                // compressedOut is closed by tarOut.close()
            } else {
                buffered.close()
            }
        }
        callback.onComplete(true, options.outputPath, null)
    }

    // ── 7z 压缩 ──

    private fun compress7z(
        options: CompressOptions,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) {
        val source = File(options.sourcePath)
        val files = collectFiles(source)
        val total = files.size.coerceAtLeast(1)
        val counter = AtomicInteger(0)
        val baseDir = if (source.isDirectory) source.parentFile else source.parentFile

        val sevenZFile = SevenZOutputFile(File(options.outputPath))
        try {
            for (file in files) {
                if (cancelFlag.get()) {
                    sevenZFile.close()
                    File(options.outputPath).delete()
                    callback.onComplete(false, null, "已取消")
                    return
                }

                val entryName = baseDir?.let { file.absolutePath.removePrefix(it.absolutePath).removePrefix("/") }
                    ?: file.name
                val entry = sevenZFile.createArchiveEntry(file, entryName)
                sevenZFile.putArchiveEntry(entry)

                if (file.isFile) {
                    FileInputStream(file).use { fis ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var len: Int
                        while (fis.read(buf).also { len = it } != -1) {
                            if (cancelFlag.get()) {
                                sevenZFile.close()
                                File(options.outputPath).delete()
                                callback.onComplete(false, null, "已取消")
                                return
                            }
                            sevenZFile.write(buf, 0, len)
                        }
                    }
                }
                sevenZFile.closeArchiveEntry()

                val count = counter.incrementAndGet()
                callback.onProgress(ProgressInfo(count, total, count.toFloat() / total, file.name))
            }
        } finally {
            sevenZFile.finish()
            sevenZFile.close()
        }
        callback.onComplete(true, options.outputPath, null)
    }

    // ── 工具函数 ──

    /** 递归收集文件和目录 */
    private fun collectFiles(source: File): List<File> {
        val result = mutableListOf<File>()
        if (source.isDirectory) {
            result.add(source) // 目录本身作为条目
            source.listFiles()?.sortedBy { it.name }?.forEach { child ->
                result.addAll(collectFiles(child))
            }
        } else {
            result.add(source)
        }
        return result
    }

    /** 转换压缩级别到 zip4j 的 CompressionLevel */
    private fun zip4jLevel(level: Int): CompressionLevel {
        return when {
            level <= 1 -> CompressionLevel.FASTEST
            level <= 3 -> CompressionLevel.FASTER
            level <= 5 -> CompressionLevel.NORMAL
            level <= 7 -> CompressionLevel.MAXIMUM
            else -> CompressionLevel.ULTRA
        }
    }

    /** 获取压缩文件后缀 */
    fun getSuffix(format: String): String = when (format) {
        "zip" -> ".zip"
        "7z" -> ".7z"
        "tar" -> ".tar"
        "tar.gz" -> ".tar.gz"
        "tar.bz2" -> ".tar.bz2"
        "tar.xz" -> ".tar.xz"
        "rar" -> ".rar"
        else -> ".zip"
    }

    /** 各格式压缩级别范围 */
    fun getLevelRange(format: String): IntRange? = when (format) {
        "zip", "7z", "tar.gz", "tar.bz2", "tar.xz" -> 0..9
        "rar" -> 0..5
        "tar" -> null  // 仅打包，无压缩
        else -> 0..9
    }

    /** 各格式默认压缩级别 */
    fun getDefaultLevel(format: String): Int = when (format) {
        "rar" -> 3
        else -> 5
    }
}
