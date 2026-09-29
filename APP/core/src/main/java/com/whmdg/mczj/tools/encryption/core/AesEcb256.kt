package com.whmdg.mczj.tools.encryption.core

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-ECB 文件名加解密实现。
 *
 * 仅用于**短文本（文件名）**的确定性可逆加密：无 IV、无认证标签，密文长度只由
 * 明文长度与 PKCS#5 填充决定（最多多一个 16 字节块），因此编码后长度最短，
 * 能在 255 字节的单文件名限制内承载更长的原始名。
 *
 * 安全定位：ECB 确定性、无认证，不适用于文件内容加密（内容走 [AesGcm256]）。
 * 文件名恢复的权威来源是 `vault_sync.db` 的 `original_name` 列，本类只作为
 * 查库未命中时的逆解兜底。
 */
object AesEcb256 {
    private const val ALGORITHM = "AES/ECB/PKCS5Padding"

    // ThreadLocal 缓存：避免每次加解密文件名都重新 getInstance + SecretKeySpec
    private val cachedCipher = ThreadLocal<Cipher>()
    private val cachedKey = ThreadLocal<Pair<ByteArray, SecretKeySpec>>()

    /** 当前实际生效的实现名称（供诊断显示）。 */
    val backendName: String by lazy {
        Cipher.getInstance(ALGORITHM).provider.name
    }

    private fun cipherFor(key: ByteArray): Cipher {
        require(key.size == 32) { "AES-256 密钥必须 32 字节，当前 ${key.size}" }
        return cachedCipher.get() ?: Cipher.getInstance(ALGORITHM).also { cachedCipher.set(it) }
    }

    private fun keyFor(key: ByteArray): SecretKeySpec =
        cachedKey.get()?.takeIf { it.first === key }?.second
            ?: SecretKeySpec(key, "AES").also { cachedKey.set(key to it) }

    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = cipherFor(key)
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(key))
        return cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = cipherFor(key)
        cipher.init(Cipher.DECRYPT_MODE, keyFor(key))
        return cipher.doFinal(ciphertext)
    }
}
