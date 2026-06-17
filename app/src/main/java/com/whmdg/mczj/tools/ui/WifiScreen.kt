package com.whmdg.mczj.tools.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.whmdg.mczj.tools.ui.components.GlowCard
import com.whmdg.mczj.tools.ui.components.GlowInfoRow
import com.whmdg.mczj.tools.ui.components.GlowSection
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.capture.InterfaceSelector
import com.whmdg.mczj.tools.capture.HandshakeCapture
import com.whmdg.mczj.tools.capture.HandshakeResultDialog

private const val PREFS_NAME = "wifi_disclaimer"
private const val KEY_ACCEPTED = "accepted"

/** WiFi 网络信息 */
data class WifiNetworkInfo(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val signalLevel: Int,   // 0-4
    val frequency: Int,      // MHz
    val band: String,        // "2.4 GHz" / "5 GHz" / "6 GHz"
    val security: String,    // "WPA3" / "WPA2" / "WPA" / "WEP" / "Open"
    val channelWidth: Int,   // MHz
    val capabilities: String // 原始 capabilities 字符串
)

/** 从 ScanResult 解析加密类型 */
private fun parseSecurity(capabilities: String): String {
    return when {
        capabilities.contains("SAE") || capabilities.contains("WPA3") -> "WPA3"
        capabilities.contains("WPA2") -> "WPA2"
        capabilities.contains("WPA") -> "WPA"
        capabilities.contains("WEP") -> "WEP"
        else -> "Open"
    }
}

/** 从频率判断频段 */
private fun parseBand(frequency: Int): String = when {
    frequency in 2400..2500 -> "2.4 GHz"
    frequency in 4900..5900 -> "5 GHz"
    frequency in 5925..7125 -> "6 GHz"
    else -> "${frequency} MHz"
}

/** ScanResult → WifiNetworkInfo */
private fun ScanResult.toNetworkInfo(): WifiNetworkInfo {
    val signalLevel = try {
        WifiManager.calculateSignalLevel(level, 5)
    } catch (_: Exception) { 0 }

    val cw = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) channelWidth else 20
    } catch (_: Exception) { 20 }

    return WifiNetworkInfo(
        ssid = if (SSID.isNullOrEmpty()) "<隐藏网络>" else SSID,
        bssid = BSSID ?: "",
        rssi = level,
        signalLevel = signalLevel,
        frequency = frequency,
        band = parseBand(frequency),
        security = parseSecurity(capabilities),
        channelWidth = cw,
        capabilities = capabilities
    )
}

/** 权限条目：权限名 + 中文描述 */
private data class PermEntry(val permission: String, val label: String)

/** 从权限常量名提取短名（如 android.permission.ACCESS_FINE_LOCATION → ACCESS_FINE_LOCATION） */
private fun permShortName(permission: String): String {
    return permission.substringAfterLast('.')
}

/** 构建需要申请的权限列表（按 API 版本） */
private fun buildRequiredPermissions(): List<PermEntry> = buildList {
    add(PermEntry(Manifest.permission.ACCESS_FINE_LOCATION, "精确位置"))
    add(PermEntry(Manifest.permission.ACCESS_COARSE_LOCATION, "粗略位置"))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(PermEntry(Manifest.permission.BLUETOOTH_SCAN, "蓝牙扫描"))
        add(PermEntry(Manifest.permission.BLUETOOTH_CONNECT, "蓝牙连接"))
    }
    if (Build.VERSION.SDK_INT >= 33) {
        add(PermEntry("android.permission.NEARBY_WIFI_DEVICES", "附近设备"))
    }
}

/** 获取当前设备缺失的权限列表 */
private fun getDeniedPermissions(context: Context): List<PermEntry> {
    return buildRequiredPermissions().filter { entry ->
        ContextCompat.checkSelfPermission(context, entry.permission) != PackageManager.PERMISSION_GRANTED
    }
}

/** 检查所有必要权限是否已授予 */
private fun allPermissionsGranted(context: Context): Boolean {
    return getDeniedPermissions(context).isEmpty()
}

/** 检查位置权限是否已授予（WiFi 扫描核心前提） */
private fun locationPermissionGranted(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}

/** 信号强度图标（统一用 Wifi 图标，通过颜色区分强度） */
@Composable
private fun signalIcon(level: Int) = Icons.Filled.Wifi

/** 信号强度颜色 */
@Composable
private fun signalColor(level: Int) = when {
    level >= 3 -> MaterialTheme.colorScheme.primary
    level >= 2 -> MaterialTheme.colorScheme.tertiary
    level >= 1 -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
    else -> MaterialTheme.colorScheme.error
}

/** 安全类型图标 */
@Composable
private fun securityIcon(security: String) = when (security) {
    "Open" -> Icons.Filled.LockOpen
    else -> Icons.Filled.Lock
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var disclaimerAccepted by remember { mutableStateOf(prefs.getBoolean(KEY_ACCEPTED, false)) }
    var showDisclaimer by remember { mutableStateOf(!disclaimerAccepted) }

    // ── 免责声明 ──
    if (showDisclaimer) {
        DisclaimerDialog(
            onAccept = {
                prefs.edit().putBoolean(KEY_ACCEPTED, true).apply()
                disclaimerAccepted = true
                showDisclaimer = false
            },
            onDismiss = { onBack() }
        )
        return
    }

    // ── 权限状态 ──
    var permissionsGranted by remember { mutableStateOf(allPermissionsGranted(context)) }
    var deniedList by remember { mutableStateOf(getDeniedPermissions(context)) }
    var showPermissionRationale by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = allPermissionsGranted(context)
        deniedList = getDeniedPermissions(context)
        if (!permissionsGranted && deniedList.isNotEmpty()) {
            showPermissionRationale = true
        }
    }

    // ── WiFi 扫描状态 ──
    val wifiManager = remember {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    var isScanning by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf<List<WifiNetworkInfo>>(emptyList()) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var wifiEnabled by remember { mutableStateOf(wifiManager.isWifiEnabled) }
    var showMenu by remember { mutableStateOf(false) }
    var showPasswordScreen by remember { mutableStateOf(false) }
    var showActionDialog by remember { mutableStateOf(false) }
    var showConnectDialog by remember { mutableStateOf(false) }
    var showCrackDialog by remember { mutableStateOf(false) }
    var crackProgress by remember { mutableStateOf("") }
    var crackResult by remember { mutableStateOf<HandshakeCapture.HandshakeData?>(null) }
    var crackError by remember { mutableStateOf<String?>(null) }
    var selectedNetwork by remember { mutableStateOf<WifiNetworkInfo?>(null) }

    // 扫描结果广播接收器
    val scanReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                try {
                    context.unregisterReceiver(this)
                } catch (_: Exception) {}
                isScanning = false
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (success || scanResults.isEmpty()) {
                    val results = wifiManager.scanResults?.map { it.toNetworkInfo() }
                        ?.sortedByDescending { it.signalLevel }
                        ?: emptyList()
                    // 去重：同 SSID 只保留信号最强的
                    scanResults = results
                        .groupBy { it.ssid }
                        .map { (_, group) -> group.maxByOrNull { it.rssi }!! }
                    scanError = null
                }
            }
        }
    }

    // 启动扫描
    fun startScan() {
        if (!wifiEnabled) {
            scanError = "WiFi 未开启，请先开启 WiFi"
            return
        }
        if (!locationPermissionGranted(context)) {
            scanError = "需要位置权限才能扫描 WiFi 网络"
            showPermissionRationale = true
            return
        }
        isScanning = true
        scanError = null
        try {
            context.registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            @Suppress("DEPRECATION")
            val started = wifiManager.startScan()
            if (!started) {
                // startScan 返回 false 时仍可尝试读取上次结果
                val results = wifiManager.scanResults?.map { it.toNetworkInfo() }
                    ?.sortedByDescending { it.signalLevel }
                    ?: emptyList()
                scanResults = results.groupBy { it.ssid }.map { (_, group) -> group.maxByOrNull { it.rssi }!! }
                isScanning = false
                try { context.unregisterReceiver(scanReceiver) } catch (_: Exception) {}
                if (scanResults.isEmpty()) {
                    scanError = "扫描请求被系统限制，请稍后重试（Android 9+ 限制扫描频率）"
                }
            }
        } catch (e: SecurityException) {
            isScanning = false
            scanError = "权限不足：${e.message}"
            try { context.unregisterReceiver(scanReceiver) } catch (_: Exception) {}
        } catch (e: Exception) {
            isScanning = false
            scanError = "扫描失败：${e.message}"
            try { context.unregisterReceiver(scanReceiver) } catch (_: Exception) {}
        }
    }

    // 清理广播接收器
    DisposableEffect(Unit) {
        onDispose {
            try { context.unregisterReceiver(scanReceiver) } catch (_: Exception) {}
        }
    }

    // ── UI ──
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi 扫描") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "菜单")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.fillMaxWidth(0.55f)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Key,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text("WiFi 密码")
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    showPasswordScreen = true
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (permissionsGranted && wifiEnabled) {
                ExtendedFloatingActionButton(
                    onClick = { startScan() },
                    icon = {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                        }
                    },
                    text = { Text(if (isScanning) "扫描中..." else "扫描") }
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            // ── 权限检查卡片（只显示缺失的权限） ──
            if (!permissionsGranted && deniedList.isNotEmpty()) {
                item {
                    GlowSection(
                        title = "缺少权限",
                        icon = Icons.Default.Security
                    ) {
                        Text(
                            text = "以下权限尚未授予，会影响 WiFi 扫描功能：",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        deniedList.forEach { entry ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${entry.label}（${permShortName(entry.permission)}）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val perms = deniedList.map { it.permission }.toTypedArray()
                                permLauncher.launch(perms)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("申请缺失权限")
                        }
                    }
                }
            }

            // ── WiFi 未开启提示 ──
            if (permissionsGranted && !wifiEnabled) {
                item {
                    GlowSection(
                        title = "WiFi",
                        icon = Icons.Default.WifiOff
                    ) {
                        Text(
                            text = "WiFi 当前未开启，需要开启 WiFi 才能扫描周围网络。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("前往 WiFi 设置")
                        }
                    }
                }
            }

            // ── 扫描错误提示 ──
            if (scanError != null) {
                item {
                    GlowSection(
                        title = "提示",
                        icon = Icons.Default.Info
                    ) {
                        Text(
                            text = scanError!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ── 当前连接的网络信息 ──
            if (permissionsGranted && wifiEnabled) {
                val connInfo = try {
                    wifiManager.connectionInfo
                } catch (_: Exception) { null }
                if (connInfo != null && !connInfo.ssid.isNullOrEmpty() && connInfo.ssid != "<unknown ssid>") {
                    item {
                        GlowSection(
                            title = "当前连接",
                            icon = Icons.Default.Wifi
                        ) {
                            GlowInfoRow("SSID", connInfo.ssid.removeSurrounding("\""))
                            Spacer(modifier = Modifier.height(6.dp))
                            GlowInfoRow("BSSID", connInfo.bssid ?: "")
                            Spacer(modifier = Modifier.height(6.dp))
                            GlowInfoRow("信号强度", "${connInfo.rssi} dBm")
                            Spacer(modifier = Modifier.height(6.dp))
                            GlowInfoRow("链路速度", "${connInfo.linkSpeed} Mbps")
                            Spacer(modifier = Modifier.height(6.dp))
                            GlowInfoRow("频率", parseBand(connInfo.frequency))
                            Spacer(modifier = Modifier.height(6.dp))
                            GlowInfoRow("IP 地址", formatIpAddress(connInfo.ipAddress))
                        }
                    }
                }
            }

            // ── 扫描结果 ──
            if (scanResults.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "扫描结果",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${scanResults.size} 个网络",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(scanResults, key = { "${it.ssid}_${it.bssid}" }) { network ->
                    WifiNetworkCard(
                        network = network,
                        onClick = {
                            selectedNetwork = network
                            showActionDialog = true
                        },
                        onNavigateToPasswords = { showPasswordScreen = true }
                    )
                }
            }

            // ── 空状态提示 ──
            if (permissionsGranted && wifiEnabled && scanResults.isEmpty() && !isScanning && scanError == null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Wifi,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "点击右下角按钮开始扫描",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 权限被拒绝后的引导弹窗（只列缺失权限） ──
    if (showPermissionRationale && deniedList.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = { Text("缺少权限") },
            text = {
                Column {
                    Text("以下权限尚未授予：")
                    Spacer(modifier = Modifier.height(8.dp))
                    deniedList.forEach { entry ->
                        Text("- ${entry.label}（${permShortName(entry.permission)}）")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "如已永久拒绝，请前往系统设置手动授权。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionRationale = false
                    val perms = deniedList.map { it.permission }.toTypedArray()
                    permLauncher.launch(perms)
                }) {
                    Text("重新申请")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionRationale = false
                    // 跳转到应用设置页面
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }) {
                    Text("前往设置")
                }
            }
        )
    }

    // ── WiFi 操作选择弹窗（连接 / 破解 / 取消） ──
    if (showActionDialog && selectedNetwork != null) {
        val net = selectedNetwork!!
        // 权限检查
        val hasAdbOrAbove = remember {
            SpecialPermissionVerifier.isAdbEnabled(context) ||
                    SpecialPermissionVerifier.isShizukuAuthorized(context) ||
                    SpecialPermissionVerifier.isRootAvailable()
        }
        val hasRoot = remember {
            SpecialPermissionVerifier.isRootAvailable()
        }

        AlertDialog(
            onDismissRequest = { showActionDialog = false },
            title = { Text(net.ssid) },
            text = {
                Column {
                    Text("安全类型：${net.security}")
                    Text("信号强度：${net.signalLevel * 25}%")
                    Text("频段：${net.band}")
                }
            },
            dismissButton = {
                TextButton(onClick = { showActionDialog = false }) {
                    Text("取消")
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            showActionDialog = false
                            showCrackDialog = true
                            crackProgress = ""
                            crackResult = null
                            crackError = null
                        },
                        enabled = hasRoot
                    ) {
                        Text(
                            text = "破解",
                            color = if (hasRoot)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                    TextButton(
                        onClick = {
                            showActionDialog = false
                            showConnectDialog = true
                        },
                        enabled = hasAdbOrAbove
                    ) {
                        Text(
                            text = "连接",
                            color = if (hasAdbOrAbove)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        )
    }

    // ── WiFi 连接对话框 ──
    if (showConnectDialog && selectedNetwork != null) {
        val net = selectedNetwork!!
        var isConnecting by remember { mutableStateOf(false) }
        var connectError by remember { mutableStateOf<String?>(null) }
        val connectScope = rememberCoroutineScope()

        WifiConnectDialog(
            ssid = net.ssid,
            security = net.security,
            isConnecting = isConnecting,
            connectError = connectError,
            onDismiss = {
                showConnectDialog = false
                connectError = null
            },
            onConnect = { password ->
                isConnecting = true
                connectError = null
                connectScope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            connectToWifi(context, net.ssid, password, net.security)
                        }
                        if (result) {
                            // 连接成功，保存密码
                            if (password.isNotEmpty()) {
                                saveConnectedWifiPassword(context, net.ssid, password, net.security)
                            }
                            showConnectDialog = false
                        } else {
                            connectError = "连接失败，请检查密码是否正确"
                        }
                    } catch (e: Exception) {
                        connectError = "连接失败：${e.message}"
                    } finally {
                        isConnecting = false
                    }
                }
            }
        )
    }

    // ── WiFi 破解进度对话框 ──
    if (showCrackDialog && selectedNetwork != null) {
        val net = selectedNetwork!!
        val crackScope = rememberCoroutineScope()

        if (crackResult != null) {
            // 显示抓取结果
            HandshakeResultDialog(
                data = crackResult!!,
                onDismiss = {
                    showCrackDialog = false
                    crackResult = null
                }
            )
        } else {
            // 显示进度/错误
            AlertDialog(
                onDismissRequest = {
                    if (crackProgress.isEmpty() || crackError != null) {
                        showCrackDialog = false
                        crackError = null
                    }
                },
                title = { Text("WiFi 破解 - ${net.ssid}") },
                text = {
                    Column {
                        if (crackError != null) {
                            Text(
                                text = crackError!!,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (crackProgress.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(crackProgress)
                            }
                        } else {
                            Text("将自动选择空闲网卡，切换监听模式，\n发送认证请求触发四次握手抓包。\n\n此过程需要 Root 权限。")
                        }
                    }
                },
                dismissButton = {
                    if (crackError != null || crackProgress.isEmpty()) {
                        TextButton(onClick = {
                            showCrackDialog = false
                            crackError = null
                        }) {
                            Text("取消")
                        }
                    }
                },
                confirmButton = {
                    if (crackProgress.isEmpty() && crackError == null) {
                        TextButton(onClick = {
                            crackScope.launch {
                                crackProgress = "正在选择空闲网卡..."
                                crackError = null
                                try {
                                    val selectedIface = InterfaceSelector.selectCaptureInterface { iface1, iface2 ->
                                        // 情况C：双网卡均连接，需要用户选择
                                        crackProgress = ""
                                        crackError = "两个网卡均在使用中：\n" +
                                                "${iface1.name} — ${iface1.ssid ?: "未知"}\n" +
                                                "${iface2.name} — ${iface2.ssid ?: "未知"}\n" +
                                                "请先手动断开一个网卡"
                                        return@selectCaptureInterface
                                    }
                                    if (selectedIface == null) {
                                        if (crackError == null) {
                                                crackError = "未找到可用网卡"
                                            }
                                            return@launch
                                        }

                                        // 切换监听模式
                                        crackProgress = "切换 $selectedIface 到监听模式..."
                                        val monitorOk = InterfaceSelector.enableMonitorMode(selectedIface)
                                        if (!monitorOk) {
                                            crackError = "切换监听模式失败"
                                            InterfaceSelector.restoreManagedMode(selectedIface)
                                            return@launch
                                        }

                                        // 抓取握手包
                                        val result = HandshakeCapture.captureHandshake(
                                            context = context,
                                            iface = selectedIface,
                                            targetSsid = net.ssid,
                                            targetBssid = null,
                                            onProgress = { crackProgress = it }
                                        )

                                        // 恢复 managed 模式
                                        InterfaceSelector.restoreManagedMode(selectedIface)

                                        if (result != null) {
                                            crackResult = result
                                        } else {
                                            crackError = "未捕获到有效握手包，请重试"
                                        }
                                    } catch (e: Exception) {
                                        crackError = "破解失败：${e.message}"
                                        try {
                                            // 尝试恢复网卡模式
                                            InterfaceSelector.restoreManagedMode("wlan0")
                                            InterfaceSelector.restoreManagedMode("wlan1")
                                        } catch (_: Exception) {}
                                    }
                                }
                            }) {
                                Text("开始破解")
                            }
                    }
                    if (crackError != null) {
                        TextButton(onClick = {
                            crackError = null
                            crackProgress = ""
                        }) {
                            Text("重试")
                        }
                    }
                }
            )
        }
    }

    // ── WiFi 密码记录界面 ──
    if (showPasswordScreen) {
        WifiPasswordScreen(onBack = { showPasswordScreen = false })
    }
}

/** WiFi 网络卡片 */
@Composable
private fun WifiNetworkCard(
    network: WifiNetworkInfo,
    onClick: () -> Unit = {},
    onNavigateToPasswords: () -> Unit = {}
) {
    val signalLevel = network.signalLevel
    val sigColor = signalColor(signalLevel)
    val sigPercent = (signalLevel * 25)  // 0-4 → 0-100%
    var showMenu by remember { mutableStateOf(false) }

    GlowCard(
        modifier = Modifier.clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── 头部：信号图标 + SSID + 安全类型 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = signalIcon(signalLevel),
                    contentDescription = null,
                    tint = sigColor,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = network.ssid,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = securityIcon(network.security),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (network.security == "Open")
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = network.security,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (network.security == "Open")
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = network.band,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 信号百分比
                Text(
                    text = "${sigPercent}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = sigColor
                )
                // 功能菜单按钮
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "功能菜单",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("已保存的密码") },
                            onClick = {
                                showMenu = false
                                onNavigateToPasswords()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── 信号强度条 ──
            LinearProgressIndicator(
                progress = { sigPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = sigColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── 详细信息 ──
            GlowInfoRow("BSSID", network.bssid)
            Spacer(modifier = Modifier.height(6.dp))
            GlowInfoRow("信号强度", "${network.rssi} dBm")
            Spacer(modifier = Modifier.height(6.dp))
            GlowInfoRow("频率", "${network.frequency} MHz (${network.band})")
            Spacer(modifier = Modifier.height(6.dp))
            GlowInfoRow("信道宽度", "${network.channelWidth} MHz")
        }
    }
}

/** IP 地址格式化 */
private fun formatIpAddress(ip: Int): String {
    return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
}

@Composable
private fun DisclaimerDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text("免责声明")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "本 WiFi 工具模块仅供网络安全测试、设备调试及学术研究等合法用途。使用本模块即表示您确认并同意以下条款：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "1. 您保证仅在自有网络或已获得明确授权的网络环境中使用本模块的各项功能。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "2. 您理解并同意，任何在未经授权的公共网络、他人私有网络或其他非法场景中使用本模块特定功能的行为，均可能违反《中华人民共和国网络安全法》《中华人民共和国刑法》等相关法律法规，相关法律后果由使用者自行承担。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "3. 开发者不对因使用本模块而产生的任何直接或间接损失承担责任，包括但不限于数据丢失、设备损坏、法律责任等。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "4. 若您不同意上述条款，请点击\"取消\"退出本模块。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "使用本模块即视为您已充分阅读、理解并同意本免责声明的全部内容。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("同意")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/** WiFi 密码记录条目（完整数据，显示 + 存储） */
private data class WifiPasswordEntry(
    // ── 显示字段（UI 直接用）──
    val ssid: String,
    val password: String,       // PreSharedKey，明文或空
    val security: String,       // WPA3-SAE / WPA2-PSK / WPA-PSK / WEP / Open

    // ── 存储字段（不显示，但保存备用）──
    val configKey: String,      // 如 "111"WPA_PSK
    val bssid: String,          // 固定 BSSID 或空
    val hiddenSSID: Boolean,
    val requirePMF: Boolean,
    val allowedKeyMgmtHex: String,   // byte-array hex
    val allowedProtocolsHex: String,
    val status: Int,            // 0=CURRENT, 1=ENABLED, 2=DISABLED
    val shared: Boolean,
    val autoJoinEnabled: Boolean,
    val trusted: Boolean,
    val defaultGwMacAddress: String,
    val randomizedMacAddress: String,
    val creatorUid: Int,
    val creatorName: String,
    val hasEverConnected: Boolean,
    val networkSelectionStatus: String,
    val ipAssignment: String,
    val proxySettings: String
)

/** 连接到WiFi网络 */
private fun connectToWifi(context: Context, ssid: String, password: String, security: String): Boolean {
    // 根据安全类型选择命令参数
    val securityType = when (security) {
        "WPA3-SAE" -> "wpa3"
        "WPA2-PSK", "WPA2" -> "wpa2"
        "WPA-PSK", "WPA" -> "wpa"
        "WEP" -> "wep"
        "Open" -> "open"
        else -> "wpa2"
    }

    // 构建连接命令
    val connectCmd = if (security == "Open") {
        "cmd wifi connect-network \"$ssid\" $securityType"
    } else {
        "cmd wifi connect-network \"$ssid\" $securityType \"$password\""
    }

    // 根据权限选择执行方式
    val result = when {
        SpecialPermissionVerifier.isRootAvailable() -> {
            SpecialPermissionVerifier.executeRootCommandFull(connectCmd)
        }
        SpecialPermissionVerifier.isShizukuAuthorized(context) -> {
            SpecialPermissionVerifier.executeShizukuCommand(connectCmd)
        }
        else -> {
            SpecialPermissionVerifier.executeShellCommandFull(connectCmd)
        }
    }

    val (stdout, stderr, exitCode) = result
    return exitCode == 0
}

/** 保存已连接的WiFi密码 */
private fun saveConnectedWifiPassword(context: Context, ssid: String, password: String, security: String) {
    val entries = loadStoredWifiPasswords(context).toMutableList()

    // 检查是否已存在
    val existingIndex = entries.indexOfFirst { it.ssid == ssid }
    if (existingIndex >= 0) {
        // 更新现有记录
        entries[existingIndex] = entries[existingIndex].copy(
            password = password,
            security = security
        )
    } else {
        // 添加新记录
        entries.add(WifiPasswordEntry(
            ssid = ssid,
            password = password,
            security = security,
            configKey = "",
            bssid = "",
            hiddenSSID = false,
            requirePMF = false,
            allowedKeyMgmtHex = "",
            allowedProtocolsHex = "",
            status = 1,
            shared = true,
            autoJoinEnabled = true,
            trusted = true,
            defaultGwMacAddress = "",
            randomizedMacAddress = "",
            creatorUid = 0,
            creatorName = "",
            hasEverConnected = true,
            networkSelectionStatus = "",
            ipAssignment = "DHCP",
            proxySettings = "NONE"
        ))
    }

    saveWifiPasswords(context, entries)
}

/** 执行 root 命令获取已保存的 WiFi 网络（读取 WifiConfigStore.xml） */
private fun loadSavedWifiNetworks(context: Context): List<WifiPasswordEntry> {
    if (!com.whmdg.mczj.tools.security.SpecialPermissionVerifier.isRootAvailable()) {
        throw SecurityException("需要 Root 权限才能查看已保存的 WiFi 密码")
    }

    val (stdout, stderr, exitCode) =
        com.whmdg.mczj.tools.security.SpecialPermissionVerifier.executeRootCommandFull(
            "cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml"
        )

    if (exitCode != 0 || stdout.isEmpty()) {
        throw Exception("命令执行失败 (exit=$exitCode): $stderr")
    }

    return parseWifiConfigStoreXml(stdout)
}

/** 解析 WifiConfigStore.xml，提取已保存网络 + 密码 */
private fun parseWifiConfigStoreXml(xml: String): List<WifiPasswordEntry> {
    val entries = mutableListOf<WifiPasswordEntry>()

    // 按 <Network> 分割，每块是一个网络配置
    val networkBlocks = xml.split("<Network>").drop(1) // 第一段是 header，跳过

    for (block in networkBlocks) {
        try {
            // ── 提取 WifiConfiguration 节内的字段 ──
            val configBlock = block.substringBefore("</WifiConfiguration>")

            val ssid = extractXmlString(configBlock, "SSID")?.removeSurrounding("\"") ?: continue
            if (ssid.isEmpty()) continue

            val configKey = extractXmlString(configBlock, "ConfigKey") ?: ""
            val rawPsk = extractXmlString(configBlock, "PreSharedKey")?.removeSurrounding("\"") ?: ""
            val password = if (rawPsk.isNotEmpty() && rawPsk != "*") rawPsk else ""
            val allowedKeyMgmtHex = extractXmlByteArray(configBlock, "AllowedKeyMgmt")
            val bssid = extractXmlString(configBlock, "BSSID") ?: ""

            // ── 提取 NetworkStatus 节内的字段 ──
            val statusBlock = block.substringBefore("</NetworkStatus>").substringAfter("<NetworkStatus>", "")
            val hasEverConnected = extractXmlBoolean(statusBlock, "HasEverConnected")
            val selectionStatus = extractXmlString(statusBlock, "SelectionStatus") ?: ""

            // ── 提取 IpConfiguration 节内的字段 ──
            val ipBlock = block.substringBefore("</IpConfiguration>").substringAfter("<IpConfiguration>", "")
            val ipAssignment = extractXmlString(ipBlock, "IpAssignment") ?: "DHCP"
            val proxySettings = extractXmlString(ipBlock, "ProxySettings") ?: "NONE"

            entries.add(WifiPasswordEntry(
                ssid = ssid,
                password = password,
                security = parseSecurityFromXml(configKey, allowedKeyMgmtHex),
                configKey = configKey,
                bssid = bssid,
                hiddenSSID = extractXmlBoolean(configBlock, "HiddenSSID"),
                requirePMF = extractXmlBoolean(configBlock, "RequirePMF"),
                allowedKeyMgmtHex = allowedKeyMgmtHex,
                allowedProtocolsHex = extractXmlByteArray(configBlock, "AllowedProtocols"),
                status = extractXmlInt(configBlock, "Status"),
                shared = extractXmlBoolean(configBlock, "Shared"),
                autoJoinEnabled = extractXmlBoolean(configBlock, "AutoJoinEnabled"),
                trusted = extractXmlBoolean(configBlock, "Trusted"),
                defaultGwMacAddress = extractXmlString(configBlock, "DefaultGwMacAddress") ?: "",
                randomizedMacAddress = extractXmlString(configBlock, "RandomizedMacAddress") ?: "",
                creatorUid = extractXmlInt(configBlock, "CreatorUid"),
                creatorName = extractXmlString(configBlock, "CreatorName") ?: "",
                hasEverConnected = hasEverConnected,
                networkSelectionStatus = selectionStatus,
                ipAssignment = ipAssignment,
                proxySettings = proxySettings
            ))
        } catch (_: Exception) {
            continue
        }
    }

    return entries
}

// ── XML 字段提取工具函数 ──

/** 提取 <string name="X">text</string>，并解码 XML 实体 */
private fun extractXmlString(block: String, name: String): String? {
    val regex = Regex("""<string\s+name="$name">([^<]*)</string>""")
    return regex.find(block)?.groupValues?.get(1)
        ?.replace("&quot;", "\"")
        ?.replace("&amp;", "&")
        ?.replace("&lt;", "<")
        ?.replace("&gt;", ">")
        ?.replace("&apos;", "'")
}

/** 提取 <int name="X" value="Y" /> */
private fun extractXmlInt(block: String, name: String): Int {
    val regex = Regex("""<int\s+name="$name"\s+value="(\d+)"\s*/>""")
    return regex.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

/** 提取 <boolean name="X" value="Y" /> */
private fun extractXmlBoolean(block: String, name: String): Boolean {
    val regex = Regex("""<boolean\s+name="$name"\s+value="(true|false)"\s*/>""")
    return regex.find(block)?.groupValues?.get(1) == "true"
}

/** 提取 <byte-array name="X" num="N">hex</byte-array> */
private fun extractXmlByteArray(block: String, name: String): String {
    val regex = Regex("""<byte-array\s+name="$name"\s+num="(\d+)"[^>]*>([^<]*)</byte-array>""")
    val match = regex.find(block) ?: return ""
    val num = match.groupValues[1].toIntOrNull() ?: 0
    return if (num > 0) match.groupValues[2].trim() else ""
}

/** 从 ConfigKey 和 AllowedKeyMgmt 推断安全类型 */
private fun parseSecurityFromXml(configKey: String, allowedKeyMgmtHex: String): String {
    val upper = configKey.uppercase()
    return when {
        upper.contains("WPA3") || upper.contains("SAE") -> "WPA3-SAE"
        upper.contains("WPA2") && upper.contains("EAP") -> "WPA2-EAP"
        upper.contains("WPA2") -> "WPA2-PSK"
        upper.contains("WPA") && upper.contains("EAP") -> "WPA-EAP"
        upper.contains("WPA") -> "WPA-PSK"
        upper.contains("WEP") -> "WEP"
        upper.contains("NONE") -> "Open"
        // 从 AllowedKeyMgmt 推断: 02=WPA_PSK, 04=WPA_EAP, 08=SAE, 10=OWE
        allowedKeyMgmtHex.contains("08") -> "WPA3-SAE"
        allowedKeyMgmtHex.contains("04") -> "WPA-EAP"
        allowedKeyMgmtHex.contains("02") -> "WPA-PSK"
        else -> "Open"
    }
}

/** 存储 WiFi 密码记录到 SharedPreferences */
private fun saveWifiPasswords(context: Context, entries: List<WifiPasswordEntry>) {
    val jsonArray = org.json.JSONArray()
    for (e in entries) {
        val obj = org.json.JSONObject().apply {
            put("ssid", e.ssid)
            put("password", e.password)
            put("security", e.security)
            put("configKey", e.configKey)
            put("bssid", e.bssid)
            put("hiddenSSID", e.hiddenSSID)
            put("requirePMF", e.requirePMF)
            put("allowedKeyMgmtHex", e.allowedKeyMgmtHex)
            put("allowedProtocolsHex", e.allowedProtocolsHex)
            put("status", e.status)
            put("shared", e.shared)
            put("autoJoinEnabled", e.autoJoinEnabled)
            put("trusted", e.trusted)
            put("defaultGwMacAddress", e.defaultGwMacAddress)
            put("randomizedMacAddress", e.randomizedMacAddress)
            put("creatorUid", e.creatorUid)
            put("creatorName", e.creatorName)
            put("hasEverConnected", e.hasEverConnected)
            put("networkSelectionStatus", e.networkSelectionStatus)
            put("ipAssignment", e.ipAssignment)
            put("proxySettings", e.proxySettings)
        }
        jsonArray.put(obj)
    }
    context.getSharedPreferences("wifi_passwords", Context.MODE_PRIVATE)
        .edit()
        .putString("data", jsonArray.toString())
        .apply()
}

/** 从 SharedPreferences 读取已存储的记录 */
private fun loadStoredWifiPasswords(context: Context): List<WifiPasswordEntry> {
    val json = context.getSharedPreferences("wifi_passwords", Context.MODE_PRIVATE)
        .getString("data", null) ?: return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            WifiPasswordEntry(
                ssid = obj.optString("ssid", ""),
                password = obj.optString("password", ""),
                security = obj.optString("security", "Open"),
                configKey = obj.optString("configKey", ""),
                bssid = obj.optString("bssid", ""),
                hiddenSSID = obj.optBoolean("hiddenSSID", false),
                requirePMF = obj.optBoolean("requirePMF", false),
                allowedKeyMgmtHex = obj.optString("allowedKeyMgmtHex", ""),
                allowedProtocolsHex = obj.optString("allowedProtocolsHex", ""),
                status = obj.optInt("status", 2),
                shared = obj.optBoolean("shared", true),
                autoJoinEnabled = obj.optBoolean("autoJoinEnabled", true),
                trusted = obj.optBoolean("trusted", true),
                defaultGwMacAddress = obj.optString("defaultGwMacAddress", ""),
                randomizedMacAddress = obj.optString("randomizedMacAddress", ""),
                creatorUid = obj.optInt("creatorUid", 0),
                creatorName = obj.optString("creatorName", ""),
                hasEverConnected = obj.optBoolean("hasEverConnected", false),
                networkSelectionStatus = obj.optString("networkSelectionStatus", ""),
                ipAssignment = obj.optString("ipAssignment", "DHCP"),
                proxySettings = obj.optString("proxySettings", "NONE")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WifiPasswordScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val hasRoot = remember {
        com.whmdg.mczj.tools.security.SpecialPermissionVerifier.isRootAvailable()
    }

    // ── 无 Root 提示 ──
    if (!hasRoot) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("WiFi 密码") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "需要 Root 权限",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "查看已保存的 WiFi 密码需要 Root 权限",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    // ── 有 Root ──
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(loadStoredWifiPasswords(context)) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    fun doImport() {
        isLoading = true
        loadError = null
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    loadSavedWifiNetworks(context)
                }
                entries = loaded
                saveWifiPasswords(context, loaded)
                loadError = null
            } catch (e: Exception) {
                loadError = e.message
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi 密码") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showImportDialog = true },
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("导入密码")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // 错误提示
            if (loadError != null) {
                item {
                    GlowSection(
                        title = "导入失败",
                        icon = Icons.Default.Warning
                    ) {
                        Text(
                            text = loadError!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { doImport() }, modifier = Modifier.fillMaxWidth()) {
                            Text("重试")
                        }
                    }
                }
            }

            // 空状态
            if (!isLoading && entries.isEmpty() && loadError == null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Key,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "暂无已保存的 WiFi 密码",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "点击右上角「导入」从系统读取",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            // 数据列表 - 单个GlowCard包裹所有条目
            if (entries.isNotEmpty()) {
                item {
                    GlowCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            entries.forEachIndexed { index, entry ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )
                                }
                                // 第一行：SSID + 密码
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = entry.ssid,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (entry.status == 0) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "当前",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Text(
                                        text = entry.password.ifEmpty { "（无密码）" },
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = if (entry.password.isEmpty())
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        else
                                            MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                // 第二行：BSSID + 安全类型（小字体）
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = entry.bssid.ifEmpty { "未记录" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = entry.security,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (entry.security == "Open")
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 导入确认弹窗 ──
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入 WiFi 密码") },
            text = {
                Column {
                    Text("该功能需要 Root 权限，将进行导入已保存的密码。")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "读取路径：/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("是否确认执行？")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    doImport()
                }) {
                    Text("确认导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/** WiFi 连接密码输入对话框 */
@Composable
private fun WifiConnectDialog(
    ssid: String,
    security: String,
    isConnecting: Boolean = false,
    connectError: String? = null,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isConnecting) onDismiss() },
        title = { Text("连接到 $ssid") },
        text = {
            Column {
                Text("安全类型：$security")
                Spacer(modifier = Modifier.height(16.dp))
                if (security != "Open") {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        enabled = !isConnecting,
                        visualTransformation = if (passwordVisible)
                            VisualTransformation.None
                        else
                            PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("此网络为开放网络，无需密码")
                }

                // 错误提示
                if (connectError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = connectError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // 连接中提示
                if (isConnecting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "正在连接...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConnect(password) },
                enabled = !isConnecting && (security == "Open" || password.isNotEmpty())
            ) {
                Text(if (isConnecting) "连接中..." else "连接")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isConnecting
            ) {
                Text("取消")
            }
        }
    )
}
