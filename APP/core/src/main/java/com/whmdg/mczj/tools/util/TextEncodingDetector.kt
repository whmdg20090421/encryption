package com.whmdg.mczj.tools.util

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本文件编码检测。
 *
 * 分层检测策略（参考 VS Code / Notepad++ 的做法）：
 * 1. BOM 检测（UTF-8 / UTF-16LE / UTF-16BE）
 * 2. 前 512 字节 NUL 奇偶模式 → UTF-16
 * 3. UTF-8 严格解码（CharsetDecoder + REPORT）成功 → UTF-8
 * 4. GBK 解码成功 → GBK
 * 5. 全部失败 → 未知（调用方按 UTF-8 兜底）
 *
 * 注意：检测结果不剥离 BOM。读取时 BOM 按原样进入文本，写入时按原样保留，
 * 由 [detect] 返回的 [DetectResult.hasBom] 仅供显示或调试参考。
 */
object TextEncodingDetector {

    private const val UTF16_NUL_SCAN_LIMIT = 512

    private val GBK: Charset = Charset.forName("GBK")

    private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val BOM_UTF16LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val BOM_UTF16BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    data class DetectResult(
        val charset: Charset?,
        val displayName: String,
        val bom: ByteArray = ByteArray(0)
    ) {
        val hasBom: Boolean get() = bom.isNotEmpty()
    }

    /**
     * 检测字节数组的编码。
     * @return 检测结果；[DetectResult.charset] 为 null 表示未知
     */
    fun detect(bytes: ByteArray): DetectResult {
        if (bytes.isEmpty()) {
            return DetectResult(Charsets.UTF_8, "UTF-8")
        }

        // 1. BOM 检测（保留原始 BOM 字节，供保存时原样补回）
        when {
            bytes.hasPrefix(BOM_UTF8) ->
                return DetectResult(Charsets.UTF_8, "UTF-8", BOM_UTF8)
            bytes.hasPrefix(BOM_UTF16LE) ->
                return DetectResult(Charsets.UTF_16LE, "UTF-16LE", BOM_UTF16LE)
            bytes.hasPrefix(BOM_UTF16BE) ->
                return DetectResult(Charsets.UTF_16BE, "UTF-16BE", BOM_UTF16BE)
        }

        // 2. 无 BOM 的 UTF-16 检测（NUL 字节奇偶分布）
        detectUtf16ByNulPattern(bytes)?.let { return it }

        // 3. UTF-8 严格解码
        if (isValidUtf8(bytes)) {
            return DetectResult(Charsets.UTF_8, "UTF-8")
        }

        // 4. GBK
        if (isValidGbk(bytes)) {
            return DetectResult(GBK, "GBK")
        }

        // 5. 未知
        return DetectResult(null, "未知")
    }

    /**
     * 通过 NUL 字节的位置规律判断无 BOM 的 UTF-16。
     * UTF-16LE 的 ASCII 字符表现为 [非0][0]，UTF-16BE 为 [0][非0]。
     */
    private fun detectUtf16ByNulPattern(bytes: ByteArray): DetectResult? {
        val limit = minOf(bytes.size, UTF16_NUL_SCAN_LIMIT)
        var couldBeLe = true
        var couldBeBe = true
        var sawNul = false

        for (i in 0 until limit) {
            val isOdd = (i % 2 == 1)
            val isZero = bytes[i].toInt() == 0
            if (isZero) sawNul = true
            if (couldBeLe && ((isOdd && !isZero) || (!isOdd && isZero))) couldBeLe = false
            if (couldBeBe && ((isOdd && isZero) || (!isOdd && !isZero))) couldBeBe = false
            if (!couldBeLe && !couldBeBe) return null
        }

        if (!sawNul) return null
        if (couldBeLe) return DetectResult(Charsets.UTF_16LE, "UTF-16LE")
        if (couldBeBe) return DetectResult(Charsets.UTF_16BE, "UTF-16BE")
        return null
    }

    /** 严格验证 UTF-8：解码过程中出现非法字节即返回 false */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    /**
     * 验证 GBK：首字节 0x81-0xFE，尾字节 0x40-0xFE（排除 0x7F）。
     * 要求至少出现一个双字节字符，避免把纯 ASCII 误判为 GBK。
     */
    private fun isValidGbk(bytes: ByteArray): Boolean {
        var i = 0
        var hasDoubleByte = false
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b <= 0x7F -> i++
                b in 0x81..0xFE -> {
                    if (i + 1 >= bytes.size) return false
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    if (b2 !in 0x40..0xFE || b2 == 0x7F) return false
                    hasDoubleByte = true
                    i += 2
                }
                else -> return false
            }
        }
        return hasDoubleByte
    }

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }
}
