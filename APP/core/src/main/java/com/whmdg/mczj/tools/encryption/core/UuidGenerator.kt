package com.whmdg.mczj.tools.encryption.core

import java.security.MessageDigest
import java.util.Base64

/**
 * 保险箱 UUID 生成器。
 * 基于客观变量（密钥材料 + 时间戳）生成唯一标识符。
 */
object UuidGenerator {

    /**
     * 生成保险箱 UUID。
     *
     * @param salt 盐值（Hex 编码）
     * @param encDek 加密的 DEK（Hex 编码）
     * @param timestamp 时间戳（毫秒）
     * @return UUID 字符串（32字符 Base64URL）
     */
    fun generate(salt: String, encDek: String, timestamp: Long): String {
        val input = salt.toByteArray() + encDek.toByteArray() + timestamp.toString().toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(input)
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(hash)
            .take(32)
    }
}
