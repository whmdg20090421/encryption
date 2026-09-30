package com.whmdg.mczj.tools.encryption.core

import android.util.Base64
import java.security.MessageDigest

/**
 * 保险箱文件名加密/解密。
 *
 * 两种磁盘名格式，均以 `.whm` 结尾：
 *  - **可逆名**（原名 UTF-8 ≤ [MAX_PLAINTEXT_BYTES] 字节）：
 *    `Base64URL( AES-256-ECB(DEK, 原文件名UTF-8) ) + ".whm"`。
 *    长度：AES-256-ECB 密文 = PKCS#5 填充后的明文（P = 16·⌈(N+1)/16⌉），
 *    Base64URL 无换行无填充后长度 = ⌈P/3⌉·4，加 ".whm" 后缀 4 字节须 ≤ 255。
 *  - **哈希名**（原名超长时）：`SHA-256(原文件名UTF-8) 的 64 位小写 hex + ".whm"`
 *    （固定 68 字符，永不超限）。哈希名**不可逆**，原始名由 `vault_sync.db.original_name`
 *    还原（权威来源，加密/上传/列表均已落库）。
 *
 * 判别：去除 `.whm` 后恰好 64 位且全为 `[0-9a-f]` 者视为哈希名。
 * Base64URL 字符集为 `A-Za-z0-9-_`，其中仅 `0-9a-f` 落在 hex 集合，一个 AES 名
 * 恰好 64 位全 hex 的概率约 2^-128，工程上可忽略；且其可逆性本就只是查库未命中时的兜底。
 *
 * 即便生成的是可逆名，权威还原来源也始终是 `vault_sync.db.original_name`（查库更快），
 * [decryptName] 仅作为查库未命中时的兜底。目录名不加密，保持明文。
 */
object FilenameCodec {

    /** 加密文件统一后缀。 */
    const val SUFFIX = ".whm"

    /** Android 单文件名字节上限。 */
    private const val MAX_FILENAME_BYTES = 255

    /** 可逆（AES）方案的原始文件名最大 UTF-8 字节数；超过则改用哈希名。 */
    const val MAX_PLAINTEXT_BYTES = 175

    /** SHA-256 十六进制摘要长度。 */
    private const val HASH_HEX_LEN = 64

    /** 无法解析加密名时的回退显示名。 */
    const val FALLBACK_NAME = "unnamed_recovered"

    private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    /**
     * 加密文件名，返回带 `.whm` 后缀的磁盘名。
     *
     * 原名 UTF-8 字节数 ≤ [MAX_PLAINTEXT_BYTES] 时返回可逆的 AES 名；
     * 超长时返回 `SHA-256(原名)` 哈希名（不可逆，靠 DB 还原）。
     */
    fun encryptName(filename: String, dek: ByteArray): String {
        val bytes = filename.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_PLAINTEXT_BYTES) {
            val ciphertext = AesEcb256.encrypt(dek, bytes)
            val encoded = Base64.encodeToString(ciphertext, B64_FLAGS)
            val diskName = encoded + SUFFIX
            check(diskName.toByteArray(Charsets.UTF_8).size <= MAX_FILENAME_BYTES) {
                "加密后文件名超长（${diskName.length} 字符）：$filename"
            }
            return diskName
        }
        return hashName(filename)
    }

    /**
     * 生成超长文件名的哈希磁盘名：`SHA-256(原名) hex + ".whm"`。
     * 固定 68 字符，恒小于 255，永不自相冲突（同一目录内）。
     */
    private fun hashName(filename: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(filename.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex + SUFFIX
    }

    /**
     * 逆解加密文件名回原始名。仅作查库未命中时的兜底：
     *  - 哈希名 → 不可逆，返回 [FALLBACK_NAME]（由调用方改查 DB）；
     *  - 可逆名 → AES 解密；失败同样返回 [FALLBACK_NAME]。
     */
    fun decryptName(encryptedName: String, dek: ByteArray): String {
        val stripped = encryptedName.removeSuffix(SUFFIX)
        if (isHashName(stripped)) return FALLBACK_NAME
        return try {
            val ciphertext = Base64.decode(stripped, B64_FLAGS)
            String(AesEcb256.decrypt(dek, ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            FALLBACK_NAME
        }
    }

    /** 判断去后缀后的名字是否为 SHA-256 哈希名（64 位小写十六进制）。 */
    fun isHashName(stripped: String): Boolean {
        if (stripped.length != HASH_HEX_LEN) return false
        for (c in stripped) {
            if (c !in '0'..'9' && c !in 'a'..'f') return false
        }
        return true
    }
}
