package com.whmdg.mczj.tools.ui.filemanager

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.whmdg.mczj.tools.encryption.core.FileCodec
import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
import com.whmdg.mczj.tools.encryption.data.SyncDatabase
import com.whmdg.mczj.tools.encryption.data.SyncEntryRow
import com.whmdg.mczj.tools.encryption.data.SyncStatus
import com.whmdg.mczj.tools.encryption.data.UploadStatus
import com.whmdg.mczj.tools.encryption.data.VaultSyncIndex
import com.whmdg.mczj.tools.fileop.sync.SyncEngine
import com.whmdg.mczj.tools.fileop.sync.CloudSyncForegroundService
import com.whmdg.mczj.tools.fileop.sync.SyncOverlayBubble
import com.whmdg.mczj.tools.fileop.sync.SyncFileProgress
import com.whmdg.mczj.tools.fileop.sync.SyncMode
import com.whmdg.mczj.tools.fileop.sync.SyncPhase
import com.whmdg.mczj.tools.fileop.sync.SyncTaskState
import com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient
import com.whmdg.mczj.tools.fileop.webdav.WebDavServerConfig
import com.whmdg.mczj.tools.util.DiagnosticLog
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
    private val vaultSession: com.whmdg.mczj.tools.encryption.services.VaultSession? = null
) {
    val state = CloudPanelState()

    private val webdavClient = WebDavFileClient(webdavConfig)
    private var syncJob: Job? = null
    private var downloadJob: Job? = null
    // 每次访问都经 getInstance 校验底层文件是否仍存在：文件被删除/替换后
    // 旧实例的文件描述符已失效，需重建（支持 init 前的云端索引恢复）。
    private val syncDb: SyncDatabase get() = SyncDatabase.getInstance(context, vaultName)

    // 补全同步记录时临时持有的会话（钥匙）。仅在本轮扫描内复用，扫描结束即清零销毁。
    private var backfillSession: com.whmdg.mczj.tools.encryption.services.VaultSession? = null

    // ── 目录级校验队列 ──
    // 以"文件夹"为单位做本地校验：每次只校验一个目录的直接子项，
    // 用户点开的目录会被插到队首优先处理，其余目录在后台按顺序推进。
    // 这样避免打开卡片时全量 walkTopDown 阻塞首帧，同时保留全量语义。
    private val folderQueue = ArrayDeque<String>()
    private val queueLock = Any()
    /** 本次会话内已完成校验的目录（相对路径，"/" 为根）。 */
    private val validatedDirs = mutableSetOf<String>()
    /** 等待某目录校验完成的信号（navigateTo 需要阻塞到目标目录就绪）。 */
    private val dirCompletion = mutableMapOf<String, CompletableDeferred<Unit>>()
    /** 后台扫描消费协程，全局仅一个。 */
    private var scanJob: Job? = null
    /** 用户取消过 MD5 补全时置位，本轮不再重复弹框（对齐旧的"只弹一次"语义）。 */
    private var backfillDeclined = false

    /** 云盘面板状态（完全独立，使用 mutableStateOf 驱动 Compose recomposition） */
    class CloudPanelState {
        var currentPath by mutableStateOf("/")
        var entries by mutableStateOf<List<CloudFileEntry>>(emptyList())
        var isLoading by mutableStateOf(false)
        var loadError by mutableStateOf<Throwable?>(null)
        /** 加载/校验进度（null=无进度显示）。用于在转圈下方展示当前阶段与进度。 */
        var loadProgress by mutableStateOf<LoadProgress?>(null)
        var selectedPaths by mutableStateOf<Set<String>>(emptySet())
        var syncTask by mutableStateOf(SyncTaskState())
        var vaultFolderName by mutableStateOf("")
        /** 同步弹窗是否可见（false=隐藏为悬浮窗或关闭） */
        var syncDialogVisible by mutableStateOf(false)
        /** 是否已使用系统级悬浮球（有 OVERLAY 权限时）。为 true 时不再渲染应用内悬浮球 */
        var overlayBubbleActive by mutableStateOf(false)
        /** 上传确认对话框（跳过已完成 / 全部重新上传） */
        var uploadConfirmDialog by mutableStateOf<UploadConfirmState?>(null)
        /** 取消上传回调（由弹窗 ✕ 按钮调用） */
        var onCancelUpload: (() -> Unit)? = null
        /** 是否已完成首次初始化扫描 */
        var isInitialized by mutableStateOf(false)
        /** 已删除文件确认对话框 */
        var deletedFilesDialog by mutableStateOf<DeletedFilesState?>(null)
        /** 上传冲突确认对话框 */
        var uploadConflictDialog by mutableStateOf<UploadConflictState?>(null)
        /** 进度异常弹窗（为 null 时隐藏） */
        var anomalyDialogInfo by mutableStateOf<AnomalyDialogInfo?>(null)
        /** 上传功能是否被禁用（用户取消下载覆盖时设置） */
        var uploadDisabled by mutableStateOf(false)
        /** 文件夹大小异常：需要重新计算的路径集合 */
        var sizeAnomalyPaths by mutableStateOf<Set<String>>(emptySet())
        /** cloud.db 同步弹窗状态（null=隐藏） */
        var cloudDbSyncState by mutableStateOf<CloudDbSyncState?>(null)
        /** 下载冲突确认对话框（null=隐藏） */
        var downloadConflictDialog by mutableStateOf<DownloadConflictState?>(null)
        /** 补全同步记录所需的密码输入框（null=隐藏） */
        var passwordDialog by mutableStateOf<PasswordDialogState?>(null)
        /** 是否正在后台校验本地文件（校验期间禁止上传/下载） */
        var isValidating by mutableStateOf(false)
    }

    /** 补充缺失的明文校验值：请求密码 → 校验取钥匙 → 纯内存解密计算。 */
    data class PasswordDialogState(
        val message: String,
        val busy: Boolean = false,
        val error: String? = null,
        val onSubmit: (String) -> Unit,
        val onCancel: () -> Unit
    )

    /** 加载/校验进度：在转圈下方展示当前阶段与进度 */
    data class LoadProgress(
        /** 第一行：当前正在做什么（如"正在计算 MD5"） */
        val reason: String,
        /** 第二行：已处理数量 */
        val current: Int = 0,
        /** 第二行：总数量（<=0 表示未知） */
        val total: Int = 0,
        /** 第二行：当前处理中的文件（可为空） */
        val currentFile: String = ""
    )

    /** cloud.db 同步弹窗状态 */
    data class CloudDbSyncState(
        val phase: String = "正在加密",  // "正在加密" / "正在上传" / "正在验证"
        val isError: Boolean = false,
        val errorMessage: String = "",
        val onRetry: () -> Unit = {},
        val onConfirm: () -> Unit = {}
    )

    data class AnomalyDialogInfo(
        val summary: String,
        val detail: String
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

    data class ConflictFileInfo(
        val path: String,
        val localSize: Long,
        val localModified: String,
        val cloudSize: Long,
        val cloudModified: String,
        /** 冲突原因（按判定经过的层级依次列出），如 ["大小不同"] 或 ["最后修改时间不同", "MD5 不同"] */
        val reasons: List<String> = emptyList()
    )

    data class UploadConflictState(
        val conflicts: List<ConflictFileInfo>,
        val onConfirm: (overwrite: Boolean) -> Unit
    )

    /** 下载冲突确认状态：本地与云端同名但哈希不同，等待用户选择覆盖或跳过 */
    data class DownloadConflictState(
        val path: String,
        val localSize: Long,
        val localModified: String,
        val cloudSize: Long,
        val cloudModified: String,
        /** 冲突原因（按判定经过的层级依次列出） */
        val reasons: List<String> = emptyList(),
        /** true=覆盖本地，false=跳过本次同步 */
        val onConfirm: (overwrite: Boolean) -> Unit
    )

    /** 文件/文件夹条目（带聚合同步状态） */
    data class CloudFileEntry(
        val name: String,
        val relativePath: String,
        val isDirectory: Boolean,
        /** 文件：自身大小；文件夹：下所有文件总大小（含云端独有） */
        val totalSize: Long,
        /** 已上传完成的文件大小（绿色） */
        val uploadedSize: Long,
        /** 正在上传的剩余字节（黄色，自动计算：total - uploaded - red - blue） */
        val uploadingSize: Long,
        /** 本地未上传的文件大小（红色） */
        val redSize: Long = 0,
        /** 云端独有文件大小（蓝色） */
        val cloudOnlySize: Long = 0,
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
        // 仅首次初始化时进行校验。若需补全记录会阻塞式弹出密码框，
        // 因此先进入 loading 态（转圈背景），根目录校验完成后再渲染列表，
        // 避免"列表已出现却又弹框"造成的误导。
        // 根目录校验完成后，剩余目录交给后台队列异步推进，不阻塞 UI。
        if (!state.isInitialized) {
            state.isInitialized = true
            state.isLoading = true
            state.loadProgress = LoadProgress(reason = "正在校验本地文件")
            scope.launch {
                awaitFolderValidated("/")
                navigateTo("/")
            }
        } else {
            navigateTo("/")
        }
    }

    /** 导航代次：只接受最新一次导航的结果，丢弃过期导航的写入 */
    private var navigationGeneration = 0

    /** 导航到本地保险箱内的相对路径 */
    fun navigateTo(path: String) {
        // 先更新 currentPath，再进行耗时的目录加载：
        // 避免加载期间其它协程读取到过期的旧路径（例如异常重算回调用它刷新，会把用户弹回上级目录）
        val generation = ++navigationGeneration
        state.currentPath = path
        DiagnosticLog.log("CloudPane", "打开目录 path='$path' generation=$generation")
        scope.launch {
            state.isLoading = true
            state.loadError = null
            // 目标目录尚未校验时，插到队首优先校验，阻塞到完成再渲染；
            // 已校验则立即返回，不显示校验进度。
            if (normalizeDirPath(path) !in validatedDirs) {
                state.loadProgress = LoadProgress(reason = "正在校验本地文件")
                awaitFolderValidated(path)
                if (generation != navigationGeneration) return@launch
            }
            state.loadProgress = LoadProgress(reason = "正在加载目录")
            val startMs = System.currentTimeMillis()
            try {
                val entries = withContext(Dispatchers.IO) {
                    listLocalFiles(path)
                }
                // 过期导航：期间已发生新的导航，丢弃本次结果，避免覆盖新目录
                if (generation != navigationGeneration) {
                    DiagnosticLog.log("CloudPane", "丢弃过期导航 path='$path' generation=$generation")
                    return@launch
                }
                state.entries = entries
                DiagnosticLog.log(
                    "CloudPane",
                    "目录加载完成 path='$path' entries=${entries.size} 耗时=${System.currentTimeMillis() - startMs}ms"
                )
            } catch (e: Exception) {
                if (generation != navigationGeneration) return@launch
                state.loadError = e
                state.entries = emptyList()
                DiagnosticLog.log(
                    "CloudPane",
                    "目录加载失败 path='$path' 耗时=${System.currentTimeMillis() - startMs}ms ${e.javaClass.simpleName}: ${e.message}"
                )
            }
            if (generation == navigationGeneration) {
                state.isLoading = false
                state.loadProgress = null
            }
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
        DiagnosticLog.log("CloudPane", "请求上传 path='$relativePath'")
        // 后台校验期间禁止上传，避免与目录级校验并发写 DB
        if (state.isValidating) {
            android.widget.Toast.makeText(context, "正在校验本地文件，请稍候", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // 并发保护：检查是否有文件正在上传
        val uploading = syncDb.getEntriesByStatus("local_entries", SyncStatus.UPLOADING)
        if (uploading.isNotEmpty()) {
            DiagnosticLog.log("CloudPane", "上传被拒绝（已有上传任务进行中） path='$relativePath'")
            android.widget.Toast.makeText(context, "当前有文件正在上传，请等待完成", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val localFile = File(vaultDir, relativePath.trimStart('/'))
        if (!localFile.exists()) {
            DiagnosticLog.log("CloudPane", "上传失败：本地文件不存在 path='$relativePath'")
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
            val originalSize = localFile.length()
            syncDb.upsertEntry("local_entries", SyncEntryRow(
                path = relativePath,
                size = originalSize,
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
                            state.anomalyDialogInfo = AnomalyDialogInfo(
                                summary = "检测到本次上传进度异常（累计${anomalyCount}次增量超限），已自动终止上传以保护数据安全。已上传的文件不受影响，未上传的文件已重置为待上传状态。",
                                detail = "异常日志: ${anomalyLogFile.absolutePath}"
                            )
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

                        // 关闭进度弹窗，上传 cloud.db（自带弹窗）
                        closeProgressDialog()
                        uploadCloudDbWithUI()
                        // 无论成功失败都删除锁文件
                        com.whmdg.mczj.tools.AppDataPaths.syncLock(context, vaultId).delete()

                        // 上传完成后刷新当前目录，重新判断冲突状态，合并本地+云端条目
                        navigateTo(state.currentPath)
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
          // 标记是否正常跑完（完成 → 清通知；取消/异常 → 保留终态）
          var completedNormally = false
          try {
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

            // ④ 已上传（COMPLETED）且本地仍存在的文件视为无需重传（密文不变）
            val localFileMap = localFiles.associateBy {
                "/" + it.relativeTo(File(vaultDir)).path.replace('\\', '/')
            }
            val validCompletedPaths = mutableSetOf<String>()
            for (dbEntry in completedDbEntries) {
                if (localFileMap.containsKey(dbEntry.path)) {
                    validCompletedPaths.add(dbEntry.path)
                }
                // 本地不存在的不在这里处理，后面删除检测会处理
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

            // ⑥ 检测上传冲突：local.status=PENDING 且 cloud.db 中存在，且明文 MD5 不同
            val conflicts = mutableListOf<ConflictFileInfo>()
            val skippedByHash = mutableSetOf<String>()  // 明文 MD5 相同自动跳过的文件
            withContext(Dispatchers.IO) {
                for ((_, relPath) in toUpload) {
                    val localEntry = syncDb.getEntry("local_entries", relPath)
                    val cloudEntry = syncDb.getEntry("cloud_entries", relPath)

                    if (localEntry != null && localEntry.status == SyncStatus.PENDING && cloudEntry != null) {
                        val localMd5 = localEntry.md5
                        val cloudMd5 = cloudEntry.md5
                        if (localMd5 != null && localMd5 == cloudMd5) {
                            // 明文 MD5 相同 → 同一文件，标记为已同步
                            syncDb.updateEntry("local_entries", relPath) { entry ->
                                entry.copy(
                                    status = SyncStatus.COMPLETED,
                                    lastSyncTime = Instant.now().toString()
                                )
                            }
                            skippedByHash.add(relPath)
                        } else {
                            conflicts.add(ConflictFileInfo(
                                path = relPath,
                                localSize = localEntry.size,
                                localModified = localEntry.lastModified,
                                cloudSize = cloudEntry.size,
                                cloudModified = cloudEntry.lastModified,
                                reasons = listOf("MD5 不同")
                            ))
                        }
                    }
                }
            }

            // 若有冲突，询问用户是否覆盖
            if (conflicts.isNotEmpty()) {
                val overwrite = suspendCancellableCoroutine<Boolean> { cont ->
                    state.uploadConflictDialog = UploadConflictState(
                        conflicts = conflicts,
                        onConfirm = { overwrite -> cont.resume(overwrite) {} }
                    )
                }
                if (!overwrite) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "已取消上传", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    closeProgressDialog()
                    return@launch
                }
            }

            // ⑦ 若有已删除文件，询问用户
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

            // ⑧ 检查是否有正在上传的文件
            if (withContext(Dispatchers.IO) {
                    syncDb.getEntriesByStatus("local_entries", SyncStatus.UPLOADING).isNotEmpty()
                }) {
                withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, "当前有文件正在上传，请等待完成", android.widget.Toast.LENGTH_SHORT).show() }
                closeProgressDialog()
                return@launch
            }

            // ⑨ 询问用户：跳过已完成 or 全部重传
            val reUploadAll = if (completedFiles.isNotEmpty()) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    state.uploadConfirmDialog = UploadConfirmState(
                        completedCount = completedFiles.size,
                        totalCount = localFiles.size,
                        onComplete = { reUpload -> cont.resume(reUpload) {} }
                    )
                }
            } else false

            // ⑩ 构建最终队列
            val finalQueue: List<Pair<File, String>>
            if (reUploadAll) {
                // 全部重传：completedFiles + toUpload
                withContext(Dispatchers.IO) {
                    for ((file, relPath) in completedFiles) {
                        syncDb.updateStatus("local_entries", relPath, SyncStatus.QUEUED)
                        syncDb.updateUploadedSize("local_entries", relPath, 0)
                    }
                }
                finalQueue = completedFiles + toUpload.filter { (_, relPath) -> relPath !in skippedByHash }
            } else {
                // 跳过已完成：重新获取 PENDING 文件（包含刚重置的）
                val pendingAfterCheck = withContext(Dispatchers.IO) {
                    syncDb.getEntriesByStatus("local_entries", SyncStatus.PENDING)
                        .filter { it.path.startsWith(prefix) && !it.path.endsWith("/") }
                }
                // 重新扫描本地文件，构建路径到文件的映射
                val localFileMap = localFiles.associateBy {
                    "/" + it.relativeTo(File(vaultDir)).path.replace('\\', '/')
                }
                finalQueue = pendingAfterCheck
                    .filter { it.path !in skippedByHash }
                    .mapNotNull { entry -> localFileMap[entry.path]?.let { it to entry.path } }
            }

            // ⑪ 将队列中未录入 DB 的文件写入
            withContext(Dispatchers.IO) {
                for ((file, relPath) in finalQueue) {
                    val existing = syncDb.getEntry("local_entries", relPath)
                    if (existing == null) {
                        val originalSize = file.length()
                        syncDb.upsertEntry("local_entries", SyncEntryRow(
                            path = relPath,
                            size = originalSize,
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

            if (finalQueue.isEmpty()) {
                // 完全关闭弹窗（与上传完成同样的关闭方式）
                silentRefresh()
                withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, "所有文件已上传完成", android.widget.Toast.LENGTH_SHORT).show() }
                closeProgressDialog()
                return@launch
            }

            // ⑫ 静默刷新当前目录（不闪 loading）
            silentRefresh()

            withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, "开始上传 ${finalQueue.size} 个文件", android.widget.Toast.LENGTH_SHORT).show() }

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

            // ⑮ 显示同步弹窗 + 初始化状态栏
            val maxConcurrency = context.getSharedPreferences("cloud_sync_settings", Context.MODE_PRIVATE)
                .getInt("max_concurrency", 3)
            state.onCancelUpload = ::cancelUpload
            state.syncTask = SyncTaskState(
                phase = SyncPhase.SYNCING,
                totalFiles = finalQueue.size,
                totalBytes = finalQueue.sumOf { it.first.length() },
                concurrency = maxConcurrency
            )
            openProgressDialog()

            // 前台 Service + 唤醒锁保活（始终启用）；有通知权限时通知栏可见进度，
            // 无权限时系统自动抑制通知，仅保留保活效果。
            val uploadTotalBytes = state.syncTask.totalBytes
            val uploadStartMs = System.currentTimeMillis()
            CloudSyncForegroundService.start(context, "正在上传 ${finalQueue.size} 个文件")
            CloudSyncForegroundService.update(0f, 0L, uploadTotalBytes, 0L, 0L)

            // ⑯ 并发动态上传（Channel 单写者模式，避免多线程竞态）
            val completedBytes = java.util.concurrent.atomic.AtomicLong(0)
            val activeFileBytes = java.util.concurrent.ConcurrentHashMap<String, Long>()
            val fileSizes = java.util.concurrent.ConcurrentHashMap<String, Long>()
            finalQueue.forEach { (file, path) -> fileSizes[path] = file.length() }
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
                                        appendLine("queueIndex=$queueIndex, finalQueue.size=${finalQueue.size}")
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
                                    state.anomalyDialogInfo = AnomalyDialogInfo(
                                        summary = "检测到进度异常回退（transferred 从 ${lastTransferredBytes} 降至 $transferred），已自动终止上传以保护数据安全。",
                                        detail = diagInfo
                                    )
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
                                // 同步刷新前台通知（百分比 / 已传 / 总量 / 已用时间 / 平均速度）
                                if (uploadTotalBytes > 0) {
                                    val elapsedMs = System.currentTimeMillis() - uploadStartMs
                                    val percent = (transferred.toDouble() / uploadTotalBytes).toFloat()
                                    val avgSpeed = if (elapsedMs > 0) transferred * 1000 / elapsedMs else 0L
                                    CloudSyncForegroundService.update(percent, transferred, uploadTotalBytes, elapsedMs, avgSpeed)
                                    SyncOverlayBubble.update(formatSyncPercent(state.syncTask))
                                }
                                // 只更新文件自身进度条（文件夹聚合在 Complete 时更新）
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
                                            state.anomalyDialogInfo = AnomalyDialogInfo(
                                                summary = "检测到本次上传进度异常（累计${anomalyCount}次增量超限），已自动终止上传以保护数据安全。已上传的文件不受影响，未上传的文件已重置为待上传状态。",
                                                detail = "异常日志: ${anomalyLogFile.absolutePath}"
                                            )
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
                                fileSizes.remove(event.path)  // 清除文件大小记录
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
                                        appendLine("queueIndex=$queueIndex, finalQueue.size=${finalQueue.size}")
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
                                    state.anomalyDialogInfo = AnomalyDialogInfo(
                                        summary = "检测到进度异常回退（transferred 从 ${lastTransferredBytes} 降至 $transferred），已自动终止上传以保护数据安全。",
                                        detail = diagInfo
                                    )
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
                                // 完整更新文件状态（含 DB 读取），内部对父文件夹做全量重算
                                updateSingleEntry(event.path)
                            }
                            is UploadEvent.StatusChange -> {
                                // 文件开始上传：完整更新文件状态，内部对父文件夹做全量重算
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

            while (queueIndex < finalQueue.size || activeWorkers > 0) {
                while (activeWorkers >= maxConcurrency && queueIndex < finalQueue.size) {
                    delay(100)
                }

                if (queueIndex < finalQueue.size && activeWorkers < maxConcurrency) {
                    val idx = queueIndex++
                    val (file, relPath) = finalQueue[idx]
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
            // 终态强制刷新一次通知，避免停在两次节流之间的中间值
            if (uploadTotalBytes > 0) {
                val elapsedMs = System.currentTimeMillis() - uploadStartMs
                val avgSpeed = if (elapsedMs > 0) state.syncTask.transferredBytes * 1000 / elapsedMs else 0L
                CloudSyncForegroundService.update(
                    state.syncTask.overallProgress,
                    state.syncTask.transferredBytes,
                    uploadTotalBytes,
                    elapsedMs,
                    avgSpeed,
                    force = true
                )
            }
            completedNormally = true

            // ⑰ Toast 提示
            val msg = "文件夹上传完成: 成功${successCount}个" + if (failCount > 0) "，失败${failCount}个" else ""
            withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show() }

            // ⑱ 更新云盘卡片数据（云端大小、文件数、同步时间）
            withContext(Dispatchers.IO) {
                val cloudSize = syncDb.getSyncedSize("cloud_entries")
                val cloudFileCount = syncDb.getCompletedFileCount("cloud_entries")
                val now = java.time.Instant.now().toString()
                com.whmdg.mczj.tools.ui.encryption.CloudSyncStore.update(context, "vault_$vaultId") { item ->
                    item.copy(
                        cloudSize = cloudSize,
                        cloudFileCount = cloudFileCount,
                        lastSyncTime = now
                    )
                }
            }

            // ⑲ 关闭进度弹窗，上传 cloud.db（自带弹窗）
            closeProgressDialog()
            uploadCloudDbWithUI()
            // 无论成功失败都删除锁文件
            com.whmdg.mczj.tools.AppDataPaths.syncLock(context, vaultId).delete()
          } finally {
            // 正常完成 → 清通知；取消/异常 → 保留最终进度，均撤销前台/唤醒锁/悬浮球
            SyncOverlayBubble.dismiss()
            state.overlayBubbleActive = false
            CloudSyncForegroundService.finish(context, success = completedNormally)
          }
        }
    }

    // ══════════════════════ 下载 ══════════════════════

    /**
     * 下载入口：文件直接下载，文件夹递归收集云端文件后逐个下载。
     *
     * 复用上传的进度弹窗 / 悬浮窗（SyncProgressDialog / SyncFloatingBubble），
     * 仅将 syncTask.mode 设为 CLOUD_TO_LOCAL，箭头自动变为 ↓。
     */
    fun downloadEntry(relativePath: String, isDirectory: Boolean) {
        DiagnosticLog.log("CloudPane", "请求下载 path='$relativePath' isDir=$isDirectory")
        // 后台校验期间禁止下载，避免与目录级校验并发写 DB
        if (state.isValidating) {
            android.widget.Toast.makeText(context, "正在校验本地文件，请稍候", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // 并发保护：上传/下载进行中不允许再次触发
        val busy = syncDb.getEntriesByStatus("local_entries", SyncStatus.UPLOADING).isNotEmpty()
        if (busy) {
            DiagnosticLog.log("CloudPane", "下载被拒绝（有上传任务进行中） path='$relativePath'")
            android.widget.Toast.makeText(context, "当前有任务正在进行，请等待完成", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        downloadJob?.cancel()
        downloadJob = scope.launch {
            // 收集云端待下载文件（相对路径 + 云端大小），按自然顺序
            val cloudFiles = withContext(Dispatchers.IO) {
                val all = syncDb.getAllEntries("cloud_entries")
                    .filter { !it.path.endsWith("/") }
                val prefix = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
                val selected = if (isDirectory) {
                    all.filter { it.path.startsWith(prefix) }
                } else {
                    all.filter { it.path == relativePath }
                }
                selected.sortedWith(naturalOrderFileName)
            }

            if (cloudFiles.isEmpty()) {
                android.widget.Toast.makeText(context, "云端没有可下载的文件", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }

            val totalBytes = cloudFiles.sumOf { it.size }

            // 初始化下载任务状态（复用上传进度 UI，mode=下载）
            state.onCancelUpload = ::cancelDownload
            state.syncTask = SyncTaskState(
                phase = SyncPhase.SYNCING,
                mode = SyncMode.CLOUD_TO_LOCAL,
                totalFiles = cloudFiles.size,
                totalBytes = totalBytes,
                concurrency = 1
            )
            openProgressDialog()

            // 前台 Service + 唤醒锁保活（与上传一致，始终启用）
            val downloadTotalBytes = totalBytes
            val downloadStartMs = System.currentTimeMillis()
            CloudSyncForegroundService.start(context, "正在下载 ${cloudFiles.size} 个文件")
            CloudSyncForegroundService.update(0f, 0L, downloadTotalBytes, 0L, 0L)

            var completedFiles = 0
            var skippedFiles = 0
            var transferredBytes = 0L

            var completedNormally = false
            try {
                for (cloudEntry in cloudFiles) {
                    currentCoroutineContext().ensureActive()
                    val relPath = cloudEntry.path
                    val fileName = relPath.substringAfterLast('/')

                    // 冲突检测：本地存在同名且未同步（PENDING）→ 红蓝双条
                    val localEntry = withContext(Dispatchers.IO) {
                        syncDb.getEntry("local_entries", relPath)
                    }
                    val hasConflict = localEntry != null && localEntry.status != SyncStatus.COMPLETED

                    if (hasConflict && localEntry != null) {
                        // 冲突原因：明文 MD5 不同
                        val reasons = listOf("MD5 不同")
                        val overwrite = suspendCancellableCoroutine<Boolean> { cont ->
                            state.downloadConflictDialog = DownloadConflictState(
                                path = relPath,
                                localSize = localEntry.size,
                                localModified = localEntry.lastModified,
                                cloudSize = cloudEntry.size,
                                cloudModified = cloudEntry.lastModified,
                                reasons = reasons,
                                onConfirm = { choice -> cont.resume(choice) {} }
                            )
                        }
                        state.downloadConflictDialog = null
                        if (!overwrite) {
                            // 跳过本次同步，继续下一个
                            skippedFiles++
                            transferredBytes += cloudEntry.size
                            state.syncTask = state.syncTask.copy(
                                completedFiles = completedFiles,
                                transferredBytes = transferredBytes
                            )
                            continue
                        }
                    }

                    // 下载单个文件（覆盖或新建，均直接写入磁盘）
                    val fileProgress = SyncFileProgress(
                        relativePath = relPath,
                        totalBytes = cloudEntry.size,
                        uploadedBytes = 0,
                        status = UploadStatus.PENDING
                    )
                    state.syncTask = state.syncTask.copy(
                        currentFileName = fileName,
                        fileProgress = state.syncTask.fileProgress + (relPath to fileProgress)
                    )

                    val remotePath = "$remoteBasePath/${relPath.trimStart('/')}"
                    val localFile = File(vaultDir, relPath.trimStart('/'))
                    val baseTransferred = transferredBytes

                    val success = withContext(Dispatchers.IO) {
                        try {
                            localFile.parentFile?.mkdirs()
                            webdavClient.downloadFile(remotePath, localFile) { done ->
                                val live = fileProgress.copy(uploadedBytes = done, status = UploadStatus.UPLOADING)
                                state.syncTask = state.syncTask.copy(
                                    transferredBytes = baseTransferred + done,
                                    fileProgress = state.syncTask.fileProgress + (relPath to live)
                                )
                            }
                            true
                        } catch (e: Exception) {
                            com.whmdg.mczj.tools.fileop.sync.CloudSyncLogger.logSyncError(
                                "CloudPane", "下载失败: $relPath", e
                            )
                            false
                        }
                    }

                    if (success) {
                        // 更新 local_entries 为与云端一致 → 绿色
                        withContext(Dispatchers.IO) {
                            val actualSize = localFile.length()
                            val actualModified = Instant.ofEpochMilli(localFile.lastModified()).toString()
                            syncDb.upsertEntry("local_entries", SyncEntryRow(
                                path = relPath,
                                size = actualSize,
                                uploadedSize = actualSize,
                                lastModified = actualModified,
                                md5 = cloudEntry.md5,
                                cloudHash = cloudEntry.cloudHash,
                                status = SyncStatus.COMPLETED,
                                lastSyncTime = Instant.now().toString(),
                                failReason = null
                            ))
                        }
                        completedFiles++
                        transferredBytes += cloudEntry.size
                    } else {
                        withContext(Dispatchers.IO) {
                            syncDb.updateStatus("local_entries", relPath, SyncStatus.PAUSED, "下载失败")
                        }
                        android.widget.Toast.makeText(context, "下载失败: $fileName", android.widget.Toast.LENGTH_SHORT).show()
                    }

                    // 清理本文件的内存进度
                    state.syncTask = state.syncTask.copy(
                        completedFiles = completedFiles,
                        transferredBytes = transferredBytes,
                        fileProgress = state.syncTask.fileProgress - relPath
                    )
                    if (downloadTotalBytes > 0) {
                        val elapsedMs = System.currentTimeMillis() - downloadStartMs
                        val percent = (transferredBytes.toDouble() / downloadTotalBytes).toFloat()
                        val avgSpeed = if (elapsedMs > 0) transferredBytes * 1000 / elapsedMs else 0L
                        CloudSyncForegroundService.update(percent, transferredBytes, downloadTotalBytes, elapsedMs, avgSpeed)
                        SyncOverlayBubble.update(formatSyncPercent(state.syncTask))
                    }
                }

                // 完成后刷新列表，冲突消除、条目变绿
                withContext(Dispatchers.Main) { navigateTo(state.currentPath) }
                val msg = buildString {
                    append("下载完成: 成功 ${completedFiles}/${cloudFiles.size} 个")
                    if (skippedFiles > 0) append("，跳过 $skippedFiles 个")
                }
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()

                // 关闭进度弹窗，上传 cloud.db（自带弹窗，无论是否修改都无害）
                closeProgressDialog()
                uploadCloudDbWithUI()
                com.whmdg.mczj.tools.AppDataPaths.syncLock(context, vaultId).delete()
                state.syncTask = SyncTaskState()
                state.onCancelUpload = null
                completedNormally = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                closeProgressDialog()
                state.syncTask = SyncTaskState()
                state.onCancelUpload = null
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "下载失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                SyncOverlayBubble.dismiss()
                state.overlayBubbleActive = false
                CloudSyncForegroundService.finish(context, success = completedNormally)
            }
        }
    }

    /** 取消下载：停止任务，已下载的文件不受影响 */
    fun cancelDownload() {
        val job = downloadJob
        downloadJob = null
        scope.launch {
            job?.cancel()
            job?.join()
            closeProgressDialog()
            state.downloadConflictDialog = null
            state.syncTask = SyncTaskState()
            state.onCancelUpload = null
            com.whmdg.mczj.tools.AppDataPaths.syncLock(context, vaultId).delete()
            silentRefresh()
            android.widget.Toast.makeText(context, "下载任务已终止", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 删除本地文件 + 从本地表移除 */
    fun deleteLocal(relativePath: String, onComplete: (() -> Unit)? = null) {
        DiagnosticLog.log("CloudPane", "请求删除本地 path='$relativePath'")
        scope.launch {
            withContext(Dispatchers.IO) {
                // 统计删除前的数量和大小
                val entries = if (relativePath.endsWith("/")) {
                    syncDb.getEntriesByParent("local_entries", relativePath)
                } else {
                    listOfNotNull(syncDb.getEntry("local_entries", relativePath))
                }
                val deletedCount = entries.count { !it.path.endsWith("/") }
                val deletedSize = entries.filter { !it.path.endsWith("/") }.sumOf { it.size }

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

                // 更新统计
                syncDb.adjustLocalStats(-deletedCount, -deletedSize)
            }
            navigateTo(state.currentPath)
            onComplete?.invoke()
        }
    }

    /** 删除云端文件 + 从云端表移除 */
    fun deleteCloud(relativePath: String, onComplete: (() -> Unit)? = null) {
        DiagnosticLog.log("CloudPane", "请求删除云端 path='$relativePath'")
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 收集本次要删除的云端条目（含传入路径自身及其整个子树）。
                    // 注意：文件夹的 relativePath 不以 "/" 结尾，不能靠 endsWith("/") 判断，
                    // 必须同时按前缀收集子树（文件夹）和精确匹配自身（文件）。
                    val selfEntry = syncDb.getEntry("cloud_entries", relativePath)
                    val subtree = syncDb.getEntriesByPrefix("cloud_entries", relativePath)
                    val entries = (listOfNotNull(selfEntry) + subtree).distinctBy { it.path }
                    val fileEntries = entries.filter { !it.path.endsWith("/") }
                    val deletedCount = fileEntries.size
                    val deletedSize = fileEntries.sumOf { it.size }

                    // 删除云端文件（WebDAV 支持递归删除文件夹）
                    val remotePath = "$remoteBasePath/${relativePath.trimStart('/')}"
                    try {
                        webdavClient.delete(remotePath)
                    } catch (e: Exception) {
                        // 404 说明文件不存在，跳过即可（已达到删除目的）
                        if (e.message?.contains("404") != true) {
                            throw e  // 其他错误继续抛出
                        }
                    }

                    // 删除云端表条目（递归删除子条目）
                    syncDb.deleteEntry("cloud_entries", relativePath)
                    syncDb.deleteEntriesByPrefix("cloud_entries", relativePath)

                    // 按条目颜色处理本地表：
                    //   绿色（local_entries 存在）→ 重置为待上传（红），清除残留进度
                    //   蓝色（local_entries 不存在，仅云端有）→ 无需处理
                    for (entry in fileEntries) {
                        val localEntry = syncDb.getEntry("local_entries", entry.path)
                        if (localEntry != null) {
                            syncDb.updateEntry("local_entries", entry.path) { row ->
                                row.copy(
                                    status = SyncStatus.PENDING,
                                    uploadedSize = 0,
                                    failReason = null
                                )
                            }
                        }
                    }

                    // 更新统计
                    syncDb.adjustCloudStats(-deletedCount, -deletedSize)

                    // 上传更新后的 cloud.db 到云端
                    uploadCloudDb()
                }
                navigateTo(state.currentPath)
                onComplete?.invoke()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "删除云端失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
                onComplete?.invoke()
            }
        }
    }

    /** 同时删除本地和云端 */
    fun deleteBoth(relativePath: String, onComplete: (() -> Unit)? = null) {
        DiagnosticLog.log("CloudPane", "请求同时删除本地+云端 path='$relativePath'")
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 统计删除前的数量和大小
                    val localEntries = if (relativePath.endsWith("/")) {
                        syncDb.getEntriesByParent("local_entries", relativePath)
                    } else {
                        listOfNotNull(syncDb.getEntry("local_entries", relativePath))
                    }
                    val cloudEntries = if (relativePath.endsWith("/")) {
                        syncDb.getEntriesByParent("cloud_entries", relativePath)
                    } else {
                        listOfNotNull(syncDb.getEntry("cloud_entries", relativePath))
                    }
                    val deletedLocalCount = localEntries.count { !it.path.endsWith("/") }
                    val deletedLocalSize = localEntries.filter { !it.path.endsWith("/") }.sumOf { it.size }
                    val deletedCloudCount = cloudEntries.count { !it.path.endsWith("/") }
                    val deletedCloudSize = cloudEntries.filter { !it.path.endsWith("/") }.sumOf { it.size }

                    // 删除本地文件
                    val localFile = File(vaultDir, relativePath.trimStart('/'))
                    if (localFile.exists()) {
                        if (localFile.isDirectory) {
                            // 递归删除文件夹，但跳过排除文件（保险箱元数据）
                            localFile.walkBottomUp().forEach { file ->
                                if (file.name !in excludedFiles) {
                                    file.delete()
                                }
                            }
                        } else {
                            // 文件：仅当不在排除列表时删除
                            if (localFile.name !in excludedFiles) {
                                localFile.delete()
                            }
                        }
                    }
                    // 删除云端文件（WebDAV 支持递归删除文件夹）
                    val remotePath = "$remoteBasePath/${relativePath.trimStart('/')}"
                    try {
                        webdavClient.delete(remotePath)
                    } catch (e: Exception) {
                        // 404 说明文件不存在，跳过即可（已达到删除目的）
                        if (e.message?.contains("404") != true) {
                            throw e  // 其他错误继续抛出
                        }
                    }

                    // 从两张表移除（递归删除子条目）
                    syncDb.deleteEntry("local_entries", relativePath)
                    syncDb.deleteEntriesByPrefix("local_entries", relativePath)
                    syncDb.deleteEntry("cloud_entries", relativePath)
                    syncDb.deleteEntriesByPrefix("cloud_entries", relativePath)

                    // 更新统计
                    syncDb.adjustLocalStats(-deletedLocalCount, -deletedLocalSize)
                    syncDb.adjustCloudStats(-deletedCloudCount, -deletedCloudSize)

                    // 上传更新后的 cloud.db 到云端
                    uploadCloudDb()
                }
                navigateTo(state.currentPath)
                onComplete?.invoke()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "删除失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
                onComplete?.invoke()
            }
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
        dismissOverlayBubble()
    }

    /** 隐藏进度弹窗。有 OVERLAY 权限时升级为系统级悬浮球，否则退回应用内悬浮球。 */
    fun hideProgressDialog() {
        state.syncDialogVisible = false
        if (SyncOverlayBubble.canShow(context)) {
            SyncOverlayBubble.show(context) { bringAppToFrontAndExpand() }
            SyncOverlayBubble.update(formatSyncPercent(state.syncTask))
            state.overlayBubbleActive = true
        }
    }

    /** 同步任务的动态进度文本，保留两位小数；扫描阶段显示文件数（与应用内悬浮球一致）。 */
    private fun formatSyncPercent(task: SyncTaskState): String =
        if (task.phase == SyncPhase.SCANNING) "${task.totalFiles}"
        else String.format("%.2f%%", task.overallProgress * 100)

    /** 显示进度弹窗（从悬浮窗恢复为弹窗，phase 不变） */
    fun showProgressDialog() {
        state.syncDialogVisible = true
        dismissOverlayBubble()
    }

    /** 收起系统级悬浮球并复位标志。 */
    private fun dismissOverlayBubble() {
        SyncOverlayBubble.dismiss()
        state.overlayBubbleActive = false
    }

    /** 点击系统级悬浮球：拉回本应用并展开进度弹窗（相当于退出最小化）。 */
    private fun bringAppToFrontAndExpand() {
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launch != null) {
                launch.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                context.startActivity(launch)
            }
        } catch (_: Exception) {
        }
        showProgressDialog()
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

                // 切换状态：正在上传
                withContext(Dispatchers.Main) {
                    state.cloudDbSyncState = state.cloudDbSyncState?.copy(phase = "正在上传")
                }

                // 使用封装的上传函数
                val configFile = File(vaultDir, "vault_config.json")
                com.whmdg.mczj.tools.ui.encryption.CloudVaultCatalogSync.uploadVaultDatabase(
                    context = context,
                    client = webdavClient,
                    configPath = webdavConfig.relativePath,
                    vaultName = vaultName,
                    dbFile = dbFile,
                    configFile = configFile
                )

                // 切换状态：正在验证
                withContext(Dispatchers.Main) {
                    state.cloudDbSyncState = state.cloudDbSyncState?.copy(phase = "正在验证")
                }

                // 保存远程元数据
                val remotePath = webdavConfig.relativePath.trimEnd('/').let { base ->
                    if (base.isEmpty()) "/.sync_meta/${vaultName}_vault_sync.db.7z"
                    else "$base/.sync_meta/${vaultName}_vault_sync.db.7z"
                }
                val remoteMeta = webdavClient.getFileMetadata(remotePath)
                if (remoteMeta != null) {
                    saveCloudDbMeta(remoteMeta.size, remoteMeta.lastModified)
                }
                CloudDbResult.Success
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

            // 使用封装的上传函数
            val configFile = File(vaultDir, "vault_config.json")
            com.whmdg.mczj.tools.ui.encryption.CloudVaultCatalogSync.uploadVaultDatabase(
                context = context,
                client = webdavClient,
                configPath = webdavConfig.relativePath,
                vaultName = vaultName,
                dbFile = dbFile,
                configFile = configFile
            )

            // 保存远程元数据
            val remotePath = webdavConfig.relativePath.trimEnd('/').let { base ->
                if (base.isEmpty()) "/.sync_meta/${vaultName}_vault_sync.db.7z"
                else "$base/.sync_meta/${vaultName}_vault_sync.db.7z"
            }
            val remoteMeta = webdavClient.getFileMetadata(remotePath)
            if (remoteMeta != null) {
                saveCloudDbMeta(remoteMeta.size, remoteMeta.lastModified)
            }
            true
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

            val remotePath = webdavConfig.relativePath.trimEnd('/').let { base ->
                if (base.isEmpty()) "/.sync_meta/${vaultName}_vault_sync.db.7z"
                else "$base/.sync_meta/${vaultName}_vault_sync.db.7z"
            }
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
        val remotePath = webdavConfig.relativePath.trimEnd('/').let { base ->
            if (base.isEmpty()) "/.sync_meta/${vaultName}_vault_sync.db.7z"
            else "$base/.sync_meta/${vaultName}_vault_sync.db.7z"
        }
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

            // 读取远程 db 的 cloud_entries，整表替换本地 cloud_entries（云端为权威快照）
            val remoteDbFile = File(extractDir, "vault_sync.db")
            if (remoteDbFile.exists()) {
                syncDb.importCloudEntriesFromFile(remoteDbFile)
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
        try {
            // 使用封装的下载函数
            val (success, _) = com.whmdg.mczj.tools.ui.encryption.CloudVaultCatalogSync.downloadVaultDatabase(
                context = context,
                client = webdavClient,
                configPath = webdavConfig.relativePath,
                vaultName = vaultName,
                targetDb = syncDb
            )

            if (success) {
                // 保存远程元数据
                val remotePath = webdavConfig.relativePath.trimEnd('/').let { base ->
                    if (base.isEmpty()) "/.sync_meta/${vaultName}_vault_sync.db.7z"
                    else "$base/.sync_meta/${vaultName}_vault_sync.db.7z"
                }
                webdavClient.getFileMetadata(remotePath)?.let {
                    saveCloudDbMeta(it.size, it.lastModified)
                }
            }
            success
        } catch (e: Exception) {
            com.whmdg.mczj.tools.fileop.sync.CloudSyncLogger.logSync("CloudPane", "云端索引恢复失败: ${e.message}")
            false
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
        val remotePath = webdavConfig.relativePath.trimEnd('/').let { base ->
            if (base.isEmpty()) "/.sync_meta/${vaultName}_vault_sync.db.7z"
            else "$base/.sync_meta/${vaultName}_vault_sync.db.7z"
        }
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
            // 更新 local_entries（使用加密后的实际文件大小）
            val originalSize = localFile.length()
            syncDb.upsertEntry("local_entries", com.whmdg.mczj.tools.encryption.data.SyncEntryRow(
                path = file.path,
                size = originalSize,
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
        scope.launch {
            // 用户主动刷新：作废已完成标记，从根目录重新走一轮队列校验。
            // 根目录压入队尾保证全量覆盖，当前目录插队首优先就绪；
            // 仅阻塞等待当前目录，其余目录后台推进。
            resetValidationSession()
            synchronized(queueLock) { folderQueue.addLast("/") }
            awaitFolderValidated(state.currentPath)
            navigateTo(state.currentPath)
        }
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

    /** 重置校验会话：取消在跑的扫描，清空队列/已完成标记，允许下一轮重新全量校验。 */
    private fun resetValidationSession() {
        scanJob?.cancel()
        synchronized(queueLock) {
            scanJob = null
            folderQueue.clear()
            validatedDirs.clear()
            dirCompletion.values.forEach { it.complete(Unit) }
            dirCompletion.clear()
        }
        state.isValidating = false
        backfillDeclined = false
        hasNotifiedValidationDone = false
    }

    fun dispose() {
        disposed = true
        syncJob?.cancel()
        synchronized(queueLock) {
            dirCompletion.values.forEach { it.complete(Unit) }
            dirCompletion.clear()
            folderQueue.clear()
        }
        backfillSession?.dispose()
        backfillSession = null
    }

    // ── 内部方法 ──

    /**
     * 确保目标目录已校验；未校验则插到队首并等待其完成。
     * 若后台队列未启动则同时启动消费协程。
     */
    private suspend fun awaitFolderValidated(path: String) {
        val normalized = normalizeDirPath(path)
        val signal = synchronized(queueLock) {
            if (normalized in validatedDirs) null
            else {
                if (folderQueue.none { it == normalized }) folderQueue.addFirst(normalized)
                dirCompletion.getOrPut(normalized) { CompletableDeferred() }
            }
        } ?: return
        startScannerIfNeeded()
        signal.await()
    }

    /** 启动后台队列消费协程（全局唯一）。 */
    private fun startScannerIfNeeded() {
        synchronized(queueLock) {
            if (scanJob?.isActive == true) return
            state.isValidating = true
            scanJob = scope.launch {
                while (true) {
                    val next = synchronized(queueLock) { folderQueue.removeFirstOrNull() }
                        ?: break
                    try {
                        validateFolder(next)
                        // 仅在校验正常完成时标记；取消或异常不标记，后续访问可重新校验。
                        synchronized(queueLock) { validatedDirs.add(next) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DiagnosticLog.log("CloudPane", "目录校验失败 path='$next' ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        synchronized(queueLock) {
                            dirCompletion.remove(next)?.complete(Unit)
                        }
                    }
                }
                // 队列排空的收尾必须与 enqueue 互斥：若退出瞬间又有目录入队，
                // 则继续消费，避免该目录无人处理导致等待方永久挂起。
                // 两种情况都先置空 scanJob，使后续 enqueue 能重新启动消费。
                val hasMore = synchronized(queueLock) {
                    scanJob = null
                    if (folderQueue.isEmpty()) {
                        state.isValidating = false
                        dirCompletion.values.forEach { it.complete(Unit) }
                        dirCompletion.clear()
                        false
                    } else true
                }
                if (hasMore) startScannerIfNeeded() else if (!disposed) onScanQueueDrained()
            }
        }
    }

    /** 队列清空后的收尾：弹出校验完成提示。 */
    private fun onScanQueueDrained() {
        if (!hasNotifiedValidationDone) {
            hasNotifiedValidationDone = true
            android.widget.Toast.makeText(context, "本地文件校验完成", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private var hasNotifiedValidationDone = false
    /** 面板已销毁：抑制退出后的 Toast 等副作用。 */
    private var disposed = false

    /** 校验单个目录的直接子项，并把其子目录加入队尾等待后续校验。 */
    private suspend fun validateFolder(dirRel: String) {
        val dir = if (dirRel == "/") File(vaultDir) else File(vaultDir, dirRel.trimStart('/'))
        if (!dir.exists() || !dir.isDirectory) return

        val dirPrefix = if (dirRel == "/") "/" else "$dirRel/"

        // ① 读取磁盘上的直接子项
        val children = withContext(Dispatchers.IO) {
            dir.listFiles()?.filter { it.name !in excludedFiles }
        } ?: emptyList()

        val localChildPaths = mutableSetOf<String>()
        val childDirs = mutableListOf<String>()
        val needBackfill = mutableListOf<Pair<String, File>>()

        // ②③④ 磁盘比对 + DB 删除检测，全部在一次 IO 上下文内完成，
        //       避免逐文件 context switch（上万文件时是主要开销）。
        withContext(Dispatchers.IO) {
            for (child in children) {
                val childRel = "$dirPrefix${child.name}"
                localChildPaths.add(childRel)
                if (child.isDirectory) {
                    childDirs.add(childRel)
                    continue
                }
                val existing = syncDb.getEntry("local_entries", childRel)
                val currentSize = child.length()
                when {
                    existing == null -> needBackfill.add(childRel to child)
                    existing.md5.isNullOrEmpty() -> needBackfill.add(childRel to child)
                    existing.size != currentSize -> {
                        val currentLastModified = Instant.ofEpochMilli(child.lastModified()).toString()
                        syncDb.updateSize("local_entries", childRel, currentSize, currentLastModified)
                        syncDb.updateStatus("local_entries", childRel, SyncStatus.PENDING)
                    }
                }
            }

            // ④ 删除检测：以本目录为单位。DB 子树中凡所属"直接子项段"已不在磁盘上的，
            //    连同其整棵子树一并清理。按精确路径删除而非前缀匹配，
            //    避免相邻同名前缀（/a 与 /ab）被误删。
            val toDelete = syncDb.getEntriesByParent("local_entries", dirPrefix)
                .filter { entry ->
                    val seg = directChildSegmentOf(entry.path, dirPrefix)
                    seg != null && seg !in localChildPaths
                }
                .map { it.path }
            if (toDelete.isNotEmpty()) {
                syncDb.deleteEntries("local_entries", toDelete)
            }
        }

        // 现场补齐缺失的明文 MD5（沿用旧的阻塞式弹框，会话随后复用）
        if (needBackfill.isNotEmpty() && !backfillDeclined) {
            backfillMissing(needBackfill)
        }

        // ⑤ 子目录追加到队尾，等待后台顺序校验
        if (childDirs.isNotEmpty()) {
            synchronized(queueLock) {
                for (childDir in childDirs) {
                    if (childDir !in validatedDirs && folderQueue.none { it == childDir }) {
                        folderQueue.addLast(childDir)
                    }
                }
            }
        }
    }

    /** 规范化目录相对路径：统一以 "/" 开头且不以 "/" 结尾（根除外）。 */
    private fun normalizeDirPath(path: String): String {
        val trimmed = path.trimEnd('/')
        return if (trimmed.isEmpty()) "/" else trimmed
    }

    /**
     * 取 DB 条目相对父目录的直接子项路径。
     * 例：prefix="/"、path="/a/b.txt" → "/a"；prefix="/a/"、path="/a/b/c" → "/a/b"。
     * 返回值是磁盘上的完整相对路径（目录条目同样去掉末尾 "/"）。
     */
    private fun directChildSegmentOf(entryPath: String, dirPrefix: String): String? {
        if (!entryPath.startsWith(dirPrefix)) return null
        val rest = entryPath.removePrefix(dirPrefix).trimEnd('/')
        if (rest.isEmpty()) return null
        val seg = rest.substringBefore('/')
        return "$dirPrefix$seg"
    }

    /**
     * 补全缺失的明文 MD5：请求密码 → 校验并取得临时会话 → 纯内存流式解密逐个计算。
     * 用户取消或密码始终错误时不写任何记录（保持"表中有记录"的原有语义）。
     *
     * 会话（钥匙）跟随面板生命周期存活：本轮扫描不销毁，便于后续扫描直接复用；
     * 面板销毁（dispose）时统一清零。用户直接杀后台则随进程内存一并消失。
     */
    private suspend fun backfillMissing(targets: List<Pair<String, File>>) {
        val session = requestBackfillSession(targets.size)
        if (session == null) {
            // 用户取消：本轮不再重复弹框，保持"只弹一次"的交互语义
            backfillDeclined = true
            return
        }
        for ((relativePath, file) in targets) {
            try {
                val md5 = withContext(Dispatchers.IO) {
                    FileCodec.md5OfPlaintext(file, session.dek, session.record.customEncryption)
                }
                syncDb.upsertLocalMd5(
                    path = relativePath,
                    md5 = md5,
                    size = file.length(),
                    lastModified = Instant.ofEpochMilli(file.lastModified()).toString()
                )
            } catch (_: Exception) {
                // 单个文件失败不影响其余文件，也不写记录
            }
        }
    }

    /**
     * 阻塞式请求保险箱密码并换取临时会话。
     * 先去重缓存的会话；无则弹密码框，校验成功返回会话（缓存复用），失败允许重试，取消返回 null。
     */
    private suspend fun requestBackfillSession(missingCount: Int): com.whmdg.mczj.tools.encryption.services.VaultSession? {
        backfillSession?.let { return it }

        val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
        val session = suspendCancellableCoroutine<com.whmdg.mczj.tools.encryption.services.VaultSession?> { cont ->
            val message = "检测到 $missingCount 个本地文件缺少同步记录，需要密码来计算明文校验值。密码仅本次使用，不会保存。"
            state.passwordDialog = PasswordDialogState(
                message = message,
                onSubmit = { password ->
                    if (resumed.compareAndSet(false, true)) {
                        state.passwordDialog = state.passwordDialog?.copy(busy = true, error = null)
                        scope.launch(Dispatchers.IO) {
                            try {
                                val vaultService = com.whmdg.mczj.tools.encryption.services.VaultService(context)
                                vaultService.load()
                                val opened = vaultService.open(vaultId, password)
                                withContext(Dispatchers.Main) { cont.resume(opened) {} }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    resumed.set(false)
                                    state.passwordDialog = state.passwordDialog?.copy(
                                        busy = false,
                                        error = e.message ?: "密码错误"
                                    )
                                }
                            }
                        }
                    }
                },
                onCancel = {
                    if (resumed.compareAndSet(false, true)) cont.resume(null) {}
                }
            )
        }
        state.passwordDialog = null
        if (session != null) backfillSession = session
        return session
    }


    /**
     * 列出本地保险箱目录，合并云端-only 条目。
     * 文件夹大小累加整棵子树（与 updateSingleEntry / refreshParentAggregates 保持同一口径）。
     * 返回的列表已排序：文件夹在前，文件在后，自然排序。
     */
    private fun listLocalFiles(relativePath: String): List<CloudFileEntry> {
        val dir = File(vaultDir, relativePath.trimStart('/'))
        val entries = mutableListOf<CloudFileEntry>()
        val localNames = mutableSetOf<String>()
        val anomalyPaths = mutableSetOf<String>()

        // 本地目录不存在时跳过本地扫描，但仍继续合并云端条目（本地与云端是并列关系）
        val children = if (dir.exists() && dir.isDirectory) dir.listFiles() else null
        if (children == null) {
            // 本地不存在，直接走云端合并
            mergeCloudOnlyEntries(relativePath, localNames, entries)
            return entries.sortedWith(naturalOrderComparator())
        }

        for ((index, file) in children.withIndex()) {
            if (file.name in excludedFiles) continue
            localNames.add(file.name)
            val childRelativePath = if (relativePath == "/") "/${file.name}" else "$relativePath/${file.name}"

            if (file.isDirectory) {
                // 文件夹统计不显示进度，仅保留转圈
                // 文件夹大小：从 SyncDatabase 递归累加整个子树的原始文件大小（避免 FolderSizeDb 的加密文件膨胀问题）
                val prefix = if (childRelativePath.endsWith("/")) childRelativePath else "$childRelativePath/"
                val folderSize = syncDb.getEntriesByParent("local_entries", childRelativePath)
                    .filter { entry -> !entry.path.endsWith("/") }  // 累加整个子树的所有文件，不只是直接子文件
                    .sumOf { it.size }
                // 云端独有文件大小（排除本地也有的）
                val localPaths = syncDb.getEntriesByParent("local_entries", childRelativePath)
                    .map { it.path }.toSet()
                val cloudOnlyFolderSize = syncDb.getEntriesByParent("cloud_entries", childRelativePath)
                    .filter { !it.path.endsWith("/") && it.path !in localPaths }
                    .sumOf { it.size }
                // 同步状态：递归统计子树
                val syncAgg = aggregateDirectChildren(childRelativePath)
                // 检测异常：uploadedSize > totalSize 说明 DB 缓存过时
                if (folderSize > 0 && syncAgg.uploadedSize > folderSize) {
                    anomalyPaths.add(childRelativePath)
                }
                entries.add(CloudFileEntry(
                    name = file.name,
                    relativePath = childRelativePath,
                    isDirectory = true,
                    totalSize = folderSize + cloudOnlyFolderSize,
                    uploadedSize = syncAgg.uploadedSize,
                    uploadingSize = 0,
                    redSize = syncAgg.redSize,
                    cloudOnlySize = cloudOnlyFolderSize,
                    lastModified = file.lastModified()
                ))
            } else {
                // 文件：从 DB 查同步状态，优先用内存实时进度，回退到 DB 持久化进度
                val dbEntry = syncDb.getEntry("local_entries", childRelativePath)
                var status = dbEntry?.status ?: SyncStatus.PENDING

                // 优先使用 DB 中的原始文件大小，避免读取加密文件的膨胀大小
                val fileSize = dbEntry?.size ?: file.length()
                val liveProgress = state.syncTask.fileProgress[childRelativePath]
                var dbUploaded = dbEntry?.uploadedSize ?: 0L

                // 健康检查：清理孤儿状态（意外中断导致的残留进度）
                val isUploadActive = state.syncTask.phase == SyncPhase.SYNCING || state.syncTask.phase == SyncPhase.SCANNING
                if (!isUploadActive && dbEntry != null) {
                    val hasOrphanStatus = status == SyncStatus.UPLOADING || status == SyncStatus.QUEUED
                    val hasOrphanProgress = dbUploaded > 0 && status != SyncStatus.COMPLETED
                    if (hasOrphanStatus || hasOrphanProgress) {
                        syncDb.updateEntry("local_entries", childRelativePath) { row ->
                            row.copy(status = SyncStatus.PENDING, uploadedSize = 0)
                        }
                        status = SyncStatus.PENDING
                        dbUploaded = 0L
                    }
                }
                val greenSize = when {
                    status == SyncStatus.COMPLETED -> fileSize
                    liveProgress != null -> liveProgress.uploadedBytes
                    dbUploaded > 0 -> dbUploaded
                    else -> 0L
                }
                val redSize = when {
                    status == SyncStatus.COMPLETED -> 0L
                    status == SyncStatus.UPLOADING -> 0L  // 剩余部分归入 yellow（uploading），不计入 red
                    else -> fileSize
                }
                entries.add(CloudFileEntry(
                    name = file.name,
                    relativePath = childRelativePath,
                    isDirectory = false,
                    totalSize = fileSize,
                    uploadedSize = greenSize,
                    uploadingSize = 0,
                    redSize = redSize,
                    lastModified = file.lastModified(),
                    syncStatus = status
                ))
            }
        }

        // 合并云端-only 条目：cloud_entries 中有但本地没有的
        mergeCloudOnlyEntries(relativePath, localNames, entries)

        // 检测到文件夹大小异常（uploadedSize > folderSize，说明 SyncDatabase 索引不自洽）
        // 注意：这里不再触发 FolderSizeDb 重算并回调 navigateTo——原因有二：
        //   1) 该判据来自 SyncDatabase，而 recalculateFolderSize 只写 FolderSizeDb，无法消除异常，会导致每次进入都重演
        //   2) 脱离的协程在导航过程中回调 navigateTo(state.currentPath) 会读到过期路径，把用户弹回上级目录
        // 仅记录异常路径供 UI 提示，随后自动清除，不影响导航。
        if (anomalyPaths.isNotEmpty()) {
            state.sizeAnomalyPaths = anomalyPaths
            scope.launch {
                delay(1500L)
                if (state.sizeAnomalyPaths == anomalyPaths) {
                    state.sizeAnomalyPaths = emptySet()
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
        var redSize = 0L

        for (file in children) {
            if (file.name in excludedFiles) continue
            val childPath = if (relativePath == "/") "/${file.name}" else "$relativePath/${file.name}"

            if (file.isDirectory) {
                val childAgg = aggregateDirectChildren(childPath)
                uploadedSize += childAgg.uploadedSize
                redSize += childAgg.redSize
            } else {
                val dbEntry = syncDb.getEntry("local_entries", childPath)
                val fileSize = dbEntry?.size ?: file.length()
                when (dbEntry?.status) {
                    SyncStatus.COMPLETED -> uploadedSize += fileSize
                    SyncStatus.UPLOADING -> {
                        val liveProgress = state.syncTask.fileProgress[childPath]
                        val dbUploaded = dbEntry?.uploadedSize ?: 0L
                        val done = liveProgress?.uploadedBytes ?: dbUploaded
                        uploadedSize += done
                        // 剩余部分归入 yellow（uploading），不计入 red
                    }
                    else -> {
                        // 状态非已完成时，再核对明文 MD5：与云端一致则视为已同步（只判断，不写库）
                        val cloudEntry = syncDb.getEntry("cloud_entries", childPath)
                        val md5 = dbEntry?.md5
                        if (!md5.isNullOrEmpty() && md5 == cloudEntry?.md5) {
                            uploadedSize += fileSize
                        } else {
                            redSize += fileSize
                        }
                    }
                }
            }
        }

        return FolderAggregate(totalSize = uploadedSize + redSize, uploadedSize = uploadedSize, redSize = redSize)
    }

    /**
     * 从 cloud_entries 中查找当前目录下云端-only 的文件和文件夹，
     * 以及与本地冲突的文件（local.status=PENDING 且云端存在），合并到 entries 列表中。
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

        // 添加云端文件（云端-only 或冲突）
        for ((name, cloudEntry) in cloudDirectFiles) {
            val childRelativePath = if (relativePath == "/") "/$name" else "$relativePath/$name"

            // 检查本地是否存在且 status=PENDING（冲突）
            val localEntry = syncDb.getEntry("local_entries", childRelativePath)
            val isConflict = localEntry != null &&
                             localEntry.status == SyncStatus.PENDING &&
                             name in localNames

            if (isConflict && localEntry != null) {
                // 冲突文件：明文 MD5 相同 → 同一文件，直接合并为 COMPLETED
                val localMd5 = localEntry.md5
                val cloudMd5 = cloudEntry.md5
                if (localMd5 != null && localMd5 == cloudMd5) {
                    syncDb.updateEntry("local_entries", childRelativePath) { row ->
                        row.copy(
                            status = SyncStatus.COMPLETED,
                            lastSyncTime = java.time.Instant.now().toString()
                        )
                    }
                    // 同步更新屏上已入列的本地条目，使本轮即显绿色（而非等下次刷新）
                    val idx = entries.indexOfFirst { it.relativePath == childRelativePath && !it.isCloudOnly }
                    if (idx >= 0) {
                        val old = entries[idx]
                        entries[idx] = old.copy(
                            uploadedSize = old.totalSize,
                            redSize = 0L,
                            syncStatus = SyncStatus.COMPLETED
                        )
                    }
                    // 不添加云端条目（已合并，只显示本地绿色条目）
                    continue
                }
            }

            // 云端-only 或冲突（MD5 不同）时，添加云端条目
            // 冲突时：本地条目在 listLocalFiles 中已添加（显示在前），云端条目在此添加（显示在后）
            if (name !in localNames || isConflict) {
                entries.add(CloudFileEntry(
                    name = name,
                    relativePath = childRelativePath,
                    isDirectory = false,
                    totalSize = cloudEntry.size,
                    uploadedSize = 0,
                    uploadingSize = 0,
                    cloudOnlySize = cloudEntry.size,
                    lastModified = parseCloudLastModified(cloudEntry.lastModified),
                    syncStatus = SyncStatus.COMPLETED,
                    isCloudOnly = true
                ))
            }
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
                uploadedSize = 0,
                uploadingSize = 0,
                cloudOnlySize = dirSize,
                lastModified = 0,
                isCloudOnly = true
            ))
        }
    }

    /** 递归聚合云端文件夹下所有文件的总大小（累加整棵子树，与本地 folderSize 口径一致） */
    private fun aggregateCloudFolderSize(relativePath: String): Long {
        val cloudChildren = syncDb.getEntriesByParent("cloud_entries", relativePath)
        val prefix = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
        var totalSize = 0L
        for (entry in cloudChildren) {
            val remainder = entry.path.removePrefix(prefix)
            if (remainder.isEmpty()) continue
            // 累加整棵子树的所有文件（getEntriesByParent 已返回所有子孙）
            if (!entry.path.endsWith("/")) {
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
        val redSize = when {
            dbEntry?.status == SyncStatus.COMPLETED -> 0L
            dbEntry?.status == SyncStatus.UPLOADING -> 0L
            else -> fileSize
        }
        val newEntry = old.copy(
            uploadedSize = greenSize,
            redSize = redSize,
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
                // folderSize 与 listLocalFiles 保持同一口径：累加整棵子树的所有文件
                val folderSize = syncDb.getEntriesByParent("local_entries", relativePath)
                    .filter { entry -> !entry.path.endsWith("/") }
                    .sumOf { it.size }
                val syncAgg = aggregateDirectChildren(relativePath)
                val localPaths = syncDb.getEntriesByParent("local_entries", relativePath)
                    .map { it.path }.toSet()
                val cloudOnlyFolderSize = syncDb.getEntriesByParent("cloud_entries", relativePath)
                    .filter { !it.path.endsWith("/") && it.path !in localPaths }
                    .sumOf { it.size }
                old.copy(totalSize = folderSize + cloudOnlyFolderSize, uploadedSize = syncAgg.uploadedSize, redSize = syncAgg.redSize, cloudOnlySize = cloudOnlyFolderSize)
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
                val redSize = when {
                    dbEntry?.status == SyncStatus.COMPLETED -> 0L
                    dbEntry?.status == SyncStatus.UPLOADING -> 0L
                    else -> fileSize
                }
                old.copy(
                    uploadedSize = greenSize,
                    redSize = redSize,
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
                // folderSize 与 listLocalFiles 保持同一口径：累加整棵子树的所有文件
                val folderSize = syncDb.getEntriesByParent("local_entries", parent)
                    .filter { entry -> !entry.path.endsWith("/") }
                    .sumOf { it.size }
                val syncAgg = aggregateDirectChildren(parent)
                val localPaths = syncDb.getEntriesByParent("local_entries", parent)
                    .map { it.path }.toSet()
                val cloudOnlyFolderSize = syncDb.getEntriesByParent("cloud_entries", parent)
                    .filter { !it.path.endsWith("/") && it.path !in localPaths }
                    .sumOf { it.size }
                entries[idx] = entries[idx].copy(
                    totalSize = folderSize + cloudOnlyFolderSize,
                    uploadedSize = syncAgg.uploadedSize,
                    redSize = syncAgg.redSize,
                    cloudOnlySize = cloudOnlyFolderSize
                )
                changed = true
            }
            val next = parent.substringBeforeLast('/', "")
            if (next == parent) break
            parent = next
        }
        return changed
    }

    /** 自然排序比较器：路径按深度优先 + 数字按自然序（file2 < file10） */
    private fun naturalOrderComparator(vaultDir: String) = Comparator<File> { a, b ->
        val pathA = a.relativeTo(File(vaultDir)).path.replace('\\', '/')
        val pathB = b.relativeTo(File(vaultDir)).path.replace('\\', '/')
        naturalCompare(pathA, pathB)
    }

    /** 按相对路径自然排序（用于下载队列） */
    private val naturalOrderFileName = Comparator<SyncEntryRow> { a, b -> naturalCompare(a.path, b.path) }

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
        val redSize: Long = 0,
        val cloudOnlySize: Long = 0
    )
}
