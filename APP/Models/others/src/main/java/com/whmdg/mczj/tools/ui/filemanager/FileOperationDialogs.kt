package com.whmdg.mczj.tools.ui.filemanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.fileop.ConflictAction
import com.whmdg.mczj.tools.fileop.ConflictRequest
import com.whmdg.mczj.tools.fileop.ConflictResult
import com.whmdg.mczj.tools.fileop.ErrorAction
import com.whmdg.mczj.tools.fileop.ErrorRequest
import com.whmdg.mczj.tools.fileop.ErrorResult
import com.whmdg.mczj.tools.fileop.FileOperationManager
import com.whmdg.mczj.tools.util.FormatUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件冲突处理弹窗。
 * 三个单选操作（替换/重命名/跳过）+ 取消/确认按钮。
 */
@Composable
fun FileConflictDialog() {
    val request by FileOperationManager.conflictRequest.collectAsState()

    request?.let { req ->
        val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
        var selected by remember(req) { mutableStateOf(ConflictAction.REPLACE) }

        AlertDialog(
            onDismissRequest = { /* 不可点击外部关闭 */ },
            title = {
                Text(
                    text = "文件冲突",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 源文件信息
                    Text(
                        text = if (req.isDirectory) "源文件夹: ${req.sourceName}" else "源文件: ${req.sourceName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (!req.isDirectory) {
                        Text(
                            text = "大小: ${FormatUtils.formatBytes(req.sourceSize)}  修改: ${dateFormat.format(Date(req.sourceModifiedTime))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    // 目标文件信息
                    Text(
                        text = if (req.isDirectory) "目标文件夹（已存在）: ${req.targetName}" else "目标文件（已存在）: ${req.targetName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (!req.isDirectory) {
                        Text(
                            text = "大小: ${FormatUtils.formatBytes(req.targetSize)}  修改: ${dateFormat.format(Date(req.targetModifiedTime))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    // 三个单选操作
                    ConflictAction.entries.filter { it != ConflictAction.CANCEL }.forEach { action ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = action }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected == action,
                                onClick = { selected = action }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = when (action) {
                                    ConflictAction.REPLACE -> "替换目标文件"
                                    ConflictAction.RENAME -> "自动重命名"
                                    ConflictAction.SKIP -> "跳过该文件"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = {
                        FileOperationManager.onConflictResolved(ConflictResult(ConflictAction.CANCEL))
                    }) {
                        Text("取消", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = {
                        FileOperationManager.onConflictResolved(ConflictResult(selected))
                    }) {
                        Text("确认")
                    }
                }
            }
        )
    }
}

/**
 * 文件错误处理弹窗。
 */
@Composable
fun FileErrorDialog() {
    val request by FileOperationManager.errorRequest.collectAsState()

    request?.let { req ->
        AlertDialog(
            onDismissRequest = {
                FileOperationManager.onErrorResolved(ErrorResult(ErrorAction.CANCEL))
            },
            title = {
                Text(
                    text = "操作失败",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = req.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = req.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "请选择操作：",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                FileOperationManager.onErrorResolved(ErrorResult(ErrorAction.RETRY))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("重试")
                        }
                        OutlinedButton(
                            onClick = {
                                FileOperationManager.onErrorResolved(ErrorResult(ErrorAction.SKIP))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("跳过")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                FileOperationManager.onErrorResolved(ErrorResult(ErrorAction.SKIP_ALL))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("全部跳过")
                        }
                        OutlinedButton(
                            onClick = {
                                FileOperationManager.onErrorResolved(ErrorResult(ErrorAction.CANCEL))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("取消")
                        }
                    }
                }
            }
        )
    }
}
