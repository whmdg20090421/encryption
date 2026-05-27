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
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.encryption.data.StorageLocation
import com.whmdg.mczj.tools.encryption.data.VaultConfig
import com.whmdg.mczj.tools.encryption.data.VaultRecord
import com.whmdg.mczj.tools.encryption.services.VaultService
import com.whmdg.mczj.tools.encryption.services.VaultSession
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class Screen {
    object Dashboard : Screen()
    object Settings : Screen()
    object Security : Screen()
    object PermissionSettings : Screen()
    object SpecialPermissions : Screen()
    object AppPermissions : Screen()
    object FileManager : Screen()
    object ThemeSettings : Screen()
    object EncryptionHome : Screen()
    object VaultCreate : Screen()
    data class VaultOpen(val session: VaultSession) : Screen()
    data class VaultChangePassword(val vault: VaultRecord) : Screen()
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
                onBack = { navigateBack() }
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
        is Screen.FileManager -> {
            FileManagerScreen(
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectedTab == 0) {
                HomeTab(onNavigate = onNavigate)
            } else {
                SettingsTab(onNavigate = onNavigate)
            }
        }
    }
}

@Composable
fun HomeTab(onNavigate: (Screen) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SettingsSection(
            title = "工具",
            icon = Icons.Default.Build
        ) {
            CompactSettingsItem(
                title = "加密",
                subtitle = "常用加密 / 解密工具",
                icon = Icons.Default.Lock,
                onClick = { onNavigate(Screen.EncryptionHome) }
            )
            CompactSettingsItem(
                title = "文件管理器",
                subtitle = "双面板文件浏览工具",
                icon = Icons.Default.Folder,
                onClick = { onNavigate(Screen.FileManager) }
            )
            CompactSettingsItem(
                title = "应用权限管理",
                subtitle = "查看和管理应用权限",
                icon = Icons.Default.Security,
                onClick = { onNavigate(Screen.AppPermissions) }
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
    var importFolderUri by remember { mutableStateOf<String?>(null) }
    var importFolderName by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var encryptionError by remember { mutableStateOf<Throwable?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // 在真正的应用中需要解析 Uri，但这里为了鲁棒，允许用户选择并且使用默认转换
            importFolderUri = it.path
            importFolderName = it.lastPathSegment?.split(":")?.lastOrNull() ?: "ImportedVault"
            showImportDialog = true
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
                                leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    showMenu = false
                                    onNavigate(Screen.VaultCreate)
                                }
                            )
                            ListItem(
                                headlineContent = { Text("导入保险箱") },
                                leadingContent = { Icon(Icons.Default.ArrowUpward, contentDescription = null) },
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

            if (showImportDialog) {
                AlertDialog(
                    onDismissRequest = { showImportDialog = false },
                    title = { Text("导入保险箱") },
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
                            val path = importFolderUri ?: ""
                            try {
                                vaultService.importVaultWithPassword(
                                    name = importFolderName,
                                    vaultPath = path,
                                    password = importPassword
                                )
                                Toast.makeText(context, "导入成功", Toast.LENGTH_SHORT).show()
                                showImportDialog = false
                            } catch (e: Exception) {
                                encryptionError = e
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

    var activeVaultForMenu by remember { mutableStateOf<VaultRecord?>(null) }
    var activeVaultForDelete by remember { mutableStateOf<VaultRecord?>(null) }

    var showPasswordDialog by remember { mutableStateOf<VaultRecord?>(null) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var alsoDeleteFiles by remember { mutableStateOf(false) }
    var showWarningDialog by remember { mutableStateOf<Pair<VaultRecord, VaultConfig.VerifyResult>?>(null) }
    var vaultListError by remember { mutableStateOf<Throwable?>(null) }

    fun openVault(vault: VaultRecord, pwd: String) {
        try {
            val session = vaultService.open(vault.id, pwd)
            if (settings.enableTeeQuickUnlock) {
                com.whmdg.mczj.tools.security.TeeManager.encryptPassword(context, vault.id, pwd)
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
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(list) { vault ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                // 第一步：三副本校验
                                val vaultDir = File(vault.relativePath)
                                val verifyResult = try {
                                    VaultConfig.verifyAllCopies(context, vaultDir)
                                } catch (e: Exception) {
                                    VaultConfig.VerifyResult(null, true)
                                }

                                if (verifyResult.isTampered) {
                                    showWarningDialog = Pair(vault, verifyResult)
                                } else {
                                    if (settings.enableTeeQuickUnlock && 
                                        com.whmdg.mczj.tools.security.TeeManager.isVaultPasswordSaved(context, vault.id)) {
                                        
                                        val cipher = com.whmdg.mczj.tools.security.TeeManager.getDecryptCipher(context, vault.id)
                                        if (cipher != null) {
                                            val activity = context as android.app.Activity
                                            val crypto = android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher as javax.crypto.Cipher)
                                            com.whmdg.mczj.tools.security.TeeManager.showBiometricPrompt(
                                                activity = activity,
                                                cryptoObject = crypto,
                                                title = "快速解锁「${vault.name}」",
                                                description = "请验证指纹以安全解锁保险箱",
                                                onSuccess = { result ->
                                                    val authenticatedCipher = result.cryptoObject!!.cipher!!
                                                    val decrypted = com.whmdg.mczj.tools.security.TeeManager.decryptPassword(context, vault.id, authenticatedCipher)
                                                    if (!decrypted.isNullOrEmpty()) {
                                                        openVault(vault, decrypted)
                                                    } else {
                                                        Toast.makeText(context, "指纹密匙读取失败，请手动解锁", Toast.LENGTH_SHORT).show()
                                                        showPasswordDialog = vault
                                                    }
                                                },
                                                onFailure = { err ->
                                                    if (err != "用户取消") {
                                                        Toast.makeText(context, "快速解锁失败: $err", Toast.LENGTH_SHORT).show()
                                                    }
                                                    showPasswordDialog = vault
                                                }
                                            )
                                        } else {
                                            Toast.makeText(context, "安全环境发生变化，指纹密钥已失效，请手动解锁以重新绑定", Toast.LENGTH_LONG).show()
                                            showPasswordDialog = vault
                                        }
                                    } else {
                                        showPasswordDialog = vault
                                    }
                                }
                            },
                            onLongClick = {
                                activeVaultForMenu = vault
                            }
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = vault.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (vault.location == StorageLocation.INTERNAL) "内部存储" else "外部存储",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
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
                Button(onClick = {
                    val pwd = passwordInput
                    showPasswordDialog = null
                    passwordInput = ""
                    openVault(vault, pwd)
                }) {
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
        }
    }
}

@Composable
fun SettingsTab(onNavigate: (Screen) -> Unit) {
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
            CompactSettingsItem(
                title = "安全",
                subtitle = "权限设置与特殊权限管理",
                icon = Icons.Default.Lock,
                onClick = { onNavigate(Screen.Security) }
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
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 6.dp)
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                content = content
            )
        }
    }
}

@Composable
private fun CompactSettingsItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun CompactSettingsToggle(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onTap),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "进入",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

