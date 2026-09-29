package com.whmdg.mczj.tools.util

import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * APK 签名方案检测：读取 APK Signing Block，报告 V1 / V2 / V3 方案组合。
 * 只显示方案组合（如 "V1 + V2"），不附证书指纹。
 */
object AppSignatureDetector {

    private const val MAGIC = "APK Sig Block 42"
    private const val MAGIC_SIZE = 16
    private const val TRAILER_SIZE = 24
    private const val MAX_BLOCK_SIZE = 20_000_000L
    private const val ID_V2 = 0x7109871aL
    private const val ID_V3 = 0xf05368c0L

    /**
     * 返回签名方案，如 "V1 + V2" / "V1" / "未签名"。
     */
    fun detect(apkPath: String): String {
        if (apkPath.isEmpty()) return "未签名"
        val schemes = mutableListOf<String>()

        // V1：META-INF 下的 .RSA/.DSA/.EC 签名文件
        try {
            ZipFile(apkPath).use { zip ->
                val hasV1 = zip.entries().asSequence().any {
                    val n = it.name.uppercase()
                    n.startsWith("META-INF/") &&
                        (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))
                }
                if (hasV1) schemes.add("V1")
            }
        } catch (_: Exception) {
        }

        // V2 / V3：APK Signing Block
        try {
            val ids = readSigningBlockIds(apkPath)
            if (ids.contains(ID_V2)) schemes.add("V2")
            if (ids.contains(ID_V3)) schemes.add("V3")
        } catch (_: Exception) {
        }

        return if (schemes.isEmpty()) "未签名" else schemes.joinToString(" + ")
    }

    private fun readSigningBlockIds(apkPath: String): Set<Long> {
        RandomAccessFile(apkPath, "r").use { file ->
            val fileLength = file.length()
            val eocdOffset = findEocdOffset(file, fileLength) ?: return emptySet()
            if (eocdOffset < 4) return emptySet()
            val centralDirOffset = readUInt32LE(file, eocdOffset + 16) ?: return emptySet()
            if (centralDirOffset < MAGIC_SIZE + TRAILER_SIZE || centralDirOffset > fileLength) return emptySet()

            val magicBytes = ByteArray(MAGIC_SIZE)
            file.seek(centralDirOffset - MAGIC_SIZE)
            file.readFully(magicBytes)
            if (String(magicBytes, Charsets.US_ASCII) != MAGIC) return emptySet()

            val blockSize = readLongLE(file, centralDirOffset - TRAILER_SIZE)
            val blockStart = centralDirOffset - 8 - blockSize
            if (blockSize !in 1..MAX_BLOCK_SIZE || blockStart < 0) return emptySet()
            if (readLongLE(file, blockStart) != blockSize) return emptySet()

            val payloadStart = blockStart + 8
            val payloadEnd = centralDirOffset - TRAILER_SIZE
            val payloadLength = payloadEnd - payloadStart
            if (payloadLength !in 0..MAX_BLOCK_SIZE) return emptySet()

            val payload = ByteArray(payloadLength.toInt())
            file.seek(payloadStart)
            file.readFully(payload)

            val ids = mutableSetOf<Long>()
            var offset = 0
            while (offset + 8 <= payload.size) {
                val pairLength = readLongLE(payload, offset)
                val idOffset = offset + 8
                if (pairLength < 4 || idOffset + pairLength > payload.size) break
                ids.add(readUInt32LE(payload, idOffset))
                offset += (8 + pairLength).toInt()
            }
            return ids
        }
    }

    private fun findEocdOffset(file: RandomAccessFile, fileLength: Long): Long? {
        val minSize = 22
        val maxComment = 0xFFFF
        if (fileLength < minSize) return null
        val searchSize = minOf(fileLength, (minSize + maxComment).toLong()).toInt()
        val buffer = ByteArray(searchSize)
        file.seek(fileLength - searchSize)
        file.readFully(buffer)
        for (pos in (searchSize - minSize) downTo 0) {
            if (readUInt32LE(buffer, pos) == 0x06054b50L) {
                val commentLength = readUInt16LE(buffer, pos + 20)
                if (pos + minSize + commentLength == searchSize) {
                    return fileLength - searchSize + pos
                }
            }
        }
        return null
    }

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun readLongLE(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = result or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return result
    }

    private fun readLongLE(file: RandomAccessFile, offset: Long): Long {
        val bytes = ByteArray(8)
        file.seek(offset)
        file.readFully(bytes)
        return readLongLE(bytes, 0)
    }

    private fun readUInt32LE(file: RandomAccessFile, offset: Long): Long? {
        val bytes = ByteArray(4)
        file.seek(offset)
        file.readFully(bytes)
        val value = readUInt32LE(bytes, 0)
        // ZIP64 哨兵值表示真实偏移在 ZIP64 EOCD 中，此处不支持
        return if (value == 0xFFFFFFFFL) null else value
    }
}
