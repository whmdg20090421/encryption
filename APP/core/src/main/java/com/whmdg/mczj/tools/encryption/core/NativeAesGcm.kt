package com.whmdg.mczj.tools.encryption.core

/**
 * JNI 桥接：通过 OpenSSL EVP API 直接调用 ARMv8 硬件 AES-256-GCM。
 *
 * 返回格式：
 * - encrypt: [IV 12B][ciphertext][tag 16B]
 * - decrypt: plaintext（tag 验证失败返回 null）
 */
object NativeAesGcm {

    private var available: Boolean? = null // null = 未检测

    init {
        try {
            System.loadLibrary("cryptolib")
        } catch (_: Throwable) {
            available = false
        }
    }

    fun isAvailable(): Boolean {
        if (available != null) return available!!
        available = try {
            encrypt(ByteArray(32), ByteArray(0), null)
            true
        } catch (_: Throwable) {
            false
        }
        return available!!
    }

    /**
     * AES-256-GCM 加密。
     * @return [IV 12B][ciphertext][tag 16B]
     */
    external fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray

    /**
     * AES-256-GCM 解密。
     * @param ciphertextAndTag [ciphertext][tag 16B]（不含 IV）
     * @return 明文，tag 验证失败返回 null
     */
    external fun decrypt(key: ByteArray, iv: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray?): ByteArray?
}
