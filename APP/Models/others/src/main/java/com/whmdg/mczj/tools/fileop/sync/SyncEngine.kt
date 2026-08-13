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
import java.security.MessageDigest

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
    private val onFileComplete: (relativePath: String, success: Boolean) -> Unit
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
     * ① 标记为 UPLOADING（锁定）
     * ② 启动上传 + 后台异步计算 MD5
     * ③ 上传完成
     * ④ 二次验证云端文件存在
     * ⑤ 获取云端信息（大小、时间、cloudHash）
     * ⑥ 写入云端表
     * ⑦ 更新本地表为 COMPLETED（解锁）
     */
    suspend fun uploadSingleFile(
        relativePath: String,
        remoteBasePath: String,
        syncDb: SyncDatabase,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit,
        onComplete: (success: Boolean, error: String?) -> Unit
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

        // ① 锁定 → UPLOADING
        syncDb.updateStatus("local_entries", relativePath, SyncStatus.UPLOADING)

        // ② 启动上传 + 后台异步计算 MD5
        val md5Deferred = async {
            calculateMd5(localFile)
        }

        val uploadSuccess = try {
            ensureRemoteDir(buildRemotePath(remoteBasePath, ""), relativePath)
            webdavClient.uploadFile(localFile, remotePath) { bytesWritten ->
                onProgress(bytesWritten, fileSize)
            }
            true
        } catch (e: Exception) {
            false
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

        // ③ 上传失败处理
        if (!uploadSuccess) {
            syncDb.updateStatus("local_entries", relativePath, SyncStatus.PAUSED, "上传失败")
            onComplete(false, "上传失败")
            return@withContext
        }

        // ④ 二次验证：检查云端文件是否存在
        val cloudExists = try {
            webdavClient.exists(remotePath)
        } catch (_: Exception) {
            false
        }

        if (!cloudExists) {
            syncDb.updateStatus("local_entries", relativePath, SyncStatus.PAUSED, "二次验证失败：云端文件不存在")
            onComplete(false, "二次验证失败")
            return@withContext
        }

        // ⑤ 获取云端信息
        var cloudSize: Long = fileSize
        var cloudLastModified: String? = null
        var cloudHash: String? = null

        try {
            val parentPath = remotePath.substringBeforeLast('/')
            val fileName = remotePath.substringAfterLast('/')
            val children = webdavClient.listChildren(parentPath)
            val cloudFile = children?.find { it.name == fileName }
            if (cloudFile != null) {
                cloudSize = cloudFile.size
                cloudLastModified = java.time.Instant.ofEpochMilli(cloudFile.lastModified).toString()
            }
        } catch (_: Exception) {
            // 获取云端信息失败，不阻塞流程
        }

        // ⑥ 写入云端表
        val now = java.time.Instant.now().toString()
        syncDb.upsertEntry("cloud_entries", SyncEntryRow(
            path = relativePath,
            size = cloudSize,
            lastModified = cloudLastModified ?: now,
            md5 = md5 ?: "",
            cloudHash = cloudHash,
            status = SyncStatus.COMPLETED,
            lastSyncTime = now,
            failReason = if (cloudLastModified == null) "云端元数据获取失败" else null
        ))

        // ⑦ 更新本地表 → COMPLETED（解锁）
        syncDb.updateStatus("local_entries", relativePath, SyncStatus.COMPLETED)
        if (cloudHash != null) {
            syncDb.updateCloudHash("local_entries", relativePath, cloudHash)
        }

        onComplete(true, null)
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

    /** 确保远程目录存在 */
    private suspend fun ensureRemoteDir(basePath: String, relativePath: String) = withContext(Dispatchers.IO) {
        val parts = relativePath.trimStart('/').split('/')
        if (parts.size <= 1) return@withContext
        var current = basePath.trimEnd('/')
        for (i in 0 until parts.size - 1) {
            current = "$current/${parts[i]}"
            try { webdavClient.mkdir(current) } catch (_: Exception) {}
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
}
