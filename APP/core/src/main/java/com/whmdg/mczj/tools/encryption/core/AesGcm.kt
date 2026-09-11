package com.whmdg.mczj.tools.encryption.core

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AesGcm256 {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    // ThreadLocal 缓存：避免每块重复 Cipher.getInstance() + SecretKeySpec 创建
    private val cachedCipher = ThreadLocal<Cipher>()
    private val cachedKey = ThreadLocal<Pair<ByteArray, SecretKeySpec>>()

    data class EncryptResult(val iv: ByteArray, val ciphertext: ByteArray)

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): EncryptResult {
        require(key.size == 32) { "AES-256 密钥必须 32 字节，当前 ${key.size}" }
        val iv = SecureRandom.bytes(IV_LENGTH_BYTES)

        val cipher = cachedCipher.get()
            ?: Cipher.getInstance(ALGORITHM).also { cachedCipher.set(it) }
        val keySpec = cachedKey.get()?.takeIf { it.first === key }?.second
            ?: SecretKeySpec(key, "AES").also { cachedKey.set(key to it) }

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        aad?.let { cipher.updateAAD(it) }
        return EncryptResult(iv, cipher.doFinal(plaintext))
    }

    fun decrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == 32) { "AES-256 密钥必须 32 字节" }
        require(iv.size == IV_LENGTH_BYTES) { "GCM IV 必须 12 字节" }
        require(ciphertext.size >= 16) { "密文太短：少于 16 字节 tag" }

        val cipher = cachedCipher.get()
            ?: Cipher.getInstance(ALGORITHM).also { cachedCipher.set(it) }
        val keySpec = cachedKey.get()?.takeIf { it.first === key }?.second
            ?: SecretKeySpec(key, "AES").also { cachedKey.set(key to it) }

        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        aad?.let { cipher.updateAAD(it) }
        return cipher.doFinal(ciphertext)
    }
}
