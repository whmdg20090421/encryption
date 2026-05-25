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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 统一报错弹窗。标题"报错信息"，左下复制、右下关闭。
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
                        .heightIn(max = 300.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = error.stackTraceToString(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text("关闭")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    try {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = ClipData.newPlainText("Error Info", error.stackTraceToString())
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "错误信息已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }) {
                    Text("复制")
                }
            }
        )
    }
}
