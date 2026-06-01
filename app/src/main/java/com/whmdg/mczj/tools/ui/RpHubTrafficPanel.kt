package com.whmdg.mczj.tools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

enum class TrafficMode { LOCAL, EXTERNAL }

data class TrafficEntry(
    val url: String,
    val method: String,
    val statusCode: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isLocal: Boolean
)

object TrafficLog {
    val enabled = mutableStateOf(false)
    val mode = mutableStateOf(TrafficMode.LOCAL)
    val entries: SnapshotStateList<TrafficEntry> = mutableStateListOf()

    fun add(entry: TrafficEntry) {
        if (!enabled.value) return
        val matchesMode = when (mode.value) {
            TrafficMode.LOCAL -> entry.isLocal
            TrafficMode.EXTERNAL -> !entry.isLocal
        }
        if (matchesMode) {
            entries.add(0, entry)
            if (entries.size > 500) entries.removeRange(400, entries.size)
        }
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

        // 开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("开启监控", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = TrafficLog.enabled.value,
                onCheckedChange = { TrafficLog.enabled.value = it }
            )
        }

        // 模式选择
        if (TrafficLog.enabled.value) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = TrafficLog.mode.value == TrafficMode.LOCAL,
                    onClick = { TrafficLog.mode.value = TrafficMode.LOCAL },
                    label = { Text("本地 HTTP") }
                )
                FilterChip(
                    selected = TrafficLog.mode.value == TrafficMode.EXTERNAL,
                    onClick = { TrafficLog.mode.value = TrafficMode.EXTERNAL },
                    label = { Text("外部 HTTP(S)") }
                )
            }
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
                    if (TrafficLog.enabled.value) "等待请求..." else "开启监控后显示请求",
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
                    TrafficEntryItem(entry, timeFmt)
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
}

@Composable
private fun TrafficEntryItem(entry: TrafficEntry, timeFmt: SimpleDateFormat) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 方法标签
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

            // 状态指示点
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (entry.isLocal) Color(0xFF4CAF50) else Color(0xFFFF9800))
            )

            // 时间
            Text(
                timeFmt.format(Date(entry.timestamp)),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }

        // URL
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
