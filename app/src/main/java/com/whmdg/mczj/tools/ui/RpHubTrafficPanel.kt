package com.whmdg.mczj.tools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TrafficEntry(
    val url: String,
    val method: String,
    val statusCode: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isLocal: Boolean,
    // 请求详情
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestParams: Map<String, String> = emptyMap(),
    val requestBody: String? = null,
    val cookies: String? = null,
    // 响应详情（仅本地请求可获取）
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBody: String? = null,
    val responseTime: Long? = null
)

object TrafficLog {
    val localEnabled = mutableStateOf(false)
    val externalEnabled = mutableStateOf(false)
    val entries: SnapshotStateList<TrafficEntry> = mutableStateListOf()

    fun add(entry: TrafficEntry) {
        val shouldCapture = if (entry.isLocal) localEnabled.value else externalEnabled.value
        if (!shouldCapture) return
        entries.add(0, entry)
        if (entries.size > 500) entries.removeRange(400, entries.size)
    }

    fun clear() {
        entries.clear()
    }
}

@Composable
fun RpHubTrafficPanel(
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    var selectedEntry by remember { mutableStateOf<TrafficEntry?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Web 面板",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = { TrafficLog.clear() }) {
                Text("清空")
            }
        }

        Spacer(Modifier.height(8.dp))

        // 本地 HTTP 开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("本地 HTTP", style = MaterialTheme.typography.bodyLarge)
                Text("localhost 请求", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = TrafficLog.localEnabled.value,
                onCheckedChange = { TrafficLog.localEnabled.value = it }
            )
        }

        Spacer(Modifier.height(4.dp))

        // 外部 HTTP(S) 开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("外部 HTTP(S)", style = MaterialTheme.typography.bodyLarge)
                Text("CDN、API 等外部请求（忽略 SSL）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = TrafficLog.externalEnabled.value,
                onCheckedChange = { TrafficLog.externalEnabled.value = it }
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        // 请求数量
        Text(
            "${TrafficLog.entries.size} 条请求",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(4.dp))

        // 请求列表
        if (TrafficLog.entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (TrafficLog.localEnabled.value || TrafficLog.externalEnabled.value) "等待请求..." else "开启监控后显示请求",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                items(TrafficLog.entries) { entry ->
                    TrafficEntryItem(entry, timeFmt) { selectedEntry = entry }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 关闭按钮
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("关闭")
        }

        Spacer(Modifier.height(8.dp))
    }

    // 详情对话框
    selectedEntry?.let { entry ->
        TrafficDetailDialog(entry = entry, timeFmt = timeFmt) { selectedEntry = null }
    }
}

@Composable
private fun TrafficEntryItem(
    entry: TrafficEntry,
    timeFmt: SimpleDateFormat,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val methodColor = when (entry.method.uppercase()) {
                "GET" -> Color(0xFF4CAF50)
                "POST" -> Color(0xFF2196F3)
                "PUT" -> Color(0xFFFF9800)
                "DELETE" -> Color(0xFFF44336)
                else -> Color.Gray
            }
            Text(
                entry.method.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .background(methodColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )

            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (entry.isLocal) Color(0xFF4CAF50) else Color(0xFFFF9800))
            )

            Text(
                timeFmt.format(Date(entry.timestamp)),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )

            entry.statusCode?.let {
                Text(
                    it.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (it < 400) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }
        }

        Text(
            entry.url,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    HorizontalDivider()
}

@Composable
private fun TrafficDetailDialog(
    entry: TrafficEntry,
    timeFmt: SimpleDateFormat,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current

    fun buildCopyText(): String {
        val sb = StringBuilder()
        sb.appendLine("${entry.method.uppercase()} ${entry.url}")
        sb.appendLine("时间: ${timeFmt.format(Date(entry.timestamp))}")
        sb.appendLine("类型: ${if (entry.isLocal) "本地" else "外部"}")
        entry.statusCode?.let { sb.appendLine("状态码: $it") }
        entry.responseTime?.let { sb.appendLine("耗时: ${it}ms") }
        if (entry.requestHeaders.isNotEmpty()) {
            sb.appendLine("\n--- 请求头 ---")
            entry.requestHeaders.forEach { (k, v) -> sb.appendLine("$k: $v") }
        }
        if (entry.requestParams.isNotEmpty()) {
            sb.appendLine("\n--- 请求参数 ---")
            entry.requestParams.forEach { (k, v) -> sb.appendLine("$k=$v") }
        }
        if (!entry.cookies.isNullOrEmpty()) {
            sb.appendLine("\n--- Cookie ---")
            sb.appendLine(entry.cookies)
        }
        if (!entry.requestBody.isNullOrEmpty()) {
            sb.appendLine("\n--- 请求体 ---")
            sb.appendLine(entry.requestBody)
        }
        if (entry.responseHeaders.isNotEmpty()) {
            sb.appendLine("\n--- 响应头 ---")
            entry.responseHeaders.forEach { (k, v) -> sb.appendLine("$k: $v") }
        }
        if (!entry.responseBody.isNullOrEmpty()) {
            sb.appendLine("\n--- 响应体 ---")
            sb.appendLine(entry.responseBody.take(5000))
        }
        return sb.toString().trimEnd()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // 标题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "请求详情",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            try {
                                val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cb.setPrimaryClip(android.content.ClipData.newPlainText("Traffic", buildCopyText()))
                                android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {}
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("关闭")
                        }
                    }
                }

                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(500.dp)
                        .verticalScroll(scrollState)
                ) {
                    // 基本信息
                    DetailSection("基本信息") {
                        DetailRow("方法", entry.method.uppercase())
                        DetailRow("URL", entry.url)
                        DetailRow("时间", timeFmt.format(Date(entry.timestamp)))
                        DetailRow("类型", if (entry.isLocal) "本地" else "外部")
                        entry.statusCode?.let { DetailRow("状态码", it.toString()) }
                        entry.responseTime?.let { DetailRow("耗时", "${it}ms") }
                    }

                    // 请求头
                    if (entry.requestHeaders.isNotEmpty()) {
                        DetailSection("请求头") {
                            entry.requestHeaders.forEach { (k, v) ->
                                DetailRow(k, v)
                            }
                        }
                    }

                    // 请求参数
                    if (entry.requestParams.isNotEmpty()) {
                        DetailSection("请求参数") {
                            entry.requestParams.forEach { (k, v) ->
                                DetailRow(k, v)
                            }
                        }
                    }

                    // Cookie
                    if (!entry.cookies.isNullOrEmpty()) {
                        DetailSection("Cookie") {
                            Text(
                                entry.cookies,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // 请求体
                    if (!entry.requestBody.isNullOrEmpty()) {
                        DetailSection("请求体") {
                            Text(
                                entry.requestBody,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // 响应头
                    if (entry.responseHeaders.isNotEmpty()) {
                        DetailSection("响应头") {
                            entry.responseHeaders.forEach { (k, v) ->
                                DetailRow(k, v)
                            }
                        }
                    }

                    // 响应体
                    if (!entry.responseBody.isNullOrEmpty()) {
                        DetailSection("响应体") {
                            Text(
                                entry.responseBody.take(5000),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    content()
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    ) {
        Text(
            "$label: ",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        Text(
            value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
