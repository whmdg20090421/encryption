package com.whmdg.mczj.tools.fileop.sync

import com.whmdg.mczj.tools.encryption.data.SyncDatabase
import com.whmdg.mczj.tools.encryption.data.SyncEntryRow
import com.whmdg.mczj.tools.encryption.data.SyncEntry
import com.whmdg.mczj.tools.encryption.data.SyncStatus
import com.whmdg.mczj.tools.encryption.data.UploadStatus
import com.whmdg.mczj.tools.encryption.data.VaultSyncIndex
import com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient
import kotlinx.coroutines.*
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 同步引擎：负责本地保险箱与 WebDAV 云端之间的文件同步。
 *
 * - 扫描本地/云端文件，计算差异
 * - 逐文件上传/下载，实时报告进度
 * - 失败标记为 PAUSED（不支持断点续传）
 */
class SyncEngine(
    private val webdavClient: WebDavFileClient,
    private val vaultDir: String,
    private val onProgress: (SyncTaskState) -> Unit,
    private val onFileComplete: (relativePath: String, success: Boolean) -> Unit,
    private val logFiles: List<File> = emptyList()
) {
    @Volatile
    private var isCancelled = false

    /** 排除的系统文件 */
    private val excludedFiles = setOf(
        "vault_config.json",
        "vault_config.backup.json",
        "vault_sync_index.json",
        "name_mappings.json",
        "folder_sizes.json"
    )

    /**
     * 启动同步。
     * 返回更新后的索引。
     */
    suspend fun startSync(
        mode: SyncMode,
        remoteBasePath: String,
        index: VaultSyncIndex
    ): VaultSyncIndex = coroutineScope {
        var currentIndex = index
        val fileProgress = mutableMapOf<String, SyncFileProgress>()

        // Phase 1: 扫描
        onProgress(SyncTaskState(phase = SyncPhase.SCANNING, mode = mode))
        val localFiles = scanLocalFiles()
        val remoteFiles = scanRemoteFiles(remoteBasePath)

        // Phase 2: 差异检测
        val toUpload = mutableListOf<LocalFileInfo>()
        val toDownload = mutableListOf<RemoteFileInfo>()

        when (mode) {
            SyncMode.LOCAL_TO_CLOUD -> {
                for ((relPath, localInfo) in localFiles) {
                    val entry = currentIndex.entries[relPath]
                    when {
                        entry == null -> toUpload.add(localInfo)
                        entry.uploadStatus == UploadStatus.PAUSED -> { /* 跳过 */ }
                        entry.uploadStatus == UploadStatus.COMPLETED && entry.md5 == localInfo.md5 -> { /* 跳过 */ }
                        else -> toUpload.add(localInfo)
                    }
                }
            }
            SyncMode.CLOUD_TO_LOCAL -> {
                for ((relPath, remoteInfo) in remoteFiles) {
                    val localInfo = localFiles[relPath]
                    if (localInfo == null || localInfo.size != remoteInfo.size) {
                        toDownload.add(remoteInfo)
                    }
                }
            }
            SyncMode.BIDIRECTIONAL -> {
                // 上传：本地有云端无，或本地修改
                for ((relPath, localInfo) in localFiles) {
                    val remoteInfo = remoteFiles[relPath]
                    val entry = currentIndex.entries[relPath]
                    if (remoteInfo == null) {
                        toUpload.add(localInfo)
                    } else if (entry == null || entry.uploadStatus != UploadStatus.COMPLETED || entry.md5 != localInfo.md5) {
                        toUpload.add(localInfo)
                    }
                }
                // 下载：云端有本地无
                for ((relPath, remoteInfo) in remoteFiles) {
                    if (localFiles[relPath] == null) {
                        toDownload.add(remoteInfo)
                    }
                }
            }
        }

        val totalFiles = toUpload.size + toDownload.size
        val totalBytes = toUpload.sumOf { it.size } + toDownload.sumOf { it.size }

        // 初始化所有待处理文件的进度
        for (info in toUpload) {
            fileProgress[info.relativePath] = SyncFileProgress(
                relativePath = info.relativePath,
                totalBytes = info.size,
                uploadedBytes = 0,
                status = UploadStatus.PENDING
            )
        }
        for (info in toDownload) {
            fileProgress[info.relativePath] = SyncFileProgress(
                relativePath = info.relativePath,
                totalBytes = info.size,
                uploadedBytes = 0,
                status = UploadStatus.PENDING
            )
        }

        onProgress(SyncTaskState(
            phase = SyncPhase.SYNCING,
            mode = mode,
            totalFiles = totalFiles,
            totalBytes = totalBytes,
            fileProgress = fileProgress.toMap()
        ))

        var completedFiles = 0
        var transferredBytes = 0L
        var lastTimeMs = System.currentTimeMillis()
        var lastTransferred = 0L

        // Phase 3: 执行上传
        for (localInfo in toUpload) {
            if (isCancelled) break
            currentCoroutineContext().ensureActive()
            val relPath = localInfo.relativePath

            // 更新状态为 UPLOADING
            fileProgress[relPath] = fileProgress[relPath]!!.copy(status = UploadStatus.UPLOADING)
            currentIndex = updateIndexEntry(currentIndex, relPath, localInfo.md5, localInfo.size, UploadStatus.UPLOADING)
            onProgress(buildTaskState(mode, SyncPhase.SYNCING, totalFiles, completedFiles, totalBytes, transferredBytes, fileProgress, relPath))

            val remotePath = buildRemotePath(remoteBasePath, relPath)
            val localFile = File(vaultDir, relPath.trimStart('/'))

            val success = try {
                // 确保远程目录存在
                ensureRemoteDir(buildRemotePath(remoteBasePath, ""), relPath)
                webdavClient.uploadFile(localFile, remotePath) { bytesWritten ->
                    fileProgress[relPath] = fileProgress[relPath]!!.copy(uploadedBytes = bytesWritten)
                    val nowMs = System.currentTimeMillis()
                    val dtMs = nowMs - lastTimeMs
                    if (dtMs > 500) {
                        val speed = (transferredBytes + bytesWritten - lastTransferred) * 1000 / dtMs
                        lastTimeMs = nowMs
                        lastTransferred = transferredBytes + bytesWritten
                        onProgress(buildTaskState(mode, SyncPhase.SYNCING, totalFiles, completedFiles, totalBytes, transferredBytes + bytesWritten, fileProgress, relPath, speed))
                    }
                }
                true
            } catch (e: Exception) {
                false
            }

            if (success) {
                fileProgress[relPath] = fileProgress[relPath]!!.copy(
                    status = UploadStatus.COMPLETED,
                    uploadedBytes = localInfo.size
                )
                currentIndex = updateIndexEntry(currentIndex, relPath, localInfo.md5, localInfo.size, UploadStatus.COMPLETED)
                completedFiles++
                transferredBytes += localInfo.size
                onFileComplete(relPath, true)
            } else {
                fileProgress[relPath] = fileProgress[relPath]!!.copy(status = UploadStatus.PAUSED)
                currentIndex = updateIndexEntry(currentIndex, relPath, localInfo.md5, localInfo.size, UploadStatus.PAUSED)
                onFileComplete(relPath, false)
            }
            onProgress(buildTaskState(mode, SyncPhase.SYNCING, totalFiles, completedFiles, totalBytes, transferredBytes, fileProgress, relPath))
        }

        // Phase 4: 执行下载
        for (remoteInfo in toDownload) {
            if (isCancelled) break
            currentCoroutineContext().ensureActive()
            val relPath = remoteInfo.relativePath

            fileProgress[relPath] = fileProgress[relPath]!!.copy(status = UploadStatus.UPLOADING)
            onProgress(buildTaskState(mode, SyncPhase.SYNCING, totalFiles, completedFiles, totalBytes, transferredBytes, fileProgress, relPath))

            val localFile = File(vaultDir, relPath.trimStart('/'))
            localFile.parentFile?.mkdirs()

            val success = try {
                webdavClient.downloadFile(remoteInfo.remotePath, localFile) { bytesRead ->
                    fileProgress[relPath] = fileProgress[relPath]!!.copy(uploadedBytes = bytesRead)
                    val nowMs = System.currentTimeMillis()
                    val dtMs = nowMs - lastTimeMs
                    if (dtMs > 500) {
                        val speed = (transferredBytes + bytesRead - lastTransferred) * 1000 / dtMs
                        lastTimeMs = nowMs
                        lastTransferred = transferredBytes + bytesRead
                        onProgress(buildTaskState(mode, SyncPhase.SYNCING, totalFiles, completedFiles, totalBytes, transferredBytes + bytesRead, fileProgress, relPath, speed))
                    }
                }
                true
            } catch (e: Exception) {
                false
            }

            if (success) {
                fileProgress[relPath] = fileProgress[relPath]!!.copy(
                    status = UploadStatus.COMPLETED,
                    uploadedBytes = remoteInfo.size
                )
                val md5 = calculateMd5(localFile)
                currentIndex = updateIndexEntry(currentIndex, relPath, md5, remoteInfo.size, UploadStatus.COMPLETED)
                completedFiles++
                transferredBytes += remoteInfo.size
                onFileComplete(relPath, true)
            } else {
                fileProgress[relPath] = fileProgress[relPath]!!.copy(status = UploadStatus.PAUSED)
                onFileComplete(relPath, false)
            }
            onProgress(buildTaskState(mode, SyncPhase.SYNCING, totalFiles, completedFiles, totalBytes, transferredBytes, fileProgress, relPath))
        }

        // Phase 5: 完成
        onProgress(SyncTaskState(
            phase = SyncPhase.COMPLETED,
            mode = mode,
            totalFiles = totalFiles,
            completedFiles = completedFiles,
            totalBytes = totalBytes,
            transferredBytes = transferredBytes,
            fileProgress = fileProgress.toMap()
        ))

        currentIndex
    }

    fun cancel() {
        isCancelled = true
    }

    /**
     * 上传单个文件（完整流程）。
     *
     * ① 预检查：确保远程目录存在 → 检查云端文件 → 比较大小 → 比较 MD5 → 跳过或上传
     * ② 上传（带重试，网络错误重试1次，等待1秒）
     * ③ 验证：比较本地大小 vs 云端大小
     * ④ 记录：写入云端表 → 更新本地表为 COMPLETED
     */
    suspend fun uploadSingleFile(
        relativePath: String,
        remoteBasePath: String,
        syncDb: SyncDatabase,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit,
        onComplete: (success: Boolean, error: String?) -> Unit,
        onStatusChange: () -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val localFile = File(vaultDir, relativePath.trimStart('/'))

        // 边界检查：文件不存在
        if (!localFile.exists() || !localFile.isFile) {
            syncDb.updateStatus("local_entries", relativePath, SyncStatus.PAUSED, "本地文件已删除")
            onComplete(false, "本地文件已删除")
            return@withContext
        }

        val fileSize = localFile.length()
        val remotePath = buildRemotePath(remoteBasePath, relativePath)
        val parentPath = remotePath.substringBeforeLast('/')
        val fileName = remotePath.substringAfterLast('/')
        CloudSyncLogger.logSync("SyncEngine", "开始上传: $relativePath -> $remotePath (大小: $fileSize)")

        // ① 预检查：确保远程目录存在
        ensureRemoteDir(remoteBasePath, relativePath)

        // ① 预检查：检查云端文件是否已存在
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
                // 大小相同 → 比较 MD5
                val localMd5 = calculateMd5(localFile)
                val cloudEntry = syncDb.getEntry("cloud_entries", relativePath)
                if (cloudEntry != null && cloudEntry.md5 == localMd5) {
                    // 完全相同 → 跳过
                    CloudSyncLogger.logSync("SyncEngine", "跳过上传（文件相同）: $relativePath")
                    syncDb.updateMd5("local_entries", relativePath, localMd5)
                    syncDb.updateStatus("local_entries", relativePath, SyncStatus.COMPLETED)
                    onComplete(true, null)
                    return@withContext
                }
                // MD5 不同或云端表无记录 → 继续上传
            }
            // 大小不同 → 继续上传
        }

        // ② 锁定 → UPLOADING
        syncDb.updateStatus("local_entries", relativePath, SyncStatus.UPLOADING)
        onStatusChange()

        // ② 后台异步计算 MD5
        val md5Deferred = async {
            calculateMd5(localFile)
        }

        // ② 上传（网络错误重试1次，等待1秒）
        var uploadSuccess = false
        var lastError: String? = null

        for (attempt in 1..2) {
            try {
                webdavClient.uploadFile(localFile, remotePath) { bytesWritten ->
                    onProgress(bytesWritten, fileSize)
                }
                uploadSuccess = true
                break
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                logError("上传失败(第${attempt}次)", relativePath, remotePath, e)

                // 只有网络错误才重试，且只重试1次
                if (!isRetryable(e) || attempt >= 2) {
                    break
                }
                kotlinx.coroutines.delay(1000L)
            }
        }

        // 等待 MD5 计算完成
        val md5 = try {
            md5Deferred.await()
        } catch (_: Exception) {
            null
        }

        // 写入 MD5（无论上传是否成功，都记录已计算的 MD5）
        if (md5 != null) {
            syncDb.updateMd5("local_entries", relativePath, md5)
        }

        // ② 上传失败处理
        if (!uploadSuccess) {
            val reason = lastError ?: "上传失败"
            CloudSyncLogger.logSync("SyncEngine", "上传失败: $relativePath - $reason")
            syncDb.updateStatus("local_entries", relativePath, SyncStatus.PAUSED, reason)
            onComplete(false, reason)
            return@withContext
        }

        // ③ 验证：获取云端文件信息（大小 + 时间），一次调用复用
        var cloudSizeAfterUpload: Long = -1
        var cloudLastModified: String? = null
        try {
            val children = webdavClient.listChildren(parentPath)
            val cloudFile = children?.find { it.name == fileName }
            if (cloudFile != null) {
                cloudSizeAfterUpload = cloudFile.size
                cloudLastModified = java.time.Instant.ofEpochMilli(cloudFile.lastModified).toString()
            }
        } catch (e: Exception) {
            logError("获取云端文件信息失败", relativePath, remotePath, e)
        }

        // ③ 验证：比较大小
        if (cloudSizeAfterUpload >= 0 && cloudSizeAfterUpload != fileSize) {
            val reason = "传输损坏: 本地${fileSize}字节 vs 云端${cloudSizeAfterUpload}字节"
            CloudSyncLogger.logSync("SyncEngine", "验证失败: $relativePath - $reason")
            logError("验证失败", relativePath, remotePath, IllegalStateException(reason))
            try { webdavClient.delete(remotePath) } catch (_: Exception) {}
            syncDb.updateStatus("local_entries", relativePath, SyncStatus.PAUSED, reason)
            onComplete(false, reason)
            return@withContext
        }

        // ④ 记录：写入云端表
        val now = java.time.Instant.now().toString()
        syncDb.upsertEntry("cloud_entries", SyncEntryRow(
            path = relativePath,
            size = if (cloudSizeAfterUpload >= 0) cloudSizeAfterUpload else fileSize,
            lastModified = cloudLastModified ?: now,
            md5 = md5 ?: "",
            cloudHash = null,
            status = SyncStatus.COMPLETED,
            lastSyncTime = now,
            failReason = if (cloudLastModified == null) "云端元数据获取失败" else null
        ))

        // ④ 更新本地表 → COMPLETED（解锁）
        CloudSyncLogger.logSync("SyncEngine", "上传成功: $relativePath (大小: $fileSize)")
        syncDb.updateStatus("local_entries", relativePath, SyncStatus.COMPLETED)
        onComplete(true, null)
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

    // ── 内部方法 ──

    private data class LocalFileInfo(
        val relativePath: String,
        val absolutePath: String,
        val md5: String,
        val size: Long
    )

    private data class RemoteFileInfo(
        val relativePath: String,
        val remotePath: String,
        val size: Long
    )

    /** 扫描本地保险箱目录 */
    private suspend fun scanLocalFiles(): Map<String, LocalFileInfo> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, LocalFileInfo>()
        val dir = File(vaultDir)
        if (!dir.exists()) return@withContext result

        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val name = file.name
            if (name in excludedFiles) return@forEach
            val relativePath = "/" + file.relativeTo(dir).path.replace('\\', '/')
            val md5 = calculateMd5(file)
            result[relativePath] = LocalFileInfo(
                relativePath = relativePath,
                absolutePath = file.absolutePath,
                md5 = md5,
                size = file.length()
            )
        }
        result
    }

    /** 扫描云端目录（递归） */
    private suspend fun scanRemoteFiles(remotePath: String): Map<String, RemoteFileInfo> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, RemoteFileInfo>()
        scanRemoteDir(remotePath, remotePath, result)
        result
    }

    private fun scanRemoteDir(basePath: String, currentPath: String, result: MutableMap<String, RemoteFileInfo>) {
        val children = webdavClient.listChildren(currentPath) ?: return
        for (child in children) {
            val childPath = if (currentPath.endsWith("/")) "$currentPath${child.name}" else "$currentPath/${child.name}"
            if (child.isDirectory) {
                scanRemoteDir(basePath, childPath, result)
            } else {
                val relativePath = childPath.removePrefix(basePath).let {
                    if (it.startsWith("/")) it else "/$it"
                }
                result[relativePath] = RemoteFileInfo(
                    relativePath = relativePath,
                    remotePath = childPath,
                    size = child.size
                )
            }
        }
    }

    /** 确保远程目录存在，先检查再创建 */
    private suspend fun ensureRemoteDir(basePath: String, relativePath: String) = withContext(Dispatchers.IO) {
        // 收集所有需要存在的目录路径（从根到目标的父目录）
        val allDirs = mutableListOf<String>()
        // 1. basePath 本身（如 webdav/TF）
        allDirs.add(basePath.trimEnd('/'))
        // 2. relativePath 的各级父目录
        val parts = relativePath.trimStart('/').split('/')
        if (parts.size > 1) {
            var current = basePath.trimEnd('/')
            for (i in 0 until parts.size - 1) {
                current = "$current/${parts[i]}"
                allDirs.add(current)
            }
        }

        // 逐级检查，不存在才创建
        for (dir in allDirs) {
            val exists = try {
                webdavClient.exists(dir)
            } catch (_: Exception) {
                false
            }
            if (!exists) {
                try {
                    webdavClient.mkdir(dir)
                } catch (e: Exception) {
                    logError("创建远程目录", dir, dir, e)
                }
            }
        }
    }

    /** 构建远程路径 */
    private fun buildRemotePath(basePath: String, relativePath: String): String {
        val base = basePath.trimEnd('/')
        val rel = relativePath.trimStart('/')
        return "$base/$rel"
    }

    /** 计算文件 MD5 */
    private fun calculateMd5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 更新索引中的条目 */
    private fun updateIndexEntry(
        index: VaultSyncIndex,
        relativePath: String,
        md5: String,
        size: Long,
        status: UploadStatus
    ): VaultSyncIndex {
        val newEntries = index.entries.toMutableMap()
        newEntries[relativePath] = SyncEntry(
            md5 = md5,
            size = size,
            uploadStatus = status,
            lastSyncTime = if (status == UploadStatus.COMPLETED) java.time.Instant.now().toString() else null
        )
        return index.copy(entries = newEntries)
    }

    /** 构建任务状态 */
    private fun buildTaskState(
        mode: SyncMode,
        phase: SyncPhase,
        totalFiles: Int,
        completedFiles: Int,
        totalBytes: Long,
        transferredBytes: Long,
        fileProgress: Map<String, SyncFileProgress>,
        currentFile: String?,
        speed: Long = 0
    ) = SyncTaskState(
        phase = phase,
        mode = mode,
        totalFiles = totalFiles,
        completedFiles = completedFiles,
        currentFileName = currentFile?.trimStart('/')?.substringAfterLast('/'),
        totalBytes = totalBytes,
        transferredBytes = transferredBytes,
        speed = speed,
        fileProgress = fileProgress.toMap()
    )

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
