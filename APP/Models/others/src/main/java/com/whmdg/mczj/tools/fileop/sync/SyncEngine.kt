package com.whmdg.mczj.tools.fileop.sync

import com.whmdg.mczj.tools.encryption.data.SyncDatabase
import com.whmdg.mczj.tools.encryption.data.SyncEntryRow
import com.whmdg.mczj.tools.encryption.data.SyncStatus
import com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient
import kotlinx.coroutines.*
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 同步引擎：负责把本地保险箱文件上传到 WebDAV 云端。
 *
 * - 逐文件上传，带 423/404/网络重试
 * - 上传前以大小 + 明文内容指纹判定是否可跳过
 * - 失败标记为 PAUSED（不支持断点续传）
 */
class SyncEngine(
    private val webdavClient: WebDavFileClient,
    private val vaultDir: String,
    private val logFiles: List<File> = emptyList()
) {
    /**
     * 上传单个文件（完整流程）。
     *
     * ① 预检查：确保远程目录存在 → 检查云端文件 → 比较大小 → 比较明文内容指纹 → 跳过或上传
     * ② 上传（带重试，网络错误重试1次，等待3秒）
     * ③ 验证：比较本地大小 vs 云端大小
     * ④ 记录：写入云端表（内容指纹取自上传前的本地记录）→ 更新本地表为 COMPLETED
     */
    suspend fun uploadSingleFile(
        relativePath: String,
        remoteBasePath: String,
        syncDb: SyncDatabase,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit,
        onComplete: (success: Boolean, error: String?, countBytes: Boolean) -> Unit,
        onStatusChange: () -> Unit = {},
        /**
         * 已由调用方（整树对账）确认过云端状态时置 true：跳过逐个文件的
         * `exists()` + `listChildren` 探测，直接 PUT。批量上传文件夹时使用，
         * 把 N 次 PROPFIND 降到对账阶段的 1 次（或每目录 1 次兜底）。
         */
        preChecked: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val localFile = File(vaultDir, relativePath.trimStart('/'))

        // 边界检查：文件不存在或是目录
        if (!localFile.exists() || !localFile.isFile) {
            val reason = if (localFile.isDirectory) "目标是文件夹，不是文件" else "本地文件已删除"
            CloudSyncLogger.logSync("SyncEngine", "跳过: $relativePath - $reason")
            syncDb.updateStatus("local_entries", relativePath, SyncStatus.PAUSED, reason)
            onComplete(false, reason, false)
            return@withContext
        }

        val fileSize = localFile.length()
        val remotePath = buildRemotePath(remoteBasePath, relativePath)
        val parentPath = remotePath.substringBeforeLast('/')
        val fileName = remotePath.substringAfterLast('/')
        CloudSyncLogger.logSync("SyncEngine", "开始上传: $relativePath -> $remotePath (大小: $fileSize)")

        // ① 预检查：检查云端文件是否已存在
        //    Batch 场景（preChecked=true）时已由整树对账完成，跳过逐文件 PROPFIND。
        if (!preChecked) {
            val cloudExists = try {
                webdavClient.exists(remotePath)
            } catch (_: Exception) {
                false
            }

            if (cloudExists) {
                // 云端已有文件 → 比较大小
                var cloudSize: Long = -1
                try {
                    val children = webdavClient.listChildren(parentPath)
                    val cloudFile = children?.find { it.name == fileName }
                    if (cloudFile != null) {
                        cloudSize = cloudFile.size
                    }
                } catch (_: Exception) {}

                if (cloudSize == fileSize) {
                    // 大小相同 → 比较本地记录与云端记录的明文内容指纹
                    val localEntry = syncDb.getEntry("local_entries", relativePath)
                    val cloudEntry = syncDb.getEntry("cloud_entries", relativePath)
                    if (localEntry?.contentHash != null && localEntry.contentHash == cloudEntry?.contentHash) {
                        // 明文内容指纹相同 → 同一文件，跳过上传
                        CloudSyncLogger.logSync("SyncEngine", "跳过上传（文件内容相同）: $relativePath")
                        syncDb.updateStatus("local_entries", relativePath, SyncStatus.COMPLETED)
                        onComplete(true, null, true)
                        return@withContext
                    }
                }
                // 大小不同或内容指纹不同 → 继续上传
            }
        }

        // ② 锁定 → UPLOADING
        syncDb.updateStatus("local_entries", relativePath, SyncStatus.UPLOADING)
        onStatusChange()

        // 上传时使用的明文内容指纹取自本地已记录的导入值
        val contentHash = syncDb.getEntry("local_entries", relativePath)?.contentHash

        // 确保远程父目录存在（惰性查表缓存，只补建缺失段）
        ensureRemoteDir(remoteBasePath, relativePath, syncDb)

        // ② 上传
        // 普通网络错误：重试1次（等待3秒）。
        // 423 Locked（服务端该路径残留隐式写锁）：优先级最高，命中后立即 DELETE 目标消除锁，
        // 等3秒后重传一次；该分支若仍失败则直接 PAUSED 跳过，不再回普通重试。
        var uploadSuccess = false
        var lastError: String? = null

        // 普通重试已用次数；423 DELETE 重传已用次数；404 目录重建重传已用次数
        var normalRetryUsed = false
        var lockedRetryUsed = false
        var missingDirRetryUsed = false
        while (true) {
            try {
                var totalWritten = 0L
                // 上传进度落库器：进度回调即时（每次 chunk 都上抛），
                // 而 uploaded_size 的 DB 写入按时间合并，避免逐 chunk 独立提交拖慢上传循环。
                val uploadedSizeStore = ThrottledUploadedSizeStore(
                    write = { written -> syncDb.updateUploadedSize("local_entries", relativePath, written) }
                )
                webdavClient.uploadFile(localFile, remotePath) { delta ->
                    totalWritten += delta
                    // 即时回调：本次读了多少就报多少，不随落库节流
                    onProgress(totalWritten, fileSize)
                    uploadedSizeStore.onBytesWritten(totalWritten)
                }
                // 上传结束强制落库一次，保证 DB 里的 uploaded_size 为最终值
                uploadedSizeStore.flush(totalWritten)
                uploadSuccess = true
                break
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                logError("上传失败", relativePath, remotePath, e)

                // 423 专用分支（最高优先级）：任何一次尝试命中 423 都转入此分支
                if (isLocked(e)) {
                    if (lockedRetryUsed) {
                        // 已 DELETE 重传过一次仍 423 → 放弃，标记 PAUSED 并跳过
                        break
                    }
                    lockedRetryUsed = true
                    // 删除服务端残留文件/锁，等 3 秒后重传
                    CloudSyncLogger.logSync("SyncEngine", "命中423，DELETE 目标后重试: $relativePath")
                    try { webdavClient.delete(remotePath) } catch (_: Exception) {}
                    kotlinx.coroutines.delay(3000L)
                    continue
                }

                // 404：父目录实际不存在（缓存标记失真或外部删除）→ 作废标记并重建后重传一次
                if (isNotFound(e)) {
                    if (missingDirRetryUsed) {
                        break
                    }
                    missingDirRetryUsed = true
                    CloudSyncLogger.logSync("SyncEngine", "命中404，作废并重建远程目录后重试: $relativePath")
                    recoverMissingDirs(remoteBasePath, relativePath, syncDb)
                    continue
                }

                // 普通路径：仅网络错误可重试，且只重试1次
                if (!isRetryable(e) || normalRetryUsed) {
                    break
                }
                normalRetryUsed = true
                kotlinx.coroutines.delay(3000L)
            }
        }

        // ② 上传失败处理
        if (!uploadSuccess) {
            val reason = lastError ?: "上传失败"
            CloudSyncLogger.logSync("SyncEngine", "上传失败: $relativePath - $reason")
            syncDb.updateStatus("local_entries", relativePath, SyncStatus.PAUSED, reason)
            // 423 跳过：该文件已尝试且放弃，需把其大小计入进度，否则进度条无法到达 100%
            onComplete(false, reason, lastError != null && isLockedMessage(lastError!!))
            return@withContext
        }

        // ③ 记录：写入云端表
        // PUT 返回 201 即代表服务端已按 Content-Length 完整接收（覆盖式原子写），
        // 不再额外 PROPFIND 校验大小；大小取本地值，修改时间取上传时刻。
        val now = java.time.Instant.now().toString()
        // 原始名从本地行继承（文件名加密的显示名权威来源），随云端表导出以支持跨设备还原
        val originalName = try {
            syncDb.getEntry("local_entries", relativePath)?.originalName
        } catch (_: Exception) {
            null
        }
        syncDb.upsertEntry("cloud_entries", SyncEntryRow(
            path = relativePath,
            size = fileSize,
            lastModified = now,
            contentHash = contentHash ?: "",
            cloudHash = null,
            status = SyncStatus.COMPLETED,
            lastSyncTime = now,
            failReason = null,
            originalName = originalName
        ))

        // ④ 更新本地表 → COMPLETED（解锁）
        CloudSyncLogger.logSync("SyncEngine", "上传成功: $relativePath (大小: $fileSize)")
        syncDb.updateStatus("local_entries", relativePath, SyncStatus.COMPLETED)
        onComplete(true, null, true)
    }

    /** 判断异常是否可重试（网络错误、超时、5xx 可重试；401/403/404 不可重试） */
    private fun isRetryable(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: ""
        if (e is java.io.IOException) return true
        if (msg.contains("timeout") || msg.contains("connection")) return true
        if (msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504")) return true
        if (msg.contains("429")) return true
        if (msg.contains("401") || msg.contains("403") || msg.contains("404")) return false
        return true
    }

    /** 是否为 404 Not Found（父目录不存在） */
    private fun isNotFound(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: ""
        return msg.contains("404") || msg.contains("not found")
    }

    /** 是否为 423 Locked（服务端残留隐式写锁） */
    private fun isLocked(e: Exception): Boolean = isLockedMessage(e.message ?: "")

    /** 消息文本是否表示 423 Locked */
    private fun isLockedMessage(message: String): Boolean {
        val msg = message.lowercase()
        return msg.contains("423") || msg.contains("locked")
    }

    // ── 内部方法 ──

    /**
     * 惰性确保远程父目录存在（带 cloud_entries 缓存）。
     *
     * 目录条目以 path 以 '/' 结尾存入 cloud_entries，dir_created 标记是否已确认创建。
     * 从最深父目录向上逐级查缓存，命中「已创建」即停止向上；只补建缺失的那段，
     * 每创建成功一级即标记 true。同一目录链首次上传付一次成本，后续文件查表即命中。
     */
    private suspend fun ensureRemoteDir(baseBasePath: String, relativePath: String, syncDb: SyncDatabase?) =
        withContext(Dispatchers.IO) {
            val basePath = baseBasePath.trimEnd('/')
            // 收集从 basePath 到目标父目录的所有目录（自顶向下）
            val chain = mutableListOf<String>()
            chain.add(basePath)
            val parts = relativePath.trimStart('/').split('/')
            if (parts.size > 1) {
                var current = basePath
                for (i in 0 until parts.size - 1) {
                    current = "$current/${parts[i]}"
                    chain.add(current)
                }
            }

            // 无 DB 时退化为逐级创建（旧行为）
            if (syncDb == null) {
                for (dir in chain) {
                    try {
                        webdavClient.mkdir(dir)
                    } catch (e: Exception) { /* 已存在（405）也会走到这里，忽略 */ }
                }
                return@withContext
            }

            // 缓存预检查：从最深父目录向上，找一个已确认创建的祖先
            var confirmedUpTo = 0  // chain 中 [0, confirmedUpTo) 均视为已存在
            for (i in chain.indices.reversed()) {
                if (syncDb.getDirCreated(chain[i]) == true) {
                    confirmedUpTo = i + 1
                    break
                }
            }

            // 自顶向下补建缺失段
            for (i in confirmedUpTo until chain.size) {
                markDirCreated(syncDb, chain[i])
            }
        }

    /**
     * 标记目录为已创建：先尝试 MKCOL（已存在返回 405 视为成功），再写入 DB dir_created=true。
     */
    private suspend fun markDirCreated(syncDb: SyncDatabase, dir: String) = withContext(Dispatchers.IO) {
        try {
            webdavClient.mkdir(dir)
        } catch (e: Exception) {
            // 405 Method Not Allowed 表示目录已存在（正常情况，不记日志）；
            // 其它异常先记录，交由后续上传的 404/失败兜底
            if (!isAlreadyExists(e)) {
                logError("创建远程目录", dir, dir, e)
            }
        }
        syncDb.setDirCreated(dir, true)
    }

    /** 是否为 405 Method Not Allowed（目录已存在） */
    private fun isAlreadyExists(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: ""
        return msg.contains("405") || msg.contains("method not allowed")
    }

    /**
     * 404 兜底：PUT 返回 404 说明云端某级目录实际不存在。
     *
     * 自底向上逐级把该链上的目录标记作废为 false（因为不知道缺的是哪一级，
     * 从最深父目录一路向上作废），再从 basePath 自顶向下重建并标记 true。
     */
    private suspend fun recoverMissingDirs(baseBasePath: String, relativePath: String, syncDb: SyncDatabase) =
        withContext(Dispatchers.IO) {
            val basePath = baseBasePath.trimEnd('/')
            val chain = mutableListOf<String>()
            chain.add(basePath)
            val parts = relativePath.trimStart('/').split('/')
            if (parts.size > 1) {
                var current = basePath
                for (i in 0 until parts.size - 1) {
                    current = "$current/${parts[i]}"
                    chain.add(current)
                }
            }
            // 自底向上作废标记
            for (i in chain.indices.reversed()) {
                syncDb.setDirCreated(chain[i], false)
            }
            // 自顶向下重建
            for (dir in chain) {
                markDirCreated(syncDb, dir)
            }
        }

    /** 构建远程路径 */
    private fun buildRemotePath(basePath: String, relativePath: String): String {
        val base = basePath.trimEnd('/')
        val rel = relativePath.trimStart('/')
        return "$base/$rel"
    }

    /** 写入错误日志到所有日志文件 + 云盘日志 */
    private fun logError(action: String, relativePath: String, remotePath: String, error: Exception) {
        // 写入云盘日志（如果开启）
        CloudSyncLogger.logSyncError("SyncEngine", "$action | $relativePath -> $remotePath", error)

        // 写入本次上传的日志文件
        if (logFiles.isEmpty()) return
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val entry = buildString {
            appendLine("════════════════════════════════════════")
            appendLine("时间: $timestamp")
            appendLine("操作: $action")
            appendLine("本地路径: $relativePath")
            appendLine("远程路径: $remotePath")
            appendLine("错误类型: ${error.javaClass.name}")
            appendLine("错误信息: ${error.message}")
            appendLine("堆栈:")
            appendLine(sw.toString())
        }
        for (file in logFiles) {
            try {
                file.parentFile?.mkdirs()
                file.appendText(entry)
            } catch (_: Exception) {}
        }
    }
}
