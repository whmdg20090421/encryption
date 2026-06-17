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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.whmdg.mczj.tools.ui.components.GlowCard
import com.whmdg.mczj.tools.ui.components.GlowInfoRow
import com.whmdg.mczj.tools.ui.components.GlowSection

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
                    WifiNetworkCard(network = network)
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

    // ── WiFi 密码记录界面 ──
    if (showPasswordScreen) {
        WifiPasswordScreen(onBack = { showPasswordScreen = false })
    }
}

/** WiFi 网络卡片 */
@Composable
private fun WifiNetworkCard(network: WifiNetworkInfo) {
    val signalLevel = network.signalLevel
    val sigColor = signalColor(signalLevel)
    val sigPercent = (signalLevel * 25)  // 0-4 → 0-100%

    GlowCard {
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
    val networkId: Int,
    val ssid: String,
    val bssid: String,
    val password: String,    // preSharedKey，脱敏时为空
    val frequency: Int,
    val signalStrength: Int,
    val flags: String,        // 如 [WPA2-PSK-CCMP][ESS]
    val status: String        // CURRENT / DISABLED / ""
)

/** 执行 root 命令获取已保存的 WiFi 网络 */
private fun loadSavedWifiNetworks(context: Context): List<WifiPasswordEntry> {
    if (!com.whmdg.mczj.tools.security.SpecialPermissionVerifier.isRootAvailable()) {
        throw SecurityException("需要 Root 权限才能查看已保存的 WiFi 密码")
    }

    val (stdout, stderr, exitCode) =
        com.whmdg.mczj.tools.security.SpecialPermissionVerifier.executeRootCommandFull(
            "cmd wifi list-networks"
        )

    if (exitCode != 0) {
        throw Exception("命令执行失败 (exit=$exitCode): $stderr")
    }

    return parseNetworksOutput(stdout)
}

/** 解析 cmd wifi list-networks 输出 */
private fun parseNetworksOutput(output: String): List<WifiPasswordEntry> {
    val lines = output.lines().filter { it.isNotBlank() }
    if (lines.size < 2) return emptyList()

    // 跳过表头行，解析数据行
    val entries = mutableListOf<WifiPasswordEntry>()
    for (i in 1 until lines.size) {
        val line = lines[i]
        try {
            // 按多个空格分割，但保留 [...] flags 整体
            // 格式示例:
            //   0  "MyWiFi"  AA:BB:CC:DD:EE:FF  2437  -55  [WPA2-PSK-CCMP][ESS]
            //   1  "OpenNet"  11:22:33:44:55:66  5180  -70  [ESS]
            val networkId = line.substringBefore(' ').trim().toIntOrNull() ?: continue

            // 提取 SSID（引号内）
            val ssidMatch = Regex("\"([^\"]*)\"").find(line) ?: continue
            val ssid = ssidMatch.groupValues[1]
            val afterSsid = line.substring(ssidMatch.range.last + 1).trim()

            // 剩余部分按空格分割
            val parts = afterSsid.split(Regex("\\s+"))
            if (parts.size < 3) continue

            val bssid = parts[0]
            val frequency = parts[1].toIntOrNull() ?: 0
            val signalStrength = parts[2].toIntOrNull() ?: 0

            // flags 和 status 在最后面
            val flags = parts.drop(3).joinToString(" ")
            val status = if (flags.contains("CURRENT")) "CURRENT" else ""

            // 密码：cmd wifi list-networks 不直接显示密码
            // 需要额外命令获取（dumpsys wifi 或 wpa_cli）
            // 先留空，后续可补充
            val password = ""

            entries.add(WifiPasswordEntry(
                networkId = networkId,
                ssid = ssid,
                bssid = bssid,
                password = password,
                frequency = frequency,
                signalStrength = signalStrength,
                flags = flags,
                status = status
            ))
        } catch (_: Exception) {
            // 跳过解析失败的行
            continue
        }
    }

    return entries
}

/** 尝试获取指定网络的密码（通过 dumpsys wifi 解析 preSharedKey） */
private fun tryFetchPasswords(entries: List<WifiPasswordEntry>): List<WifiPasswordEntry> {
    return try {
        val (stdout, _, exitCode) =
            com.whmdg.mczj.tools.security.SpecialPermissionVerifier.executeRootCommandFull(
                "dumpsys wifi"
            )
        if (exitCode != 0) return entries

        // 从 dumpsys wifi 中提取 SSID → preSharedKey 映射
        val ssidToPassword = mutableMapOf<String, String>()
        val blocks = stdout.split("WifiConfiguration:")
        for (block in blocks.drop(1)) {
            val ssidMatch = Regex("SSID:\\s*\"([^\"]*)\"").find(block)
            val pskMatch = Regex("preSharedKey:\\s*\"([^\"]*)\"").find(block)
            if (ssidMatch != null && pskMatch != null) {
                val ssid = ssidMatch.groupValues[1]
                val psk = pskMatch.groupValues[1]
                if (psk.isNotEmpty() && psk != "*") {
                    ssidToPassword[ssid] = psk
                }
            }
        }

        entries.map { entry ->
            val psk = ssidToPassword[entry.ssid]
            if (psk != null) entry.copy(password = psk) else entry
        }
    } catch (_: Exception) {
        entries
    }
}

/** 存储 WiFi 密码记录到 SharedPreferences */
private fun saveWifiPasswords(context: Context, entries: List<WifiPasswordEntry>) {
    val jsonArray = org.json.JSONArray()
    for (e in entries) {
        val obj = org.json.JSONObject().apply {
            put("networkId", e.networkId)
            put("ssid", e.ssid)
            put("bssid", e.bssid)
            put("password", e.password)
            put("frequency", e.frequency)
            put("signalStrength", e.signalStrength)
            put("flags", e.flags)
            put("status", e.status)
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
                networkId = obj.optInt("networkId", 0),
                ssid = obj.optString("ssid", ""),
                bssid = obj.optString("bssid", ""),
                password = obj.optString("password", ""),
                frequency = obj.optInt("frequency", 0),
                signalStrength = obj.optInt("signalStrength", 0),
                flags = obj.optString("flags", ""),
                status = obj.optString("status", "")
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

    // ── 有 Root：加载数据 ──
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(loadStoredWifiPasswords(context)) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // 首次进入时自动刷新
    LaunchedEffect(Unit) {
        if (entries.isEmpty()) {
            isLoading = true
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val networks = loadSavedWifiNetworks(context)
                    tryFetchPasswords(networks)
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

    // 刷新函数
    fun refresh() {
        isLoading = true
        loadError = null
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val networks = loadSavedWifiNetworks(context)
                    tryFetchPasswords(networks)
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
                    IconButton(onClick = { refresh() }, enabled = !isLoading) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
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
                        title = "加载失败",
                        icon = Icons.Default.Warning
                    ) {
                        Text(
                            text = loadError!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) {
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
                                text = "点击右上角刷新按钮加载",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            // 数据列表
            items(entries, key = { "${it.networkId}_${it.bssid}" }) { entry ->
                GlowCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左侧：SSID（BSSID）
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = entry.ssid,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (entry.status == "CURRENT") {
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
                                text = "(${entry.bssid})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        // 右侧：密码明文
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
                }
            }
        }
    }
}
