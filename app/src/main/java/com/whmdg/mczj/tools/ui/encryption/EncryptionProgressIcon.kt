package com.whmdg.mczj.tools.ui.encryption

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.encryption.models.*
import com.whmdg.mczj.tools.encryption.services.EncryptionTaskManager
import com.whmdg.mczj.tools.util.FormatUtils

/**
 * 加密进度图标
 * 显示在 AppBar 的 actions 中，点击弹出进度面板
 */
@Composable
fun EncryptionProgressIcon(
    modifier: Modifier = Modifier,
    onShowPanel: () -> Unit = {}
) {
    val stateFlow = EncryptionTaskManager.stateFlow.collectAsState()

    // 计算统计数据
    val stats = remember(stateFlow.value) {
        calculateStats()
    }

    Canvas(
        modifier = modifier
            .size(width = 80.dp, height = 12.dp)
            .pointerInput(Unit) {
                detectTapGestures {
                    onShowPanel()
                }
            }
    ) {
        val width = size.width
        val height = size.height
        val cornerRadius = CornerRadius(6.dp.toPx())

        // 绘制背景
        drawRoundRect(
            color = Color.Gray.copy(alpha = 0.3f),
            cornerRadius = cornerRadius
        )

        if (stats.totalSize <= 0) return@Canvas

        // 计算各段宽度
        val minWidth = 1.dp.toPx()
        var completedWidth = if (stats.completedSize > 0) {
            (stats.completedSize.toFloat() / stats.totalSize * width).coerceAtLeast(minWidth)
        } else 0f

        var encryptingWidth = if (stats.encryptingSize > 0) {
            (stats.encryptingSize.toFloat() / stats.totalSize * width).coerceAtLeast(minWidth)
        } else 0f

        var pendingWidth = if (stats.pendingSize > 0) {
            (stats.pendingSize.toFloat() / stats.totalSize * width).coerceAtLeast(minWidth)
        } else 0f

        var pausedErrorWidth = if (stats.pausedErrorSize > 0) {
            (stats.pausedErrorSize.toFloat() / stats.totalSize * width).coerceAtLeast(minWidth)
        } else 0f

        // 如果总宽度超过画布宽度，按比例缩放
        val totalWidth = completedWidth + encryptingWidth + pendingWidth + pausedErrorWidth
        if (totalWidth > width) {
            val scale = width / totalWidth
            completedWidth *= scale
            encryptingWidth *= scale
            pendingWidth *= scale
            pausedErrorWidth *= scale
        }

        // 绘制各段
        var currentX = 0f

        // 绿色 - 已完成
        if (completedWidth > 0) {
            drawRect(
                color = Color.Green,
                topLeft = Offset(currentX, 0f),
                size = Size(completedWidth, height)
            )
            currentX += completedWidth
        }

        // 黄色 - 加密中
        if (encryptingWidth > 0) {
            drawRect(
                color = Color.Yellow,
                topLeft = Offset(currentX, 0f),
                size = Size(encryptingWidth, height)
            )
            currentX += encryptingWidth
        }

        // 红色 - 等待中
        if (pendingWidth > 0) {
            drawRect(
                color = Color.Red,
                topLeft = Offset(currentX, 0f),
                size = Size(pendingWidth, height)
            )
            currentX += pendingWidth
        }

        // 灰色 - 暂停/错误
        if (pausedErrorWidth > 0) {
            drawRect(
                color = Color.Gray,
                topLeft = Offset(currentX, 0f),
                size = Size(pausedErrorWidth, height)
            )
            currentX += pausedErrorWidth
        }

        // 剩余空间填充灰色背景
        if (currentX < width) {
            drawRect(
                color = Color.Gray.copy(alpha = 0.3f),
                topLeft = Offset(currentX, 0f),
                size = Size(width - currentX, height)
            )
        }
    }
}

/**
 * 计算统计数据
 */
private fun calculateStats(): SizeStats {
    var completedSize = 0L
    var encryptingSize = 0L
    var encryptingCompletedSize = 0L
    var pendingSize = 0L
    var pausedErrorSize = 0L
    var totalSize = 0L

    fun traverse(node: EncryptionNode) {
        when (node) {
            is FileNode -> {
                totalSize += node.rawSize
                when (node.status) {
                    NodeStatus.COMPLETED -> completedSize += node.rawSize
                    NodeStatus.ENCRYPTING -> {
                        encryptingCompletedSize += node.encryptingCompletedBytes
                        encryptingSize += node.rawSize - node.encryptingCompletedBytes
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

    EncryptionTaskManager.tasks.forEach { traverse(it) }

    return SizeStats(
        completedSize = completedSize,
        encryptingSize = encryptingSize,
        encryptingCompletedSize = encryptingCompletedSize,
        pendingSize = pendingSize,
        pausedErrorSize = pausedErrorSize,
        totalSize = totalSize
    )
}

/**
 * 构建 Tooltip 消息
 */
fun buildTooltipMessage(stats: SizeStats): String {
    return buildString {
        append("已加密: ${FormatUtils.formatBytes(stats.completedSize)}")
        append(" / 总大小: ${FormatUtils.formatBytes(stats.totalSize)}")
        append("\n加密中: ${FormatUtils.formatBytes(stats.encryptingSize + stats.encryptingCompletedSize)}")
        append("\n等待中: ${FormatUtils.formatBytes(stats.pendingSize)}")
        append("\n异常/暂停: ${FormatUtils.formatBytes(stats.pausedErrorSize)}")
    }
}
