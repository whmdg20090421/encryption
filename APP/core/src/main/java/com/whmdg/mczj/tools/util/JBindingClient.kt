package com.whmdg.mczj.tools.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.IOutCreateCallback
import net.sf.sevenzipjbinding.IOutFeatureSetEncryptHeader
import net.sf.sevenzipjbinding.IOutFeatureSetLevel
import net.sf.sevenzipjbinding.IOutItemAllFormats
import net.sf.sevenzipjbinding.ISequentialInStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.OutItemFactory
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel as Zip4jLevel
import net.lingala.zip4j.model.enums.CompressionMethod as Zip4jMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

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

    /** 压缩包条目（结构化数据，无需字符串解析） */
    data class ArchiveEntry(
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val compressedSize: Long
    )

    fun init(context: Context) {
        Log.d(TAG, "JBindingClient 已初始化")
    }

    suspend fun ensureDaemonOrThrow() {}

    // ── 对外 API ──

    /**
     * 列出压缩包条目（结构化数据，推荐使用）。
     */
    suspend fun listArchiveEntries(archivePath: String, password: String = ""): Result<List<ArchiveEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val entries = mutableListOf<ArchiveEntry>()

                withInArchive(archivePath, password) { inArchive ->
                    val count = inArchive.numberOfItems
                    for (i in 0 until count) {
                        val path = inArchive.getStringProperty(i, PropID.PATH) ?: continue
                        val isDir = inArchive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                        val size = inArchive.getProperty(i, PropID.SIZE) as? Long ?: 0L
                        val packedSize = inArchive.getProperty(i, PropID.PACKED_SIZE) as? Long ?: 0L
                        entries.add(ArchiveEntry(path, isDir, size, packedSize))
                    }
                }

                entries
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
        entryPath: String,
        destFile: File,
        password: String = "",
        cancelFlag: AtomicBoolean? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            withInArchive(archivePath, password) { inArchive ->
                val count = inArchive.numberOfItems
                var targetIndex = -1
                for (i in 0 until count) {
                    val path = inArchive.getStringProperty(i, PropID.PATH) ?: continue
                    if (path == entryPath || path.replace('\\', '/') == entryPath.replace('\\', '/')) {
                        targetIndex = i
                        break
                    }
                }
                if (targetIndex < 0) throw RuntimeException("文件不存在: $entryPath")

                destFile.parentFile?.mkdirs()
                val out = java.io.FileOutputStream(destFile)
                try {
                    inArchive.extract(intArrayOf(targetIndex), false, object : IArchiveExtractCallback, ICryptoGetTextPassword {
                        override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream {
                            return ISequentialOutStream { data ->
                                if (cancelFlag?.get() == true) throw InterruptedIOException("用户取消")
                                out.write(data)
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

                        // 提供密码（ZIP/RAR/TAR/7z 内容加密需要）
                        override fun cryptoGetTextPassword(): String = password
                    })
                } finally {
                    out.close()
                }
            }
            ""
        }
    }

    /**
     * 将单个压缩包条目直接写入调用方提供的字节接收器，不创建明文临时文件。
     *
     * [onProgress] 回调 (已写入字节数, 该条目解压后总字节数)，用于展示真实提取进度；
     * 总字节数取自压缩包条目的 SIZE 属性，未知时为 0。
     */
    suspend fun extractSingleFileToSink(
        archivePath: String,
        entryPath: String,
        password: String = "",
        onProgress: ((done: Long, total: Long) -> Unit)? = null,
        onBytes: (ByteArray) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "extractSingleFileToSink 开始: archive=${File(archivePath).name}, entry=$entryPath, hasPassword=${password.isNotEmpty()}, passwordLength=${password.length}")
            withInArchive(archivePath, password) { inArchive ->
                val targetIndex = (0 until inArchive.numberOfItems).firstOrNull { index ->
                    val path = inArchive.getStringProperty(index, PropID.PATH) ?: return@firstOrNull false
                    path == entryPath || path.replace('\\', '/') == entryPath.replace('\\', '/')
                } ?: throw RuntimeException("文件不存在: $entryPath")

                val entryTotal = (inArchive.getProperty(targetIndex, PropID.SIZE) as? Long) ?: 0L
                var written = 0L

                var sinkFailure: Exception? = null
                var extractResult: ExtractOperationResult? = null

                // 使用 extract + callback 提供密码（兼容 ZIP/RAR/TAR/7z 内容加密）
                inArchive.extract(intArrayOf(targetIndex), false, object : IArchiveExtractCallback, ICryptoGetTextPassword {
                    override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream? {
                        return if (extractAskMode == ExtractAskMode.EXTRACT) {
                            ISequentialOutStream { data ->
                                try {
                                    if (data.isNotEmpty()) {
                                        onBytes(data)
                                        written += data.size
                                        onProgress?.invoke(written, entryTotal)
                                    }
                                } catch (e: Exception) {
                                    sinkFailure = e
                                    throw e
                                }
                                data.size
                            }
                        } else null
                    }

                    override fun prepareOperation(extractAskMode: ExtractAskMode) {}

                    override fun setOperationResult(result: ExtractOperationResult) {
                        extractResult = result
                    }

                    override fun setTotal(total: Long) {}
                    override fun setCompleted(complete: Long) {}

                    // 提供密码（ZIP/RAR/TAR/7z 内容加密需要）
                    override fun cryptoGetTextPassword(): String = password
                })

                val result = extractResult ?: throw RuntimeException("未返回解压结果")

                if (result != ExtractOperationResult.OK) {
                    // 根据结果类型和是否提供密码，推断可能的错误原因
                    val resultStr = result.toString()
                    val errorMsg = if (password.isNotEmpty() && (
                        resultStr.contains("DATA", ignoreCase = true) ||
                        resultStr.contains("PASSWORD", ignoreCase = true) ||
                        resultStr.contains("CRC", ignoreCase = true)
                    )) {
                        "密码错误或数据损坏: $result"
                    } else if (password.isEmpty() && resultStr.contains("DATA", ignoreCase = true)) {
                        "需要密码或数据损坏: $result"
                    } else {
                        "提取失败: $result"
                    }
                    Log.e(TAG, "extractSingleFileToSink 失败: archive=${File(archivePath).name}, entry=$entryPath, result=$result, hasPassword=${password.isNotEmpty()}, passwordLength=${password.length}")
                    throw SevenZipException(errorMsg)
                }

                if (sinkFailure != null) {
                    throw sinkFailure!!
                }
            }
            Log.d(TAG, "extractSingleFileToSink 成功: archive=${File(archivePath).name}, entry=$entryPath")
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
                inArchive.extract(indices, false, ExtractAllCallback(inArchive, outputDir, password))
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
        encryptNames: Boolean = false,
        cancelFlag: AtomicBoolean? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            doCompress(sourcePaths, outputPath, format, level, password, useAes, encryptNames, cancelFlag, null)
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
        cancelFlag: AtomicBoolean? = null,
        onLine: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            doCompress(sourcePaths, outputPath, format, level, password, useAes, encryptNames, cancelFlag, onLine)
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
                inArchive.extract(indices, false, ExtractAllCallback(inArchive, outputDir, password, onLine))
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
            // 尝试用密码打开（仅 7z 头部加密需要）
            val inArchive = try {
                if (password.isNotEmpty()) {
                    SevenZip.openInArchive(null, stream, password)
                } else {
                    SevenZip.openInArchive(null, stream)
                }
            } catch (e: SevenZipException) {
                // 如果带密码打开失败，且异常提示加密，说明是头部加密
                if (password.isNotEmpty() && e.message?.contains("encrypted", ignoreCase = true) == true) {
                    throw e
                }
                // 否则尝试不带密码打开（ZIP/RAR/TAR 或 7z 内容加密）
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
        cancelFlag: AtomicBoolean?,
        onLine: ((String) -> Unit)?
    ) {
        val archiveFormat = FORMAT_MAP[format]
            ?: throw IllegalArgumentException("不支持的格式: $format")

        // 收集条目并保留目录结构（相对各自源路径的父目录）
        val entries = collectEntries(sourcePaths)
        if (entries.isEmpty()) throw IllegalArgumentException("没有可压缩的文件")

        try {
            when {
                // ZIP + AES-256：JBinding 的 Java 层未开放 em 属性，改用 zip4j
                archiveFormat == ArchiveFormat.ZIP && password.isNotEmpty() && useAes ->
                    compressZipAes(entries, outputPath, level, password, cancelFlag, onLine)

                archiveFormat == ArchiveFormat.GZIP || archiveFormat == ArchiveFormat.BZIP2 ->
                    compressTarThenOuter(entries, outputPath, archiveFormat, level, cancelFlag, onLine)

                else ->
                    compressGeneric(entries, outputPath, archiveFormat, level, password, encryptNames, cancelFlag, onLine)
            }
        } catch (e: Throwable) {
            // 取消/失败时清理半成品输出
            File(outputPath).delete()
            throw e
        }
    }

    /** 收集源路径下的所有条目（含目录），relativePath 相对各源的父目录，保留目录结构 */
    private fun collectEntries(sourcePaths: List<String>): List<ArchiveSource> {
        val entries = mutableListOf<ArchiveSource>()
        for (src in sourcePaths) {
            val file = File(src)
            if (!file.exists()) continue
            val base = file.parentFile ?: File("/")
            if (file.isDirectory) {
                file.walkTopDown().forEach { f ->
                    val rel = f.relativeTo(base).path.replace('\\', '/')
                    if (rel.isNotEmpty()) entries.add(ArchiveSource(f, rel))
                }
            } else {
                entries.add(ArchiveSource(file, file.name))
            }
        }
        return entries
    }

    /** 通过 JBinding 创建 7z / zip / tar 等（zip 非 AES 场景） */
    private fun compressGeneric(
        entries: List<ArchiveSource>,
        outputPath: String,
        archiveFormat: ArchiveFormat,
        level: Int,
        password: String,
        encryptNames: Boolean,
        cancelFlag: AtomicBoolean?,
        onLine: ((String) -> Unit)?
    ) {
        val outArchive = SevenZip.openOutArchive(archiveFormat)
        try {
            (outArchive as? IOutFeatureSetLevel)?.setLevel(level)
            // 7z：有密码时由引擎自动 AES-256 内容加密；勾选加密文件名再开头部加密
            if (password.isNotEmpty() && encryptNames) {
                (outArchive as? IOutFeatureSetEncryptHeader)?.setHeaderEncryption(true)
            }

            val outFile = RandomAccessFile(File(outputPath), "rw").apply { setLength(0) }
            val outStream = RandomAccessFileOutStream(outFile)
            try {
                outArchive.createArchive(outStream, entries.size, JBindingCreateCallback(entries, password, cancelFlag, onLine))
            } finally {
                outStream.close()
                outFile.close()
            }
        } finally {
            outArchive.close()
        }
    }

    /** ZIP + AES-256：使用 zip4j（JBinding Java 层无法创建 AES ZIP） */
    private fun compressZipAes(
        entries: List<ArchiveSource>,
        outputPath: String,
        level: Int,
        password: String,
        cancelFlag: AtomicBoolean?,
        onLine: ((String) -> Unit)?
    ) {
        val baseParams = ZipParameters().apply {
            compressionMethod = if (level == 0) Zip4jMethod.STORE else Zip4jMethod.DEFLATE
            compressionLevel = Zip4jLevel.values()[level.coerceIn(0, 9)]
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            isIncludeRootFolder = false
        }

        FileOutputStream(outputPath).use { fos ->
            ZipOutputStream(fos, password.toCharArray()).use { zos ->
                entries.forEachIndexed { index, entry ->
                    if (cancelFlag?.get() == true) throw InterruptedIOException("用户取消")
                    val name = if (entry.file.isDirectory) {
                        entry.relativePath.trimEnd('/') + "/"
                    } else {
                        entry.relativePath
                    }
                    val params = ZipParameters(baseParams).apply {
                        fileNameInZip = name
                        if (!entry.file.isDirectory) {
                            lastModifiedFileTime = entry.file.lastModified()
                            // STORE 模式下必须显式提供原始大小
                            if (level == 0) entrySize = entry.file.length()
                        }
                    }
                    zos.putNextEntry(params)
                    if (!entry.file.isDirectory) {
                        entry.file.inputStream().use { it.copyTo(zos) }
                    }
                    zos.closeEntry()
                    onLine?.invoke("  ${(index + 1) * 100 / entries.size}%  ${index + 1}")
                }
            }
        }
    }

    /** tar.gz / tar.bz2：先打成临时 tar，再整体用 gzip/bzip2 压缩（单流格式） */
    private fun compressTarThenOuter(
        entries: List<ArchiveSource>,
        outputPath: String,
        outerFormat: ArchiveFormat,
        level: Int,
        cancelFlag: AtomicBoolean?,
        onLine: ((String) -> Unit)?
    ) {
        val tempTar = File.createTempFile("mczj_tar_", ".tar", File(outputPath).parentFile)
        try {
            compressGeneric(entries, tempTar.absolutePath, ArchiveFormat.TAR, level, "", false, cancelFlag, null)

            // 内层 tar 在压缩包中的名字：foo.tar.gz → foo.tar
            val innerName = File(outputPath).name.removeSuffix(".gz").removeSuffix(".bz2")

            val outArchive = SevenZip.openOutArchive(outerFormat)
            try {
                (outArchive as? IOutFeatureSetLevel)?.setLevel(level)
                val outFile = RandomAccessFile(File(outputPath), "rw").apply { setLength(0) }
                val outStream = RandomAccessFileOutStream(outFile)
                try {
                    outArchive.createArchive(
                        outStream,
                        1,
                        JBindingCreateCallback(listOf(ArchiveSource(tempTar, innerName)), "", cancelFlag, onLine)
                    )
                } finally {
                    outStream.close()
                    outFile.close()
                }
            } finally {
                outArchive.close()
            }
        } finally {
            tempTar.delete()
        }
    }

    /** JBinding 创建回调：支持目录结构、真实密码、取消 */
    private class JBindingCreateCallback(
        private val entries: List<ArchiveSource>,
        private val password: String,
        private val cancelFlag: AtomicBoolean?,
        private val onLine: ((String) -> Unit)?
    ) : IOutCreateCallback<IOutItemAllFormats>, ICryptoGetTextPassword {

        private var currentItem = 0
        private var totalBytes = 0L

        override fun getItemInformation(index: Int, factory: OutItemFactory<IOutItemAllFormats>): IOutItemAllFormats {
            val item = factory.createOutItem()
            val entry = entries[index]
            val isDir = entry.file.isDirectory
            item.setPropertyPath(entry.relativePath)
            item.setPropertyIsDir(isDir)
            item.setDataSize(if (isDir) 0L else entry.file.length())
            item.setPropertyAttributes(attributesFor(isDir))
            // tar 使用 POSIX 属性保留权限（对 zip/7z 无副作用）
            item.setPropertyPosixAttributes(if (isDir) 0x41ED else 0x81A4)
            return item
        }

        override fun getStream(index: Int): ISequentialInStream? {
            val entry = entries[index]
            if (entry.file.isDirectory) return null
            // 必须返回可 seek 的流（IInStream），否则 ZIP 压缩会报 E_NOTIMPL。
            // 引擎不回调 close()，且会先读完整流做 CRC 再 seek 回起点，因此不能提前关闭；
            // 与解压回调一致，交给 GC 回收底层文件句柄。
            return RandomAccessFileInStream(RandomAccessFile(entry.file, "r"))
        }

        override fun setTotal(total: Long) {
            totalBytes = total
        }

        override fun setCompleted(complete: Long) {
            if (cancelFlag?.get() == true) throw InterruptedIOException("用户取消")
            onLine?.let { callback ->
                val percent = if (totalBytes > 0) (complete * 100 / totalBytes).toInt() else 0
                callback("  $percent%  ${currentItem + 1}")
            }
        }

        override fun setOperationResult(operationResultOk: Boolean) {
            if (!operationResultOk) throw SevenZipException("压缩条目失败")
            currentItem++
        }

        override fun cryptoGetTextPassword(): String? = password.ifEmpty { null }

        private fun attributesFor(isDir: Boolean): Int {
            val unixExt = PropID.AttributesBitMask.FILE_ATTRIBUTE_UNIX_EXTENSION
            return if (isDir) {
                unixExt or PropID.AttributesBitMask.FILE_ATTRIBUTE_DIRECTORY or (0x41ED shl 16) // drwxr-xr-x
            } else {
                unixExt or (0x81A4 shl 16) // -rw-r--r--
            }
        }
    }

    /** 压缩源条目：文件 + 压缩包内相对路径 */
    private data class ArchiveSource(val file: File, val relativePath: String)

    /** 解压所有文件（支持进度回调） */
    private class ExtractAllCallback(
        private val inArchive: IInArchive,
        private val outputDir: String,
        private val password: String = "",
        private val onLine: ((String) -> Unit)? = null
    ) : IArchiveExtractCallback, ICryptoGetTextPassword {

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

        // 提供密码（ZIP/RAR/TAR/7z 内容加密需要）
        override fun cryptoGetTextPassword(): String = password
    }

    private val FORMAT_MAP = mapOf(
        "zip" to ArchiveFormat.ZIP,
        "7z" to ArchiveFormat.SEVEN_ZIP,
        "tar" to ArchiveFormat.TAR,
        "tar.gz" to ArchiveFormat.GZIP,
        "tar.bz2" to ArchiveFormat.BZIP2,
    )
}
