package com.whmdg.mczj.tools.encryption.core

import com.whmdg.mczj.tools.encryption.core.AesGcm256
import com.whmdg.mczj.tools.encryption.core.FileConstants
import com.whmdg.mczj.tools.encryption.core.NailObfuscation
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单个加密文件的二进制编解码器。
 *
 * 新格式（无 metadata 块）：
 *   [magic header] (仅 customEncryption)
 *   [4B chunk_len] [12B IV] [cipher] × N
 */
object FileCodec {

    /** 并发加密文件数，1 = 串行。预留变量，暂不提供修改接口。 */
    var concurrentFiles = 1

    /** 根据文件大小选择 chunk：小文件小块减少内存占用，大文件大块减少初始化次数。 */
    private fun chunkSizeFor(fileSize: Long): Int = when {
        fileSize <= 2L * 1024 * 1024  -> 1 * 1024 * 1024  // ≤2MB → 1MB chunk
        fileSize <= 8L * 1024 * 1024  -> 2 * 1024 * 1024  // ≤8MB → 2MB chunk
        else                          -> 4 * 1024 * 1024  // >8MB → 4MB chunk
    }

    /**
     * 接收解压回调的明文字节并按加密格式写出，始终只保留一个固定大小明文块。
     * 调用方必须在成功时调用 [finish]，失败或取消时调用 [abort]。
     */
    class EncryptingSink(
        private val dst: File,
        private val dek: ByteArray,
        private val customEncryption: Boolean,
        private val sourceModifiedAt: Long = System.currentTimeMillis(),
        private val onProgress: (Long) -> Unit = {},
        private val cancelFlag: AtomicBoolean? = null
    ) {
        private val aad = if (customEncryption) FileConstants.aadCustomObf else null
        private val buffer = ByteArray(FileConstants.CHUNK_SIZE)
        private val headerBuf = ByteArray(4 + 12) // 复用：chunkLen(4) + IV(12)
        private val out = FileOutputStream(dst)
        private var buffered = 0
        private var written = 0L
        private var closed = false

        init {
            if (customEncryption) out.write(FileConstants.magicHeader)
        }

        fun write(data: ByteArray) {
            check(!closed) { "加密写入器已关闭" }
            if (cancelFlag?.get() == true) throw InterruptedIOException("用户取消")
            var offset = 0
            while (offset < data.size) {
                val count = minOf(buffer.size - buffered, data.size - offset)
                data.copyInto(buffer, buffered, offset, offset + count)
                buffered += count
                offset += count
                if (buffered == buffer.size) flushChunk()
            }
        }

        fun finish() {
            if (closed) return
            if (buffered > 0) flushChunk()
            closed = true
            out.close()
            onProgress(written)
        }

        fun abort() {
            if (!closed) {
                closed = true
                out.close()
            }
            dst.delete()
        }

        private fun flushChunk() {
            if (cancelFlag?.get() == true) throw InterruptedIOException("用户取消")
            val plain = if (buffered == buffer.size) buffer else buffer.copyOf(buffered)
            val encrypted = AesGcm256.encrypt(dek, plain, aad)
            var cipher = encrypted.ciphertext
            if (customEncryption && cipher.size >= 1024) cipher = NailObfuscation.insert(cipher, encrypted.iv, dek)
            // 合并写出：length(4B) + IV(12B) 一次 syscall，密文紧随
            val chunkLen = encrypted.iv.size + cipher.size
            headerBuf[0] = (chunkLen shr 24).toByte()
            headerBuf[1] = (chunkLen shr 16).toByte()
            headerBuf[2] = (chunkLen shr 8).toByte()
            headerBuf[3] = chunkLen.toByte()
            System.arraycopy(encrypted.iv, 0, headerBuf, 4, encrypted.iv.size)
            out.write(headerBuf, 0, 4 + encrypted.iv.size)
            out.write(cipher)
            written += buffered
            buffered = 0
            onProgress(written)
        }
    }

    fun encrypt(
        src: File,
        dst: File,
        dek: ByteArray,
        customEncryption: Boolean,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        cancelFlag: AtomicBoolean? = null,
        context: android.content.Context? = null
    ) {
        val aad = if (customEncryption) FileConstants.aadCustomObf else null
        val totalSize = src.length()
        val chunkSize = chunkSizeFor(totalSize)
        val trace = context != null && EncryptionTraceLog.enabled(context)
        // 复用 buffer：chunkLen(4B) + IV(12B)，合并一次写出减少 syscall
        val headerBuf = ByteArray(4 + 12)

        if (trace) {
            EncryptionTraceLog.log("FileCodec.encrypt: src=${src.name} size=$totalSize chunkSize=$chunkSize chunks=${(totalSize + chunkSize - 1) / chunkSize}")
        }

        FileOutputStream(dst).use { out ->
            if (customEncryption) {
                out.write(FileConstants.magicHeader)
            }

            var bytesDone = 0L
            var chunkIndex = 0
            FileInputStream(src).use { inp ->
                val buffer = ByteArray(chunkSize)
                while (true) {
                    if (cancelFlag?.get() == true) throw InterruptedIOException("用户取消")
                    val t0 = if (trace) System.nanoTime() else 0L
                    val read = inp.read(buffer)
                    if (read <= 0) break
                    val t1 = if (trace) System.nanoTime() else 0L
                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                    val e = AesGcm256.encrypt(dek, chunk, aad)
                    var cipherOut = e.ciphertext
                    if (customEncryption && cipherOut.size >= 1024) {
                        cipherOut = NailObfuscation.insert(cipherOut, e.iv, dek)
                    }
                    val t2 = if (trace) System.nanoTime() else 0L
                    // 合并写出：length(4B) + IV(12B) 一次 syscall，密文紧随
                    val chunkLen = e.iv.size + cipherOut.size
                    headerBuf[0] = (chunkLen shr 24).toByte()
                    headerBuf[1] = (chunkLen shr 16).toByte()
                    headerBuf[2] = (chunkLen shr 8).toByte()
                    headerBuf[3] = chunkLen.toByte()
                    System.arraycopy(e.iv, 0, headerBuf, 4, e.iv.size)
                    out.write(headerBuf, 0, 4 + e.iv.size)
                    out.write(cipherOut)
                    val t3 = if (trace) System.nanoTime() else 0L
                    bytesDone += read
                    if (trace) {
                        val readMs = (t1 - t0) / 1_000_000.0
                        val encMs = (t2 - t1) / 1_000_000.0
                        val writeMs = (t3 - t2) / 1_000_000.0
                        val totalMs = (t3 - t0) / 1_000_000.0
                        val speed = if (totalMs > 0) read / 1024.0 / 1024.0 / (totalMs / 1000.0) else 0.0
                        EncryptionTraceLog.log("chunk#${chunkIndex}: read=${read}B readTime=%.1fms encTime=%.1fms writeTime=%.1fms total=%.1fms speed=%.1fMB/s".format(readMs, encMs, writeMs, totalMs, speed))
                        EncryptionTraceLog.addBytes(read.toLong())
                    }
                    chunkIndex++
                    onProgress(bytesDone, totalSize)
                }
            }
        }
        if (trace) {
            EncryptionTraceLog.log("FileCodec.encrypt done: totalBytes=$totalSize")
        }
        onProgress(totalSize, totalSize)
    }

    fun decrypt(
        src: File,
        dst: File,
        dek: ByteArray,
        customEncryption: Boolean,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        val aad = if (customEncryption) FileConstants.aadCustomObf else null
        val totalSize = src.length()

        FileInputStream(src).use { `in` ->
            if (customEncryption) {
                val magic = ByteArray(FileConstants.magicHeader.size)
                `in`.read(magic)
                if (!magic.contentEquals(FileConstants.magicHeader)) {
                    throw IllegalArgumentException("文件头损坏或未启用对应加密配置")
                }
            }

            val dataEnd = totalSize
            var currentPos = if (customEncryption) FileConstants.magicHeader.size.toLong() else 0L

            FileOutputStream(dst).use { out ->
                var bytesDone = 0L
                while (currentPos < dataEnd) {
                    val clBuf = ByteArray(4)
                    val readLen = `in`.read(clBuf)
                    if (readLen < 4) break
                    currentPos += 4
                    val chunkLen = ByteBuffer.wrap(clBuf).order(ByteOrder.BIG_ENDIAN).int
                    if (chunkLen < 12 || chunkLen > FileConstants.MAX_CHUNK_SIZE) {
                        throw IllegalArgumentException("块长度异常: $chunkLen，文件可能被篡改")
                    }
                    val iv = ByteArray(12)
                    `in`.read(iv)
                    var cipher = ByteArray(chunkLen - 12)
                    `in`.read(cipher)
                    currentPos += chunkLen

                    if (customEncryption && cipher.size >= 1040) {
                        cipher = NailObfuscation.extract(cipher, iv, dek)
                    }
                    val plain = AesGcm256.decrypt(dek, iv, cipher, aad)
                    out.write(plain)
                    bytesDone += plain.size
                    onProgress(bytesDone, totalSize)
                }
            }
        }
        onProgress(totalSize, totalSize)
    }

    /**
     * 解密到 [OutputStream]（内存），用于缩略图等不需要落盘的场景。
     * 逻辑与 [decrypt] 完全一致，仅输出目标不同。
     */
    fun decryptToStream(
        src: File,
        out: OutputStream,
        dek: ByteArray,
        customEncryption: Boolean
    ) {
        val aad = if (customEncryption) FileConstants.aadCustomObf else null
        val totalSize = src.length()

        FileInputStream(src).use { `in` ->
            if (customEncryption) {
                val magic = ByteArray(FileConstants.magicHeader.size)
                `in`.read(magic)
                if (!magic.contentEquals(FileConstants.magicHeader)) {
                    throw IllegalArgumentException("文件头损坏或未启用对应加密配置")
                }
            }

            val dataEnd = totalSize
            var currentPos = if (customEncryption) FileConstants.magicHeader.size.toLong() else 0L

            while (currentPos < dataEnd) {
                val clBuf = ByteArray(4)
                val readLen = `in`.read(clBuf)
                if (readLen < 4) break
                currentPos += 4
                val chunkLen = ByteBuffer.wrap(clBuf).order(ByteOrder.BIG_ENDIAN).int
                if (chunkLen < 12 || chunkLen > FileConstants.MAX_CHUNK_SIZE) {
                    throw IllegalArgumentException("块长度异常: $chunkLen，文件可能被篡改")
                }
                val iv = ByteArray(12)
                `in`.read(iv)
                var cipher = ByteArray(chunkLen - 12)
                `in`.read(cipher)
                currentPos += chunkLen

                if (customEncryption && cipher.size >= 1040) {
                    cipher = NailObfuscation.extract(cipher, iv, dek)
                }
                val plain = AesGcm256.decrypt(dek, iv, cipher, aad)
                out.write(plain)
            }
        }
    }

    /**
     * 将旧格式加密文件（含 metadata 块）迁移到新格式（无 metadata 块）。
     * 纯字节级操作，不需要 DEK 也不需要重新加密。
     */
    fun stripMetadata(src: File, dst: File, customEncryption: Boolean) {
        FileInputStream(src).use { `in` ->
            FileOutputStream(dst).use { out ->
                if (customEncryption) {
                    val magic = ByteArray(FileConstants.magicHeader.size)
                    `in`.read(magic)
                    if (!magic.contentEquals(FileConstants.magicHeader)) {
                        throw IllegalArgumentException("文件头损坏: ${src.name}")
                    }
                    out.write(magic)
                }

                val metaLenBuf = ByteArray(4)
                if (`in`.read(metaLenBuf) < 4) throw IllegalArgumentException("文件过短: ${src.name}")
                val metaLen = ByteBuffer.wrap(metaLenBuf).order(ByteOrder.BIG_ENDIAN).int
                val headerSize = (if (customEncryption) FileConstants.magicHeader.size else 0) + 4
                if (metaLen < 0 || metaLen > src.length() - headerSize) {
                    throw IllegalArgumentException("metadata 长度异常: $metaLen, 文件: ${src.name}")
                }

                var skipped = 0L
                val skipBuf = ByteArray(8192)
                while (skipped < metaLen) {
                    val toRead = minOf(skipBuf.size.toLong(), metaLen - skipped).toInt()
                    val read = `in`.read(skipBuf, 0, toRead)
                    if (read <= 0) throw IllegalArgumentException("metadata 块读取不完整: ${src.name}")
                    skipped += read
                }

                val copyBuf = ByteArray(65536)
                while (true) {
                    val read = `in`.read(copyBuf)
                    if (read <= 0) break
                    out.write(copyBuf, 0, read)
                }
            }
        }
    }
}
