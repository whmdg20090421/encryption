package com.whmdg.mczj.tools.ui.hook.内存管理

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel

private val AccentPrimary = Color(0xFF7C3AED)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryUsageScreen(
    onBack: () -> Unit,
    viewModel: MemoryUsageViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showNoRootDialog by remember { mutableStateOf(false) }
    var showKernelDetail by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                Toast.makeText(context, "数据可能已变化，可点击右上角刷新", Toast.LENGTH_SHORT).show()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState.rootAvailable) {
        if (!uiState.rootAvailable && !uiState.isLoading) {
            showNoRootDialog = true
        }
    }

    // 无 root 弹窗
    if (showNoRootDialog) {
        AlertDialog(
            onDismissRequest = {
                showNoRootDialog = false
                onBack()
            },
            icon = {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("需要 Root 权限") },
            text = { Text("该功能需要 Root 权限才能查看完整进程内存信息") },
            confirmButton = {
                TextButton(onClick = {
                    showNoRootDialog = false
                    onBack()
                }) { Text("确认") }
            }
        )
    }

    // 内核详情页
    if (showKernelDetail) {
        KernelDetailScreen(
            kernel = uiState.kernelInfo,
            onBack = { showKernelDetail = false }
        )
        return
    }

    // 清理缓存确认弹窗
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isClearing) showClearCacheDialog = false
            },
            icon = {
                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = AccentPrimary)
            },
            title = { Text("清理缓存") },
            text = {
                if (isClearing) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                            color = AccentPrimary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("正在清理缓存…")
                    }
                } else {
                    Text("是否使用 Root 权限清理缓存？\n\n清理后文件缓存将被释放，已打开的应用可能需要重新加载资源。")
                }
            },
            confirmButton = {
                if (!isClearing) {
                    TextButton(onClick = {
                        isClearing = true
                        viewModel.clearCache { success ->
                            isClearing = false
                            showClearCacheDialog = false
                            if (!success) {
                                Toast.makeText(context, "清理失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("确认清理") }
                }
            },
            dismissButton = {
                if (!isClearing) {
                    TextButton(onClick = { showClearCacheDialog = false }) { Text("取消") }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("内存占用查询") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val text = viewModel.buildCopyText()
                        clipboardManager.setText(AnnotatedString(text))
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { viewModel.loadMemoryInfo() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedVisibility(visible = uiState.isLoading, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentPrimary, strokeWidth = 3.dp)
                }
            }

            AnimatedVisibility(
                visible = !uiState.isLoading && uiState.rootAvailable && uiState.processList.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                MemoryContent(
                    uiState = uiState,
                    onKernelClick = { showKernelDetail = true },
                    onCacheClick = { showClearCacheDialog = true }
                )
            }

            AnimatedVisibility(visible = !uiState.isLoading && uiState.error != null, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = uiState.error ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryContent(
    uiState: MemoryUsageUiState,
    onKernelClick: () -> Unit,
    onCacheClick: () -> Unit
) {
    val cardBgColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surface else Color.White

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // ── 总览卡片 ──
        SummaryCard(uiState, cardBgColor)

        Spacer(modifier = Modifier.height(20.dp))

        // ── 三分类卡片 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardBgColor)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 内核
                KernelRow(uiState.kernelInfo, onClick = onKernelClick)
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.5.dp
                )
                // 可清理缓存
                CacheRow(uiState.cacheInfo, onClick = onCacheClick)
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.5.dp
                )
                // 进程列表
                uiState.processList.forEachIndexed { index, process ->
                    ProcessRow(process)
                    if (index < uiState.processList.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ==================== 总览卡片 ====================

@Composable
private fun SummaryCard(uiState: MemoryUsageUiState, cardBgColor: Color) {
    val totalRamGb = uiState.totalRamKb / 1024.0 / 1024.0
    val realUsedGb = uiState.realUsedKb / 1024.0 / 1024.0
    val availableGb = uiState.memAvailableKb / 1024.0 / 1024.0
    val percent = if (uiState.totalRamKb > 0) uiState.realUsedKb * 100.0 / uiState.totalRamKb else 0.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "总内存: ${formatSize(totalRamGb)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "已用: ${formatSize(realUsedGb)} (${String.format("%.1f", percent)}%)",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentPrimary
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "可用: ${formatSize(availableGb)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = AccentPrimary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
    }
}

// ==================== 内核行 ====================

@Composable
private fun KernelRow(kernel: KernelMemoryBreakdown?, onClick: () -> Unit) {
    val totalGb = (kernel?.totalKb ?: 0L) / 1024.0 / 1024.0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.DeveloperBoard, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            text = "内核",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatSize(totalGb),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

// ==================== 缓存行 ====================

@Composable
private fun CacheRow(cache: CacheInfo?, onClick: () -> Unit) {
    val totalGb = (cache?.totalKb ?: 0L) / 1024.0 / 1024.0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color(0xFFFF9800), modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            text = "可清理缓存",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatSize(totalGb),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

// ==================== 进程行 ====================

@Composable
private fun ProcessRow(process: MemoryProcessInfo) {
    val pssGb = process.pssKb / 1024.0 / 1024.0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (process.isSystem) Icons.Default.Settings else Icons.Default.Memory,
            contentDescription = null,
            tint = if (process.isSystem) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = process.processName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (process.displayName != process.processName) {
                Text(
                    text = process.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = formatSize(pssGb),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ==================== 内核详情页 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KernelDetailScreen(
    kernel: KernelMemoryBreakdown?,
    onBack: () -> Unit
) {
    val cardBgColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surface else Color.White

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("内核占用") },
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
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            if (kernel == null) {
                Text("无法获取", color = MaterialTheme.colorScheme.error)
                return@Column
            }

            Text(
                text = "总计: ${formatKb(kernel.totalKb)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBgColor)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    KernelDetailItem("不可回收 Slab", kernel.sUnreclaimKb)
                    KernelDetailDivider()
                    KernelDetailItem("页表 (PageTables)", kernel.pageTablesKb)
                    KernelDetailDivider()
                    KernelDetailItem("内核栈 (KernelStack)", kernel.kernelStackKb)
                    KernelDetailDivider()
                    KernelDetailItem("Vmalloc", kernel.vmallocUsedKb)
                    KernelDetailDivider()
                    KernelDetailItem("CMA 已用", kernel.cmaUsedKb)
                    KernelDetailDivider()
                    KernelDetailItem("DMA-BUF", kernel.dmaBufKb)
                    KernelDetailDivider()
                    KernelDetailItem("GPU", kernel.gpuKb)
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "总计来自 dumpsys meminfo \"Used RAM\" 行的 kernel 值，各字段来自 /proc/meminfo，仅用于展示细分，与总计可能存在重叠",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun KernelDetailDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 0.5.dp
    )
}

@Composable
private fun KernelDetailItem(name: String, valueKb: Long) {
    val displayText = if (valueKb > 0) formatKb(valueKb) else "无法获取"
    val textColor = if (valueKb > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(text = displayText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = textColor)
    }
}

// ==================== 工具函数 ====================

private fun formatSize(gb: Double): String {
    return when {
        gb >= 1.0 -> "${String.format("%.1f", gb)} GB"
        else -> "${String.format("%.0f", gb * 1024)} MB"
    }
}

private fun formatKb(kb: Long): String {
    val mb = kb / 1024.0
    return when {
        mb >= 1024.0 -> "${String.format("%.1f", mb / 1024.0)} GB"
        else -> "${String.format("%.0f", mb)} MB"
    }
}
