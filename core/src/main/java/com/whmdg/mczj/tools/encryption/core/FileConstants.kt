package com.whmdg.mczj.tools.encryption.core

/**
 * 与 Python `加密工具.py` 二进制兼容的文件格式常量。
 */
object FileConstants {
    /**
     * `"艨艟战舰".encode('utf-8')`，12 字节
     */
    val magicHeader = "艨艟战舰".toByteArray(Charsets.UTF_8)

    /**
     * Python 的 LEGAL_FOOTER 完全一致
     */
    val legalFooter = ("\n除非在有证据我授权的情况下，否则不得对我的算法具体解密方式进行破解分析研究。" +
            "同时，在没有授权的情况下，不得使用我这个算法进行加密解密。").toByteArray(Charsets.UTF_8)

    /**
     * 自定义加密模式的 AAD 字节串
     */
    val aadCustomObf = "CUSTOM_OBF".toByteArray(Charsets.UTF_8)

    /**
     * 分块大小 = 4 MiB
     */
    const val CHUNK_SIZE = 4 * 1024 * 1024

    /**
     * 大于此阈值时附 legal_footer
     */
    const val FOOTER_THRESHOLD = 4 * 1024 * 1024

    /**
     * 单个加密块允许的最大密文长度（防异常 chunk_len 导致 OOM）
     */
    const val MAX_CHUNK_SIZE = 2 * 4 * 1024 * 1024 + 1024
}
