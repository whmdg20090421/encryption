package com.whmdg.mczj.tools.ui

import android.content.ClipData
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.whmdg.mczj.tools.ui.theme.DialogWidthFraction

/**
 * 消息弹窗数据类
 */
data class MessageDialogData(
    val title: String,
    val command: String = "",
    val output: String = "",
    val errorMessage: String = ""
)

/**
 * 通用消息弹窗。
 * 用于显示可预料到的错误或信息提示（如压缩包提取失败、密码错误等）。
 *
 * @param data 弹窗数据
 * @param onDismiss 关闭弹窗回调
 */
@Composable
fun MessageDialog(
    data: MessageDialogData,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = { onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(DialogWidthFraction),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 标题
                Text(
                    text = data.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )

                // 详细信息（可滚动）
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(scrollState)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 执行的命令
                        if (data.command.isNotEmpty()) {
                            Column {
                                Text(
                                    text = "执行命令：",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = data.command,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 输出结果
                        if (data.output.isNotEmpty()) {
                            Column {
                                Text(
                                    text = "输出结果：",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = data.output,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 错误信息
                        if (data.errorMessage.isNotEmpty()) {
                            Column {
                                Text(
                                    text = "错误信息：",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = data.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    // 复制按钮
                    OutlinedButton(
                        onClick = {
                            try {
                                val fullText = buildString {
                                    appendLine("标题: ${data.title}")
                                    if (data.command.isNotEmpty()) {
                                        appendLine("命令: ${data.command}")
                                    }
                                    if (data.output.isNotEmpty()) {
                                        appendLine("输出: ${data.output}")
                                    }
                                    if (data.errorMessage.isNotEmpty()) {
                                        appendLine("错误: ${data.errorMessage}")
                                    }
                                }
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("Message Info", fullText))
                                Toast.makeText(context, "信息已复制", Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("复制")
                    }

                    // 确认按钮
                    Button(
                        onClick = { onDismiss() }
                    ) {
                        Text("确认")
                    }
                }
            }
        }
    }
}
