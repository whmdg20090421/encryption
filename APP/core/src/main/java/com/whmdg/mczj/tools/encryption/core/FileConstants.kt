package com.whmdg.mczj.tools.encryption.core

/**
 * 与 Python `加密工具.py` 二进制兼容的文件格式常量。
 */
object FileConstants {
    /**
     * `"艨艟战舰".encode('utf-8')`，12 字节，标识加密文件
     */
    val magicHeader = "艨艟战舰".toByteArray(Charsets.UTF_8)

    /**
     * 自定义加密模式的 AAD 字节串
     */
    val aadCustomObf = "CUSTOM_OBF".toByteArray(Charsets.UTF_8)

    /**
     * 分块大小 = 1 MiB
     */
    const val CHUNK_SIZE = 1 * 1024 * 1024

    /**
     * 单个加密块允许的最大密文长度（防异常 chunk_len 导致 OOM）
     */
    const val MAX_CHUNK_SIZE = 2 * 4 * 1024 * 1024 + 1024
}
