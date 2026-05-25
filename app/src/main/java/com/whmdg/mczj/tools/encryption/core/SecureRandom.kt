package com.whmdg.mczj.tools.encryption.core

import java.security.SecureRandom

object SecureRandom {
    private val random = SecureRandom()

    fun bytes(length: Int): ByteArray {
        val out = ByteArray(length)
        random.nextBytes(out)
        return out
    }
}
