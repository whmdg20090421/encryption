package com.whmdg.mczj.tools.ui.download

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.ui.Screen
import com.whmdg.mczj.tools.ui.components.GlowCard
import com.whmdg.mczj.tools.ui.components.GlowSection
import com.whmdg.mczj.tools.ui.components.GlowListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchDownloaderScreen(
    onBack: () -> Unit,
    onNavigate: (Screen) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("批量下载器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 下载工具
            SettingsSection(
                title = "下载工具",
                icon = Icons.Default.CloudDownload
            ) {
                CompactSettingsItem(
                    title = "FA 下载器",
                    subtitle = "Fur Affinity 图片批量下载",
                    icon = Icons.Default.Palette,
                    onClick = { onNavigate(Screen.FADownloader) }
                )
                CompactSettingsItem(
                    title = "DeviantArt 下载器",
                    subtitle = "DeviantArt 作品批量下载",
                    icon = Icons.Default.Brush,
                    onClick = { onNavigate(Screen.DeviantDownloader) }
                )
            }

            // 更多工具提示
            GlowCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFF38D4F5),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "更多下载工具正在开发中...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0x9964B4D2)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    GlowSection(
        title = title,
        icon = icon,
        content = content
    )
}

@Composable
private fun CompactSettingsItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    GlowListItem(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = onClick
    )
}
