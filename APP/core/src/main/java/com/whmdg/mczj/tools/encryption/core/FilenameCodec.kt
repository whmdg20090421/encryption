package com.whmdg.mczj.tools.encryption.core

import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 文件名加密/解密 + 长度兜底。与 Python 工具一致。
 */
object FilenameCodec {

    data class EncryptResult(
        val encoded: String,
        val mappingKey: String? = null,
        val mappingValue: String? = null
    )

    fun encrypt(filename: String, dek: ByteArray, aad: ByteArray? = null): EncryptResult {
        val nameBytes = filename.toByteArray(Charsets.UTF_8)

        val e1 = AesGcm256.encrypt(dek, nameBytes, aad)
        val combined1 = e1.iv + e1.ciphertext
        val hex1 = HexCodec.encode(combined1)

        if (hex1.length <= 251) {
            return EncryptResult("$hex1.whm")
        }

        // 太长 -> zlib 压缩
        val compressed = compress(nameBytes)
        val e2 = AesGcm256.encrypt(dek, compressed, aad)
        val combined2 = e2.iv + e2.ciphertext
        val hex2 = HexCodec.encode(combined2)

        if (hex2.length <= 251) {
            return EncryptResult("$hex2.whm")
        }

        // 还是太长 -> SHA-256 哈希 + 映射
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(hex2.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return EncryptResult("$hash.whm", hash, hex2)
    }

    fun decrypt(
        encryptedName: String,
        dek: ByteArray,
        aad: ByteArray? = null,
        lookupMapping: (String) -> String? = { null }
    ): String {
        var name = if (encryptedName.endsWith(".whm")) {
            encryptedName.substring(0, encryptedName.length - 4)
        } else {
            encryptedName
        }

        if (name.length == 64) {
            val mapped = lookupMapping(name)
            if (mapped != null) {
                name = mapped
            }
        }

        return try {
            val raw = HexCodec.decode(name)
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val pt = AesGcm256.decrypt(dek, iv, ct, aad)
            
            try {
                val decomp = decompress(pt)
                String(decomp, Charsets.UTF_8)
            } catch (e: Exception) {
                String(pt, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            "unnamed_recovered"
        }
    }

    private fun compress(input: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()
        val bos = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            if (count == 0) break
            bos.write(buffer, 0, count)
        }
        deflater.end()
        return bos.toByteArray()
    }

    private fun decompress(input: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(input)
        val bos = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0) break
            bos.write(buffer, 0, count)
        }
        inflater.end()
        return bos.toByteArray()
    }
}
