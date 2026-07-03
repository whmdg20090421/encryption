package com.whmdg.mczj.tools.encryption.core

object HexCodec {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val i = b.toInt() and 0xff
            sb.append(DIGITS[i shr 4])
            sb.append(DIGITS[i and 0x0f])
        }
        return sb.toString()
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex 长度必须为偶数" }
        val out = ByteArray(hex.length / 2)
        for (i in 0 until hex.length step 2) {
            out[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        }
        return out
    }
}
