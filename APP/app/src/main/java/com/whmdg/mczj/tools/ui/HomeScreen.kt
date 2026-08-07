package com.whmdg.mczj.tools.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.util.FormatUtils
import com.whmdg.mczj.tools.util.SizeTreeNode
import com.whmdg.mczj.tools.encryption.services.VaultService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.whmdg.mczj.tools.auth.Feature
import com.whmdg.mczj.tools.auth.NoPermissionDialog
import com.whmdg.mczj.tools.auth.PasswordDialog
import com.whmdg.mczj.tools.auth.PermissionManager
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.em
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

// Screen、ModuleId、MODULE_REGISTRY、NavigateGate 等已移至 core 模块的 Screen.kt
// 本文件仅保留 MainAppContainer（导航路由）和 HomeScreen（主页 UI）

import com.whmdg.mczj.tools.ui.accounting.AccountingModuleScreen
import com.whmdg.mczj.tools.ui.components.GlowCard
import com.whmdg.mczj.tools.ui.theme.LocalIsDarkMode
import com.whmdg.mczj.tools.ui.components.GlowSection
import com.whmdg.mczj.tools.ui.components.GlowListItem
import com.whmdg.mczj.tools.ui.components.GlowToggleItem
import com.whmdg.mczj.tools.ui.components.glowEffect
import com.whmdg.mczj.tools.ui.security.SecurityModuleScreen
import com.whmdg.mczj.tools.ui.security.AuthManagementScreen
import com.whmdg.mczj.tools.ui.security.PermissionGuideViewModel
import com.whmdg.mczj.tools.fileop.FileOperationManager
import com.whmdg.mczj.tools.ui.encryption.EncryptionModuleScreen
import com.whmdg.mczj.tools.ui.filemanager.FileManagerModuleScreen
import com.whmdg.mczj.tools.ui.download.DownloaderModuleScreen
import com.whmdg.mczj.tools.ui.rphub.RpHubModuleScreen
import com.whmdg.mczj.tools.ui.diary.DiaryModuleScreen
import com.whmdg.mczj.tools.ui.wifi.WifiModuleScreen
import com.whmdg.mczj.tools.ui.hook.HookModuleScreen

@Composable
fun MainAppContainer() {
    val context = LocalContext.current
    val backStack = remember { mutableStateListOf<Screen>(Screen.Dashboard) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var accountingSelectedTab by remember { mutableIntStateOf(0) }

    val vaultService = remember { VaultService(context).apply { load() } }

    // 注册保险箱存储用量变更回调（FileOperationManager 完成 vault 操作后通知）
    DisposableEffect(vaultService) {
        FileOperationManager.setVaultSizeChangeCallback { vaultId, delta ->
            vaultService.updateStorageSize(vaultId, delta)
        }
        onDispose { FileOperationManager.setVaultSizeChangeCallback(null) }
    }

    // ── 诊断状态（Debug 模式） ──
    val isDebugMode = remember { isDebugAuth(context) }
    var startupDiagnostic by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val sp = context.getSharedPreferences(AppDataPaths.PREFS_LEGACY_SPECIAL_PERMISSIONS, Context.MODE_PRIVATE)
        val target = sp.getString("target_permission_level", "NORMAL") ?: "NORMAL"
        if (target != "NORMAL") {
            val isStillValid = when (target) {
                "ACCESSIBILITY" -> com.whmdg.mczj.tools.security.SpecialPermissionVerifier.isAccessibilityEnabled(context)
                "ADB" -> com.whmdg.mczj.tools.security.SpecialPermissionVerifier.isAdbEnabled(context)
                "ADMIN" -> com.whmdg.mczj.tools.security.SpecialPermissionVerifier.isDeviceAdminActive(context)
                "ROOT" -> com.whmdg.mczj.tools.security.SpecialPermissionVerifier.isRootAvailable()
                else -> true
            }
            if (!isStillValid) {
                sp.edit().putString("target_permission_level", "NORMAL").apply()
                Toast.makeText(context, "安全环境已改变，特殊权限不可用，已自动退回普通用户模式", Toast.LENGTH_LONG).show()
                if (isDebugMode) {
                    startupDiagnostic = "当前 $target 不可用，回退到 Standard 模式"
                }
            } else {
                if (isDebugMode) {
                    val targetName = when (target) {
                        "ROOT" -> "Root"
                        "ADB" -> "ADB"
                        "ADMIN" -> "设备管理员"
                        "ACCESSIBILITY" -> "无障碍"
                        else -> target
                    }
                    startupDiagnostic = "${targetName} 权限已激活"
                }
            }
        } else {
            if (isDebugMode) {
                startupDiagnostic = "Standard 模式（无特殊权限）"
            }
        }
    }

    val currentScreen = backStack.last()

    var encryptionError by remember { mutableStateOf<Throwable?>(null) }
    val currentScreenState = rememberUpdatedState(currentScreen)

    val isCurrentlyInEncryptionFlow = {
        val scr = currentScreenState.value
        scr is Screen.Encryption
    }

    LaunchedEffect(Unit) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            while (true) {
                try {
                    android.os.Looper.loop()
                } catch (e: Throwable) {
                    if (isCurrentlyInEncryptionFlow()) {
                        encryptionError = e
                    } else {
                        // 手动触发全局异常处理器，避免异常在 Looper.loop() 中无限循环
                        Thread.getDefaultUncaughtExceptionHandler()
                            ?.uncaughtException(Thread.currentThread(), e)
                    }
                }
            }
        }
    }

    fun navigateTo(screen: Screen) {
        backStack.add(screen)
    }

    fun navigateBack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.size - 1)
        }
    }

    if (encryptionError != null) {
        ErrorDialog(error = encryptionError, onDismiss = { encryptionError = null })
    }

    var backPressedTime by remember { mutableStateOf(0L) }
    BackHandler(enabled = true) {
        if (backStack.size > 1) {
            navigateBack()
        } else {
            val now = System.currentTimeMillis()
            if (now - backPressedTime <= 2000) {
                (context as? Activity)?.finish()
            } else {
                backPressedTime = now
                Toast.makeText(context, "再滑一次退出应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    when (currentScreen) {
        is Screen.Dashboard -> {
            HomeScreen(
                selectedTab = selectedTab,
                onTabSelect = { selectedTab = it },
                onNavigate = { navigateTo(it) }
            )
        }
        is Screen.Settings -> {
            HomeScreen(
                selectedTab = 1,
                onTabSelect = { selectedTab = it },
                onNavigate = { navigateTo(it) }
            )
        }
        is Screen.Security -> {
            SecurityModuleScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.ThemeSettings -> {
            ThemeSettingsScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.Encryption -> {
            EncryptionModuleScreen(
                vaultService = vaultService,
                onBack = { navigateBack() },
                onNavigate = { navigateTo(it) }
            )
        }
        is Screen.SpecialPermissions -> {
            SecurityModuleScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.AppPermissions -> {
            SecurityModuleScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.PermissionManagementConfig -> {
            SecurityModuleScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.FileManager -> {
            FileManagerModuleScreen(
                onBack = { navigateBack() },
                vaultSession = currentScreen.vaultSession,
                vaultService = vaultService
            )
        }
        is Screen.BatchDownloader -> {
            DownloaderModuleScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.AuthManagement -> {
            AuthManagementScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.RpHub -> {
            RpHubModuleScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.Diary -> {
            DiaryModuleScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.Wifi -> {
            WifiModuleScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.Accounting -> {
            AccountingModuleScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.Hook -> {
            HookModuleScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.FunctionalTest -> {
            FunctionalTestScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.About -> {
            AboutScreen(
                onBack = { navigateBack() },
                onNavigate = { navigateTo(it) }
            )
        }
        is Screen.Changelog -> {
            ChangelogScreen(
                onBack = { navigateBack() }
            )
        }
    }

    // ── 大小统计状态面板（底部悬浮，工具栏上方，全局显示） ──
    val calcStatus = SizeCalcManager.statusMessage
    val calcIsCalculating = SizeCalcManager.isCalculating
    if (calcIsCalculating || calcStatus != null) {
        val bgColor = if (calcIsCalculating)
            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        else
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.95f)
                .padding(bottom = 68.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(bgColor)
                .clickable(interactionSource = null, indication = null) {}
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                if (calcIsCalculating && calcStatus != null) {
                    // 阶段一：正在统计文件夹数量（find 执行中）
                    Text(
                        text = calcStatus!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else if (calcIsCalculating) {
                    // 阶段二：进度条显示
                    val calcProgress = SizeCalcManager.progress
                    val calcScanned = SizeCalcManager.scannedCount
                    val calcTotal = SizeCalcManager.totalCount
                    val cooldownSec = SizeCalcManager.binderCooldownSeconds
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LinearProgressIndicator(
                            progress = { calcProgress },
                            modifier = Modifier.weight(1f).height(6.dp),
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${(calcProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    if (cooldownSec > 0) {
                        Text(
                            text = "Binder 队列过长，等待 ${cooldownSec} 秒...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            text = "已扫描 $calcScanned / $calcTotal 个目录  ${(calcProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // 阶段三：已统计完成
                    Text(
                        text = calcStatus!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                // 取消/保存按钮（仅计算中显示）
                if (calcIsCalculating) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { SizeCalcManager.requestCancel() }) {
                            Text("取消", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = { SizeCalcManager.save() }) {
                            Text("保存", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                } else {
                    // 完成状态：关闭按钮
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { SizeCalcManager.dismissStatus() }) {
                            Text("关闭", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    // ── 大小统计完成树形弹窗 ──
    val calcTree = SizeCalcManager.completedTree
    if (calcTree != null && !calcIsCalculating) {
        val expandedPaths = remember { mutableStateSetOf<String>() }

        data class FlatNode(val node: SizeTreeNode, val depth: Int)

        fun flatten(node: SizeTreeNode, depth: Int): List<FlatNode> {
            val result = mutableListOf(FlatNode(node, depth))
            if (node.isDir && node.path in expandedPaths) {
                for (child in node.children) {
                    result.addAll(flatten(child, depth + 1))
                }
            }
            return result
        }

        // expandedPaths 变化时 recompose（expandedPaths.size 读取 State 值触发）
        @Suppress("UNUSED_VARIABLE")
        val expandedVersion = expandedPaths.size
        val flatList = flatten(calcTree, 0)

        Dialog(onDismissRequest = {
            expandedPaths.clear()
            SizeCalcManager.completedTree = null
        }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.75f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 标题
                    Text(
                        text = calcTree.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = FormatUtils.formatBytes(calcTree.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))

                    // 树形列表
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(flatList, key = { it.node.path }) { (node, depth) ->
                            val isExpanded = node.path in expandedPaths
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (node.isDir) {
                                            if (isExpanded) expandedPaths.remove(node.path)
                                            else expandedPaths.add(node.path)
                                        }
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                // 缩进
                                Spacer(Modifier.width((depth * 16).dp))
                                // 三角/文件图标
                                if (node.isDir) {
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Spacer(Modifier.width(18.dp))
                                }
                                Spacer(Modifier.width(4.dp))
                                // 名称
                                Text(
                                    text = node.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                // 大小
                                Text(
                                    text = FormatUtils.formatBytes(node.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    // 底部按钮
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            expandedPaths.clear()
                            SizeCalcManager.completedTree = null
                        }) {
                            Text("我知道了", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    // ── 大小统计报错弹窗 ──
    val calcError = SizeCalcManager.loadError
    if (calcError != null) {
        ErrorDialog(
            error = calcError,
            onDismiss = { SizeCalcManager.loadError = null }
        )
    }

    // ── 保存进度？对话框 ──
    if (SizeCalcManager.pendingSaveDialog) {
        AlertDialog(
            onDismissRequest = { SizeCalcManager.discardPartial() },
            title = { Text("统计中断") },
            text = { Text("统计过程中发生错误，是否保存已统计的部分结果？") },
            confirmButton = {
                TextButton(onClick = { SizeCalcManager.confirmSavePartial() }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { SizeCalcManager.discardPartial() }) {
                    Text("丢弃")
                }
            }
        )
    }
    } // Box

    // ── 启动诊断信息显示（底部 Snackbar，仅 Debug 模式，5 秒自动消失） ──
    LaunchedEffect(startupDiagnostic) {
        if (startupDiagnostic != null) {
            kotlinx.coroutines.delay(5000)
            startupDiagnostic = null
        }
    }
    if (isDebugMode && startupDiagnostic != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .clickable { startupDiagnostic = null },  // 点击关闭
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp
            ) {
                Text(
                    text = startupDiagnostic ?: "",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    selectedTab: Int,
    onTabSelect: (Int) -> Unit,
    onNavigate: (Screen) -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (selectedTab == 0) "艨艟战舰" else "设置") }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { onTabSelect(0) },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 0) Icons.Filled.Home else Icons.Outlined.Home,
                            contentDescription = "主页"
                        )
                    },
                    label = { Text("主页") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { onTabSelect(1) },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 1) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = "设置"
                        )
                    },
                    label = { Text("设置") }
                )
            }
        }
    ) { innerPadding ->
        NavigateGate(onNavigate = onNavigate) { navigateToModule ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (selectedTab == 0) {
                    HomeTab(navigateToModule = navigateToModule)
                } else {
                    SettingsTab(navigateToModule = navigateToModule, onNavigate = onNavigate)
                }
            }
        }
    }
}

private val HOME_MODULE_IDS = listOf(
    ModuleId.ENCRYPTION,
    ModuleId.FILE_MANAGER,
    ModuleId.APP_PERMISSIONS,
    ModuleId.BATCH_DOWNLOADER
)

@Composable
fun HomeTab(navigateToModule: (ModuleId) -> Unit) {
    val authState by PermissionManager.state.collectAsState()
    val context = LocalContext.current

    // 应用启动时检测权限是否仍然有效
    LaunchedEffect(Unit) {
        val permissionDowngraded = PermissionGuideViewModel.validateAndUpdatePermission(context)
        if (permissionDowngraded) {
            Toast.makeText(context, "检测到权限已失效，已降级为普通权限", Toast.LENGTH_LONG).show()
        }
    }

    // 文件管理器预加载：与主界面渲染同时触发，不拖慢启动
    LaunchedEffect(Unit) {
        com.whmdg.mczj.tools.ui.filemanager.FileManagerPreloader.preload(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (authState is PermissionManager.AuthState.Locked) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Text(
                    text = "点击任意模块输入密钥以解锁",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        SettingsSection(
            title = "工具",
            icon = Icons.Default.Build
        ) {
            HOME_MODULE_IDS.forEach { moduleId ->
                val entry = MODULE_REGISTRY[moduleId]!!
                CompactSettingsItem(
                    title = entry.title,
                    subtitle = entry.subtitle,
                    icon = entry.icon,
                    enabled = PermissionManager.has(entry.feature),
                    onClick = { navigateToModule(moduleId) }
                )
            }
        }

        SettingsSection(
            title = "应用",
            icon = Icons.Default.Apps
        ) {
            listOf(ModuleId.RP_HUB, ModuleId.DIARY, ModuleId.ACCOUNTING, ModuleId.HOOK).forEach { moduleId ->
                val entry = MODULE_REGISTRY[moduleId]!!
                CompactSettingsItem(
                    title = entry.title,
                    subtitle = entry.subtitle,
                    icon = entry.icon,
                    enabled = PermissionManager.has(entry.feature),
                    onClick = { navigateToModule(moduleId) }
                )
            }
        }

        SettingsSection(
            title = "网络",
            icon = Icons.Default.NetworkCheck
        ) {
            val wifiEntry = MODULE_REGISTRY[ModuleId.WIFI]!!
            CompactSettingsItem(
                title = wifiEntry.title,
                subtitle = wifiEntry.subtitle,
                icon = wifiEntry.icon,
                enabled = PermissionManager.has(wifiEntry.feature),
                onClick = { navigateToModule(ModuleId.WIFI) }
            )
        }

    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(onBack: () -> Unit) {
    val isDarkMode = com.whmdg.mczj.tools.ui.theme.LocalIsDarkMode.current
    val onToggleTheme = com.whmdg.mczj.tools.ui.theme.LocalOnToggleTheme.current
    val isGlowEnabled = com.whmdg.mczj.tools.ui.theme.LocalIsGlowEnabled.current
    val onToggleGlow = com.whmdg.mczj.tools.ui.theme.LocalOnToggleGlow.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("主题") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item {
                Text(
                    text = "背景主题",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(if (isDarkMode) "黑夜" else "白天") },
                    supportingContent = {
                        Text(if (isDarkMode) "深色背景，浅色文字" else "浅色背景，深色文字")
                    },
                    leadingContent = {
                        Icon(
                            if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                            contentDescription = null
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = isDarkMode,
                            onCheckedChange = { onToggleTheme(it) }
                        )
                    }
                )
            }

            item {
                Text(
                    text = "卡片效果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("光晕扩散") },
                    supportingContent = {
                        Text(if (isGlowEnabled) "卡片边框带有青色光晕扩散效果" else "仅显示线条边框，无光晕")
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.Flare,
                            contentDescription = null
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = isGlowEnabled,
                            onCheckedChange = { onToggleGlow(it) }
                        )
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FunctionalTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AppDataPaths.PREFS_RP_HUB, Context.MODE_PRIVATE) }
    var debugMode by remember { mutableStateOf(prefs.getBoolean("debug_mode", false)) }
    val hasDebugPerm = PermissionManager.has(Feature.DEBUG_MODE)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("功能性测试") },
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
            if (!hasDebugPerm) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("无权限", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(8.dp))
                        Text("当前密钥不含 DEBUG_MODE 权限，无法使用功能性测试。", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            } else {
                SettingsSection(
                    title = "RP-Hub",
                    icon = Icons.Default.BugReport
                ) {
                    CompactSettingsToggle(
                        title = "Debug 模式",
                        subtitle = "显示 CDN 加载诊断面板：资源状态、全局变量、JS 错误",
                        icon = Icons.Default.BugReport,
                        checked = debugMode,
                        onCheckedChange = {
                            debugMode = it
                            prefs.edit().putBoolean("debug_mode", it).apply()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsTab(navigateToModule: (ModuleId) -> Unit, onNavigate: (Screen) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SettingsSection(
            title = "外观",
            icon = Icons.Default.Palette
        ) {
            CompactSettingsItem(
                title = "主题",
                subtitle = "深色/浅色模式切换",
                icon = Icons.Default.DarkMode,
                onClick = { onNavigate(Screen.ThemeSettings) }
            )
        }

        SettingsSection(
            title = "安全与权限",
            icon = Icons.Default.Security
        ) {
            val secEntry = MODULE_REGISTRY[ModuleId.SECURITY]!!
            CompactSettingsItem(
                title = secEntry.title,
                subtitle = secEntry.subtitle,
                icon = secEntry.icon,
                enabled = PermissionManager.has(secEntry.feature),
                onClick = { navigateToModule(ModuleId.SECURITY) }
            )
            CompactSettingsItem(
                title = "更改密钥授权",
                subtitle = "切换或清除当前权限令牌",
                icon = Icons.Default.VpnKey,
                onClick = { onNavigate(Screen.AuthManagement) }
            )
        }

        SettingsSection(
            title = "开发",
            icon = Icons.Default.Code
        ) {
            val hasDebugPerm = PermissionManager.has(Feature.DEBUG_MODE)
            CompactSettingsItem(
                title = "功能性测试",
                subtitle = if (hasDebugPerm) "调试工具与诊断模式" else "需要 DEBUG_MODE 权限",
                icon = Icons.Default.BugReport,
                enabled = hasDebugPerm,
                onClick = { onNavigate(Screen.FunctionalTest) }
            )
            CompactSettingsItem(
                title = "关于",
                subtitle = "版本信息与更新日志",
                icon = Icons.Default.Info,
                enabled = true,
                onClick = { onNavigate(Screen.About) }
            )
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
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    GlowListItem(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = onClick,
        enabled = enabled
    )
}

@Composable
private fun CompactSettingsToggle(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    GlowToggleItem(
        title = title,
        subtitle = subtitle,
        icon = icon,
        checked = checked,
        onCheckedChange = onCheckedChange
    )
}

@Composable
fun FrostedGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 0.5.dp,
            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.5f),
                    Color.White.copy(alpha = 0.15f)
                )
            )
        )
    ) {
        content()
    }
}

@Composable
fun ToolTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onTap: () -> Unit
) {
    GlowCard(
        modifier = Modifier.clickable(onClick = onTap)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color(0xFF38D4F5),
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFE8F4FF)
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0x9964B4D2)
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "进入",
                tint = Color(0xFF38D4F5)
            )
        }
    }
}

@Composable
fun VaultInfoRow(label: String, value: String) {
    val isDarkMode = LocalIsDarkMode.current
    val labelColor = if (isDarkMode) Color(0x9964B4D2) else Color(0x9964748B)
    val valueColor = if (isDarkMode) Color(0xFFA8D4F0) else Color(0xFF0EA5E9)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = labelColor,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            letterSpacing = 0.03.em
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

