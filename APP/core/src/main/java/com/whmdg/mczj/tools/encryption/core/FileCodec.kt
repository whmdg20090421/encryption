package com.whmdg.mczj.tools.encryption.core

import com.whmdg.mczj.tools.encryption.core.AesGcm256
import com.whmdg.mczj.tools.encryption.core.FileConstants
import com.whmdg.mczj.tools.encryption.core.NailObfuscation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单个加密文件的二进制编解码器，与 Python 工具完全互通。
 */
object FileCodec {

    /**
     * 接收解压回调的明文字节并按加密格式写出，始终只保留一个固定大小明文块。
     * 调用方必须在成功时调用 [finish]，失败或取消时调用 [abort]。
     */
    class EncryptingSink(
        private val dst: File,
        private val dek: ByteArray,
        private val encryptMetadata: Boolean,
        private val customEncryption: Boolean,
        private val sourceModifiedAt: Long = System.currentTimeMillis(),
        private val onProgress: (Long) -> Unit = {},
        private val cancelFlag: AtomicBoolean? = null
    ) {
        private val aad = if (customEncryption) FileConstants.aadCustomObf else null
        private val buffer = ByteArray(FileConstants.CHUNK_SIZE)
        private val out = FileOutputStream(dst)
        private var buffered = 0
        private var written = 0L
        private var closed = false

        init {
            if (customEncryption) out.write(FileConstants.magicHeader)
            val metadata = if (encryptMetadata) {
                "{\"mtime\":${sourceModifiedAt / 1000.0},\"ctime\":${sourceModifiedAt / 1000.0}}"
            } else "{}"
            val encrypted = AesGcm256.encrypt(dek, metadata.toByteArray(Charsets.UTF_8), aad)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(encrypted.iv.size + encrypted.ciphertext.size).array())
            out.write(encrypted.iv)
            out.write(encrypted.ciphertext)
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
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(encrypted.iv.size + cipher.size).array())
            out.write(encrypted.iv)
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
        encryptMetadata: Boolean,
        customEncryption: Boolean,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        cancelFlag: AtomicBoolean? = null
    ) {
        val aad = if (customEncryption) FileConstants.aadCustomObf else null
        val totalSize = src.length()

        FileOutputStream(dst).use { out ->
            // ① MAGIC 头（标识加密文件）
            if (customEncryption) {
                out.write(FileConstants.magicHeader)
            }

            // ② metadata 块
            val metaMap = mutableMapOf<String, Double>()
            if (encryptMetadata) {
                val mtime = src.lastModified() / 1000.0
                metaMap["mtime"] = mtime
                metaMap["ctime"] = mtime
            }
            // 使用简单的 JSON 序列化
            val metaJson = metaMap.entries.joinToString(",", "{", "}") { 
                "\"${it.key}\":${it.value}" 
            }
            val metaBytes = metaJson.toByteArray(Charsets.UTF_8)
            val metaEnc = AesGcm256.encrypt(dek, metaBytes, aad)
            val metaLen = metaEnc.iv.size + metaEnc.ciphertext.size
            
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(metaLen).array())
            out.write(metaEnc.iv)
            out.write(metaEnc.ciphertext)

            // ③ 数据块
            var bytesDone = 0L
            FileInputStream(src).use { `in` ->
                val buffer = ByteArray(FileConstants.CHUNK_SIZE)
                while (true) {
                    val read = `in`.read(buffer)
                    if (read <= 0) break
                    val chunk = if (read == buffer.size) buffer else buffer.copyOfRange(0, read)
                    val e = AesGcm256.encrypt(dek, chunk, aad)
                    var cipherOut = e.ciphertext
                    if (customEncryption && cipherOut.size >= 1024) {
                        cipherOut = NailObfuscation.insert(cipherOut, e.iv, dek)
                    }
                    val chunkLen = e.iv.size + cipherOut.size
                    out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(chunkLen).array())
                    out.write(e.iv)
                    out.write(cipherOut)
                    bytesDone += read
                    onProgress(bytesDone, totalSize)
                    if (cancelFlag?.get() == true) throw InterruptedIOException("用户取消")
                }
            }
        }
        onProgress(totalSize, totalSize)
    }

    fun decrypt(
        src: File,
        dst: File,
        dek: ByteArray,
        customEncryption: Boolean,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Map<String, Double> {
        val aad = if (customEncryption) FileConstants.aadCustomObf else null
        val totalSize = src.length()
        var metadata = mapOf<String, Double>()

        FileInputStream(src).use { `in` ->
            if (customEncryption) {
                val magic = ByteArray(FileConstants.magicHeader.size)
                `in`.read(magic)
                if (!magic.contentEquals(FileConstants.magicHeader)) {
                    throw IllegalArgumentException("文件头损坏或未启用对应加密配置")
                }
            }

            // metadata
            val metaLenBuf = ByteArray(4)
            `in`.read(metaLenBuf)
            val metaLen = ByteBuffer.wrap(metaLenBuf).order(ByteOrder.BIG_ENDIAN).int
            val metaIv = ByteArray(12)
            `in`.read(metaIv)
            val metaCipher = ByteArray(metaLen - 12)
            `in`.read(metaCipher)
            
            val metaPlain = AesGcm256.decrypt(dek, metaIv, metaCipher, aad)
            val metaStr = String(metaPlain, Charsets.UTF_8)
            // 简单解析 JSON (或者使用 kotlinx-serialization)
            try {
                val jsonElement = Json.parseToJsonElement(metaStr)
                if (jsonElement is JsonObject) {
                    metadata = jsonElement.mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 数据区
            val dataEnd = totalSize
            var currentPos = if (customEncryption) FileConstants.magicHeader.size.toLong() else 0L
            currentPos += 4 + metaLen // metaLenBuf(4) + iv(12) + cipher(metaLen-12)

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

        metadata["mtime"]?.let {
            dst.setLastModified((it * 1000).toLong())
        }
        onProgress(totalSize, totalSize)
        return metadata
    }
}
