package com.whmdg.mczj.tools.encryption.core

import com.whmdg.mczj.tools.encryption.data.Argon2Params
import com.whmdg.mczj.tools.encryption.data.KdfType

/**
 * KDF 统一调度。
 */
object KeyDerivation {

    fun derive(
        password: String,
        salt: ByteArray,
        type: KdfType,
        params: Argon2Params
    ): ByteArray {
        return when (type) {
            KdfType.ARGON2ID -> Argon2idKdf.derive(
                password = password,
                salt = salt,
                timeCost = params.timeCost,
                memoryCostKb = params.memoryCostKb,
                parallelism = params.parallelism
            )
            KdfType.PBKDF2_SHA256 -> {
                // 与 Python 一致：iterations = max(100000, time_cost * 50000)
                val iterations = if (params.timeCost * 50000 > 100000) params.timeCost * 50000 else 100000
                Pbkdf2Kdf.derive(
                    password = password,
                    salt = salt,
                    iterations = iterations
                )
            }
        }
    }
}
