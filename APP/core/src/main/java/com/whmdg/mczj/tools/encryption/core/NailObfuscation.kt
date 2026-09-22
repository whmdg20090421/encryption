package com.whmdg.mczj.tools.encryption.core

import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 钉子混淆：将由 DEK 派生的 16 字节秘密拆 4 段，插入到密文 4 个伪随机位置。
 * 解密时按相同算法定位并校验。**字节级与 Python 实现一致。**
 */
object NailObfuscation {

    /**
     * 最短启用钉子的明/密文长度，小于此值原样返回
     */
    private const val MIN_LENGTH = 1024

    // ThreadLocal 缓存：避免每块重复 Mac.getInstance() + init()
    private val cachedMac = ThreadLocal<Mac>()

    /**
     * 插入钉子。返回结果比输入长 16 字节。
     *
     * 纯 [System.arraycopy] 实现：按升序把 4 个 4 字节钉子段拼接到密文的 4 个伪随机
     * 位置之间，字节结果与原 `toMutableList` 逐字节装箱实现完全一致。
     */
    fun insert(chunk: ByteArray, iv: ByteArray, dek: ByteArray): ByteArray {
        val l = chunk.size
        if (l < MIN_LENGTH) return chunk

        val parts = computeNailParts(iv, dek, l)
        val q = l / 4
        if (q == 0 || l - 3 * q <= 0) return chunk

        val p1 = (u32(parts[0]) % q).toInt()
        val p2 = q + (u32(parts[1]) % q).toInt()
        val p3 = 2 * q + (u32(parts[2]) % q).toInt()
        val p4 = 3 * q + (u32(parts[3]) % (l - 3 * q)).toInt()

        val out = ByteArray(l + 16)
        // p1 < p2 < p3 < p4 恒成立，按升序拼接，结果等同倒序插入
        System.arraycopy(chunk, 0, out, 0, p1)
        System.arraycopy(parts[0], 0, out, p1, 4)
        System.arraycopy(chunk, p1, out, p1 + 4, p2 - p1)
        System.arraycopy(parts[1], 0, out, p2 + 4, 4)
        System.arraycopy(chunk, p2, out, p2 + 8, p3 - p2)
        System.arraycopy(parts[2], 0, out, p3 + 8, 4)
        System.arraycopy(chunk, p3, out, p3 + 12, p4 - p3)
        System.arraycopy(parts[3], 0, out, p4 + 12, 4)
        System.arraycopy(chunk, p4, out, p4 + 16, l - p4)
        return out
    }

    /**
     * 抽取钉子并校验。如不匹配抛 [IllegalArgumentException]。
     *
     * 纯 [System.arraycopy] 实现，与 [insert] 严格互逆。
     */
    fun extract(obfChunk: ByteArray, iv: ByteArray, dek: ByteArray): ByteArray {
        val l = obfChunk.size - 16 // 原始长度
        if (l < MIN_LENGTH) return obfChunk

        val parts = computeNailParts(iv, dek, l)
        val q = l / 4
        if (q == 0 || l - 3 * q <= 0) return obfChunk

        val p1 = (u32(parts[0]) % q).toInt()
        val p2 = q + (u32(parts[1]) % q).toInt()
        val p3 = 2 * q + (u32(parts[2]) % q).toInt()
        val p4 = 3 * q + (u32(parts[3]) % (l - 3 * q)).toInt()

        // 插入后各钉子段起始偏移（前面每插入一段整体右移 4 字节）
        val i1 = p1
        val i2 = p2 + 4
        val i3 = p3 + 8
        val i4 = p4 + 12

        if (!matchesAt(obfChunk, i1, parts[0]) ||
            !matchesAt(obfChunk, i2, parts[1]) ||
            !matchesAt(obfChunk, i3, parts[2]) ||
            !matchesAt(obfChunk, i4, parts[3])
        ) {
            throw IllegalArgumentException("安全拦截：数据被篡改或校验失败，钉子提取不匹配")
        }

        val out = ByteArray(l)
        System.arraycopy(obfChunk, 0, out, 0, p1)
        System.arraycopy(obfChunk, i1 + 4, out, p1, p2 - p1)
        System.arraycopy(obfChunk, i2 + 4, out, p2, p3 - p2)
        System.arraycopy(obfChunk, i3 + 4, out, p3, p4 - p3)
        System.arraycopy(obfChunk, i4 + 4, out, p4, l - p4)
        return out
    }

    private fun matchesAt(src: ByteArray, offset: Int, expected: ByteArray): Boolean {
        for (i in 0 until 4) {
            if (src[offset + i] != expected[i]) return false
        }
        return true
    }

    private fun computeNailParts(iv: ByteArray, dek: ByteArray, l: Int): List<ByteArray> {
        val ivInt = BigInteger(1, iv)
        val mac = cachedMac.get()
            ?: Mac.getInstance("HmacSHA256").also { cachedMac.set(it) }
        mac.init(SecretKeySpec(dek, "HmacSHA256"))
        val nailSecret = mac.doFinal("nail".toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)
        val nailInt = BigInteger(1, nailSecret)
        val r = (ivInt.multiply(BigInteger.valueOf(l.toLong())).multiply(BigInteger.valueOf(421)))
            .xor(nailInt)
        
        val last16 = bigToLast16(r)
        return listOf(
            last16.copyOfRange(0, 4),
            last16.copyOfRange(4, 8),
            last16.copyOfRange(8, 12),
            last16.copyOfRange(12, 16)
        )
    }

    private fun bigToLast16(r: BigInteger): ByteArray {
        val bytes = r.toByteArray()
        val result = ByteArray(16)
        if (bytes.size >= 16) {
            System.arraycopy(bytes, bytes.size - 16, result, 0, 16)
        } else {
            System.arraycopy(bytes, 0, result, 16 - bytes.size, bytes.size)
        }
        return result
    }

    private fun u32(b: ByteArray): Long {
        var res = 0L
        for (i in 0..3) {
            res = (res shl 8) or (b[i].toLong() and 0xff)
        }
        return res
    }
}
