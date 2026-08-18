package com.whmdg.mczj.tools.ui.encryption

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.whmdg.mczj.tools.encryption.data.VaultPaths
import com.whmdg.mczj.tools.encryption.data.VaultRecord
import com.whmdg.mczj.tools.encryption.services.VaultService
import com.whmdg.mczj.tools.fileop.webdav.WebDavConnectionStatus
import com.whmdg.mczj.tools.fileop.webdav.WebDavAccountState
import com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient
import com.whmdg.mczj.tools.fileop.webdav.WebDavServerConfig
import com.whmdg.mczj.tools.fileop.webdav.WebDavServerStore
import com.whmdg.mczj.tools.ui.theme.LocalIsDarkMode
import com.whmdg.mczj.tools.ui.components.glowEffect
import com.whmdg.mczj.tools.util.FormatUtils
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 云盘同步项（UI 数据模型 + 持久化字段） */
data class CloudSyncItem(
    val id: String,
    val vaultId: Int = 0,       // 保险箱 ID（vault 类型时使用）
    val vaultName: String,
    val type: String,           // "保险箱" 或 "本地文件夹"
    val vaultSize: Long,
    val lastSyncTime: String,
    val cloudSize: Long,
    val diffFileCount: Int,
    val webdavPath: String = "", // 云端目标路径
    val localFileCount: Int? = null, // 本地文件数量（仅保险箱类型）
    val cloudFileCount: Int? = null  // 云端文件数量
)

/**
 * 云盘同步事件接口 —— 加密模块通过此接口向云盘模块传递用户选择。
 * 云盘模块内部自行管理 syncItems 状态，外部只通过事件驱动。
 */
class CloudSyncEvents {
    internal var addVaultRequest by mutableStateOf<VaultRecord?>(null)
    internal var requestCounter by mutableIntStateOf(0)

    /** 外部调用：请求添加保险箱到同步列表 */
    fun requestAddVault(vault: VaultRecord) {
        addVaultRequest = vault
        requestCounter++
    }
}

@Composable
fun CloudSyncScreen(
    vaultService: VaultService,
    events: CloudSyncEvents,
    onShowVaultSheet: () -> Unit,
    onNavigateToFileManager: (webdavConfig: WebDavServerConfig, vaultDir: String, vaultId: Int, vaultName: String) -> Unit = { _, _, _, _ -> }
) {
    val isDarkMode = LocalIsDarkMode.current
    val context = LocalContext.current
    var fabExpanded by remember { mutableStateOf(false) }
    val syncItems = remember { mutableStateListOf<CloudSyncItem>() }
    val processedVaultIds = remember { mutableSetOf<Int>() }

    // WebDAV 账户状态
    val scope = rememberCoroutineScope()
    var accountState by remember { mutableStateOf(WebDavAccountState()) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var confirmError by remember { mutableStateOf<Throwable?>(null) }

    // 从持久化存储加载同步项 + 刷新保险箱大小 + 检测 WebDAV 连接状态
    LaunchedEffect(Unit) {
        val saved = CloudSyncStore.load(context)
        if (saved.isNotEmpty()) {
            // 刷新保险箱类型的本地大小和文件数
            val refreshed = saved.map { item ->
                if (item.type == "保险箱" && item.vaultId > 0) {
                    val vault = vaultService.getVault(item.vaultId)
                    if (vault != null) item.copy(
                        vaultSize = vault.storageSize,
                        localFileCount = vault.fileCount
                    ) else item
                } else item
            }
            syncItems.clear()
            syncItems.addAll(refreshed)
            processedVaultIds.addAll(refreshed.filter { it.id.startsWith("vault_") }
                .map { it.id.removePrefix("vault_").toIntOrNull() ?: 0 })
            // 有变化则持久化
            if (refreshed != saved) CloudSyncStore.save(context, refreshed)
        }
        // 检测 WebDAV 连接状态
        val configs = WebDavServerStore.getAll(context)
        val config = configs.firstOrNull()
        if (config == null) {
            accountState = WebDavAccountState(WebDavConnectionStatus.NOT_LOGGED_IN)
        } else {
            // 先从缓存乐观显示为已登录，再异步验证
            accountState = WebDavAccountState(
                status = WebDavConnectionStatus.LOGGED_IN,
                config = config,
                displayName = config.getDefaultName()
            )
            // 异步验证连接
            val ok = withContext(Dispatchers.IO) {
                try {
                    val client = WebDavFileClient(config)
                    client.testConnection()
                    true
                } catch (_: Exception) { false }
            }
            if (!ok) {
                accountState = accountState.copy(status = WebDavConnectionStatus.EXPIRED)
            }
        }
    }

    // 处理外部传入的保险箱添加请求
    LaunchedEffect(events.requestCounter) {
        val vault = events.addVaultRequest ?: return@LaunchedEffect
        if (vault.id !in processedVaultIds) {
            processedVaultIds.add(vault.id)
            val item = CloudSyncItem(
                id = "vault_${vault.id}",
                vaultId = vault.id,
                vaultName = vault.name,
                type = "保险箱",
                vaultSize = vault.storageSize,
                lastSyncTime = "未同步",
                cloudSize = 0,
                diffFileCount = 0,
                localFileCount = vault.fileCount
            )
            syncItems.add(item)
            CloudSyncStore.save(context, syncItems.toList())
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 点击空白区域关闭菜单
        if (fabExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { fabExpanded = false }
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // ── 顶部状态栏：云盘标题 + 连接状态 + 齿轮 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isDarkMode) Color(0xFF38D4F5) else Color(0xFF00838F)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "云盘",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDarkMode) Color(0xFFE8F4FF) else Color(0xFF1E293B)
                )
                Spacer(modifier = Modifier.weight(1f))

                // 连接状态指示
                val statusColor = when (accountState.status) {
                    WebDavConnectionStatus.LOGGED_IN -> Color(0xFF4CAF50)
                    WebDavConnectionStatus.EXPIRED -> Color(0xFFFFC107)
                    WebDavConnectionStatus.NOT_LOGGED_IN -> Color(0xFFE57373)
                }
                val statusText = when (accountState.status) {
                    WebDavConnectionStatus.LOGGED_IN -> "已登录"
                    WebDavConnectionStatus.EXPIRED -> "已失效"
                    WebDavConnectionStatus.NOT_LOGGED_IN -> "未登录"
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    statusText,
                    fontSize = 12.sp,
                    color = statusColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = "设置",
                        modifier = Modifier.size(18.dp),
                        tint = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
                    )
                }
            }

            // ── 内容区域 ──
            if (syncItems.isEmpty()) {
                // 空状态
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = if (isDarkMode) Color(0xFF334155) else Color(0xFFCBD5E1)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "暂无同步项目",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isDarkMode) Color(0xFF64748B) else Color(0xFF94A3B8)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "点击右下角 + 添加保险箱或文件夹",
                            fontSize = 12.sp,
                            color = if (isDarkMode) Color(0xFF475569) else Color(0xFFB0BEC5)
                        )
                    }
                }
            } else {
                // 同步列表
                var showConfirmDialog by remember { mutableStateOf<CloudSyncItem?>(null) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(syncItems, key = { it.id }) { item ->
                        CloudSyncCard(
                            item = item,
                            onClick = { showConfirmDialog = item }
                        )
                    }
                }

                // 确认进入云盘模式弹窗
                showConfirmDialog?.let { item ->
                    AlertDialog(
                        onDismissRequest = { showConfirmDialog = null },
                        title = { Text("打开云盘同步") },
                        text = { Text("是否打开「${item.vaultName}」的云盘同步管理？") },
                        confirmButton = {
                            TextButton(onClick = {
                                showConfirmDialog = null
                                val config = accountState.config
                                if (config == null) {
                                    showSettingsDialog = true
                                    return@TextButton
                                }
                                if (item.type != "保险箱") return@TextButton
                                val vaultRecord = vaultService.getVault(item.vaultId)
                                if (vaultRecord == null) {
                                    android.widget.Toast.makeText(context, "保险箱不存在，请重新添加", android.widget.Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                val vaultDir = com.whmdg.mczj.tools.encryption.data.VaultPaths.resolveVault(
                                    context, vaultRecord.location, vaultRecord.relativePath
                                ).absolutePath
                                onNavigateToFileManager(config, vaultDir, item.vaultId, item.vaultName)
                            }) { Text("确认") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showConfirmDialog = null }) { Text("取消") }
                        }
                    )
                }

                // 报错弹窗
                com.whmdg.mczj.tools.ui.ErrorDialog(
                    error = confirmError,
                    onDismiss = { confirmError = null }
                )
            }
        }

        // 右下角可展开 FAB
        val fabScale by animateFloatAsState(
            targetValue = if (fabExpanded) 1f else 0f,
            animationSpec = tween(220),
            label = "fab_scale"
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(1f, 1f)
                    scaleX = fabScale
                    scaleY = fabScale
                    alpha = fabScale
                }
                .padding(end = 16.dp, bottom = 16.dp)
        ) {
            Column(horizontalAlignment = Alignment.End) {
                // 添加保险箱
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFE2E8F0),
                    shadowElevation = 4.dp,
                    modifier = Modifier.clickable(enabled = fabExpanded) {
                        fabExpanded = false
                        onShowVaultSheet()
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isDarkMode) Color(0xFF38D4F5) else Color(0xFF00838F))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("添加保险箱", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = if (isDarkMode) Color(0xFFE8F4FF) else Color(0xFF1E293B))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 添加文件夹
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFE2E8F0),
                    shadowElevation = 4.dp,
                    modifier = Modifier.clickable(enabled = fabExpanded) { fabExpanded = false }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isDarkMode) Color(0xFF38D4F5) else Color(0xFF00838F))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("添加文件夹", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = if (isDarkMode) Color(0xFFE8F4FF) else Color(0xFF1E293B))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 关闭按钮
                FloatingActionButton(
                    onClick = { fabExpanded = false },
                    containerColor = if (isDarkMode) Color(0xFF00C8FF) else Color(0xFF00838F),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
        }

        // 收起态的加号 FAB
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(1f, 1f)
                    scaleX = 1f - fabScale
                    scaleY = 1f - fabScale
                    alpha = 1f - fabScale
                }
        ) {
            FloatingActionButton(
                onClick = { fabExpanded = true },
                containerColor = if (isDarkMode) Color(0xFF00C8FF) else Color(0xFF00838F),
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        }

        // ── WebDAV 设置弹窗 ──
        if (showSettingsDialog) {
            WebDavSettingsDialog(
                initialConfig = accountState.config,
                onDismiss = { showSettingsDialog = false },
                onSave = { config ->
                    WebDavServerStore.save(context, config)
                    showSettingsDialog = false
                    // 重新检测连接状态
                    accountState = WebDavAccountState(
                        config = config,
                        displayName = config.getDefaultName()
                    )
                    // 异步检测
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            try {
                                val client = WebDavFileClient(config)
                                client.testConnection()
                                true
                            } catch (_: Exception) { false }
                        }
                        accountState = accountState.copy(
                            status = if (ok) WebDavConnectionStatus.LOGGED_IN else WebDavConnectionStatus.EXPIRED
                        )
                    }
                }
            )
        }
    }
}

// ── WebDAV 设置弹窗 ──
@Composable
private fun WebDavSettingsDialog(
    initialConfig: WebDavServerConfig?,
    onDismiss: () -> Unit,
    onSave: (WebDavServerConfig) -> Unit
) {
    var url by remember { mutableStateOf(initialConfig?.let { "${it.protocol}://${it.host}:${it.port}" } ?: "") }
    var username by remember { mutableStateOf(initialConfig?.username ?: "") }
    var password by remember { mutableStateOf(initialConfig?.password ?: "") }
    var path by remember { mutableStateOf(initialConfig?.relativePath ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    val dialogScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WebDAV 服务器配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 网址
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("网址") },
                    placeholder = { Text("https://dav.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // 账户
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("账户") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // 密码
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                // 路径（可选）
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("路径（可选）") },
                    placeholder = { Text("/留空则用根目录") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 测试连接按钮
                Button(
                    onClick = {
                        isTesting = true
                        testResult = null
                        val parsed = parseWebDavUrlPublic(url)
                        if (parsed == null) {
                            testResult = "网址格式错误"
                            testSuccess = false
                            isTesting = false
                            return@Button
                        }
                        val urlPath = extractPathFromUrl(url)
                        val effectivePath = path.trim().ifEmpty { urlPath }
                        val config = WebDavServerConfig(
                            protocol = parsed.first,
                            host = parsed.second,
                            port = parsed.third,
                            username = username.trim(),
                            password = password,
                            relativePath = effectivePath
                        )
                        dialogScope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                try {
                                    val client = WebDavFileClient(config)
                                    client.testConnection()
                                    true
                                } catch (e: Exception) {
                                    testResult = "连接失败: ${e.message}"
                                    false
                                }
                            }
                            if (ok) {
                                testResult = "连接成功"
                                testSuccess = true
                            } else if (testResult == null) {
                                testResult = "连接失败"
                                testSuccess = false
                            }
                            isTesting = false
                        }
                    },
                    enabled = !isTesting && url.isNotBlank() && username.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("测试连接")
                }

                // 测试结果
                if (testResult != null) {
                    Text(
                        text = testResult!!,
                        fontSize = 13.sp,
                        color = if (testSuccess) Color(0xFF4CAF50) else Color(0xFFE57373)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = parseWebDavUrlPublic(url)
                    if (parsed != null) {
                        val urlPath = extractPathFromUrl(url)
                        val effectivePath = path.trim().ifEmpty { urlPath }
                        onSave(WebDavServerConfig(
                            protocol = parsed.first,
                            host = parsed.second,
                            port = parsed.third,
                            username = username.trim(),
                            password = password,
                            relativePath = effectivePath
                        ))
                    }
                },
                enabled = url.isNotBlank() && username.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 解析 WebDAV URL 为 (protocol, host, port, path) */
fun parseWebDavUrlPublic(url: String): Triple<String, String, Int>? {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return null
    return try {
        val uri = java.net.URI(trimmed)
        val scheme = uri.scheme ?: "https"
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        Triple(scheme, host, port)
    } catch (_: Exception) { null }
}

/** 从 URL 中提取路径部分（不含主机和端口） */
fun extractPathFromUrl(url: String): String {
    val trimmed = url.trim()
    return try {
        val uri = java.net.URI(trimmed)
        (uri.path ?: "").trim('/')
    } catch (_: Exception) { "" }
}

// ── 云盘同步卡片 ──
@Composable
private fun CloudSyncCard(
    item: CloudSyncItem,
    onClick: () -> Unit = {}
) {
    val isDarkMode = LocalIsDarkMode.current
    val glowEnabled = true

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(
                horizontal = 16.dp,
                vertical = if (glowEnabled) 8.dp else 4.dp
            )
    ) {
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
                    } else Modifier
                )
                .drawBehind {
                    drawRoundRect(
                        color = Color(0x8C00D2FF),
                        cornerRadius = CornerRadius(20.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    if (glowEnabled) {
                        drawRoundRect(
                            color = Color(0x1F008CC8),
                            cornerRadius = CornerRadius(21.5.dp.toPx()),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                },
            shape = RoundedCornerShape(20.dp),
            color = Color.Transparent,
            shadowElevation = 4.dp
        ) {
            Box(
                modifier = Modifier.background(
                    Brush.linearGradient(
                        colors = if (isDarkMode) {
                            listOf(Color(0xFF111827), Color(0xFF0D1525), Color(0xFF0A1020))
                        } else {
                            listOf(Color(0xFFE0F7FA), Color(0xFFE8F5E9), Color(0xFFF5F5F5))
                        }
                    )
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 头部：图标 + 标题 + 类型
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .drawBehind {
                                    drawRoundRect(
                                        brush = Brush.linearGradient(
                                            colors = if (isDarkMode) {
                                                listOf(Color(0xFF0E2A40), Color(0xFF091825))
                                            } else {
                                                listOf(Color(0xFFB2EBF2), Color(0xFF80DEEA))
                                            }
                                        ),
                                        cornerRadius = CornerRadius(10.dp.toPx())
                                    )
                                    drawRoundRect(
                                        color = if (isDarkMode) Color(0x4000C8FF) else Color(0x4000BCD4),
                                        cornerRadius = CornerRadius(10.dp.toPx()),
                                        style = Stroke(width = 1.dp.toPx())
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                tint = if (isDarkMode) Color(0xFF38D4F5) else Color(0xFF00838F),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "云盘同步列表",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDarkMode) Color(0xFFE8F4FF) else Color(0xFF1E293B)
                            )
                            Text(
                                item.type,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.12.em,
                                color = if (isDarkMode) Color(0x8C00C8FF) else Color(0x8C00838F)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 分隔线
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0x3300B4E6), Color(0x0D00B4E6), Color.Transparent)
                                )
                            )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 信息行
                    CloudInfoRow("名称", item.vaultName, isDarkMode)
                    Spacer(modifier = Modifier.height(4.dp))
                    CloudInfoRow("最后同步", item.lastSyncTime, isDarkMode)
                    Spacer(modifier = Modifier.height(4.dp))
                    CloudInfoRow("本地大小", buildString {
                        append(FormatUtils.formatBytes(item.vaultSize))
                        if (item.type == "保险箱" && item.localFileCount != null) append(" (${item.localFileCount} 个文件)")
                    }, isDarkMode)
                    Spacer(modifier = Modifier.height(4.dp))
                    CloudInfoRow("云端大小", buildString {
                        append(FormatUtils.formatBytes(item.cloudSize))
                        if (item.type == "保险箱" && item.cloudFileCount != null) append(" (${item.cloudFileCount} 个文件)")
                    }, isDarkMode)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "差异文件",
                            fontSize = 11.sp,
                            color = if (isDarkMode) Color(0x9964B4D2) else Color(0x9964748B),
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.03.em
                        )
                        Text(
                            "${item.diffFileCount} 个",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (item.diffFileCount > 0) Color(0xFFFF9800) else {
                                if (isDarkMode) Color(0xFFA8D4F0) else Color(0xFF0EA5E9)
                            },
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudInfoRow(label: String, value: String, isDarkMode: Boolean) {
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
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.03.em
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
