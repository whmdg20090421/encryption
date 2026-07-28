package com.whmdg.mczj.tools.ui.filemanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.whmdg.mczj.tools.ui.theme.DialogWidthFraction
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

        Dialog(onDismissRequest = { /* 不可点击外部关闭 */ }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Card(
                modifier = Modifier.fillMaxWidth(DialogWidthFraction),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "文件冲突",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )

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

                    HorizontalDivider()

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

                    HorizontalDivider()

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

                    // 按钮
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
            }
        }
    }
}

/**
 * 文件错误处理弹窗。
 * 有详情时显示"详情"蓝色按钮，点击打开详情面板。
 */
@Composable
fun FileErrorDialog() {
    val request by FileOperationManager.errorRequest.collectAsState()

    request?.let { req ->
        var showDetail by remember(req) { mutableStateOf(false) }

        if (showDetail) {
            ErrorDetailDialog(
                errorMessage = req.errorMessage,
                detailMessage = req.detailMessage,
                onDismiss = { showDetail = false }
            )
        }

        Dialog(onDismissRequest = { FileOperationManager.onErrorResolved(ErrorResult(ErrorAction.CANCEL)) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Card(
                modifier = Modifier.fillMaxWidth(DialogWidthFraction),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "操作失败",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )

                    if (req.fileName.isNotEmpty()) {
                        Text(
                            text = req.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = req.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (req.detailMessage.isNotEmpty()) {
                            TextButton(
                                onClick = { showDetail = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("详情", fontSize = 13.sp)
                            }
                        }
                    }

                    Text(
                        text = "请选择操作：",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )

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
            }
        }
    }
}

/**
 * 错误详情弹窗：显示完整错误链 + 复制按钮。
 */
@Composable
private fun ErrorDetailDialog(
    errorMessage: String,
    detailMessage: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val fullText = buildString {
        appendLine("错误信息：")
        appendLine(errorMessage)
        if (detailMessage.isNotEmpty()) {
            appendLine()
            appendLine("详细信息：")
            appendLine(detailMessage)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxWidth(DialogWidthFraction),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "错误详情",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Text(
                        text = fullText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        copyToClipboard(context, fullText)
                    }) {
                        Text("复制")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("错误详情", text))
    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
}
