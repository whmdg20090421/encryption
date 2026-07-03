package com.whmdg.mczj.tools.encryption.core

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Pbkdf2Kdf {
    fun derive(
        password: String,
        salt: ByteArray,
        iterations: Int,
        outputBits: Int = 256
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, outputBits)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}
