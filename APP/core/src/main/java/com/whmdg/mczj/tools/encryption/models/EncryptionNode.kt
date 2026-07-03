package com.whmdg.mczj.tools.encryption.models

/**
 * 加密节点状态
 */
enum class NodeStatus {
    PENDING_WAITING,  // 等待中
    PENDING_PAUSED,   // 已暂停
    ENCRYPTING,       // 加密中
    COMPLETED,        // 已完成
    ERROR             // 错误
}

/**
 * 加密节点基类
 */
sealed class EncryptionNode {
    abstract val id: String
    abstract val name: String
    abstract val rawSize: Long
    abstract var status: NodeStatus
    abstract var isPaused: Boolean
    abstract var errorMessage: String?

    val isCompleted: Boolean get() = status == NodeStatus.COMPLETED
    val isError: Boolean get() = status == NodeStatus.ERROR
    val isEncrypting: Boolean get() = status == NodeStatus.ENCRYPTING
    val isPending: Boolean get() = status == NodeStatus.PENDING_WAITING || status == NodeStatus.PENDING_PAUSED

    /**
     * 计算进度百分比 (0.0 ~ 1.0)
     */
    open fun getProgress(): Double {
        return when (status) {
            NodeStatus.COMPLETED -> 1.0
            NodeStatus.ERROR, NodeStatus.PENDING_WAITING, NodeStatus.PENDING_PAUSED -> 0.0
            NodeStatus.ENCRYPTING -> 0.5 // 默认值，子类会覆盖
        }
    }
}

/**
 * 文件节点
 */
data class FileNode(
    override val id: String,
    override val name: String,
    val absolutePath: String,
    override val rawSize: Long,
    override var status: NodeStatus = NodeStatus.PENDING_WAITING,
    override var isPaused: Boolean = false,
    override var errorMessage: String? = null,
    var encryptingCompletedBytes: Long = 0
) : EncryptionNode() {

    override fun getProgress(): Double {
        return when (status) {
            NodeStatus.COMPLETED -> 1.0
            NodeStatus.ENCRYPTING -> {
                if (rawSize > 0) {
                    encryptingCompletedBytes.toDouble() / rawSize
                } else {
                    0.0
                }
            }
            else -> 0.0
        }
    }
}

/**
 * 文件夹节点
 */
data class FolderNode(
    override val id: String,
    override val name: String,
    val absolutePath: String,
    val children: MutableList<EncryptionNode> = mutableListOf(),
    override var status: NodeStatus = NodeStatus.PENDING_WAITING,
    override var isPaused: Boolean = false,
    override var errorMessage: String? = null
) : EncryptionNode() {

    override val rawSize: Long
        get() = children.sumOf { it.rawSize }

    override fun getProgress(): Double {
        val total = rawSize
        if (total <= 0) return 0.0

        var completedSize = 0L
        var encryptingCompletedSize = 0L

        fun traverse(node: EncryptionNode) {
            when (node) {
                is FileNode -> {
                    when (node.status) {
                        NodeStatus.COMPLETED -> completedSize += node.rawSize
                        NodeStatus.ENCRYPTING -> encryptingCompletedSize += node.encryptingCompletedBytes
                        else -> {}
                    }
                }
                is FolderNode -> {
                    node.children.forEach { traverse(it) }
                }
            }
        }

        children.forEach { traverse(it) }
        return (completedSize + encryptingCompletedSize).toDouble() / total
    }

    /**
     * 获取各状态的大小统计
     */
    fun getSizeStats(): SizeStats {
        var completedSize = 0L
        var encryptingSize = 0L
        var encryptingCompletedSize = 0L
        var pendingSize = 0L
        var pausedErrorSize = 0L

        fun traverse(node: EncryptionNode) {
            when (node) {
                is FileNode -> {
                    when (node.status) {
                        NodeStatus.COMPLETED -> completedSize += node.rawSize
                        NodeStatus.ENCRYPTING -> {
                            encryptingSize += node.rawSize - node.encryptingCompletedBytes
                            encryptingCompletedSize += node.encryptingCompletedBytes
                        }
                        NodeStatus.PENDING_WAITING -> pendingSize += node.rawSize
                        NodeStatus.PENDING_PAUSED, NodeStatus.ERROR -> pausedErrorSize += node.rawSize
                    }
                }
                is FolderNode -> {
                    node.children.forEach { traverse(it) }
                }
            }
        }

        children.forEach { traverse(it) }
        return SizeStats(
            completedSize = completedSize,
            encryptingSize = encryptingSize,
            encryptingCompletedSize = encryptingCompletedSize,
            pendingSize = pendingSize,
            pausedErrorSize = pausedErrorSize,
            totalSize = rawSize
        )
    }
}

/**
 * 大小统计
 */
data class SizeStats(
    val completedSize: Long,
    val encryptingSize: Long,
    val encryptingCompletedSize: Long,
    val pendingSize: Long,
    val pausedErrorSize: Long,
    val totalSize: Long
) {
    val completedPercent: Double
        get() = if (totalSize > 0) completedSize.toDouble() / totalSize else 0.0

    val encryptingPercent: Double
        get() = if (totalSize > 0) (encryptingSize + encryptingCompletedSize).toDouble() / totalSize else 0.0

    val pendingPercent: Double
        get() = if (totalSize > 0) pendingSize.toDouble() / totalSize else 0.0

    val pausedErrorPercent: Double
        get() = if (totalSize > 0) pausedErrorSize.toDouble() / totalSize else 0.0
}
