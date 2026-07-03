package com.whmdg.mczj.tools.ui.rphub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

data class DownloadEntry(
    val fileName: String,
    val externalPath: String,
    val internalPath: String,
    val timestamp: Long = System.currentTimeMillis()
)

object DownloadLog {
    val entries = mutableStateListOf<DownloadEntry>()

    fun add(entry: DownloadEntry) {
        entries.add(0, entry)
    }

    fun clear() {
        entries.clear()
    }
}

@Composable
fun RpHubDownloadPanel(
    onDismiss: () -> Unit,
    onSaveAs: (DownloadEntry) -> Unit
) {
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
                "下载管理",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = { DownloadLog.clear() }) {
                Text("清空")
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "${DownloadLog.entries.size} 个文件",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        if (DownloadLog.entries.isEmpty()) {
            Text(
                "暂无下载记录",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 32.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                items(DownloadLog.entries) { entry ->
                    DownloadEntryItem(entry, onSaveAs)
                    HorizontalDivider()
                }
            }
        }

        Spacer(Modifier.height(8.dp))

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
private fun DownloadEntryItem(
    entry: DownloadEntry,
    onSaveAs: (DownloadEntry) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：文件名
        Text(
            entry.fileName,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(Modifier.width(8.dp))

        // 中间：绝对路径
        Text(
            entry.externalPath,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.5f)
        )

        Spacer(Modifier.width(8.dp))

        // 右侧：另存为按钮
        OutlinedButton(
            onClick = { onSaveAs(entry) },
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("另存为", fontSize = 11.sp)
        }
    }
}
