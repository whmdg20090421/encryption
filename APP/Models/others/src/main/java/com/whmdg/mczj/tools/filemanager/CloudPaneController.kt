package com.whmdg.mczj.tools.ui.filemanager

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
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
    private val vaultName: String,
    private val folderSizeDb: () -> FolderSizeDb,
    private val recalculateFolderSize: suspend (String) -> Unit
) {
    val state = CloudPanelState()
    private val webdavClient = WebDavFileClient(webdavConfig)
    private var syncJob: Job? = null
    // 构造时即打开本地同步库，支持 init 前的云端索引恢复。
    private val syncDb: SyncDatabase = SyncDatabase.getInstance(context, vaultName)

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
        /** 上传功能是否被禁用（用户取消下载覆盖时设置） */
        var uploadDisabled by mutableStateOf(false)
        /** 文件夹大小异常：需要重新计算的路径集合 */
        var sizeAnomalyPaths by mutableStateOf<Set<String>>(emptySet())
        /** cloud.db 同步弹窗状态（null=隐藏） */
        var cloudDbSyncState by mutableStateOf<CloudDbSyncState?>(null)
    }

    /** cloud.db 同步弹窗状态 */
    data class CloudDbSyncState(
        val phase: String = "正在加密",  // "正在加密" / "正在上传" / "正在验证"
        val isError: Boolean = false,
        val errorMessage: String = "",
        val onRetry: () -> Unit = {},
        val onConfirm: () -> Unit = {}
    )

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
        val syncStatus: SyncStatus? = null,
        /** 仅存在于云端，本地无对应文件（纯内存标识，不持久化） */
        val isCloudOnly: Boolean = false
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
        // 首次进入云端保险箱时恢复云端文件索引，随后刷新云端-only 目录。
        scope.launch {
            restoreCloudDbFromCloud()
            navigateTo(state.currentPath)
        }
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
        openProgressDialog()
        syncJob = scope.launch {
            // 上传前检查云端 db 是否被其他设备更新
            syncCloudDbBeforeUpload()

            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val logFileName = "${vaultName}_upload_${timestamp}.log"
            val internalLogDir = com.whmdg.mczj.tools.AppDataPaths.cloudSyncLogs(context)
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
            val localFileProgress = java.util.concurrent.ConcurrentHashMap<String, SyncFileProgress>()
            // 进度异常检测器
            val anomalyThreshold = 128 * 1024L  // 128KB
            var anomalyCount = 0
            var lastUiTransferredBytes = 0L
            val anomalyLogFile = File(com.whmdg.mczj.tools.AppDataPaths.cloudSyncAnomalies(context), "${vaultName}_anomaly_${timestamp}.log")
            val anomalyTerminated = java.util.concurrent.atomic.AtomicBoolean(false)
            engine.uploadSingleFile(
                relativePath = relativePath,
                remoteBasePath = remoteBasePath,
                syncDb = syncDb,
                onProgress = { uploadedBytes, totalBytes ->
                    if (anomalyTerminated.get()) return@uploadSingleFile
                    val now = System.currentTimeMillis()
                    val uiDelta = uploadedBytes - lastUiTransferredBytes
                    // 始终记录到本地 map（供 updateSingleEntry 读取）
                    localFileProgress[relativePath] = SyncFileProgress(
                        relativePath = relativePath,
                        totalBytes = totalBytes,
                        uploadedBytes = uploadedBytes,
                        status = UploadStatus.UPLOADING
                    )
                    // 每次回调直接更新 state，由 Compose 渲染机制自行节流
                    val currentProgress = state.syncTask.fileProgress.toMutableMap()
                    currentProgress[relativePath] = localFileProgress[relativePath]!!
                    state.syncTask = state.syncTask.copy(
                        fileProgress = currentProgress,
                        transferredBytes = uploadedBytes
                    )
                    updateFileProgressOnly(relativePath)
                    // 异步冒泡父文件夹三色进度条（不阻塞进度回调）
                    scope.launch(Dispatchers.IO) { updateSingleEntry(relativePath) }
                    // 进度异常检测：单次回调增量 > 128KB
                    if (uiDelta > anomalyThreshold && lastUiTransferredBytes > 0) {
                        anomalyCount++
                        val deltaKB = uiDelta / 1024
                        val deltaStr = if (deltaKB >= 1024) "${String.format("%.1f", uiDelta / 1048576.0)}MB" else "${deltaKB}KB"
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(context, "检测到第${anomalyCount}次数据异常，数据异常为增加了$deltaStr", android.widget.Toast.LENGTH_LONG).show()
                        }
                        try {
                            anomalyLogFile.appendText(buildString {
                                appendLine("=== 第${anomalyCount}次进度异常（单文件上传）===")
                                appendLine("时间: ${java.time.LocalDateTime.now()}")
                                appendLine("文件: $relativePath")
                                appendLine()
                                appendLine("--- 渲染器帧对比 ---")
                                appendLine("上次渲染 transferredBytes: $lastUiTransferredBytes")
                                appendLine("本次渲染 transferredBytes: $uploadedBytes")
                                appendLine("帧增量: ${uiDelta} bytes ($deltaStr)")
                                appendLine("anomalyThreshold: $anomalyThreshold")
                                appendLine()
                                appendLine("--- 诊断 ---")
                                val singleChunkOversize = uiDelta > anomalyThreshold
                                appendLine("单次回调是否超限: $singleChunkOversize")
                                if (singleChunkOversize) {
                                    appendLine("结论: 单次 onProgress 回调 delta=${deltaStr}，远超 128KB chunk 限制")
                                    appendLine("原因: OkHttp BufferedSink 缓冲合并了多次 sink.write，或网络层返回了超大块数据")
                                } else {
                                    appendLine("结论: 多次回调累积未刷新，刷新频率不足")
                                }
                                appendLine()
                            })
                        } catch (_: Exception) {}
                        if (anomalyCount >= 5) {
                            anomalyTerminated.set(true)
                            state.anomalyDialogMessage = "检测到本次上传进度异常（累计${anomalyCount}次增量超限），已自动终止上传以保护数据安全。已上传的文件不受影响，未上传的文件已重置为待上传状态。"
                            forceTerminate()
                        }
                    }
                    lastUiTransferredBytes = uploadedBytes
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

                        // 关闭进度弹窗，上传 cloud.db（自带弹窗）
                        closeProgressDialog()
                        uploadCloudDbWithUI()
                        // 无论成功失败都删除锁文件
                        com.whmdg.mczj.tools.AppDataPaths.syncLock(context, vaultId).delete()
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

            // 上传前检查云端 db 是否被其他设备更新
            syncCloudDbBeforeUpload()

            // 创建上传锁
            val lockFile = com.whmdg.mczj.tools.AppDataPaths.syncLock(context, vaultId)
            lockFile.writeText("""{"vaultId":$vaultId,"vaultName":"$vaultName","startTime":"${java.time.LocalDateTime.now()}","status":"uploading"}""")

            // 显示弹窗（扫描阶段：不定进度条）
            state.onCancelUpload = ::cancelUpload
            state.syncTask = SyncTaskState(phase = SyncPhase.SCANNING)
            openProgressDialog()

            // ① 获取本地文件列表（磁盘）
            val localFiles = withContext(Dispatchers.IO) {
                folder.walkTopDown()
                    .filter { it.isFile && it.name !in excludedFiles }
                    .sortedWith(naturalOrderComparator(vaultDir))
                    .toList()
            }

            if (localFiles.isEmpty()) {
                withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, "文件夹为空", android.widget.Toast.LENGTH_SHORT).show() }
                closeProgressDialog()
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
                closeProgressDialog()
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
                silentRefresh()
                withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, "所有文件已上传完成", android.widget.Toast.LENGTH_SHORT).show() }
                closeProgressDialog()
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
            val logDir = com.whmdg.mczj.tools.AppDataPaths.cloudSyncLogs(context)
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
            state.syncTask = SyncTaskState(
                phase = SyncPhase.SYNCING,
                totalFiles = queue.size,
                totalBytes = queue.sumOf { it.first.length() },
                concurrency = maxConcurrency
            )
            openProgressDialog()

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
                // 进度异常检测器（按单文件增量检测，128KB 阈值）
                val anomalyThreshold = 128 * 1024L
                var anomalyCount = 0
                val lastUiFileBytes = java.util.concurrent.ConcurrentHashMap<String, Long>()
                val anomalyLogFile = File(com.whmdg.mczj.tools.AppDataPaths.cloudSyncAnomalies(context), "${vaultName}_anomaly_${timestamp}.log")

                for (event in eventChannel) {
                    if (!isActive) break
                    try {
                        when (event) {
                            is UploadEvent.Progress -> {
                                val oldUploaded = activeFileBytes[event.path] ?: 0L
                                val delta = event.uploaded - oldUploaded
                                activeFileBytes[event.path] = event.uploaded
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
                                        val regDir = com.whmdg.mczj.tools.AppDataPaths.cloudSyncRegressions(context)
                                        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                                        java.io.File(regDir, "${vaultName}_regression_$ts.log").writeText(diagInfo)
                                    } catch (_: Exception) {}
                                    state.anomalyDialogMessage = "检测到进度异常回退（transferred 从 ${lastTransferredBytes} 降至 $transferred），已自动终止上传以保护数据安全。"
                                    forceTerminate()
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
                                // 每次回调直接更新 state，由 Compose 渲染机制自行节流
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
                                // 增量更新父文件夹（使用本次单次 delta）
                                if (delta > 0) updateFolderAggregates(event.path, addGreen = delta, addYellow = -delta)
                                // 只更新文件自身进度条（不触发 aggregateFolder）
                                updateFileProgressOnly(event.path)
                                // 进度异常检测：单文件渲染帧增量 > 128KB
                                for ((path, uploaded) in activeFileBytes) {
                                    val prev = lastUiFileBytes[path]
                                    if (prev != null && uploaded - prev > anomalyThreshold) {
                                        anomalyCount++
                                        val uiDelta = uploaded - prev
                                        val deltaKB = uiDelta / 1024
                                        val deltaStr = if (deltaKB >= 1024) "${String.format("%.1f", uiDelta / 1048576.0)}MB" else "${deltaKB}KB"
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            android.widget.Toast.makeText(context, "检测到第${anomalyCount}次数据异常，数据异常为增加了$deltaStr", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                        try {
                                            anomalyLogFile.appendText(buildString {
                                                appendLine("=== 第${anomalyCount}次进度异常（并发上传）===")
                                                appendLine("时间: ${java.time.LocalDateTime.now()}")
                                                appendLine()
                                                appendLine("--- 渲染器帧对比 ---")
                                                appendLine("上次渲染各文件状态:")
                                                lastUiFileBytes.forEach { (k, v) -> appendLine("  $k = $v bytes") }
                                                appendLine("本次渲染各文件状态:")
                                                activeFileBytes.forEach { (k, v) -> appendLine("  $k = $v bytes") }
                                                appendLine()
                                                appendLine("--- 逐文件增量 ---")
                                                activeFileBytes.forEach { (k, v) ->
                                                    val p = lastUiFileBytes[k]
                                                    val d = if (p != null) v - p else 0L
                                                    val dStr = if (d >= 1024 * 1024) "${String.format("%.2f", d / 1048576.0)}MB" else "${d / 1024}KB"
                                                    val flag = if (p != null && d > anomalyThreshold) " ⚠️ 超限" else ""
                                                    appendLine("  $k: +${dStr}$flag")
                                                }
                                                appendLine()
                                                appendLine("--- 诊断 ---")
                                                appendLine("触发文件: $path")
                                                appendLine("帧增量: ${uiDelta} bytes ($deltaStr)")
                                                appendLine("anomalyThreshold: $anomalyThreshold")
                                                appendLine("本次回调 event.delta: $delta bytes")
                                                val singleChunkOversize = delta > anomalyThreshold
                                                appendLine("单次回调是否超限(>128KB): $singleChunkOversize")
                                                if (singleChunkOversize) {
                                                    appendLine("结论: 单次 onProgress 回调 delta=${delta / 1024}KB，远超 128KB chunk 限制")
                                                    appendLine("原因: OkHttp BufferedSink 缓冲合并了多次 sink.write，或网络层返回了超大块数据")
                                                } else {
                                                    appendLine("结论: 多次回调累积未刷新，刷新频率不足")
                                                }
                                                appendLine()
                                            })
                                        } catch (_: Exception) {}
                                        if (anomalyCount >= 5) {
                                            state.anomalyDialogMessage = "检测到本次上传进度异常（累计${anomalyCount}次增量超限），已自动终止上传以保护数据安全。已上传的文件不受影响，未上传的文件已重置为待上传状态。"
                                            forceTerminate()
                                            return@launch
                                        }
                                    }
                                    lastUiFileBytes[path] = uploaded
                                }
                            }
                            is UploadEvent.Complete -> {
                                val oldUploaded = activeFileBytes[event.path] ?: 0L
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
                                        val regDir = com.whmdg.mczj.tools.AppDataPaths.cloudSyncRegressions(context)
                                        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                                        java.io.File(regDir, "${vaultName}_regression_$ts.log").writeText(diagInfo)
                                    } catch (_: Exception) {}
                                    state.anomalyDialogMessage = "检测到进度异常回退（transferred 从 ${lastTransferredBytes} 降至 $transferred），已自动终止上传以保护数据安全。"
                                    forceTerminate()
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

            // ⑮ Toast 提示
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

            // ⑰ 关闭进度弹窗，上传 cloud.db（自带弹窗）
            closeProgressDialog()
            uploadCloudDbWithUI()
            // 无论成功失败都删除锁文件
            com.whmdg.mczj.tools.AppDataPaths.syncLock(context, vaultId).delete()
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
            val internalLogDir = com.whmdg.mczj.tools.AppDataPaths.cloudSyncLogs(context)
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

    // ── 进度弹窗控制 ──
    // 注意：open/show 只控制 syncDialogVisible，phase 由调用方在之前设置

    /** 打开进度弹窗（首次显示，调用前需先设置 phase） */
    fun openProgressDialog() {
        state.syncDialogVisible = true
    }

    /** 关闭进度弹窗（弹窗和悬浮窗都消失，phase 设为 COMPLETED） */
    fun closeProgressDialog() {
        state.syncDialogVisible = false
        state.syncTask = state.syncTask.copy(phase = SyncPhase.COMPLETED)
    }

    /** 隐藏进度弹窗（弹窗消失，保留悬浮窗，phase 不变） */
    fun hideProgressDialog() {
        state.syncDialogVisible = false
    }

    /** 显示进度弹窗（从悬浮窗恢复为弹窗，phase 不变） */
    fun showProgressDialog() {
        state.syncDialogVisible = true
    }

    /** 取消上传：停止任务，上传 cloud.db，清理锁，已上传的不动，未上传的重置为 PENDING */
    fun cancelUpload() {
        val job = syncJob
        syncJob = null
        scope.launch {
            // 1. 弹窗切换为不定进度条，提示正在取消
            state.syncTask = SyncTaskState(phase = SyncPhase.SCANNING, currentFileName = "正在取消上传连接...")
            // 2. 取消旧协程并等待其真正终止
            job?.cancel()
            job?.join()

            // 3. 关闭进度弹窗，上传 cloud.db（自带弹窗）
            closeProgressDialog()
            uploadCloudDbWithUI()
            // 无论成功失败都删除锁文件
            com.whmdg.mczj.tools.AppDataPaths.syncLock(context, vaultId).delete()

            // 4. 清理 DB 中残留的 UPLOADING/QUEUED 状态
            withContext(Dispatchers.IO) {
                val entries = syncDb.getEntriesByStatus("local_entries", SyncStatus.QUEUED) +
                    syncDb.getEntriesByStatus("local_entries", SyncStatus.UPLOADING)
                for (entry in entries) {
                    syncDb.updateStatus("local_entries", entry.path, SyncStatus.PENDING)
                    syncDb.updateUploadedSize("local_entries", entry.path, 0)
                }
            }
            silentRefresh()
            android.widget.Toast.makeText(context, "上传任务已终止", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 强制终止：立即杀死上传协程，关闭进度弹窗，重置状态，不上传 cloud.db */
    fun forceTerminate() {
        syncJob?.cancel()
        syncJob = null
        closeProgressDialog()
        scope.launch(Dispatchers.IO) {
            val entries = syncDb.getEntriesByStatus("local_entries", SyncStatus.QUEUED) +
                syncDb.getEntriesByStatus("local_entries", SyncStatus.UPLOADING)
            for (entry in entries) {
                syncDb.updateStatus("local_entries", entry.path, SyncStatus.PENDING)
                syncDb.updateUploadedSize("local_entries", entry.path, 0)
            }
            try { com.whmdg.mczj.tools.AppDataPaths.syncLock(context, vaultId).delete() } catch (_: Exception) {}
            silentRefresh()
        }
    }

    fun getSyncState(path: String): SyncFileProgress? {
        return state.syncTask.fileProgress[path]
    }

    /** 压缩并上传 cloud.db 到 .sync_meta/。成功返回 true，失败返回 false。 */
    /**
     * 上传 cloud.db 到云端，带 UI 弹窗反馈。
     * 成功：关闭弹窗，删除锁文件。
     * 失败：弹窗显示错误原因 + 重试/确认按钮，锁文件由调用方删除。
     * @return true=成功，false=失败（用户点确认或重试后仍失败）
     */
    suspend fun uploadCloudDbWithUI(): Boolean {
        // 显示同步弹窗
        state.cloudDbSyncState = CloudDbSyncState(phase = "正在加密")

        val result = withContext(Dispatchers.IO) {
            try {
                val dbFile = File(com.whmdg.mczj.tools.AppDataPaths.encryption(context), "云盘同步/$vaultName/vault_sync.db")
                if (!dbFile.exists()) return@withContext CloudDbResult.Failure("数据库文件不存在")

                val zipFile = File(context.cacheDir, "${vaultName}_vault_sync.db.7z")
                try {
                    com.whmdg.mczj.tools.util.JBindingClient.compress(
                        sourcePaths = listOf(dbFile.absolutePath),
                        outputPath = zipFile.absolutePath,
                        format = "7z", level = 9,
                        password = "mczj", useAes = true, encryptNames = true
                    ).getOrThrow()

                    // 切换状态：正在上传
                    withContext(Dispatchers.Main) {
                        state.cloudDbSyncState = state.cloudDbSyncState?.copy(phase = "正在上传")
                    }

                    val metaDir = "${remoteBasePath}/.sync_meta"
                    try { webdavClient.mkdir(metaDir) } catch (_: Exception) {}

                    val remotePath = "$metaDir/${vaultName}_vault_sync.db.7z"
                    webdavClient.uploadFile(zipFile, remotePath) { _ -> }

                    // 切换状态：正在验证
                    withContext(Dispatchers.Main) {
                        state.cloudDbSyncState = state.cloudDbSyncState?.copy(phase = "正在验证")
                    }

                    val exists = webdavClient.exists(remotePath)
                    if (!exists) {
                        return@withContext CloudDbResult.Failure("云端文件验证失败")
                    }
                    // 保存远程元数据
                    val remoteMeta = webdavClient.getFileMetadata(remotePath)
                    if (remoteMeta != null) {
                        saveCloudDbMeta(remoteMeta.size, remoteMeta.lastModified)
                    }
                    CloudDbResult.Success
                } finally {
                    zipFile.delete()
                }
            } catch (e: Exception) {
                CloudDbResult.Failure(e.message ?: "未知错误")
            }
        }

        return when (result) {
            is CloudDbResult.Success -> {
                state.cloudDbSyncState = null
                true
            }
            is CloudDbResult.Failure -> {
                // 显示错误弹窗，等待用户选择重试或确认
                val userChoice = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                    state.cloudDbSyncState = CloudDbSyncState(
                        phase = "上传失败",
                        isError = true,
                        errorMessage = result.message,
                        onRetry = { cont.resume(true) {} },
                        onConfirm = { cont.resume(false) {} }
                    )
                }
                state.cloudDbSyncState = null
                if (userChoice) {
                    // 重试
                    uploadCloudDbWithUI()
                } else {
                    false
                }
            }
        }
    }

    private sealed class CloudDbResult {
        object Success : CloudDbResult()
        data class Failure(val message: String) : CloudDbResult()
    }

    /** 上传 cloud.db（无 UI，用于恢复场景） */
    suspend fun uploadCloudDb(): Boolean = withContext(Dispatchers.IO) {
        try {
            val dbFile = File(com.whmdg.mczj.tools.AppDataPaths.encryption(context), "云盘同步/$vaultName/vault_sync.db")
            if (!dbFile.exists()) return@withContext false

            val zipFile = File(context.cacheDir, "${vaultName}_vault_sync.db.7z")
            try {
                com.whmdg.mczj.tools.util.JBindingClient.compress(
                    sourcePaths = listOf(dbFile.absolutePath),
                    outputPath = zipFile.absolutePath,
                    format = "7z", level = 9,
                    password = "mczj", useAes = true, encryptNames = true
                ).getOrThrow()

                val metaDir = "${remoteBasePath}/.sync_meta"
                try { webdavClient.mkdir(metaDir) } catch (_: Exception) {}

                val remotePath = "$metaDir/${vaultName}_vault_sync.db.7z"
                webdavClient.uploadFile(zipFile, remotePath) { _ -> }

                val exists = webdavClient.exists(remotePath)
                if (exists) {
                    val remoteMeta = webdavClient.getFileMetadata(remotePath)
                    if (remoteMeta != null) {
                        saveCloudDbMeta(remoteMeta.size, remoteMeta.lastModified)
                    }
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

    /** 保存云端 db 元数据到本地 */
    private fun saveCloudDbMeta(size: Long, lastModified: Long) {
        try {
            val metaFile = File(com.whmdg.mczj.tools.AppDataPaths.cloudDbMeta(context), "${vaultName}_meta.json")
            metaFile.writeText("""{"size":$size,"lastModified":$lastModified}""")
        } catch (_: Exception) {}
    }

    /** 检查云端 db 是否与本地记录一致。一致返回 true，不一致或无记录返回 false。 */
    private suspend fun isCloudDbConsistent(): Boolean = withContext(Dispatchers.IO) {
        try {
            val metaFile = File(com.whmdg.mczj.tools.AppDataPaths.cloudDbMeta(context), "${vaultName}_meta.json")
            if (!metaFile.exists()) return@withContext false

            val localMeta = org.json.JSONObject(metaFile.readText())
            val localSize = localMeta.getLong("size")
            val localLastModified = localMeta.getLong("lastModified")

            val remotePath = "${remoteBasePath}/.sync_meta/${vaultName}_vault_sync.db.7z"
            val remoteMeta = webdavClient.getFileMetadata(remotePath) ?: return@withContext false

            remoteMeta.size == localSize && remoteMeta.lastModified == localLastModified
        } catch (_: Exception) {
            false
        }
    }

    /** 上传前检查云端 db 是否被其他设备更新，若是则下载合并 */
    suspend fun syncCloudDbBeforeUpload() = withContext(Dispatchers.IO) {
        if (isCloudDbConsistent()) return@withContext

        // 云端 db 被更新过，下载并合并
        val remotePath = "${remoteBasePath}/.sync_meta/${vaultName}_vault_sync.db.7z"
        val zipFile = File(context.cacheDir, "${vaultName}_vault_sync_remote.db.7z")
        try {
            webdavClient.downloadFile(remotePath, zipFile) { _ -> }

            // 解压
            val extractDir = File(context.cacheDir, "cloud_db_merge_${vaultName}")
            extractDir.mkdirs()
            com.whmdg.mczj.tools.util.JBindingClient.extractAll(
                archivePath = zipFile.absolutePath,
                outputDir = extractDir.absolutePath,
                password = "mczj"
            ).getOrThrow()

            // 读取远程 db 的 cloud_entries，合并到本地
            val remoteDbFile = File(extractDir, "vault_sync.db")
            if (remoteDbFile.exists()) {
                val remoteDb = com.whmdg.mczj.tools.encryption.data.SyncDatabase.getInstance(context, "${vaultName}_remote")
                // 远程 db 的 cloud_entries 合并到本地 cloud_entries
                val remoteEntries = remoteDb.getAllEntries("cloud_entries")
                for (entry in remoteEntries) {
                    val localEntry = syncDb.getEntry("cloud_entries", entry.path)
                    if (localEntry == null || (entry.lastSyncTime ?: "") > (localEntry.lastSyncTime ?: "")) {
                        syncDb.upsertEntry("cloud_entries", entry)
                    }
                }
            }

            // 更新本地元数据
            val remoteMeta = webdavClient.getFileMetadata(remotePath)
            if (remoteMeta != null) {
                saveCloudDbMeta(remoteMeta.size, remoteMeta.lastModified)
            }

            com.whmdg.mczj.tools.fileop.sync.CloudSyncLogger.logSync("CloudPane", "云端 db 已合并")
        } catch (e: Exception) {
            com.whmdg.mczj.tools.fileop.sync.CloudSyncLogger.logSync("CloudPane", "云端 db 合并失败: ${e.message}")
        } finally {
            zipFile.delete()
            File(context.cacheDir, "cloud_db_merge_${vaultName}").deleteRecursively()
        }
    }

    /** 下载并解压云端同步数据库，将 cloud_entries 导入当前本地数据库。 */
    suspend fun restoreCloudDbFromCloud(): Boolean = withContext(Dispatchers.IO) {
        val remotePath = "${remoteBasePath}/.sync_meta/${vaultName}_vault_sync.db.7z"
        val zipFile = File(context.cacheDir, "${vaultName}_vault_sync_restore.db.7z")
        val extractDir = File(context.cacheDir, "cloud_db_restore_${vaultName}")
        try {
            if (!webdavClient.exists(remotePath)) return@withContext false
            webdavClient.downloadFile(remotePath, zipFile) { }
            extractDir.mkdirs()
            com.whmdg.mczj.tools.util.JBindingClient.extractAll(
                archivePath = zipFile.absolutePath,
                outputDir = extractDir.absolutePath,
                password = "mczj"
            ).getOrThrow()
            val sourceDb = File(extractDir, "vault_sync.db")
            if (!sourceDb.exists()) return@withContext false
            syncDb.importCloudEntriesFromFile(sourceDb)
            webdavClient.getFileMetadata(remotePath)?.let { saveCloudDbMeta(it.size, it.lastModified) }
            true
        } catch (e: Exception) {
            com.whmdg.mczj.tools.fileop.sync.CloudSyncLogger.logSync("CloudPane", "云端索引恢复失败: ${e.message}")
            false
        } finally {
            zipFile.delete()
            extractDir.deleteRecursively()
        }
    }

    /** 云端 db 差异检测结果 */
    data class CloudDiffResult(val changedFiles: List<ChangedFile>)

    /** 被其他设备更新的文件 */
    data class ChangedFile(
        val path: String,
        val localSize: Long,
        val cloudSize: Long,
        val localLastModified: Long,
        val cloudLastModified: Long
    )

    /** 下载进度 */
    data class DownloadProgress(
        val currentFile: Int,
        val totalFiles: Int,
        val fileName: String,
        val downloadedBytes: Long,
        val totalBytes: Long
    )

    /** 下载云端 db 并与本地对比，返回被其他设备更新的文件列表 */
    suspend fun downloadAndCompareCloudDb(
        onPhaseChange: (String) -> Unit = {}
    ): CloudDiffResult = withContext(Dispatchers.IO) {
        val remotePath = "${remoteBasePath}/.sync_meta/${vaultName}_vault_sync.db.7z"
        val zipFile = File(context.cacheDir, "${vaultName}_vault_sync_remote.db.7z")
        try {
            onPhaseChange("正在下载云端数据库...")
            webdavClient.downloadFile(remotePath, zipFile) { _ -> }

            onPhaseChange("正在解压数据库...")
            val extractDir = File(context.cacheDir, "cloud_db_diff_${vaultName}")
            extractDir.mkdirs()
            com.whmdg.mczj.tools.util.JBindingClient.extractAll(
                archivePath = zipFile.absolutePath,
                outputDir = extractDir.absolutePath,
                password = "mczj"
            ).getOrThrow()

            onPhaseChange("正在对比文件差异...")
            val remoteDbFile = File(extractDir, "vault_sync.db")
            val changedFiles = mutableListOf<ChangedFile>()
            if (remoteDbFile.exists()) {
                val remoteEntries = readCloudEntries(remoteDbFile)
                for (entry in remoteEntries) {
                    val localEntry = syncDb.getEntry("cloud_entries", entry.path)
                    if (localEntry != null) {
                        if (localEntry.size != entry.size || localEntry.lastModified != entry.lastModified) {
                            // 本地文件存在时才需要下载覆盖
                            val localFile = File(vaultDir, entry.path.trimStart('/'))
                            if (localFile.exists()) {
                                changedFiles.add(ChangedFile(
                                    path = entry.path,
                                    localSize = localEntry.size,
                                    cloudSize = entry.size,
                                    localLastModified = 0L,
                                    cloudLastModified = 0L
                                ))
                            }
                        }
                    }
                    // 合并到本地 cloud_entries
                    if (localEntry == null || (entry.lastSyncTime ?: "") > (localEntry.lastSyncTime ?: "")) {
                        syncDb.upsertEntry("cloud_entries", entry)
                    }
                }
            }

            // 更新本地元数据
            val remoteMeta = webdavClient.getFileMetadata(remotePath)
            if (remoteMeta != null) {
                saveCloudDbMeta(remoteMeta.size, remoteMeta.lastModified)
            }

            CloudDiffResult(changedFiles)
        } catch (e: Exception) {
            com.whmdg.mczj.tools.fileop.sync.CloudSyncLogger.logSync("CloudPane", "云端 db 对比失败: ${e.message}")
            CloudDiffResult(emptyList())
        } finally {
            zipFile.delete()
            File(context.cacheDir, "cloud_db_diff_${vaultName}").deleteRecursively()
        }
    }

    private fun readCloudEntries(sourceDb: File): List<com.whmdg.mczj.tools.encryption.data.SyncEntryRow> {
        val temp = com.whmdg.mczj.tools.encryption.data.SyncDatabase.getInstance(context, "${vaultName}_diff")
        temp.importCloudEntriesFromFile(sourceDb)
        return temp.getAllEntries("cloud_entries")
    }

    /** 下载被其他设备更新的文件，覆盖本地 */
    suspend fun downloadChangedFiles(
        files: List<ChangedFile>,
        onProgress: (DownloadProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val totalBytes = files.sumOf { it.cloudSize }
        var downloadedBytes = 0L
        for ((index, file) in files.withIndex()) {
            val fileName = file.path.substringAfterLast('/')
            onProgress(DownloadProgress(index + 1, files.size, fileName, downloadedBytes, totalBytes))
            val remotePath = "${remoteBasePath}/${file.path.trimStart('/')}"
            val localFile = File(vaultDir, file.path.trimStart('/'))
            localFile.parentFile?.mkdirs()
            webdavClient.downloadFile(remotePath, localFile) { delta ->
                downloadedBytes += delta
                onProgress(DownloadProgress(index + 1, files.size, fileName, downloadedBytes, totalBytes))
            }
            // 更新 local_entries
            syncDb.upsertEntry("local_entries", com.whmdg.mczj.tools.encryption.data.SyncEntryRow(
                path = file.path,
                size = localFile.length(),
                lastModified = java.time.Instant.ofEpochMilli(localFile.lastModified()).toString(),
                md5 = null,
                cloudHash = null,
                status = com.whmdg.mczj.tools.encryption.data.SyncStatus.COMPLETED,
                lastSyncTime = java.time.Instant.now().toString(),
                failReason = null
            ))
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
     * 列出本地保险箱目录，合并云端-only 条目。
     * 文件夹大小从 FolderSizeDb 缓存读取，同步状态只统计直接子文件。
     * 返回的列表已排序：文件夹在前，文件在后，自然排序。
     */
    private fun listLocalFiles(relativePath: String): List<CloudFileEntry> {
        val dir = File(vaultDir, relativePath.trimStart('/'))
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val children = dir.listFiles() ?: return emptyList()
        val entries = mutableListOf<CloudFileEntry>()
        val localNames = mutableSetOf<String>()
        val anomalyPaths = mutableSetOf<String>()

        for (file in children) {
            if (file.name in excludedFiles) continue
            localNames.add(file.name)
            val childRelativePath = if (relativePath == "/") "/${file.name}" else "$relativePath/${file.name}"

            if (file.isDirectory) {
                // 文件夹大小从 FolderSizeDb 缓存读取
                val folderSize = folderSizeDb().get(File(vaultDir, childRelativePath.trimStart('/')).absolutePath)?.size ?: 0L
                // 同步状态：递归统计子树
                val syncAgg = aggregateDirectChildren(childRelativePath)
                // 检测异常：uploadedSize > totalSize 说明 FolderSizeDb 缓存过时
                if (folderSize > 0 && syncAgg.uploadedSize > folderSize) {
                    anomalyPaths.add(childRelativePath)
                }
                entries.add(CloudFileEntry(
                    name = file.name,
                    relativePath = childRelativePath,
                    isDirectory = true,
                    totalSize = folderSize,
                    uploadedSize = syncAgg.uploadedSize,
                    uploadingSize = syncAgg.uploadingSize,
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

        // 合并云端-only 条目：cloud_entries 中有但本地没有的
        mergeCloudOnlyEntries(relativePath, localNames, entries)

        // 检测到文件夹大小异常时，异步触发重新计算
        if (anomalyPaths.isNotEmpty()) {
            state.sizeAnomalyPaths = anomalyPaths
            scope.launch(Dispatchers.IO) {
                for (path in anomalyPaths) {
                    val absolutePath = File(vaultDir, path.trimStart('/')).absolutePath
                    recalculateFolderSize(absolutePath)
                }
                // 重新计算完成后刷新列表
                withContext(Dispatchers.Main) {
                    state.sizeAnomalyPaths = emptySet()
                    navigateTo(state.currentPath)
                }
            }
        }

        return entries.sortedWith(naturalOrderComparator())
    }

    /** 递归统计子树中所有文件的同步状态 */
    private fun aggregateDirectChildren(relativePath: String): FolderAggregate {
        val dir = File(vaultDir, relativePath.trimStart('/'))
        if (!dir.exists() || !dir.isDirectory) return FolderAggregate()

        val children = dir.listFiles() ?: return FolderAggregate()
        var uploadedSize = 0L
        var uploadingSize = 0L

        for (file in children) {
            if (file.name in excludedFiles) continue
            val childPath = if (relativePath == "/") "/${file.name}" else "$relativePath/${file.name}"

            if (file.isDirectory) {
                val childAgg = aggregateDirectChildren(childPath)
                uploadedSize += childAgg.uploadedSize
                uploadingSize += childAgg.uploadingSize
            } else {
                val dbEntry = syncDb.getEntry("local_entries", childPath)
                val fileSize = file.length()
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
                        }
                    }
                    else -> {}
                }
            }
        }

        return FolderAggregate(0L, uploadedSize, uploadingSize)
    }

    /**
     * 从 cloud_entries 中查找当前目录下云端-only 的文件和文件夹，
     * 合并到 entries 列表中。
     */
    private fun mergeCloudOnlyEntries(
        relativePath: String,
        localNames: Set<String>,
        entries: MutableList<CloudFileEntry>
    ) {
        val cloudChildren = syncDb.getEntriesByParent("cloud_entries", relativePath)
        if (cloudChildren.isEmpty()) return

        val prefix = if (relativePath.endsWith("/")) relativePath else "$relativePath/"

        // 收集直接子级的云端文件和推断的云端文件夹
        val cloudDirectFiles = mutableMapOf<String, SyncEntryRow>()
        val cloudInferredDirs = mutableSetOf<String>()

        for (entry in cloudChildren) {
            val remainder = entry.path.removePrefix(prefix)
            if (remainder.isEmpty()) continue
            val slashIdx = remainder.indexOf('/')
            if (slashIdx < 0) {
                // 直接子级文件
                cloudDirectFiles[remainder] = entry
            } else {
                // 子级文件夹（从路径推断）
                cloudInferredDirs.add(remainder.substring(0, slashIdx))
            }
        }

        // 添加云端-only 文件
        for ((name, cloudEntry) in cloudDirectFiles) {
            if (name in localNames) continue
            val childRelativePath = if (relativePath == "/") "/$name" else "$relativePath/$name"
            entries.add(CloudFileEntry(
                name = name,
                relativePath = childRelativePath,
                isDirectory = false,
                totalSize = cloudEntry.size,
                uploadedSize = cloudEntry.size,  // 云端文件视为已上传
                uploadingSize = 0,
                lastModified = parseCloudLastModified(cloudEntry.lastModified),
                syncStatus = SyncStatus.COMPLETED,
                isCloudOnly = true
            ))
        }

        // 添加云端-only 文件夹
        for (dirName in cloudInferredDirs) {
            if (dirName in localNames) continue
            val childRelativePath = if (relativePath == "/") "/$dirName" else "$relativePath/$dirName"
            val dirSize = aggregateCloudFolderSize(childRelativePath)
            entries.add(CloudFileEntry(
                name = dirName,
                relativePath = childRelativePath,
                isDirectory = true,
                totalSize = dirSize,
                uploadedSize = dirSize,  // 云端文件夹视为已上传
                uploadingSize = 0,
                lastModified = 0,
                isCloudOnly = true
            ))
        }
    }

    /** 递归聚合云端文件夹下所有文件的总大小 */
    private fun aggregateCloudFolderSize(relativePath: String): Long {
        val cloudChildren = syncDb.getEntriesByParent("cloud_entries", relativePath)
        val prefix = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
        var totalSize = 0L
        for (entry in cloudChildren) {
            val remainder = entry.path.removePrefix(prefix)
            if (remainder.isEmpty()) continue
            // 只累加直接文件（不含子文件夹的文件，getEntriesByParent 已返回所有子孙）
            if ('/' !in remainder) {
                totalSize += entry.size
            }
        }
        return totalSize
    }

    /** 解析云端 lastModified 字符串为 epoch millis */
    private fun parseCloudLastModified(lastModified: String): Long {
        return try {
            java.time.Instant.parse(lastModified).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    /** 自然排序比较器：文件夹优先，然后按名称自然排序（数字按数值比较） */
    private fun naturalOrderComparator(): Comparator<CloudFileEntry> {
        return compareBy<CloudFileEntry> { !it.isDirectory }
            .thenComparator { a, b -> naturalCompare(a.name, b.name) }
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
                val folderSize = folderSizeDb().get(File(vaultDir, relativePath.trimStart('/')).absolutePath)?.size ?: old.totalSize
                val syncAgg = aggregateDirectChildren(relativePath)
                old.copy(totalSize = folderSize, uploadedSize = syncAgg.uploadedSize, uploadingSize = syncAgg.uploadingSize)
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
                val folderSize = folderSizeDb().get(File(vaultDir, parent.trimStart('/')).absolutePath)?.size ?: entries[idx].totalSize
                val syncAgg = aggregateDirectChildren(parent)
                entries[idx] = entries[idx].copy(
                    totalSize = folderSize,
                    uploadedSize = syncAgg.uploadedSize,
                    uploadingSize = syncAgg.uploadingSize
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
