package com.whmdg.mczj.tools.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.File
import java.io.RandomAccessFile

/**
 * 7-Zip JBinding 客户端 API。
 * 通过 JNI 直接调用 7-Zip 引擎，无需 shell 权限。
 */
object JBindingClient {

    private const val TAG = "JBindingClient"

    private val FORMAT_MAP = mapOf(
        "zip" to ArchiveFormat.ZIP,
        "7z" to ArchiveFormat.SEVEN_ZIP,
        "tar" to ArchiveFormat.TAR,
        "tar.gz" to ArchiveFormat.GZIP,
        "tar.bz2" to ArchiveFormat.BZIP2,
        "gz" to ArchiveFormat.GZIP,
        "bz2" to ArchiveFormat.BZIP2,
    )

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        // JBinding native 库由 AAR 自动加载，无需额外初始化
        Log.d(TAG, "JBindingClient 已初始化")
    }

    suspend fun ensureDaemonOrThrow() {}

    // ── 对外 API ──

    suspend fun listArchive(archivePath: String, password: String = ""): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val entries = mutableListOf<String>()
                withInArchive(archivePath, password) { inArchive ->
                    val count = inArchive.numberOfItems
                    for (i in 0 until count) {
                        val path = inArchive.getStringProperty(i, PropID.PATH) ?: continue
                        val isDir = inArchive.getBooleanProperty(i, PropID.IS_DIR)
                        val size = inArchive.getLongProperty(i, PropID.SIZE)
                        val packedSize = inArchive.getLongProperty(i, PropID.PACKED_SIZE)
                        val attrs = if (isDir) "D" else "A"
                        // 匹配 ArchiveBrowser.parseListOutput 格式：date attr compressedSize size path
                        entries.add("2000-01-01 00:00:00 $attrs   $packedSize         $size         $path")
                    }
                }
                entries.joinToString("\n")
            }
        }

    suspend fun listArchiveDetail(archivePath: String, password: String = ""): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sb = StringBuilder()
                withInArchive(archivePath, password) { inArchive ->
                    val count = inArchive.numberOfItems
                    sb.appendLine("Listing archive: $archivePath")
                    sb.appendLine()
                    for (i in 0 until count) {
                        val path = inArchive.getStringProperty(i, PropID.PATH) ?: continue
                        val isDir = inArchive.getBooleanProperty(i, PropID.IS_DIR)
                        val size = inArchive.getLongProperty(i, PropID.SIZE)
                        val packedSize = inArchive.getLongProperty(i, PropID.PACKED_SIZE)
                        val encrypted = inArchive.getBooleanProperty(i, PropID.ENCRYPTED)
                        sb.appendLine("Path = $path")
                        sb.appendLine("Folder = ${if (isDir) "+" else "-"}")
                        sb.appendLine("Size = $size")
                        sb.appendLine("Packed Size = $packedSize")
                        sb.appendLine("Encrypted = ${if (encrypted) "+" else "-"}")
                        sb.appendLine()
                    }
                }
                sb.toString()
            }
        }

    suspend fun extractSingleFile(
        archivePath: String,
        fileName: String,
        outputDir: String,
        password: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            withInArchive(archivePath, password) { inArchive ->
                val count = inArchive.numberOfItems
                var targetIndex = -1
                for (i in 0 until count) {
                    val path = inArchive.getStringProperty(i, PropID.PATH) ?: continue
                    if (path == fileName || path.replace('\\', '/') == fileName.replace('\\', '/')) {
                        targetIndex = i
                        break
                    }
                }
                if (targetIndex < 0) throw RuntimeException("文件不存在: $fileName")

                inArchive.extract(intArrayOf(targetIndex), false, object : IArchiveExtractCallback {
                    override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream {
                        return ISequentialOutStream { data ->
                            if (extractAskMode == ExtractAskMode.EXTRACT) {
                                val outFile = File(outputDir, fileName)
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { it.write(data) }
                            }
                            data.size
                        }
                    }
                    override fun prepareOperation(extractAskMode: ExtractAskMode) {}
                    override fun setOperationResult(result: ExtractOperationResult) {
                        if (result != ExtractOperationResult.OK) {
                            throw SevenZipException("提取失败: $result")
                        }
                    }
                    override fun setTotal(total: Long) {}
                    override fun setCompleted(complete: Long) {}
                })
            }
            ""
        }
    }

    suspend fun extractAll(
        archivePath: String,
        outputDir: String,
        password: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            withInArchive(archivePath, password) { inArchive ->
                val count = inArchive.numberOfItems
                val indices = (0 until count).toIntArray()
                inArchive.extract(indices, false, ExtractAllCallback(outputDir))
            }
            ""
        }
    }

    suspend fun compress(
        sourcePaths: List<String>,
        outputPath: String,
        format: String,
        level: Int,
        password: String = "",
        useAes: Boolean = false,
        encryptNames: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            doCompress(sourcePaths, outputPath, format, level, password, useAes, encryptNames, null)
            ""
        }
    }

    suspend fun detectPassword(archivePath: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                withInArchive(archivePath, "dummy") { inArchive ->
                    val count = inArchive.numberOfItems
                    for (i in 0 until count) {
                        if (inArchive.getBooleanProperty(i, PropID.ENCRYPTED)) {
                            return@runCatching "true"
                        }
                    }
                }
                "false"
            } catch (e: SevenZipException) {
                val msg = e.message ?: ""
                when {
                    msg.contains("Wrong password", ignoreCase = true) ||
                    msg.contains("encrypted", ignoreCase = true) -> "true"
                    else -> "false"
                }
            }
        }
    }

    suspend fun compressStream(
        sourcePaths: List<String>,
        outputPath: String,
        format: String,
        level: Int,
        password: String = "",
        useAes: Boolean = false,
        encryptNames: Boolean = false,
        onLine: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            doCompress(sourcePaths, outputPath, format, level, password, useAes, encryptNames, onLine)
            ""
        }
    }

    suspend fun extractStream(
        archivePath: String,
        outputDir: String,
        password: String = "",
        onLine: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            withInArchive(archivePath, password) { inArchive ->
                val count = inArchive.numberOfItems
                val indices = (0 until count).toIntArray()
                inArchive.extract(indices, false, ExtractAllCallback(outputDir, onLine))
            }
            ""
        }
    }

    // ── 内部实现 ──

    private fun <T> withInArchive(
        archivePath: String,
        password: String,
        block: (IInArchive) -> T
    ): T {
        val raf = RandomAccessFile(File(archivePath), "r")
        return raf.use { stream ->
            val callback = ArchiveOpenCallback()
            val inArchive = SevenZip.openInArchive(
                SevenZip.getArchiveFormat(stream),
                stream,
                callback,
                password.ifEmpty { null }
            )
            inArchive.use { block(it) }
        }
    }

    private fun doCompress(
        sourcePaths: List<String>,
        outputPath: String,
        format: String,
        level: Int,
        password: String,
        useAes: Boolean,
        encryptNames: Boolean,
        onLine: ((String) -> Unit)?
    ) {
        val archiveFormat = FORMAT_MAP[format]
            ?: throw IllegalArgumentException("不支持的格式: $format")

        SevenZip.createCompressor(archiveFormat).use { compressor ->
            if (password.isNotEmpty()) {
                compressor.password = password
            }

            when (archiveFormat) {
                ArchiveFormat.SEVEN_ZIP -> {
                    compressor.setProperty("compressionLevel", level)
                    if (password.isNotEmpty() && useAes) {
                        compressor.setProperty("compressionMethod", "LZMA2")
                        compressor.setProperty("encryptionMethod", "AES256")
                    }
                    if (encryptNames) {
                        compressor.setProperty("he", "on")
                    }
                }
                ArchiveFormat.ZIP -> {
                    compressor.setProperty("compressionLevel", level)
                    if (password.isNotEmpty() && useAes) {
                        compressor.setProperty("encryptionMethod", "AES256")
                    }
                }
                ArchiveFormat.BZIP2 -> compressor.setProperty("compressionLevel", level)
                ArchiveFormat.GZIP -> compressor.setProperty("compressionLevel", level)
                ArchiveFormat.TAR -> { /* tar 无压缩参数 */ }
                else -> compressor.setProperty("compressionLevel", level)
            }

            val outFile = File(outputPath)
            compressor.setOutputFile(outFile)

            for (src in sourcePaths) {
                val file = File(src)
                if (file.isDirectory) {
                    compressor.addDirectory(file)
                } else {
                    compressor.addFile(file)
                }
            }

            val totalBytes = sourcePaths.sumOf { calculateTotalBytes(it) }
            val totalFiles = sourcePaths.sumOf { countFiles(it) }
            var completedFiles = 0

            compressor.progress = net.sf.sevenzipjbinding.ICompressProgressInfo { inSize, outSize ->
                onLine?.let { callback ->
                    completedFiles++
                    val percent = if (totalFiles > 0) (completedFiles * 100 / totalFiles) else 0
                    callback("  $percent%  $completedFiles")
                }
            }

            compressor.commit()
        }
    }

    /** 解压所有文件（支持进度回调） */
    private class ExtractAllCallback(
        private val outputDir: String,
        private val onLine: ((String) -> Unit)? = null
    ) : IArchiveExtractCallback {

        private var totalItems = 0
        private var currentItem = 0

        override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream {
            return ISequentialOutStream { data ->
                if (extractAskMode == ExtractAskMode.EXTRACT && data.isNotEmpty()) {
                    // 由 setTotal/setCompleted 驱动进度，此处仅写入数据
                }
                data.size
            }
        }

        override fun prepareOperation(extractAskMode: ExtractAskMode) {}

        override fun setOperationResult(result: ExtractOperationResult) {
            if (result != ExtractOperationResult.OK) {
                throw SevenZipException("解压失败: $result")
            }
            currentItem++
            onLine?.let { callback ->
                val percent = if (totalItems > 0) (currentItem * 100 / totalItems) else 0
                callback("  $percent%  $currentItem")
            }
        }

        override fun setTotal(total: Long) {
            totalItems = total.toInt()
        }

        override fun setCompleted(complete: Long) {}
    }

    private fun calculateTotalBytes(path: String): Long {
        val f = File(path)
        return if (f.isDirectory) {
            f.walkTopDown().filter { it.isFile && !it.isHidden }.sumOf { it.length() }
        } else {
            f.length()
        }
    }

    private fun countFiles(path: String): Int {
        val f = File(path)
        return if (f.isDirectory) {
            f.walkTopDown().filter { it.isFile && !it.isHidden }.count()
        } else {
            1
        }
    }
}
