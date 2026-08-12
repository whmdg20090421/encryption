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
 * 每个文件/文件夹下方有进度条显示同步状态：红=未上传，绿=已完成，黄=上传中。
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
        /** 当前相对路径（相对于 vaultDir），"/" 表示根目录 */
        var currentPath by mutableStateOf("/")
        var entries by mutableStateOf<List<CloudFileEntry>>(emptyList())
        var isLoading by mutableStateOf(false)
        var loadError by mutableStateOf<Throwable?>(null)
        var selectedPaths by mutableStateOf<Set<String>>(emptySet())
        var syncTask by mutableStateOf(SyncTaskState())
        var syncIndex by mutableStateOf(VaultSyncIndex())
    }

    /** 本地文件条目（带同步状态） */
    data class CloudFileEntry(
        val name: String,
        /** 相对于 vaultDir 的路径，如 "/docs/report.whm" */
        val relativePath: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long = 0,
        /** 同步状态（仅文件有，文件夹为 null） */
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
        // 加载本地索引
        val indexFile = File(vaultDir, "vault_sync_index.json")
        if (indexFile.exists()) {
            try {
                val json = indexFile.readText()
                if (json.isNotBlank()) {
                    state.syncIndex = kotlinx.serialization.json.Json.decodeFromString<VaultSyncIndex>(json)
                }
            } catch (_: Exception) {}
        }

        // 如果索引没有保险箱名称，自动设置
        if (state.syncIndex.vaultFolderName.isEmpty()) {
            state.syncIndex = state.syncIndex.copy(vaultFolderName = vaultName)
        }

        // 如果索引没有远程路径，从 WebDAV 配置初始化
        if (state.syncIndex.remoteBasePath.isEmpty() && webdavConfig.relativePath.isNotEmpty()) {
            state.syncIndex = state.syncIndex.copy(remoteBasePath = webdavConfig.relativePath)
        }

        // 列出本地保险箱根目录
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
        val parent = state.currentPath.substringBeforeLast('/', "")
        if (parent.isEmpty()) return null
        val parentPath = if (parent == "") "/" else parent
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
                    // 同步完成后刷新当前目录
                    if (success) {
                        navigateTo(state.currentPath)
                    }
                }
            )
            try {
                val updatedIndex = engine.startSync(
                    mode = mode,
                    remoteBasePath = state.syncIndex.effectiveRemoteBase,
                    index = state.syncIndex
                )
                state.syncIndex = updatedIndex
                // 保存索引
                saveIndex(updatedIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    /** 暂停同步 */
    fun pauseSync() {
        syncJob?.cancel()
        state.syncTask = state.syncTask.copy(phase = SyncPhase.IDLE)
    }

    /** 获取文件同步状态 */
    fun getSyncState(path: String): SyncFileProgress? {
        return state.syncTask.fileProgress[path]
    }

    /** 刷新当前目录 */
    fun refresh() {
        navigateTo(state.currentPath)
    }

    /** 释放资源 */
    fun dispose() {
        syncJob?.cancel()
    }

    // ── 内部方法 ──

    /** 列出本地保险箱目录下的文件和文件夹 */
    private fun listLocalFiles(relativePath: String): List<CloudFileEntry> {
        val dir = File(vaultDir, relativePath.trimStart('/'))
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val entries = mutableListOf<CloudFileEntry>()
        val children = dir.listFiles() ?: return emptyList()

        for (file in children) {
            val name = file.name
            if (name in excludedFiles) continue

            val childRelativePath = if (relativePath == "/") "/$name" else "$relativePath/$name"

            if (file.isDirectory) {
                entries.add(CloudFileEntry(
                    name = name,
                    relativePath = childRelativePath,
                    isDirectory = true,
                    size = 0,
                    lastModified = file.lastModified(),
                    syncStatus = null
                ))
            } else {
                // 从索引中查找同步状态
                val syncEntry = state.syncIndex.entries[childRelativePath]
                val status = syncEntry?.uploadStatus ?: UploadStatus.PENDING

                entries.add(CloudFileEntry(
                    name = name,
                    relativePath = childRelativePath,
                    isDirectory = false,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    syncStatus = status
                ))
            }
        }

        // 排序：文件夹在前，文件在后，按名称排序
        return entries.sortedWith(compareBy<CloudFileEntry> { !it.isDirectory }.thenBy { it.name })
    }

    /** 保存索引到文件 */
    private fun saveIndex(index: VaultSyncIndex) {
        try {
            val indexFile = File(vaultDir, "vault_sync_index.json")
            indexFile.writeText(kotlinx.serialization.json.Json.encodeToString(VaultSyncIndex.serializer(), index))
        } catch (_: Exception) {}
    }
}
