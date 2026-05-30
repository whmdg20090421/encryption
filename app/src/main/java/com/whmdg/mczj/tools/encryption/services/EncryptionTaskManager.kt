package com.whmdg.mczj.tools.encryption.services

import android.content.Context
import android.util.Log
import com.whmdg.mczj.tools.encryption.data.Argon2Params
import com.whmdg.mczj.tools.encryption.data.ConfigFlags
import com.whmdg.mczj.tools.encryption.data.KdfType
import com.whmdg.mczj.tools.encryption.data.StorageLocation
import com.whmdg.mczj.tools.encryption.data.VaultConfig
import com.whmdg.mczj.tools.encryption.data.VaultRecord
import com.whmdg.mczj.tools.encryption.models.EncryptionNode
import com.whmdg.mczj.tools.encryption.models.FileNode
import com.whmdg.mczj.tools.encryption.models.FolderNode
import com.whmdg.mczj.tools.encryption.models.NodeStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 加密任务管理器（单例）
 * 管理加密任务队列、后台加密、速度计算、持久化
 */
object EncryptionTaskManager {
    private const val TAG = "EncryptionTaskManager"
    private const val MAX_WORKERS = 2
    private const val SPEED_CACHE_DURATION_MS = 10000L // 10秒

    // 任务队列
    private val _tasks = mutableListOf<EncryptionNode>()
    val tasks: List<EncryptionNode> get() = _tasks.toList()

    // 历史记录
    private val _historyTasks = mutableListOf<EncryptionNode>()
    val historyTasks: List<EncryptionNode> get() = _historyTasks.toList()

    // 状态流，用于通知 UI 更新
    private val _stateFlow = MutableStateFlow(0L)
    val stateFlow: StateFlow<Long> = _stateFlow.asStateFlow()

    // 活跃的加密任务
    private val activeJobs = ConcurrentHashMap<String, Job>()

    // 速度计算
    private val speedCache = mutableListOf<Pair<Long, Long>>() // timestamp, bytes
    private var _currentSpeedBytesPerSecond = 0L
    val currentSpeedBytesPerSecond: Long get() = _currentSpeedBytesPerSecond

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 持久化文件
    private var queueFile: File? = null
    private var historyFile: File? = null

    // 应用上下文
    private var appContext: Context? = null

    /**
     * 初始化管理器
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        queueFile = File(context.filesDir, "encryption_tasks.json")
        historyFile = File(context.filesDir, "encryption_history.json")
        loadQueue()
        loadHistory()
        startSpeedCalculator()
    }

    /**
     * 创建加密任务
     */
    fun createEncryptionTask(
        file: File,
        session: VaultSession,
        subDir: String,
        onComplete: (() -> Unit)? = null
    ): EncryptionNode {
        val taskId = UUID.randomUUID().toString()
        val node = if (file.isDirectory) {
            buildFolderNode(file, taskId)
        } else {
            buildFileNode(file, taskId)
        }

        // 保存任务参数
        val taskArgs = TaskArgs(
            vaultDir = session.vaultDir.absolutePath,
            dek = session.dek.clone(),
            subDir = subDir,
            encryptFilename = session.record.encryptFilename,
            customEncryption = session.record.customEncryption,
            configJson = Json.encodeToString(VaultConfig.serializer(), session.config)
        )

        synchronized(_tasks) {
            _tasks.add(node)
        }

        saveQueue()
        notifyStateChanged()

        // 启动后台加密
        startEncryption(node, taskArgs, onComplete)

        return node
    }

    /**
     * 构建文件节点
     */
    private fun buildFileNode(file: File, taskId: String): FileNode {
        return FileNode(
            id = taskId,
            name = file.name,
            absolutePath = file.absolutePath,
            rawSize = file.length()
        )
    }

    /**
     * 构建文件夹节点
     */
    private fun buildFolderNode(dir: File, taskId: String): FolderNode {
        val children = mutableListOf<EncryptionNode>()

        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                children.add(buildFolderNode(file, UUID.randomUUID().toString()))
            } else {
                children.add(buildFileNode(file, UUID.randomUUID().toString()))
            }
        }

        return FolderNode(
            id = taskId,
            name = dir.name,
            absolutePath = dir.absolutePath,
            children = children
        )
    }

    /**
     * 启动加密任务
     */
    private fun startEncryption(
        node: EncryptionNode,
        taskArgs: TaskArgs,
        onComplete: (() -> Unit)?
    ) {
        val job = scope.launch {
            try {
                encryptNode(node, taskArgs)
                onComplete?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Encryption failed", e)
                markNodeError(node, e.message ?: "Unknown error")
            } finally {
                activeJobs.remove(node.id)
                checkAndArchiveCompleted()
            }
        }
        activeJobs[node.id] = job
    }

    /**
     * 递归加密节点
     */
    private suspend fun encryptNode(node: EncryptionNode, taskArgs: TaskArgs) {
        when (node) {
            is FileNode -> {
                if (node.isPaused) {
                    node.status = NodeStatus.PENDING_PAUSED
                    notifyStateChanged()
                    return
                }

                node.status = NodeStatus.ENCRYPTING
                notifyStateChanged()

                try {
                    val srcFile = File(node.absolutePath)
                    if (!srcFile.exists()) {
                        throw Exception("File not found: ${node.absolutePath}")
                    }

                    val context = appContext ?: throw Exception("EncryptionTaskManager not initialized")

                    // 反序列化 config
                    val config = Json.decodeFromString(VaultConfig.serializer(), taskArgs.configJson)

                    // 创建 VaultSession 用于加密
                    val session = VaultSession(
                        vaultDir = File(taskArgs.vaultDir),
                        dek = taskArgs.dek,
                        config = config,
                        record = VaultRecord(
                            id = 0,
                            name = "",
                            location = StorageLocation.INTERNAL,
                            relativePath = taskArgs.vaultDir,
                            encryptFilename = taskArgs.encryptFilename,
                            encryptMetadata = config.configFlags.encryptMetadata,
                            customEncryption = taskArgs.customEncryption,
                            createdAt = ""
                        )
                    )

                    // 执行加密
                    CryptoService.encryptIntoVault(
                        context = context,
                        session = session,
                        srcFile = srcFile,
                        subDir = taskArgs.subDir,
                        overwrite = true,
                        onProgress = { processed, total ->
                            node.encryptingCompletedBytes = processed
                            updateSpeed(processed)
                            notifyStateChanged()
                        }
                    )

                    node.status = NodeStatus.COMPLETED
                    node.encryptingCompletedBytes = node.rawSize
                    notifyStateChanged()

                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    node.status = NodeStatus.ERROR
                    node.errorMessage = e.message
                    notifyStateChanged()
                }
            }
            is FolderNode -> {
                node.status = NodeStatus.ENCRYPTING
                notifyStateChanged()

                for (child in node.children) {
                    if (node.isPaused) {
                        node.status = NodeStatus.PENDING_PAUSED
                        notifyStateChanged()
                        return
                    }
                    encryptNode(child, taskArgs)
                }

                // 检查子节点状态
                val hasError = node.children.any { it.status == NodeStatus.ERROR }
                val hasPending = node.children.any {
                    it.status == NodeStatus.PENDING_WAITING || it.status == NodeStatus.PENDING_PAUSED
                }

                node.status = when {
                    hasError -> NodeStatus.ERROR
                    hasPending -> NodeStatus.PENDING_WAITING
                    else -> NodeStatus.COMPLETED
                }
                notifyStateChanged()
            }
        }
    }

    /**
     * 标记节点错误
     */
    private fun markNodeError(node: EncryptionNode, message: String) {
        node.status = NodeStatus.ERROR
        node.errorMessage = message
        notifyStateChanged()
    }

    /**
     * 暂停任务
     */
    fun pauseTask(node: EncryptionNode) {
        node.isPaused = true
        when (node) {
            is FileNode -> {
                if (node.status == NodeStatus.PENDING_WAITING) {
                    node.status = NodeStatus.PENDING_PAUSED
                }
            }
            is FolderNode -> {
                node.children.forEach { pauseTask(it) }
            }
        }
        notifyStateChanged()
        saveQueue()
    }

    /**
     * 继续任务
     */
    fun resumeTask(node: EncryptionNode) {
        node.isPaused = false
        when (node) {
            is FileNode -> {
                if (node.status == NodeStatus.PENDING_PAUSED) {
                    node.status = NodeStatus.PENDING_WAITING
                    // 重新启动加密
                    val taskArgs = findTaskArgs(node)
                    if (taskArgs != null) {
                        startEncryption(node, taskArgs, null)
                    }
                }
            }
            is FolderNode -> {
                node.children.forEach { resumeTask(it) }
            }
        }
        notifyStateChanged()
        saveQueue()
    }

    /**
     * 移除任务
     */
    fun removeTask(node: EncryptionNode) {
        // 取消活跃的任务
        activeJobs[node.id]?.cancel()
        activeJobs.remove(node.id)

        synchronized(_tasks) {
            _tasks.remove(node)
        }
        notifyStateChanged()
        saveQueue()
    }

    /**
     * 移除历史记录
     */
    fun removeHistoryTask(node: EncryptionNode) {
        synchronized(_historyTasks) {
            _historyTasks.remove(node)
        }
        notifyStateChanged()
        saveHistory()
    }

    /**
     * 标记已修复并重试
     */
    fun markTaskAsFixed(node: EncryptionNode) {
        when (node) {
            is FileNode -> {
                if (node.status == NodeStatus.ERROR || node.status == NodeStatus.PENDING_PAUSED) {
                    node.status = NodeStatus.PENDING_WAITING
                    node.isPaused = false
                    node.errorMessage = null
                    val taskArgs = findTaskArgs(node)
                    if (taskArgs != null) {
                        startEncryption(node, taskArgs, null)
                    }
                }
            }
            is FolderNode -> {
                node.children.forEach { markTaskAsFixed(it) }
            }
        }
        notifyStateChanged()
        saveQueue()
    }

    /**
     * 查找任务参数（简化实现）
     */
    private fun findTaskArgs(node: EncryptionNode): TaskArgs? {
        // 从持久化中恢复任务参数
        return null
    }

    /**
     * 更新速度
     */
    private fun updateSpeed(bytes: Long) {
        val now = System.currentTimeMillis()
        synchronized(speedCache) {
            speedCache.add(Pair(now, bytes))
        }
    }

    /**
     * 启动速度计算器
     */
    private fun startSpeedCalculator() {
        scope.launch {
            while (isActive) {
                delay(1000)
                calculateSpeed()
            }
        }
    }

    /**
     * 计算当前速度
     */
    private fun calculateSpeed() {
        val now = System.currentTimeMillis()
        synchronized(speedCache) {
            // 清理过期数据
            speedCache.removeAll { now - it.first > SPEED_CACHE_DURATION_MS }

            if (speedCache.isEmpty()) {
                _currentSpeedBytesPerSecond = 0
                return
            }

            val totalBytes = speedCache.sumOf { it.second }
            val duration = (now - speedCache.first().first).coerceAtLeast(1000)
            _currentSpeedBytesPerSecond = totalBytes * 1000 / duration
        }
    }

    /**
     * 检查并归档完成的任务
     */
    private fun checkAndArchiveCompleted() {
        synchronized(_tasks) {
            val completedTasks = _tasks.filter { it.status == NodeStatus.COMPLETED }
            completedTasks.forEach { task ->
                _tasks.remove(task)
                _historyTasks.add(0, task)
            }
        }
        saveQueue()
        saveHistory()
    }

    /**
     * 通知状态变化
     */
    private fun notifyStateChanged() {
        _stateFlow.value = System.currentTimeMillis()
    }

    /**
     * 保存任务队列
     */
    private fun saveQueue() {
        try {
            val file = queueFile ?: return
            val jsonArray = JSONArray()

            synchronized(_tasks) {
                _tasks.forEach { node ->
                    jsonArray.put(nodeToJson(node))
                }
            }

            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save queue", e)
        }
    }

    /**
     * 加载任务队列
     */
    private fun loadQueue() {
        try {
            val file = queueFile ?: return
            if (!file.exists()) return

            val jsonArray = JSONArray(file.readText())
            val nodes = mutableListOf<EncryptionNode>()

            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                nodes.add(jsonToNode(json))
            }

            synchronized(_tasks) {
                _tasks.clear()
                _tasks.addAll(nodes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load queue", e)
        }
    }

    /**
     * 保存历史记录
     */
    private fun saveHistory() {
        try {
            val file = historyFile ?: return
            val jsonArray = JSONArray()

            synchronized(_historyTasks) {
                _historyTasks.forEach { node ->
                    jsonArray.put(nodeToJson(node))
                }
            }

            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save history", e)
        }
    }

    /**
     * 加载历史记录
     */
    private fun loadHistory() {
        try {
            val file = historyFile ?: return
            if (!file.exists()) return

            val jsonArray = JSONArray(file.readText())
            val nodes = mutableListOf<EncryptionNode>()

            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                nodes.add(jsonToNode(json))
            }

            synchronized(_historyTasks) {
                _historyTasks.clear()
                _historyTasks.addAll(nodes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load history", e)
        }
    }

    /**
     * 节点转 JSON
     */
    private fun nodeToJson(node: EncryptionNode): JSONObject {
        val json = JSONObject()
        json.put("id", node.id)
        json.put("name", node.name)
        json.put("rawSize", node.rawSize)
        json.put("status", node.status.name)
        json.put("isPaused", node.isPaused)
        json.put("errorMessage", node.errorMessage ?: JSONObject.NULL)

        when (node) {
            is FileNode -> {
                json.put("type", "file")
                json.put("absolutePath", node.absolutePath)
                json.put("encryptingCompletedBytes", node.encryptingCompletedBytes)
            }
            is FolderNode -> {
                json.put("type", "folder")
                json.put("absolutePath", node.absolutePath)
                val childrenArray = JSONArray()
                node.children.forEach { child ->
                    childrenArray.put(nodeToJson(child))
                }
                json.put("children", childrenArray)
            }
        }

        return json
    }

    /**
     * JSON 转节点
     */
    private fun jsonToNode(json: JSONObject): EncryptionNode {
        val id = json.getString("id")
        val name = json.getString("name")
        val rawSize = json.getLong("rawSize")
        val status = NodeStatus.valueOf(json.getString("status"))
        val isPaused = json.getBoolean("isPaused")
        val errorMessage = if (json.isNull("errorMessage")) null else json.getString("errorMessage")

        return when (json.getString("type")) {
            "file" -> {
                FileNode(
                    id = id,
                    name = name,
                    absolutePath = json.getString("absolutePath"),
                    rawSize = rawSize,
                    status = status,
                    isPaused = isPaused,
                    errorMessage = errorMessage,
                    encryptingCompletedBytes = json.getLong("encryptingCompletedBytes")
                )
            }
            "folder" -> {
                val children = mutableListOf<EncryptionNode>()
                val childrenArray = json.getJSONArray("children")
                for (i in 0 until childrenArray.length()) {
                    children.add(jsonToNode(childrenArray.getJSONObject(i)))
                }
                FolderNode(
                    id = id,
                    name = name,
                    absolutePath = json.getString("absolutePath"),
                    children = children,
                    status = status,
                    isPaused = isPaused,
                    errorMessage = errorMessage
                )
            }
            else -> throw IllegalArgumentException("Unknown node type")
        }
    }

    /**
     * 任务参数
     */
    data class TaskArgs(
        val vaultDir: String,
        val dek: ByteArray,
        val subDir: String,
        val encryptFilename: Boolean,
        val customEncryption: Boolean,
        val configJson: String // VaultConfig 的 JSON 序列化
    )
}
