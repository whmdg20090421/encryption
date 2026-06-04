package com.whmdg.mczj.tools.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.ui.components.GlowCard
import com.whmdg.mczj.tools.ui.theme.LocalIsDarkMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: android.graphics.drawable.Drawable?,
    val installTime: Long,
    val isSystemApp: Boolean
)

data class PermissionInfo(
    val name: String,
    val description: String,
    val granted: Boolean,
    val dangerous: Boolean,
    val group: String,
    val rawName: String,
    val appOpsMode: String = "" // AppOps 模式下的当前状态：allow/ignore/deny/default/foreground
)

data class PermOpMapping(
    val opName: String,
    val displayName: String,
    val description: String
)

val permToOpMap = mapOf(
    // ── 标准 Android 权限 ──
    "android.permission.CAMERA" to PermOpMapping("android:camera", "相机", "控制应用能否使用摄像头拍照或录像"),
    "android.permission.READ_CONTACTS" to PermOpMapping("android:read_contacts", "读取联系人", "控制应用能否读取通讯录中的联系人信息"),
    "android.permission.WRITE_CONTACTS" to PermOpMapping("android:write_contacts", "写入联系人", "控制应用能否新增、修改或删除联系人"),
    "android.permission.GET_ACCOUNTS" to PermOpMapping("android:get_accounts", "获取账户", "控制应用能否获取设备上已登录的账户列表"),
    "android.permission.ACCESS_FINE_LOCATION" to PermOpMapping("android:fine_location", "精确位置", "控制应用能否通过 GPS 获取精确地理位置"),
    "android.permission.ACCESS_COARSE_LOCATION" to PermOpMapping("android:coarse_location", "粗略位置", "控制应用能否通过基站或 Wi-Fi 获取大致位置"),
    "android.permission.ACCESS_BACKGROUND_LOCATION" to PermOpMapping("android:coarse_location", "后台位置", "控制应用能否在后台持续获取位置信息"),
    "android.permission.READ_PHONE_STATE" to PermOpMapping("android:read_phone_state", "读取手机状态", "控制应用能否读取设备标识、通话状态等信息"),
    "android.permission.READ_PHONE_NUMBERS" to PermOpMapping("android:read_phone_numbers", "读取手机号码", "控制应用能否读取本机电话号码"),
    "android.permission.CALL_PHONE" to PermOpMapping("android:call_phone", "拨打电话", "控制应用能否直接拨出电话（无需用户确认）"),
    "android.permission.ANSWER_PHONE_CALLS" to PermOpMapping("android:answer_phone_calls", "接听电话", "控制应用能否代为接听来电"),
    "android.permission.ADD_VOICEMAIL" to PermOpMapping("android:add_voicemail", "添加语音信箱", "控制应用能否添加语音信箱"),
    "android.permission.USE_SIP" to PermOpMapping("android:use_sip", "使用 SIP", "控制应用能否使用 SIP 进行网络通话"),
    "android.permission.ACCEPT_HANDOVER" to PermOpMapping("android:accept_handover", "通话转移", "控制应用能否继续其他应用正在进行的通话"),
    "android.permission.READ_SMS" to PermOpMapping("android:read_sms", "读取短信", "控制应用能否读取收到的短信内容"),
    "android.permission.SEND_SMS" to PermOpMapping("android:send_sms", "发送短信", "控制应用能否发送短信"),
    "android.permission.RECEIVE_SMS" to PermOpMapping("android:receive_sms", "接收短信", "控制应用能否接收短信"),
    "android.permission.RECEIVE_MMS" to PermOpMapping("android:receive_mms", "接收彩信", "控制应用能否接收彩信"),
    "android.permission.RECEIVE_WAP_PUSH" to PermOpMapping("android:receive_wap_push", "接收 WAP 推送", "控制应用能否接收 WAP 推送消息"),
    "android.permission.RECORD_AUDIO" to PermOpMapping("android:record_audio", "录音", "控制应用能否使用麦克风录制音频"),
    "android.permission.READ_CALENDAR" to PermOpMapping("android:read_calendar", "读取日历", "控制应用能否读取日历事件和提醒"),
    "android.permission.WRITE_CALENDAR" to PermOpMapping("android:write_calendar", "写入日历", "控制应用能否新增、修改或删除日历事件"),
    "android.permission.READ_EXTERNAL_STORAGE" to PermOpMapping("android:read_external_storage", "读取存储", "控制应用能否读取外部存储中的文件"),
    "android.permission.WRITE_EXTERNAL_STORAGE" to PermOpMapping("android:write_external_storage", "写入存储", "控制应用能否在外部存储中创建或修改文件"),
    "android.permission.MANAGE_EXTERNAL_STORAGE" to PermOpMapping("android:manage_external_storage", "管理存储", "控制应用能否访问和管理外部存储中的所有文件"),
    "android.permission.READ_MEDIA_IMAGES" to PermOpMapping("android:read_media_images", "读取图片", "控制应用能否访问设备上的图片文件"),
    "android.permission.READ_MEDIA_VIDEO" to PermOpMapping("android:read_media_video", "读取视频", "控制应用能否访问设备上的视频文件"),
    "android.permission.READ_MEDIA_AUDIO" to PermOpMapping("android:read_media_audio", "读取音频", "控制应用能否访问设备上的音频文件"),
    "android.permission.BODY_SENSORS" to PermOpMapping("android:body_sensors", "身体传感器", "控制应用能否读取心率、血氧等身体传感器数据"),
    "android.permission.BODY_SENSORS_BACKGROUND" to PermOpMapping("android:body_sensors", "后台身体传感器", "控制应用能否在后台持续读取身体传感器数据"),
    "android.permission.ACTIVITY_RECOGNITION" to PermOpMapping("android:activity_recognition", "活动识别", "控制应用能否检测用户的步行、跑步等运动状态"),
    "android.permission.READ_CALL_LOG" to PermOpMapping("android:read_call_log", "读取通话记录", "控制应用能否读取通话历史记录"),
    "android.permission.WRITE_CALL_LOG" to PermOpMapping("android:write_call_log", "写入通话记录", "控制应用能否修改或删除通话记录"),
    "android.permission.PROCESS_OUTGOING_CALLS" to PermOpMapping("android:process_outgoing_calls", "处理外拨电话", "控制应用能否监视、修改或阻止外拨电话"),
    // ── 系统级权限 ──
    "android.permission.SYSTEM_ALERT_WINDOW" to PermOpMapping("android:system_alert_window", "悬浮窗", "控制应用能否在其他应用上方显示悬浮窗"),
    "android.permission.REQUEST_INSTALL_PACKAGES" to PermOpMapping("android:request_install_packages", "安装应用", "控制应用能否安装其他应用（APK）"),
    "android.permission.REQUEST_DELETE_PACKAGES" to PermOpMapping("android:request_delete_packages", "卸载应用", "控制应用能否请求删除其他应用"),
    "android.permission.PACKAGE_USAGE_STATS" to PermOpMapping("android:package_usage_stats", "使用情况统计", "控制应用能否读取其他应用的使用统计数据"),
    "android.permission.POST_NOTIFICATIONS" to PermOpMapping("android:post_notifications", "发送通知", "控制应用能否向通知栏推送消息"),
    "android.permission.WRITE_SETTINGS" to PermOpMapping("android:write_settings", "修改系统设置", "控制应用能否修改系统设置"),
    "android.permission.NEARBY_WIFI_DEVICES" to PermOpMapping("android:nearby_wifi_devices", "附近 Wi-Fi 设备", "控制应用能否发现和连接附近的 Wi-Fi 设备"),
    "android.permission.BLUETOOTH_CONNECT" to PermOpMapping("android:bluetooth_connect", "蓝牙连接", "控制应用能否连接已配对的蓝牙设备"),
    "android.permission.BLUETOOTH_SCAN" to PermOpMapping("android:bluetooth_scan", "蓝牙扫描", "控制应用能否扫描附近的蓝牙设备"),
    "android.permission.BLUETOOTH_ADVERTISE" to PermOpMapping("android:bluetooth_advertise", "蓝牙广播", "控制应用能否让自身可被蓝牙发现"),
    // ── 厂商特殊权限 ──
    "com.huawei.permission.external_apps.BROADCAST" to PermOpMapping("android:auto_start", "自启动（华为）", "控制应用能否在开机或被杀后自动启动（华为设备）"),
    "com.miui.permission.AUTO_START" to PermOpMapping("android:auto_start", "自启动（小米）", "控制应用能否在开机或被杀后自动启动（小米设备）"),
    "com.oplus.permission.safe.AUTO_START" to PermOpMapping("android:auto_start", "自启动（OPPO/一加）", "控制应用能否在开机或被杀后自动启动（OPPO/一加设备）"),
    "com.vivo.permission.AUTO_START" to PermOpMapping("android:auto_start", "自启动（Vivo）", "控制应用能否在开机或被杀后自动启动（Vivo设备）"),
    "com.miui.securitycenter.permission.SYSTEM_ALERT_WINDOW" to PermOpMapping("android:background_popup", "后台弹窗（小米）", "控制应用能否在后台弹出界面（小米设备）"),
    "com.oplus.permission.safe.BACKGROUND_POPUP" to PermOpMapping("android:background_popup", "后台弹窗（OPPO/一加）", "控制应用能否在后台弹出界面（OPPO/一加设备）"),
    "com.vivo.permission.BACKGROUND_POPUP" to PermOpMapping("android:background_popup", "后台弹窗（Vivo）", "控制应用能否在后台弹出界面（Vivo设备）")
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun AppPermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    var selectedAppPermissions by remember { mutableStateOf<List<PermissionInfo>>(emptyList()) }
    var isPermissionLoading by remember { mutableStateOf(false) }
    var showSystemApps by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showError by remember { mutableStateOf(false) }

    // 当前权限管理模式
    val modePrefs = context.getSharedPreferences(AppDataPaths.PREFS_PERMISSION_MANAGEMENT, Context.MODE_PRIVATE)
    val currentMode by remember { mutableStateOf(modePrefs.getString("mode", "NORMAL") ?: "NORMAL") }
    val isAppOpsMode = currentMode == "APPOPS" || currentMode == "PERMISSION_CONTROLLER"
    val useRootForOps = currentMode == "PERMISSION_CONTROLLER"

    // AppOps 底部弹窗状态
    var showOpsSheet by remember { mutableStateOf(false) }
    var opsSheetPermission by remember { mutableStateOf<PermissionInfo?>(null) }
    val opsSheetState = rememberModalBottomSheetState()

    val groupedPermissions = remember(selectedAppPermissions) { selectedAppPermissions.groupBy { it.group } }

    val groupColors = remember {
        mapOf(
            "CAMERA" to Color(0xFFBA68C8),
            "CONTACTS" to Color(0xFF4DB6AC),
            "LOCATION" to Color(0xFFFFB74D),
            "MICROPHONE" to Color(0xFF4FC3F7),
            "PHONE" to Color(0xFFFF8A65),
            "SENSORS" to Color(0xFF9CCC65),
            "SMS" to Color(0xFFFF8A65),
            "STORAGE" to Color(0xFF7E57C2),
            "CALL_LOG" to Color(0xFFE57373),
            "CALENDAR" to Color(0xFF7986CB),
            "ACTIVITY_RECOGNITION" to Color(0xFF8D6E63),
            "OTHER_GRANTED" to Color(0xFF66BB6A),
            "OTHER_DENIED" to Color(0xFF78909C),
            "undefined" to Color(0xFF9E9E9E)
        )
    }

    val groupIcons = remember {
        mapOf(
            "CAMERA" to Icons.Default.PhotoCamera,
            "CONTACTS" to Icons.Default.Contacts,
            "LOCATION" to Icons.Default.LocationOn,
            "MICROPHONE" to Icons.Default.Mic,
            "PHONE" to Icons.Default.Phone,
            "SENSORS" to Icons.Default.Sensors,
            "SMS" to Icons.Default.Sms,
            "STORAGE" to Icons.Default.Folder,
            "CALL_LOG" to Icons.Default.Call,
            "CALENDAR" to Icons.Default.DateRange,
            "ACTIVITY_RECOGNITION" to Icons.Default.DirectionsRun,
            "OTHER_GRANTED" to Icons.Default.Check,
            "OTHER_DENIED" to Icons.Default.Block,
            "undefined" to Icons.Default.Info
        )
    }

    LaunchedEffect(Unit) {
        isLoading = true
        coroutineScope.launch {
            installedApps = loadInstalledApps(packageManager)
            isLoading = false
        }
    }

    val filteredApps = remember(installedApps, searchQuery, showSystemApps) {
        installedApps
            .filter {
                (searchQuery.isEmpty() || it.name.contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)) &&
                        (showSystemApps || !it.isSystemApp)
            }
            .sortedBy { it.name }
    }

    fun loadAppPermissions(packageName: String) {
        isPermissionLoading = true
        coroutineScope.launch {
            try {
                var perms = getAppPermissions(packageName, context)
                if (isAppOpsMode) {
                    val opsState = readAppOpsState(packageName, context)
                    perms = perms.map { perm ->
                        val opMapping = permToOpMap[perm.rawName]
                        if (opMapping != null) {
                            val opsMode = opsState[opMapping.opName] ?: "default"
                            perm.copy(appOpsMode = opsMode)
                        } else {
                            perm
                        }
                    }
                }
                selectedAppPermissions = perms
            } catch (e: Exception) {
                errorMessage = "获取权限失败: ${e.message}"
                showError = true
            } finally {
                isPermissionLoading = false
            }
        }
    }

    Scaffold { innerPadding ->
        AnimatedContent(
            targetState = selectedApp,
            transitionSpec = {
                if (targetState == null) {
                    slideInHorizontally { -it } + fadeIn() with slideOutHorizontally { it } + fadeOut()
                } else {
                    slideInHorizontally { it } + fadeIn() with slideOutHorizontally { -it } + fadeOut()
                }
            },
            modifier = Modifier.padding(innerPadding)
        ) { targetApp ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (targetApp == null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = onBack) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        modifier = Modifier.weight(1f).height(56.dp),
                                        placeholder = { Text("搜索应用...") },
                                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                        trailingIcon = {
                                            if (searchQuery.isNotEmpty()) {
                                                IconButton(onClick = { searchQuery = "" }) {
                                                    Icon(Icons.Default.Clear, contentDescription = null)
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        shape = RoundedCornerShape(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                            .clickable { showSystemApps = !showSystemApps }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Checkbox(checked = showSystemApps, onCheckedChange = { showSystemApps = it })
                                        Text("系统应用", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }

                        if (isLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("正在加载应用列表...", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else if (filteredApps.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                                    Icon(Icons.Default.SearchOff, contentDescription = null, modifier = Modifier.size(72.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("未找到应用", style = MaterialTheme.typography.titleLarge)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        if (searchQuery.isNotEmpty()) "请尝试其他关键词"
                                        else if (!showSystemApps) "请尝试显示系统应用"
                                        else "没有已安装的应用",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(items = filteredApps, key = { it.packageName }) { app ->
                                    AppItem(app = app) {
                                        selectedApp = app
                                        loadAppPermissions(app.packageName)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { selectedApp = null }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                selectedApp?.icon?.let { appIcon ->
                                    Image(
                                        bitmap = appIcon.toBitmap().asImageBitmap(),
                                        contentDescription = selectedApp?.name,
                                        modifier = Modifier.size(40.dp).clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                            .padding(4.dp)
                                    )
                                } ?: Icon(Icons.Default.Android, contentDescription = selectedApp?.name,
                                    modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(selectedApp?.name ?: "", style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(selectedApp?.packageName ?: "", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                FilledTonalIconButton(
                                    onClick = {
                                        val pkg = selectedApp?.packageName ?: return@FilledTonalIconButton
                                        coroutineScope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                SpecialPermissionVerifier.executeRootCommandFull("pm reset-permissions $pkg")
                                            }
                                            if (result.third == 0) loadAppPermissions(pkg) else {
                                                val stderr = result.second.lowercase()
                                                errorMessage = when {
                                                    stderr.contains("permission denied") ||
                                                    stderr.contains("insufficient") ||
                                                    stderr.contains("not allowed") ->
                                                        "当前应用所获得权限不足，无法进行此操作。"
                                                    else -> "重置失败: ${result.second}"
                                                }
                                                showError = true
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Default.RestartAlt, contentDescription = "重置权限",
                                        modifier = Modifier.size(20.dp))
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = !isPermissionLoading && selectedAppPermissions.isNotEmpty(),
                            enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()
                        ) {
                            val totalPerms = selectedAppPermissions.size
                            val grantedPerms = selectedAppPermissions.count { it.granted }
                            val dangerousPerms = selectedAppPermissions.count { it.dangerous }
                            val isDarkMode = LocalIsDarkMode.current
                            val overviewTitleColor = if (isDarkMode) Color(0xFFE8F4FF) else Color(0xFF1E293B)

                            GlowCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("权限概览", style = MaterialTheme.typography.titleMedium,
                                        color = overviewTitleColor)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        PermissionStat(totalPerms, "总权限", Icons.Default.List, Color(0xFF38D4F5))
                                        PermissionStat(grantedPerms, "已授权", Icons.Default.Check, Color(0xFF4CAF50))
                                        PermissionStat(dangerousPerms, "危险权限", Icons.Default.Warning, Color(0xFFFF9800))
                                    }
                                }
                            }
                        }

                        if (isPermissionLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("正在获取权限信息...", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else if (selectedAppPermissions.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                                    Icon(Icons.Default.Shield, contentDescription = null,
                                        modifier = Modifier.size(96.dp).padding(8.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("无特殊权限", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("该应用未请求任何特殊运行时权限", style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                }
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                                groupedPermissions.forEach { (group, permissions) ->
                                    item {
                                        val groupName = when (group) {
                                            "CAMERA" -> "相机"
                                            "CONTACTS" -> "通讯录"
                                            "LOCATION" -> "位置"
                                            "MICROPHONE" -> "麦克风"
                                            "PHONE" -> "电话"
                                            "SENSORS" -> "传感器"
                                            "SMS" -> "短信"
                                            "STORAGE" -> "存储"
                                            "CALL_LOG" -> "通话记录"
                                            "CALENDAR" -> "日历"
                                            "ACTIVITY_RECOGNITION" -> "活动识别"
                                            "OTHER_GRANTED" -> "其他已授权"
                                            "OTHER_DENIED" -> "其他未授权"
                                            else -> "其他"
                                        }
                                        Surface(
                                            color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
                                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 16.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier.size(36.dp).background(
                                                        groupColors[group]?.copy(alpha = 0.2f) ?: Color.Gray.copy(alpha = 0.2f),
                                                        CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(groupIcons[group] ?: Icons.Default.Extension,
                                                        contentDescription = groupName,
                                                        tint = groupColors[group] ?: Color.Gray,
                                                        modifier = Modifier.size(20.dp))
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column {
                                                    Text(groupName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                    Text("${permissions.size} 项", style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                    itemsIndexed(items = permissions, key = { _, p -> p.rawName }) { _, permission ->
                                        PermissionToggleItem(
                                            permission = permission,
                                            mode = currentMode,
                                            onToggle = {
                                                if (isAppOpsMode) {
                                                    // AppOps/PermissionController 模式：打开底部弹窗
                                                    opsSheetPermission = permission
                                                    showOpsSheet = true
                                                } else {
                                                    // 普通模式：pm grant/revoke + 0.2s 后验证
                                                    coroutineScope.launch {
                                                        val pkg = selectedApp?.packageName ?: return@launch
                                                        val expectedGranted = !permission.granted
                                                        val action = if (permission.granted) "revoke" else "grant"
                                                        val result = withContext(Dispatchers.IO) {
                                                            SpecialPermissionVerifier.executeRootCommandFull("pm $action $pkg ${permission.rawName}")
                                                        }
                                                        if (result.third != 0) {
                                                            val stderr = result.second.lowercase()
                                                            errorMessage = when {
                                                                stderr.contains("permission denied") ||
                                                                stderr.contains("insufficient") ||
                                                                stderr.contains("not allowed") ->
                                                                    "当前应用所获得权限不足，无法进行此操作。"
                                                                else -> "修改失败: ${result.second}"
                                                            }
                                                            showError = true
                                                            return@launch
                                                        }
                                                        // 等待 0.2 秒后验证权限状态
                                                        kotlinx.coroutines.delay(200)
                                                        val verifyResult = withContext(Dispatchers.IO) {
                                                            SpecialPermissionVerifier.executeRootCommandFull("dumpsys package $pkg | grep '${permission.rawName}'")
                                                        }
                                                        val verifyOutput = verifyResult.first
                                                        val actuallyGranted = verifyOutput.contains("granted=true")
                                                        if (actuallyGranted == expectedGranted) {
                                                            // 生效，更新 UI
                                                            val updated = selectedAppPermissions.toMutableList()
                                                            val idx = updated.indexOfFirst { it.rawName == permission.rawName }
                                                            if (idx != -1) {
                                                                updated[idx] = permission.copy(granted = expectedGranted)
                                                                selectedAppPermissions = updated
                                                            }
                                                        } else {
                                                            errorMessage = "权限修改未生效\n\n" +
                                                                "应用「${selectedApp?.name}」的「${permission.name}」权限" +
                                                                "仍为${if (actuallyGranted) "已授权" else "未授权"}状态。\n\n" +
                                                                "可能原因：该应用通过设备管理员或其他策略锁定了此权限。"
                                                            showError = true
                                                        }
                                                    }
                                                }
                                            },
                                            groupColor = groupColors[group] ?: Color.Gray
                                        )
                                    }
                                }
                                item { Spacer(modifier = Modifier.height(80.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showError && errorMessage != null) {
        AlertDialog(
            onDismissRequest = { showError = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("操作失败")
                }
            },
            text = { Text(errorMessage!!) },
            confirmButton = { TextButton(onClick = { showError = false }) { Text("确定") } },
            shape = RoundedCornerShape(16.dp)
        )
    }

    // AppOps 模式底部弹窗
    if (showOpsSheet && opsSheetPermission != null && selectedApp != null) {
        val perm = opsSheetPermission!!
        val pkg = selectedApp!!.packageName
        val opMapping = permToOpMap[perm.rawName]

        val appOpsStates = if (currentMode == "PERMISSION_CONTROLLER") {
            listOf(
                Triple("allow", "允许", "应用可以正常使用该权限"),
                Triple("ignore", "忽略", "应用以为有权限但实际被静默拦截"),
                Triple("deny", "拒绝", "应用使用该权限时会收到错误提示"),
                Triple("default", "默认", "跟随系统默认策略"),
                Triple("foreground", "仅前台", "仅当应用在前台时允许使用"),
                Triple("one_time", "一次性允许", "应用进程结束后自动撤销"),
                Triple("user_fixed", "用户固定拒绝", "用户手动拒绝且勾选了不再询问"),
                Triple("policy_fixed", "策略固定", "由企业设备管理器锁定，无法更改")
            )
        } else {
            listOf(
                Triple("allow", "允许", "应用可以正常使用该权限"),
                Triple("ignore", "忽略", "应用以为有权限但实际被静默拦截"),
                Triple("deny", "拒绝", "应用使用该权限时会收到错误提示"),
                Triple("default", "默认", "跟随系统默认策略"),
                Triple("foreground", "仅前台", "仅当应用在前台时允许使用")
            )
        }

        ModalBottomSheet(
            onDismissRequest = { showOpsSheet = false },
            sheetState = opsSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    opMapping?.displayName ?: perm.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (opMapping != null) {
                    Text(
                        opMapping.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "当前状态：${appOpsModeToDisplayName(perm.appOpsMode)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))

                appOpsStates.forEach { (modeKey, modeName, modeDesc) ->
                    val isCurrentMode = perm.appOpsMode == modeKey
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                coroutineScope.launch {
                                    val opName = opMapping?.opName ?: return@launch
                                    val success = setAppOpsMode(pkg, opName, modeKey, useRootForOps)
                                    if (success) {
                                        val updated = selectedAppPermissions.toMutableList()
                                        val idx = updated.indexOfFirst { it.rawName == perm.rawName }
                                        if (idx != -1) {
                                            updated[idx] = perm.copy(appOpsMode = modeKey)
                                            selectedAppPermissions = updated
                                        }
                                        showOpsSheet = false
                                    } else {
                                        errorMessage = if (!SpecialPermissionVerifier.isRootAvailable()) {
                                            "设置失败：AppOps 操作需要 Root 或 Shizuku 权限才能执行。\n\n请通过 Root 管理器（如 Magisk）授予 su 授权，或启动 Shizuku 并授权本应用，或切换到普通模式。"
                                        } else {
                                            "设置失败：命令执行出错，请重试"
                                        }
                                        showError = true
                                    }
                                }
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCurrentMode) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isCurrentMode) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    modeName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isCurrentMode) FontWeight.Medium else FontWeight.Normal
                                )
                                Text(
                                    modeDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionStat(count: Int, label: String, icon: ImageVector, iconTint: Color) {
    val isDarkMode = LocalIsDarkMode.current
    val countColor = if (isDarkMode) Color(0xFFE8F4FF) else Color(0xFF1E293B)
    val labelColor = if (isDarkMode) Color(0x9964B4D2) else Color(0x9964748B)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(48.dp).background(iconTint.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(count.toString(), style = MaterialTheme.typography.titleLarge,
            color = countColor, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = labelColor)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppItem(app: AppInfo, onClick: () -> Unit) {
    val isDarkMode = LocalIsDarkMode.current
    val iconBgColor = if (isDarkMode) Color(0xFF0E2A40) else Color(0xFFB2EBF2)
    val iconTint = if (isDarkMode) Color(0xFF38D4F5) else Color(0xFF00838F)
    val titleColor = if (isDarkMode) Color(0xFFE8F4FF) else Color(0xFF1E293B)
    val subtitleColor = if (isDarkMode) Color(0x9964B4D2) else Color(0x9964748B)
    val buttonBgColor = if (isDarkMode) Color(0xFF0E2A40) else Color(0xFFE0F7FA)

    GlowCard(
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(52.dp).padding(end = 8.dp)
                    .background(iconBgColor, CircleShape)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                val appIcon = app.icon
                if (appIcon != null) {
                    Image(bitmap = appIcon.toBitmap().asImageBitmap(), contentDescription = app.name,
                        modifier = Modifier.size(48.dp).clip(CircleShape))
                } else {
                    Icon(Icons.Default.Android, contentDescription = app.name,
                        modifier = Modifier.size(32.dp), tint = iconTint)
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = titleColor)
                Spacer(modifier = Modifier.height(4.dp))
                Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (app.isSystemApp) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("系统应用", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                    }
                }
            }
            FilledIconButton(
                onClick = onClick, modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = buttonBgColor)
            ) {
                Icon(Icons.Default.Security, contentDescription = "查看权限",
                    tint = iconTint, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun PermissionToggleItem(permission: PermissionInfo, onToggle: () -> Unit, groupColor: Color, mode: String = "NORMAL") {
    val isAppOpsMode = mode == "APPOPS" || mode == "PERMISSION_CONTROLLER"
    val animatedElevation by animateDpAsState(
        targetValue = if (permission.granted || isAppOpsMode) 2.dp else 0.dp, label = "elevation")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 2.dp)
            .then(if (isAppOpsMode) Modifier.clickable { onToggle() } else Modifier)
            .shadow(elevation = animatedElevation, shape = RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (permission.granted || isAppOpsMode) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(4.dp)
                .background(groupColor.copy(alpha = if (permission.granted || isAppOpsMode) 0.9f else 0.4f)))
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(40.dp).background(
                        if (permission.granted || isAppOpsMode) groupColor.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (permission.dangerous) {
                        Icon(Icons.Default.Warning, contentDescription = "危险权限",
                            tint = if (permission.granted || isAppOpsMode) Color(0xFFFF9800) else Color(0xFFFF9800).copy(alpha = 0.6f),
                            modifier = Modifier.size(24.dp))
                    } else {
                        Icon(
                            imageVector = if (permission.granted || isAppOpsMode) Icons.Default.Check else Icons.Default.Lock,
                            contentDescription = if (permission.granted) "已授权" else "未授权",
                            tint = if (permission.granted || isAppOpsMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(24.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(permission.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                        color = if (permission.granted || isAppOpsMode) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(permission.description, style = MaterialTheme.typography.bodySmall,
                        color = if (permission.granted || isAppOpsMode) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        lineHeight = 16.sp)
                }
                if (isAppOpsMode) {
                    // AppOps 模式：显示当前状态标签 + 箭头
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = when (permission.appOpsMode) {
                            "allow" -> MaterialTheme.colorScheme.primaryContainer
                            "ignore" -> Color(0xFFFF9800).copy(alpha = 0.2f)
                            "deny" -> MaterialTheme.colorScheme.errorContainer
                            "foreground" -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                appOpsModeToDisplayName(permission.appOpsMode),
                                style = MaterialTheme.typography.labelMedium,
                                color = when (permission.appOpsMode) {
                                    "allow" -> MaterialTheme.colorScheme.onPrimaryContainer
                                    "ignore" -> Color(0xFFE65100)
                                    "deny" -> MaterialTheme.colorScheme.onErrorContainer
                                    "foreground" -> MaterialTheme.colorScheme.onSecondaryContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // 普通模式：Switch 开关
                    Switch(
                        checked = permission.granted,
                        onCheckedChange = { onToggle() },
                        thumbContent = if (permission.granted) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                        } else null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            uncheckedThumbColor = MaterialTheme.colorScheme.surfaceVariant,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }
    }
}

private suspend fun loadInstalledApps(packageManager: PackageManager): List<AppInfo> = withContext(Dispatchers.IO) {
    val apps = mutableListOf<AppInfo>()
    try {
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_SHARED_LIBRARY_FILES
        val installedApps = packageManager.getInstalledApplications(flags)
        for (appInfo in installedApps) {
            try {
                val packageInfo = packageManager.getPackageInfo(appInfo.packageName, 0)
                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                apps.add(AppInfo(
                    name = packageManager.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    icon = packageManager.getApplicationIcon(appInfo.packageName),
                    installTime = packageInfo.firstInstallTime,
                    isSystemApp = isSystemApp
                ))
            } catch (_: Exception) {}
        }
    } catch (e: Exception) { e.printStackTrace() }
    apps
}

private suspend fun getAppPermissions(packageName: String, context: Context): List<PermissionInfo> = withContext(Dispatchers.IO) {
    val permissions = mutableListOf<PermissionInfo>()
    try {
        val pkgInfo = context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val requestedPermsArray = pkgInfo.requestedPermissions ?: emptyArray()
        val requestedFlags = pkgInfo.requestedPermissionsFlags ?: IntArray(requestedPermsArray.size)
        val requestedPerms = requestedPermsArray.toSet()

        val grantedPerms = mutableSetOf<String>()
        for (i in requestedPermsArray.indices) {
            if ((requestedFlags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                grantedPerms.add(requestedPermsArray[i])
            }
        }

        val importantPermGroups = mapOf(
            "android.permission.CAMERA" to "CAMERA",
            "android.permission.READ_CONTACTS" to "CONTACTS", "android.permission.WRITE_CONTACTS" to "CONTACTS",
            "android.permission.GET_ACCOUNTS" to "CONTACTS",
            "android.permission.ACCESS_FINE_LOCATION" to "LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION" to "LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION" to "LOCATION",
            "android.permission.READ_CALL_LOG" to "CALL_LOG", "android.permission.WRITE_CALL_LOG" to "CALL_LOG",
            "android.permission.PROCESS_OUTGOING_CALLS" to "CALL_LOG",
            "android.permission.READ_PHONE_STATE" to "PHONE", "android.permission.READ_PHONE_NUMBERS" to "PHONE",
            "android.permission.CALL_PHONE" to "PHONE", "android.permission.ANSWER_PHONE_CALLS" to "PHONE",
            "android.permission.ADD_VOICEMAIL" to "PHONE", "android.permission.USE_SIP" to "PHONE",
            "android.permission.ACCEPT_HANDOVER" to "PHONE",
            "android.permission.BODY_SENSORS" to "SENSORS", "android.permission.BODY_SENSORS_BACKGROUND" to "SENSORS",
            "android.permission.ACTIVITY_RECOGNITION" to "ACTIVITY_RECOGNITION",
            "android.permission.READ_CALENDAR" to "CALENDAR", "android.permission.WRITE_CALENDAR" to "CALENDAR",
            "android.permission.READ_EXTERNAL_STORAGE" to "STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE" to "STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE" to "STORAGE",
            "android.permission.READ_MEDIA_IMAGES" to "STORAGE", "android.permission.READ_MEDIA_VIDEO" to "STORAGE",
            "android.permission.READ_MEDIA_AUDIO" to "STORAGE",
            "android.permission.RECORD_AUDIO" to "MICROPHONE",
            "android.permission.SEND_SMS" to "SMS", "android.permission.RECEIVE_SMS" to "SMS",
            "android.permission.READ_SMS" to "SMS", "android.permission.RECEIVE_WAP_PUSH" to "SMS",
            "android.permission.RECEIVE_MMS" to "SMS"
        )

        val permDisplayNames = mapOf(
            "android.permission.CAMERA" to "相机",
            "android.permission.READ_CONTACTS" to "读取联系人", "android.permission.WRITE_CONTACTS" to "写入联系人",
            "android.permission.GET_ACCOUNTS" to "获取账户",
            "android.permission.ACCESS_FINE_LOCATION" to "精确位置", "android.permission.ACCESS_COARSE_LOCATION" to "粗略位置",
            "android.permission.ACCESS_BACKGROUND_LOCATION" to "后台位置",
            "android.permission.READ_CALL_LOG" to "读取通话记录", "android.permission.WRITE_CALL_LOG" to "写入通话记录",
            "android.permission.PROCESS_OUTGOING_CALLS" to "处理外拨电话",
            "android.permission.READ_PHONE_STATE" to "读取手机状态", "android.permission.READ_PHONE_NUMBERS" to "读取手机号码",
            "android.permission.CALL_PHONE" to "拨打电话", "android.permission.ANSWER_PHONE_CALLS" to "接听电话",
            "android.permission.ADD_VOICEMAIL" to "添加语音信箱", "android.permission.USE_SIP" to "使用 SIP",
            "android.permission.ACCEPT_HANDOVER" to "通话转移",
            "android.permission.BODY_SENSORS" to "身体传感器", "android.permission.BODY_SENSORS_BACKGROUND" to "后台身体传感器",
            "android.permission.ACTIVITY_RECOGNITION" to "活动识别",
            "android.permission.READ_CALENDAR" to "读取日历", "android.permission.WRITE_CALENDAR" to "写入日历",
            "android.permission.READ_EXTERNAL_STORAGE" to "读取存储", "android.permission.WRITE_EXTERNAL_STORAGE" to "写入存储",
            "android.permission.MANAGE_EXTERNAL_STORAGE" to "管理存储",
            "android.permission.READ_MEDIA_IMAGES" to "读取图片", "android.permission.READ_MEDIA_VIDEO" to "读取视频",
            "android.permission.READ_MEDIA_AUDIO" to "读取音频",
            "android.permission.RECORD_AUDIO" to "录音",
            "android.permission.SEND_SMS" to "发送短信", "android.permission.RECEIVE_SMS" to "接收短信",
            "android.permission.READ_SMS" to "读取短信", "android.permission.RECEIVE_WAP_PUSH" to "接收 WAP 推送",
            "android.permission.RECEIVE_MMS" to "接收彩信"
        )

        val permDescriptions = mapOf(
            "android.permission.CAMERA" to "允许应用使用相机拍摄照片和录制视频",
            "android.permission.READ_CONTACTS" to "允许应用读取您的通讯录联系人信息",
            "android.permission.WRITE_CONTACTS" to "允许应用新增、修改或删除通讯录联系人",
            "android.permission.GET_ACCOUNTS" to "允许应用获取设备上已登录的账户列表",
            "android.permission.ACCESS_FINE_LOCATION" to "允许应用通过 GPS 获取精确的地理位置",
            "android.permission.ACCESS_COARSE_LOCATION" to "允许应用通过基站或 Wi-Fi 获取大致位置",
            "android.permission.ACCESS_BACKGROUND_LOCATION" to "允许应用在后台持续获取位置信息",
            "android.permission.READ_CALL_LOG" to "允许应用读取通话历史记录",
            "android.permission.WRITE_CALL_LOG" to "允许应用修改或删除通话记录",
            "android.permission.PROCESS_OUTGOING_CALLS" to "允许应用监视、修改或阻止外拨电话",
            "android.permission.READ_PHONE_STATE" to "允许应用读取设备标识、网络运营商和通话状态",
            "android.permission.READ_PHONE_NUMBERS" to "允许应用读取本机电话号码",
            "android.permission.CALL_PHONE" to "允许应用直接拨出电话，无需用户确认",
            "android.permission.ANSWER_PHONE_CALLS" to "允许应用代为接听来电",
            "android.permission.ADD_VOICEMAIL" to "允许应用添加语音信箱",
            "android.permission.USE_SIP" to "允许应用使用 SIP 进行网络通话",
            "android.permission.ACCEPT_HANDOVER" to "允许应用继续其他应用正在进行的通话",
            "android.permission.BODY_SENSORS" to "允许应用读取心率、血氧等身体传感器数据",
            "android.permission.BODY_SENSORS_BACKGROUND" to "允许应用在后台持续读取身体传感器数据",
            "android.permission.ACTIVITY_RECOGNITION" to "允许应用检测步行、跑步、骑行等运动状态",
            "android.permission.READ_CALENDAR" to "允许应用读取日历事件和提醒",
            "android.permission.WRITE_CALENDAR" to "允许应用新增、修改或删除日历事件",
            "android.permission.READ_EXTERNAL_STORAGE" to "允许应用读取外部存储中的文件",
            "android.permission.WRITE_EXTERNAL_STORAGE" to "允许应用在外部存储中创建或修改文件",
            "android.permission.MANAGE_EXTERNAL_STORAGE" to "允许应用访问和管理外部存储中的所有文件",
            "android.permission.READ_MEDIA_IMAGES" to "允许应用访问设备上的图片和照片文件",
            "android.permission.READ_MEDIA_VIDEO" to "允许应用访问设备上的视频文件",
            "android.permission.READ_MEDIA_AUDIO" to "允许应用访问设备上的音频文件",
            "android.permission.RECORD_AUDIO" to "允许应用使用麦克风录制音频",
            "android.permission.SEND_SMS" to "允许应用发送短信，可能产生费用",
            "android.permission.RECEIVE_SMS" to "允许应用接收和读取收到的短信",
            "android.permission.READ_SMS" to "允许应用读取设备上存储的短信内容",
            "android.permission.RECEIVE_WAP_PUSH" to "允许应用接收 WAP 推送消息",
            "android.permission.RECEIVE_MMS" to "允许应用接收彩信"
        )

        for ((permName, group) in importantPermGroups) {
            if (requestedPerms.contains(permName)) {
                permissions.add(PermissionInfo(
                    name = permDisplayNames[permName] ?: permName.substringAfterLast("."),
                    description = permDescriptions[permName] ?: "该权限用于系统级功能访问",
                    granted = grantedPerms.contains(permName),
                    dangerous = true, group = group, rawName = permName
                ))
            }
        }

        val processedPerms = permissions.map { it.rawName }.toSet()
        for (permName in grantedPerms) {
            if (permName !in processedPerms && (permName.startsWith("android.permission.") || permName.startsWith("permission."))
                && !importantPermGroups.containsKey(permName)) {
                permissions.add(PermissionInfo(
                    name = permName.substringAfterLast("."), description = "该权限用于系统级功能访问",
                    granted = true, dangerous = false, group = "OTHER_GRANTED", rawName = permName
                ))
            }
        }

        val allProcessed = permissions.map { it.rawName }.toSet()
        for (permName in requestedPerms) {
            if (permName !in allProcessed && (permName.startsWith("android.permission.") || permName.startsWith("permission."))
                && !importantPermGroups.containsKey(permName) && !grantedPerms.contains(permName)) {
                permissions.add(PermissionInfo(
                    name = permName.substringAfterLast("."), description = "该权限用于系统级功能访问",
                    granted = false, dangerous = false, group = "OTHER_DENIED", rawName = permName
                ))
            }
        }

        if (permissions.isEmpty()) {
            permissions.add(PermissionInfo("调试信息", "请求权限数: ${requestedPerms.size}, 授权数: ${grantedPerms.size}",
                granted = false, dangerous = false, group = "undefined", rawName = "debug.info"))
        }
    } catch (e: Exception) {
        permissions.add(PermissionInfo("错误信息", "获取权限失败: ${e.message}",
            granted = false, dangerous = false, group = "undefined", rawName = "error.info"))
    }

    permissions.sortedWith(compareBy(
        { if (it.group == "undefined") 1 else 0 },
        { !it.granted }, { it.group }, { it.name }
    ))
}

// ── AppOps 模式函数 ──

/** 读取单个应用的 AppOps 状态，返回 opName -> mode 的映射 */
private suspend fun readAppOpsState(packageName: String, context: Context): Map<String, String> = withContext(Dispatchers.IO) {
    val result = mutableMapOf<String, String>()
    try {
        val cmd = "appops get $packageName"
        val output = when {
            SpecialPermissionVerifier.isRootAvailable() -> SpecialPermissionVerifier.executeRootCommandFull(cmd)
            SpecialPermissionVerifier.isShizukuAuthorized(context) -> SpecialPermissionVerifier.executeShizukuCommand(cmd)
            else -> SpecialPermissionVerifier.executeShellCommandFull(cmd)
        }
        val lines = output.first.split("\n")
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            // 格式: "android:camera: mode=allow" 或 "android:camera: allow"
            val colonIdx = trimmed.indexOf(":")
            if (colonIdx < 0) continue
            val opPart = trimmed.substring(0, colonIdx).trim()
            val modePart = trimmed.substring(colonIdx + 1).trim()
            val mode = when {
                modePart.contains("allow") -> "allow"
                modePart.contains("ignore") -> "ignore"
                modePart.contains("deny") -> "deny"
                modePart.contains("foreground") -> "foreground"
                modePart.contains("default") -> "default"
                else -> "default"
            }
            result[opPart] = mode
        }
    } catch (_: Exception) {}
    result
}

/** 读取单个 op 的状态 */
private suspend fun readSingleOpState(packageName: String, opName: String, context: Context): String = withContext(Dispatchers.IO) {
    try {
        val cmd = "appops get $packageName $opName"
        val output = when {
            SpecialPermissionVerifier.isRootAvailable() -> SpecialPermissionVerifier.executeRootCommandFull(cmd)
            SpecialPermissionVerifier.isShizukuAuthorized(context) -> SpecialPermissionVerifier.executeShizukuCommand(cmd)
            else -> SpecialPermissionVerifier.executeShellCommandFull(cmd)
        }
        val text = output.first.trim()
        when {
            text.contains("allow") -> "allow"
            text.contains("ignore") -> "ignore"
            text.contains("deny") -> "deny"
            text.contains("foreground") -> "foreground"
            text.contains("default") -> "default"
            else -> "default"
        }
    } catch (_: Exception) { "default" }
}

/** 设置 AppOps 模式 */
private suspend fun setAppOpsMode(packageName: String, opName: String, mode: String, useRoot: Boolean): Boolean = withContext(Dispatchers.IO) {
    try {
        val cmd = "appops set $packageName $opName $mode"
        // appops 命令需要 root 或 shell(Shizuku) 权限，自动检测并优先使用 root
        val shizukuAvailable = com.whmdg.mczj.tools.security.ShizukuAuthorizer.isShizukuServiceRunning() &&
                com.whmdg.mczj.tools.security.ShizukuAuthorizer.hasShizukuPermission()
        val result = when {
            useRoot || SpecialPermissionVerifier.isRootAvailable() -> SpecialPermissionVerifier.executeRootCommandFull(cmd)
            shizukuAvailable -> SpecialPermissionVerifier.executeShizukuCommand(cmd)
            else -> SpecialPermissionVerifier.executeShellCommandFull(cmd)
        }
        result.third == 0
    } catch (_: Exception) { false }
}

/** AppOps 模式中文显示名 */
fun appOpsModeToDisplayName(mode: String): String = when (mode) {
    "allow" -> "允许"
    "ignore" -> "忽略"
    "deny" -> "拒绝"
    "default" -> "默认"
    "foreground" -> "仅前台"
    else -> "未知"
}

/** AppOps 模式中文说明 */
fun appOpsModeToDescription(mode: String): String = when (mode) {
    "allow" -> "应用可以正常使用该权限"
    "ignore" -> "应用以为有权限但实际被静默拦截"
    "deny" -> "应用使用该权限时会收到错误提示"
    "default" -> "跟随系统默认策略"
    "foreground" -> "仅当应用在前台时允许使用"
    else -> ""
}
