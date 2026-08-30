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
import net.sf.sevenzipjbinding.IOutCreateArchive7z
import net.sf.sevenzipjbinding.IOutCreateArchiveZip
import net.sf.sevenzipjbinding.IOutCreateCallback
import net.sf.sevenzipjbinding.IOutItem7z
import net.sf.sevenzipjbinding.IOutItemZip
import net.sf.sevenzipjbinding.ISequentialInStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.OutItemFactory
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
import java.io.File
import java.io.RandomAccessFile

/**
 * 7-Zip JBinding 客户端 API。
 * 通过 JNI 直接调用 7-Zip 引擎，无需 shell 权限。
 */
object JBindingClient {

    private const val TAG = "JBindingClient"

    fun init(context: Context) {
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
                        val isDir = inArchive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                        val size = inArchive.getProperty(i, PropID.SIZE) as? Long ?: 0L
                        val packedSize = inArchive.getProperty(i, PropID.PACKED_SIZE) as? Long ?: 0L
                        val attrs = if (isDir) "D" else "A"
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
                        val isDir = inArchive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                        val size = inArchive.getProperty(i, PropID.SIZE) as? Long ?: 0L
                        val packedSize = inArchive.getProperty(i, PropID.PACKED_SIZE) as? Long ?: 0L
                        val encrypted = inArchive.getProperty(i, PropID.ENCRYPTED) as? Boolean ?: false
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
                val indices = IntArray(count) { it }
                inArchive.extract(indices, false, ExtractAllCallback(inArchive, outputDir))
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
                var encrypted = false
                withInArchive(archivePath, "dummy") { inArchive ->
                    val count = inArchive.numberOfItems
                    for (i in 0 until count) {
                        if (inArchive.getProperty(i, PropID.ENCRYPTED) as? Boolean ?: false) {
                            encrypted = true
                            break
                        }
                    }
                }
                encrypted.toString()
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
                val indices = IntArray(count) { it }
                inArchive.extract(indices, false, ExtractAllCallback(inArchive, outputDir, onLine))
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
        val stream = RandomAccessFileInStream(raf)
        try {
            val inArchive = if (password.isNotEmpty()) {
                SevenZip.openInArchive(null, stream, password)
            } else {
                SevenZip.openInArchive(null, stream)
            }
            try {
                return block(inArchive)
            } finally {
                inArchive.close()
            }
        } finally {
            stream.close()
            raf.close()
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

        // 收集所有文件
        val allFiles = mutableListOf<File>()
        for (src in sourcePaths) {
            val file = File(src)
            if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile }.forEach { allFiles.add(it) }
            } else {
                allFiles.add(file)
            }
        }

        when (archiveFormat) {
            ArchiveFormat.SEVEN_ZIP -> compress7z(allFiles, outputPath, level, password, useAes, encryptNames, onLine)
            ArchiveFormat.ZIP -> compressZip(allFiles, outputPath, level, password, onLine)
            else -> throw IllegalArgumentException("暂不支持创建 $format 格式")
        }
    }

    private fun compress7z(
        files: List<File>,
        outputPath: String,
        level: Int,
        password: String,
        useAes: Boolean,
        encryptNames: Boolean,
        onLine: ((String) -> Unit)?
    ) {
        val outArchive: IOutCreateArchive7z = SevenZip.openOutArchive7z()
        try {
            outArchive.setLevel(level)
            if (password.isNotEmpty() && useAes) {
                outArchive.setHeaderEncryption(encryptNames)
            }

            val outFile = RandomAccessFile(File(outputPath), "rw")
            val outStream = RandomAccessFileOutStream(outFile)

            outArchive.createArchive(outStream, files.size, object : IOutCreateCallback<IOutItem7z> {
                private var currentItem = 0
                private var totalBytes = 0L

                override fun getItemInformation(index: Int, factory: OutItemFactory<IOutItem7z>): IOutItem7z {
                    val item = factory.createOutItem()
                    val file = files[index]
                    item.propertyPath = file.name
                    item.propertyIsDir = false
                    item.dataSize = file.length()
                    return item
                }

                override fun getStream(index: Int): ISequentialInStream? {
                    val file = files[index]
                    if (file.isDirectory) return null
                    return FileSequentialInStream(file)
                }

                override fun setTotal(total: Long) { totalBytes = total }
                override fun setCompleted(complete: Long) {
                    onLine?.let { callback ->
                        val percent = if (totalBytes > 0) (complete * 100 / totalBytes).toInt() else 0
                        callback("  $percent%  ${currentItem + 1}")
                    }
                }
                override fun setOperationResult(operationResultOk: Boolean) {
                    currentItem++
                }
            })

            outStream.close()
            outFile.close()
        } finally {
            outArchive.close()
        }
    }

    private fun compressZip(
        files: List<File>,
        outputPath: String,
        level: Int,
        password: String,
        onLine: ((String) -> Unit)?
    ) {
        val outArchive: IOutCreateArchiveZip = SevenZip.openOutArchiveZip()
        try {
            outArchive.setLevel(level)

            val outFile = RandomAccessFile(File(outputPath), "rw")
            val outStream = RandomAccessFileOutStream(outFile)

            outArchive.createArchive(outStream, files.size, object : IOutCreateCallback<IOutItemZip> {
                private var currentItem = 0
                private var totalBytes = 0L

                override fun getItemInformation(index: Int, factory: OutItemFactory<IOutItemZip>): IOutItemZip {
                    val item = factory.createOutItem()
                    val file = files[index]
                    item.propertyPath = file.name
                    item.propertyIsDir = false
                    item.dataSize = file.length()
                    return item
                }

                override fun getStream(index: Int): ISequentialInStream? {
                    val file = files[index]
                    if (file.isDirectory) return null
                    return FileSequentialInStream(file)
                }

                override fun setTotal(total: Long) { totalBytes = total }
                override fun setCompleted(complete: Long) {
                    onLine?.let { callback ->
                        val percent = if (totalBytes > 0) (complete * 100 / totalBytes).toInt() else 0
                        callback("  $percent%  ${currentItem + 1}")
                    }
                }
                override fun setOperationResult(operationResultOk: Boolean) {
                    currentItem++
                }
            })

            outStream.close()
            outFile.close()
        } finally {
            outArchive.close()
        }
    }

    /** 解压所有文件（支持进度回调） */
    private class ExtractAllCallback(
        private val inArchive: IInArchive,
        private val outputDir: String,
        private val onLine: ((String) -> Unit)? = null
    ) : IArchiveExtractCallback {

        private var totalItems = 0
        private var currentItem = 0
        private var currentOutStream: java.io.FileOutputStream? = null

        override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream {
            val path = inArchive.getStringProperty(index, PropID.PATH) ?: "unknown"
            val outFile = File(outputDir, path)
            if (extractAskMode == ExtractAskMode.EXTRACT) {
                outFile.parentFile?.mkdirs()
                currentOutStream = java.io.FileOutputStream(outFile)
            }
            return ISequentialOutStream { data ->
                if (extractAskMode == ExtractAskMode.EXTRACT && data.isNotEmpty()) {
                    currentOutStream?.write(data)
                }
                data.size
            }
        }

        override fun prepareOperation(extractAskMode: ExtractAskMode) {}

        override fun setOperationResult(result: ExtractOperationResult) {
            currentOutStream?.close()
            currentOutStream = null
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

    /** 文件输入流，用于压缩时读取源文件 */
    private class FileSequentialInStream(private val file: File) : ISequentialInStream {
        private val inputStream = file.inputStream()

        override fun read(data: ByteArray): Int {
            val bytesRead = inputStream.read(data)
            return if (bytesRead == -1) 0 else bytesRead
        }

        override fun close() = inputStream.close()
    }

    private val FORMAT_MAP = mapOf(
        "zip" to ArchiveFormat.ZIP,
        "7z" to ArchiveFormat.SEVEN_ZIP,
        "tar" to ArchiveFormat.TAR,
        "tar.gz" to ArchiveFormat.GZIP,
        "tar.bz2" to ArchiveFormat.BZIP2,
        "gz" to ArchiveFormat.GZIP,
        "bz2" to ArchiveFormat.BZIP2,
    )
}
