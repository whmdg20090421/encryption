package com.whmdg.mczj.tools.ui.filemanager

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.whmdg.mczj.tools.encryption.data.SyncDatabase
import com.whmdg.mczj.tools.encryption.data.SyncEntryRow
import com.whmdg.mczj.tools.encryption.data.SyncStatus
import com.whmdg.mczj.tools.encryption.data.UploadStatus
import com.whmdg.mczj.tools.encryption.data.VaultSyncIndex
import com.whmdg.mczj.tools.fileop.sync.SyncEngine
import com.whmdg.mczj.tools.fileop.sync.SyncFileProgress
import com.whmdg.mczj.tools.fileop.sync.SyncMode
import com.whmdg.mczj.tools.fileop.sync.SyncPhase
import com.whmdg.mczj.tools.fileop.sync.SyncTaskState
import com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient
import com.whmdg.mczj.tools.fileop.webdav.WebDavServerConfig
import kotlinx.coroutines.*
import java.io.File
import java.time.Instant

/**
 * 云盘面板控制器。
 *
 * 显示本地保险箱文件 + 同步状态（不从 WebDAV 读取）。
 * 文件夹显示聚合同步状态（自底向上冒泡）。
 * 使用 SyncDatabase（SQLite）管理本地表和云端表。
 */
class CloudPaneController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val webdavConfig: WebDavServerConfig,
    private val vaultDir: String,
    private val vaultId: Int,
    private val vaultName: String
) {
    val state = CloudPanelState()
    private val webdavClient = WebDavFileClient(webdavConfig)
    private var syncJob: Job? = null
    private lateinit var syncDb: SyncDatabase

    /** 云盘面板状态（完全独立，使用 mutableStateOf 驱动 Compose recomposition） */
    class CloudPanelState {
        var currentPath by mutableStateOf("/")
        var entries by mutableStateOf<List<CloudFileEntry>>(emptyList())
        var isLoading by mutableStateOf(false)
        var loadError by mutableStateOf<Throwable?>(null)
        var selectedPaths by mutableStateOf<Set<String>>(emptySet())
        var syncTask by mutableStateOf(SyncTaskState())
        var vaultFolderName by mutableStateOf("")
        /** 同步弹窗是否可见（false=隐藏为悬浮窗或关闭） */
        var syncDialogVisible by mutableStateOf(false)
        /** 上传确认对话框（跳过已完成 / 全部重新上传） */
        var uploadConfirmDialog by mutableStateOf<UploadConfirmState?>(null)
    }

    data class UploadConfirmState(
        val completedCount: Int,
        val totalCount: Int,
        val onComplete: (reUploadAll: Boolean) -> Unit
    )

    /** 文件/文件夹条目（带聚合同步状态） */
    data class CloudFileEntry(
        val name: String,
        val relativePath: String,
        val isDirectory: Boolean,
        /** 文件：自身大小；文件夹：下所有文件总大小 */
        val totalSize: Long,
        /** 文件：自身大小（如已完成）；文件夹：下所有已完成文件总大小 */
        val uploadedSize: Long,
        /** 文件夹：下所有正在上传文件总大小 */
        val uploadingSize: Long,
        val lastModified: Long = 0,
        /** 文件的单个同步状态（文件夹为 null，用聚合字段代替） */
        val syncStatus: SyncStatus? = null
    )

    /** 排除的系统文件 */
    private val excludedFiles = setOf(
        "vault_config.json",
        "vault_config.backup.json",
        "vault_sync_index.json",
        "name_mappings.json",
        "folder_sizes.json"
    )

    /** 远程基准路径 */
    private val remoteBasePath: String
        get() {
            val base = webdavConfig.relativePath.trimEnd('/')
            val folder = vaultName
            return if (base.isEmpty()) "/$folder" else "$base/$folder"
        }

    /** 初始化：打开 DB + 注册日志写入器 + 同步本地文件 + 列出根目录 */
    fun init() {
        syncDb = SyncDatabase.getInstance(context, vaultName)
        state.vaultFolderName = vaultName
        // 中断恢复：WebDAV 不支持断点续传，所有 UPLOADING 重置为 PENDING
        syncDb.resetUploadingToPending("local_entries")
        // 注册云盘日志写入器
        com.whmdg.mczj.tools.fileop.sync.CloudSyncLogger.externalWriter = { tag, message ->
            com.whmdg.mczj.tools.fileop.sync.CloudSyncLogger.log(context, tag, message)
        }
        syncLocalFiles()
        navigateTo("/")
    }

    /** 导航到本地保险箱内的相对路径 */
    fun navigateTo(path: String) {
        scope.launch {
            state.isLoading = true
            state.loadError = null
            try {
                val entries = withContext(Dispatchers.IO) {
                    listLocalFiles(path)
                }
                state.currentPath = path
                state.entries = entries
            } catch (e: Exception) {
                state.loadError = e
                state.entries = emptyList()
            }
            state.isLoading = false
        }
    }

    /** 返回上级目录 */
    fun goUp(): String? {
        if (state.currentPath == "/") return null
        val parent = state.currentPath.substringBeforeLast('/', "")
        val parentPath = if (parent.isEmpty()) "/" else parent
        navigateTo(parentPath)
        return parentPath
    }

    /** 上传单个文件或文件夹 */
    fun uploadFile(relativePath: String) {
        // 并发保护：检查是否有文件正在上传
        val uploading = syncDb.getEntriesByStatus("local_entries", SyncStatus.UPLOADING)
        if (uploading.isNotEmpty()) {
            android.widget.Toast.makeText(context, "当前有文件正在上传，请等待完成", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val localFile = File(vaultDir, relativePath.trimStart('/'))
        if (!localFile.exists()) {
            android.widget.Toast.makeText(context, "本地文件不存在", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // 文件夹：递归扫描内部文件，逐个上传
        if (localFile.isDirectory) {
            uploadFolder(relativePath)
            return
        }

        // 文件：检查是否已 COMPLETED 且未修改
        val existingEntry = syncDb.getEntry("local_entries", relativePath)
        if (existingEntry != null && existingEntry.status == SyncStatus.COMPLETED) {
            val currentLastModified = Instant.ofEpochMilli(localFile.lastModified()).toString()
            if (existingEntry.lastModified == currentLastModified) {
                return
            }
        }

        // 录入本地表（如果是新文件）
        if (existingEntry == null) {
            syncDb.upsertEntry("local_entries", SyncEntryRow(
                path = relativePath,
                size = localFile.length(),
                lastModified = Instant.ofEpochMilli(localFile.lastModified()).toString(),
                md5 = null,
                cloudHash = null,
                status = SyncStatus.PENDING,
                lastSyncTime = null,
                failReason = null
            ))
        }

        // 标记为 QUEUED
        syncDb.updateStatus("local_entries", relativePath, SyncStatus.QUEUED)
        updateSingleEntry(relativePath)

        // 启动上传
        syncJob?.cancel()
        state.syncDialogVisible = true
        syncJob = scope.launch {
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val logFileName = "${vaultName}_upload_${timestamp}.log"
            val internalLogDir = com.whmdg.mczj.tools.AppDataPaths.cloudSync(context)
            val internalLogFile = File(internalLogDir, logFileName)
            val externalLogDir = context.getExternalFilesDir(null)?.let { File(it, "Android_tools/云盘") }
            val externalLogFile = externalLogDir?.let { File(it, logFileName) }
            val engine = SyncEngine(
                webdavClient = webdavClient,
                vaultDir = vaultDir,
                onProgress = { _ -> },
                onFileComplete = { _, _ -> },
                logFiles = listOfNotNull(internalLogFile, externalLogFile)
            )
            engine.uploadSingleFile(
                relativePath = relativePath,
                remoteBasePath = remoteBasePath,
                syncDb = syncDb,
                onProgress = { uploadedBytes, totalBytes ->
                    // 更新内存中的文件进度，供 updateSingleEntry 读取
                    val currentProgress = state.syncTask.fileProgress.toMutableMap()
                    currentProgress[relativePath] = SyncFileProgress(
                        relativePath = relativePath,
                        totalBytes = totalBytes,
                        uploadedBytes = uploadedBytes,
                        status = UploadStatus.UPLOADING
                    )
                    state.syncTask = state.syncTask.copy(fileProgress = currentProgress)
                    updateSingleEntry(relativePath)
                },
                onComplete = { success, error ->
                    scope.launch {
                        // 清理内存进度
                        val currentProgress = state.syncTask.fileProgress.toMutableMap()
                        currentProgress.remove(relativePath)
                        state.syncTask = state.syncTask.copy(
                            phase = SyncPhase.COMPLETED,
                            fileProgress = currentProgress,
                            completedFiles = if (success) 1 else 0
                        )
                        if (!success && error != null) {
                            android.widget.Toast.makeText(context, "上传失败: $error，请查看日志", android.widget.Toast.LENGTH_LONG).show()
                        }
                        updateSingleEntry(relativePath)
                        kotlinx.coroutines.delay(1500)
                        state.syncDialogVisible = false
                    }
                },
                onStatusChange = {
                    scope.launch { updateSingleEntry(relativePath) }
                }
            )
        }
    }

    /** 上传文件夹：扫描分类 → 用户决策（跳过/重传已完成） → 逐个上传 */
    private fun uploadFolder(folderRelativePath: String) {
        scope.launch {
            val folder = File(vaultDir, folderRelativePath.trimStart('/'))
            if (!folder.exists() || !folder.isDirectory) return@launch

            // ① 递归扫描所有文件
            val allFiles = withContext(Dispatchers.IO) {
                folder.walkTopDown()
                    .filter { it.isFile && it.name !in excludedFiles }
                    .toList()
            }

            if (allFiles.isEmpty()) {
                android.widget.Toast.makeText(context, "文件夹为空", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }

            // ② 分类：查询每个文件的 DB 状态
            data class ClassifiedFile(val file: File, val relPath: String, val dbStatus: SyncStatus?)
            val classified = withContext(Dispatchers.IO) {
                allFiles.map { file ->
                    val relPath = "/" + file.relativeTo(File(vaultDir)).path.replace('\\', '/')
                    val dbEntry = syncDb.getEntry("local_entries", relPath)
                    ClassifiedFile(file, relPath, dbEntry?.status)
                }
            }

            // ③ 检查是否有正在上传的文件
            if (classified.any { it.dbStatus == SyncStatus.UPLOADING }) {
                android.widget.Toast.makeText(context, "当前有文件正在上传，请等待完成", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }

            // ④ 分桶
            val completedFiles = classified.filter { it.dbStatus == SyncStatus.COMPLETED }
            val toUpload = classified.filter {
                it.dbStatus == null || it.dbStatus == SyncStatus.PENDING ||
                it.dbStatus == SyncStatus.QUEUED || it.dbStatus == SyncStatus.PAUSED
            }

            // ⑤ 若有已完成文件，询问用户
            val reUploadAll = if (completedFiles.isNotEmpty()) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    state.uploadConfirmDialog = UploadConfirmState(
                        completedCount = completedFiles.size,
                        totalCount = classified.size,
                        onComplete = { reUpload -> cont.resume(reUpload) {} }
                    )
                }
            } else false

            // ⑥ 构建最终队列
            val queue: List<Pair<File, String>> = if (reUploadAll) {
                // 全部重新上传：已完成文件重置为 QUEUED
                withContext(Dispatchers.IO) {
                    for (cf in completedFiles) {
                        syncDb.updateStatus("local_entries", cf.relPath, SyncStatus.QUEUED)
                        syncDb.updateUploadedSize("local_entries", cf.relPath, 0)
                    }
                }
                classified.map { it.file to it.relPath }
            } else {
                // 跳过已完成：仅上传未完成文件
                toUpload.map { it.file to it.relPath }
            }

            // 将队列中未录入 DB 的文件写入
            withContext(Dispatchers.IO) {
                for ((file, relPath) in queue) {
                    val existing = syncDb.getEntry("local_entries", relPath)
                    if (existing == null) {
                        syncDb.upsertEntry("local_entries", SyncEntryRow(
                            path = relPath,
                            size = file.length(),
                            lastModified = Instant.ofEpochMilli(file.lastModified()).toString(),
                            md5 = null,
                            cloudHash = null,
                            status = SyncStatus.QUEUED,
                            lastSyncTime = null,
                            failReason = null
                        ))
                    } else if (existing.status != SyncStatus.QUEUED) {
                        syncDb.updateStatus("local_entries", relPath, SyncStatus.QUEUED)
                    }
                }
            }

            // ⑦ 初始加载（仅一次）
            navigateTo(state.currentPath)

            if (queue.isEmpty()) {
                android.widget.Toast.makeText(context, "所有文件已上传完成", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }

            android.widget.Toast.makeText(context, "开始上传 ${queue.size} 个文件", android.widget.Toast.LENGTH_SHORT).show()

            // ⑧ 创建日志文件 + SyncEngine
            val logDir = com.whmdg.mczj.tools.AppDataPaths.cloudSync(context)
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val logFileName = "${vaultName}_batch_${timestamp}.log"
            val internalLogFile = File(logDir, logFileName)
            val externalLogDir = context.getExternalFilesDir(null)?.let { File(it, "Android_tools/云盘") }
            val externalLogFile = externalLogDir?.let { File(it, logFileName) }

            val engine = SyncEngine(
                webdavClient = webdavClient,
                vaultDir = vaultDir,
                onProgress = { _ -> },
                onFileComplete = { _, _ -> },
                logFiles = listOfNotNull(internalLogFile, externalLogFile)
            )

            // ⑨ 显示同步弹窗 + 初始化状态栏
            state.syncDialogVisible = true
            state.syncTask = SyncTaskState(
                phase = SyncPhase.SYNCING,
                totalFiles = queue.size,
                totalBytes = queue.sumOf { it.first.length() }
            )

            // ⑩ 逐个上传
            var successCount = 0
            var failCount = 0
            var completedBytes = 0L

            for ((index, pair) in queue.withIndex()) {
                val (file, relPath) = pair
                val fileSize = file.length()
                com.whmdg.mczj.tools.fileop.sync.CloudSyncLogger.logSync("CloudPane", "开始上传 [${index + 1}/${queue.size}]: $relPath")

                engine.uploadSingleFile(
                    relativePath = relPath,
                    remoteBasePath = remoteBasePath,
                    syncDb = syncDb,
                    onProgress = { uploadedBytes, totalBytes ->
                        val currentProgress = state.syncTask.fileProgress.toMutableMap()
                        currentProgress[relPath] = SyncFileProgress(
                            relativePath = relPath,
                            totalBytes = totalBytes,
                            uploadedBytes = uploadedBytes,
                            status = UploadStatus.UPLOADING
                        )
                        state.syncTask = state.syncTask.copy(
                            fileProgress = currentProgress,
                            completedFiles = successCount,
                            transferredBytes = completedBytes + uploadedBytes
                        )
                        updateSingleEntry(relPath)
                    },
                    onComplete = { success, error ->
                        if (success) {
                            successCount++
                            completedBytes += fileSize
                        } else {
                            failCount++
                        }
                        // 清理该文件的内存进度
                        val currentProgress = state.syncTask.fileProgress.toMutableMap()
                        currentProgress.remove(relPath)
                        state.syncTask = state.syncTask.copy(
                            fileProgress = currentProgress,
                            completedFiles = successCount,
                            transferredBytes = completedBytes
                        )
                        if (!success && error != null) {
                            com.whmdg.mczj.tools.fileop.sync.CloudSyncLogger.logSync("CloudPane", "上传失败 [${index + 1}/${queue.size}]: $relPath - $error")
                        }
                        updateSingleEntry(relPath)
                    },
                    onStatusChange = {
                        scope.launch { updateSingleEntry(relPath) }
                    }
                )
            }

            // ⑪ 最终状态
            state.syncTask = state.syncTask.copy(phase = SyncPhase.COMPLETED, completedFiles = successCount)
            val msg = "文件夹上传完成: 成功${successCount}个" + if (failCount > 0) "，失败${failCount}个" else ""
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            kotlinx.coroutines.delay(1500)
            state.syncDialogVisible = false
        }
    }

    /** 删除本地文件 + 从本地表移除 */
    fun deleteLocal(relativePath: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val localFile = File(vaultDir, relativePath.trimStart('/'))
                if (localFile.exists()) {
                    if (localFile.isDirectory) {
                        localFile.deleteRecursively()
                    } else {
                        localFile.delete()
                    }
                }
                // 从本地表移除
                syncDb.deleteEntry("local_entries", relativePath)
                // 如果是目录，也移除子条目
                syncDb.deleteEntriesByPrefix("local_entries", relativePath)
            }
            navigateTo(state.currentPath)
        }
    }

    /** 删除云端文件 + 从云端表移除 */
    fun deleteCloud(relativePath: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val remotePath = "$remoteBasePath/${relativePath.trimStart('/')}"
                try {
                    webdavClient.delete(remotePath)
                } catch (_: Exception) {}
                // 从云端表移除
                syncDb.deleteEntry("cloud_entries", relativePath)
                syncDb.deleteEntriesByPrefix("cloud_entries", relativePath)
                // 本地状态重置为 PENDING
                syncDb.updateStatus("local_entries", relativePath, SyncStatus.PENDING)
            }
            navigateTo(state.currentPath)
        }
    }

    /** 同时删除本地和云端 */
    fun deleteBoth(relativePath: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                // 删除本地文件
                val localFile = File(vaultDir, relativePath.trimStart('/'))
                if (localFile.exists()) {
                    if (localFile.isDirectory) {
                        localFile.deleteRecursively()
                    } else {
                        localFile.delete()
                    }
                }
                // 删除云端文件
                val remotePath = "$remoteBasePath/${relativePath.trimStart('/')}"
                try {
                    webdavClient.delete(remotePath)
                } catch (_: Exception) {}
                // 从两张表移除
                syncDb.deleteEntry("local_entries", relativePath)
                syncDb.deleteEntriesByPrefix("local_entries", relativePath)
                syncDb.deleteEntry("cloud_entries", relativePath)
                syncDb.deleteEntriesByPrefix("cloud_entries", relativePath)
            }
            navigateTo(state.currentPath)
        }
    }

    /** 启动批量同步（保留旧接口，暂未使用） */
    fun startSync(mode: SyncMode) {
        syncJob?.cancel()
        syncJob = scope.launch {
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val logFileName = "${vaultName}_batch_${timestamp}.log"
            val internalLogDir = com.whmdg.mczj.tools.AppDataPaths.cloudSync(context)
            val internalLogFile = File(internalLogDir, logFileName)
            val externalLogDir = context.getExternalFilesDir(null)?.let { File(it, "Android_tools/云盘") }
            val externalLogFile = externalLogDir?.let { File(it, logFileName) }
            val engine = SyncEngine(
                webdavClient = webdavClient,
                vaultDir = vaultDir,
                onProgress = { taskState ->
                    state.syncTask = taskState
                },
                onFileComplete = { relativePath, success ->
                    if (success) navigateTo(state.currentPath)
                },
                logFiles = listOfNotNull(internalLogFile, externalLogFile)
            )
            try {
                engine.startSync(
                    mode = mode,
                    remoteBasePath = remoteBasePath,
                    index = VaultSyncIndex()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    fun pauseSync() {
        syncJob?.cancel()
        state.syncTask = state.syncTask.copy(phase = SyncPhase.IDLE)
    }

    fun getSyncState(path: String): SyncFileProgress? {
        return state.syncTask.fileProgress[path]
    }

    fun refresh() {
        syncLocalFiles()
        navigateTo(state.currentPath)
    }

    fun dispose() {
        syncJob?.cancel()
    }

    // ── 内部方法 ──

    /**
     * 同步本地文件到数据库。
     * - 本地有、表中无 → 新增（PENDING）
     * - 本地无、表中有 → 移除
     * - 本地有、表中有 → 检查 lastModified 变化则重置为 PENDING
     */
    private fun syncLocalFiles() {
        val dir = File(vaultDir)
        if (!dir.exists()) return

        val dbPaths = syncDb.getAllEntries("local_entries").map { it.path }.toSet()
        val localPaths = mutableSetOf<String>()

        dir.walkTopDown().forEach { file ->
            if (file.name in excludedFiles) return@forEach
            val relativePath = "/" + file.relativeTo(dir).path.replace('\\', '/')
            localPaths.add(relativePath)

            if (file.isFile) {
                val existing = syncDb.getEntry("local_entries", relativePath)
                val currentLastModified = Instant.ofEpochMilli(file.lastModified()).toString()
                val currentSize = file.length()

                if (existing == null) {
                    // 新文件 → 录入
                    syncDb.upsertEntry("local_entries", SyncEntryRow(
                        path = relativePath,
                        size = currentSize,
                        lastModified = currentLastModified,
                        md5 = null,
                        cloudHash = null,
                        status = SyncStatus.PENDING,
                        lastSyncTime = null,
                        failReason = null
                    ))
                } else if (existing.lastModified != currentLastModified || existing.size != currentSize) {
                    // 文件被修改 → 重置为 PENDING
                    syncDb.updateSize("local_entries", relativePath, currentSize, currentLastModified)
                    syncDb.updateStatus("local_entries", relativePath, SyncStatus.PENDING)
                }
            }
        }

        // 移除本地已不存在的条目
        for (dbPath in dbPaths) {
            if (dbPath !in localPaths) {
                syncDb.deleteEntry("local_entries", dbPath)
            }
        }
    }

    /**
     * 列出本地保险箱目录，自底向上聚合文件夹同步状态。
     * 返回的列表已排序：文件夹在前，文件在后。
     */
    private fun listLocalFiles(relativePath: String): List<CloudFileEntry> {
        val dir = File(vaultDir, relativePath.trimStart('/'))
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val children = dir.listFiles() ?: return emptyList()
        val entries = mutableListOf<CloudFileEntry>()

        for (file in children) {
            if (file.name in excludedFiles) continue
            val childRelativePath = if (relativePath == "/") "/${file.name}" else "$relativePath/${file.name}"

            if (file.isDirectory) {
                // 递归聚合文件夹状态
                val agg = aggregateFolder(childRelativePath)
                entries.add(CloudFileEntry(
                    name = file.name,
                    relativePath = childRelativePath,
                    isDirectory = true,
                    totalSize = agg.totalSize,
                    uploadedSize = agg.uploadedSize,
                    uploadingSize = agg.uploadingSize,
                    lastModified = file.lastModified()
                ))
            } else {
                // 文件：从 DB 查同步状态，优先用内存实时进度，回退到 DB 持久化进度
                val dbEntry = syncDb.getEntry("local_entries", childRelativePath)
                val status = dbEntry?.status ?: SyncStatus.PENDING
                val fileSize = file.length()
                val liveProgress = state.syncTask.fileProgress[childRelativePath]
                val dbUploaded = dbEntry?.uploadedSize ?: 0L
                val greenSize = when {
                    status == SyncStatus.COMPLETED -> fileSize
                    liveProgress != null -> liveProgress.uploadedBytes
                    dbUploaded > 0 -> dbUploaded
                    else -> 0L
                }
                val yellowSize = when {
                    status == SyncStatus.COMPLETED -> 0L
                    liveProgress != null -> fileSize - liveProgress.uploadedBytes
                    dbUploaded > 0 -> fileSize - dbUploaded
                    status == SyncStatus.UPLOADING -> fileSize
                    else -> 0L
                }
                entries.add(CloudFileEntry(
                    name = file.name,
                    relativePath = childRelativePath,
                    isDirectory = false,
                    totalSize = fileSize,
                    uploadedSize = greenSize,
                    uploadingSize = yellowSize,
                    lastModified = file.lastModified(),
                    syncStatus = status
                ))
            }
        }

        return entries.sortedWith(compareBy<CloudFileEntry> { !it.isDirectory }.thenBy { it.name })
    }

    /** 递归聚合文件夹下所有文件的同步状态 */
    private fun aggregateFolder(relativePath: String): FolderAggregate {
        val dir = File(vaultDir, relativePath.trimStart('/'))
        if (!dir.exists() || !dir.isDirectory) return FolderAggregate()

        val children = dir.listFiles() ?: return FolderAggregate()
        var totalSize = 0L
        var uploadedSize = 0L
        var uploadingSize = 0L

        for (file in children) {
            if (file.name in excludedFiles) continue

            if (file.isDirectory) {
                val childPath = if (relativePath == "/") "/${file.name}" else "$relativePath/${file.name}"
                val childAgg = aggregateFolder(childPath)
                totalSize += childAgg.totalSize
                uploadedSize += childAgg.uploadedSize
                uploadingSize += childAgg.uploadingSize
            } else {
                val fileSize = file.length()
                totalSize += fileSize
                val childPath = if (relativePath == "/") "/${file.name}" else "$relativePath/${file.name}"
                val dbEntry = syncDb.getEntry("local_entries", childPath)
                val liveProgress = state.syncTask.fileProgress[childPath]
                val dbUploaded = dbEntry?.uploadedSize ?: 0L
                when (dbEntry?.status) {
                    SyncStatus.COMPLETED -> uploadedSize += fileSize
                    SyncStatus.UPLOADING -> {
                        val done = liveProgress?.uploadedBytes ?: dbUploaded
                        uploadedSize += done
                        uploadingSize += (fileSize - done)
                    }
                    else -> {} // PENDING / QUEUED / PAUSED → 不计入
                }
            }
        }

        return FolderAggregate(totalSize, uploadedSize, uploadingSize)
    }

    /** 就地更新单个条目（不重建整个列表，不显示 loading） */
    private fun updateSingleEntry(relativePath: String) {
        val entries = state.entries.toMutableList()
        val idx = entries.indexOfFirst { it.relativePath == relativePath }
        if (idx < 0) return

        val old = entries[idx]
        val newEntry = if (old.isDirectory) {
            val agg = aggregateFolder(relativePath)
            old.copy(totalSize = agg.totalSize, uploadedSize = agg.uploadedSize, uploadingSize = agg.uploadingSize)
        } else {
            val dbEntry = syncDb.getEntry("local_entries", relativePath)
            val liveProgress = state.syncTask.fileProgress[relativePath]
            val fileSize = old.totalSize
            val greenSize = when {
                dbEntry?.status == SyncStatus.COMPLETED -> fileSize
                liveProgress != null -> liveProgress.uploadedBytes
                (dbEntry?.uploadedSize ?: 0L) > 0 -> dbEntry!!.uploadedSize
                else -> 0L
            }
            val yellowSize = when {
                dbEntry?.status == SyncStatus.COMPLETED -> 0L
                liveProgress != null -> fileSize - liveProgress.uploadedBytes
                else -> 0L
            }
            old.copy(
                uploadedSize = greenSize,
                uploadingSize = yellowSize,
                syncStatus = dbEntry?.status ?: old.syncStatus
            )
        }
        entries[idx] = newEntry
        refreshParentAggregates(entries, relativePath)
        state.entries = entries
    }

    /** 刷新父文件夹聚合进度（向上冒泡到 currentPath 的直接子文件夹） */
    private fun refreshParentAggregates(entries: MutableList<CloudFileEntry>, changedPath: String) {
        var parent = changedPath.substringBeforeLast('/', "/")
        while (parent != "/" && parent != state.currentPath) {
            val idx = entries.indexOfFirst { it.relativePath == parent && it.isDirectory }
            if (idx >= 0) {
                val agg = aggregateFolder(parent)
                entries[idx] = entries[idx].copy(
                    totalSize = agg.totalSize,
                    uploadedSize = agg.uploadedSize,
                    uploadingSize = agg.uploadingSize
                )
            }
            parent = parent.substringBeforeLast('/', "/")
        }
    }

    private data class FolderAggregate(
        val totalSize: Long = 0,
        val uploadedSize: Long = 0,
        val uploadingSize: Long = 0
    )
}
