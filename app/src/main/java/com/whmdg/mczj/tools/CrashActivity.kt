package com.whmdg.mczj.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.OnBackPressedCallback
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import java.io.File
import java.io.FileOutputStream

/**
 * Native 崩溃显示 Activity。
 *
 * 从 Native 信号处理器接收崩溃信息（通过 pipe1），
 * 显示全屏错误界面，用户可复制信息或退出应用。
 *
 * 退出时通过 pipe2 通知 Native 信号处理器执行 _exit()。
 */
class CrashActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CRASH_INFO = "crash_info"
        const val EXTRA_EXIT_WRITE_FD = "exit_write_fd"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        /* 禁用返回键 */
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                /* 不做任何事，阻止返回 */
            }
        })

        val crashInfo = intent.getStringExtra(EXTRA_CRASH_INFO) ?: "未知崩溃"
        val exitWriteFd = intent.getIntExtra(EXTRA_EXIT_WRITE_FD, -1)

        /* 解析崩溃信息 */
        val parsed = parseCrashInfo(crashInfo)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CrashScreen(
                    signalName = parsed.signalName,
                    signalNumber = parsed.signalNumber,
                    timestamp = parsed.timestamp,
                    errnoValue = parsed.errnoValue,
                    rawInfo = crashInfo,
                    exitWriteFd = exitWriteFd
                )
            }
        }
    }

    /**
     * 解析 Native 信号处理器发来的崩溃信息。
     * 格式: SIGNO|SIGNAME|TIMESTAMP|ERRNO_NUM
     */
    private fun parseCrashInfo(info: String): CrashInfo {
        val parts = info.split("|")
        return if (parts.size >= 4) {
            CrashInfo(
                signalNumber = parts[0].toIntOrNull() ?: 0,
                signalName = parts[1],
                timestamp = parts[2].toLongOrNull() ?: 0L,
                errnoValue = parts[3].toIntOrNull() ?: 0
            )
        } else {
            CrashInfo(0, "UNKNOWN", 0L, 0)
        }
    }

    private data class CrashInfo(
        val signalNumber: Int,
        val signalName: String,
        val timestamp: Long,
        val errnoValue: Int
    )
}

@Composable
private fun CrashScreen(
    signalName: String,
    signalNumber: Int,
    timestamp: Long,
    errnoValue: Int,
    rawInfo: String,
    exitWriteFd: Int
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    /* 格式化时间戳 */
    val timeStr = if (timestamp > 0) {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        sdf.format(java.util.Date(timestamp * 1000))
    } else {
        "未知"
    }

    /* 构建完整错误文本 */
    val fullErrorText = buildString {
        appendLine("=== Native Crash Report ===")
        appendLine("信号: $signalName (SIG #$signalNumber)")
        appendLine("时间: $timeStr")
        appendLine("Errno: $errnoValue")
        appendLine("原始数据: $rawInfo")
    }

    /* 保存到文件 */
    var savedPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            val dir = File(AppDataPaths.diagnostics(context), "crash_reports")
            dir.mkdirs()
            val file = File(dir, "native_crash_${timestamp}.txt")
            FileOutputStream(file).use { it.write(fullErrorText.toByteArray()) }
            savedPath = file.absolutePath
        } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.errorContainer.copy(alpha = 0.3f))
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            /* 标题 */
            Text(
                text = "Native 层崩溃",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colorScheme.error
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "应用底层发生不可恢复的错误，已终止运行。",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onErrorContainer.copy(alpha = 0.8f)
            )

            Spacer(Modifier.height(24.dp))

            /* 崩溃信息卡片 */
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.errorContainer.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow("信号", "$signalName (#$signalNumber)")
                    InfoRow("时间", timeStr)
                    InfoRow("Errno", "$errnoValue")
                }
            }

            /* 保存路径 */
            savedPath?.let { path ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "报告已保存: $path",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.weight(1f))

            /* 操作按钮 */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                /* 左下角：复制按钮 */
                OutlinedButton(
                    onClick = {
                        try {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("Crash Report", fullErrorText))
                            Toast.makeText(context, "崩溃信息已复制", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = colorScheme.error
                    )
                ) {
                    Text("复制")
                }

                /* 右下角：退出应用按钮 */
                Button(
                    onClick = {
                        notifyNativeAndExit(exitWriteFd)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.error
                    )
                ) {
                    Text("退出应用")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = colorScheme.onErrorContainer
        )
    }
}

/**
 * 通知 Native 层退出：向 pipe2 写入一个字节，信号处理器收到后 _exit()。
 * 如果写入失败，直接调用 exitProcess 兜底。
 */
private fun notifyNativeAndExit(exitWriteFd: Int) {
    try {
        if (exitWriteFd >= 0) {
            /* 通过 ParcelFileDescriptor 获取 FileDescriptor，写入退出信号 */
            val pfd = ParcelFileDescriptor.fromFd(exitWriteFd)
            android.system.Os.write(pfd.fileDescriptor, byteArrayOf(1), 0, 1)
            pfd.close()
        }
    } catch (_: Exception) {
        /* 如果 pipe 写入失败，兜底直接退出 */
    }
    kotlin.system.exitProcess(128)
}
