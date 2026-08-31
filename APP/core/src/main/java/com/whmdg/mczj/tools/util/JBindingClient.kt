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

    /** 加密类型检测结果 */
    sealed class EncryptionType {
        /** 无加密 */
        object None : EncryptionType()
        /** 仅内容加密（文件名可见，可直接展开目录树） */
        object ContentOnly : EncryptionType()
        /** 头部加密（文件名也加密，必须先输入密码） */
        object Header : EncryptionType()
    }

    /** 编码转换信息 */
    data class EncodingConversion(
        val originalEncoding: String,
        val targetEncoding: String,
        val count: Int
    )

    /** 压缩包条目（结构化数据，无需字符串解析） */
    data class ArchiveEntry(
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val compressedSize: Long,
        val encodingConversion: EncodingConversion? = null
    )

    fun init(context: Context) {
        Log.d(TAG, "JBindingClient 已初始化")
    }

    suspend fun ensureDaemonOrThrow() {}

    // ── 对外 API ──

    /**
     * 列出压缩包条目（结构化数据，推荐使用）。
     * 对 ZIP 格式自动检测文件名编码，若 JBinding 返回乱码则逆转换恢复。
     * @return Pair<条目列表, 编码转换信息（可能为null）>
     */
    suspend fun listArchiveEntries(archivePath: String, password: String = ""): Result<Pair<List<ArchiveEntry>, EncodingConversion?>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val isZip = archivePath.endsWith(".zip", ignoreCase = true)
                val entries = mutableListOf<ArchiveEntry>()
                var totalConversion: EncodingConversion? = null

                withInArchive(archivePath, password) { inArchive ->
                    val count = inArchive.numberOfItems
                    var convertedCount = 0
                    var srcEnc = ""
                    var dstEnc = ""

                    for (i in 0 until count) {
                        val rawPath = inArchive.getStringProperty(i, PropID.PATH) ?: continue
                        val isDir = inArchive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                        val size = inArchive.getProperty(i, PropID.SIZE) as? Long ?: 0L
                        val packedSize = inArchive.getProperty(i, PropID.PACKED_SIZE) as? Long ?: 0L

                        if (isZip) {
                            val recovered = ZipFilenameRecovery.recover(rawPath)
                            if (recovered != null) {
                                convertedCount++
                                srcEnc = recovered.first
                                dstEnc = recovered.second
                                entries.add(ArchiveEntry(recovered.third, isDir, size, packedSize))
                            } else {
                                entries.add(ArchiveEntry(rawPath, isDir, size, packedSize))
                            }
                        } else {
                            entries.add(ArchiveEntry(rawPath, isDir, size, packedSize))
                        }
                    }

                    if (convertedCount > 0) {
                        totalConversion = EncodingConversion(srcEnc, dstEnc, convertedCount)
                    }
                }

                Pair(entries, totalConversion)
            }
        }

    /** 列出压缩包条目（字符串格式，兼容旧代码） */
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

    /**
     * 检测压缩包加密类型。
     * 用 dummy 密码尝试打开：
     * - 打开失败（异常含 "encrypted"）→ Header（头部加密，文件名也加密）
     * - 打开成功 → None 或 ContentOnly（文件名可见，可直接展开目录树）
     *
     * 注意：头部加密的检测对所有格式通用，不局限于7z。
     * 但实际只有7z支持头部加密，其他格式不会走到 Header 分支。
     */
    suspend fun detectEncryption(archivePath: String): Result<EncryptionType> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                withInArchive(archivePath, "dummy") { inArchive ->
                    // 能打开 → 文件名可见，检查是否有内容加密
                    var hasEncrypted = false
                    for (i in 0 until inArchive.numberOfItems) {
                        if (inArchive.getProperty(i, PropID.ENCRYPTED) as? Boolean ?: false) {
                            hasEncrypted = true
                            break
                        }
                    }
                    if (hasEncrypted) EncryptionType.ContentOnly else EncryptionType.None
                }
            } catch (e: SevenZipException) {
                val msg = e.message ?: ""
                if (msg.contains("encrypted", ignoreCase = true)) {
                    // 打不开 → 头部加密（文件名也加密）
                    EncryptionType.Header
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * 通过读取7z文件头检测是否头部加密。
     * 无需打开压缩包，只读取签名头 + 下一个头的首个字节。
     *
     * 7z格式：签名头(32B) → nextHeaderOfs/nextHeaderSize → 下一个头首字节：
     *   0x01 = PROPERTY.HEADER（未加密）
     *   0x17 = PROPERTY.ENCODED_HEADER（加密）
     */
    suspend fun detect7zHeaderEncryption(archivePath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            RandomAccessFile(File(archivePath), "r").use { raf ->
                // 签名头32字节：魔数(6) + 版本(2) + startHeaderCrc(4) + nextheaderofs(8) + nextheadersize(8) + nextheadercrc(4)
                val sigHeader = ByteArray(32)
                raf.readFully(sigHeader)

                // 校验魔数 "7z\xBC\xAF\x27\x1C"
                if (sigHeader[0] != '7'.code.toByte() || sigHeader[1] != 'z'.code.toByte() ||
                    sigHeader[2] != 0xBC.toByte() || sigHeader[3] != 0xAF.toByte() ||
                    sigHeader[4] != 0x27.toByte() || sigHeader[5] != 0x1C.toByte()) {
                    throw IllegalArgumentException("不是有效的7z文件")
                }

                // nextheaderofs: 8字节小端，偏移12
                val nextHeaderOfs = readLongLE(sigHeader, 12)
                // nextheadersize: 8字节小端，偏移20
                val nextHeaderSize = readLongLE(sigHeader, 20)

                if (nextHeaderSize < 1) {
                    throw IllegalArgumentException("7z下一个头大小异常: $nextHeaderSize")
                }

                // 定位到下一个头，读取首字节
                raf.seek(nextHeaderOfs)
                val headerType = raf.read()

                // 0x17 = PROPERTY.ENCODED_HEADER = 头部加密
                headerType == 0x17
            }
        }
    }

    private fun readLongLE(buf: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = result or ((buf[offset + i].toLong() and 0xFF) shl (i * 8))
        }
        return result
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

    /**
     * ZIP 文件名编码逆转换恢复。
     *
     * 7-Zip 对无 UTF-8 flag 的 ZIP 文件名使用 CP_OEMCP/CP_ACP 解码。
     * 在 Android/Linux 上这些 codepage 通常映射到 UTF-8，但如果原始文件名
     * 是 GBK/CP437 等编码，7-Zip 会用 UTF-8 错误解码，产生乱码。
     *
     * 策略：用 ISO-8859-1（完全可逆）编码回原始字节，再尝试 GBK 解码。
     */
    object ZipFilenameRecovery {
        private val GBK = java.nio.charset.Charset.forName("GBK")

        /**
         * 尝试恢复文件名编码。
         * @return Triple(源编码名, 目标编码名, 恢复后的文件名)，null 表示无需恢复
         */
        fun recover(garbledPath: String): Triple<String, String, String>? {
            if (garbledPath.isEmpty()) return null

            // 纯 ASCII 无需恢复
            if (garbledPath.all { it.code in 0x20..0x7E || it.code == 0x09 }) return null

            // 无替换字符且无控制字符 → 可能已经是正确的 UTF-8
            if (!garbledPath.contains('�') && garbledPath.all { !it.isISOControl() || it.code == 0x09 }) {
                // 检查是否为有效 UTF-8（不含高位字节的异常序列）
                if (isValidUtf8(garbledPath)) return null
            }

            // 有乱码，尝试逆转换
            // 7-Zip 在 Android 上通常用 ISO-8859-1 或 UTF-8 解码
            // ISO-8859-1 是完全可逆的：每个字符映射到唯一的字节
            val rawBytes = garbledPath.toByteArray(Charsets.ISO_8859_1)

            // 尝试 GBK 解码
            val gbkDecoded = String(rawBytes, GBK)
            if (isCleanText(gbkDecoded)) {
                Log.d(TAG, "ZIP 编码恢复: ISO-8859-1 → GBK ($garbledPath → $gbkDecoded)")
                return Triple("ISO-8859-1", "GBK", gbkDecoded)
            }

            // 尝试 CP437 解码
            try {
                val cp437 = java.nio.charset.Charset.forName("CP437")
                val cp437Decoded = String(rawBytes, cp437)
                if (isCleanText(cp437Decoded)) {
                    Log.d(TAG, "ZIP 编码恢复: ISO-8859-1 → CP437 ($garbledPath → $cp437Decoded)")
                    return Triple("ISO-8859-1", "CP437", cp437Decoded)
                }
            } catch (_: Exception) { }

            return null
        }

        private fun isCleanText(s: String): Boolean {
            if (s.contains('�')) return false
            // 检查是否有不合理的控制字符（排除 tab/newline）
            for (c in s) {
                if (c.isISOControl() && c.code != 0x09 && c.code != 0x0A && c.code != 0x0D) return false
            }
            return true
        }

        private fun isValidUtf8(s: String): Boolean {
            // 检查字符串中是否有高位字节产生的异常字符
            for (c in s) {
                val code = c.code
                // 补充形式（0xFFFx）或替换字符
                if (code in 0xFFF0..0xFFFF) return false
                // Surrogate pairs 在 BMP 中不应该出现
                if (c.isSurrogate()) return false
            }
            return true
        }
    }
}
