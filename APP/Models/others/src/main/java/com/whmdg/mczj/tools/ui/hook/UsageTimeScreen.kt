package com.whmdg.mczj.tools.ui.hook

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whmdg.mczj.tools.ui.hook.usage.AppUsageInfo
import com.whmdg.mczj.tools.ui.hook.usage.HourlyUsageTable
import com.whmdg.mczj.tools.ui.hook.usage.MergeStrategy
import com.whmdg.mczj.tools.ui.hook.usage.PathResult
import com.whmdg.mczj.tools.ui.hook.usage.UsageTimeViewModel
import kotlinx.coroutines.launch

// ==================== 自定义颜色 ====================

private val AccentPrimary = Color(0xFF7C3AED)
private val GradientPurple = Color(0xFF8B5CF6)
private val GradientPink = Color(0xFFEC4899)
private val UsageHigh = Color(0xFFEF4444)

// ==================== 主界面 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageTimeScreen(
    onBack: () -> Unit,
    viewModel: UsageTimeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // 设置弹窗状态
    var showMenu by remember { mutableStateOf(false) }
    var showDataSourceDialog by remember { mutableStateOf(false) }
    var pathResult by remember { mutableStateOf<PathResult?>(null) }
    var showExportLoading by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 排除应用弹窗状态
    var showExcludePanel by remember { mutableStateOf(false) }
    var showAppSelectionPanel by remember { mutableStateOf(false) }

    // onResume 时自动刷新
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手机使用时长") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "设置",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("数据来源") },
                                onClick = {
                                    showMenu = false
                                    coroutineScope.launch {
                                        pathResult = viewModel.getPathTimes()
                                        showDataSourceDialog = true
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("查看数据") },
                                onClick = {
                                    showMenu = false
                                    showExportLoading = true
                                    coroutineScope.launch {
                                        val intent = viewModel.buildAndShareXlsx()
                                        showExportLoading = false
                                        if (intent != null) {
                                            context.startActivity(
                                                Intent.createChooser(intent, "分享使用时长数据")
                                            )
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("排除应用") },
                                onClick = {
                                    showMenu = false
                                    viewModel.openExcludePanel()
                                    showExcludePanel = true
                                }
                            )
                        }
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
            // 加载中
            AnimatedVisibility(
                visible = uiState.isLoading && uiState.appUsageList.isEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = AccentPrimary,
                        strokeWidth = 3.dp
                    )
                }
            }

            // 无权限
            AnimatedVisibility(
                visible = !uiState.hasPermission,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                PermissionRequiredState()
            }

            // 空数据
            AnimatedVisibility(
                visible = uiState.hasPermission && !uiState.isLoading && uiState.appUsageList.isEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                EmptyState()
            }

            // 应用使用列表
            AnimatedVisibility(
                visible = uiState.appUsageList.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                AppUsageList(
                    appUsageList = uiState.appUsageList,
                    totalScreenTime = uiState.totalScreenTime,
                    hourlyTable = uiState.hourlyTable
                )
            }
        }
    }

    // 数据来源弹窗
    if (showDataSourceDialog) {
        DataSourceDialog(
            pathResult = pathResult,
            currentStrategy = uiState.mergeStrategy,
            onStrategyChange = { viewModel.setMergeStrategy(it) },
            onDismiss = { showDataSourceDialog = false }
        )
    }

    // 数据导出加载弹窗
    if (showExportLoading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("数据正在更新") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp,
                        color = AccentPrimary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("正在生成使用时长数据...")
                }
            },
            confirmButton = {}
        )
    }

    // 排除应用主面板
    if (showExcludePanel) {
        ExcludeAppsPanel(
            viewModel = viewModel,
            onDismiss = {
                viewModel.cancelExcludeChanges()
                showExcludePanel = false
            },
            onConfirm = {
                viewModel.saveExcludedPackages()
                showExcludePanel = false
            },
            onSelectApps = {
                showAppSelectionPanel = true
            }
        )
    }

    // 选择应用面板
    if (showAppSelectionPanel) {
        AppSelectionPanel(
            viewModel = viewModel,
            onDismiss = { showAppSelectionPanel = false },
            onConfirm = { selected ->
                viewModel.applySelectionToTemp(selected)
                showAppSelectionPanel = false
            }
        )
    }
}

// ==================== 数据来源弹窗 ====================

@Composable
private fun DataSourceDialog(
    pathResult: PathResult?,
    currentStrategy: MergeStrategy,
    onStrategyChange: (MergeStrategy) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedStrategy by remember { mutableStateOf(currentStrategy) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("数据来源") },
        text = {
            Column {
                // 各路径时间
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "路径 A（系统聚合）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatPathTime(pathResult?.pathATimeMillis ?: 0L),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "路径 B（实时事件）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatPathTime(pathResult?.pathBTimeMillis ?: 0L),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 数据来源选择
                Text(
                    text = "数据来源",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                listOf(
                    MergeStrategy.PATH_A_ONLY to "仅路径 A（系统聚合）（数据可能不准）",
                    MergeStrategy.PATH_B_ONLY to "仅路径 B（实时事件）",
                    MergeStrategy.MERGED_MAX to "合并取最大值（数据可能不准）",
                    MergeStrategy.MERGED_MIN to "合并取最小值（数据可能不准）"
                ).forEach { (strategy, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedStrategy == strategy,
                                onClick = { selectedStrategy = strategy },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedStrategy == strategy,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onStrategyChange(selectedStrategy)
                onDismiss()
            }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun formatPathTime(millis: Long): String {
    if (millis <= 0) return "无数据"
    val hours = millis / (1000 * 60 * 60)
    val minutes = (millis / (1000 * 60)) % 60
    val seconds = (millis / 1000) % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

// ==================== 排除应用主面板 ====================

@Composable
private fun ExcludeAppsPanel(
    viewModel: UsageTimeViewModel,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onSelectApps: () -> Unit
) {
    var showInput by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    var tempExcluded by remember { mutableStateOf(viewModel.getTempExcludedPackages()) }

    // 每次 recomposition 时刷新临时列表
    LaunchedEffect(showInput) {
        tempExcluded = viewModel.getTempExcludedPackages()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排除应用") },
        text = {
            Column(modifier = Modifier.padding(0.dp)) {
                // 第一行：添加应用 + 选择应用
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = {
                        showInput = !showInput
                        inputText = ""
                        inputError = null
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("添加应用")
                    }
                    TextButton(onClick = {
                        showInput = false
                        inputText = ""
                        inputError = null
                        onSelectApps()
                    }) {
                        Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("选择应用")
                    }
                }

                // 输入框（展开时显示）
                AnimatedVisibility(visible = showInput) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = {
                                    inputText = it
                                    inputError = null
                                },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("输入包名", style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                isError = inputError != null,
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(onClick = {
                                val error = viewModel.addExcludedPackage(inputText)
                                if (error != null) {
                                    inputError = error
                                } else {
                                    inputText = ""
                                    inputError = null
                                    showInput = false
                                    tempExcluded = viewModel.getTempExcludedPackages()
                                }
                            }) {
                                Icon(Icons.Default.Check, contentDescription = "确认", tint = Color(0xFF4CAF50))
                            }
                            IconButton(onClick = {
                                inputText = ""
                                inputError = null
                                showInput = false
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "取消", tint = Color(0xFFF44336))
                            }
                        }
                        // 错误提示
                        if (inputError != null) {
                            Text(
                                text = inputError!!,
                                color = Color(0xFFF44336),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 排除列表
                if (tempExcluded.isEmpty()) {
                    Text(
                        text = "暂无排除应用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    Column {
                        tempExcluded.forEach { pkg ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = pkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1
                                )
                                IconButton(
                                    onClick = {
                                        viewModel.removeExcludedPackage(pkg)
                                        tempExcluded = viewModel.getTempExcludedPackages()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ==================== 选择应用面板 ====================

@Composable
private fun AppSelectionPanel(
    viewModel: UsageTimeViewModel,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val apps = remember { viewModel.getTodayAppsForSelection() }
    val currentExcluded = remember { viewModel.getTempExcludedPackages() }
    var selectedPackages by remember { mutableStateOf(currentExcluded.toMutableSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要排除的应用") },
        text = {
            if (apps.isEmpty()) {
                Text(
                    text = "今日暂无使用数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                Column {
                    apps.forEach { app ->
                        val isChecked = selectedPackages.contains(app.packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .selectable(
                                    selected = isChecked,
                                    onClick = {
                                        selectedPackages = selectedPackages.toMutableSet().apply {
                                            if (isChecked) remove(app.packageName) else add(app.packageName)
                                        }
                                    },
                                    role = Role.Checkbox
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = app.appName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = app.formattedTime,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedPackages) }) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ==================== 应用使用列表（单卡片） ====================

@Composable
private fun AppUsageList(
    appUsageList: List<AppUsageInfo>,
    totalScreenTime: String,
    hourlyTable: HourlyUsageTable?
) {
    val cardBgColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surface else Color.White
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // ── 总用时卡片 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = cardBgColor
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 总用时
                Text(
                    text = totalScreenTime,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // 当前日期
                Text(
                    text = java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.getDefault())
                        .format(java.util.Date()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                // 柱状图
                if (hourlyTable != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HourlyBarChart(hourlyTable = hourlyTable)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "统计详情",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = cardBgColor
            )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                appUsageList.forEachIndexed { index, usage ->
                    AppUsageRow(appUsageInfo = usage)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ==================== 每小时使用柱状图 ====================

@Composable
private fun HourlyBarChart(hourlyTable: HourlyUsageTable) {
    val summaryData = hourlyTable.summaryRow.hourlyMillis
    val outlineColor = MaterialTheme.colorScheme.outline
    val fillColorArgb = 0xFF1565C0.toInt() // 深蓝色填充
    val bgColorArgb = MaterialTheme.colorScheme.surface.toArgb() // 主题色背景
    val outlineColorArgb = outlineColor.toArgb()

    // Y 轴上限：max(60分钟, 最大小时使用时长)
    val sixtyMinutesMs = 60L * 60 * 1000
    val maxHourlyMs = summaryData.maxOrNull() ?: 0L
    val yMaxMs = maxOf(sixtyMinutesMs, maxHourlyMs)

    val chartHeight = 160.dp
    val barCount = 24
    val xLabels = listOf("0:00", "6:00", "12:00", "18:00", "24:00")

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
    ) {
        val nativeCanvas = drawContext.canvas.nativeCanvas
        val w = size.width
        val h = size.height
        val barAreaHeight = h - 20.dp.toPx()
        val barWidth = w / barCount * 0.6f
        val barGap = w / barCount
        val radius = barWidth / 2
        val bgPaint = android.graphics.Paint().apply {
            color = bgColorArgb
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
        val fillPaint = android.graphics.Paint().apply {
            color = fillColorArgb
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }

        for (i in 0 until barCount) {
            val valueMs = summaryData[i]
            val fillRatio = if (yMaxMs > 0) (valueMs.toFloat() / yMaxMs).coerceIn(0f, 1f) else 0f
            val cx = i * barGap + barGap / 2
            val capsuleTop = 0f
            val capsuleBottom = barAreaHeight

            // 胶囊 RectF
            val rectF = android.graphics.RectF(
                cx - radius, capsuleTop,
                cx + radius, capsuleBottom
            )

            // 绘制浅灰背景填充
            nativeCanvas.drawRoundRect(rectF, radius, radius, bgPaint)

            // 绘制蓝色填充（clipPath 裁切到胶囊形状内）
            if (fillRatio > 0f) {
                val fillHeight = (capsuleBottom - capsuleTop) * fillRatio
                val fillTop = capsuleBottom - fillHeight

                nativeCanvas.save()
                nativeCanvas.clipPath(android.graphics.Path().apply {
                    addRoundRect(rectF, radius, radius, android.graphics.Path.Direction.CW)
                })
                nativeCanvas.drawRect(
                    cx - radius, fillTop,
                    cx + radius, capsuleBottom,
                    fillPaint
                )
                nativeCanvas.restore()
            }
        }

        // X 轴标签（冒号对齐柱子边界）
        val textPaint = android.graphics.Paint().apply {
            color = outlineColorArgb
            textSize = 10.dp.toPx()
            isAntiAlias = true
        }
        val labelY = h - 4.dp.toPx()
        // 标签对应的柱子索引：0, 6, 12, 18, 24（24 = 右边界）
        val labelBarIndices = listOf(0, 6, 12, 18, 24)
        for ((idx, label) in xLabels.withIndex()) {
            val barIdx = labelBarIndices[idx]
            val colonIdx = label.indexOf(':')
            val colonOffset = textPaint.measureText(label, 0, colonIdx)
            val colonX = if (barIdx < barCount) {
                barIdx * barGap + barGap / 2
            } else {
                barIdx * barGap // 24:00 = 右边界
            }
            textPaint.textAlign = android.graphics.Paint.Align.LEFT
            nativeCanvas.drawText(label, colonX - colonOffset, labelY, textPaint)
        }
    }
}

// ==================== 单个应用行 ====================

@Composable
private fun AppUsageRow(appUsageInfo: AppUsageInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIconBox(appUsageInfo)
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = appUsageInfo.appName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = appUsageInfo.formattedTime,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ==================== 应用图标 ====================

@Composable
private fun AppIconBox(appUsageInfo: AppUsageInfo) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        appUsageInfo.appIcon?.let { drawable ->
            val bitmap = remember(drawable) {
                try {
                    if (drawable is BitmapDrawable) {
                        drawable.bitmap
                    } else {
                        val bmp = android.graphics.Bitmap.createBitmap(
                            drawable.intrinsicWidth.coerceAtLeast(1),
                            drawable.intrinsicHeight.coerceAtLeast(1),
                            android.graphics.Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(bmp)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bmp
                    }
                } catch (_: Exception) {
                    null
                }
            }

            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = appUsageInfo.appName,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                IconPlaceholder()
            }
        } ?: IconPlaceholder()
    }
}

@Composable
private fun IconPlaceholder() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(GradientPurple.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
    )
}

// ==================== 空状态 ====================

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            AccentPrimary.copy(alpha = 0.2f),
                            GradientPurple.copy(alpha = 0.2f)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = AccentPrimary,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "暂无使用数据",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "开始使用你的应用，稍后再来查看使用统计。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ==================== 无权限状态 ====================

@Composable
private fun PermissionRequiredState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            GradientPink.copy(alpha = 0.2f),
                            UsageHigh.copy(alpha = 0.2f)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = GradientPink,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "需要使用情况访问权限",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "请前往 设置 → 安全 → 有使用权限的应用，授予本应用[使用情况访问权限]后返回此页面。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
