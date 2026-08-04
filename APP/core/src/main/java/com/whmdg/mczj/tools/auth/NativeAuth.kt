package com.whmdg.mczj.tools.auth

object NativeAuth {

    init {
        System.loadLibrary("authcore")
    }

    external fun verifyPassword(pw: String): ByteArray?

    external fun keyIdOf(pw: String): Int

    /** 计算 deadline HMAC（用于存储） */
    external fun computeDeadlineHmac(deadline: String, vaultId: String): String

    /**
     * 验证 deadline 是否有效。
     * @return 未过期且 HMAC 正确 → 返回 HMAC hex 字符串；否则返回空字符串
     */
    external fun verifyDeadline(deadline: String, vaultId: String, storedProof: String): String

    /** 通过 JNI native 层调用 VMRuntime.setHiddenApiExemptions({"L"})，绕过 hidden API 限制 */
    external fun bypassHiddenApi(): Boolean
}
