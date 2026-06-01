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
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.encryption.data.StorageLocation
import com.whmdg.mczj.tools.encryption.data.VaultConfig
import com.whmdg.mczj.tools.encryption.data.VaultRecord
import com.whmdg.mczj.tools.encryption.services.VaultService
import com.whmdg.mczj.tools.encryption.services.VaultSession
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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

import com.whmdg.mczj.tools.ui.components.GlowCard
import com.whmdg.mczj.tools.ui.theme.LocalIsDarkMode
import com.whmdg.mczj.tools.ui.components.GlowSection
import com.whmdg.mczj.tools.ui.components.GlowListItem
import com.whmdg.mczj.tools.ui.components.GlowToggleItem
import com.whmdg.mczj.tools.ui.components.glowEffect

/** 鉴权调试开关：由 debug_mode SharedPreferences 控制 */
fun isDebugAuth(ctx: Context): Boolean =
    ctx.getSharedPreferences(AppDataPaths.PREFS_RP_HUB, Context.MODE_PRIVATE)
        .getBoolean("debug_mode", false)

fun featureDisplayName(f: Feature): String = when (f) {
    Feature.ENCRYPTION_VAULT -> "加密"
    Feature.FILE_MANAGER -> "文件管理器"
    Feature.APP_PERMISSIONS -> "应用权限管理"
    Feature.BATCH_DOWNLOADER -> "批量下载器"
    Feature.SECURITY_SETTINGS -> "安全"
    Feature.DEBUG_MODE -> "调试模式"
    Feature.RP_HUB -> "RP-Hub"
}

sealed class Screen {
    object Dashboard : Screen()
    object Settings : Screen()
    object Security : Screen()
    object PermissionSettings : Screen()
    object SpecialPermissions : Screen()
    object AppPermissions : Screen()
    object PermissionManagementConfig : Screen()
    object FileManager : Screen()
    object BatchDownloader : Screen()
    object FADownloader : Screen()
    object FALogin : Screen()
    object DeviantDownloader : Screen()
    object DeviantLogin : Screen()
    object ThemeSettings : Screen()
    object EncryptionHome : Screen()
    object VaultCreate : Screen()
    data class VaultOpen(val session: VaultSession) : Screen()
    data class VaultChangePassword(val vault: VaultRecord) : Screen()
    object AuthManagement : Screen()
    object FunctionalTest : Screen()
    object RpHub : Screen()
}

enum class ModuleId {
    ENCRYPTION,
    FILE_MANAGER,
    APP_PERMISSIONS,
    BATCH_DOWNLOADER,
    SECURITY,
    RP_HUB
}

data class ModuleEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val feature: Feature,
    val screen: Screen
)

val MODULE_REGISTRY: Map<ModuleId, ModuleEntry> = mapOf(
    ModuleId.ENCRYPTION to ModuleEntry("加密", "常用加密 / 解密工具", Icons.Default.Lock, Feature.ENCRYPTION_VAULT, Screen.EncryptionHome),
    ModuleId.FILE_MANAGER to ModuleEntry("文件管理器", "双面板文件浏览工具", Icons.Default.Folder, Feature.FILE_MANAGER, Screen.FileManager),
    ModuleId.APP_PERMISSIONS to ModuleEntry("应用权限管理", "查看和管理应用权限", Icons.Default.Security, Feature.APP_PERMISSIONS, Screen.AppPermissions),
    ModuleId.BATCH_DOWNLOADER to ModuleEntry("批量下载器", "FA 图片批量下载等工具", Icons.Default.Download, Feature.BATCH_DOWNLOADER, Screen.BatchDownloader),
    ModuleId.SECURITY to ModuleEntry("安全", "权限设置与特殊权限管理", Icons.Default.Lock, Feature.SECURITY_SETTINGS, Screen.Security),
    ModuleId.RP_HUB to ModuleEntry("RP-Hub", "本地角色扮演对话工具", Icons.Default.SmartToy, Feature.RP_HUB, Screen.RpHub)
)

/**
 * 集中式鉴权跳转：传入 ModuleId → 校验权限 → 通过则由本函数跳转，否则拦截。
 */
@Composable
fun NavigateGate(
    onNavigate: (Screen) -> Unit,
    content: @Composable (navigateToModule: (ModuleId) -> Unit) -> Unit
) {
    val authState by PermissionManager.state.collectAsState()
    val ctx = LocalContext.current

    var pendingModule by remember { mutableStateOf<ModuleId?>(null) }
    var noPermModule by remember { mutableStateOf<ModuleId?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    // DEBUG: 认证成功后的调试弹窗状态
    data class DebugInfo(
        val keyId: String,
        val features: Set<Feature>,
        val neededFeature: Feature,
        val hasPerm: Boolean,
        val authState: String,
        val targetScreen: Screen
    )
    var debugInfo by remember { mutableStateOf<DebugInfo?>(null) }

    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            toastMsg = null
        }
    }

    fun navigateToModule(moduleId: ModuleId) {
        val entry = MODULE_REGISTRY[moduleId] ?: return
        if (PermissionManager.has(entry.feature)) {
            onNavigate(entry.screen)
        } else if (PermissionManager.state.value is PermissionManager.AuthState.Locked) {
            pendingModule = moduleId
        } else {
            noPermModule = moduleId
        }
    }

    content(::navigateToModule)

    // 未登录 → 密码输入弹窗
    pendingModule?.let { moduleId ->
        val entry = MODULE_REGISTRY[moduleId]!!
        PasswordDialog(
            onDismiss = { pendingModule = null },
            onVerify = { pw ->
                val res = PermissionManager.tryAuthenticate(ctx, pw)
                if (res.isSuccess) {
                    val features = res.getOrNull() ?: emptySet()
                    val hasPerm = PermissionManager.has(entry.feature)
                    val state = PermissionManager.state.value
                    val keyId = (state as? PermissionManager.AuthState.Authed)?.keyId ?: "?"
                    pendingModule = null
                    debugInfo = DebugInfo(
                        keyId = keyId,
                        features = features,
                        neededFeature = entry.feature,
                        hasPerm = hasPerm,
                        authState = state.toString(),
                        targetScreen = entry.screen
                    )
                    true
                } else {
                    false
                }
            }
        )
    }

    // 已登录但权限不足 → 仅提示
    noPermModule?.let { moduleId ->
        val entry = MODULE_REGISTRY[moduleId]!!
        NoPermissionDialog(
            feature = entry.feature,
            onDismiss = { noPermModule = null }
        )
    }

    // 认证成功弹窗（Debug 模式时显示详细调试信息，否则显示简洁权限列表）
    debugInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { debugInfo = null },
            title = { Text("密钥已激活") },
            text = {
                Column {
                    if (isDebugAuth(ctx)) {
                        // DEBUG 模式：显示全部变量值
                        Text("当前已激活权限：", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("keyId = \"${info.keyId}\"")
                        Text("features = ${info.features.joinToString { it.name }}")
                        Text("neededFeature = ${info.neededFeature.name}")
                        Text("hasPerm = ${info.hasPerm}")
                        Text("authState = ${info.authState}")
                        Text("targetScreen = ${info.targetScreen::class.simpleName}")
                    } else {
                        // 生产模式：简洁权限列表
                        Text("你拥有以下权限：")
                        Spacer(modifier = Modifier.height(8.dp))
                        info.features.forEach { f ->
                            Text("· ${featureDisplayName(f)}")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val screen = info.targetScreen
                    debugInfo = null
                    if (info.hasPerm) {
                        onNavigate(screen)
                    }
                }) {
                    Text(if (info.hasPerm) "继续进入" else "确定")
                }
            }
        )
    }
}

@Composable
fun MainAppContainer() {
    val context = LocalContext.current
    val backStack = remember { mutableStateListOf<Screen>(Screen.Dashboard) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val vaultService = remember { VaultService(context).apply { load() } }
    val encryptionSettings = remember { EncryptionSettings(context) }

    LaunchedEffect(Unit) {
        val sp = context.getSharedPreferences("special_permissions", Context.MODE_PRIVATE)
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
            }
        }
    }

    val currentScreen = backStack.last()

    var encryptionError by remember { mutableStateOf<Throwable?>(null) }
    val currentScreenState = rememberUpdatedState(currentScreen)

    val isCurrentlyInEncryptionFlow = {
        val scr = currentScreenState.value
        scr is Screen.EncryptionHome ||
        scr is Screen.VaultCreate ||
        scr is Screen.VaultOpen ||
        scr is Screen.VaultChangePassword ||
        scr is Screen.FileManager
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
                        throw e
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
            SecurityScreen(
                onBack = { navigateBack() },
                onNavigate = { navigateTo(it) }
            )
        }
        is Screen.PermissionSettings -> {
            PermissionSettingsScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.ThemeSettings -> {
            ThemeSettingsScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.EncryptionHome -> {
            EncryptionHomeScreen(
                vaultService = vaultService,
                settings = encryptionSettings,
                onBack = { navigateBack() },
                onNavigate = { navigateTo(it) }
            )
        }
        is Screen.VaultCreate -> {
            VaultCreateScreen(
                vaultService = vaultService,
                onBack = { navigateBack() }
            )
        }
        is Screen.VaultOpen -> {
            VaultOpenScreen(
                session = currentScreen.session,
                onBack = { navigateBack() },
                vaultService = vaultService
            )
        }
        is Screen.VaultChangePassword -> {
            VaultChangePasswordScreen(
                vaultService = vaultService,
                vault = currentScreen.vault,
                onBack = { navigateBack() }
            )
        }
        is Screen.SpecialPermissions -> {
            SpecialPermissionsScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.AppPermissions -> {
            AppPermissionsScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.PermissionManagementConfig -> {
            PermissionManagementConfigScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.FileManager -> {
            FileManagerScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.BatchDownloader -> {
            com.whmdg.mczj.tools.ui.download.BatchDownloaderScreen(
                onBack = { navigateBack() },
                onNavigate = { navigateTo(it) }
            )
        }
        is Screen.FADownloader -> {
            com.whmdg.mczj.tools.ui.download.FADownloaderScreen(
                onBack = { navigateBack() },
                onLogin = { navigateTo(Screen.FALogin) }
            )
        }
        is Screen.FALogin -> {
            com.whmdg.mczj.tools.ui.download.FALoginScreen(
                onBack = { navigateBack() },
                onLoginSuccess = { cookie, username ->
                    com.whmdg.mczj.tools.ui.download.FADownloaderViewModel.saveCookieStatic(
                        context, cookie, username
                    )
                    navigateBack()
                }
            )
        }
        is Screen.DeviantDownloader -> {
            com.whmdg.mczj.tools.ui.download.Deviant.DeviantDownloaderScreen(
                onBack = { navigateBack() },
                onLogin = { navigateTo(Screen.DeviantLogin) }
            )
        }
        is Screen.DeviantLogin -> {
            com.whmdg.mczj.tools.ui.download.Deviant.DeviantLoginScreen(
                onBack = { navigateBack() },
                onLoginSuccess = { cookie ->
                    com.whmdg.mczj.tools.ui.download.Deviant.DeviantDownloaderViewModel.saveCookieStatic(
                        context, cookie
                    )
                    navigateBack()
                }
            )
        }
        is Screen.AuthManagement -> {
            AuthManagementScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.RpHub -> {
            RpHubScreen(
                onBack = { navigateBack() }
            )
        }
        is Screen.FunctionalTest -> {
            FunctionalTestScreen(
                onBack = { navigateBack() }
            )
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
            val rpHubEntry = MODULE_REGISTRY[ModuleId.RP_HUB]!!
            CompactSettingsItem(
                title = rpHubEntry.title,
                subtitle = rpHubEntry.subtitle,
                icon = rpHubEntry.icon,
                enabled = PermissionManager.has(rpHubEntry.feature),
                onClick = { navigateToModule(ModuleId.RP_HUB) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncryptionHomeScreen(
    vaultService: VaultService,
    settings: EncryptionSettings,
    onBack: () -> Unit,
    onNavigate: (Screen) -> Unit
) {
    var subTab by remember { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var importFolderUri by remember { mutableStateOf<Uri?>(null) }
    var importFolderName by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var importUseSaf by remember { mutableStateOf(false) }
    var encryptionError by remember { mutableStateOf<Throwable?>(null) }
    var fatalError by remember { mutableStateOf<Throwable?>(null) }
    var showImportPermissionDialog by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            importFolderUri = it
            importFolderName = it.lastPathSegment?.split(":")?.lastOrNull() ?: "ImportedVault"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                !android.os.Environment.isExternalStorageManager()) {
                // 无所有文件访问权限，弹窗建议授予
                showImportPermissionDialog = true
            } else {
                importUseSaf = false
                showImportDialog = true
            }
        }
    }

    Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (subTab == 0) "保险箱" else if (subTab == 1) "云盘" else "设置") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (subTab == 0) {
                            IconButton(onClick = { folderPicker.launch(null) }) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "导入保险箱")
                            }
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = subTab == 0,
                        onClick = { subTab = 0 },
                        icon = { Icon(if (subTab == 0) Icons.Filled.Lock else Icons.Outlined.Lock, contentDescription = "保险箱") },
                        label = { Text("保险箱") }
                    )
                    NavigationBarItem(
                        selected = subTab == 1,
                        onClick = { subTab = 1 },
                        icon = { Icon(if (subTab == 1) Icons.Filled.Cloud else Icons.Outlined.Cloud, contentDescription = "云盘") },
                        label = { Text("云盘") }
                    )
                    NavigationBarItem(
                        selected = subTab == 2,
                        onClick = { subTab = 2 },
                        icon = { Icon(if (subTab == 2) Icons.Filled.Settings else Icons.Outlined.Settings, contentDescription = "设置") },
                        label = { Text("设置") }
                    )
                }
            },
            floatingActionButton = {
                if (subTab == 0) {
                    FloatingActionButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加")
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (subTab) {
                    0 -> VaultsListTab(vaultService = vaultService, settings = settings, onNavigate = onNavigate)
                    1 -> CloudTab()
                    2 -> EncryptionSettingsTab(settings = settings)
            }

            if (showMenu) {
                AlertDialog(
                    onDismissRequest = { showMenu = false },
                    title = { Text("添加或导入") },
                    text = {
                        Column {
                            ListItem(
                                headlineContent = { Text("添加保险箱") },
                                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    showMenu = false
                                    onNavigate(Screen.VaultCreate)
                                }
                            )
                            ListItem(
                                headlineContent = { Text("导入保险箱") },
                                leadingContent = { Icon(Icons.Filled.ArrowUpward, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    showMenu = false
                                    folderPicker.launch(null)
                                }
                            )
                        }
                    },
                    confirmButton = {}
                )
            }

            // 导入权限建议弹窗
            if (showImportPermissionDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showImportPermissionDialog = false
                        importUseSaf = true
                        showImportDialog = true
                    },
                    icon = {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    title = { Text("建议授予所有文件访问权限") },
                    text = {
                        Text("授予「所有文件访问」权限后，导入速度更快且兼容性更好。\n\n如不授予，将使用 SAF 模式导入（功能相同但速度较慢）。")
                    },
                    confirmButton = {
                        Button(onClick = {
                            showImportPermissionDialog = false
                            try {
                                val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                } else {
                                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                try {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    encryptionError = Exception("无法跳转权限设置页面，请手动前往系统设置开启「所有文件访问」权限")
                                }
                            }
                        }) {
                            Text("前往设置")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showImportPermissionDialog = false
                            importUseSaf = true
                            showImportDialog = true
                        }) {
                            Text("取消，使用SAF")
                        }
                    }
                )
            }

            if (showImportDialog) {
                AlertDialog(
                    onDismissRequest = { showImportDialog = false },
                    title = { Text("导入保险箱") },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    text = {
                        Column {
                            OutlinedTextField(
                                value = importFolderName,
                                onValueChange = { importFolderName = it },
                                label = { Text("保险箱名称") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            var importPasswordVisible by remember { mutableStateOf(false) }
                            OutlinedTextField(
                                value = importPassword,
                                onValueChange = { importPassword = it },
                                label = { Text("密码") },
                                visualTransformation = if (importPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { importPasswordVisible = !importPasswordVisible }) {
                                        Icon(
                                            imageVector = if (importPasswordVisible) Icons.Filled.Refresh else Icons.Filled.Refresh,
                                            contentDescription = "切换显示"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            val uri = importFolderUri
                            if (uri == null) {
                                encryptionError = Exception("未选择文件夹")
                                return@Button
                            }
                            try {
                                if (importUseSaf) {
                                    vaultService.importVaultWithPasswordSaf(
                                        name = importFolderName,
                                        treeUri = uri,
                                        password = importPassword
                                    )
                                } else {
                                    val absPath = com.whmdg.mczj.tools.AppDataPaths.safUriToAbsolutePath(context, uri)
                                        ?: throw Exception("无法解析文件夹路径，请尝试授予所有文件访问权限后重试")
                                    vaultService.importVaultWithPassword(
                                        name = importFolderName,
                                        vaultPath = absPath,
                                        password = importPassword
                                    )
                                }
                                Toast.makeText(context, "导入成功", Toast.LENGTH_SHORT).show()
                                showImportDialog = false
                            } catch (e: Exception) {
                                val msg = e.message ?: ""
                                // 可恢复：密码错误、名称冲突、路径解析失败
                                val isRecoverable = msg.contains("密码错误")
                                    || msg.contains("已存在")
                                    || msg.contains("无法解析")
                                    || msg.contains("未选择")
                                // 致命：配置损坏/丢失/格式错误/完整性失败
                                val isFatal = !isRecoverable && (
                                    e is java.io.FileNotFoundException
                                    || msg.contains("不存在")
                                    || msg.contains("损坏")
                                    || msg.contains("丢失")
                                    || msg.contains("格式错误")
                                    || msg.contains("完整性校验失败")
                                    || e is java.io.IOError
                                    || e is kotlinx.serialization.SerializationException
                                )
                                if (isFatal) {
                                    fatalError = e
                                } else {
                                    encryptionError = e
                                }
                            }
                        }) {
                            Text("验证并导入")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showImportDialog = false }) {
                            Text("取消")
                        }
                    }
                )
            }

            ErrorDialog(error = encryptionError, onDismiss = { encryptionError = null })
            ErrorDialog(error = fatalError, onDismiss = { fatalError = null }, fatal = true)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultsListTab(
    vaultService: VaultService,
    settings: EncryptionSettings,
    onNavigate: (Screen) -> Unit
) {
    val context = LocalContext.current
    val list = vaultService.vaults
    val glowEnabled = com.whmdg.mczj.tools.ui.theme.LocalIsGlowEnabled.current
    val isDarkMode = LocalIsDarkMode.current

    var activeVaultForMenu by remember { mutableStateOf<VaultRecord?>(null) }
    var activeVaultForDelete by remember { mutableStateOf<VaultRecord?>(null) }

    var showPasswordDialog by remember { mutableStateOf<VaultRecord?>(null) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var alsoDeleteFiles by remember { mutableStateOf(false) }
    var showWarningDialog by remember { mutableStateOf<Pair<VaultRecord, VaultConfig.VerifyResult>?>(null) }
    var vaultListError by remember { mutableStateOf<Throwable?>(null) }

    // ── 保持打开时长（每个保险箱独立） ──
    val lockPrefs = remember { context.getSharedPreferences("vault_lock_prefs", Context.MODE_PRIVATE) }
    var selectedVault by remember { mutableStateOf<VaultRecord?>(null) }
    // 0 = 立即锁定, 5/15/30/60 = 分钟
    var lockDurationMin by remember { mutableStateOf(0) }
    var showTimerPicker by remember { mutableStateOf(false) }

    // 检查保险箱是否仍在保持打开期内（JNI 验证 HMAC + 时间戳）
    fun isVaultUnlocked(vaultId: String): Boolean {
        val durationMs = lockPrefs.getLong("duration_$vaultId", 0L)
        if (durationMs <= 0) return false
        val deadlineCipher = lockPrefs.getString("deadline_$vaultId", null) ?: return false
        val deadlineIv = lockPrefs.getString("deadline_iv_$vaultId", null) ?: return false
        val storedProof = lockPrefs.getString("deadline_proof_$vaultId", null) ?: return false
        return try {
            val cipherBytes = android.util.Base64.decode(deadlineCipher, android.util.Base64.NO_WRAP)
            val ivBytes = android.util.Base64.decode(deadlineIv, android.util.Base64.NO_WRAP)
            val deadlineStr = String(
                com.whmdg.mczj.tools.auth.KeystoreMaster.unwrap(cipherBytes, ivBytes) ?: return false,
                Charsets.UTF_8
            )
            // JNI 验证：HMAC 匹配 + 时间未过期 → 返回非空 proof
            com.whmdg.mczj.tools.auth.NativeAuth.verifyDeadline(deadlineStr, vaultId, storedProof).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    // 写入加密的 deadline + HMAC proof（当前时间 + 保持时长）
    fun writeDeadline(vaultId: String, durationMs: Long) {
        try {
            val deadline = System.currentTimeMillis() + durationMs
            val deadlineStr = deadline.toString()
            val (cipher, iv) = com.whmdg.mczj.tools.auth.KeystoreMaster.wrap(
                deadlineStr.toByteArray(Charsets.UTF_8)
            )
            // JNI 计算 HMAC proof
            val proof = com.whmdg.mczj.tools.auth.NativeAuth.computeDeadlineHmac(deadlineStr, vaultId)
            lockPrefs.edit()
                .putString("deadline_$vaultId", android.util.Base64.encodeToString(cipher, android.util.Base64.NO_WRAP))
                .putString("deadline_iv_$vaultId", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
                .putString("deadline_proof_$vaultId", proof)
                .commit()
        } catch (_: Exception) {}
    }

    // 清除 deadline
    fun clearDeadline(vaultId: String) {
        lockPrefs.edit()
            .remove("deadline_$vaultId")
            .remove("deadline_iv_$vaultId")
            .remove("deadline_proof_$vaultId")
            .remove("cached_pwd_$vaultId")
            .remove("cached_iv_$vaultId")
            .commit()
    }

    fun openVault(vault: VaultRecord, pwd: String) {
        if (pwd.isEmpty()) {
            vaultListError = Exception("密码不能为空")
            return
        }
        try {
            val session = vaultService.open(vault.id, pwd)
            if (settings.enableTeeQuickUnlock) {
                com.whmdg.mczj.tools.security.TeeManager.encryptPassword(context, vault.id, pwd)
            }
            // 如果设置了保持时长，缓存密码 + 写入 deadline
            val durationMs = lockPrefs.getLong("duration_${vault.id}", 0L)
            if (durationMs > 0) {
                try {
                    val (cipher, iv) = com.whmdg.mczj.tools.auth.KeystoreMaster.wrap(pwd.toByteArray(Charsets.UTF_8))
                    lockPrefs.edit()
                        .putString("cached_pwd_${vault.id}", android.util.Base64.encodeToString(cipher, android.util.Base64.NO_WRAP))
                        .putString("cached_iv_${vault.id}", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
                        .apply()
                    // 立即写入加密 deadline（防止被杀时丢失）
                    writeDeadline(vault.id.toString(), durationMs)
                } catch (_: Exception) {}
            }
            onNavigate(Screen.VaultOpen(session))
        } catch (e: Exception) {
            vaultListError = e
        }
    }

    if (list.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(16.dp))
                Text("还没有任何保险箱", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("点击右下角 + 按钮新建第一个保险箱", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(bottom = if (selectedVault != null) 72.dp else 0.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(list) { vault ->
                // ── 保险箱卡片（参考 encryption_card_reference.jsx 设计风格） ──
                // 外层 padding 给光晕扩散留空间（仅开启光晕时需要）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 16.dp,
                            vertical = if (glowEnabled) 14.dp else 6.dp
                        )
                ) {
                    // 卡片主体 + 光晕效果
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (glowEnabled) {
                                    Modifier.glowEffect(
                                        glowColor = Color(0xFF00C8FF),
                                        glowRadius = 16.dp,
                                        cornerRadius = 20.dp
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .drawBehind {
                                // 青色边框（用 drawRoundRect 模拟，兼容圆角）
                                drawRoundRect(
                                    color = Color(0x8C00D2FF),
                                    cornerRadius = CornerRadius(20.dp.toPx()),
                                    style = Stroke(width = 1.5.dp.toPx())
                                )
                                // 外晕（仅开启光晕时显示）
                                if (glowEnabled) {
                                    drawRoundRect(
                                        color = Color(0x1F008CC8),
                                        cornerRadius = CornerRadius(21.5.dp.toPx()),
                                        style = Stroke(width = 3.dp.toPx())
                                    )
                                }
                            }
                            .combinedClickable(
                                onClick = {
                                    // 选中保险箱，显示底部栏
                                    if (selectedVault?.id == vault.id) {
                                        selectedVault = null
                                        showTimerPicker = false
                                    } else {
                                        selectedVault = vault
                                        lockDurationMin = (lockPrefs.getLong("duration_${vault.id}", 0L) / 60000).toInt()
                                        showTimerPicker = false
                                    }
                                },
                                onLongClick = {
                                    activeVaultForMenu = vault
                                }
                            ),
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Transparent,
                        shadowElevation = 4.dp
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    Brush.linearGradient(
                                        colors = if (isDarkMode) {
                                            listOf(
                                                Color(0xFF111827),
                                                Color(0xFF0D1525),
                                                Color(0xFF0A1020)
                                            )
                                        } else {
                                            listOf(
                                                Color(0xFFE0F7FA),
                                                Color(0xFFE8F5E9),
                                                Color(0xFFF5F5F5)
                                            )
                                        }
                                    )
                                )
                        ) {
                            Column(modifier = Modifier.padding(24.dp)) {
                                // ── 头部：图标 + 名称 ──
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    // 图标容器
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .drawBehind {
                                                drawRoundRect(
                                                    brush = Brush.linearGradient(
                                                        colors = if (isDarkMode) {
                                                            listOf(Color(0xFF0E2A40), Color(0xFF091825))
                                                        } else {
                                                            listOf(Color(0xFFB2EBF2), Color(0xFF80DEEA))
                                                        }
                                                    ),
                                                    cornerRadius = CornerRadius(13.dp.toPx())
                                                )
                                                drawRoundRect(
                                                    color = if (isDarkMode) Color(0x4000C8FF) else Color(0x4000BCD4),
                                                    cornerRadius = CornerRadius(13.dp.toPx()),
                                                    style = Stroke(width = 1.dp.toPx())
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = if (isDarkMode) Color(0xFF38D4F5) else Color(0xFF00838F),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "加密保险箱",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            letterSpacing = 0.12.em,
                                            color = if (isDarkMode) Color(0x8C00C8FF) else Color(0x8C00838F)
                                        )
                                        Text(
                                            text = vault.name,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isDarkMode) Color(0xFFE8F4FF) else Color(0xFF1E293B),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(18.dp))

                                // ── 分隔线（渐变） ──
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(
                                                    Color(0x3300B4E6),
                                                    Color(0x0D00B4E6),
                                                    Color.Transparent
                                                )
                                            )
                                        )
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                // ── 信息行 ──
                                fun formatIsoTime(iso: String?): String? {
                                    if (iso == null) return null
                                    return try {
                                        val sdfIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                                            timeZone = TimeZone.getTimeZone("UTC")
                                        }
                                        val sdfOut = SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.CHINA)
                                        sdfOut.format(sdfIn.parse(iso)!!)
                                    } catch (_: Exception) { null }
                                }

                                val pathDisplay = vault.relativePath.let { path ->
                                    if (path.startsWith("content://")) "SAF 模式" else path
                                }

                                // 路径
                                VaultInfoRow("存储路径", pathDisplay)
                                Spacer(modifier = Modifier.height(10.dp))
                                // 大小占位
                                VaultInfoRow("存储用量", if (vault.storageSize > 0) com.whmdg.mczj.tools.util.FormatUtils.formatBytes(vault.storageSize) else "未统计")
                                Spacer(modifier = Modifier.height(10.dp))
                                // 最后更改时间
                                VaultInfoRow("最后更改时间", formatIsoTime(vault.lastModifiedAt) ?: "未知(Null)")
                                Spacer(modifier = Modifier.height(10.dp))
                                // 最后打开时间
                                VaultInfoRow("最后打开时间", formatIsoTime(vault.lastOpenedAt) ?: "未知(Null)")
                            }
                        }
                    }
                }
            }
        }

        } // end Box
    }

    // ── 保险箱打开弹窗（居中对话框） ──
    selectedVault?.let { vault ->
        val durationLabel = when (lockDurationMin) {
            0 -> "立即锁定"
            5 -> "5 分钟"
            15 -> "15 分钟"
            30 -> "30 分钟"
            60 -> "1 小时"
            else -> "${lockDurationMin} 分钟"
        }
        AlertDialog(
            onDismissRequest = { selectedVault = null },
            title = { Text("打开「${vault.name}」") },
            text = {
                Column {
                    // 时长选择器
                    Text("保持打开时长", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        onClick = { showTimerPicker = !showTimerPicker },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Timer, contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(durationLabel, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            Icon(
                                if (showTimerPicker) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null, modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    // 时长选项展开列表
                    AnimatedVisibility(
                        visible = showTimerPicker,
                        enter = expandVertically(
                            animationSpec = tween(durationMillis = 200, easing = LinearEasing)
                        ) + fadeIn(
                            animationSpec = tween(durationMillis = 200, easing = LinearEasing)
                        ),
                        exit = shrinkVertically(
                            animationSpec = tween(durationMillis = 200, easing = LinearEasing)
                        ) + fadeOut(
                            animationSpec = tween(durationMillis = 200, easing = LinearEasing)
                        )
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            listOf(0 to "立即锁定", 5 to "5 分钟", 15 to "15 分钟", 30 to "30 分钟", 60 to "1 小时")
                                .forEach { (min, label) ->
                                    val isSelected = lockDurationMin == min
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            lockDurationMin = min
                                            showTimerPicker = false
                                            lockPrefs.edit().putLong("duration_${vault.id}", min * 60000L).apply()
                                            if (min == 0) clearDeadline(vault.id.toString())
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isSelected) {
                                                Icon(Icons.Default.Check, contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary)
                                                Spacer(modifier = Modifier.width(6.dp))
                                            }
                                            Text(label, style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val v = selectedVault ?: return@Button
                    selectedVault = null
                    showTimerPicker = false

                    // 检查是否在保持打开期内（deadline 未过期）
                    if (isVaultUnlocked(v.id.toString())) {
                        try {
                            val cachedCipher = lockPrefs.getString("cached_pwd_${v.id}", null)
                            val cachedIv = lockPrefs.getString("cached_iv_${v.id}", null)
                            val pwd = if (cachedCipher != null && cachedIv != null) {
                                val cipherBytes = android.util.Base64.decode(cachedCipher, android.util.Base64.NO_WRAP)
                                val ivBytes = android.util.Base64.decode(cachedIv, android.util.Base64.NO_WRAP)
                                String(com.whmdg.mczj.tools.auth.KeystoreMaster.unwrap(cipherBytes, ivBytes)!!, Charsets.UTF_8)
                            } else throw Exception("缓存密码丢失")
                            val session = vaultService.open(v.id, pwd)
                            onNavigate(Screen.VaultOpen(session))
                        } catch (e: Exception) {
                            clearDeadline(v.id.toString())
                            showPasswordDialog = v
                        }
                    } else {
                        clearDeadline(v.id.toString())
                        val vaultDir = File(v.relativePath)
                        val verifyResult = try {
                            VaultConfig.verifyAllCopies(context, vaultDir)
                        } catch (e: Exception) {
                            VaultConfig.VerifyResult(null, true)
                        }
                        if (verifyResult.isTampered) {
                            showWarningDialog = Pair(v, verifyResult)
                        } else if (settings.enableTeeQuickUnlock &&
                            com.whmdg.mczj.tools.security.TeeManager.isVaultPasswordSaved(context, v.id)) {
                            val cipher = com.whmdg.mczj.tools.security.TeeManager.getDecryptCipher(context, v.id)
                            if (cipher != null) {
                                val activity = context as android.app.Activity
                                val crypto = android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher as javax.crypto.Cipher)
                                com.whmdg.mczj.tools.security.TeeManager.showBiometricPrompt(
                                    activity = activity,
                                    cryptoObject = crypto,
                                    title = "快速解锁「${v.name}」",
                                    description = "请验证指纹以安全解锁保险箱",
                                    onSuccess = { result ->
                                        val authenticatedCipher = result.cryptoObject!!.cipher!!
                                        val decrypted = com.whmdg.mczj.tools.security.TeeManager.decryptPassword(context, v.id, authenticatedCipher)
                                        if (!decrypted.isNullOrEmpty()) {
                                            openVault(v, decrypted)
                                        } else {
                                            Toast.makeText(context, "指纹密匙读取失败，请手动解锁", Toast.LENGTH_SHORT).show()
                                            showPasswordDialog = v
                                        }
                                    },
                                    onFailure = { err ->
                                        if (err != "用户取消") {
                                            Toast.makeText(context, "快速解锁失败: $err", Toast.LENGTH_SHORT).show()
                                        }
                                        showPasswordDialog = v
                                    }
                                )
                            } else {
                                Toast.makeText(context, "安全环境发生变化，指纹密钥已失效，请手动解锁以重新绑定", Toast.LENGTH_LONG).show()
                                showPasswordDialog = v
                            }
                        } else {
                            showPasswordDialog = v
                        }
                    }
                }) {
                    Text("打开")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedVault = null; showTimerPicker = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Warnings Dialog
    showWarningDialog?.let { (vault, verify) ->
        AlertDialog(
            onDismissRequest = { showWarningDialog = null },
            title = { Text("配置完整性警告") },
            text = { Text("检测到配置文件被篡改或损坏，可以使用备份文件进行解密，但存在安全问题，是否继续解密？") },
            confirmButton = {
                Button(onClick = {
                    showWarningDialog = null
                    showPasswordDialog = vault
                }) {
                    Text("继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarningDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Password Prompt Dialog
    showPasswordDialog?.let { vault ->
        AlertDialog(
            onDismissRequest = { showPasswordDialog = null },
            title = { Text("打开「${vault.name}」") },
            text = {
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("密码") },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pwd = passwordInput
                        showPasswordDialog = null
                        passwordInput = ""
                        openVault(vault, pwd)
                    },
                    enabled = passwordInput.isNotEmpty()
                ) {
                    Text("打开")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Active Vault Long-click Menu
    activeVaultForMenu?.let { vault ->
        AlertDialog(
            onDismissRequest = { activeVaultForMenu = null },
            title = { Text(vault.name) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("修改密码") },
                        leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        modifier = Modifier.clickable {
                            activeVaultForMenu = null
                            onNavigate(Screen.VaultChangePassword(vault))
                        }
                    )
                    ListItem(
                        headlineContent = { Text("删除保险箱", color = MaterialTheme.colorScheme.error) },
                        leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable {
                            activeVaultForMenu = null
                            activeVaultForDelete = vault
                        }
                    )
                }
            },
            confirmButton = {}
        )
    }

    // Active Vault Delete Dialog
    activeVaultForDelete?.let { vault ->
        AlertDialog(
            onDismissRequest = { activeVaultForDelete = null },
            title = { Text("删除「${vault.name}」?") },
            text = {
                Column {
                    Text("删除后箱内文件可能无法恢复，请谨慎。")
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = alsoDeleteFiles,
                            onCheckedChange = { alsoDeleteFiles = it }
                        )
                        Text("同时清除磁盘文件", modifier = Modifier.clickable { alsoDeleteFiles = !alsoDeleteFiles })
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            vaultService.removeVault(vault.id, alsoDeleteFiles)
                            Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            vaultListError = e
                        }
                        activeVaultForDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (alsoDeleteFiles) "彻底删除" else "从清单删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { activeVaultForDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    ErrorDialog(error = vaultListError, onDismiss = { vaultListError = null })
}

@Composable
fun CloudTab() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(16.dp))
            Text("云盘", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text("（加密文件存储 / 云端同步功能待添加）", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EncryptionSettingsTab(settings: EncryptionSettings) {
    val context = LocalContext.current
    val activity = context as android.app.Activity

    var showNoBiometricDialog by remember { mutableStateOf(false) }
    var showConfirmDisableDialog by remember { mutableStateOf(false) }

    fun triggerEnableTeeQuickUnlock() {
        if (!com.whmdg.mczj.tools.security.TeeManager.hasEnrolledBiometrics(context)) {
            showNoBiometricDialog = true
            return
        }

        com.whmdg.mczj.tools.security.TeeManager.showBiometricPrompt(
            activity = activity,
            cryptoObject = null,
            title = "开启 TEE 快速解锁",
            description = "验证指纹/人脸以安全开启 TEE 快速解锁功能",
            onSuccess = {
                try {
                    com.whmdg.mczj.tools.security.TeeManager.generateRsaKeyPair()
                    settings.setEnableTeeQuickUnlock(true)
                    Toast.makeText(context, "已成功开启 TEE 快速解锁特权模式，首次解锁保险箱时将自动保存机密口令", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "密钥生成失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            },
            onFailure = { err ->
                if (err != "用户取消") {
                    Toast.makeText(context, "指纹验证失败: $err", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    fun triggerDisableTeeQuickUnlock() {
        // 第一步：先验证指纹
        com.whmdg.mczj.tools.security.TeeManager.showBiometricPrompt(
            activity = activity,
            cryptoObject = null,
            title = "验证身份",
            description = "需要验证指纹以确认您的身份，然后才能关闭 TEE 快速解锁",
            onSuccess = {
                // 指纹通过后，弹出警告对话框让用户确认
                showConfirmDisableDialog = true
            },
            onFailure = { err ->
                if (err != "用户取消") {
                    Toast.makeText(context, "指纹验证失败: $err，无法关闭该服务", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    fun executeWipeTeeData() {
        com.whmdg.mczj.tools.security.TeeManager.wipeAllTeeData(context)
        settings.setEnableTeeQuickUnlock(false)
        Toast.makeText(context, "已成功关闭快速解锁功能，所有本地 TEE 密码已彻底安全擦除", Toast.LENGTH_LONG).show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SettingsSection(
                title = "加密偏好",
                icon = Icons.Default.Check
            ) {
                CompactSettingsToggle(
                    title = "解密前二次确认",
                    subtitle = "点击解密按钮时弹窗确认，避免误操作",
                    icon = Icons.Default.Check,
                    checked = settings.confirmBeforeDecrypt,
                    onCheckedChange = { settings.setConfirmBeforeDecrypt(it) }
                )
            }

            SettingsSection(
                title = "安全增强",
                icon = Icons.Default.Lock
            ) {
                CompactSettingsToggle(
                    title = "启用 TEE 快速解锁",
                    subtitle = "生物认证即可代替每次输入密码，解锁 token 存放于 Android Keystore (TEE)",
                    icon = Icons.Default.Lock,
                    checked = settings.enableTeeQuickUnlock,
                    onCheckedChange = { checked ->
                        if (checked) {
                            triggerEnableTeeQuickUnlock()
                        } else {
                            triggerDisableTeeQuickUnlock()
                        }
                    }
                )
            }

            Text(
                text = "以上设置仅对\"加密\"模块生效，会立即写入设备本地 SharedPreferences，下次启动 App 自动恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // 设备未录入指纹警告弹窗
        if (showNoBiometricDialog) {
            AlertDialog(
                onDismissRequest = { showNoBiometricDialog = false },
                title = { Text("无法启用 TEE 快速解锁") },
                text = { Text("系统未检测到录入的指纹或人脸信息，请先前往系统设置 -> 安全/生物识别中录入至少一个指纹后重新开启此功能。") },
                confirmButton = {
                    Button(onClick = { showNoBiometricDialog = false }) {
                        Text("知道了")
                    }
                }
            )
        }

        // 关闭 TEE 确认弹窗
        if (showConfirmDisableDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDisableDialog = false },
                title = { Text("确认关闭 TEE 快速解锁？") },
                text = { Text("警告：关闭该功能后，原先加密保存在 Android 系统安全芯片（TEE）中的所有保险箱自动解锁密码将被永久彻底抹除。确认关闭并抹除所有机密数据吗？") },
                confirmButton = {
                    Button(
                        onClick = {
                            showConfirmDisableDialog = false
                            executeWipeTeeData()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("确定擦除并关闭", color = androidx.compose.ui.graphics.Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmDisableDialog = false }) {
                        Text("取消")
                    }
                }
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

