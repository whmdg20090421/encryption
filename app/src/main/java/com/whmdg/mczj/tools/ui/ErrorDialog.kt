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
 * 统一报错弹窗。布局策略：
 * - confirmButton 只放"关闭"，避免被 M3 actions 槽位挤掉
 * - "复制"和"Debug"放进正文区，占完整对话框宽度
 * - 错误出现时自动落盘到外部 + 内部双目录，toast 显示实际路径
 */
@Composable
fun ErrorDialog(
    error: Throwable?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var savedFile by remember(error) { mutableStateOf<File?>(null) }
    var saveAttempted by remember(error) { mutableStateOf(false) }

    LaunchedEffect(error) {
        if (error != null && !saveAttempted) {
            saveAttempted = true
            DiagnosticLog.log("ErrorDialog", "捕获错误: ${error.javaClass.simpleName}: ${error.message}")
            val f = DiagnosticLog.exportCrashReport(
                context,
                error,
                extraContext = "来源: ErrorDialog (UI 流程异常)"
            )
            savedFile = f
            if (f != null) {
                Toast.makeText(context, "Debug 已保存: ${f.parent}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Debug 文件保存失败（看 logcat）", Toast.LENGTH_LONG).show()
            }
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
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 错误信息（可滚动）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
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
                            when {
                                f != null -> Text(
                                    text = "Debug 报告:\n${f.absolutePath}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                saveAttempted -> Text(
                                    text = "(Debug 报告写入失败，请看 logcat)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                else -> Text(
                                    text = "(Debug 报告写入中…)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // 操作按钮（放正文区，占满宽度，永远可见）
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cb.setPrimaryClip(ClipData.newPlainText("Error Info", buildShortErrorText(error)))
                                    Toast.makeText(context, "错误信息已复制", Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("复制")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                val f = savedFile
                                if (f != null) {
                                    try {
                                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        cb.setPrimaryClip(ClipData.newPlainText("Debug Path", f.absolutePath))
                                        Toast.makeText(context, "Debug 路径已复制", Toast.LENGTH_LONG).show()
                                    } catch (_: Exception) {}
                                } else {
                                    Toast.makeText(context, "诊断尚未写入完成", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Debug")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        )
    }
}

private fun buildShortErrorText(error: Throwable): String {
    val sb = StringBuilder()
    sb.appendLine("异常类型: ${error.javaClass.name}")
    sb.appendLine("消息: ${error.message ?: "(无消息)"}")
    return sb.toString().trimEnd()
}
