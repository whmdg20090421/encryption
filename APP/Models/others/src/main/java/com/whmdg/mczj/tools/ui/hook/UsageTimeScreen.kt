package com.whmdg.mczj.tools.ui.hook

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whmdg.mczj.tools.ui.hook.usage.AppUsageInfo
import com.whmdg.mczj.tools.ui.hook.usage.CategoryHourlyData
import com.whmdg.mczj.tools.ui.hook.usage.ExcludedTimeRange
import com.whmdg.mczj.tools.ui.hook.usage.HourlyUsageTable
import com.whmdg.mczj.tools.ui.hook.usage.MergeStrategy
import com.whmdg.mczj.tools.ui.hook.usage.PathResult
import com.whmdg.mczj.tools.ui.hook.usage.UsageTimeViewModel

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
    var showTimeRangeDialog by remember { mutableStateOf(false) }
    var showFixedTimeDialog by remember { mutableStateOf(false) }
    var excludeRefreshTrigger by remember { mutableIntStateOf(0) }

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
                                text = { Text("排除应用与时间") },
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
                    hourlyTable = uiState.hourlyTable,
                    categoryHourlyData = uiState.categoryHourlyData
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
            },
            onAddTimeRange = {
                showTimeRangeDialog = true
            },
            onAddFixedTime = {
                showFixedTimeDialog = true
            },
            refreshTrigger = excludeRefreshTrigger
        )
    }

    // 添加排除时间段对话框
    if (showTimeRangeDialog) {
        ExcludeTimeRangeDialog(
            onDismiss = { showTimeRangeDialog = false },
            onConfirm = { range ->
                viewModel.addExcludedTimeRange(range)
                showTimeRangeDialog = false
                excludeRefreshTrigger++
            }
        )
    }

    // 添加固定排除时段对话框
    if (showFixedTimeDialog) {
        ExcludeFixedTimeDialog(
            onDismiss = { showFixedTimeDialog = false },
            onConfirm = { range ->
                viewModel.addExcludedTimeRange(range)
                showFixedTimeDialog = false
                excludeRefreshTrigger++
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
                excludeRefreshTrigger++
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

// ==================== 排除应用与时间主面板 ====================

@Composable
private fun ExcludeAppsPanel(
    viewModel: UsageTimeViewModel,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onSelectApps: () -> Unit,
    onAddTimeRange: () -> Unit,
    onAddFixedTime: () -> Unit,
    refreshTrigger: Int = 0
) {
    var showInput by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    var tempExcluded by remember { mutableStateOf(viewModel.getTempExcludedPackages()) }
    var tempTimeRanges by remember { mutableStateOf(viewModel.getTempExcludedTimeRanges()) }

    // 面板首次显示、输入框状态变化、或从子对话框返回时，刷新临时列表
    LaunchedEffect(showInput, refreshTrigger) {
        tempExcluded = viewModel.getTempExcludedPackages()
        tempTimeRanges = viewModel.getTempExcludedTimeRanges()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排除应用与时间") },
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

                // 第二行：添加时间 + 固定时长
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = {
                        onAddTimeRange()
                    }) {
                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("添加时间")
                    }
                    TextButton(onClick = { onAddFixedTime() }) {
                        Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("固定时长")
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

                // 排除应用列表
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

                // 分隔线 + 排除时间段列表
                if (tempTimeRanges.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    tempTimeRanges.forEach { range ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (range.isRecurring) {
                                    ExcludedTimeRange.formatRecurring(range.startMinuteOfDay, range.endMinuteOfDay, range.repeatDays!!)
                                } else {
                                    ExcludedTimeRange.formatOneTime(range.startMillis, range.endMillis)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            IconButton(
                                onClick = {
                                    viewModel.removeExcludedTimeRange(range.id)
                                    tempTimeRanges = viewModel.getTempExcludedTimeRanges()
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
    hourlyTable: HourlyUsageTable?,
    categoryHourlyData: CategoryHourlyData?
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
            Column(modifier = Modifier.padding(15.dp)) {
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
                if (hourlyTable != null && categoryHourlyData != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HourlyBarChart(hourlyTable = hourlyTable, categoryData = categoryHourlyData)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

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
                    if (index < appUsageList.lastIndex) {
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

// ==================== 每小时使用柱状图 ====================

@Composable
private fun HourlyBarChart(hourlyTable: HourlyUsageTable, categoryData: CategoryHourlyData) {
    val summaryData = hourlyTable.summaryRow.hourlyMillis
    val outlineColor = MaterialTheme.colorScheme.outline
    val bgColorArgb = MaterialTheme.colorScheme.surface.toArgb()
    val outlineColorArgb = outlineColor.toArgb()

    // 类别颜色
    val gameColor = 0xFF7C3AED.toInt()      // 紫色
    val mediaColor = 0xFFFF9800.toInt()     // 橙色
    val otherColor = 0xFF1565C0.toInt()     // 蓝色

    // Y 轴上限：max(60分钟, 最大小时使用时长)
    val sixtyMinutesMs = 60L * 60 * 1000
    val maxHourlyMs = summaryData.maxOrNull() ?: 0L
    val yMaxMs = maxOf(sixtyMinutesMs, maxHourlyMs)

    val chartHeight = 160.dp
    val barCount = 24
    val xLabels = listOf("0:00", "6:00", "12:00", "18:00", "24:00")

    // 格式化类别总时长
    fun formatCategoryTime(millis: Long): String {
        if (millis <= 0) return "0分"
        val hours = millis / (1000 * 60 * 60)
        val minutes = (millis / (1000 * 60)) % 60
        return when {
            hours > 0 -> "${hours}小时${minutes}分"
            minutes > 0 -> "${minutes}分"
            else -> "0分"
        }
    }

    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
        ) {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val w = size.width
            val h = size.height
            val yAxisWidth = 36.dp.toPx()
            val barAreaHeight = h - 20.dp.toPx()
            val chartWidth = w - yAxisWidth
            val barWidth = chartWidth / barCount * 0.6f
            val barGap = chartWidth / barCount
            val radius = barWidth / 2

            val bgPaint = android.graphics.Paint().apply {
                color = bgColorArgb
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = true
            }
            val gamePaint = android.graphics.Paint().apply {
                color = gameColor
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = true
            }
            val mediaPaint = android.graphics.Paint().apply {
                color = mediaColor
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = true
            }
            val otherPaint = android.graphics.Paint().apply {
                color = otherColor
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = true
            }

            for (i in 0 until barCount) {
                val gameMs = categoryData.gameHourly[i]
                val mediaMs = categoryData.mediaHourly[i]
                val otherMs = categoryData.otherHourly[i]
                val totalMs = gameMs + mediaMs + otherMs

                val cx = yAxisWidth + i * barGap + barGap / 2
                val capsuleTop = 0f
                val capsuleBottom = barAreaHeight

                val rectF = android.graphics.RectF(
                    cx - radius, capsuleTop,
                    cx + radius, capsuleBottom
                )

                // 背景
                nativeCanvas.drawRoundRect(rectF, radius, radius, bgPaint)

                // 堆叠填充（clipPath 裁切到胶囊形状内）
                if (totalMs > 0 && yMaxMs > 0) {
                    val totalRatio = (totalMs.toFloat() / yMaxMs).coerceIn(0f, 1f)
                    val totalFillHeight = (capsuleBottom - capsuleTop) * totalRatio

                    // 各类别占比
                    val gameRatio = gameMs.toFloat() / totalMs
                    val mediaRatio = mediaMs.toFloat() / totalMs

                    val gameFillHeight = totalFillHeight * gameRatio
                    val mediaFillHeight = totalFillHeight * mediaRatio
                    // otherFillHeight = totalFillHeight - gameFillHeight - mediaFillHeight

                    nativeCanvas.save()
                    nativeCanvas.clipPath(android.graphics.Path().apply {
                        addRoundRect(rectF, radius, radius, android.graphics.Path.Direction.CW)
                    })

                    // 从底部向上堆叠：其他 → 视频 → 游戏
                    var currentBottom = capsuleBottom

                    // 其他（蓝色）
                    if (otherMs > 0) {
                        val h1 = totalFillHeight - gameFillHeight - mediaFillHeight
                        nativeCanvas.drawRect(cx - radius, currentBottom - h1, cx + radius, currentBottom, otherPaint)
                        currentBottom -= h1
                    }
                    // 视频/音频（橙色）
                    if (mediaMs > 0) {
                        nativeCanvas.drawRect(cx - radius, currentBottom - mediaFillHeight, cx + radius, currentBottom, mediaPaint)
                        currentBottom -= mediaFillHeight
                    }
                    // 游戏（紫色）
                    if (gameMs > 0) {
                        nativeCanvas.drawRect(cx - radius, currentBottom - gameFillHeight, cx + radius, currentBottom, gamePaint)
                    }

                    nativeCanvas.restore()
                }
            }

            // X 轴标签
            val textPaint = android.graphics.Paint().apply {
                color = outlineColorArgb
                textSize = 10.dp.toPx()
                isAntiAlias = true
            }
            val labelY = h - 4.dp.toPx()
            val labelBarIndices = listOf(0, 6, 12, 18, 24)
            for ((idx, label) in xLabels.withIndex()) {
                val barIdx = labelBarIndices[idx]
                val colonIdx = label.indexOf(':')
                val colonOffset = textPaint.measureText(label, 0, colonIdx)
                val colonX = if (barIdx < barCount) {
                    yAxisWidth + barIdx * barGap + barGap / 2
                } else {
                    yAxisWidth + barIdx * barGap
                }
                textPaint.textAlign = android.graphics.Paint.Align.LEFT
                nativeCanvas.drawText(label, colonX - colonOffset, labelY, textPaint)
            }

            // Y 轴标签
            val yAxisLabelPaint = android.graphics.Paint().apply {
                color = outlineColorArgb
                textSize = 9.dp.toPx()
                textAlign = android.graphics.Paint.Align.RIGHT
                isAntiAlias = true
            }
            val yMaxMinutes = (yMaxMs / (1000 * 60)).toInt()
            val yLabels = mutableListOf(0, 30, 60)
            if (yMaxMinutes > 60) yLabels.add(yMaxMinutes)

            for (label in yLabels) {
                val ratio = label.toFloat() / yMaxMinutes.coerceAtLeast(1)
                val y = barAreaHeight - ratio * barAreaHeight
                nativeCanvas.drawText(
                    label.toString(),
                    yAxisWidth - 6.dp.toPx(),
                    y + 3.dp.toPx(),
                    yAxisLabelPaint
                )
            }

            // 30/60 分钟基准虚线
            val gridLinePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(179, 128, 128, 128) // 深灰 70% 不透明
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 0.5.dp.toPx()
                isAntiAlias = true
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()), 0f)
            }
            for (mark in listOf(30, 60)) {
                val ratio = mark.toFloat() / yMaxMinutes.coerceAtLeast(1)
                val y = barAreaHeight - ratio * barAreaHeight
                nativeCanvas.drawLine(yAxisWidth, y, w, y, gridLinePaint)
            }
        }

        // 图例行：游戏 | 视频播放 | 其他
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 游戏
            Column(horizontalAlignment = Alignment.Start) {
                Text("游戏", style = MaterialTheme.typography.labelSmall, color = Color(gameColor))
                Text(formatCategoryTime(categoryData.gameTotal), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
            // 视频播放
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("视频播放", style = MaterialTheme.typography.labelSmall, color = Color(mediaColor))
                Text(formatCategoryTime(categoryData.mediaTotal), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
            // 其他
            Column(horizontalAlignment = Alignment.End) {
                Text("其他", style = MaterialTheme.typography.labelSmall, color = Color(otherColor))
                Text(formatCategoryTime(categoryData.otherTotal), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
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
            .size(40.dp)
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
                        .size(32.dp)
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
            .size(32.dp)
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

// ==================== 排除时间段对话框 ====================

/** ExcludeTimeRangeDialog 中当前展开的子面板 */
private sealed class ActivePanel {
    data object None : ActivePanel()
    data class Year(val isFrom: Boolean) : ActivePanel()
    data class MonthDay(val isFrom: Boolean) : ActivePanel()
    data class HourMinute(val isFrom: Boolean) : ActivePanel()
}

/**
 * 排除时间段对话框
 *
 * 两行输入：从 [年] [月日] [时分] 和 到 [年] [月日] [时分]
 * 默认：从 = 今天 00:00，到 = 当前时间
 * 三个输入框各自弹出底部面板，互斥（同时只能展开一个）
 */
@Composable
private fun ExcludeTimeRangeDialog(
    onDismiss: () -> Unit,
    onConfirm: (ExcludedTimeRange) -> Unit
) {
    val now = java.util.Calendar.getInstance()

    // 从：今天 00:00
    var fromYear by remember { mutableIntStateOf(now.get(java.util.Calendar.YEAR)) }
    var fromMonth by remember { mutableIntStateOf(now.get(java.util.Calendar.MONTH)) }
    var fromDay by remember { mutableIntStateOf(now.get(java.util.Calendar.DAY_OF_MONTH)) }
    var fromHour by remember { mutableIntStateOf(0) }
    var fromMinute by remember { mutableIntStateOf(0) }

    // 到：当前时间
    var toYear by remember { mutableIntStateOf(now.get(java.util.Calendar.YEAR)) }
    var toMonth by remember { mutableIntStateOf(now.get(java.util.Calendar.MONTH)) }
    var toDay by remember { mutableIntStateOf(now.get(java.util.Calendar.DAY_OF_MONTH)) }
    var toHour by remember { mutableIntStateOf(now.get(java.util.Calendar.HOUR_OF_DAY)) }
    var toMinute by remember { mutableIntStateOf(now.get(java.util.Calendar.MINUTE)) }

    var activePanel by remember { mutableStateOf<ActivePanel>(ActivePanel.None) }

    Dialog(onDismissRequest = onDismiss) {
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        val dialogWidth = screenWidth * 0.85f
        val dialogHeight = screenHeight * 0.7f

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .width(dialogWidth)
                    .height(dialogHeight)
                    .padding(16.dp)
            ) {
                // 标题
                Text(
                    text = "排除时间段",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // 从
                Text(
                    text = "从",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                TimePointRow(
                    year = fromYear, month = fromMonth, day = fromDay,
                    hour = fromHour, minute = fromMinute,
                    onYearClick = { if (activePanel is ActivePanel.None) activePanel = ActivePanel.Year(true) },
                    onMonthDayClick = { if (activePanel is ActivePanel.None) activePanel = ActivePanel.MonthDay(true) },
                    onHourMinuteClick = { if (activePanel is ActivePanel.None) activePanel = ActivePanel.HourMinute(true) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 到
                Text(
                    text = "到",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                TimePointRow(
                    year = toYear, month = toMonth, day = toDay,
                    hour = toHour, minute = toMinute,
                    onYearClick = { if (activePanel is ActivePanel.None) activePanel = ActivePanel.Year(false) },
                    onMonthDayClick = { if (activePanel is ActivePanel.None) activePanel = ActivePanel.MonthDay(false) },
                    onHourMinuteClick = { if (activePanel is ActivePanel.None) activePanel = ActivePanel.HourMinute(false) }
                )

                // 面板区域
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (val panel = activePanel) {
                        is ActivePanel.Year -> {
                            val initialYear = if (panel.isFrom) fromYear else toYear
                            YearWheelPanel(
                                initialYear = initialYear,
                                onConfirm = { selectedYear ->
                                    if (panel.isFrom) fromYear = selectedYear else toYear = selectedYear
                                    activePanel = ActivePanel.None
                                },
                                onCancel = { activePanel = ActivePanel.None }
                            )
                        }
                        is ActivePanel.MonthDay -> {
                            val initYear = if (panel.isFrom) fromYear else toYear
                            val initMonth = if (panel.isFrom) fromMonth else toMonth
                            val initDay = if (panel.isFrom) fromDay else toDay
                            MonthDayPanel(
                                initYear = initYear, initMonth = initMonth, initDay = initDay,
                                onConfirm = { y, m, d ->
                                    if (panel.isFrom) { fromYear = y; fromMonth = m; fromDay = d }
                                    else { toYear = y; toMonth = m; toDay = d }
                                    activePanel = ActivePanel.None
                                },
                                onCancel = { activePanel = ActivePanel.None }
                            )
                        }
                        is ActivePanel.HourMinute -> {
                            val initHour = if (panel.isFrom) fromHour else toHour
                            val initMinute = if (panel.isFrom) fromMinute else toMinute
                            HourMinutePanel(
                                initHour = initHour, initMinute = initMinute,
                                onConfirm = { h, m ->
                                    if (panel.isFrom) { fromHour = h; fromMinute = m }
                                    else { toHour = h; toMinute = m }
                                    activePanel = ActivePanel.None
                                },
                                onCancel = { activePanel = ActivePanel.None }
                            )
                        }
                        is ActivePanel.None -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "点击上方输入框选择时间",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // 底部按钮（子面板展开时隐藏）
                AnimatedVisibility(visible = activePanel is ActivePanel.None) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            val startCal = java.util.Calendar.getInstance().apply {
                                set(fromYear, fromMonth, fromDay, fromHour, fromMinute, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                            }
                            val endCal = java.util.Calendar.getInstance().apply {
                                set(toYear, toMonth, toDay, toHour, toMinute, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                            }
                            onConfirm(ExcludedTimeRange(
                                startMillis = startCal.timeInMillis,
                                endMillis = endCal.timeInMillis
                            ))
                        }) {
                            Text("确定", color = AccentPrimary)
                        }
                    }
                }
            }
        }
    }
}

// ==================== 固定排除时段对话框 ====================

@Composable
private fun ExcludeFixedTimeDialog(
    onDismiss: () -> Unit,
    onConfirm: (ExcludedTimeRange) -> Unit
) {
    var selStartHour by remember { mutableIntStateOf(0) }
    var selStartMinute by remember { mutableIntStateOf(0) }
    var selEndHour by remember { mutableIntStateOf(23) }
    var selEndMinute by remember { mutableIntStateOf(59) }
    var selectedDays by remember { mutableStateOf(setOf<Int>()) }

    Dialog(onDismissRequest = onDismiss) {
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        val dialogWidth = screenWidth * 0.85f
        val dialogHeight = screenHeight * 0.7f

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .width(dialogWidth)
                    .height(dialogHeight)
                    .padding(16.dp)
            ) {
                Text(
                    text = "固定排除时段",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // 起始时分
                Text(
                    text = "起始时间",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                    TimeWheel(
                        range = 0..23, selected = selStartHour,
                        onSelect = { selStartHour = it },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = { "%02d".format(it) }
                    )
                    TimeWheel(
                        range = 0..59, selected = selStartMinute,
                        onSelect = { selStartMinute = it },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = { "%02d".format(it) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 结束时分
                Text(
                    text = "结束时间",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                    TimeWheel(
                        range = 0..23, selected = selEndHour,
                        onSelect = { selEndHour = it },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = { "%02d".format(it) }
                    )
                    TimeWheel(
                        range = 0..59, selected = selEndMinute,
                        onSelect = { selEndMinute = it },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = { "%02d".format(it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 星期选择
                Text(
                    text = "重复日期",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
                    for (i in 1..7) {
                        val isSelected = i in selectedDays
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) AccentPrimary
                                    else MaterialTheme.colorScheme.surfaceContainerLow
                                )
                                .clickable {
                                    selectedDays = if (isSelected) selectedDays - i
                                    else selectedDays + i
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = dayLabels[i - 1],
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) Color.White
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = {
                            val startMod = selStartHour * 60 + selStartMinute
                            val endMod = selEndHour * 60 + selEndMinute
                            onConfirm(ExcludedTimeRange(
                                startMinuteOfDay = startMod,
                                endMinuteOfDay = endMod,
                                repeatDays = selectedDays
                            ))
                        },
                        enabled = selectedDays.isNotEmpty() &&
                            (selStartHour * 60 + selStartMinute) < (selEndHour * 60 + selEndMinute)
                    ) {
                        Text("确定", color = if (selectedDays.isNotEmpty() &&
                            (selStartHour * 60 + selStartMinute) < (selEndHour * 60 + selEndMinute))
                            AccentPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ==================== 时间点行（年 | 月日 | 时分） ====================

@Composable
private fun TimePointRow(
    year: Int, month: Int, day: Int,
    hour: Int, minute: Int,
    onYearClick: () -> Unit,
    onMonthDayClick: () -> Unit,
    onHourMinuteClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .weight(0.3f)
                .clickable(onClick = onYearClick)
        ) {
            Text(
                text = "$year",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                textAlign = TextAlign.Center
            )
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .weight(0.4f)
                .clickable(onClick = onMonthDayClick)
        ) {
            Text(
                text = String.format("%02d-%02d", month + 1, day),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                textAlign = TextAlign.Center
            )
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .weight(0.3f)
                .clickable(onClick = onHourMinuteClick)
        ) {
            Text(
                text = String.format("%02d:%02d", hour, minute),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ==================== 年份滚轮面板 ====================

@Composable
private fun YearWheelPanel(
    initialYear: Int,
    onConfirm: (Int) -> Unit,
    onCancel: () -> Unit
) {
    var selectedYear by remember { mutableIntStateOf(initialYear) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            TimeWheel(
                range = (currentYear - 10)..(currentYear + 10),
                selected = selectedYear,
                onSelect = { selectedYear = it },
                modifier = Modifier.fillMaxSize(),
                label = { "$it" }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onCancel) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onConfirm(selectedYear) }) {
                Text("确定", color = AccentPrimary)
            }
        }
    }
}

// ==================== 月日选择面板（日历网格） ====================

@Composable
private fun MonthDayPanel(
    initYear: Int, initMonth: Int, initDay: Int,
    onConfirm: (year: Int, month: Int, day: Int) -> Unit,
    onCancel: () -> Unit
) {
    var calYear by remember { mutableIntStateOf(initYear) }
    var calMonth by remember { mutableIntStateOf(initMonth) }
    var selDay by remember { mutableIntStateOf(initDay) }

    val daysInMonth = remember(calYear, calMonth) {
        val cal = java.util.Calendar.getInstance()
        cal.set(calYear, calMonth, 1)
        cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
    }
    val firstDayOfWeek = remember(calYear, calMonth) {
        val cal = java.util.Calendar.getInstance()
        cal.set(calYear, calMonth, 1)
        (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (calMonth == 0) { calMonth = 11; calYear-- } else calMonth--
            }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上月", tint = AccentPrimary)
            }
            Text(
                text = "%04d-%02d".format(calYear, calMonth + 1),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            IconButton(onClick = {
                if (calMonth == 11) { calMonth = 0; calYear++ } else calMonth++
            }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下月", tint = AccentPrimary)
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(
                    text = it,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            items(firstDayOfWeek) { Spacer(Modifier.aspectRatio(1f)) }
            items(daysInMonth) { day ->
                val d = day + 1
                val isSelected = d == selDay
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (isSelected) AccentPrimary else Color.Transparent)
                        .clickable { selDay = d },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$d",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) Color.White
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onCancel) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onConfirm(calYear, calMonth, selDay) }) {
                Text("确定", color = AccentPrimary)
            }
        }
    }
}

// ==================== 时分选择面板 ====================

@Composable
private fun HourMinutePanel(
    initHour: Int, initMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onCancel: () -> Unit
) {
    var selHour by remember { mutableIntStateOf(initHour) }
    var selMinute by remember { mutableIntStateOf(initMinute) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            TimeWheel(
                range = 0..23,
                selected = selHour,
                onSelect = { selHour = it },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = { "%02d".format(it) }
            )
            TimeWheel(
                range = 0..59,
                selected = selMinute,
                onSelect = { selMinute = it },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = { "%02d".format(it) }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onCancel) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onConfirm(selHour, selMinute) }) {
                Text("确定", color = AccentPrimary)
            }
        }
    }
}

// ==================== TimeWheel（无限循环滚轮） ====================

@Composable
private fun TimeWheel(
    range: IntRange,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    label: (Int) -> String
) {
    val size = range.last - range.first + 1
    val totalItems = size * 10000
    val initialIndex = totalItems / 2 + (selected - range.first)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val itemHeight = 36.dp
    val itemSpacing = 4.dp
    val totalItemHeight = itemHeight + itemSpacing
    val density = LocalDensity.current

    var firstLayout by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        if (firstLayout) {
            snapshotFlow { listState.layoutInfo.viewportEndOffset }
                .first { it > 0 }
            val vpH = listState.layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset }
            val ih = with(density) { itemHeight.roundToPx() }
            listState.scrollToItem(initialIndex, -((vpH - ih) / 2))
            firstLayout = false
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .collect {
                val info = listState.layoutInfo
                val items = info.visibleItemsInfo
                if (items.isEmpty()) return@collect
                val vpH = info.viewportEndOffset - info.viewportStartOffset
                val centerY = vpH / 2f
                val best = items.minByOrNull {
                    kotlin.math.abs(it.offset + it.size / 2f - centerY)
                } ?: return@collect
                val scrollOffset = -((vpH - best.size) / 2).toInt()
                listState.animateScrollToItem(best.index, scrollOffset)
                onSelect(range.first + (best.index % size + size) % size)
            }
    }

    val scope = rememberCoroutineScope()
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(totalItems) { index ->
                val value = range.first + (index % size + size) % size
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .padding(vertical = itemSpacing / 2)
                        .clickable {
                            onSelect(value)
                            scope.launch {
                                val info = listState.layoutInfo
                                val vpH = info.viewportEndOffset - info.viewportStartOffset
                                val ih = (itemHeight.value * density.density).toInt()
                                listState.animateScrollToItem(index, -((vpH - ih) / 2))
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label(value),
                        style = if (value == selected) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.bodyLarge,
                        color = if (value == selected) AccentPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(totalItemHeight)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(4.dp))
                .background(AccentPrimary.copy(alpha = 0.12f))
        )
    }
}
