package com.whmdg.mczj.tools.util

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Zip 文件名编码自适应检测。
 * 从原始字节中检测 UTF-8 / GBK / CP437 / ISO-8859-1 编码并解码。
 */
object ZipEncodingDetector {

    private val GBK: Charset = Charset.forName("GBK")
    private val CP437: Charset = Charset.forName("CP437")
    private val ISO_8859_1: Charset = Charsets.ISO_8859_1

    /**
     * 根据原始文件名字节和 UTF-8 flag 解码文件名。
     * @param rawName 原始文件名字节（来自 zip local file header）
     * @param utf8Flag zip general purpose bit flag 的 bit 11 是否设置
     */
    fun decodeFilename(rawName: ByteArray, utf8Flag: Boolean): String {
        if (rawName.isEmpty()) return ""

        // 纯 ASCII 无需检测
        if (rawName.all { it in 0x20..0x7E || it == 0x09.toByte() }) {
            return String(rawName, Charsets.US_ASCII)
        }

        // UTF-8 flag 已设置：直接用 UTF-8
        if (utf8Flag) {
            return decodeWithValidation(rawName, Charsets.UTF_8)
        }

        // 未设置 flag：尝试各种编码
        // 1. UTF-8（现代工具即使不设 flag 也可能用 UTF-8）
        if (isValidUtf8(rawName)) {
            return String(rawName, Charsets.UTF_8)
        }

        // 2. GBK（中文 Windows 环境最常见的编码）
        if (isLikelyGbk(rawName)) {
            return String(rawName, GBK)
        }

        // 3. CP437（DOS 原始编码）
        val cp437Str = String(rawName, CP437)
        if (cp437Str.all { !it.isSurrogate() }) {
            return cp437Str
        }

        // 4. ISO-8859-1（兜底，任何字节都合法）
        return String(rawName, ISO_8859_1)
    }

    /** 用指定 charset 解码，验证结果无替换字符 */
    private fun decodeWithValidation(raw: ByteArray, charset: Charset): String {
        val str = String(raw, charset)
        return if (str.contains('�')) {
            // UTF-8 解码有替换字符，说明不是有效 UTF-8，回退到 GBK
            if (isLikelyGbk(raw)) String(raw, GBK) else str
        } else {
            str
        }
    }

    /**
     * 验证字节数组是否为合法 UTF-8。
     * 检查多字节序列结构：首字节决定后续字节数，后续字节必须是 10xxxxxx 格式。
     */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b <= 0x7F -> { /* ASCII */ i++ }
                b in 0xC2..0xDF -> {
                    // 2 字节序列：110xxxxx 10xxxxxx
                    if (i + 1 >= bytes.size) return false
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    if (b2 !in 0x80..0xBF) return false
                    i += 2
                }
                b in 0xE0..0xEF -> {
                    // 3 字节序列：1110xxxx 10xxxxxx 10xxxxxx
                    if (i + 2 >= bytes.size) return false
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    val b3 = bytes[i + 2].toInt() and 0xFF
                    if (b2 !in 0x80..0xBF || b3 !in 0x80..0xBF) return false
                    // 排除过长编码（overlong）
                    if (b == 0xE0 && b2 < 0xA0) return false
                    i += 3
                }
                b in 0xF0..0xF4 -> {
                    // 4 字节序列：11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
                    if (i + 3 >= bytes.size) return false
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    val b3 = bytes[i + 2].toInt() and 0xFF
                    val b4 = bytes[i + 3].toInt() and 0xFF
                    if (b2 !in 0x80..0xBF || b3 !in 0x80..0xBF || b4 !in 0x80..0xBF) return false
                    // 排除过长和超出 Unicode 范围
                    if (b == 0xF0 && b2 < 0x90) return false
                    if (b == 0xF4 && b2 > 0x8F) return false
                    i += 4
                }
                else -> return false // 非法首字节
            }
        }
        return true
    }

    /**
     * 启发式判断是否为 GBK 编码。
     * GBK 双字节范围：首字节 0x81-0xFE，尾字节 0x40-0xFE（排除 0x7F）。
     * 也接受纯 ASCII 混合。
     */
    private fun isLikelyGbk(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var i = 0
        var hasHighByte = false
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b <= 0x7F -> i++ // ASCII
                b in 0x81..0xFE -> {
                    // GBK 双字节首字节
                    if (i + 1 >= bytes.size) return false
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    if (b2 !in 0x40..0xFE || b2 == 0x7F) return false
                    hasHighByte = true
                    i += 2
                }
                else -> return false // 不符合 GBK 规则
            }
        }
        return hasHighByte
    }
}
