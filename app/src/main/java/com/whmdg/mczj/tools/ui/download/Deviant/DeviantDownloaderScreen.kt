package com.whmdg.mczj.tools.ui.download.Deviant

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whmdg.mczj.tools.ui.download.NetworkStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviantDownloaderScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    viewModel: DeviantDownloaderViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val logListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 自动滚动到最新日志
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) {
            logListState.animateScrollToItem(0)
        }
    }

    // 刷新认证状态并检查网络连接
    LaunchedEffect(Unit) {
        viewModel.refreshAuth()
        viewModel.checkNetworkStatus()
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
                title = { Text("DeviantArt 下载器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 网络/登录状态
                    when (state.networkStatus) {
                        NetworkStatus.CHECKING -> {
                            TextButton(onClick = { viewModel.checkNetworkStatus() }) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("检测中...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        NetworkStatus.NO_COOKIE -> {
                            TextButton(onClick = onLogin) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("未登录", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        NetworkStatus.NETWORK_DOWN -> {
                            TextButton(onClick = { viewModel.checkNetworkStatus() }) {
                                Icon(
                                    Icons.Default.CloudOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("网络断开", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        NetworkStatus.COOKIE_EXPIRED -> {
                            TextButton(onClick = onLogin) {
                                Icon(
                                    Icons.Default.VpnKeyOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Cookie失效", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        NetworkStatus.CONNECTED -> {
                            TextButton(onClick = onLogin) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("已登录", color = MaterialTheme.colorScheme.primary)
                            }
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
            // ── 头部卡片 ──
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
                                "DeviantArt 批量下载器",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "输入用户名，批量下载画廊/收藏作品",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // ── 用户名 & 画廊类型 ──
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionLabel("用户设置", Icons.Default.Person)
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = state.username,
                            onValueChange = { viewModel.updateUsername(it) },
                            label = { Text("用户名") },
                            placeholder = { Text("例如: username") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isDownloading && !state.isCollecting
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // 画廊类型选择
                        Text("画廊类型", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = state.galleryType == "gallery",
                                onClick = { viewModel.updateGalleryType("gallery") },
                                label = { Text("画廊") },
                                leadingIcon = if (state.galleryType == "gallery") {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null,
                                enabled = !state.isDownloading && !state.isCollecting
                            )
                            FilterChip(
                                selected = state.galleryType == "favourites",
                                onClick = { viewModel.updateGalleryType("favourites") },
                                label = { Text("收藏") },
                                leadingIcon = if (state.galleryType == "favourites") {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null,
                                enabled = !state.isDownloading && !state.isCollecting
                            )
                        }
                    }
                }
            }

            // ── 保存目录 & 设置 ──
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionLabel("下载设置", Icons.Default.Settings)
                        Spacer(modifier = Modifier.height(12.dp))

                        // 保存目录
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !state.isDownloading) {
                                    dirPickerLauncher.launch(null)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "保存目录",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        state.saveDirPath.ifEmpty { "点击选择目录" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 跳过已存在
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("跳过已下载", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = state.skipExisting,
                                onCheckedChange = { viewModel.updateSkipExisting(it) },
                                enabled = !state.isDownloading
                            )
                        }

                        // 线程数
                        Text(
                            "下载线程: ${state.downloadThreads}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = state.downloadThreads.toFloat(),
                            onValueChange = { viewModel.updateDownloadThreads(it.toInt()) },
                            valueRange = 1f..4f,
                            steps = 2,
                            enabled = !state.isDownloading
                        )
                    }
                }
            }

            // ── 控制按钮 ──
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // 状态消息
                        Text(
                            state.statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 进度条
                        if (state.isDownloading) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { state.currentProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "已下载: ${state.downloadedCount}  跳过: ${state.skippedCount}  失败: ${state.failedCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 按钮行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 收集按钮
                            if (!state.isCollecting && !state.isDownloading && !state.collectionComplete) {
                                Button(
                                    onClick = { viewModel.startCollect() },
                                    modifier = Modifier.weight(1f),
                                    enabled = state.username.isNotBlank() && state.saveDir != null && state.isLoggedIn
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("收集作品")
                                }
                            }

                            // 取消收集
                            if (state.isCollecting) {
                                Button(
                                    onClick = { viewModel.cancelCollect() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("取消收集")
                                }
                            }

                            // 开始下载
                            if (state.collectionComplete && !state.isDownloading && state.pendingTasks.isNotEmpty()) {
                                Button(
                                    onClick = { viewModel.startDownload() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("开始下载 (${state.pendingTasks.size})")
                                }
                            }

                            // 暂停/继续
                            if (state.isDownloading) {
                                if (state.isPaused) {
                                    Button(
                                        onClick = { viewModel.resumeDownload() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("继续")
                                    }
                                } else {
                                    Button(
                                        onClick = { viewModel.pauseDownload() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("暂停")
                                    }
                                }
                                OutlinedButton(
                                    onClick = { viewModel.stopDownload() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("停止")
                                }
                            }
                        }
                    }
                }
            }

            // ── 预览列表 ──
            if (state.showPreview && state.pendingTasks.isNotEmpty()) {
                item {
                    SectionLabel("待下载列表 (${state.pendingTasks.size})", Icons.Default.List)
                }
                items(state.pendingTasks.take(50)) { task ->
                    PreviewTaskItem(task = task)
                }
                if (state.pendingTasks.size > 50) {
                    item {
                        Text(
                            "... 还有 ${state.pendingTasks.size - 50} 个任务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }

            // ── 错误消息 ──
            if (state.errorMessage != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                state.errorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // ── 日志 ──
            if (state.logs.isNotEmpty()) {
                item {
                    SectionLabel("日志", Icons.Default.Info)
                }
                items(state.logs.size) { index ->
                    val log = state.logs[index]
                    Text(
                        text = log.message,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PreviewTaskItem(task: DeviantPreviewItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 序号
            Text(
                "${task.seq}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp)
            )

            // 图标
            Icon(
                Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))

            // 文件名和标题
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    task.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (task.title.isNotEmpty()) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
