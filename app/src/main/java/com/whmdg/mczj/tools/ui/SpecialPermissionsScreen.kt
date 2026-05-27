package com.whmdg.mczj.tools.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.security.AndroidPermissionLevel
import kotlinx.coroutines.launch

private const val INTRO_PAGES_COUNT = 3
private const val WELCOME_PAGE_INDEX = INTRO_PAGES_COUNT
private const val BASIC_PERMISSIONS_PAGE_INDEX = INTRO_PAGES_COUNT + 1
private const val PERMISSION_LEVEL_PAGE_INDEX = INTRO_PAGES_COUNT + 2
private const val TOTAL_PAGES_COUNT = INTRO_PAGES_COUNT + 3

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SpecialPermissionsScreen(
    onBack: () -> Unit,
    viewModel: PermissionGuideViewModel = remember { PermissionGuideViewModel() }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()

    // 引导是否已完成
    var guideCompleted by remember { mutableStateOf(PermissionGuideViewModel.isGuideCompleted(context)) }

    // 引导完成或 isCompleted 变为 true 时切换到状态页
    LaunchedEffect(uiState.isCompleted) {
        if (uiState.isCompleted) {
            guideCompleted = true
        }
    }

    if (guideCompleted) {
        PermissionStatusPage(
            onBack = onBack,
            onReconfigure = {
                viewModel.resetGuide(context)
                guideCompleted = false
            }
        )
    } else {
        PermissionGuideWizard(
            onBack = onBack,
            viewModel = viewModel,
            uiState = uiState
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// 引导向导（首次使用）
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PermissionGuideWizard(
    onBack: () -> Unit,
    viewModel: PermissionGuideViewModel,
    uiState: PermissionGuideViewModel.UiState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { TOTAL_PAGES_COUNT })
    var showPermissionWarning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.checkPermissions(context) }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val readGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
            val writeGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: false
            if (readGranted && writeGranted) viewModel.checkPermissions(context)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) viewModel.updateLocationPermission(true)
    }

    LaunchedEffect(pagerState.currentPage) {
        when (pagerState.currentPage) {
            in 0..WELCOME_PAGE_INDEX -> viewModel.setCurrentStep(PermissionGuideViewModel.Step.WELCOME)
            BASIC_PERMISSIONS_PAGE_INDEX -> viewModel.setCurrentStep(PermissionGuideViewModel.Step.BASIC_PERMISSIONS)
            PERMISSION_LEVEL_PAGE_INDEX -> viewModel.setCurrentStep(PermissionGuideViewModel.Step.PERMISSION_LEVEL)
        }
    }

    // 校验失败弹窗
    if (uiState.validationError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearValidationError() },
            icon = {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("权限不足") },
            text = { Text(uiState.validationError!!) },
            confirmButton = {
                Button(onClick = { viewModel.clearValidationError() }) { Text("确定") }
            }
        )
    }

    if (showPermissionWarning) {
        AlertDialog(
            onDismissRequest = { showPermissionWarning = false },
            icon = {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("权限未全部授予", style = MaterialTheme.typography.titleMedium) },
            text = { Text("部分基础权限尚未授予，某些功能可能受限。是否继续？", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = {
                    showPermissionWarning = false
                    scope.launch { pagerState.animateScrollToPage(PERMISSION_LEVEL_PAGE_INDEX) }
                }) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionWarning = false }) { Text("返回") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("特殊权限") },
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LinearProgressIndicator(
                progress = { (pagerState.currentPage + 1).toFloat() / pagerState.pageCount },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                userScrollEnabled = false
            ) { page ->
                when (page) {
                    0 -> IntroductionPage("欢迎使用工具箱", "本工具箱需要一些系统权限来提供完整功能体验。接下来我们将引导您完成权限配置。", 0)
                    1 -> IntroductionPage("权限说明", "工具箱支持多种权限级别，从普通权限到 Root 权限。不同权限级别解锁不同的系统交互能力。", 1)
                    2 -> IntroductionPage("开始配置", "让我们先检查一些基础权限，然后选择适合您的权限级别。", 2)
                    WELCOME_PAGE_INDEX -> WelcomePage()
                    BASIC_PERMISSIONS_PAGE_INDEX -> BasicPermissionsPage(
                        hasStoragePermission = uiState.hasStoragePermission,
                        hasOverlayPermission = uiState.hasOverlayPermission,
                        hasBatteryOptimizationExemption = uiState.hasBatteryOptimizationExemption,
                        hasLocationPermission = uiState.hasLocationPermission,
                        onStoragePermissionClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                try {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "无法打开存储权限设置", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                storagePermissionLauncher.launch(arrayOf(
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                ))
                            }
                        },
                        onOverlayPermissionClick = {
                            try {
                                context.startActivity(Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ))
                            } catch (_: Exception) {
                                Toast.makeText(context, "无法打开悬浮窗权限设置", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onBatteryOptimizationClick = {
                            try {
                                // 直接请求忽略电池优化，无需用户搜索应用
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .apply { data = Uri.parse("package:${context.packageName}") }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // 如果直接请求失败，尝试打开电池优化设置页面
                                try {
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                    Toast.makeText(context, "请在列表中找到本应用并关闭电池优化", Toast.LENGTH_LONG).show()
                                } catch (_: Exception) {
                                    Toast.makeText(context, "无法打开电池优化设置", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onLocationPermissionClick = {
                            locationPermissionLauncher.launch(arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ))
                        },
                        onRefresh = { viewModel.checkPermissions(context) }
                    )
                    PERMISSION_LEVEL_PAGE_INDEX -> PermissionLevelPage(
                        selectedLevel = uiState.selectedPermissionLevel,
                        onLevelSelected = { viewModel.selectPermissionLevel(it) },
                        onConfirm = { viewModel.savePermissionLevel(context) }
                    )
                }
            }

            // 底部导航按钮
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                    IconButton(
                        onClick = { scope.launch { if (pagerState.currentPage > 0) pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                        enabled = pagerState.currentPage > 0
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一步",
                            tint = if (pagerState.currentPage > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }

                Text(
                    text = when (pagerState.currentPage) {
                        in 0 until INTRO_PAGES_COUNT -> "介绍 ${pagerState.currentPage + 1}/$INTRO_PAGES_COUNT"
                        WELCOME_PAGE_INDEX -> "欢迎"
                        BASIC_PERMISSIONS_PAGE_INDEX -> "基础权限"
                        PERMISSION_LEVEL_PAGE_INDEX -> "权限级别"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )

                Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                when {
                                    pagerState.currentPage == PERMISSION_LEVEL_PAGE_INDEX &&
                                            uiState.selectedPermissionLevel != null -> {
                                        viewModel.savePermissionLevel(context)
                                    }
                                    pagerState.currentPage == BASIC_PERMISSIONS_PAGE_INDEX &&
                                            !uiState.allBasicPermissionsGranted -> {
                                        showPermissionWarning = true
                                    }
                                    pagerState.currentPage < pagerState.pageCount - 1 -> {
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                    }
                                }
                            }
                        },
                        enabled = when (pagerState.currentPage) {
                            in 0..WELCOME_PAGE_INDEX -> true
                            BASIC_PERMISSIONS_PAGE_INDEX -> true
                            PERMISSION_LEVEL_PAGE_INDEX -> uiState.selectedPermissionLevel != null
                            else -> false
                        }
                    ) {
                        Icon(
                            imageVector = if (pagerState.currentPage == PERMISSION_LEVEL_PAGE_INDEX) Icons.Default.Check
                            else Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = if (pagerState.currentPage == PERMISSION_LEVEL_PAGE_INDEX) "完成" else "下一步",
                            tint = when {
                                pagerState.currentPage < BASIC_PERMISSIONS_PAGE_INDEX -> MaterialTheme.colorScheme.primary
                                pagerState.currentPage == BASIC_PERMISSIONS_PAGE_INDEX -> MaterialTheme.colorScheme.primary
                                pagerState.currentPage == PERMISSION_LEVEL_PAGE_INDEX && uiState.selectedPermissionLevel != null ->
                                    MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            }
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 权限状态页面（引导完成后显示）
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionStatusPage(
    onBack: () -> Unit,
    onReconfigure: () -> Unit
) {
    val context = LocalContext.current
    val savedLevel = PermissionGuideViewModel.getSavedLevel(context) ?: AndroidPermissionLevel.STANDARD
    var viewingLevel by remember { mutableStateOf(savedLevel) }
    var showSetDialog by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf<String?>(null) }

    val levels = AndroidPermissionLevel.entries

    // 刷新权限状态
    var permissionStatuses by remember {
        mutableStateOf(PermissionGuideViewModel.getPermissionStatusForLevel(context, viewingLevel))
    }
    LaunchedEffect(viewingLevel) {
        permissionStatuses = PermissionGuideViewModel.getPermissionStatusForLevel(context, viewingLevel)
    }

    // 设为当前级别确认弹窗
    if (showSetDialog) {
        AlertDialog(
            onDismissRequest = { showSetDialog = false },
            icon = {
                Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            title = { Text("切换权限级别") },
            text = { Text("确定将权限级别切换为「${getLevelDisplayName(viewingLevel)}」？") },
            confirmButton = {
                Button(onClick = {
                    showSetDialog = false
                    if (PermissionGuideViewModel.validatePermissionLevel(context, viewingLevel)) {
                        val sp = context.getSharedPreferences("special_permissions", Context.MODE_PRIVATE)
                        sp.edit().putString("target_permission_level", viewingLevel.name).apply()
                        permissionStatuses = PermissionGuideViewModel.getPermissionStatusForLevel(context, viewingLevel)
                    } else {
                        showErrorDialog = PermissionGuideViewModel.getValidationErrorMessage(viewingLevel)
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showSetDialog = false }) { Text("取消") }
            }
        )
    }

    // 校验失败弹窗
    if (showErrorDialog != null) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = null },
            icon = {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("权限不足") },
            text = { Text(showErrorDialog!!) },
            confirmButton = {
                Button(onClick = { showErrorDialog = null }) { Text("确定") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("特殊权限状态") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        permissionStatuses = PermissionGuideViewModel.getPermissionStatusForLevel(context, viewingLevel)
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
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
        ) {
            // 顶部当前级别卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "当前权限级别",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                getLevelDisplayName(savedLevel),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        val allGranted = permissionStatuses.all { it.isGranted } && viewingLevel == savedLevel
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (allGranted) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                        ) {
                            Text(
                                if (allGranted) "已激活" else "未完全激活",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (allGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // 级别切换 TabRow
            ScrollableTabRow(
                selectedTabIndex = levels.indexOf(viewingLevel).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                levels.forEach { level ->
                    Tab(
                        selected = viewingLevel == level,
                        onClick = { viewingLevel = level },
                        text = {
                            Text(
                                getLevelDisplayName(level),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )
                }
            }

            // 级别描述
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    getLevelDescription(viewingLevel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            // 权限状态列表
            Text(
                "权限状态",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    permissionStatuses.forEachIndexed { index, status ->
                        PermissionStatusRow(status)
                        if (index < permissionStatuses.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 底部操作按钮
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (viewingLevel == savedLevel) {
                    // 当前正在使用
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "当前正在使用",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else {
                    // 设为当前级别
                    Button(
                        onClick = { showSetDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("设为当前级别")
                    }
                }

                // 重新配置
                OutlinedButton(
                    onClick = onReconfigure,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("重新配置")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionStatusRow(status: PermissionStatus) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (status.isGranted) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            tint = if (status.isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            status.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (status.isGranted) "已激活" else "未激活",
            style = MaterialTheme.typography.labelMedium,
            color = if (status.isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

private fun getLevelDisplayName(level: AndroidPermissionLevel): String {
    return when (level) {
        AndroidPermissionLevel.STANDARD -> "普通权限"
        AndroidPermissionLevel.ACCESSIBILITY -> "无障碍权限"
        AndroidPermissionLevel.ADB -> "Shizuku 权限"
        AndroidPermissionLevel.DEBUGGER -> "ADB 权限"
        AndroidPermissionLevel.ADMIN -> "管理员权限"
        AndroidPermissionLevel.ROOT -> "Root 权限"
    }
}

private fun getLevelDescription(level: AndroidPermissionLevel): String {
    return when (level) {
        AndroidPermissionLevel.STANDARD -> "使用标准 Android 应用权限，受系统沙盒保护。适用于一般文件管理和基础工具功能。"
        AndroidPermissionLevel.ACCESSIBILITY -> "通过无障碍服务模拟屏幕手势操作，无需 Root。可实现自动化操作、辅助功能等高级特性。"
        AndroidPermissionLevel.ADB -> "通过 Shizuku 获得 shell 级别权限。可执行 appops 等系统命令，管理应用权限设置。需安装并启动 Shizuku。"
        AndroidPermissionLevel.DEBUGGER -> "通过 ADB 调试授权获得 WRITE_SECURE_SETTINGS 权限。可修改系统设置数据库，需 USB 调试连接。"
        AndroidPermissionLevel.ADMIN -> "激活设备管理器获得系统级权限。可执行设备锁定、密码策略、远程擦除等管理操作。"
        AndroidPermissionLevel.ROOT -> "获取最高级超级用户权限，解除一切系统沙箱限制。可直接访问系统文件、修改受保护配置。"
    }
}

// ═══════════════════════════════════════════════════════════════
// 引导向导子页面（保留原有 UI）
// ═══════════════════════════════════════════════════════════════

@Composable
private fun IntroductionPage(title: String, description: String, pageIndex: Int) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "#${pageIndex + 1}",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text(text = title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = description, style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "权限配置向导", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "接下来我们将引导您完成基础权限检查和权限级别选择。",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(32.dp))
        Text(text = "请按下一步继续", style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

@Composable
private fun BasicPermissionsPage(
    hasStoragePermission: Boolean,
    hasOverlayPermission: Boolean,
    hasBatteryOptimizationExemption: Boolean,
    hasLocationPermission: Boolean,
    onStoragePermissionClick: () -> Unit,
    onOverlayPermissionClick: () -> Unit,
    onBatteryOptimizationClick: () -> Unit,
    onLocationPermissionClick: () -> Unit,
    onRefresh: () -> Unit
) {
    var refreshRotation by remember { mutableStateOf(0f) }
    val rotationAngle by animateFloatAsState(targetValue = refreshRotation, animationSpec = tween(500), label = "rotation")

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "基础权限", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "以下权限用于保障工具箱的基本功能正常运行",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PermissionItemRow("存储权限", "访问设备文件系统", hasStoragePermission, onStoragePermissionClick)
                HorizontalDivider()
                PermissionItemRow("悬浮窗权限", "显示悬浮窗和快捷操作", hasOverlayPermission, onOverlayPermissionClick)
                HorizontalDivider()
                PermissionItemRow("电池优化豁免", "后台持续运行服务", hasBatteryOptimizationExemption, onBatteryOptimizationClick)
                HorizontalDivider()
                PermissionItemRow("位置权限", "获取设备位置信息", hasLocationPermission, onLocationPermissionClick)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(onClick = {
            refreshRotation += 360f
            onRefresh()
        }) {
            Icon(Icons.Default.Refresh, contentDescription = null,
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotationAngle })
            Spacer(modifier = Modifier.width(8.dp))
            Text("重新检查")
        }

        val allGranted = hasStoragePermission && hasOverlayPermission &&
                hasBatteryOptimizationExemption && hasLocationPermission

        AnimatedVisibility(visible = allGranted, enter = fadeIn(), exit = fadeOut()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("所有基础权限已授予", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun PermissionItemRow(title: String, description: String, isGranted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(text = description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(
                    if (isGranted) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    else MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                    CircleShape
                )
                .border(1.dp,
                    if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.Check else Icons.Default.Close,
                contentDescription = if (isGranted) "已授予" else "未授予",
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun PermissionLevelPage(
    selectedLevel: AndroidPermissionLevel?,
    onLevelSelected: (AndroidPermissionLevel) -> Unit,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "选择权限级别", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "选择适合您设备状态的权限级别，不同级别解锁不同功能",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(modifier = Modifier.height(24.dp))

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PermissionLevelItem(AndroidPermissionLevel.STANDARD, "普通权限", "标准应用权限，受系统沙盒保护",
                selectedLevel == AndroidPermissionLevel.STANDARD) { onLevelSelected(AndroidPermissionLevel.STANDARD) }
            PermissionLevelItem(AndroidPermissionLevel.ACCESSIBILITY, "无障碍权限", "允许模拟屏幕手势操作，免 Root 辅助",
                selectedLevel == AndroidPermissionLevel.ACCESSIBILITY) { onLevelSelected(AndroidPermissionLevel.ACCESSIBILITY) }
            PermissionLevelItem(AndroidPermissionLevel.ADB, "Shizuku 权限", "通过 Shizuku 获得 shell 级别权限，可执行 appops 等系统命令",
                selectedLevel == AndroidPermissionLevel.ADB) { onLevelSelected(AndroidPermissionLevel.ADB) }
            PermissionLevelItem(AndroidPermissionLevel.DEBUGGER, "ADB 权限", "通过 USB 调试授予 WRITE_SECURE_SETTINGS，可修改系统设置",
                selectedLevel == AndroidPermissionLevel.DEBUGGER) { onLevelSelected(AndroidPermissionLevel.DEBUGGER) }
            PermissionLevelItem(AndroidPermissionLevel.ADMIN, "管理员权限", "系统级设备管理器，提供强制锁定及防护特权",
                selectedLevel == AndroidPermissionLevel.ADMIN) { onLevelSelected(AndroidPermissionLevel.ADMIN) }
            PermissionLevelItem(AndroidPermissionLevel.ROOT, "Root 权限", "最高级超级用户控制权限，解除一切系统沙箱约束",
                selectedLevel == AndroidPermissionLevel.ROOT) { onLevelSelected(AndroidPermissionLevel.ROOT) }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onConfirm,
            enabled = selectedLevel != null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
        ) {
            Text("确认并应用", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "可随时在安全设置中更改权限级别",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

@Composable
private fun PermissionLevelItem(
    level: AndroidPermissionLevel,
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        CircleShape
                    )
                    .border(1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(Icons.Default.Check, contentDescription = "已选择",
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.titleSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(text = description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
    }
}
