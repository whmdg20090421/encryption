package com.whmdg.mczj.tools.encryption.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 加密箱在全局数据库（`vault_db.json`）中的元信息记录。
 */
@Serializable
data class VaultRecord(
    val id: Int,
    val uuid: String = "",
    val name: String,
    val location: StorageLocation,
    val relativePath: String,
    @SerialName("encrypt_filename") val encryptFilename: Boolean,
    @SerialName("encrypt_metadata") val encryptMetadata: Boolean,
    @SerialName("custom_encryption") val customEncryption: Boolean,
    val algorithm: String = "AES-256-GCM",
    val createdAt: String, // 存 ISO8601 字符串
    @SerialName("last_opened_at") val lastOpenedAt: String? = null,
    @SerialName("last_modified_at") val lastModifiedAt: String? = null,
    @SerialName("storage_size") val storageSize: Long = 0,  // 字节，0 = 未统计
    @SerialName("file_count") val fileCount: Int? = null    // 文件数量，null = 未统计
)
