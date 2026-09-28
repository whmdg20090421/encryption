package com.whmdg.mczj.tools.encryption.data

/**
 * 同步状态枚举。
 *
 * - PENDING：未同步，不在同步计划中
 * - QUEUED：等待中，已录入 DB，等待轮到上传
 * - UPLOADING：正在上传，文件已锁定
 * - COMPLETED：已同步，二次验证通过
 * - PAUSED：自动暂停（上传失败，暂不恢复）
 */
enum class SyncStatus {
    PENDING,
    QUEUED,
    UPLOADING,
    COMPLETED,
    PAUSED
}

/** 兼容旧代码 */
typealias UploadStatus = SyncStatus
