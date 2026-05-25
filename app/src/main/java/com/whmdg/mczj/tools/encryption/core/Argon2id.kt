package com.whmdg.mczj.tools.encryption.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

object Argon2idKdf {
    fun derive(
        password: String,
        salt: ByteArray,
        timeCost: Int,
        memoryCostKb: Int,
        parallelism: Int,
        outputLength: Int = 32
    ): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(timeCost)
            .withMemoryAsKB(memoryCostKb)
            .withParallelism(parallelism)
            .withSalt(salt)

        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        val result = ByteArray(outputLength)
        generator.generateBytes(password.toByteArray(Charsets.UTF_8), result)
        return result
    }
}
