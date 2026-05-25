package com.whmdg.mczj.tools.ui

import android.content.ClipData
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 统一报错弹窗。标题"报错信息"，左下复制、右下关闭。
 * 错误信息包含完整栈 + cause 链 + 本地化消息。
 */
@Composable
fun ErrorDialog(
    error: Throwable?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    if (error != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedCornerShape(28.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "报错信息",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            text = {
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = buildFullErrorText(error),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = ClipData.newPlainText("Error Info", buildFullErrorText(error))
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "错误信息已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }) {
                    Text("复制")
                }
            },
            dismissButton = {
                Button(onClick = onDismiss) {
                    Text("取消")
                }
            }
        )
    }
}

private fun buildFullErrorText(error: Throwable): String {
    val sb = StringBuilder()
    sb.appendLine("异常类型: ${error.javaClass.name}")
    sb.appendLine("消息: ${error.message ?: "(无消息)"}")
    sb.appendLine()
    sb.appendLine("--- 完整栈 ---")
    sb.appendLine(error.stackTraceToString())
    var cause = error.cause
    var depth = 1
    while (cause != null && cause !== error) {
        sb.appendLine()
        sb.appendLine("--- 原因 #$depth: ${cause.javaClass.name} ---")
        sb.appendLine(cause.stackTraceToString())
        cause = cause.cause
        depth++
    }
    return sb.toString()
}
