package com.whmdg.mczj.tools.util

import java.io.File

/**
 * 音频内嵌元数据读取：从 MP3 的 ID3v2 / ID3v1 中提取封面（APIC）与歌词（USLT）。
 *
 * 仅解析 ID3v2.2/2.3/2.4 帧结构，不依赖任何第三方标签库。
 */
object AudioTagReader {

    data class AudioTags(
        val coverBytes: ByteArray?,
        val lyrics: String?
    )

    fun read(filePath: String): AudioTags {
        val file = File(filePath)
        if (!file.isFile) return AudioTags(null, null)
        return try {
            val bytes = file.readBytes()
            readFromBytes(bytes)
        } catch (_: Exception) {
            AudioTags(null, null)
        }
    }

    private fun readFromBytes(data: ByteArray): AudioTags {
        if (data.size < 10 || data[0] != 'I'.code.toByte() ||
            data[1] != 'D'.code.toByte() || data[2] != '3'.code.toByte()
        ) {
            return AudioTags(null, null)
        }

        val majorVersion = data[3].toInt() and 0xFF
        val flags = data[5].toInt() and 0xFF
        val tagSize = synchsafe(data, 6)
        val bodyEnd = minOf(10 + tagSize, data.size)

        var offset = 10
        if (flags and 0x40 != 0 && majorVersion >= 4) {
            // 扩展头（v2.4）：跳过扩展头大小字段
            if (offset + 4 <= bodyEnd) {
                val extSize = synchsafe(data, offset)
                offset += extSize
            }
        }

        var cover: ByteArray? = null
        var lyrics: String? = null
        val headerLen = if (majorVersion == 2) 6 else 10

        while (offset + headerLen <= bodyEnd) {
            val id = String(data, offset, if (majorVersion == 2) 3 else 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break

            val frameSize = if (majorVersion == 2) {
                ((data[offset + 3].toInt() and 0xFF) shl 16) or
                    ((data[offset + 4].toInt() and 0xFF) shl 8) or
                    (data[offset + 5].toInt() and 0xFF)
            } else if (majorVersion == 4) {
                synchsafe(data, offset + 4)
            } else {
                ((data[offset + 4].toInt() and 0xFF) shl 24) or
                    ((data[offset + 5].toInt() and 0xFF) shl 16) or
                    ((data[offset + 6].toInt() and 0xFF) shl 8) or
                    (data[offset + 7].toInt() and 0xFF)
            }
            if (frameSize <= 0) break

            val contentStart = offset + headerLen
            val contentEnd = minOf(contentStart + frameSize, bodyEnd)
            if (contentStart >= contentEnd) break

            when (id) {
                "APIC", "PIC" -> if (cover == null) {
                    cover = parseApic(data, contentStart, contentEnd, majorVersion)
                }
                "USLT", "ULT" -> if (lyrics == null) {
                    lyrics = parseUslt(data, contentStart, contentEnd)
                }
            }

            offset = contentStart + frameSize
        }

        return AudioTags(cover, lyrics)
    }

    private fun parseApic(data: ByteArray, start: Int, end: Int, version: Int): ByteArray? {
        // v2.2 PIC: [encoding][imageFormat(3)][pictureType][description\0][data]
        // v2.3/2.4 APIC: [encoding][mime\0][pictureType][description\0][data]
        if (start >= end) return null
        val encoding = data[start].toInt() and 0xFF
        var pos = start + 1

        if (version == 2) {
            pos += 3
        } else {
            val mimeEnd = indexOf(data, pos, end, 0)
            if (mimeEnd < 0) return null
            pos = mimeEnd + 1
        }
        if (pos >= end) return null

        pos++ // pictureType
        pos = skipTerminatedString(data, pos, end, encoding)

        if (pos < 0 || pos >= end) return null
        return data.copyOfRange(pos, end)
    }

    private fun parseUslt(data: ByteArray, start: Int, end: Int): String? {
        // [encoding][language(3)][descriptor\0][lyrics]
        if (start + 4 >= end) return null
        val encoding = data[start].toInt() and 0xFF
        var pos = start + 4
        pos = skipTerminatedString(data, pos, end, encoding)
        if (pos < 0 || pos >= end) return null
        return decodeText(data, pos, end, encoding)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun skipTerminatedString(data: ByteArray, start: Int, end: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var i = start
            while (i + 1 < end) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i + 2
                i += 2
            }
            return -1
        }
        return indexOf(data, start, end, 0).let { if (it < 0) -1 else it + 1 }
    }

    private fun decodeText(data: ByteArray, start: Int, end: Int, encoding: Int): String? {
        if (start >= end) return null
        return try {
            when (encoding) {
                0 -> String(data, start, end - start, Charsets.ISO_8859_1)
                1 -> String(data, start, end - start, Charsets.UTF_16).let { stripBom(it) }
                2 -> String(data, start, end - start, Charsets.UTF_16BE)
                3 -> String(data, start, end - start, Charsets.UTF_8)
                else -> String(data, start, end - start, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun stripBom(text: String): String =
        if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text

    private fun indexOf(data: ByteArray, start: Int, end: Int, value: Int): Int {
        var i = start
        while (i < end) {
            if ((data[i].toInt() and 0xFF) == value) return i
            i++
        }
        return -1
    }

    private fun synchsafe(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)
    }
}
