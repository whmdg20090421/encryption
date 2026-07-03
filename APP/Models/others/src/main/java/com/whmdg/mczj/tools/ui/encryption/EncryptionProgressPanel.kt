package com.whmdg.mczj.tools.ui.encryption

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whmdg.mczj.tools.encryption.models.*
import com.whmdg.mczj.tools.encryption.services.EncryptionTaskManager
import com.whmdg.mczj.tools.util.FormatUtils

/**
 * 显示加密进度面板
 */
fun showEncryptionProgressPanel(context: android.content.Context) {
    // 这个函数需要在 Composable 上下文中调用
    // 实际实现会在 VaultOpenScreen 中
}

/**
 * 加密进度面板
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncryptionProgressPanel(
    onDismiss: () -> Unit
) {
    val stateFlow = EncryptionTaskManager.stateFlow.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            // 标题
            Text(
                text = "加密任务进度",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Tab 栏
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("进行中") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("历史记录") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 内容
            when (selectedTab) {
                0 -> {
                    val tasks = remember(stateFlow.value) {
                        EncryptionTaskManager.tasks
                    }
                    TaskList(
                        tasks = tasks,
                        isHistory = false,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                1 -> {
                    val historyTasks = remember(stateFlow.value) {
                        EncryptionTaskManager.historyTasks
                    }
                    TaskList(
                        tasks = historyTasks,
                        isHistory = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * 任务列表
 */
@Composable
private fun TaskList(
    tasks: List<EncryptionNode>,
    isHistory: Boolean,
    modifier: Modifier = Modifier
) {
    if (tasks.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isHistory) "没有历史记录" else "当前没有任务",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tasks) { task ->
                TaskCard(
                    task = task,
                    isHistory = isHistory
                )
            }
        }
    }
}

/**
 * 任务卡片
 */
@Composable
private fun TaskCard(
    task: EncryptionNode,
    isHistory: Boolean
) {
    val speed = EncryptionTaskManager.currentSpeedBytesPerSecond
    var showActionMenu by remember { mutableStateOf(false) }

    // 计算统计数据
    val stats = remember(task) {
        when (task) {
            is FolderNode -> task.getSizeStats()
            is FileNode -> {
                SizeStats(
                    completedSize = if (task.status == NodeStatus.COMPLETED) task.rawSize else 0,
                    encryptingSize = if (task.status == NodeStatus.ENCRYPTING) task.rawSize - task.encryptingCompletedBytes else 0,
                    encryptingCompletedSize = if (task.status == NodeStatus.ENCRYPTING) task.encryptingCompletedBytes else 0,
                    pendingSize = if (task.status == NodeStatus.PENDING_WAITING) task.rawSize else 0,
                    pausedErrorSize = if (task.status == NodeStatus.PENDING_PAUSED || task.status == NodeStatus.ERROR) task.rawSize else 0,
                    totalSize = task.rawSize
                )
            }
        }
    }

    val progress = task.getProgress()
    val isPaused = task.isPaused
    val isError = task.status == NodeStatus.ERROR
    val isCompleted = task.status == NodeStatus.COMPLETED

    // 计算剩余时间
    val remainingSize = stats.totalSize - stats.completedSize - stats.encryptingCompletedSize
    val timeRemaining = if (speed > 0 && !isCompleted && !isPaused && !isError) {
        remainingSize / speed
    } else 0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (task is FolderNode) {
                        // 可以展开文件夹查看详情
                    }
                },
                onLongClick = {
                    if (!isHistory) {
                        showActionMenu = true
                    }
                }
            ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 第一行：暂停/继续按钮 + 名称
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 暂停/继续按钮
                IconButton(
                    onClick = {
                        when {
                            isCompleted -> {}
                            isError -> EncryptionTaskManager.markTaskAsFixed(task)
                            isPaused -> EncryptionTaskManager.resumeTask(task)
                            else -> EncryptionTaskManager.pauseTask(task)
                        }
                    },
                    enabled = !isCompleted,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = when {
                            isCompleted -> Icons.Default.CheckCircle
                            isError -> Icons.Default.Refresh
                            isPaused || task.status == NodeStatus.PENDING_PAUSED -> Icons.Default.PlayArrow
                            else -> Icons.Default.Pause
                        },
                        contentDescription = when {
                            isCompleted -> "已完成"
                            isError -> "重试"
                            isPaused -> "继续"
                            else -> "暂停"
                        },
                        tint = when {
                            isCompleted -> Color.Green
                            isError -> Color.Red
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 名称
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // 错误信息
                    if (isError && task.errorMessage != null) {
                        Text(
                            text = task.errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Red,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 文件夹图标
                if (task is FolderNode) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 第二行：4色进度条
            FourColorProgressBar(
                completedSize = stats.completedSize,
                encryptingCompletedSize = stats.encryptingCompletedSize,
                encryptingRemainingSize = stats.encryptingSize,
                pendingSize = stats.pendingSize,
                pausedErrorSize = stats.pausedErrorSize,
                totalSize = stats.totalSize,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 第三行：百分比 + 速度 + 剩余时间
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 百分比 + 速度
                Text(
                    text = buildString {
                        append("%.1f%%".format(progress * 100))
                        if (speed > 0 && !isCompleted && !isPaused && !isError) {
                            append(" (${FormatUtils.formatSpeed(speed)})")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                // 已完成/总量 + 剩余时间
                Text(
                    text = buildString {
                        append("${FormatUtils.formatBytes(stats.completedSize + stats.encryptingCompletedSize)}")
                        append(" / ")
                        append(FormatUtils.formatBytes(stats.totalSize))
                        if (timeRemaining > 0) {
                            append(" (${FormatUtils.formatTimeRemaining(timeRemaining)})")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }

    // 长按菜单
    if (showActionMenu) {
        AlertDialog(
            onDismissRequest = { showActionMenu = false },
            title = { Text(task.name) },
            text = {
                Column {
                    if (isHistory) {
                        ListItem(
                            headlineContent = { Text("删除历史记录") },
                            supportingContent = { Text("仅清除列表数据，不删除文件") },
                            leadingContent = {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red)
                            },
                            modifier = Modifier.clickable {
                                showActionMenu = false
                                EncryptionTaskManager.removeHistoryTask(task)
                            }
                        )
                    } else {
                        ListItem(
                            headlineContent = { Text("移除任务") },
                            leadingContent = {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red)
                            },
                            modifier = Modifier.clickable {
                                showActionMenu = false
                                EncryptionTaskManager.removeTask(task)
                            }
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("标记已修复并重试") },
                            leadingContent = {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                showActionMenu = false
                                EncryptionTaskManager.markTaskAsFixed(task)
                            }
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }
}

/**
 * 4色进度条
 */
@Composable
private fun FourColorProgressBar(
    completedSize: Long,
    encryptingCompletedSize: Long,
    encryptingRemainingSize: Long,
    pendingSize: Long,
    pausedErrorSize: Long,
    totalSize: Long,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val cornerRadius = CornerRadius(2.dp.toPx())

        // 绘制背景
        drawRoundRect(
            color = Color.Gray.copy(alpha = 0.3f),
            cornerRadius = cornerRadius
        )

        if (totalSize <= 0) return@Canvas

        // 计算各段宽度
        val minWidth = (width * 0.01).toFloat()
        var completedWidth = if (completedSize > 0) {
            (completedSize.toFloat() / totalSize * width).coerceAtLeast(minWidth)
        } else 0f

        var encryptingCompletedWidth = if (encryptingCompletedSize > 0) {
            (encryptingCompletedSize.toFloat() / totalSize * width).coerceAtLeast(minWidth)
        } else 0f

        var encryptingRemainingWidth = if (encryptingRemainingSize > 0) {
            (encryptingRemainingSize.toFloat() / totalSize * width).coerceAtLeast(minWidth)
        } else 0f

        var pendingWidth = if (pendingSize > 0) {
            (pendingSize.toFloat() / totalSize * width).coerceAtLeast(minWidth)
        } else 0f

        var pausedErrorWidth = if (pausedErrorSize > 0) {
            (pausedErrorSize.toFloat() / totalSize * width).coerceAtLeast(minWidth)
        } else 0f

        // 如果总宽度超过画布宽度，按比例缩放
        val totalWidth = completedWidth + encryptingCompletedWidth + encryptingRemainingWidth + pendingWidth + pausedErrorWidth
        if (totalWidth > width) {
            val scale = width / totalWidth
            completedWidth *= scale
            encryptingCompletedWidth *= scale
            encryptingRemainingWidth *= scale
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

        // 渐变 - 加密中已完成部分
        if (encryptingCompletedWidth > 0) {
            drawRect(
                color = Color(0xFFADFF2F), // 黄绿色
                topLeft = Offset(currentX, 0f),
                size = Size(encryptingCompletedWidth, height)
            )
            currentX += encryptingCompletedWidth
        }

        // 黄色 - 加密中剩余部分
        if (encryptingRemainingWidth > 0) {
            drawRect(
                color = Color.Yellow,
                topLeft = Offset(currentX, 0f),
                size = Size(encryptingRemainingWidth, height)
            )
            currentX += encryptingRemainingWidth
        }

        // 灰色 - 等待中
        if (pendingWidth > 0) {
            drawRect(
                color = Color.Gray.copy(alpha = 0.4f),
                topLeft = Offset(currentX, 0f),
                size = Size(pendingWidth, height)
            )
            currentX += pendingWidth
        }

        // 红色 - 暂停/错误
        if (pausedErrorWidth > 0) {
            drawRect(
                color = Color.Red,
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
