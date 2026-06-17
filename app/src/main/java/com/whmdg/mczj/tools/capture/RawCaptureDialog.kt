package com.whmdg.mczj.tools.capture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whmdg.mczj.tools.AppDataPaths
import java.io.File

/**
 * 原始抓包数据弹窗（调试模式）
 * 展示 pcap base64 数据，支持复制和保存
 */
@Composable
fun RawCaptureDialog(
    b64Data: String,
    dataSize: Int,
    onDismiss: () -> Unit
) {
    val hexData = try {
        val bytes = android.util.Base64.decode(b64Data, android.util.Base64.DEFAULT)
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    } catch (_: Exception) { "(解码失败)" }
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("原始抓包数据（调试）", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = "pcap 大小: ${dataSize} 字节",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Base64 数据:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = b64Data.take(500) + if (b64Data.length > 500) "\n... (共 ${b64Data.length} 字符)" else "",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 14.sp
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Hex 预览 (前 256 字节):",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = hexData.take(512) + if (hexData.length > 512) "..." else "",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 14.sp
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    copyToClipboard(context, b64Data)
                    Toast.makeText(context, "Base64 已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("复制 Base64")
                }
                TextButton(onClick = {
                    val savedPath = saveToFile(context, b64Data, dataSize)
                    Toast.makeText(
                        context,
                        if (savedPath != null) "已保存到 $savedPath" else "保存失败",
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("保存")
                }
            }
        }
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("raw_pcap", text))
}

private fun saveToFile(context: Context, b64Data: String, dataSize: Int): String? {
    return try {
        val dir = File(AppDataPaths.root(context), "capture")
        dir.mkdirs()
        val ts = System.currentTimeMillis()
        val file = File(dir, "raw_pcap_${ts}.txt")
        file.writeText(buildString {
            appendLine("=== 原始抓包数据 (调试) ===")
            appendLine("大小: $dataSize 字节")
            appendLine()
            appendLine("[Base64]")
            appendLine(b64Data)
        })
        file.absolutePath
    } catch (_: Exception) {
        null
    }
}
