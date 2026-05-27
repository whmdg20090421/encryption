package com.whmdg.mczj.tools.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.widget.Toast

data class PermissionEntry(
    val name: String,
    val androidName: String,
    val icon: String,
    val granted: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var permissionList by remember { mutableStateOf(listOf<PermissionEntry>()) }

    fun refreshPermissions() {
        permissionList = checkAllPermissions(context)
    }

    LaunchedEffect(Unit) {
        refreshPermissions()
    }

    var selectedPermissionToRequest by remember { mutableStateOf<String?>(null) }
    var showWriteSecureSettingsDialog by remember { mutableStateOf(false) }

    val normalLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        refreshPermissions()
    }

    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开设置，请手动开启权限", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestPermission(androidName: String) {
        when (androidName) {
            "MANAGE_EXTERNAL_STORAGE" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            context.startActivity(intent)
                        } catch (ex: Exception) {
                            openAppSettings(context)
                        }
                    }
                }
            }
            "SYSTEM_ALERT_WINDOW" -> {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        context.startActivity(intent)
                    } catch (ex: Exception) {
                        openAppSettings(context)
                    }
                }
            }
            "REQUEST_INSTALL_PACKAGES" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        openAppSettings(context)
                    }
                }
            }
            "WRITE_SECURE_SETTINGS" -> {
                showWriteSecureSettingsDialog = true
            }
            "IGNORE_BATTERY_OPTIMIZATIONS" -> {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .apply { data = Uri.parse("package:${context.packageName}") }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(intent)
                        Toast.makeText(context, "请在列表中找到本应用并关闭电池优化", Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {
                        openAppSettings(context)
                    }
                }
            }
            "WRITE_EXTERNAL_STORAGE" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestPermission("MANAGE_EXTERNAL_STORAGE")
                } else {
                    try {
                        normalLauncher.launch("android.permission.WRITE_EXTERNAL_STORAGE")
                    } catch (e: Exception) {
                        openAppSettings(context)
                    }
                }
            }
            else -> {
                try {
                    normalLauncher.launch("android.permission.$androidName")
                } catch (e: Exception) {
                    openAppSettings(context)
                }
            }
        }
    }

    if (showWriteSecureSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showWriteSecureSettingsDialog = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("授予修改系统设置权限") },
            text = {
                Text("请在特殊权限设置中找到「修改系统设置」并为本应用开启。\n\n如无法找到该选项，可通过 ADB 命令授予：\nadb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
            },
            confirmButton = {
                Button(onClick = {
                    showWriteSecureSettingsDialog = false
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(context, "无法打开设置，请手动开启或使用 ADB 命令", Toast.LENGTH_LONG).show()
                    }
                }) { Text("打开设置") }
            },
            dismissButton = {
                TextButton(onClick = { showWriteSecureSettingsDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshPermissions() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(permissionList) { entry ->
                PermissionRow(
                    entry = entry,
                    onTap = {
                        if (!entry.granted) {
                            requestPermission(entry.androidName)
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
fun PermissionRow(entry: PermissionEntry, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(entry.icon, style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                entry.androidName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (entry.granted) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "已授权",
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "未授权",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

fun checkAllPermissions(context: Context): List<PermissionEntry> {
    val all = listOf(
        // ── 存储 ──
        Triple("存储空间", "WRITE_EXTERNAL_STORAGE", "📁"),
        Triple("所有文件访问", "MANAGE_EXTERNAL_STORAGE", "📂"),
        // ── 媒体 ──
        Triple("媒体-图片", "READ_MEDIA_IMAGES", "🖼️"),
        Triple("媒体-视频", "READ_MEDIA_VIDEO", "🎬"),
        Triple("媒体-音频", "READ_MEDIA_AUDIO", "🎵"),
        // ── 相机/麦克风 ──
        Triple("相机", "CAMERA", "📷"),
        Triple("麦克风", "RECORD_AUDIO", "🎤"),
        // ── 位置 ──
        Triple("精确位置", "ACCESS_FINE_LOCATION", "📍"),
        Triple("粗略位置", "ACCESS_COARSE_LOCATION", "🌐"),
        // ── 联系人 ──
        Triple("读取联系人", "READ_CONTACTS", "👤"),
        Triple("写入联系人", "WRITE_CONTACTS", "📇"),
        // ── 日历 ──
        Triple("读取日历", "READ_CALENDAR", "📅"),
        Triple("写入日历", "WRITE_CALENDAR", "📆"),
        // ── 短信 ──
        Triple("读取短信", "READ_SMS", "💬"),
        Triple("发送短信", "SEND_SMS", "✉️"),
        // ── 电话 ──
        Triple("读取设备状态", "READ_PHONE_STATE", "📱"),
        Triple("拨打电话", "CALL_PHONE", "📞"),
        // ── 通知 ──
        Triple("通知", "POST_NOTIFICATIONS", "🔔"),
        // ── 特殊权限 ──
        Triple("悬浮窗", "SYSTEM_ALERT_WINDOW", "🪟"),
        Triple("安装未知应用", "REQUEST_INSTALL_PACKAGES", "📦"),
        Triple("修改系统设置", "WRITE_SECURE_SETTINGS", "⚙️"),
        // ── 蓝牙 ──
        Triple("蓝牙连接", "BLUETOOTH_CONNECT", "🔵"),
        Triple("蓝牙扫描", "BLUETOOTH_SCAN", "🔍"),
        // ── 传感器 ──
        Triple("身体传感器", "BODY_SENSORS", "🏃"),
        Triple("活动识别", "ACTIVITY_RECOGNITION", "🚶"),
        // ── 电池优化 ──
        Triple("电池优化豁免", "IGNORE_BATTERY_OPTIMIZATIONS", "🔋")
    )

    return all.map { (name, androidName, icon) ->
        val granted = isPermissionGranted(context, androidName)
        PermissionEntry(name, androidName, icon, granted)
    }
}

fun isPermissionGranted(context: Context, androidName: String): Boolean {
    return try {
        when (androidName) {
            "MANAGE_EXTERNAL_STORAGE" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.os.Environment.isExternalStorageManager()
                } else {
                    ContextCompat.checkSelfPermission(
                        context,
                        "android.permission.WRITE_EXTERNAL_STORAGE"
                    ) == PackageManager.PERMISSION_GRANTED
                }
            }
            "SYSTEM_ALERT_WINDOW" -> {
                Settings.canDrawOverlays(context)
            }
            "REQUEST_INSTALL_PACKAGES" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.packageManager.canRequestPackageInstalls()
                } else {
                    true
                }
            }
            "IGNORE_BATTERY_OPTIMIZATIONS" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    pm.isIgnoringBatteryOptimizations(context.packageName)
                } else {
                    true
                }
            }
            "WRITE_SECURE_SETTINGS" -> {
                // 签名级权限，优先通过 PackageManager 检查
                val granted = try {
                    val pkgInfo = context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.GET_PERMISSIONS
                    )
                    val idx = pkgInfo.requestedPermissions?.indexOf(
                        "android.permission.WRITE_SECURE_SETTINGS"
                    ) ?: -1
                    idx >= 0 && (pkgInfo.requestedPermissionsFlags!![idx] and
                            PackageManager.REQUESTED_PERMISSION_GRANTED) != 0
                } catch (_: Exception) { false }
                if (granted) return true
                // 降级：通过写入检测
                val key = "toolbox_wss_check"
                try {
                    Settings.Secure.putString(context.contentResolver, key, "1")
                    val result = Settings.Secure.getString(context.contentResolver, key)
                    Settings.Secure.putString(context.contentResolver, key, null)
                    result == "1"
                } catch (_: SecurityException) { false }
            }
            "WRITE_EXTERNAL_STORAGE" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.os.Environment.isExternalStorageManager()
                } else {
                    ContextCompat.checkSelfPermission(
                        context,
                        "android.permission.WRITE_EXTERNAL_STORAGE"
                    ) == PackageManager.PERMISSION_GRANTED
                }
            }
            else -> {
                val fullName = "android.permission.$androidName"
                ContextCompat.checkSelfPermission(context, fullName) == PackageManager.PERMISSION_GRANTED
            }
        }
    } catch (e: Exception) {
        false
    }
}
