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
        /** 取消上传回调（由弹窗 ✕ 按钮调用） */
        var onCancelUpload: (() -> Unit)? = null
        /** 是否已完成首次初始化扫描 */
        var isInitialized by mutableStateOf(false)
        /** 已删除文件确认对话框 */
        var deletedFilesDialog by mutableStateOf<DeletedFilesState?>(null)
        /** 进度异常弹窗（为 null 时隐藏） */
        var anomalyDialogMessage by mutableStateOf<String?>(null)
    }

    data class DeletedFilesState(
        val deletedPaths: List<String>,
        val onConfirm: (deleteFromCloud: Boolean) -> Unit
    )

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

    /** 初始化：打开 DB + 注册日志写入器 + 首次扫描 + 列出根目录 */
    fun init() {
        syncDb = SyncDatabase.getInstance(context, vaultName)
        state.vaultFolderName = vaultName
        // 中断恢复：WebDAV 不支持断点续传，所有 UPLOADING 重置为 PENDING
        syncDb.resetUploadingToPending("local_entries")
        // 注册云盘日志写入器
        com.whmdg.mczj.tools.fileop.sync.CloudSyncLogger.externalWriter = { tag, message ->
            com.whmdg.mczj.tools.fileop.sync.CloudSyncLogger.log(context, tag, message)
        }
        // 仅首次初始化时全量扫描
        if (!state.isInitialized) {
            syncLocalFiles()
            state.isInitialized = true
        }
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

    /** 静默刷新当前目录（不设 isLoading，不闪 loading spinner） */
    private suspend fun silentRefresh() {
        try {
            val entries = withContext(Dispatchers.IO) {
                listLocalFiles(state.currentPath)
            }
            state.entries = entries
        } catch (_: Exception) {}
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

        // 创建上传锁
        val lockFile = com.whmdg.mczj.tools.AppDataPaths.syncLock(context, vaultId)
        lockFile.writeText("""{"vaultId":$vaultId,"vaultName":"$vaultName","startTime":"${java.time.LocalDateTime.now()}","status":"uploading"}""")

        // 启动上传
        syncJob?.cancel()
        state.onCancelUpload = ::cancelUpload
        state.syncTask = SyncTaskState(phase = SyncPhase.SYNCING, totalFiles = 1, totalBytes = localFile.length())
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
            // UI 节流：最多每 100ms 更新一次 state（避免 Compose recomposition 过载）
            var lastUiUpdateTime = 0L
            val localFileProgress = java.util.concurrent.ConcurrentHashMap<String, SyncFileProgress>()
            // 进度异常检测器
            val anomalyThreshold = 128 * 1024L  // 128KB
            var anomalyCount = 0
            var lastUiTransferredBytes = 0L
            val anomalyLogFile = File(com.whmdg.mczj.tools.AppDataPaths.diagnostics(context), "progress_anomaly_${vaultName}_${timestamp}.log")
            engine.uploadSingleFile(
                relativePath = relativePath,
                remoteBasePath = remoteBasePath,
                syncDb = syncDb,
                onProgress = { uploadedBytes, totalBytes ->
                    // 始终记录到本地 map（供 updateSingleEntry 读取）
                    localFileProgress[relativePath] = SyncFileProgress(
                        relativePath = relativePath,
                        totalBytes = totalBytes,
                        uploadedBytes = uploadedBytes,
                        status = UploadStatus.UPLOADING
                    )
                    // 按时间节流：每 100ms 才触发一次 UI 更新
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdateTime >= 100) {
                        lastUiUpdateTime = now
                        val currentProgress = state.syncTask.fileProgress.toMutableMap()
                        currentProgress[relativePath] = localFileProgress[relativePath]!!
                        state.syncTask = state.syncTask.copy(
                            fileProgress = currentProgress,
                            transferredBytes = uploadedBytes
                        )
                        updateSingleEntry(relativePath)
                        // 进度异常检测：UI 增量 > 128KB
                        val uiDelta = uploadedBytes - lastUiTransferredBytes
                        if (uiDelta > anomalyThreshold && lastUiTransferredBytes > 0) {
                            anomalyCount++
                            val deltaKB = uiDelta / 1024
                            val deltaStr = if (deltaKB >= 1024) "${String.format("%.1f", uiDelta / 1048576.0)}MB" else "${deltaKB}KB"
                            android.widget.Toast.makeText(context, "检测到第${anomalyCount}次数据异常，数据异常为增加了$deltaStr", android.widget.Toast.LENGTH_LONG).show()
                            try {
                                anomalyLogFile.appendText(buildString {
                                    appendLine("=== 第${anomalyCount}次进度异常 ===")
                                    appendLine("时间: ${java.time.LocalDateTime.now()}")
                                    appendLine("文件: $relativePath")
                                    appendLine("UI增量: ${uiDelta} bytes ($deltaStr)")
                                    appendLine("上次UI transferredBytes: $lastUiTransferredBytes")
                                    appendLine("本次UI transferredBytes: $uploadedBytes")
                                    appendLine("totalBytes: $totalBytes")
                                    appendLine("anomalyThreshold: $anomalyThreshold")
                                    appendLine()
                                })
                            } catch (_: Exception) {}
                            if (anomalyCount >= 5) {
                                android.widget.Toast.makeText(context, "检测到本次上传异常，已自动终止，为了保护数据安全", android.widget.Toast.LENGTH_LONG).show()
                                state.anomalyDialogMessage = "检测到本次上传进度异常（累计${anomalyCount}次增量超限），已自动终止上传以保护数据安全。已上传的文件不受影响，未上传的文件已重置为待上传状态。"
                                syncJob?.cancel()
                                return@uploadFile
                            }
                        }
                        lastUiTransferredBytes = uploadedBytes
                    }
                },
                onComplete = { success, error ->
                    scope.launch {
                        // 清理内存进度
                        val currentProgress = state.syncTask.fileProgress.toMutableMap()
                        currentProgress.remove(relativePath)
                        state.syncTask = state.syncTask.copy(
                            fileProgress = currentProgress,
                            completedFiles = if (success) 1 else 0
                        )
                        if (!success && error != null) {
                            android.widget.Toast.makeText(context, "上传失败: $error，请查看日志", android.widget.Toast.LENGTH_LONG).show()
                        }
                        updateSingleEntry(relativePath)

                        // 上传 cloud.db + 删除 lock
                        state.syncTask = state.syncTask.copy(phase = SyncPhase.SYNCING, currentFileName = "正在同步云端列表...")
                        val dbUploaded = uploadCloudDb()
                        if (dbUploaded) {
                            com.whmdg.mczj.tools.AppDataPaths.syncLock(context, vaultId).delete()
                        }

                        state.syncTask = state.syncTask.copy(phase = SyncPhase.COMPLETED)
                        kotlinx.coroutines.delay(1500)
                        state.syncDialogVisible = false
                    }
                },
                onStatusChange = {
                    updateSingleEntry(relativePath)
                }
            )
        }
    }

    /** 上传文件夹：对比本地文件与 DB → 用户决策 → 并发上传 */
    private fun uploadFolder(folderRelativePath: String) {
        // 冗余措施：先终止旧上传协程（如果还在运行）
        val oldJob = syncJob
        syncJob = null

        syncJob = scope.launch(Dispatchers.Default) {
            // 等待旧协程真正终止
            if (oldJob != null && oldJob.isActive) {
                oldJob.cancel()
                oldJob.join()
                // 清理旧任务残留的 DB 状态
                withContext(Dispatchers.IO) {
                    val entries = syncDb.getEntriesByStatus("local_entries", SyncStatus.QUEUED) +
                        syncDb.getEntriesByStatus("local_entries", SyncStatus.UPLOADING)
                    for (entry in entries) {
                        syncDb.updateStatus("local_entries", entry.path, SyncStatus.PENDING)
                        syncDb.updateUploadedSize("local_entries", entry.path, 0)
                    }
                }
            }

            val folder = File(vaultDir, folderRelativePath.trimStart('/'))
            if (!folder.exists() || !folder.isDirectory) return@launch

            // 创建上传锁
            val lockFile = com.whmdg.mczj.tools.AppDataPaths.syncLock(context, vaultId)
            lockFile.writeText("""{"vaultId":$vaultId,"vaultName":"$vaultName","startTime":"${java.time.LocalDateTime.now()}","status":"uploading"}""")

            // 显示弹窗（扫描阶段：不定进度条）
            state.onCancelUpload = ::cancelUpload
            state.syncTask = SyncTaskState(phase = SyncPhase.SCANNING)
            state.syncDialogVisible = true

            // ① 获取本地文件列表（磁盘）
            val localFiles = withContext(Dispatchers.IO) {
                folder.walkTopDown()
                    .filter { it.isFile && it.name !in excludedFiles }
                    .sortedWith(naturalOrderComparator(vaultDir))
                    .toList()
            }

            if (localFiles.isEmpty()) {
                withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, "文件夹为空", android.widget.Toast.LENGTH_SHORT).show() }
                state.syncDialogVisible = false
                return@launch
            }

            // ② 本地文件树
            val localPathSet = localFiles.map {
                "/" + it.relativeTo(File(vaultDir)).path.replace('\\', '/')
            }.toSet()

            // ③ DB 中该文件夹下已上传（COMPLETED）的文件树
            val prefix = if (folderRelativePath.endsWith("/")) folderRelativePath else "$folderRelativePath/"
            val completedDbEntries = withContext(Dispatchers.IO) {
                syncDb.getEntriesByStatus("local_entries", SyncStatus.COMPLETED)
                    .filter { it.path.startsWith(prefix) || it.path == folderRelativePath }
            }

            // ④ 前置校验：本地与 DB 已上传文件的大小/时间戳一致性检查
            val localFileMap = localFiles.associateBy {
                "/" + it.relativeTo(File(vaultDir)).path.replace('\\', '/')
            }
            val validCompletedPaths = mutableSetOf<String>()
            withContext(Dispatchers.IO) {
                for (dbEntry in completedDbEntries) {
                    val localFile = localFileMap[dbEntry.path]
                    if (localFile != null) {
                        val currentSize = localFile.length()
                        val currentLastModified = Instant.ofEpochMilli(localFile.lastModified()).toString()
                        if (dbEntry.size == currentSize && dbEntry.lastModified == currentLastModified) {
                            validCompletedPaths.add(dbEntry.path)
                        } else {
                            // 大小或时间戳不一致 → 重置为 PENDING
                            syncDb.updateStatus("local_entries", dbEntry.path, SyncStatus.PENDING)
                        }
                    }
                    // 本地不存在的不在这里处理，后面删除检测会处理
                }
            }

            // ⑤ 对比：双方都有=跳过，本地有DB无=需上传，DB有本地无=已删除
            val completedFiles = mutableListOf<Pair<File, String>>()
            val toUpload = mutableListOf<Pair<File, String>>()
            val deletedPaths = mutableListOf<String>()

            for (file in localFiles) {
                val relPath = "/" + file.relativeTo(File(vaultDir)).path.replace('\\', '/')
                if (relPath in validCompletedPaths) {
                    completedFiles.add(file to relPath)
                } else {
                    toUpload.add(file to relPath)
                }
            }

            for (dbEntry in completedDbEntries) {
                if (dbEntry.path !in localPathSet) {
                    deletedPaths.add(dbEntry.path)
                }
            }

            // ⑥ 若有已删除文件，询问用户
            if (deletedPaths.isNotEmpty()) {
                val deleteFromCloud = suspendCancellableCoroutine<Boolean> { cont ->
                    state.deletedFilesDialog = DeletedFilesState(
                        deletedPaths = deletedPaths,
                        onConfirm = { deleteFromCloud -> cont.resume(deleteFromCloud) {} }
                    )
                }
                if (deleteFromCloud) {
                    withContext(Dispatchers.IO) {
                        for (path in deletedPaths) {
                            val remotePath = "$remoteBasePath/${path.trimStart('/')}"
                            try { webdavClient.delete(remotePath) } catch (_: Exception) {}
                            syncDb.deleteEntry("local_entries", path)
                            syncDb.deleteEntry("cloud_entries", path)
                        }
                    }
                }
                // 选择忽略：保持 DB 不动
            }

            // ⑦ 检查是否有正在上传的文件
            if (withContext(Dispatchers.IO) {
                    syncDb.getEntriesByStatus("local_entries", SyncStatus.UPLOADING).isNotEmpty()
                }) {
                withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, "当前有文件正在上传，请等待完成", android.widget.Toast.LENGTH_SHORT).show() }
                state.syncDialogVisible = false
                return@launch
            }

            // ⑧ 询问用户：跳过已完成 or 全部重传
            val reUploadAll = if (completedFiles.isNotEmpty()) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    state.uploadConfirmDialog = UploadConfirmState(
                        completedCount = completedFiles.size,
                        totalCount = localFiles.size,
                        onComplete = { reUpload -> cont.resume(reUpload) {} }
                    )
                }
            } else false

            // ⑨ 构建最终队列
            val queue: List<Pair<File, String>> = if (reUploadAll) {
                withContext(Dispatchers.IO) {
                    for ((file, relPath) in completedFiles) {
                        syncDb.updateStatus("local_entries", relPath, SyncStatus.QUEUED)
                        syncDb.updateUploadedSize("local_entries", relPath, 0)
                    }
                }
                completedFiles + toUpload
            } else {
                toUpload
            }

            // ⑩ 将队列中未录入 DB 的文件写入
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

            if (queue.isEmpty()) {
                // 完全关闭弹窗（与上传完成同样的关闭方式）
                state.syncTask = SyncTaskState(phase = SyncPhase.COMPLETED)
                silentRefresh()
                withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, "所有文件已上传完成", android.widget.Toast.LENGTH_SHORT).show() }
                kotlinx.coroutines.delay(1500)
                state.syncDialogVisible = false
                return@launch
            }

            // ⑪ 静默刷新当前目录（不闪 loading）
            silentRefresh()

            withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, "开始上传 ${queue.size} 个文件", android.widget.Toast.LENGTH_SHORT).show() }

            // ⑫ 预计算文件夹聚合值（避免上传过程中 O(n²) 全量遍历）
            val folderTotalSize = mutableMapOf<String, Long>()
            for ((file, relPath) in queue) {
                val fileSize = file.length()
                var parent = relPath.substringBeforeLast('/', "/")
                while (parent.isNotEmpty()) {
                    folderTotalSize[parent] = (folderTotalSize[parent] ?: 0L) + fileSize
                    val next = parent.substringBeforeLast('/', "")
                    if (next == parent) break
                    parent = next
                }
            }
            // 将预计算的 totalSize 写入当前视图中的文件夹条目
            withContext(Dispatchers.Main) {
                val entries = state.entries.toMutableList()
                var changed = false
                for ((folderPath, totalSize) in folderTotalSize) {
                    val idx = entries.indexOfFirst { it.relativePath == folderPath && it.isDirectory }
                    if (idx >= 0) {
                        entries[idx] = entries[idx].copy(totalSize = totalSize)
                        changed = true
                    }
                }
                if (changed) state.entries = entries
            }

            // ⑬ 创建日志文件 + SyncEngine
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

            // ⑬ 显示同步弹窗 + 初始化状态栏
            val maxConcurrency = context.getSharedPreferences("cloud_sync_settings", Context.MODE_PRIVATE)
                .getInt("max_concurrency", 3)
            state.onCancelUpload = ::cancelUpload
            state.syncDialogVisible = true
            state.syncTask = SyncTaskState(
                phase = SyncPhase.SYNCING,
                totalFiles = queue.size,
                totalBytes = queue.sumOf { it.first.length() },
                concurrency = maxConcurrency
            )

            // ⑭ 并发动态上传（Channel 单写者模式，避免多线程竞态）
            val completedBytes = java.util.concurrent.atomic.AtomicLong(0)
            val activeFileBytes = java.util.concurrent.ConcurrentHashMap<String, Long>()
            val fileSizes = queue.associate { (file, path) -> path to file.length() }
            var activeWorkers = 0
            var queueIndex = 0
            var completedFilesCount = 0  // 仅更新器协程访问
            var successCount = 0         // 仅更新器协程访问
            var failCount = 0            // 仅更新器协程访问

            // 事件 Channel + 更新器协程（单线程顺序处理所有状态更新）
            val eventChannel = kotlinx.coroutines.channels.Channel<UploadEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)

            val updaterJob = launch {
                // 速度计算：每秒采样一次吞吐量
                var speedLastBytes = 0L
                var speedLastTime = System.currentTimeMillis()
                var currentSpeed = 0L
                // 进度回退检测：记录上次 transferredBytes
                var lastTransferredBytes = 0L
                // UI 节流：最多每 100ms 更新一次 state（避免 Compose recomposition 过载）
                var lastUiUpdateTime = 0L
                // 累积 delta（节流期间合并多个 Progress 事件的增量）
                val pendingDeltas = java.util.concurrent.ConcurrentHashMap<String, Long>()
                // 进度异常检测器
                val anomalyThreshold = 128 * 1024L  // 128KB
                var anomalyCount = 0
                var lastUiTransferredBytes = 0L
                val anomalyLogFile = File(com.whmdg.mczj.tools.AppDataPaths.diagnostics(context), "progress_anomaly_${vaultName}_${timestamp}.log")

                for (event in eventChannel) {
                    if (!isActive) break
                    try {
                        when (event) {
                            is UploadEvent.Progress -> {
                                val oldUploaded = activeFileBytes[event.path] ?: 0L
                                val delta = event.uploaded - oldUploaded
                                activeFileBytes[event.path] = event.uploaded
                                // 累积 delta（节流期间合并）
                                if (delta > 0) pendingDeltas[event.path] = (pendingDeltas[event.path] ?: 0L) + delta
                                val activeTotal = activeFileBytes.values.sum()
                                val transferred = completedBytes.get() + activeTotal
                                // 进度回退检测
                                if (transferred < lastTransferredBytes) {
                                    val prevPct = if (state.syncTask.totalBytes > 0) lastTransferredBytes * 100.0 / state.syncTask.totalBytes else 0.0
                                    val currPct = if (state.syncTask.totalBytes > 0) transferred * 100.0 / state.syncTask.totalBytes else 0.0
                                    val diagInfo = buildString {
                                        appendLine("=== 进度回退检测报告 ===")
                                        appendLine("时间: ${java.time.LocalDateTime.now()}")
                                        appendLine("触发: transferred($transferred) < lastTransferredBytes($lastTransferredBytes)")
                                        appendLine("回退量: ${lastTransferredBytes - transferred} bytes")
                                        appendLine()
                                        appendLine("--- 百分比 ---")
                                        appendLine("上次: ${String.format("%.4f", prevPct)}%")
                                        appendLine("本次: ${String.format("%.4f", currPct)}%")
                                        appendLine("百分比回退: ${String.format("%.4f", prevPct - currPct)}%")
                                        appendLine()
                                        appendLine("--- 事件详情 ---")
                                        appendLine("事件类型: Progress")
                                        appendLine("event.path=${event.path}")
                                        appendLine("event.uploaded=${event.uploaded}")
                                        appendLine("event.total=${event.total}")
                                        appendLine("oldUploaded=$oldUploaded")
                                        appendLine("delta=$delta")
                                        appendLine()
                                        appendLine("--- 内部状态 ---")
                                        appendLine("completedBytes=${completedBytes.get()}")
                                        appendLine("completedFilesCount=$completedFilesCount")
                                        appendLine("successCount=$successCount")
                                        appendLine("failCount=$failCount")
                                        appendLine("activeTotal=$activeTotal")
                                        appendLine("activeFileBytes(${activeFileBytes.size}个):")
                                        activeFileBytes.forEach { (k, v) -> appendLine("  $k = $v") }
                                        appendLine()
                                        appendLine("--- state.syncTask 快照 ---")
                                        appendLine("phase=${state.syncTask.phase}")
                                        appendLine("totalFiles=${state.syncTask.totalFiles}")
                                        appendLine("totalBytes=${state.syncTask.totalBytes}")
                                        appendLine("transferredBytes=${state.syncTask.transferredBytes}")
                                        appendLine("overallProgress=${state.syncTask.overallProgress}")
                                        appendLine("speed=${state.syncTask.speed}")
                                        appendLine("concurrency=${state.syncTask.concurrency}")
                                        appendLine("fileProgress(${state.syncTask.fileProgress.size}个):")
                                        state.syncTask.fileProgress.forEach { (k, v) ->
                                            appendLine("  $k: uploaded=${v.uploadedBytes}/${v.totalBytes} status=${v.status}")
                                        }
                                        appendLine()
                                        appendLine("--- queue 状态 ---")
                                        appendLine("queueIndex=$queueIndex, queue.size=${queue.size}")
                                        appendLine("activeWorkers=$activeWorkers")
                                        appendLine("maxConcurrency=$maxConcurrency")
                                        appendLine()
                                        appendLine("--- 速度计算 ---")
                                        appendLine("speedLastBytes=$speedLastBytes")
                                        appendLine("speedLastTime=$speedLastTime")
                                        appendLine("currentSpeed=$currentSpeed")
                                        appendLine()
                                        appendLine("--- 调用栈 ---")
                                        Thread.currentThread().stackTrace.take(25).forEach { appendLine("  $it") }
                                    }
                                    try {
                                        val diagDir = com.whmdg.mczj.tools.AppDataPaths.diagnostics(context)
                                        diagDir.mkdirs()
                                        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                                        java.io.File(diagDir, "progress_regression_$ts.log").writeText(diagInfo)
                                    } catch (_: Exception) {}
                                    android.widget.Toast.makeText(context, "检测到进度异常回退，已强制中断以保护数据安全", android.widget.Toast.LENGTH_LONG).show()
                                    syncJob?.cancel()
                                    return@launch
                                }
                                lastTransferredBytes = transferred
                                // 速度计算
                                val now = System.currentTimeMillis()
                                if (now - speedLastTime >= 1000) {
                                    currentSpeed = (transferred - speedLastBytes) * 1000 / (now - speedLastTime)
                                    speedLastBytes = transferred
                                    speedLastTime = now
                                }
                                // 按时间节流：每 100ms 才触发一次 UI 更新
                                if (now - lastUiUpdateTime >= 100) {
                                    lastUiUpdateTime = now
                                    // 从 activeFileBytes 构建最新进度 map（避免读 stale state）
                                    val currentProgress = activeFileBytes.mapValues { (path, uploaded) ->
                                        SyncFileProgress(
                                            relativePath = path,
                                            totalBytes = fileSizes[path] ?: uploaded,
                                            uploadedBytes = uploaded,
                                            status = UploadStatus.UPLOADING
                                        )
                                    }
                                    state.syncTask = state.syncTask.copy(
                                        fileProgress = currentProgress,
                                        transferredBytes = transferred,
                                        speed = currentSpeed,
                                        concurrency = maxConcurrency
                                    )
                                    // 增量更新父文件夹（使用累积 delta）
                                    for ((path, accDelta) in pendingDeltas) {
                                        updateFolderAggregates(path, addGreen = accDelta, addYellow = -accDelta)
                                    }
                                    pendingDeltas.clear()
                                    // 只更新文件自身进度条（不触发 aggregateFolder）
                                    updateFileProgressOnly(event.path)
                                    // 进度异常检测：UI 增量 > 128KB
                                    val uiDelta = transferred - lastUiTransferredBytes
                                    if (uiDelta > anomalyThreshold && lastUiTransferredBytes > 0) {
                                        anomalyCount++
                                        val deltaKB = uiDelta / 1024
                                        val deltaStr = if (deltaKB >= 1024) "${String.format("%.1f", uiDelta / 1048576.0)}MB" else "${deltaKB}KB"
                                        android.widget.Toast.makeText(context, "检测到第${anomalyCount}次数据异常，数据异常为增加了$deltaStr", android.widget.Toast.LENGTH_LONG).show()
                                        try {
                                            anomalyLogFile.appendText(buildString {
                                                appendLine("=== 第${anomalyCount}次进度异常 ===")
                                                appendLine("时间: ${java.time.LocalDateTime.now()}")
                                                appendLine("UI增量: ${uiDelta} bytes ($deltaStr)")
                                                appendLine("上次UI transferredBytes: $lastUiTransferredBytes")
                                                appendLine("本次UI transferredBytes: $transferred")
                                                appendLine("totalBytes: ${state.syncTask.totalBytes}")
                                                appendLine("anomalyThreshold: $anomalyThreshold")
                                                appendLine("活跃文件: ${activeFileBytes.keys.joinToString()}")
                                                appendLine()
                                            })
                                        } catch (_: Exception) {}
                                        if (anomalyCount >= 5) {
                                            android.widget.Toast.makeText(context, "检测到本次上传异常，已自动终止，为了保护数据安全", android.widget.Toast.LENGTH_LONG).show()
                                            state.anomalyDialogMessage = "检测到本次上传进度异常（累计${anomalyCount}次增量超限），已自动终止上传以保护数据安全。已上传的文件不受影响，未上传的文件已重置为待上传状态。"
                                            syncJob?.cancel()
                                            return@launch
                                        }
                                    }
                                    lastUiTransferredBytes = transferred
                                }
                            }
                            is UploadEvent.Complete -> {
                                val oldUploaded = activeFileBytes[event.path] ?: 0L
                                // 先刷新该文件的累积 delta 到 UI
                                val accDelta = pendingDeltas.remove(event.path) ?: 0L
                                if (accDelta > 0) updateFolderAggregates(event.path, addGreen = accDelta, addYellow = -accDelta)
                                val remaining = event.fileSize - oldUploaded
                                activeFileBytes.remove(event.path)
                                completedFilesCount++
                                if (event.success) {
                                    successCount++
                                    completedBytes.addAndGet(event.fileSize)
                                } else {
                                    failCount++
                                }
                                val currentProgress = state.syncTask.fileProgress.toMutableMap()
                                currentProgress.remove(event.path)
                                val transferred = completedBytes.get() + activeFileBytes.values.sum()
                                // 进度回退检测
                                if (transferred < lastTransferredBytes) {
                                    val prevPct = if (state.syncTask.totalBytes > 0) lastTransferredBytes * 100.0 / state.syncTask.totalBytes else 0.0
                                    val currPct = if (state.syncTask.totalBytes > 0) transferred * 100.0 / state.syncTask.totalBytes else 0.0
                                    val diagInfo = buildString {
                                        appendLine("=== 进度回退检测报告 ===")
                                        appendLine("时间: ${java.time.LocalDateTime.now()}")
                                        appendLine("触发: transferred($transferred) < lastTransferredBytes($lastTransferredBytes)")
                                        appendLine("回退量: ${lastTransferredBytes - transferred} bytes")
                                        appendLine()
                                        appendLine("--- 百分比 ---")
                                        appendLine("上次: ${String.format("%.4f", prevPct)}%")
                                        appendLine("本次: ${String.format("%.4f", currPct)}%")
                                        appendLine("百分比回退: ${String.format("%.4f", prevPct - currPct)}%")
                                        appendLine()
                                        appendLine("--- 事件详情 ---")
                                        appendLine("事件类型: Complete")
                                        appendLine("event.path=${event.path}")
                                        appendLine("event.success=${event.success}")
                                        appendLine("event.fileSize=${event.fileSize}")
                                        appendLine("event.error=${event.error}")
                                        appendLine("oldUploaded=$oldUploaded")
                                        appendLine("remaining=$remaining")
                                        appendLine()
                                        appendLine("--- 内部状态 ---")
                                        appendLine("completedBytes=${completedBytes.get()}")
                                        appendLine("completedFilesCount=$completedFilesCount")
                                        appendLine("successCount=$successCount")
                                        appendLine("failCount=$failCount")
                                        appendLine("activeFileBytes(${activeFileBytes.size}个):")
                                        activeFileBytes.forEach { (k, v) -> appendLine("  $k = $v") }
                                        appendLine()
                                        appendLine("--- state.syncTask 快照 ---")
                                        appendLine("phase=${state.syncTask.phase}")
                                        appendLine("totalFiles=${state.syncTask.totalFiles}")
                                        appendLine("totalBytes=${state.syncTask.totalBytes}")
                                        appendLine("transferredBytes=${state.syncTask.transferredBytes}")
                                        appendLine("overallProgress=${state.syncTask.overallProgress}")
                                        appendLine("speed=${state.syncTask.speed}")
                                        appendLine("concurrency=${state.syncTask.concurrency}")
                                        appendLine("fileProgress(${state.syncTask.fileProgress.size}个):")
                                        state.syncTask.fileProgress.forEach { (k, v) ->
                                            appendLine("  $k: uploaded=${v.uploadedBytes}/${v.totalBytes} status=${v.status}")
                                        }
                                        appendLine()
                                        appendLine("--- queue 状态 ---")
                                        appendLine("queueIndex=$queueIndex, queue.size=${queue.size}")
                                        appendLine("activeWorkers=$activeWorkers")
                                        appendLine("maxConcurrency=$maxConcurrency")
                                        appendLine()
                                        appendLine("--- 速度计算 ---")
                                        appendLine("speedLastBytes=$speedLastBytes")
                                        appendLine("speedLastTime=$speedLastTime")
                                        appendLine("currentSpeed=$currentSpeed")
                                        appendLine()
                                        appendLine("--- 调用栈 ---")
                                        Thread.currentThread().stackTrace.take(25).forEach { appendLine("  $it") }
                                    }
                                    try {
                                        val diagDir = com.whmdg.mczj.tools.AppDataPaths.diagnostics(context)
                                        diagDir.mkdirs()
                                        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                                        java.io.File(diagDir, "progress_regression_$ts.log").writeText(diagInfo)
                                    } catch (_: Exception) {}
                                    android.widget.Toast.makeText(context, "检测到进度异常回退，已强制中断以保护数据安全", android.widget.Toast.LENGTH_LONG).show()
                                    syncJob?.cancel()
                                    return@launch
                                }
                                lastTransferredBytes = transferred
                                state.syncTask = state.syncTask.copy(
                                    fileProgress = currentProgress,
                                    completedFiles = completedFilesCount,
                                    transferredBytes = transferred,
                                    speed = currentSpeed,
                                    concurrency = maxConcurrency
                                )
                                if (!event.success && event.error != null) {
                                    com.whmdg.mczj.tools.fileop.sync.CloudSyncLogger.logSync("CloudPane", "上传失败: ${event.path} - ${event.error}")
                                }
                                // 增量更新父文件夹（余量从黄色移到绿色）
                                if (event.success && remaining > 0) updateFolderAggregates(event.path, addGreen = remaining, addYellow = -remaining)
                                // 完整更新文件状态（含 DB 读取）
                                updateSingleEntry(event.path)
                            }
                            is UploadEvent.StatusChange -> {
                                // 文件开始上传：设置文件夹的黄色进度条
                                val fileEntry = state.entries.find { it.relativePath == event.path }
                                if (fileEntry != null) {
                                    updateFolderAggregates(event.path, addYellow = fileEntry.totalSize)
                                }
                                updateSingleEntry(event.path)
                            }
                        }
                    } catch (e: Exception) {
                        com.whmdg.mczj.tools.util.DiagnosticLog.log("SyncUpdater", "事件处理异常: ${e.message}")
                    }
                }
            }

            // 上传工作协程（回调仅发送事件，不直接修改 state）
            val uploadJobs = mutableListOf<Job>()

            while (queueIndex < queue.size || activeWorkers > 0) {
                while (activeWorkers >= maxConcurrency && queueIndex < queue.size) {
                    delay(100)
                }

                if (queueIndex < queue.size && activeWorkers < maxConcurrency) {
                    val idx = queueIndex++
                    val (file, relPath) = queue[idx]
                    val fileSize = file.length()
                    activeWorkers++

                    val job = launch {
                        try {
                            engine.uploadSingleFile(
                                relativePath = relPath,
                                remoteBasePath = remoteBasePath,
                                syncDb = syncDb,
                                onProgress = { uploadedBytes, totalBytes ->
                                    eventChannel.trySend(UploadEvent.Progress(relPath, uploadedBytes, totalBytes))
                                },
                                onComplete = { success, error ->
                                    eventChannel.trySend(UploadEvent.Complete(relPath, success, fileSize, error))
                                },
                                onStatusChange = {
                                    eventChannel.trySend(UploadEvent.StatusChange(relPath))
                                }
                            )
                        } finally {
                            activeWorkers--
                        }
                    }
                    uploadJobs.add(job)
                }

                uploadJobs.removeAll { !it.isActive }
            }

            // 等待所有上传完成 → 关闭 Channel → 等更新器处理完剩余事件
            uploadJobs.forEach { it.join() }
            eventChannel.close()
            updaterJob.join()

            // ⑮ 最终状态
            state.syncTask = state.syncTask.copy(phase = SyncPhase.COMPLETED, completedFiles = completedFilesCount)
            val msg = "文件夹上传完成: 成功${successCount}个" + if (failCount > 0) "，失败${failCount}个" else ""
            withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show() }

            // ⑯ 更新云盘卡片数据（云端大小、文件数、同步时间）
            withContext(Dispatchers.IO) {
                val cloudSize = syncDb.getSyncedSize("cloud_entries")
                val cloudCounts = syncDb.getStatusCounts("cloud_entries")
                val cloudFileCount = cloudCounts[SyncStatus.COMPLETED] ?: 0
                val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                com.whmdg.mczj.tools.ui.encryption.CloudSyncStore.update(context, "vault_$vaultId") { item ->
                    item.copy(
                        cloudSize = cloudSize,
                        cloudFileCount = cloudFileCount,
                        lastSyncTime = now
                    )
                }
            }

            // ⑰ 上传 cloud.db 到云端元数据目录
            state.syncTask = state.syncTask.copy(phase = SyncPhase.SYNCING, currentFileName = "正在同步云端列表...")
            val dbUploaded = uploadCloudDb()
            if (dbUploaded) {
                com.whmdg.mczj.tools.AppDataPaths.syncLock(context, vaultId).delete()
            }

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

    /** 取消上传：停止任务，上传 cloud.db，清理锁，已上传的不动，未上传的重置为 PENDING */
    fun cancelUpload() {
        state.syncDialogVisible = false
        val job = syncJob
        syncJob = null
        scope.launch {
            // 1. 取消旧协程并等待其真正终止
            job?.cancel()
            job?.join()

            // 2. 上传 cloud.db（已上传的文件已在DB中更新）
            state.syncDialogVisible = true
            state.syncTask = SyncTaskState(phase = SyncPhase.SYNCING, currentFileName = "正在同步云端列表...")
            val dbUploaded = uploadCloudDb()
            if (dbUploaded) {
                com.whmdg.mczj.tools.AppDataPaths.syncLock(context, vaultId).delete()
            }

            // 3. 清理 DB 中残留的 UPLOADING/QUEUED 状态
            withContext(Dispatchers.IO) {
                val entries = syncDb.getEntriesByStatus("local_entries", SyncStatus.QUEUED) +
                    syncDb.getEntriesByStatus("local_entries", SyncStatus.UPLOADING)
                for (entry in entries) {
                    syncDb.updateStatus("local_entries", entry.path, SyncStatus.PENDING)
                    syncDb.updateUploadedSize("local_entries", entry.path, 0)
                }
            }
            state.syncTask = SyncTaskState()
            state.syncDialogVisible = false
            silentRefresh()
            // 4. 自检确认已终止
            if (job == null || !job.isActive) {
                android.widget.Toast.makeText(context, "上传任务已终止", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun getSyncState(path: String): SyncFileProgress? {
        return state.syncTask.fileProgress[path]
    }

    /** 压缩并上传 cloud.db 到 .sync_meta/。成功返回 true，失败返回 false。 */
    suspend fun uploadCloudDb(): Boolean = withContext(Dispatchers.IO) {
        try {
            val dbFile = File(com.whmdg.mczj.tools.AppDataPaths.encryption(context), "云盘同步/$vaultName/vault_sync.db")
            if (!dbFile.exists()) return@withContext false

            val zipFile = File(context.cacheDir, "${vaultName}_vault_sync.db.7z")
            try {
                val binary = com.whmdg.mczj.tools.util.BinaryExtractor.ensureExtracted(context)
                val esc = com.whmdg.mczj.tools.util.SevenZipCommand::escape
                val cmd = "${esc(binary.absolutePath)} a ${esc(zipFile.absolutePath)} ${esc(dbFile.absolutePath)} -mx=9 -pmczj -mhe=on"
                com.whmdg.mczj.tools.security.ShellExecutor.execute(com.whmdg.mczj.tools.security.Permission.MAX, cmd)

                val metaDir = "${remoteBasePath}/.sync_meta"
                try { webdavClient.mkdir(metaDir) } catch (_: Exception) {}

                val remotePath = "$metaDir/${vaultName}_vault_sync.db.7z"
                webdavClient.uploadFile(zipFile, remotePath) { _ -> }

                val exists = webdavClient.exists(remotePath)
                if (!exists) {
                    com.whmdg.mczj.tools.fileop.sync.CloudSyncLogger.logSync("CloudPane", "cloud.db 上传验证失败")
                }
                exists
            } finally {
                zipFile.delete()
            }
        } catch (e: Exception) {
            com.whmdg.mczj.tools.fileop.sync.CloudSyncLogger.logSync("CloudPane", "cloud.db 上传失败: ${e.message}")
            false
        }
    }

    fun refresh() {
        syncLocalFiles()
        navigateTo(state.currentPath)
    }

    /** 异常终止后重置 QUEUED/UPLOADING 为 PENDING */
    fun resetUploadingEntries() {
        scope.launch(Dispatchers.IO) {
            val entries = syncDb.getEntriesByStatus("local_entries", SyncStatus.QUEUED) +
                syncDb.getEntriesByStatus("local_entries", SyncStatus.UPLOADING)
            for (entry in entries) {
                syncDb.updateStatus("local_entries", entry.path, SyncStatus.PENDING)
                syncDb.updateUploadedSize("local_entries", entry.path, 0)
            }
        }
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
                        val isSyncActive = state.syncTask.phase == SyncPhase.SYNCING || state.syncTask.phase == SyncPhase.SCANNING
                        if (isSyncActive) {
                            val done = liveProgress?.uploadedBytes ?: dbUploaded
                            uploadedSize += done
                            uploadingSize += (fileSize - done)
                        } else {
                            // 没有活跃上传任务，重置为待上传
                            syncDb.updateStatus("local_entries", childPath, SyncStatus.PENDING)
                        }
                    }
                    else -> {} // PENDING / QUEUED / PAUSED → 不计入
                }
            }
        }

        return FolderAggregate(totalSize, uploadedSize, uploadingSize)
    }

    /** 只更新文件自身的进度条（不触发父文件夹聚合，用于 Progress 事件高频调用） */
    private fun updateFileProgressOnly(relativePath: String) {
        val entries = state.entries
        val idx = entries.indexOfFirst { it.relativePath == relativePath }
        if (idx < 0) return

        val old = entries[idx]
        if (old.isDirectory) return  // 文件夹不处理

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
        val newEntry = old.copy(
            uploadedSize = greenSize,
            uploadingSize = yellowSize,
            syncStatus = dbEntry?.status ?: old.syncStatus
        )
        val newEntries = entries.toMutableList()
        newEntries[idx] = newEntry
        state.entries = newEntries
    }

    /** 就地更新单个条目（不重建整个列表，不显示 loading） */
    private fun updateSingleEntry(relativePath: String) {
        val entries = state.entries.toMutableList()
        var changed = false

        // 1. 如果该条目在当前视图中，直接更新
        val idx = entries.indexOfFirst { it.relativePath == relativePath }
        if (idx >= 0) {
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
            changed = true
        }

        // 2. 向上冒泡更新所有祖先文件夹（无论文件是否在当前视图中）
        changed = refreshParentAggregates(entries, relativePath) || changed

        if (changed) state.entries = entries
    }

    /** 刷新父文件夹聚合进度（向上冒泡，更新当前视图中可见的祖先文件夹） */
    private fun refreshParentAggregates(entries: MutableList<CloudFileEntry>, changedPath: String): Boolean {
        var changed = false
        var parent = changedPath.substringBeforeLast('/', "/")
        while (parent.isNotEmpty()) {
            val idx = entries.indexOfFirst { it.relativePath == parent && it.isDirectory }
            if (idx >= 0) {
                val agg = aggregateFolder(parent)
                entries[idx] = entries[idx].copy(
                    totalSize = agg.totalSize,
                    uploadedSize = agg.uploadedSize,
                    uploadingSize = agg.uploadingSize
                )
                changed = true
            }
            val next = parent.substringBeforeLast('/', "")
            if (next == parent) break
            parent = next
        }
        return changed
    }

    /** 增量更新父文件夹聚合值（O(深度)，不遍历文件）
     *  @param addGreen 绿色增加量（已上传字节）
     *  @param addYellow 黄色增加量（正在上传字节，负数表示减少）
     */
    private fun updateFolderAggregates(changedPath: String, addGreen: Long = 0, addYellow: Long = 0) {
        var parent = changedPath.substringBeforeLast('/', "/")
        while (parent.isNotEmpty()) {
            val entries = state.entries
            val idx = entries.indexOfFirst { it.relativePath == parent && it.isDirectory }
            if (idx >= 0) {
                val old = entries[idx]
                val newEntries = entries.toMutableList()
                newEntries[idx] = old.copy(
                    uploadedSize = (old.uploadedSize + addGreen).coerceAtLeast(0L),
                    uploadingSize = (old.uploadingSize + addYellow).coerceAtLeast(0L)
                )
                state.entries = newEntries
            }
            val next = parent.substringBeforeLast('/', "")
            if (next == parent) break
            parent = next
        }
    }

    /** 自然排序比较器：路径按深度优先 + 数字按自然序（file2 < file10） */
    private fun naturalOrderComparator(vaultDir: String) = Comparator<File> { a, b ->
        val pathA = a.relativeTo(File(vaultDir)).path.replace('\\', '/')
        val pathB = b.relativeTo(File(vaultDir)).path.replace('\\', '/')
        naturalCompare(pathA, pathB)
    }

    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                // 提取连续数字，按数值比较
                var numA = 0L
                while (i < a.length && a[i].isDigit()) {
                    numA = numA * 10 + (a[i] - '0')
                    i++
                }
                var numB = 0L
                while (j < b.length && b[j].isDigit()) {
                    numB = numB * 10 + (b[j] - '0')
                    j++
                }
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
            } else {
                val cmp = ca.compareTo(cb)
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return a.length.compareTo(b.length)
    }

    /** 并发上传事件（通过 Channel 传递给更新器协程，避免多线程竞态） */
    private sealed class UploadEvent {
        data class Progress(val path: String, val uploaded: Long, val total: Long) : UploadEvent()
        data class Complete(val path: String, val success: Boolean, val fileSize: Long, val error: String?) : UploadEvent()
        data class StatusChange(val path: String) : UploadEvent()
    }

    private data class FolderAggregate(
        val totalSize: Long = 0,
        val uploadedSize: Long = 0,
        val uploadingSize: Long = 0
    )
}
