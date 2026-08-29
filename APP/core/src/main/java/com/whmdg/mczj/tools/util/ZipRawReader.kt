package com.whmdg.mczj.tools.util

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 手动解析 zip 文件结构，提取原始文件名字节。
 * 绕过 java.util.zip.ZipFile 的 charset 解码，保留原始字节用于编码检测。
 */
object ZipRawReader {

    private const val TAG = "ZipRawReader"

    /** Local File Header 签名 */
    private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50
    /** Central Directory Header 签名 */
    private const val CENTRAL_DIR_SIGNATURE = 0x02014b50
    /** End of Central Directory 签名 */
    private const val EOCD_SIGNATURE = 0x06054b50
    /** ZIP64 End of Central Directory 签名 */
    private const val ZIP64_EOCD_SIGNATURE = 0x06064b50

    data class RawZipEntry(
        val rawName: ByteArray,
        val size: Long,
        val compressedSize: Long,
        val isDirectory: Boolean,
        val lastModifiedTime: Long,
        val generalFlag: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawZipEntry) return false
            return rawName.contentEquals(other.rawName) && size == other.size
        }
        override fun hashCode(): Int = rawName.contentHashCode() * 31 + size.hashCode()
    }

    /**
     * 读取 zip 文件的所有条目，返回原始数据。
     * 优先使用 Central Directory（更可靠），失败时回退到 Local File Header 扫描。
     */
    fun readRawEntries(zipFile: File): List<RawZipEntry> {
        return try {
            val entries = readFromCentralDirectory(zipFile)
            if (entries.isNotEmpty()) entries else readFromLocalHeaders(zipFile)
        } catch (e: Exception) {
            Log.w(TAG, "Central Directory 解析失败，回退到 Local Header 扫描", e)
            readFromLocalHeaders(zipFile)
        }
    }

    /**
     * 从 Central Directory 读取条目（推荐方式，包含完整元数据）。
     */
    private fun readFromCentralDirectory(zipFile: File): List<RawZipEntry> {
        val entries = mutableListOf<RawZipEntry>()
        RandomAccessFile(zipFile, "r").use { raf ->
            val cdOffset = findCentralDirectoryOffset(raf)
            if (cdOffset < 0) return emptyList()

            raf.seek(cdOffset)
            while (raf.filePointer < raf.length()) {
                val sig = readIntLE(raf)
                if (sig != CENTRAL_DIR_SIGNATURE) break

                val versionMadeBy = readShortLE(raf)
                val versionNeeded = readShortLE(raf)
                val generalFlag = readShortLE(raf)
                val compressionMethod = readShortLE(raf)
                val lastModTime = readShortLE(raf)
                val lastModDate = readShortLE(raf)
                val crc32 = readIntLE(raf)
                val compressedSize = readUnsignedInt(raf)
                val uncompressedSize = readUnsignedInt(raf)
                val filenameLen = readShortLE(raf)
                val extraFieldLen = readShortLE(raf)
                val commentLen = readShortLE(raf)
                val diskNumberStart = readShortLE(raf)
                val internalAttributes = readShortLE(raf)
                val externalAttributes = readIntLE(raf)
                val localHeaderOffset = readUnsignedInt(raf)

                val rawName = ByteArray(filenameLen)
                raf.readFully(rawName)

                // 跳过 extra field 和 comment
                val skipBytes = extraFieldLen + commentLen
                raf.seek(raf.filePointer + skipBytes)

                // 处理 zip64：如果标准字段为 0xFFFFFFFF，从 zip64 extra field 读取真实值
                var realCompressed = compressedSize
                var realUncompressed = uncompressedSize
                if (compressedSize == 0xFFFFFFFFL || uncompressedSize == 0xFFFFFFFFL) {
                    // 读取 extra field 中的 zip64 扩展信息
                    val savedPos = raf.filePointer
                    raf.seek(localHeaderOffset + 30 + filenameLen)
                    val zip64Extra = readZip64Extra(raf, extraFieldLen)
                    if (zip64Extra != null) {
                        realUncompressed = zip64Extra.first
                        realCompressed = zip64Extra.second
                    }
                    raf.seek(savedPos)
                }

                val nameStr = String(rawName, Charsets.UTF_8) // 临时解码，仅用于目录判断
                val isDir = nameStr.endsWith("/") || (externalAttributes ushr 16) and 0x10 != 0
                // 文件时间：组合 date 和 time 为毫秒时间戳
                val lastModMs = zipDateTimeToMillis(lastModDate, lastModTime)

                entries.add(
                    RawZipEntry(
                        rawName = rawName,
                        size = realUncompressed,
                        compressedSize = realCompressed,
                        isDirectory = isDir,
                        lastModifiedTime = lastModMs,
                        generalFlag = generalFlag
                    )
                )
            }
        }
        return entries
    }

    /**
     * 从 Local File Header 扫描条目（兜底方式）。
     */
    private fun readFromLocalHeaders(zipFile: File): List<RawZipEntry> {
        val entries = mutableListOf<RawZipEntry>()
        RandomAccessFile(zipFile, "r").use { raf ->
            while (raf.filePointer < raf.length() - 4) {
                val pos = raf.filePointer
                val sig = readIntLE(raf)
                if (sig == LOCAL_FILE_HEADER_SIGNATURE) {
                    val generalFlag = readShortLE(raf)
                    val compressionMethod = readShortLE(raf)
                    val lastModTime = readShortLE(raf)
                    val lastModDate = readShortLE(raf)
                    val crc32 = readIntLE(raf)
                    val compressedSize = readUnsignedInt(raf)
                    val uncompressedSize = readUnsignedInt(raf)
                    val filenameLen = readShortLE(raf)
                    val extraFieldLen = readShortLE(raf)

                    val rawName = ByteArray(filenameLen)
                    raf.readFully(rawName)

                    // 跳过 extra field 和 compressed data
                    val dataOffset = raf.filePointer + extraFieldLen
                    val skipAmount = if (compressedSize == 0xFFFFFFFFL) {
                        // zip64: 尝试从 zip64 extra 读取大小
                        raf.seek(raf.filePointer)
                        val zip64 = readZip64Extra(raf, extraFieldLen)
                        zip64?.second ?: 0L
                    } else {
                        compressedSize
                    }
                    raf.seek(dataOffset + skipAmount)

                    val nameStr = String(rawName, Charsets.UTF_8)
                    val isDir = nameStr.endsWith("/")
                    val lastModMs = zipDateTimeToMillis(lastModDate, lastModTime)

                    entries.add(
                        RawZipEntry(
                            rawName = rawName,
                            size = uncompressedSize,
                            compressedSize = compressedSize,
                            isDirectory = isDir,
                            lastModifiedTime = lastModMs,
                            generalFlag = generalFlag
                        )
                    )
                } else {
                    // 向前搜索下一个签名
                    raf.seek(pos + 1)
                }
            }
        }
        return entries
    }

    /**
     * 查找 Central Directory 的起始偏移。
     * 从文件末尾搜索 EOCD 签名。
     */
    private fun findCentralDirectoryOffset(raf: RandomAccessFile): Long {
        val fileLen = raf.length()
        // EOCD 最小位置：文件末尾 - 22 字节（最小 EOCD 大小）
        val searchStart = maxOf(0L, fileLen - 65536 - 22)

        raf.seek(maxOf(0L, fileLen - 22))
        while (raf.filePointer >= searchStart) {
            val pos = raf.filePointer
            val sig = readIntLE(raf)
            if (sig == EOCD_SIGNATURE) {
                // EOCD 结构：sig(4) + diskNum(2) + cdDiskNum(2) + cdEntriesDisk(2) + cdEntriesTotal(2) + cdSize(4) + cdOffset(4) + commentLen(2)
                raf.seek(pos + 12)
                val cdSize = readUnsignedInt(raf)
                val cdOffset = readUnsignedInt(raf)
                return cdOffset
            }
            raf.seek(pos - 1)
        }
        return -1
    }

    /**
     * 读取 zip64 extra field 中的未压缩/压缩大小。
     * @return Pair(uncompressedSize, compressedSize) 或 null
     */
    private fun readZip64Extra(raf: RandomAccessFile, extraLen: Int): Pair<Long, Long>? {
        val extraStart = raf.filePointer
        val extraEnd = extraStart + extraLen
        while (raf.filePointer + 4 <= extraEnd) {
            val headerId = readShortLE(raf)
            val dataSize = readShortLE(raf)
            if (headerId == 0x0001) { // zip64 extended information
                val uncompressed = if (dataSize >= 8) readULongLE(raf) else 0L
                val compressed = if (dataSize >= 16) readULongLE(raf) else uncompressed
                return Pair(uncompressed, compressed)
            }
            raf.seek(raf.filePointer + dataSize)
        }
        raf.seek(extraEnd)
        return null
    }

    // ── 底层读取工具 ──

    private fun readIntLE(raf: RandomAccessFile): Int {
        val buf = ByteArray(4)
        raf.readFully(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readShortLE(raf: RandomAccessFile): Int {
        val buf = ByteArray(2)
        raf.readFully(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    }

    private fun readUnsignedInt(raf: RandomAccessFile): Long {
        return readIntLE(raf).toLong() and 0xFFFFFFFFL
    }

    private fun readULongLE(raf: RandomAccessFile): Long {
        val lo = readUnsignedInt(raf)
        val hi = readUnsignedInt(raf)
        return (hi shl 32) or lo
    }

    /** 将 zip date/time 组合为毫秒时间戳 */
    private fun zipDateTimeToMillis(date: Int, time: Int): Long {
        val day = date and 0x1F
        val month = (date ushr 5) and 0x0F
        val year = ((date ushr 9) and 0x7F) + 1980
        val second = (time and 0x1F) * 2
        val minute = (time ushr 5) and 0x3F
        val hour = (time ushr 11) and 0x1F

        return try {
            val cal = java.util.Calendar.getInstance()
            cal.set(year, month - 1, day, hour, minute, second)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } catch (_: Exception) {
            0L
        }
    }
}
