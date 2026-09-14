package com.whmdg.mczj.tools.util

data class VaultThumbnailRequest(
    val encryptedPath: String,
    val entryPath: String,
    val vaultDir: String,
    val vaultName: String,
    val dek: ByteArray,
    val customEncryption: Boolean,
    val displayName: String = entryPath.substringAfterLast('/')
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultThumbnailRequest) return false
        return encryptedPath == other.encryptedPath &&
               entryPath == other.entryPath &&
               vaultName == other.vaultName &&
               displayName == other.displayName
    }

    override fun hashCode(): Int {
        var result = encryptedPath.hashCode()
        result = 31 * result + entryPath.hashCode()
        result = 31 * result + vaultName.hashCode()
        result = 31 * result + displayName.hashCode()
        return result
    }
}