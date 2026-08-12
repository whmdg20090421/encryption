package com.whmdg.mczj.tools.ui.filemanager

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.whmdg.mczj.tools.encryption.data.VaultSyncIndex
import com.whmdg.mczj.tools.encryption.data.UploadStatus
import com.whmdg.mczj.tools.fileop.sync.SyncEngine
import com.whmdg.mczj.tools.fileop.sync.SyncFileProgress
import com.whmdg.mczj.tools.fileop.sync.SyncMode
import com.whmdg.mczj.tools.fileop.sync.SyncPhase
import com.whmdg.mczj.tools.fileop.sync.SyncTaskState
import com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient
import com.whmdg.mczj.tools.fileop.webdav.WebDavServerConfig
import kotlinx.coroutines.*
import java.io.File

/**
 * 云盘面板控制器。
 *
 * 显示本地保险箱文件 + 同步状态（不从 WebDAV 读取）。
 * 文件夹显示聚合同步状态（自底向上冒泡）。
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

    /** 云盘面板状态（完全独立，使用 mutableStateOf 驱动 Compose recomposition） */
    class CloudPanelState {
        var currentPath by mutableStateOf("/")
        var entries by mutableStateOf<List<CloudFileEntry>>(emptyList())
        var isLoading by mutableStateOf(false)
        var loadError by mutableStateOf<Throwable?>(null)
        var selectedPaths by mutableStateOf<Set<String>>(emptySet())
        var syncTask by mutableStateOf(SyncTaskState())
        var syncIndex by mutableStateOf(VaultSyncIndex())
    }

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
        val syncStatus: UploadStatus? = null
    )

    /** 排除的系统文件 */
    private val excludedFiles = setOf(
        "vault_config.json",
        "vault_config.backup.json",
        "vault_sync_index.json",
        "name_mappings.json",
        "folder_sizes.json"
    )

    /** 初始化：加载索引 + 列出本地保险箱根目录 */
    fun init() {
        val indexFile = File(vaultDir, "vault_sync_index.json")
        if (indexFile.exists()) {
            try {
                val json = indexFile.readText()
                if (json.isNotBlank()) {
                    state.syncIndex = kotlinx.serialization.json.Json.decodeFromString<VaultSyncIndex>(json)
                }
            } catch (_: Exception) {}
        }

        if (state.syncIndex.vaultFolderName.isEmpty()) {
            state.syncIndex = state.syncIndex.copy(vaultFolderName = vaultName)
        }

        if (state.syncIndex.remoteBasePath.isEmpty() && webdavConfig.relativePath.isNotEmpty()) {
            state.syncIndex = state.syncIndex.copy(remoteBasePath = webdavConfig.relativePath)
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

    /** 返回上级目录 */
    fun goUp(): String? {
        if (state.currentPath == "/") return null
        val parent = state.currentPath.substringBeforeLast('/', "")
        val parentPath = if (parent.isEmpty()) "/" else parent
        navigateTo(parentPath)
        return parentPath
    }

    /** 启动同步 */
    fun startSync(mode: SyncMode) {
        syncJob?.cancel()
        syncJob = scope.launch {
            val engine = SyncEngine(
                webdavClient = webdavClient,
                vaultDir = vaultDir,
                onProgress = { taskState ->
                    state.syncTask = taskState
                },
                onFileComplete = { relativePath, success ->
                    if (success) navigateTo(state.currentPath)
                }
            )
            try {
                val updatedIndex = engine.startSync(
                    mode = mode,
                    remoteBasePath = state.syncIndex.effectiveRemoteBase,
                    index = state.syncIndex
                )
                state.syncIndex = updatedIndex
                saveIndex(updatedIndex)
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
        navigateTo(state.currentPath)
    }

    fun dispose() {
        syncJob?.cancel()
    }

    // ── 内部方法 ──

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
                // 文件：从索引查同步状态
                val syncEntry = state.syncIndex.entries[childRelativePath]
                val status = syncEntry?.uploadStatus ?: UploadStatus.PENDING
                val fileSize = file.length()
                entries.add(CloudFileEntry(
                    name = file.name,
                    relativePath = childRelativePath,
                    isDirectory = false,
                    totalSize = fileSize,
                    uploadedSize = if (status == UploadStatus.COMPLETED) fileSize else 0,
                    uploadingSize = if (status == UploadStatus.UPLOADING) fileSize else 0,
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
                val syncEntry = state.syncIndex.entries[childPath]
                when (syncEntry?.uploadStatus) {
                    UploadStatus.COMPLETED -> uploadedSize += fileSize
                    UploadStatus.UPLOADING -> uploadingSize += fileSize
                    else -> {} // PENDING / PAUSED_PERMANENT → 不计入
                }
            }
        }

        return FolderAggregate(totalSize, uploadedSize, uploadingSize)
    }

    private data class FolderAggregate(
        val totalSize: Long = 0,
        val uploadedSize: Long = 0,
        val uploadingSize: Long = 0
    )

    private fun saveIndex(index: VaultSyncIndex) {
        try {
            val indexFile = File(vaultDir, "vault_sync_index.json")
            indexFile.writeText(kotlinx.serialization.json.Json.encodeToString(VaultSyncIndex.serializer(), index))
        } catch (_: Exception) {}
    }
}
