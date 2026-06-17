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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whmdg.mczj.tools.ui.theme.LocalIsDarkMode
import com.whmdg.mczj.tools.AppDataPaths
import java.io.File

/**
 * 四次握手抓取结果弹窗
 * 展示 ANonce/SNonce/MIC/PMKID 等数据，支持复制和保存
 */
@Composable
fun HandshakeResultDialog(
    data: HandshakeCapture.HandshakeData,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isDarkMode = LocalIsDarkMode.current
    val scrollState = rememberScrollState()

    val labelColor = if (isDarkMode) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = if (isDarkMode) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.primary

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("四次握手捕获结果", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                // ── 基本信息 ──
                InfoSection("目标信息") {
                    InfoRow("SSID", data.ssid, labelColor, valueColor)
                    InfoRow("BSSID", data.bssid, labelColor, valueColor)
                    InfoRow("客户端", data.clientMac, labelColor, valueColor)
                    InfoRow("密钥版本", formatKeyVersion(data.keyVersion), labelColor, valueColor)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // ── 握手核心数据 ──
                InfoSection("握手数据") {
                    InfoRow("ANonce", data.aNonce, labelColor, valueColor)
                    InfoRow("SNonce", data.sNonce, labelColor, valueColor)
                    InfoRow("MIC", data.mic, labelColor, valueColor)
                    if (data.pmkid != null) {
                        InfoRow("PMKID", data.pmkid, labelColor, valueColor)
                    }
                    InfoRow("Key Info", "0x%04X".format(data.keyInfo), labelColor, valueColor)
                    InfoRow("Key Length", "${data.keyLen}", labelColor, valueColor)
                    InfoRow("Key Descriptor", "0x%02X".format(data.keyDescriptor), labelColor, valueColor)
                    InfoRow("EAPOL 版本", "${data.eapolVersion}", labelColor, valueColor)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // ── EAPOL 帧 ──
                InfoSection("EAPOL 帧 (${data.eapolFrames.size})") {
                    data.eapolFrames.forEachIndexed { index, hex ->
                        Text(
                            text = "Msg${index + 1}:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = labelColor,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            text = hex.take(120) + if (hex.length > 120) "..." else "",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = valueColor,
                            lineHeight = 14.sp
                        )
                    }
                }

                // ── pcap 路径 ──
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "PCAP: ${data.pcapFilePath}",
                    fontSize = 10.sp,
                    color = labelColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace
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
                    copyToClipboard(context, formatAllData(data))
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("复制全部")
                }
                TextButton(onClick = {
                    val savedPath = saveToFile(context, data)
                    Toast.makeText(
                        context,
                        if (savedPath != null) "已保存到 $savedPath" else "保存失败",
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("保存")
                }
            }
        }
    )
}

@Composable
private fun InfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Column(content = content)
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = labelColor
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

private fun formatKeyVersion(version: Int): String = when (version) {
    1 -> "RC4-MD5 (v1)"
    2 -> "HMAC-SHA1 (v2)"
    3 -> "HMAC-SHA256 (v3)"
    else -> "Unknown ($version)"
}

private fun formatAllData(data: HandshakeCapture.HandshakeData): String {
    return buildString {
        appendLine("=== WPA/WPA2 四次握手数据 ===")
        appendLine()
        appendLine("[目标信息]")
        appendLine("SSID: ${data.ssid}")
        appendLine("BSSID: ${data.bssid}")
        appendLine("客户端: ${data.clientMac}")
        appendLine("密钥版本: ${formatKeyVersion(data.keyVersion)}")
        appendLine("Key Info: 0x%04X".format(data.keyInfo))
        appendLine()
        appendLine("[握手数据]")
        appendLine("ANonce: ${data.aNonce}")
        appendLine("SNonce: ${data.sNonce}")
        appendLine("MIC: ${data.mic}")
        if (data.pmkid != null) {
            appendLine("PMKID: ${data.pmkid}")
        }
        appendLine("Key Length: ${data.keyLen}")
        appendLine("Key Descriptor: 0x%02X".format(data.keyDescriptor))
        appendLine("EAPOL Version: ${data.eapolVersion}")
        appendLine()
        appendLine("[EAPOL 帧]")
        data.eapolFrames.forEachIndexed { index, hex ->
            appendLine("Msg${index + 1}: $hex")
        }
        appendLine()
        appendLine("[文件]")
        appendLine("PCAP: ${data.pcapFilePath}")
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("handshake_data", text))
}

private fun saveToFile(context: Context, data: HandshakeCapture.HandshakeData): String? {
    return try {
        val dir = File(
            AppDataPaths.root(context),
            "capture"
        )
        dir.mkdirs()
        val file = File(dir, "handshake_${data.ssid}_${System.currentTimeMillis()}.txt")
        file.writeText(formatAllData(data))
        file.absolutePath
    } catch (_: Exception) {
        null
    }
}
