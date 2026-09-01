package com.whmdg.mczj.tools.encryption.data

import kotlinx.serialization.Serializable

/** 云端同步目录中的保险箱清单。ID 是跨设备稳定身份。 */
@Serializable
data class CloudVaultCatalog(
    val version: Int = 1,
    val vaults: List<CloudVaultMetadata> = emptyList()
)

@Serializable
data class CloudVaultMetadata(
    val id: Int,
    val name: String,
    val remoteFolder: String,
    val relativePath: String = "",
    val createdAt: String,
    val location: StorageLocation = StorageLocation.EXTERNAL,
    val encryptFilename: Boolean = false,
    val encryptMetadata: Boolean = false,
    val customEncryption: Boolean = false,
    val algorithm: String = "AES-256-GCM",
    val lastModifiedAt: String? = null
) {
    fun toVaultRecord(localPath: String = relativePath.ifBlank { name }): VaultRecord = VaultRecord(
        id = id,
        name = name,
        location = location,
        relativePath = localPath,
        encryptFilename = encryptFilename,
        encryptMetadata = encryptMetadata,
        customEncryption = customEncryption,
        algorithm = algorithm,
        createdAt = createdAt,
        lastModifiedAt = lastModifiedAt
    )
}
