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
import com.whmdg.mczj.tools.util.DiagnosticLog
import java.io.File

/**
 * 统一报错弹窗。
 * - 弹出时显示原本简短的异常信息（类型 + 消息），便于用户直接看
 * - 同时自动把完整诊断（栈 + 事件追踪 + 全线程栈 + 设备信息）落盘到 filesDir/debug_logs/
 * - 提供 "Debug" 按钮：复制诊断文件路径到剪贴板，方便用户找到完整报告
 */
@Composable
fun ErrorDialog(
    error: Throwable?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var savedFile by remember(error) { mutableStateOf<File?>(null) }

    // 错误第一次出现时自动落盘
    LaunchedEffect(error) {
        if (error != null && savedFile == null) {
            DiagnosticLog.log("ErrorDialog", "捕获错误: ${error.javaClass.simpleName}: ${error.message}")
            savedFile = DiagnosticLog.exportCrashReport(
                context,
                error,
                extraContext = "来源: ErrorDialog (UI 流程异常)"
            )
        }
    }

    if (error != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(
                    text = "报错信息",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(scrollState)
                ) {
                    Column {
                        Text(
                            text = buildShortErrorText(error),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(12.dp))
                        val f = savedFile
                        if (f != null) {
                            Text(
                                text = "Debug 报告已保存:\n${f.absolutePath}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = "(Debug 报告写入中…)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Error Info", buildShortErrorText(error)))
                        Toast.makeText(context, "错误信息已复制", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }) {
                    Text("复制")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val f = savedFile
                        if (f != null) {
                            try {
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("Debug Path", f.absolutePath))
                                Toast.makeText(context, "Debug 路径已复制: ${f.name}", Toast.LENGTH_LONG).show()
                            } catch (_: Exception) {}
                        } else {
                            Toast.makeText(context, "诊断尚未写入完成", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("Debug")
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        )
    }
}

/** 报错弹窗上只显示这些 —— 类型 + 消息，简短。完整内容在落盘的 txt 里。 */
private fun buildShortErrorText(error: Throwable): String {
    val sb = StringBuilder()
    sb.appendLine("异常类型: ${error.javaClass.name}")
    sb.appendLine("消息: ${error.message ?: "(无消息)"}")
    return sb.toString().trimEnd()
}
