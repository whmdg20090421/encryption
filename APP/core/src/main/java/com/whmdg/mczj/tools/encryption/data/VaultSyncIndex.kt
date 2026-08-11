package com.whmdg.mczj.tools.encryption.data

import kotlinx.serialization.Serializable

/**
 * 保险箱云盘同步索引。
 *
 * 存放路径：保险箱目录下 `vault_sync_index.json`
 * 记录每个文件的 MD5、大小、上传状态，用于差异检测和断点续传判断。
 */
@Serializable
data class VaultSyncIndex(
    val version: Int = 1,
    val webdavConfigId: Long = 0,        // 关联的 WebDAV 服务器 ID
    val remoteBasePath: String = "",     // 用户配置的根路径（空=根目录）
    val vaultFolderName: String = "",    // 保险箱文件夹名（自动用保险箱名称）
    val entries: Map<String, SyncEntry> = emptyMap()
) {
    /** 实际云端基准路径 = remoteBasePath/vaultFolderName */
    val effectiveRemoteBase: String
        get() {
            val base = remoteBasePath.trimEnd('/')
            return if (base.isEmpty()) "/$vaultFolderName" else "$base/$vaultFolderName"
        }
}

@Serializable
data class SyncEntry(
    val md5: String,                     // 文件 MD5
    val size: Long,                      // 文件大小（字节）
    val uploadStatus: UploadStatus = UploadStatus.PENDING,
    val cloudPath: String? = null,       // 云端相对路径（用于快速定位）
    val lastSyncTime: String? = null     // ISO8601
)

@Serializable
enum class UploadStatus {
    PENDING,             // 未开始
    UPLOADING,           // 正在上传
    COMPLETED,           // 已完成
    PAUSED_PERMANENT     // 失败导致的永久暂停（不支持断点续传，需从头重传）
}
