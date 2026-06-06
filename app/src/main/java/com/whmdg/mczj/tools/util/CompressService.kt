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
import java.util.zip.ZipFile as JavaZipFile
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
        val jxlPackZip: Boolean = false, // JXL 是否打包成 ZIP
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
                "jxl" -> compressJxl(options, cancelFlag, callback)
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

    // ── JPEG XL 图片压缩 ──

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "bmp", "gif", "tiff", "tif", "heic", "heif", "avif"
    )

    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    private fun compressJxl(
        options: CompressOptions,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) {
        val source = File(options.sourcePath)
        val allFiles = collectFiles(source).filter { it.isFile }
        val jpegExtensions = setOf("jpg", "jpeg")
        val imageFiles = allFiles.filter { it.extension.lowercase() in IMAGE_EXTENSIONS }
        val nonImageFiles = allFiles.filter { it.extension.lowercase() !in IMAGE_EXTENSIONS }

        if (imageFiles.isEmpty()) {
            callback.onComplete(false, null, "未找到可处理的图片文件")
            return
        }

        val effortArray = arrayOf(
            com.awxkee.jxlcoder.JxlEffort.LIGHTNING,   // 1
            com.awxkee.jxlcoder.JxlEffort.THUNDER,     // 2
            com.awxkee.jxlcoder.JxlEffort.FALCON,      // 3
            com.awxkee.jxlcoder.JxlEffort.CHEETAH,     // 4
            com.awxkee.jxlcoder.JxlEffort.HARE,        // 5
            com.awxkee.jxlcoder.JxlEffort.WOMBAT,      // 6
            com.awxkee.jxlcoder.JxlEffort.SQUIRREL,    // 7
            com.awxkee.jxlcoder.JxlEffort.KITTEN,      // 8
            com.awxkee.jxlcoder.JxlEffort.TORTOISE,    // 9
            com.awxkee.jxlcoder.JxlEffort.GLACIER,     // 10
        )
        val effort = effortArray[options.compressionLevel.coerceIn(1, 10) - 1]
        val total = allFiles.size
        val counter = AtomicInteger(0)

        val workDir = if (options.jxlPackZip) {
            File(options.outputPath).parentFile!!.resolve(".jxl_tmp_${System.currentTimeMillis()}").apply { mkdirs() }
        } else if (source.isDirectory) {
            File(options.outputPath).apply { mkdirs() }
        } else {
            File(options.outputPath).parentFile!!
        }

        try {
            for (file in imageFiles) {
                if (cancelFlag.get()) {
                    if (options.jxlPackZip) workDir.deleteRecursively()
                    callback.onComplete(false, null, "已取消")
                    return
                }

                val relativePath = file.absolutePath.removePrefix(source.absolutePath).removePrefix("/")
                val outFile = File(workDir, relativePath.substringBeforeLast('.') + ".jxl")
                outFile.parentFile?.mkdirs()

                if (file.extension.lowercase() in jpegExtensions) {
                    // JPG/JPEG：无损转码，直接打包 DCT 系数，体积不会增大
                    val jpegBytes = file.readBytes()
                    val jxlBytes = com.awxkee.jxlcoder.JxlCoder.construct(jpegBytes)
                    outFile.writeBytes(jxlBytes)
                } else {
                    // 其他图片：解码像素后无损编码
                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap == null) {
                        Log.w(TAG, "无法解码图片: ${file.name}")
                        counter.incrementAndGet()
                        continue
                    }
                    val jxlBytes = com.awxkee.jxlcoder.JxlCoder.encode(bitmap, compressionOption = com.awxkee.jxlcoder.JxlCompressionOption.LOSSLESS, quality = 100, effort = effort)
                    bitmap.recycle()
                    outFile.writeBytes(jxlBytes)
                }

                val count = counter.incrementAndGet()
                callback.onProgress(ProgressInfo(count, total, count.toFloat() / total, file.name))
            }

            // 非图片文件：直接复制到输出目录
            for (file in nonImageFiles) {
                if (cancelFlag.get()) {
                    if (options.jxlPackZip) workDir.deleteRecursively()
                    callback.onComplete(false, null, "已取消")
                    return
                }
                val relativePath = file.absolutePath.removePrefix(source.absolutePath).removePrefix("/")
                val outFile = File(workDir, relativePath)
                outFile.parentFile?.mkdirs()
                file.copyTo(outFile, overwrite = true)

                val count = counter.incrementAndGet()
                callback.onProgress(ProgressInfo(count, total, count.toFloat() / total, file.name))
            }

            val resultPath = if (options.jxlPackZip) {
                val zipFile = File(options.outputPath)
                packDirToZip(workDir, zipFile, options.password, options.useAes, cancelFlag, callback)
                workDir.deleteRecursively()
                zipFile.absolutePath
            } else if (source.isDirectory) {
                workDir.absolutePath
            } else {
                val baseName = source.nameWithoutExtension
                File(workDir, "$baseName.jxl").absolutePath
            }
            callback.onComplete(true, resultPath, null)
        } catch (e: Exception) {
            Log.e(TAG, "JXL 压缩失败", e)
            if (options.jxlPackZip) workDir.deleteRecursively()
            callback.onComplete(false, null, e.message ?: "JXL 压缩失败")
        }
    }

    /** 将目录打包成 ZIP 文件 */
    private fun packDirToZip(
        sourceDir: File,
        zipFile: File,
        password: String,
        useAes: Boolean,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) {
        if (password.isNotEmpty()) {
            val z = net.lingala.zip4j.ZipFile(zipFile.absolutePath, password.toCharArray())
            val params = net.lingala.zip4j.model.ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = if (useAes) {
                    net.lingala.zip4j.model.enums.EncryptionMethod.AES
                } else {
                    net.lingala.zip4j.model.enums.EncryptionMethod.ZIP_STANDARD
                }
                aesKeyStrength = if (useAes) net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256 else null
            }
            val files = sourceDir.walkTopDown().filter { it.isFile }.toList()
            for (f in files) {
                if (cancelFlag.get()) return
                params.fileNameInZip = f.relativeTo(sourceDir).path
                z.addFile(f, params)
            }
        } else {
            java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(zipFile))).use { zos ->
                val files = sourceDir.walkTopDown().filter { it.isFile }.toList()
                for (f in files) {
                    if (cancelFlag.get()) return
                    zos.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(sourceDir).path))
                    java.io.BufferedInputStream(java.io.FileInputStream(f), 8192).use { bis ->
                        val buf = ByteArray(8192)
                        var len: Int
                        while (bis.read(buf).also { len = it } != -1) {
                            if (cancelFlag.get()) return
                            zos.write(buf, 0, len)
                        }
                    }
                    zos.closeEntry()
                }
            }
        }
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
        "jxl" -> ".jxl"
        else -> ".zip"
    }

    /** 各格式压缩级别范围 */
    fun getLevelRange(format: String): IntRange? = when (format) {
        "zip", "7z", "tar.gz", "tar.xz" -> 0..9
        "tar.bz2" -> 1..9  // BZip2 blockSize 参数，最小为 1
        "jxl" -> 1..10     // JPEG XL Effort（1=最快，10=最大压缩比）
        "tar" -> null  // 仅打包，无压缩
        else -> 0..9
    }

    /** 各格式默认压缩级别 */
    fun getDefaultLevel(format: String): Int = when (format) {
        "jxl" -> 7  // SQUIRREL
        else -> 5
    }

    // ══════════════════════════════════════════════════════
    //  压缩包浏览：索引读取 & 按需解压
    // ══════════════════════════════════════════════════════

    /** 内存虚拟文件系统（按需加载，不预存内容） */
    data class ArchiveMemFs(
        val entries: Map<String, ArchiveMemEntry>,
        val reader: ArchiveReader,
        val format: String,
        val password: String
    )

    sealed class ArchiveMemEntry {
        abstract val name: String
        abstract val path: String   // 相对路径，如 "dir/subdir/file.txt"
    }

    class ArchiveMemDir(
        override val name: String,
        override val path: String,
        var size: Long = 0,          // 目录内所有文件的原始大小之和
        var compressedSize: Long = 0 // 目录内所有文件的压缩后大小之和
    ) : ArchiveMemEntry()

    class ArchiveMemFile(
        override val name: String,
        override val path: String,
        val size: Long,
        val compressedSize: Long,
        val entryIndex: Int
    ) : ArchiveMemEntry()

    /** 抽象读取器，各格式实现按需读取单个条目内容 */
    interface ArchiveReader {
        fun readEntry(entry: ArchiveMemFile): ByteArray
        fun close()
    }

    /**
     * 自底向上计算每个目录的 size / compressedSize。
     * 按路径深度降序排列，子目录先于父目录处理，
     * 这样父目录聚合时子目录的值已经算好了。
     */
    private fun calcDirSizes(entries: MutableMap<String, ArchiveMemEntry>) {
        val dirs = entries.values.filterIsInstance<ArchiveMemDir>()
            .sortedByDescending { it.path.count { c -> c == '/' } }
        for (dir in dirs) {
            val prefix = dir.path + "/"
            var sumSize = 0L
            var sumCompressed = 0L
            for ((path, entry) in entries) {
                if (!path.startsWith(prefix)) continue
                val remainder = path.removePrefix(prefix)
                // 只统计直接子项（子目录或文件），不重复统计孙辈
                if (remainder.contains('/')) continue
                when (entry) {
                    is ArchiveMemFile -> {
                        sumSize += entry.size
                        sumCompressed += entry.compressedSize
                    }
                    is ArchiveMemDir -> {
                        sumSize += entry.size
                        sumCompressed += entry.compressedSize
                    }
                }
            }
            dir.size = sumSize
            dir.compressedSize = sumCompressed
        }
    }

    /**
     * 根据文件扩展名检测压缩格式。
     * 返回 null 表示不是支持的压缩格式。
     */
    fun detectFormat(fileName: String): String? = when {
        fileName.endsWith(".zip", true) -> "zip"
        fileName.endsWith(".7z", true) -> "7z"
        fileName.endsWith(".tar", true) -> "tar"
        fileName.endsWith(".tar.gz", true) || fileName.endsWith(".tgz", true) -> "tar.gz"
        fileName.endsWith(".tar.bz2", true) || fileName.endsWith(".tbz2", true) -> "tar.bz2"
        fileName.endsWith(".tar.xz", true) || fileName.endsWith(".txz", true) -> "tar.xz"
        fileName.endsWith(".rar", true) -> "rar"
        else -> null
    }

    /**
     * 通过 magic bytes 检测压缩格式（扩展名不可靠时的后备方案）。
     * 读取文件头部几个字节判断。
     */
    fun detectFormatByMagic(file: File): String? {
        if (!file.exists() || file.length() < 4) return null
        try {
            val header = file.inputStream().use { fis ->
                val buf = ByteArray(8)
                fis.read(buf)
                buf
            }
            // ZIP: PK\x03\x04
            if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) return "zip"
            // 7z: 7z\xbc\xaf\x27\x1c
            if (header.size >= 6 &&
                header[0] == 0x37.toByte() && header[1] == 0x7A.toByte() &&
                header[2] == 0xBC.toByte() && header[3] == 0xAF.toByte() &&
                header[4] == 0x27.toByte() && header[5] == 0x1C.toByte()) return "7z"
            // RAR: Rar!\x1a\x07 (RAR4) or Rar!\x1a\x07\x01\x00 (RAR5)
            if (header.size >= 6 &&
                header[0] == 0x52.toByte() && header[1] == 0x61.toByte() &&
                header[2] == 0x72.toByte() && header[3] == 0x21.toByte() &&
                header[4] == 0x1A.toByte() && header[5] == 0x07.toByte()) return "rar"
            // GZIP: \x1f\x8b
            if (header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()) return "tar.gz"
            // BZ2: BZ
            if (header[0] == 0x42.toByte() && header[1] == 0x5A.toByte()) return "tar.bz2"
            // XZ: \xFD7zXZ\x00
            if (header.size >= 6 &&
                header[0] == 0xFD.toByte() && header[1] == 0x37.toByte() &&
                header[2] == 0x7A.toByte() && header[3] == 0x58.toByte() &&
                header[4] == 0x5A.toByte() && header[5] == 0x00.toByte()) return "tar.xz"
            // TAR: 后续判断（tar magic offset 257 = "ustar"）
            if (file.length() > 263) {
                file.inputStream().use { fis ->
                    fis.skip(257)
                    val magic = ByteArray(5)
                    fis.read(magic)
                    if (magic[0] == 0x75.toByte() && magic[1] == 0x73.toByte() &&
                        magic[2] == 0x74.toByte() && magic[3] == 0x61.toByte() &&
                        magic[4] == 0x72.toByte()) return "tar"
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * 检测压缩包是否加密。
     * 对于 ZIP 使用 zip4j 检测，RAR 使用 junrar 检测。
     * TAR 系列不支持加密，始终返回 false。
     */
    fun isEncrypted(archivePath: String, format: String): Boolean {
        return try {
            when (format) {
                "zip" -> {
                    val zf = net.lingala.zip4j.ZipFile(archivePath)
                    zf.isValidZipFile && zf.fileHeaders.any { (it as net.lingala.zip4j.model.FileHeader).isEncrypted }
                }
                "rar" -> {
                    val arch = com.github.junrar.Archive(File(archivePath))
                    val encrypted = arch.mainHeader.isEncrypted
                    arch.close()
                    encrypted
                }
                else -> false // tar 系列不支持加密
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 验证密码是否正确。
     * ZIP: 尝试用 zip4j 读取第一个文件头验证。
     * RAR: junrar 无法在不实际解压的情况下验证密码，返回 true（依赖实际解压时的异常）。
     * TAR: 不加密，始终 true。
     */
    fun verifyPassword(archivePath: String, format: String, password: String): Boolean {
        return try {
            when (format) {
                "zip" -> {
                    val zf = net.lingala.zip4j.ZipFile(archivePath)
                    if (!zf.isValidZipFile) return false
                    if (password.isEmpty()) return true
                    // 尝试读取第一个条目的 InputStream 来验证密码
                    val headers = zf.fileHeaders
                    if (headers.isEmpty()) return true
                    val firstHeader = headers.first() as net.lingala.zip4j.model.FileHeader
                    if (!firstHeader.isEncrypted) return true
                    zf.setPassword(password.toCharArray())
                    zf.getInputStream(firstHeader).use { it.read() }
                    true
                }
                "rar" -> {
                    // junrar 无法预验证密码，返回 true 由实际解压时报错
                    true
                }
                else -> true
            }
        } catch (e: Exception) {
            // zip4j 密码错误会抛出异常
            Log.w(TAG, "密码验证失败: ${e.message}")
            false
        }
    }

    /**
     * 仅读取压缩包目录结构索引（不解压内容），返回虚拟文件系统。
     */
    fun openArchiveIndex(
        archivePath: String,
        format: String,
        password: String
    ): ArchiveMemFs {
        val file = File(archivePath)
        if (!file.exists()) throw IllegalArgumentException("文件不存在: $archivePath")

        return when (format) {
            "zip" -> openZipIndex(archivePath, password)
            "7z" -> open7zIndex(archivePath, password)
            "tar", "tar.gz", "tar.bz2", "tar.xz" -> openTarIndex(archivePath, format)
            "rar" -> openRarIndex(archivePath, password)
            else -> throw IllegalArgumentException("不支持的格式: $format")
        }
    }

    private fun openZipIndex(archivePath: String, password: String): ArchiveMemFs {
        val entries = mutableMapOf<String, ArchiveMemEntry>()
        val zf = net.lingala.zip4j.ZipFile(archivePath)
        val headers = zf.fileHeaders

        // 先确保所有父目录都存在
        val allPaths = headers.map { (it as net.lingala.zip4j.model.FileHeader).fileName }
        val dirPaths = mutableSetOf<String>()
        for (p in allPaths) {
            var current = p.substringBeforeLast("/", "").trimEnd('/')
            while (current.isNotEmpty()) {
                dirPaths.add(current)
                current = current.substringBeforeLast("/", "").trimEnd('/')
            }
        }
        for (dp in dirPaths) {
            val name = dp.substringAfterLast("/")
            if (name.isNotEmpty() && !entries.containsKey(dp)) {
                entries[dp] = ArchiveMemDir(name, dp)
            }
        }

        headers.forEachIndexed { index, header ->
            val fh = header as net.lingala.zip4j.model.FileHeader
            val path = fh.fileName.trimEnd('/')
            if (path.isEmpty()) return@forEachIndexed
            val name = path.substringAfterLast("/")
            if (fh.isDirectory) {
                if (!entries.containsKey(path)) {
                    entries[path] = ArchiveMemDir(name, path)
                }
            } else {
                entries[path] = ArchiveMemFile(
                    name = name,
                    path = path,
                    size = fh.uncompressedSize,
                    compressedSize = fh.compressedSize,
                    entryIndex = index
                )
            }
        }

        calcDirSizes(entries)
        val reader = ZipArchiveReader(archivePath, password)
        return ArchiveMemFs(entries, reader, "zip", password)
    }

    private fun open7zIndex(archivePath: String, password: String): ArchiveMemFs {
        val entries = mutableMapOf<String, ArchiveMemEntry>()
        val sevenZFile = if (password.isNotEmpty()) {
            org.apache.commons.compress.archivers.sevenz.SevenZFile(File(archivePath), password.toCharArray())
        } else {
            org.apache.commons.compress.archivers.sevenz.SevenZFile(File(archivePath))
        }

        val dirPaths = mutableSetOf<String>()
        var index = 0
        var entry = sevenZFile.nextEntry
        while (entry != null) {
            val path = entry.name.trimEnd('/')
            if (path.isNotEmpty()) {
                val name = path.substringAfterLast("/")
                // 确保父目录存在
                var current = path.substringBeforeLast("/", "").trimEnd('/')
                while (current.isNotEmpty()) {
                    if (dirPaths.add(current)) {
                        val dirName = current.substringAfterLast("/")
                        entries[current] = ArchiveMemDir(dirName, current)
                    }
                    current = current.substringBeforeLast("/", "").trimEnd('/')
                }
                if (entry.isDirectory) {
                    if (!entries.containsKey(path)) {
                        entries[path] = ArchiveMemDir(name, path)
                    }
                } else {
                    entries[path] = ArchiveMemFile(
                        name = name,
                        path = path,
                        size = entry.size,
                        compressedSize = 0, // 7z 不提供单条目压缩大小
                        entryIndex = index
                    )
                }
            }
            index++
            entry = sevenZFile.nextEntry
        }
        sevenZFile.close()

        calcDirSizes(entries)
        val reader = SevenZArchiveReader(archivePath, password)
        return ArchiveMemFs(entries, reader, "7z", password)
    }

    private fun openTarIndex(archivePath: String, format: String): ArchiveMemFs {
        val entries = mutableMapOf<String, ArchiveMemEntry>()
        val fis = File(archivePath).inputStream()
        val buffered = java.io.BufferedInputStream(fis)

        val compressedIn = when (format) {
            "tar.gz" -> org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(buffered)
            "tar.bz2" -> org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(buffered)
            "tar.xz" -> org.apache.commons.compress.compressors.xz.XZCompressorInputStream(buffered)
            else -> null
        }

        val tarIn = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(compressedIn ?: buffered)
        val dirPaths = mutableSetOf<String>()
        var index = 0

        try {
            var entry = tarIn.nextEntry
            while (entry != null) {
                val path = entry.name.trimEnd('/')
                if (path.isNotEmpty()) {
                    val name = path.substringAfterLast("/")
                    // 确保父目录存在
                    var current = path.substringBeforeLast("/", "").trimEnd('/')
                    while (current.isNotEmpty()) {
                        if (dirPaths.add(current)) {
                            val dirName = current.substringAfterLast("/")
                            entries[current] = ArchiveMemDir(dirName, current)
                        }
                        current = current.substringBeforeLast("/", "").trimEnd('/')
                    }
                    if (entry.isDirectory) {
                        if (!entries.containsKey(path)) {
                            entries[path] = ArchiveMemDir(name, path)
                        }
                    } else {
                        entries[path] = ArchiveMemFile(
                            name = name,
                            path = path,
                            size = entry.size,
                            compressedSize = 0,
                            entryIndex = index
                        )
                    }
                }
                index++
                entry = tarIn.nextEntry
            }
        } finally {
            tarIn.close()
        }

        calcDirSizes(entries)
        val reader = TarArchiveReader(archivePath, format)
        return ArchiveMemFs(entries, reader, format, "")
    }

    private fun openRarIndex(archivePath: String, password: String): ArchiveMemFs {
        val entries = mutableMapOf<String, ArchiveMemEntry>()
        val arch = com.github.junrar.Archive(File(archivePath))
        val dirPaths = mutableSetOf<String>()
        var index = 0

        var header = arch.nextFileHeader()
        while (header != null) {
            if (!header.isEncrypted || password.isNotEmpty()) {
                val rawName = header.fileName.trimEnd('/')
                if (rawName.isNotEmpty()) {
                    val path = rawName.replace('\\', '/') // RAR 可能用反斜杠
                    val name = path.substringAfterLast("/")
                    // 确保父目录存在
                    var current = path.substringBeforeLast("/", "").trimEnd('/')
                    while (current.isNotEmpty()) {
                        if (dirPaths.add(current)) {
                            val dirName = current.substringAfterLast("/")
                            entries[current] = ArchiveMemDir(dirName, current)
                        }
                        current = current.substringBeforeLast("/", "").trimEnd('/')
                    }
                    if (header.isDirectory) {
                        if (!entries.containsKey(path)) {
                            entries[path] = ArchiveMemDir(name, path)
                        }
                    } else {
                        entries[path] = ArchiveMemFile(
                            name = name,
                            path = path,
                            size = header.fullUnpackSize,
                            compressedSize = header.fullPackSize,
                            entryIndex = index
                        )
                    }
                }
            }
            index++
            header = arch.nextFileHeader()
        }
        arch.close()

        calcDirSizes(entries)
        val reader = RarArchiveReader(archivePath, password)
        return ArchiveMemFs(entries, reader, "rar", password)
    }

    /**
     * 按需解压单个文件到 ByteArray（用于文本文件等小文件）。
     */
    fun extractSingleFile(
        archivePath: String,
        format: String,
        password: String,
        entry: ArchiveMemFile
    ): ByteArray {
        val reader = when (format) {
            "zip" -> ZipArchiveReader(archivePath, password)
            "7z" -> SevenZArchiveReader(archivePath, password)
            "tar", "tar.gz", "tar.bz2", "tar.xz" -> TarArchiveReader(archivePath, format)
            "rar" -> RarArchiveReader(archivePath, password)
            else -> throw IllegalArgumentException("不支持的格式: $format")
        }
        return try {
            reader.readEntry(entry)
        } finally {
            reader.close()
        }
    }

    /**
     * 按需解压单个文件到磁盘临时文件，返回临时文件路径。
     * 用于图片文件等需要文件路径的场景。
     */
    fun extractSingleFileToDisk(
        archivePath: String,
        format: String,
        password: String,
        entry: ArchiveMemFile,
        targetDir: File
    ): File {
        if (!targetDir.exists()) targetDir.mkdirs()
        val data = extractSingleFile(archivePath, format, password, entry)
        val targetFile = File(targetDir, entry.name)
        targetFile.writeBytes(data)
        return targetFile
    }

    // ── 各格式 ArchiveReader 实现 ──

    private class ZipArchiveReader(
        private val archivePath: String,
        private val password: String
    ) : ArchiveReader {
        override fun readEntry(entry: ArchiveMemFile): ByteArray {
            val zf = net.lingala.zip4j.ZipFile(archivePath)
            if (password.isNotEmpty()) zf.setPassword(password.toCharArray())
            val header = zf.fileHeaders[entry.entryIndex] as net.lingala.zip4j.model.FileHeader
            zf.getInputStream(header).use { ins ->
                return ins.readBytes()
            }
        }
        override fun close() {}
    }

    private class SevenZArchiveReader(
        private val archivePath: String,
        private val password: String
    ) : ArchiveReader {
        override fun readEntry(entry: ArchiveMemFile): ByteArray {
            val sevenZFile = if (password.isNotEmpty()) {
                org.apache.commons.compress.archivers.sevenz.SevenZFile(File(archivePath), password.toCharArray())
            } else {
                org.apache.commons.compress.archivers.sevenz.SevenZFile(File(archivePath))
            }
            try {
                // 7z 不支持随机访问，必须顺序遍历到目标条目
                var idx = 0
                var current = sevenZFile.nextEntry
                while (current != null) {
                    if (idx == entry.entryIndex) {
                        return sevenZFile.getInputStream(current).use { it.readBytes() }
                    }
                    idx++
                    current = sevenZFile.nextEntry
                }
                throw IllegalStateException("条目未找到: ${entry.path}")
            } finally {
                sevenZFile.close()
            }
        }
        override fun close() {}
    }

    private class TarArchiveReader(
        private val archivePath: String,
        private val format: String
    ) : ArchiveReader {
        override fun readEntry(entry: ArchiveMemFile): ByteArray {
            val fis = File(archivePath).inputStream()
            val buffered = java.io.BufferedInputStream(fis)
            val compressedIn = when (format) {
                "tar.gz" -> org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(buffered)
                "tar.bz2" -> org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(buffered)
                "tar.xz" -> org.apache.commons.compress.compressors.xz.XZCompressorInputStream(buffered)
                else -> null
            }
            val tarIn = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(compressedIn ?: buffered)
            try {
                var idx = 0
                var current = tarIn.nextEntry
                while (current != null) {
                    if (idx == entry.entryIndex) {
                        return tarIn.readBytes()
                    }
                    idx++
                    current = tarIn.nextEntry
                }
                throw IllegalStateException("条目未找到: ${entry.path}")
            } finally {
                tarIn.close()
            }
        }
        override fun close() {}
    }

    private class RarArchiveReader(
        private val archivePath: String,
        private val password: String
    ) : ArchiveReader {
        override fun readEntry(entry: ArchiveMemFile): ByteArray {
            val arch = com.github.junrar.Archive(File(archivePath))
            if (password.isNotEmpty()) arch.setPassword(password)
            try {
                var idx = 0
                var header = arch.nextFileHeader()
                while (header != null) {
                    if (idx == entry.entryIndex) {
                        val baos = java.io.ByteArrayOutputStream()
                        arch.extractFile(header, baos)
                        return baos.toByteArray()
                    }
                    idx++
                    header = arch.nextFileHeader()
                }
                throw IllegalStateException("条目未找到: ${entry.path}")
            } finally {
                arch.close()
            }
        }
        override fun close() {}
    }
}
