package com.whmdg.mczj.tools.fileop.sync

import com.whmdg.mczj.tools.encryption.data.UploadStatus

/** 同步方向（三种手动模式） */
enum class SyncMode {
    LOCAL_TO_CLOUD,    // 本地 → 云端
    CLOUD_TO_LOCAL,    // 云端 → 本地
    BIDIRECTIONAL      // 双向
}

/** 同步阶段 */
enum class SyncPhase {
    IDLE,          // 空闲
    SCANNING,      // 扫描中
    SYNCING,       // 同步中
    COMPLETED,     // 完成
    FAILED         // 失败
}

/** 单个文件的同步进度 */
data class SyncFileProgress(
    val relativePath: String,
    val totalBytes: Long,
    val uploadedBytes: Long,
    val status: UploadStatus
) {
    val progress: Float
        get() = if (totalBytes > 0) (uploadedBytes.toDouble() / totalBytes).toFloat() else 0f
}

/** 整体同步任务状态（运行时，不持久化） */
data class SyncTaskState(
    val phase: SyncPhase = SyncPhase.IDLE,
    val mode: SyncMode = SyncMode.LOCAL_TO_CLOUD,
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val currentFileName: String? = null,
    val totalBytes: Long = 0,
    val transferredBytes: Long = 0,
    val speed: Long = 0,                   // bytes/sec
    val concurrency: Int = 0,              // 当前并发数
    val fileProgress: Map<String, SyncFileProgress> = emptyMap()
) {
    val overallProgress: Float
        get() = if (totalBytes > 0) (transferredBytes.toDouble() / totalBytes).toFloat() else 0f
}
