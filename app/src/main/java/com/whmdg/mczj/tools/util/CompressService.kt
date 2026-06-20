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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 压缩服务，支持多种格式、多源文件、进度回调、取消。
 */
object CompressService {

    private const val TAG = "CompressService"
    private const val BUFFER_SIZE = 8192

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
        val progress: Float,          // 0.0 - 1.0（基于字节大小）
        val currentFileName: String = "",
        val bytesProcessed: Long = 0,
        val totalBytes: Long = 0
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
            DiagnosticLog.log("Compress", "压缩失败: ${e.message}")
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
        val sources = options.sourcePaths.map { File(it) }
        val files = collectFiles(sources)
        val total = files.size.coerceAtLeast(1)
        val counter = AtomicInteger(0)

        if (options.password.isNotEmpty()) {
            compressZip4j(options, sources, files, total, counter, cancelFlag, callback)
        } else {
            compressZipStandard(options, sources, files, total, counter, cancelFlag, callback)
        }
    }

    private fun compressZip4j(
        options: CompressOptions,
        sources: List<File>,
        files: List<File>,
        total: Int,
        counter: AtomicInteger,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) {
        val zipFile = ZipFile(options.outputPath, options.password.toCharArray())
        val baseParams = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = if (options.useAes) EncryptionMethod.AES else EncryptionMethod.ZIP_STANDARD
            aesKeyStrength = if (options.useAes) AesKeyStrength.KEY_STRENGTH_256 else null
            compressionLevel = zip4jLevel(options.compressionLevel)
        }

        // zip4j addFile 会递归添加目录内容，所以只传文件，目录条目由 zip4j 自动创建
        val filesOnly = files.filter { it.isFile }

        if (cancelFlag.get()) {
            File(options.outputPath).delete()
            callback.onComplete(false, null, "已取消")
            return
        }

        for (file in filesOnly) {
            if (cancelFlag.get()) {
                File(options.outputPath).delete()
                callback.onComplete(false, null, "已取消")
                return
            }
            val params = ZipParameters()
            params.isEncryptFiles = baseParams.isEncryptFiles
            params.encryptionMethod = baseParams.encryptionMethod
            params.aesKeyStrength = baseParams.aesKeyStrength
            params.compressionLevel = baseParams.compressionLevel
            params.fileNameInZip = computeEntryName(file, sources)
            zipFile.addFile(file, params)
        }

        val count = filesOnly.size
        callback.onProgress(ProgressInfo(count, total, 1f))
        callback.onComplete(true, options.outputPath, null)
    }

    private fun compressZipStandard(
        options: CompressOptions,
        sources: List<File>,
        files: List<File>,
        total: Int,
        counter: AtomicInteger,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) {
        val totalSize = files.sumOf { if (it.isFile) it.length() else 0L }.coerceAtLeast(1L)
        var bytesProcessed = 0L

        ZipOutputStream(BufferedOutputStream(FileOutputStream(options.outputPath))).use { zos ->
            zos.setLevel(options.compressionLevel.coerceIn(0, 9))

            for (file in files) {
                if (cancelFlag.get()) {
                    File(options.outputPath).delete()
                    callback.onComplete(false, null, "已取消")
                    return
                }

                val entryName = computeEntryName(file, sources)
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
                if (file.isFile) bytesProcessed += file.length()
                callback.onProgress(ProgressInfo(count, total, bytesProcessed.toFloat() / totalSize, file.name, bytesProcessed, totalSize))
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
        val sources = options.sourcePaths.map { File(it) }
        val files = collectFiles(sources)
        val total = files.size.coerceAtLeast(1)
        val counter = AtomicInteger(0)
        val totalSize = files.sumOf { if (it.isFile) it.length() else 0L }.coerceAtLeast(1L)
        var bytesProcessed = 0L

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

                val entryName = computeEntryName(file, sources)
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
                if (file.isFile) bytesProcessed += file.length()
                callback.onProgress(ProgressInfo(count, total, bytesProcessed.toFloat() / totalSize, file.name, bytesProcessed, totalSize))
            }
        } finally {
            tarOut.finish()
            tarOut.close()
        }
        callback.onComplete(true, options.outputPath, null)
    }

    // ── 7z 压缩 ──

    private fun compress7z(
        options: CompressOptions,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) {
        val sources = options.sourcePaths.map { File(it) }
        val files = collectFiles(sources)
        val total = files.size.coerceAtLeast(1)
        val counter = AtomicInteger(0)
        val totalSize = files.sumOf { if (it.isFile) it.length() else 0L }.coerceAtLeast(1L)
        var bytesProcessed = 0L

        val sevenZFile = SevenZOutputFile(File(options.outputPath))
        try {
            for (file in files) {
                if (cancelFlag.get()) {
                    sevenZFile.close()
                    File(options.outputPath).delete()
                    callback.onComplete(false, null, "已取消")
                    return
                }

                val entryName = computeEntryName(file, sources)
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
                if (file.isFile) bytesProcessed += file.length()
                callback.onProgress(ProgressInfo(count, total, bytesProcessed.toFloat() / totalSize, file.name, bytesProcessed, totalSize))
            }
        } finally {
            sevenZFile.finish()
            sevenZFile.close()
        }
        callback.onComplete(true, options.outputPath, null)
    }

    // ── 工具函数 ──

    /**
     * 计算文件在压缩包内的相对路径。
     * 单源目录：以目录本身为根（如 /a/b/ → b/file.txt）
     * 单源文件：直接用文件名
     * 多源：以公共父目录为根，保留各文件的相对路径
     */
    private fun computeEntryName(file: File, sources: List<File>): String {
        if (sources.size == 1) {
            val source = sources[0]
            return if (source.isDirectory) {
                file.absolutePath.removePrefix(source.absolutePath).removePrefix("/")
            } else {
                file.name
            }
        }
        // 多源：找公共父目录
        val commonParent = findCommonParent(sources)
        return if (commonParent != null) {
            file.absolutePath.removePrefix(commonParent.absolutePath).removePrefix("/")
        } else {
            file.name
        }
    }

    /** 找到多个文件的公共父目录 */
    private fun findCommonParent(files: List<File>): File? {
        if (files.isEmpty()) return null
        val paths = files.map { if (it.isDirectory) it.absolutePath else (it.parent ?: it.absolutePath) }
        val parts = paths.map { it.split(File.separator).filter { s -> s.isNotEmpty() } }
        val common = parts.reduce { acc, list -> acc.zip(list).takeWhile { (a, b) -> a == b }.map { it.first } }
        return if (common.isEmpty()) File("/") else File(common.joinToString(File.separator, File.separator))
    }

    /** 递归收集多个源的文件和目录 */
    private fun collectFiles(sources: List<File>): List<File> {
        val result = mutableListOf<File>()
        for (source in sources) {
            collectFilesRecursive(source, result)
        }
        return result
    }

    private fun collectFilesRecursive(source: File, result: MutableList<File>) {
        if (source.isDirectory) {
            result.add(source)
            source.listFiles()?.sortedBy { it.name }?.forEach { child ->
                collectFilesRecursive(child, result)
            }
        } else {
            result.add(source)
        }
    }

    /** 转换压缩级别到 zip4j 的 CompressionLevel */
    private fun zip4jLevel(level: Int): CompressionLevel {
        return when {
            level <= 0 -> CompressionLevel.NO_COMPRESSION
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
        else -> ".zip"
    }

    /** 各格式压缩级别范围 */
    fun getLevelRange(format: String): IntRange? = when (format) {
        "zip", "7z", "tar.gz", "tar.xz" -> 0..9
        "tar.bz2" -> 1..9
        "tar" -> null
        else -> 0..9
    }

    /** 各格式默认压缩级别 */
    fun getDefaultLevel(format: String): Int = 5
}
