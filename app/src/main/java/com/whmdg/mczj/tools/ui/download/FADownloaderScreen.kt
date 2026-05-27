package com.whmdg.mczj.tools.ui.download

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FADownloaderScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    viewModel: FADownloaderViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val logListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to latest log
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) {
            logListState.animateScrollToItem(state.logs.size - 1)
        }
    }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val path = it.path ?: it.toString()
            viewModel.updateSaveDir(it, path)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FA 下载器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // Login status indicator
                    if (state.isLoggedIn) {
                        IconButton(onClick = onLogin) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "已登录",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        TextButton(onClick = onLogin) {
                            Icon(
                                Icons.AutoMirrored.Filled.Login,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("登录")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Header Card ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Fur Affinity 批量下载器",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "输入作者名，批量下载 Gallery / Scraps 作品",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // ── Author & Type ──
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionLabel("作者设置", Icons.Default.Person)
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = state.author,
                            onValueChange = { viewModel.updateAuthor(it) },
                            label = { Text("作者名") },
                            placeholder = { Text("例如: username") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isDownloading
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            "下载类型",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = state.downloadType == "gallery",
                                onClick = { if (!state.isDownloading) viewModel.updateType("gallery") },
                                label = { Text("Gallery") },
                                leadingIcon = if (state.downloadType == "gallery") {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                            FilterChip(
                                selected = state.downloadType == "scraps",
                                onClick = { if (!state.isDownloading) viewModel.updateType("scraps") },
                                label = { Text("Scraps") },
                                leadingIcon = if (state.downloadType == "scraps") {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }
                }
            }

            // ── Download Options ──
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionLabel("下载选项", Icons.Default.Tune)
                        Spacer(modifier = Modifier.height(12.dp))

                        // Save directory
                        OutlinedCard(
                            onClick = { if (!state.isDownloading) dirPickerLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isDownloading
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "保存目录",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        if (state.saveDirPath.isNotEmpty()) state.saveDirPath else "点击选择目录",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (state.saveDirPath.isNotEmpty())
                                            MaterialTheme.colorScheme.onSurface
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Start page & max download
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = state.startPage,
                                onValueChange = { viewModel.updateStartPage(it) },
                                label = { Text("起始页") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                enabled = !state.isDownloading
                            )
                            OutlinedTextField(
                                value = state.maxDownload,
                                onValueChange = { viewModel.updateMaxDownload(it) },
                                label = { Text("最大下载量") },
                                placeholder = { Text("0 = 不限") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                enabled = !state.isDownloading
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Switches
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Switch(
                                    checked = state.skipExisting,
                                    onCheckedChange = { viewModel.updateSkipExisting(it) },
                                    enabled = !state.isDownloading
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("跳过已存在", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Switch(
                                    checked = state.useCache,
                                    onCheckedChange = { viewModel.updateUseCache(it) },
                                    enabled = !state.isDownloading
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("使用缓存", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // ── Control Buttons ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { viewModel.startDownload() },
                        modifier = Modifier.weight(1f),
                        enabled = !state.isDownloading
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("开始下载")
                    }
                    OutlinedButton(
                        onClick = { viewModel.stopDownload() },
                        modifier = Modifier.weight(1f),
                        enabled = state.isDownloading
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("停止")
                    }
                }
            }

            // ── Progress & Stats ──
            item {
                AnimatedVisibility(visible = state.isDownloading || state.downloadedCount > 0 || state.statusMessage != "准备就绪") {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Progress bar
                            if (state.isDownloading) {
                                LinearProgressIndicator(
                                    progress = { state.currentProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Status
                            Text(
                                state.statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Stats row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                StatBadge("已下载", state.downloadedCount, MaterialTheme.colorScheme.primary)
                                StatBadge("跳过", state.skippedCount, MaterialTheme.colorScheme.tertiary)
                                StatBadge("失败", state.failedCount, MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // ── Log Section ──
            item {
                SectionLabel("下载日志", Icons.Default.Terminal)
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 400.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    if (state.logs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "准备就绪",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            state = logListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(state.logs) { log ->
                                LogLine(log.message)
                            }
                        }
                    }
                }
            }

            // Bottom spacer
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SectionLabel(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun StatBadge(label: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$count",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LogLine(message: String) {
    val color = when {
        message.startsWith("  ✓") -> MaterialTheme.colorScheme.primary
        message.startsWith("  ✗") -> MaterialTheme.colorScheme.error
        message.contains("错误") || message.contains("失败") || message.contains("异常") -> MaterialTheme.colorScheme.error
        message.contains("完成") -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        ),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
