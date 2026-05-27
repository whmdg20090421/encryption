package com.whmdg.mczj.tools.ui

import android.Manifest
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whmdg.mczj.tools.security.AndroidPermissionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val INTRO_PAGES_COUNT = 3
private const val WELCOME_PAGE_INDEX = INTRO_PAGES_COUNT
private const val BASIC_PERMISSIONS_PAGE_INDEX = INTRO_PAGES_COUNT + 1
private const val PERMISSION_LEVEL_PAGE_INDEX = INTRO_PAGES_COUNT + 2
private const val TOTAL_PAGES_COUNT = INTRO_PAGES_COUNT + 3

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpecialPermissionsScreen(
    onBack: () -> Unit,
    viewModel: PermissionGuideViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
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

    LaunchedEffect(uiState.isCompleted) {
        if (uiState.isCompleted) {
            delay(500)
            onBack()
        }
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
                                context.startActivity(Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                ).apply { data = Uri.parse("package:${context.packageName}") })
                            } catch (_: Exception) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
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
            PermissionLevelItem(AndroidPermissionLevel.ADB, "ADB 权限", "高级调试授权，可通过 Shizuku 或 USB 调试授予",
                selectedLevel == AndroidPermissionLevel.ADB) { onLevelSelected(AndroidPermissionLevel.ADB) }
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
