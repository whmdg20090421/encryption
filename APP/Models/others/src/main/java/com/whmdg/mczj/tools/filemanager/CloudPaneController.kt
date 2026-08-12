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
 * 管理云盘面板的所有状态，与左右 FilePaneController 完全隔离。
 * 通过 PanelCoordinator 与其他面板交互。
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

    /** 云端文件条目 */
    data class CloudFileEntry(
        val name: String,
        val remotePath: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long = 0
    )

    /** 初始化：加载索引 + 列出云端根目录 */
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

        // 列出云端根目录
        scope.launch { navigateTo(state.syncIndex.effectiveRemoteBase) }
    }

    /** 导航到云端目录 */
    fun navigateTo(path: String) {
        scope.launch {
            state.isLoading = true
            state.loadError = null
            try {
                val children = withContext(Dispatchers.IO) {
                    webdavClient.listChildren(path)
                }
                state.currentPath = path
                state.entries = (children ?: emptyList()).map {
                    CloudFileEntry(
                        name = it.name,
                        remotePath = it.remotePath,
                        isDirectory = it.isDirectory,
                        size = it.size,
                        lastModified = it.lastModified
                    )
                }.sortedWith(compareBy<CloudFileEntry> { !it.isDirectory }.thenBy { it.name })
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
        if (parent.isEmpty() || parent == state.syncIndex.effectiveRemoteBase) return null
        navigateTo(parent)
        return parent
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

    /** 保存索引到文件 */
    private fun saveIndex(index: VaultSyncIndex) {
        try {
            val indexFile = File(vaultDir, "vault_sync_index.json")
            indexFile.writeText(kotlinx.serialization.json.Json.encodeToString(VaultSyncIndex.serializer(), index))
        } catch (_: Exception) {}
    }
}
