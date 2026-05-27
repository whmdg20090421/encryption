package com.whmdg.mczj.tools.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.animation.*
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
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
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
    val rawName: String
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
                selectedAppPermissions = getAppPermissions(packageName, context)
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
                                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                                errorMessage = "重置失败: ${result.second}"
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
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("权限概览", style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        PermissionStat(totalPerms, "总权限", Icons.Default.List, MaterialTheme.colorScheme.onPrimaryContainer)
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
                                            onToggle = {
                                                coroutineScope.launch {
                                                    val pkg = selectedApp?.packageName ?: return@launch
                                                    val action = if (permission.granted) "revoke" else "grant"
                                                    val result = withContext(Dispatchers.IO) {
                                                        SpecialPermissionVerifier.executeRootCommandFull("pm $action $pkg ${permission.rawName}")
                                                    }
                                                    if (result.third == 0) {
                                                        val updated = selectedAppPermissions.toMutableList()
                                                        val idx = updated.indexOfFirst { it.rawName == permission.rawName }
                                                        if (idx != -1) {
                                                            updated[idx] = permission.copy(granted = !permission.granted)
                                                            selectedAppPermissions = updated
                                                        }
                                                    } else {
                                                        errorMessage = "修改失败: ${result.second}"
                                                        showError = true
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
}

@Composable
private fun PermissionStat(count: Int, label: String, icon: ImageVector, iconTint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(48.dp).background(iconTint.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(count.toString(), style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppItem(app: AppInfo, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(52.dp).padding(end = 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), CircleShape)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                val appIcon = app.icon
                if (appIcon != null) {
                    Image(bitmap = appIcon.toBitmap().asImageBitmap(), contentDescription = app.name,
                        modifier = Modifier.size(48.dp).clip(CircleShape))
                } else {
                    Icon(Icons.Default.Android, contentDescription = app.name,
                        modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(4.dp))
                Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
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
                    containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Icon(Icons.Default.Security, contentDescription = "查看权限",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun PermissionToggleItem(permission: PermissionInfo, onToggle: () -> Unit, groupColor: Color) {
    val animatedElevation by animateDpAsState(
        targetValue = if (permission.granted) 2.dp else 0.dp, label = "elevation")

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 2.dp)
            .shadow(elevation = animatedElevation, shape = RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (permission.granted) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(4.dp)
                .background(groupColor.copy(alpha = if (permission.granted) 0.9f else 0.4f)))
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(40.dp).background(
                        if (permission.granted) groupColor.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (permission.dangerous) {
                        Icon(Icons.Default.Warning, contentDescription = "危险权限",
                            tint = if (permission.granted) Color(0xFFFF9800) else Color(0xFFFF9800).copy(alpha = 0.6f),
                            modifier = Modifier.size(24.dp))
                    } else {
                        Icon(
                            imageVector = if (permission.granted) Icons.Default.Check else Icons.Default.Lock,
                            contentDescription = if (permission.granted) "已授权" else "未授权",
                            tint = if (permission.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(24.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(permission.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                        color = if (permission.granted) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(permission.description, style = MaterialTheme.typography.bodySmall,
                        color = if (permission.granted) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        lineHeight = 16.sp)
                }
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
        val packageInfoResult = SpecialPermissionVerifier.executeRootCommandFull("dumpsys package $packageName")
        val output = packageInfoResult.first

        val grantedPermsResult = SpecialPermissionVerifier.executeRootCommandFull(
            "dumpsys package $packageName | grep -E \"granted=true|:granted=true\"")

        val requestedPerms = mutableSetOf<String>()
        val requestedSection = extractSectionContent(output, "requested permissions:")
        if (requestedSection.isNotEmpty()) extractPermissionsFromSection(requestedSection, requestedPerms)
        val installSection = extractSectionContent(output, "install permissions:")
        if (installSection.isNotEmpty()) extractPermissionsFromSection(installSection, requestedPerms)
        val runtimeSection = extractSectionContent(output, "runtime permissions:")
        if (runtimeSection.isNotEmpty()) extractPermissionsFromSection(runtimeSection, requestedPerms)

        if (requestedPerms.isEmpty()) {
            val permRegex = "(android\\.permission\\.[\\w\\.]+|permission\\.[\\w\\.]+)".toRegex()
            permRegex.findAll(output).forEach { match ->
                if (!match.value.contains("uses-permission:")) requestedPerms.add(match.value)
            }
        }

        val grantedPerms = mutableSetOf<String>()
        for (line in grantedPermsResult.first.split("\n")) {
            val permMatch = "(android\\.permission\\.[\\w\\.]+|permission\\.[\\w\\.]+)".toRegex().find(line)
            permMatch?.value?.let { grantedPerms.add(it) }
        }

        val grantedSection = extractSectionContent(output, "grantedPermissions:")
        if (grantedSection.isNotEmpty()) {
            for (line in grantedSection.split("\n")) {
                val trimmed = line.trim()
                if (trimmed.startsWith("android.permission.") || trimmed.startsWith("permission.")) {
                    grantedPerms.add(trimmed)
                }
            }
        }

        if (requestedPerms.isEmpty() && grantedPerms.isNotEmpty()) requestedPerms.addAll(grantedPerms)

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
            "android.permission.READ_CONTACTS" to "允许应用读取您的联系人数据",
            "android.permission.WRITE_CONTACTS" to "允许应用修改您的联系人数据",
            "android.permission.ACCESS_FINE_LOCATION" to "允许应用获取精确的位置信息",
            "android.permission.ACCESS_COARSE_LOCATION" to "允许应用获取粗略的位置信息",
            "android.permission.RECORD_AUDIO" to "允许应用使用麦克风录音",
            "android.permission.READ_PHONE_STATE" to "允许应用读取手机状态和身份",
            "android.permission.CALL_PHONE" to "允许应用直接拨打电话",
            "android.permission.READ_SMS" to "允许应用读取短信内容",
            "android.permission.SEND_SMS" to "允许应用发送短信"
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

private fun extractSectionContent(output: String, sectionHeader: String): String {
    val startIndex = output.indexOf(sectionHeader)
    if (startIndex == -1) return ""
    val sectionStart = startIndex + sectionHeader.length
    val possibleNextSections = listOf("grantedPermissions:", "runtime permissions:", "install permissions:",
        "requested permissions:", "User 0:", "Package [")
    var endIndex = output.length
    for (nextSection in possibleNextSections) {
        val nextIndex = output.indexOf(nextSection, sectionStart)
        if (nextIndex != -1 && nextIndex < endIndex) endIndex = nextIndex
    }
    return output.substring(sectionStart, endIndex).trim()
}

private fun extractPermissionsFromSection(section: String, permissions: MutableSet<String>) {
    for (line in section.split("\n")) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed.startsWith("android.permission.") || trimmed.startsWith("permission.")) {
            val permEnd = trimmed.indexOf(":")
            permissions.add(if (permEnd > 0) trimmed.substring(0, permEnd).trim() else trimmed)
        }
    }
}
