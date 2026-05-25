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

    /**
     * 插入钉子。返回结果比输入长 16 字节。
     */
    fun insert(chunk: ByteArray, iv: ByteArray, dek: ByteArray): ByteArray {
        val l = chunk.size
        if (l < MIN_LENGTH) return chunk

        val parts = computeNailParts(iv, dek, l)
        val q = l / 4
        if (q == 0 || l - 3 * q <= 0) return chunk

        val seed1 = u32(parts[0])
        val seed2 = u32(parts[1])
        val seed3 = u32(parts[2])
        val seed4 = u32(parts[3])
        val p1 = (seed1 % q).toInt()
        val p2 = q + (seed2 % q).toInt()
        val p3 = 2 * q + (seed3 % q).toInt()
        val p4 = 3 * q + (seed4 % (l - 3 * q)).toInt()

        val list = chunk.toMutableList()
        // 倒着插入
        list.addAll(p4, parts[3].toList())
        list.addAll(p3, parts[2].toList())
        list.addAll(p2, parts[1].toList())
        list.addAll(p1, parts[0].toList())
        return list.toByteArray()
    }

    /**
     * 抽取钉子并校验。如不匹配抛 [IllegalArgumentException]。
     */
    fun extract(obfChunk: ByteArray, iv: ByteArray, dek: ByteArray): ByteArray {
        val l = obfChunk.size - 16 // 原始长度
        if (l < MIN_LENGTH) return obfChunk

        val parts = computeNailParts(iv, dek, l)
        val q = l / 4
        if (q == 0 || l - 3 * q <= 0) return obfChunk

        val seed1 = u32(parts[0])
        val seed2 = u32(parts[1])
        val seed3 = u32(parts[2])
        val seed4 = u32(parts[3])
        val p1 = (seed1 % q).toInt()
        val p2 = q + (seed2 % q).toInt()
        val p3 = 2 * q + (seed3 % q).toInt()
        val p4 = 3 * q + (seed4 % (l - 3 * q)).toInt()

        val list = obfChunk.toMutableList()
        val ext1 = list.subList(p1, p1 + 4).toByteArray()
        repeat(4) { list.removeAt(p1) }
        val ext2 = list.subList(p2, p2 + 4).toByteArray()
        repeat(4) { list.removeAt(p2) }
        val ext3 = list.subList(p3, p3 + 4).toByteArray()
        repeat(4) { list.removeAt(p3) }
        val ext4 = list.subList(p4, p4 + 4).toByteArray()
        repeat(4) { list.removeAt(p4) }

        if (!ext1.contentEquals(parts[0]) ||
            !ext2.contentEquals(parts[1]) ||
            !ext3.contentEquals(parts[2]) ||
            !ext4.contentEquals(parts[3])
        ) {
            throw IllegalArgumentException("安全拦截：数据被篡改或校验失败，钉子提取不匹配")
        }
        return list.toByteArray()
    }

    private fun computeNailParts(iv: ByteArray, dek: ByteArray, l: Int): List<ByteArray> {
        val ivInt = BigInteger(1, iv)
        val mac = Mac.getInstance("HmacSHA256")
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
