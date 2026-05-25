package com.whmdg.mczj.tools.encryption.core

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AesGcm256 {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    data class EncryptResult(val iv: ByteArray, val ciphertext: ByteArray)

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): EncryptResult {
        require(key.size == 32) { "AES-256 密钥必须 32 字节，当前 ${key.size}" }
        val iv = SecureRandom.bytes(IV_LENGTH_BYTES)
        val cipher = Cipher.getInstance(ALGORITHM)
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
        aad?.let { cipher.updateAAD(it) }
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptResult(iv, ciphertext)
    }

    fun decrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == 32) { "AES-256 密钥必须 32 字节" }
        require(iv.size == IV_LENGTH_BYTES) { "GCM IV 必须 12 字节" }
        require(ciphertext.size >= 16) { "密文太短：少于 16 字节 tag" }
        val cipher = Cipher.getInstance(ALGORITHM)
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
        aad?.let { cipher.updateAAD(it) }
        return cipher.doFinal(ciphertext)
    }
}
