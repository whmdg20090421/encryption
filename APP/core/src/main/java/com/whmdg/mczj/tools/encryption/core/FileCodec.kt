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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 单个加密文件的二进制编解码器，与 Python 工具完全互通。
 */
object FileCodec {

    fun encrypt(
        src: File,
        dst: File,
        dek: ByteArray,
        encryptMetadata: Boolean,
        customEncryption: Boolean,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        val aad = if (customEncryption) FileConstants.aadCustomObf else null
        val totalSize = src.length()
        val hasFooter = customEncryption && totalSize > FileConstants.FOOTER_THRESHOLD

        FileOutputStream(dst).use { out ->
            // ① MAGIC + footer 标志
            if (customEncryption) {
                out.write(FileConstants.magicHeader)
                out.write(if (hasFooter) 1 else 0)
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
                }
            }

            // ④ Legal footer
            if (hasFooter) {
                out.write(FileConstants.legalFooter)
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
            var hasFooter = false
            if (customEncryption) {
                val magic = ByteArray(FileConstants.magicHeader.size)
                `in`.read(magic)
                if (!magic.contentEquals(FileConstants.magicHeader)) {
                    throw IllegalArgumentException("文件头损坏或未启用对应加密配置")
                }
                val flag = `in`.read()
                if (flag == -1) throw IllegalArgumentException("文件格式错误：缺少 footer 标志")
                hasFooter = flag == 1
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
            val dataEnd = if (hasFooter) totalSize - FileConstants.legalFooter.size else totalSize
            var currentPos = if (customEncryption) (FileConstants.magicHeader.size + 1).toLong() else 0L
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
