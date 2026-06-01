package com.whmdg.mczj.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 全局未捕获异常显示 Activity。
 *
 * 由 ToolsApp 的 UncaughtExceptionHandler 启动，
 * 从 crash_tmp/latest_crash.txt 读取完整异常信息，
 * 用户可复制或关闭（回到上一个 Activity）。
 */
class ErrorReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val crashDir = File(filesDir, "crash_tmp")
        val crashFile = File(crashDir, "latest_crash.txt")
        val crashText = try {
            crashFile.readText()
        } catch (_: Exception) {
            "无法读取崩溃报告文件"
        }

        // 解析异常类型和消息（第一行和第二行）
        val lines = crashText.lines()
        val typeName = lines.firstOrNull { it.startsWith("异常类型:") }
            ?.substringAfter("异常类型:")?.trim() ?: "未知异常"
        val message = lines.firstOrNull { it.startsWith("消息:") }
            ?.substringAfter("消息:")?.trim() ?: ""

        setContent {
            MaterialTheme {
                ErrorReportScreen(
                    typeName = typeName,
                    message = message,
                    fullText = crashText
                )
            }
        }
    }
}

@Composable
private fun ErrorReportScreen(
    typeName: String,
    message: String,
    fullText: String
) {
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部标题栏
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = "未捕获异常",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = typeName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    if (message.isNotEmpty()) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                            maxLines = 3
                        )
                    }
                }
            }

            // 栈信息
            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = fullText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 底部按钮
            Surface(tonalElevation = 1.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            try {
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("Crash Report", fullText))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("复制")
                    }
                    Button(
                        onClick = {
                            (context as? ComponentActivity)?.finish()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}
