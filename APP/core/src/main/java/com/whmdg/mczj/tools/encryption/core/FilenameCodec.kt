package com.whmdg.mczj.tools.encryption.core

import android.util.Base64

/**
 * 保险箱文件名加密/解密。
 *
 * 统一为**单层**方案：`Base64URL( AES-256-ECB(DEK, 原文件名UTF-8) ) + ".whm"`。
 * 不含 zlib 压缩与哈希兜底，长度上限由 Android 单文件名 255 字节硬约束决定。
 *
 * 长度约束：AES-256-ECB 密文 = PKCS#5 填充后的明文（P = 16·⌈(N+1)/16⌉），
 * Base64URL 无换行无填充后长度 = ⌈P/3⌉·4，加 ".whm" 后缀 4 字节须 ≤ 255，
 * 可解得原始文件名 UTF-8 字节数 N ≤ [MAX_PLAINTEXT_BYTES]。
 *
 * 加密名可逆，但权威还原来源是 `vault_sync.db.original_name`（查库更快），
 * [decryptName] 仅作为查库未命中时的兜底。目录名不加密，保持明文。
 */
object FilenameCodec {

    /** 加密文件统一后缀。 */
    const val SUFFIX = ".whm"

    /** Android 单文件名字节上限。 */
    private const val MAX_FILENAME_BYTES = 255

    /** 原始文件名允许的最大 UTF-8 字节数（含此值可加密）。 */
    const val MAX_PLAINTEXT_BYTES = 175

    /** 无法解析加密名时的回退显示名。 */
    const val FALLBACK_NAME = "unnamed_recovered"

    private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    /**
     * 校验原始文件名是否可以加密到合法长度。
     * @throws IllegalArgumentException 文件名超过 [MAX_PLAINTEXT_BYTES] 字节
     */
    fun requireEncryptable(filename: String) {
        val bytes = filename.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PLAINTEXT_BYTES) {
            "文件名过长（${bytes.size} 字节，上限 $MAX_PLAINTEXT_BYTES）：$filename"
        }
    }

    /**
     * 加密文件名，返回带 `.whm` 后缀的磁盘名。
     * @throws IllegalArgumentException 文件名过长或 DEK 非法
     */
    fun encryptName(filename: String, dek: ByteArray): String {
        requireEncryptable(filename)
        val ciphertext = AesEcb256.encrypt(dek, filename.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(ciphertext, B64_FLAGS)
        val diskName = encoded + SUFFIX
        check(diskName.toByteArray(Charsets.UTF_8).size <= MAX_FILENAME_BYTES) {
            "加密后文件名超长（${diskName.length} 字符）：$filename"
        }
        return diskName
    }

    /**
     * 逆解加密文件名回原始名。仅作查库未命中时的兜底，失败返回 [FALLBACK_NAME]。
     */
    fun decryptName(encryptedName: String, dek: ByteArray): String {
        val stripped = encryptedName.removeSuffix(SUFFIX)
        return try {
            val ciphertext = Base64.decode(stripped, B64_FLAGS)
            String(AesEcb256.decrypt(dek, ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            FALLBACK_NAME
        }
    }
}
