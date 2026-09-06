package com.whmdg.mczj.tools.ui.encryption

import androidx.compose.animation.AnimatedVisibility
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
import com.whmdg.mczj.tools.encryption.data.VaultConfig
import com.whmdg.mczj.tools.encryption.services.VaultService
import kotlinx.serialization.json.Json
import com.whmdg.mczj.tools.fileop.webdav.WebDavConnectionStatus
import com.whmdg.mczj.tools.fileop.webdav.WebDavAccountState
import com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient
import com.whmdg.mczj.tools.fileop.webdav.WebDavServerConfig
import com.whmdg.mczj.tools.fileop.webdav.WebDavServerStore
import com.whmdg.mczj.tools.ui.theme.LocalIsDarkMode
import com.whmdg.mczj.tools.ui.components.glowEffect
import com.whmdg.mczj.tools.util.FormatUtils
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.outlined.Settings
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import java.io.File

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

/** 待创建的云端保险箱信息 */
private data class PendingVaultInfo(
    val uuid: String,
    val vaultName: String,
    val configFile: java.io.File,  // 缓存的 vault_config.json 路径
    val syncDb: com.whmdg.mczj.tools.encryption.data.SyncDatabase,  // 已下载的同步数据库
    val stats: com.whmdg.mczj.tools.encryption.data.SyncStatsRow  // 云端统计数据
)

private data class CatalogSyncProgress(
    val phase: String,
    val current: Int = 0,
    val total: Int = 0,
    val completed: Boolean = false,
    val error: String? = null
)

private data class SyncSummary(
    val localExisting: List<String>,    // 本地已有的保险箱
    val cloudNew: List<String>,         // 从云端新增的保险箱
    val uploaded: List<String>          // 上传到云端的保险箱
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
    var syncErrorMessage by remember { mutableStateOf<String?>(null) }

    // 异常恢复弹窗状态
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var showRecoveryProgress by remember { mutableStateOf(false) }
    var recoveryVaultId by remember { mutableStateOf(0) }
    var recoveryVaultName by remember { mutableStateOf("") }
    var recoveryVaultDir by remember { mutableStateOf("") }
    var recoveryConfig by remember { mutableStateOf<com.whmdg.mczj.tools.fileop.webdav.WebDavServerConfig?>(null) }
    var showManualSyncConfirm by remember { mutableStateOf(false) }
    var catalogSyncProgress by remember { mutableStateOf<CatalogSyncProgress?>(null) }
    var syncRunning by remember { mutableStateOf(false) }
    var syncJob by remember { mutableStateOf<Job?>(null) }
    var showCancelSyncConfirm by remember { mutableStateOf(false) }
    var syncSummary by remember { mutableStateOf<SyncSummary?>(null) }
    var pendingVaults by remember { mutableStateOf<List<PendingVaultInfo>>(emptyList()) }
    var currentPendingIndex by remember { mutableStateOf(0) }
    var showCreateVaultDialog by remember { mutableStateOf(false) }
    var creatingVault by remember { mutableStateOf<PendingVaultInfo?>(null) }
    var selectedDirectory by remember { mutableStateOf<android.net.Uri?>(null) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var creationError by remember { mutableStateOf<String?>(null) }
    var useSaf by remember { mutableStateOf(false) }

    // UUID 迁移状态（简化）
    var migrationInProgress by remember { mutableStateOf(false) }
    var migrationProgress by remember { mutableStateOf("") }

    // 启动时检查并执行迁移
    LaunchedEffect(Unit) {
        val flagFile = com.whmdg.mczj.tools.AppDataPaths.vaultUuidMigrationFlag(context)
        if (!flagFile.exists()) {
            migrationInProgress = true
            try {
                withContext(Dispatchers.IO) {
                    val vaults = vaultService.vaults.toList()
                    if (vaults.isEmpty()) {
                        flagFile.apply { parentFile?.mkdirs() }.writeText("completed")
                        return@withContext
                    }

                    // 阶段 1: 补全 UUID 和 name
                    migrationProgress = "正在升级保险箱配置..."
                    val json = Json { ignoreUnknownKeys = true; prettyPrint = true; prettyPrintIndent = "    " }

                    for (vault in vaults) {
                        val vaultDir = VaultPaths.resolveVault(context, vault.location, vault.relativePath)
                        val configFile = File(vaultDir, "vault_config.json")
                        if (!configFile.exists()) continue

                        val config = json.decodeFromString<VaultConfig>(configFile.readText())
                        if (config.uuid.isNullOrBlank() || config.name.isNullOrBlank()) {
                            val newUuid = config.uuid?.takeIf { it.isNotBlank() }
                                ?: com.whmdg.mczj.tools.encryption.core.UuidGenerator.generate(
                                    config.salt, config.encDek, System.currentTimeMillis()
                                )
                            val updatedConfig = config.copy(uuid = newUuid, name = vault.name)
                            updatedConfig.saveWithBackup(context, vaultDir)

                            val updatedRecord = vault.copy(uuid = newUuid)
                            vaultService.updateVault(updatedRecord)
                            delay(10) // 避免时间戳冲突
                        }
                    }

                    vaultService.saveAndRefresh()

                    // 阶段 2: 上传到云端（如果有配置）
                    val webdavConfigs = WebDavServerStore.getAll(context)
                    if (webdavConfigs.isNotEmpty()) {
                        val webdavConfig = webdavConfigs.first()
                        migrationProgress = "正在同步到云端..."
                        val client = WebDavFileClient(webdavConfig)
                        for (vault in vaultService.vaults) {
                            val vaultDir = VaultPaths.resolveVault(context, vault.location, vault.relativePath)
                            val dbFile = File(vaultDir, "vault_sync.db")
                            val configFile = File(vaultDir, "vault_config.json")
                            if (dbFile.exists()) {
                                CloudVaultCatalogSync.uploadVaultDatabase(
                                    context, client, webdavConfig.relativePath, dbFile, configFile
                                )
                            }
                        }
                    }

                    // 创建标志文件
                    flagFile.apply { parentFile?.mkdirs() }.writeText("completed")
                }
                migrationInProgress = false
            } catch (e: Exception) {
                migrationProgress = "升级失败: ${e.message}"
                // 保持 migrationInProgress = true 阻止进入
            }
        }
    }

    // 目录选择器
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            selectedDirectory = uri
            showPasswordDialog = true
        } else {
            // 用户取消 → 跳过当前保险箱
            creatingVault?.configFile?.delete()
            creatingVault = null
            currentPendingIndex++
            if (currentPendingIndex < pendingVaults.size) {
                showCreateVaultDialog = true
            } else {
                pendingVaults = emptyList()
                currentPendingIndex = 0
            }
        }
    }

    /** 用户确认同步后，从云端扫描保险箱列表，恢复保险箱卡片与本地占位目录。 */
    suspend fun restoreCloudCatalog(
        config: WebDavServerConfig,
        onProgress: (CatalogSyncProgress) -> Unit = {}
    ): Triple<List<String>, List<String>, List<PendingVaultInfo>> {  // 返回 (本地已有, 云端新增, 待处理)
        onProgress(CatalogSyncProgress("正在连接云端"))
        val client = WebDavFileClient(config)
        val json = Json { ignoreUnknownKeys = true }

        onProgress(CatalogSyncProgress("正在扫描云端保险箱"))
        val cloudUuids = CloudVaultCatalogSync.listCloudVaults(client, config.relativePath)
        val total = cloudUuids.size
        val localExisting = mutableListOf<String>()
        val cloudNew = mutableListOf<String>()
        val pendingVaults = mutableListOf<PendingVaultInfo>()

        if (total == 0) {
            // 云端还没有任何保险箱时，卡片视图应反映空的云端状态。
            syncItems.clear()
            processedVaultIds.clear()
            CloudSyncStore.save(context, emptyList())
            return Triple(localExisting, cloudNew, pendingVaults)
        }

        for ((index, uuid) in cloudUuids.withIndex()) {
            onProgress(CatalogSyncProgress("正在恢复保险箱同步数据库：${uuid.take(8)}...", index, total))

            // 下载同步数据库和配置文件
            val syncDb = com.whmdg.mczj.tools.encryption.data.SyncDatabase.getInstance(context, uuid)
            val (success, configFile, vaultName) = CloudVaultCatalogSync.downloadVaultDatabase(
                context, client, config.relativePath, uuid, syncDb
            )

            if (!success || configFile == null) {
                throw IllegalStateException("保险箱「${uuid}」同步数据库恢复失败")
            }

            // 检查本地是否已存在该 UUID 的保险箱
            val existing = vaultService.vaults.find { it.uuid == uuid }
            if (existing != null) {
                localExisting.add(existing.name)
                // 更新卡片
                val itemId = "vault_${existing.id}"
                if (syncItems.none { it.id == itemId }) {
                    val stats = syncDb.getStats()
                    syncItems.add(
                        CloudSyncItem(
                            id = itemId,
                            vaultId = existing.id,
                            vaultName = existing.name,
                            type = "保险箱",
                            vaultSize = stats.localSize,
                            lastSyncTime = stats.lastUpdate ?: "未同步",
                            cloudSize = stats.cloudSize,
                            diffFileCount = stats.diffCount,
                            webdavPath = config.relativePath,
                            localFileCount = stats.localFileCount,
                            cloudFileCount = stats.cloudFileCount
                        )
                    )
                }
                processedVaultIds.add(existing.id)
            } else {
                cloudNew.add(vaultName)
                // 构建待处理信息
                val stats = syncDb.getStats()
                pendingVaults.add(PendingVaultInfo(uuid, vaultName, configFile, syncDb, stats))
            }

            onProgress(CatalogSyncProgress("正在处理保险箱：${vaultName}", index + 1, total))
        }

        CloudSyncStore.save(context, syncItems.toList())
        onProgress(CatalogSyncProgress("同步完成", total, total, completed = true))
        return Triple(localExisting, cloudNew, pendingVaults)
    }

    suspend fun publishCloudCatalog(config: WebDavServerConfig): List<String> {
        val localVaults = vaultService.vaults.toList()
        // 不再需要上传 vault_catalog.json，保险箱列表通过扫描 .7z 文件获取
        return localVaults.map { it.name }
    }

    suspend fun runCatalogSync(config: WebDavServerConfig) {
        try {
            val (localExisting, cloudNew, pendingVaultsList) = restoreCloudCatalog(config) { catalogSyncProgress = it }
            catalogSyncProgress = CatalogSyncProgress("正在上传保险箱清单")
            val uploaded = publishCloudCatalog(config)
            catalogSyncProgress = CatalogSyncProgress("同步完成", completed = true)
            syncSummary = SyncSummary(
                localExisting = localExisting,
                cloudNew = cloudNew,
                uploaded = uploaded
            )
            pendingVaults = pendingVaultsList
        } catch (e: TimeoutCancellationException) {
            catalogSyncProgress = null
            syncErrorMessage = "同步超时：云端在两分钟内未响应，请检查网络后重试。"
        } catch (_: CancellationException) {
            // 取消时临时下载文件会由各同步步骤的 finally 删除。
            catalogSyncProgress = null
        } catch (e: java.io.IOException) {
            catalogSyncProgress = null
            syncErrorMessage = "网络同步失败：${e.message ?: "连接中断"}"
        } catch (e: IllegalStateException) {
            catalogSyncProgress = null
            syncErrorMessage = e.message ?: "同步数据不完整"
        } catch (e: Throwable) {
            catalogSyncProgress = null
            com.whmdg.mczj.tools.util.DiagnosticLog.exportCrashReport(
                context, e, "云盘同步发生未预期错误"
            )
            confirmError = e
        } finally {
            syncRunning = false
            syncJob = null
        }
    }

    fun startCatalogSync(config: WebDavServerConfig) {
        if (syncJob?.isActive == true) return
        syncRunning = true
        syncErrorMessage = null
        catalogSyncProgress = CatalogSyncProgress("准备同步")
        syncJob = scope.launch { runCatalogSync(config) }
    }

    fun cancelCatalogSync() {
        syncJob?.cancel()
        syncJob = null
        syncRunning = false
        catalogSyncProgress = null
    }

    // 从持久化存储加载同步项 + 刷新保险箱大小 + 检测 WebDAV 连接状态
    LaunchedEffect(Unit) {
        val saved = CloudSyncStore.load(context)
        if (saved.isNotEmpty()) {
            // 刷新保险箱类型的本地大小和文件数
            val refreshed = saved.map { item ->
                if (item.type == "保险箱" && item.vaultId > 0) {
                    val vault = vaultService.getVault(item.vaultId)
                    if (vault != null) {
                        val syncDb = com.whmdg.mczj.tools.encryption.data.SyncDatabase.getInstance(context, item.vaultName)
                        val stats = syncDb.getStats()
                        item.copy(
                            vaultSize = stats.localSize,
                            cloudSize = stats.cloudSize,
                            diffFileCount = stats.diffCount,
                            lastSyncTime = stats.lastUpdate ?: item.lastSyncTime,
                            localFileCount = stats.localFileCount,
                            cloudFileCount = stats.cloudFileCount
                        )
                    } else item
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
            val syncDb = com.whmdg.mczj.tools.encryption.data.SyncDatabase.getInstance(context, vault.name)
            val stats = syncDb.getStats()

            // 检查是否存在同名的占位卡片（vaultId=0）
            val pendingIndex = syncItems.indexOfFirst { it.vaultName == vault.name && it.vaultId == 0 }
            if (pendingIndex >= 0) {
                // 升级占位卡片为正式卡片
                syncItems[pendingIndex] = syncItems[pendingIndex].copy(
                    id = "vault_${vault.id}",
                    vaultId = vault.id,
                    vaultSize = stats.localSize,
                    localFileCount = stats.localFileCount,
                    cloudSize = stats.cloudSize,
                    diffFileCount = stats.diffCount,
                    lastSyncTime = stats.lastUpdate ?: syncItems[pendingIndex].lastSyncTime
                )
            } else {
                // 新增卡片
                val item = CloudSyncItem(
                    id = "vault_${vault.id}",
                    vaultId = vault.id,
                    vaultName = vault.name,
                    type = "保险箱",
                    vaultSize = stats.localSize,
                    lastSyncTime = stats.lastUpdate ?: "未同步",
                    cloudSize = stats.cloudSize,
                    diffFileCount = stats.diffCount,
                    localFileCount = stats.localFileCount,
                    cloudFileCount = stats.cloudFileCount
                )
                syncItems.add(item)
            }
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
                    onClick = { showManualSyncConfirm = true },
                    enabled = accountState.status == WebDavConnectionStatus.LOGGED_IN && !syncRunning,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "同步云端",
                        modifier = Modifier.size(18.dp),
                        tint = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
                    )
                }
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
                var showConcurrencyDialog by remember { mutableStateOf(false) }
                var showDiffDialog by remember { mutableStateOf(false) }
                var diffResult by remember { mutableStateOf<DiffScanResult?>(null) }
                var showDeleteWarning by remember { mutableStateOf<CloudSyncItem?>(null) }
                var showDeleteOptions by remember { mutableStateOf<CloudSyncItem?>(null) }
                var deleteScope by remember { mutableStateOf<String?>(null) }
                var showDeleteProgress by remember { mutableStateOf(false) }
                var deleteLocalProgress by remember { mutableFloatStateOf(0f) }
                var deleteCloudProgress by remember { mutableFloatStateOf(0f) }
                var deletePhase by remember { mutableStateOf("") }
                var deleteComplete by remember { mutableStateOf(false) }
                var activeDeletingScope by remember { mutableStateOf<String?>(null) }
                val currentConcurrency = remember {
                    context.getSharedPreferences("cloud_sync_settings", Context.MODE_PRIVATE)
                        .getInt("max_concurrency", 3)
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(syncItems, key = { it.id }) { item ->
                            CloudSyncCard(
                                item = item,
                                onClick = { showConfirmDialog = item },
                                onConcurrencyChange = { showConcurrencyDialog = true },
                                onDiffRefresh = { showDiffDialog = true },
                                onDeleteVault = { showDeleteWarning = item }
                            )
                        }
                    }

                    // 并发数滑动条对话框
                    if (showConcurrencyDialog) {
                        ConcurrencySliderDialog(
                            currentValue = currentConcurrency,
                            onConfirm = { value ->
                                context.getSharedPreferences("cloud_sync_settings", Context.MODE_PRIVATE)
                                    .edit().putInt("max_concurrency", value).apply()
                                showConcurrencyDialog = false
                            },
                            onDismiss = { showConcurrencyDialog = false }
                        )
                    }

                    // 差异文件扫描对话框
                    if (showDiffDialog) {
                        val vaultItem = syncItems.firstOrNull { it.type == "保险箱" }
                        val vaultRecord = vaultItem?.let { vaultService.getVault(it.vaultId) }
                        DiffScanDialog(
                            context = context,
                            vaultDir = vaultRecord?.relativePath ?: "",
                            vaultName = vaultItem?.vaultName ?: "",
                            vaultId = vaultItem?.vaultId ?: 0,
                            onComplete = { result ->
                                showDiffDialog = false
                                diffResult = result
                            }
                        )
                    }

                    // 差异结果对话框
                    if (diffResult != null) {
                        DiffResultDialog(
                            result = diffResult!!,
                            onConfirm = {
                                // 统计已持久化，直接重新加载卡片
                                val vaultId = syncItems.firstOrNull { it.type == "保险箱" }?.vaultId ?: 0
                                val idx = syncItems.indexOfFirst { it.id == "vault_$vaultId" }
                                if (idx >= 0) {
                                    val vaultName = syncItems[idx].vaultName
                                    val syncDb = com.whmdg.mczj.tools.encryption.data.SyncDatabase.getInstance(context, vaultName)
                                    val stats = syncDb.getStats()
                                    syncItems[idx] = syncItems[idx].copy(
                                        diffFileCount = stats.diffCount,
                                        localFileCount = stats.localFileCount,
                                        cloudFileCount = stats.cloudFileCount,
                                        vaultSize = stats.localSize,
                                        cloudSize = stats.cloudSize,
                                        lastSyncTime = stats.lastUpdate ?: syncItems[idx].lastSyncTime
                                    )
                                    CloudSyncStore.save(context, syncItems.toList())
                                }
                                diffResult = null
                            },
                            confirmError = confirmError
                        )
                    }

                // 删除云盘警告弹窗（第一次确认）
                showDeleteWarning?.let { item ->
                    AlertDialog(
                        onDismissRequest = { showDeleteWarning = null },
                        title = { Text("删除云盘") },
                        text = { Text("确定要删除云盘「${item.vaultName}」吗？此操作将根据您的选择删除本地或云端数据。") },
                        confirmButton = {
                            TextButton(onClick = {
                                showDeleteWarning = null
                                showDeleteOptions = item
                            }) { Text("确认") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteWarning = null }) { Text("取消") }
                        }
                    )
                }

                // 删除云盘选项弹窗（第二次确认，选择删除范围）
                showDeleteOptions?.let { item ->
                    AlertDialog(
                        onDismissRequest = { showDeleteOptions = null },
                        title = { Text("选择删除范围") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("请选择要删除的范围：", fontSize = 14.sp)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { deleteScope = "local" }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = deleteScope == "local",
                                        onClick = { deleteScope = "local" }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("仅删除本地", fontSize = 14.sp)
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { deleteScope = "both" }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = deleteScope == "both",
                                        onClick = { deleteScope = "both" }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("本地和云端一起删除", fontSize = 14.sp)
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { deleteScope = "cloud" }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = deleteScope == "cloud",
                                        onClick = { deleteScope = "cloud" }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("仅删除云端", fontSize = 14.sp)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = deleteScope != null,
                                onClick = {
                                    val selectedScope = deleteScope ?: return@TextButton
                                    val itemToDelete = item
                                    showDeleteOptions = null
                                    deleteScope = null

                                    // 显示删除进度弹窗
                                    showDeleteProgress = true
                                    activeDeletingScope = selectedScope
                                    deleteLocalProgress = 0f
                                    deleteCloudProgress = 0f
                                    deleteComplete = false

                                    // 执行删除操作
                                    scope.launch {
                                        try {
                                            when (selectedScope) {
                                                "local" -> {
                                                    // 仅删除本地（保留保险箱记录，删除文件和 Local DB）
                                                    deletePhase = "正在删除本地数据"
                                                    if (itemToDelete.type == "保险箱") {
                                                        withContext(Dispatchers.IO) {
                                                            deleteLocalProgress = 0.2f
                                                            // 删除保险箱文件
                                                            vaultService.removeVault(itemToDelete.vaultId, deleteFiles = true)
                                                            deleteLocalProgress = 0.6f
                                                            // 清理 Local DB（保留 Cloud DB）
                                                            val syncDb = com.whmdg.mczj.tools.encryption.data.SyncDatabase.getInstance(context, itemToDelete.vaultName)
                                                            syncDb.writableDatabase.delete("local_entries", null, null)
                                                            deleteLocalProgress = 1f
                                                        }
                                                    }
                                                    syncItems.removeIf { it.id == itemToDelete.id }
                                                    processedVaultIds.remove(itemToDelete.vaultId)
                                                    CloudSyncStore.save(context, syncItems.toList())
                                                    deleteComplete = true
                                                    deletePhase = "本地数据删除完成"
                                                }
                                                "cloud" -> {
                                                    // 仅删除云端（清理 Cloud DB，保留 Local DB）
                                                    deletePhase = "正在删除云端数据"
                                                    val config = accountState.config
                                                    if (config != null && itemToDelete.type == "保险箱") {
                                                        withContext(Dispatchers.IO) {
                                                            val client = WebDavFileClient(config)
                                                            val vaultCloudPath = "${config.relativePath}/${itemToDelete.vaultName}"
                                                            deleteCloudProgress = 0.2f
                                                            client.delete(vaultCloudPath)
                                                            deleteCloudProgress = 0.5f
                                                            // 从云端清单中移除
                                                            publishCloudCatalog(config)
                                                            deleteCloudProgress = 0.8f
                                                            // 清理 Cloud DB（保留 Local DB）
                                                            val syncDb = com.whmdg.mczj.tools.encryption.data.SyncDatabase.getInstance(context, itemToDelete.vaultName)
                                                            syncDb.writableDatabase.delete("cloud_entries", null, null)
                                                            deleteCloudProgress = 1f
                                                        }
                                                    }
                                                    deleteComplete = true
                                                    deletePhase = "云端数据删除完成"
                                                }
                                                "both" -> {
                                                    // 本地和云端一起删除（删除所有，包括整个同步数据库）
                                                    if (itemToDelete.type == "保险箱") {
                                                        // 删除本地
                                                        deletePhase = "正在删除本地数据"
                                                        withContext(Dispatchers.IO) {
                                                            deleteLocalProgress = 0.3f
                                                            vaultService.removeVault(itemToDelete.vaultId, deleteFiles = true)
                                                            deleteLocalProgress = 1f
                                                        }

                                                        // 删除云端
                                                        deletePhase = "正在删除云端数据"
                                                        val config = accountState.config
                                                        if (config != null) {
                                                            withContext(Dispatchers.IO) {
                                                                val client = WebDavFileClient(config)
                                                                val vaultCloudPath = "${config.relativePath}/${itemToDelete.vaultName}"
                                                                deleteCloudProgress = 0.3f
                                                                client.delete(vaultCloudPath)
                                                                deleteCloudProgress = 0.6f
                                                                // 更新云端清单
                                                                publishCloudCatalog(config)
                                                                deleteCloudProgress = 1f
                                                            }
                                                        }

                                                        // 清理整个同步数据库目录
                                                        deletePhase = "正在清理同步数据"
                                                        withContext(Dispatchers.IO) {
                                                            val syncDir = File(com.whmdg.mczj.tools.AppDataPaths.encryption(context), "云盘同步/${itemToDelete.vaultName}")
                                                            if (syncDir.exists()) {
                                                                syncDir.deleteRecursively()
                                                            }
                                                        }
                                                    }
                                                    syncItems.removeIf { it.id == itemToDelete.id }
                                                    processedVaultIds.remove(itemToDelete.vaultId)
                                                    CloudSyncStore.save(context, syncItems.toList())
                                                    deleteComplete = true
                                                    deletePhase = "本地和云端数据删除完成"
                                                }
                                            }
                                        } catch (e: Exception) {
                                            deleteComplete = true
                                            deletePhase = "删除失败：${e.message}"
                                            com.whmdg.mczj.tools.util.DiagnosticLog.exportCrashReport(
                                                context, e, "云盘删除操作失败"
                                            )
                                        }
                                    }
                                }
                            ) { Text("确认") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showDeleteOptions = null
                                deleteScope = null
                            }) { Text("取消") }
                        }
                    )
                }

                // 删除进度弹窗
                if (showDeleteProgress) {
                    DeleteProgressDialog(
                        phase = deletePhase,
                        localProgress = deleteLocalProgress,
                        cloudProgress = deleteCloudProgress,
                        showLocalProgress = activeDeletingScope == "local" || activeDeletingScope == "both",
                        showCloudProgress = activeDeletingScope == "cloud" || activeDeletingScope == "both",
                        isComplete = deleteComplete,
                        onDismiss = {
                            if (deleteComplete) {
                                showDeleteProgress = false
                                deleteLocalProgress = 0f
                                deleteCloudProgress = 0f
                                activeDeletingScope = null
                            }
                        }
                    )
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

                                // 检查是否是云端新增的占位卡片
                                if (item.vaultId == 0) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "此保险箱仅存在于云端，请先在「保险箱」标签页中创建同名保险箱「${item.vaultName}」并设置密码",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    return@TextButton
                                }

                                val vaultRecord = vaultService.getVault(item.vaultId)
                                if (vaultRecord == null) {
                                    android.widget.Toast.makeText(context, "保险箱不存在，请重新添加", android.widget.Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                val vaultDir = com.whmdg.mczj.tools.encryption.data.VaultPaths.resolveVault(
                                    context, vaultRecord.location, vaultRecord.relativePath
                                ).absolutePath

                                // 检查 lock 文件
                                val lockFile = com.whmdg.mczj.tools.AppDataPaths.syncLock(context, item.vaultId)
                                if (lockFile.exists()) {
                                    // 存在 lock，弹出异常恢复弹窗
                                    recoveryVaultId = item.vaultId
                                    recoveryVaultName = item.vaultName
                                    recoveryVaultDir = vaultDir
                                    recoveryConfig = config
                                    showRecoveryDialog = true
                                } else {
                                    onNavigateToFileManager(config, vaultDir, item.vaultId, item.vaultName)
                                }
                            }) { Text("确认") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showConfirmDialog = null }) { Text("取消") }
                        }
                    )
                }

                // 异常恢复确认弹窗
                if (showRecoveryDialog) {
                    AlertDialog(
                        onDismissRequest = { showRecoveryDialog = false },
                        title = { Text("异常退出检测") },
                        text = { Text("检测到上次上传异常退出，云端列表可能未更新。是否同步云端列表？") },
                        confirmButton = {
                            TextButton(onClick = {
                                showRecoveryDialog = false
                                showRecoveryProgress = true
                                scope.launch {
                                    val controller = com.whmdg.mczj.tools.ui.filemanager.CloudPaneController(
                                        context = context,
                                        scope = scope,
                                        webdavConfig = recoveryConfig!!,
                                        vaultDir = recoveryVaultDir,
                                        vaultId = recoveryVaultId,
                                        vaultName = recoveryVaultName,
                                        folderSizeDb = { com.whmdg.mczj.tools.encryption.data.FolderSizeDb() },
                                        recalculateFolderSize = {}
                                    )
                                    val dbUploaded = controller.uploadCloudDb()
                                    com.whmdg.mczj.tools.AppDataPaths.syncLock(context, recoveryVaultId).delete()
                                    controller.dispose()
                                    showRecoveryProgress = false
                                    onNavigateToFileManager(recoveryConfig!!, recoveryVaultDir, recoveryVaultId, recoveryVaultName)
                                }
                            }) { Text("同步") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showRecoveryDialog = false
                                com.whmdg.mczj.tools.AppDataPaths.syncLock(context, recoveryVaultId).delete()
                                onNavigateToFileManager(recoveryConfig!!, recoveryVaultDir, recoveryVaultId, recoveryVaultName)
                            }) { Text("跳过") }
                        }
                    )
                }

                // 恢复进度弹窗
                if (showRecoveryProgress) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("同步中") },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("正在同步云端列表...")
                            }
                        },
                        confirmButton = {},
                        dismissButton = {}
                    )
                }

            }
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

        // ── 同步确认对话框 ──
        if (showManualSyncConfirm) {
            AlertDialog(
                onDismissRequest = { showManualSyncConfirm = false },
                title = { Text("同步云端数据") },
                text = { Text("将从云端同步保险箱清单和同步数据库，可能需要一些时间。是否继续？") },
                confirmButton = {
                    TextButton(onClick = {
                        showManualSyncConfirm = false
                        accountState.config?.let(::startCatalogSync)
                    }) { Text("开始同步") }
                },
                dismissButton = { TextButton(onClick = { showManualSyncConfirm = false }) { Text("取消") } }
            )
        }

        if (showCancelSyncConfirm) {
            AlertDialog(
                onDismissRequest = { showCancelSyncConfirm = false },
                title = { Text("取消云端同步") },
                text = { Text("将停止当前同步并清理临时下载文件，已恢复的数据会保留。是否取消？") },
                confirmButton = {
                    TextButton(onClick = {
                        showCancelSyncConfirm = false
                        cancelCatalogSync()
                    }) { Text("确认取消") }
                },
                dismissButton = { TextButton(onClick = { showCancelSyncConfirm = false }) { Text("继续同步") } }
            )
        }

        syncErrorMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { syncErrorMessage = null },
                title = { Text("云端同步失败") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { syncErrorMessage = null }) { Text("关闭") } }
            )
        }

        syncSummary?.let { summary ->
            AlertDialog(
                onDismissRequest = { syncSummary = null },
                title = { Text("云端同步完成") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (summary.cloudNew.isNotEmpty()) {
                            Text("从云端新增 ${summary.cloudNew.size} 个保险箱：", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                summary.cloudNew.forEach { name ->
                                    Text("• $name", fontSize = 13.sp)
                                }
                            }
                        }
                        if (summary.localExisting.isNotEmpty()) {
                            Text("本地已有 ${summary.localExisting.size} 个保险箱：", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                summary.localExisting.forEach { name ->
                                    Text("• $name", fontSize = 13.sp)
                                }
                            }
                        }
                        if (summary.uploaded.isNotEmpty()) {
                            Text("已上传 ${summary.uploaded.size} 个保险箱到云端", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        if (summary.cloudNew.isEmpty() && summary.localExisting.isEmpty()) {
                            Text("云端暂无保险箱", fontSize = 13.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        syncSummary = null
                        // 如果有待处理的保险箱，触发逐个询问流程
                        if (pendingVaults.isNotEmpty()) {
                            currentPendingIndex = 0
                            showCreateVaultDialog = true
                        }
                    }) { Text("关闭") }
                }
            )
        }

        catalogSyncProgress?.let { progress ->
            AlertDialog(
                onDismissRequest = { if (!syncRunning) catalogSyncProgress = null },
                title = { Text(if (progress.error == null) "云端同步" else "同步失败") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!progress.completed) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(progress.phase)
                            }
                        } else {
                            Text(progress.error ?: progress.phase)
                        }
                        if (progress.total > 0) {
                            LinearProgressIndicator(
                                progress = { progress.current.toFloat() / progress.total.toFloat() },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("${progress.current}/${progress.total} 个保险箱")
                        }
                    }
                },
                confirmButton = {
                    if (!syncRunning) {
                        TextButton(onClick = { catalogSyncProgress = null }) { Text("关闭") }
                    }
                },
                dismissButton = {
                    if (syncRunning) {
                        TextButton(onClick = { showCancelSyncConfirm = true }) { Text("取消同步") }
                    }
                }
            )
        }

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
                        if (ok) {
                            startCatalogSync(config)
                        } else {
                            syncErrorMessage = "无法连接云端，请检查服务器地址、账号、密码和网络后重试。"
                        }
                    }
                }
            )
        }

        // 逐个询问创建保险箱
        if (showCreateVaultDialog && currentPendingIndex < pendingVaults.size) {
            val pending = pendingVaults[currentPendingIndex]

            AlertDialog(
                onDismissRequest = { /* 不允许点击外部关闭 */ },
                title = { Text("创建保险箱「${pending.vaultName}」") },
                text = {
                    Text("云端检测到此保险箱，需要在本地创建以访问加密文件。\n\n" +
                         "云端文件数：${pending.stats.cloudFileCount}\n" +
                         "云端大小：${FormatUtils.formatBytes(pending.stats.cloudSize)}")
                },
                confirmButton = {
                    TextButton(onClick = {
                        creatingVault = pending
                        showCreateVaultDialog = false

                        // 检查权限并选择模式
                        useSaf = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            !Environment.isExternalStorageManager()
                        } else {
                            false
                        }

                        folderPicker.launch(null)
                    }) { Text("创建") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        // 跳过：删除临时文件
                        pending.configFile.delete()
                        currentPendingIndex++
                        if (currentPendingIndex < pendingVaults.size) {
                            showCreateVaultDialog = true
                        } else {
                            showCreateVaultDialog = false
                            pendingVaults = emptyList()
                            currentPendingIndex = 0
                        }
                    }) { Text("跳过") }
                }
            )
        }

        // 密码输入弹窗
        if (showPasswordDialog && creatingVault != null && selectedDirectory != null) {
            var password by remember { mutableStateOf("") }
            var isCreating by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { /* 不允许点击外部关闭 */ },
                title = { Text("验证密码") },
                text = {
                    Column {
                        Text("请输入保险箱密码以验证并导入")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("密码") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                        if (creationError != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(creationError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (password.isBlank()) {
                                creationError = "密码不能为空"
                                return@TextButton
                            }

                            isCreating = true
                            creationError = null
                            scope.launch {
                                try {
                                    createVaultFromPending(
                                        context, vaultService,
                                        creatingVault!!, selectedDirectory!!, password, useSaf,
                                        syncItems, accountState.config?.relativePath ?: ""
                                    )

                                    // 成功 → 清理并移动到下一个
                                    showPasswordDialog = false
                                    creatingVault = null
                                    selectedDirectory = null
                                    password = ""
                                    creationError = null
                                    currentPendingIndex++
                                    if (currentPendingIndex < pendingVaults.size) {
                                        showCreateVaultDialog = true
                                    } else {
                                        pendingVaults = emptyList()
                                        currentPendingIndex = 0
                                    }
                                } catch (e: Exception) {
                                    creationError = e.message ?: "创建失败"
                                    isCreating = false
                                }
                            }
                        },
                        enabled = !isCreating
                    ) { Text(if (isCreating) "创建中..." else "确认") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showPasswordDialog = false
                            creatingVault?.configFile?.delete()
                            creatingVault = null
                            selectedDirectory = null
                            password = ""
                            creationError = null
                            currentPendingIndex++
                            if (currentPendingIndex < pendingVaults.size) {
                                showCreateVaultDialog = true
                            } else {
                                pendingVaults = emptyList()
                                currentPendingIndex = 0
                            }
                        },
                        enabled = !isCreating
                    ) { Text("跳过") }
                }
            )
        }
    }

    // UUID 迁移阻塞弹窗
    if (migrationInProgress) {
        MigrationBlockingDialog(progress = migrationProgress)
    }

    com.whmdg.mczj.tools.ui.ErrorDialog(
        error = confirmError,
        onDismiss = { confirmError = null }
    )
}

// ── WebDAV 设置弹窗 ──
@OptIn(ExperimentalMaterial3Api::class)
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
    var authType by remember { mutableStateOf(initialConfig?.authType ?: "password") }
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

                // 认证类型
                var authExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = authExpanded,
                    onExpandedChange = { authExpanded = it }
                ) {
                    val authLabel = when (authType) {
                        "password" -> "密码"
                        "token" -> "访问令牌"
                        else -> "无"
                    }
                    OutlinedTextField(
                        value = authLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("认证类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(authExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = authExpanded,
                        onDismissRequest = { authExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("密码") },
                            onClick = { authType = "password"; authExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("访问令牌") },
                            onClick = { authType = "token"; authExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("无") },
                            onClick = { authType = "none"; authExpanded = false }
                        )
                    }
                }

                // 账户（仅密码认证需要）
                AnimatedVisibility(authType == "password") {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("账户") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 密码/令牌
                AnimatedVisibility(authType != "none") {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(if (authType == "token") "访问令牌" else "密码") },
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
                }

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
                            authType = authType,
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
                    enabled = !isTesting && url.isNotBlank() && (authType != "password" || username.isNotBlank()),
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
                            authType = authType,
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
    onClick: () -> Unit = {},
    onConcurrencyChange: (() -> Unit)? = null,
    onDiffRefresh: (() -> Unit)? = null,
    onDeleteVault: (() -> Unit)? = null
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
                        // 设置按钮
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "设置",
                                    tint = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("并发数调整") },
                                    onClick = {
                                        showMenu = false
                                        onConcurrencyChange?.invoke()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("刷新差异文件") },
                                    onClick = {
                                        showMenu = false
                                        onDiffRefresh?.invoke()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除云盘") },
                                    onClick = {
                                        showMenu = false
                                        onDeleteVault?.invoke()
                                    }
                                )
                            }
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

// ── 并发数滑动条对话框 ──

@Composable
private fun ConcurrencySliderDialog(
    currentValue: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val isDarkMode = LocalIsDarkMode.current
    val cardColor = if (isDarkMode) Color(0xFF1E293B) else Color.White
    val textColor = if (isDarkMode) Color(0xFFE2E8F0) else Color(0xFF1E293B)
    val subTextColor = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

    var sliderValue by remember { mutableFloatStateOf(currentValue.toFloat()) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    text = "并发数调整",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "同时上传文件数量：${sliderValue.toInt()}",
                    fontSize = 14.sp,
                    color = subTextColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 1f..10f,
                    steps = 8,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF3B82F6),
                        activeTrackColor = Color(0xFF3B82F6)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1", fontSize = 11.sp, color = subTextColor)
                    Text("10", fontSize = 11.sp, color = subTextColor)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = subTextColor)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(sliderValue.toInt()) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("确认", color = Color.White)
                    }
                }
            }
        }
    }
}

// ── 差异文件扫描对话框 ──

@Composable
private fun DiffScanDialog(
    context: Context,
    vaultDir: String,
    vaultName: String,
    vaultId: Int,
    onComplete: (DiffScanResult) -> Unit
) {
    val isDarkMode = LocalIsDarkMode.current
    val cardColor = if (isDarkMode) Color(0xFF1E293B) else Color.White
    val textColor = if (isDarkMode) Color(0xFFE2E8F0) else Color(0xFF1E293B)
    val subTextColor = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

    var step1Progress by remember { mutableFloatStateOf(0f) }
    var step2Progress by remember { mutableFloatStateOf(0f) }
    var step3Progress by remember { mutableFloatStateOf(0f) }
    var step1Text by remember { mutableStateOf("扫描本地文件") }
    var step2Text by remember { mutableStateOf("检查云端数据库") }
    var step3Text by remember { mutableStateOf("计算差异") }

    // 三步扫描流程
    LaunchedEffect(Unit) {
        val vaultDirFile = java.io.File(vaultDir)
        val syncDb = com.whmdg.mczj.tools.encryption.data.SyncDatabase.getInstance(context, vaultName)
        val excludedFiles = setOf(
            "vault_config.json",
            "vault_config.backup.json",
            "vault_sync_index.json",
            "name_mappings.json",
            "folder_sizes.json"
        )

        try {
            // ── 第一步：扫描本地文件，更新 local_entries ──
            step1Progress = 0.1f
            step1Text = "扫描本地文件 (0/0)"

            // 原子进度变量（多线程安全）
            val progressState = AtomicInteger(0)
            val totalState = AtomicInteger(0)
            val updatedState = AtomicInteger(0)

            // 后台工作协程：执行文件扫描
            val step1Job = launch(Dispatchers.IO) {
                val localFiles = mutableListOf<java.io.File>()
                if (vaultDirFile.exists()) {
                    vaultDirFile.walkTopDown()
                        .filter { it.isFile && it.name !in excludedFiles }
                        .forEach { localFiles.add(it) }
                }

                totalState.set(localFiles.size)

                for (file in localFiles) {
                    ensureActive()  // 检查取消

                    val relPath = "/" + file.relativeTo(vaultDirFile).path.replace('\\', '/')
                    val currentModified = java.time.Instant.ofEpochMilli(file.lastModified()).toString()
                    val currentSize = file.length()  // 使用加密文件的实际大小

                    val existingEntry = syncDb.getEntry("local_entries", relPath)

                    if (existingEntry == null) {
                        // 新文件：插入 PENDING
                        syncDb.upsertEntry("local_entries", com.whmdg.mczj.tools.encryption.data.SyncEntryRow(
                            path = relPath,
                            size = currentSize,
                            uploadedSize = 0,
                            lastModified = currentModified,
                            md5 = null,
                            cloudHash = null,
                            status = com.whmdg.mczj.tools.encryption.data.SyncStatus.PENDING,
                            lastSyncTime = null,
                            failReason = null
                        ))
                        updatedState.incrementAndGet()
                    } else if (existingEntry.size != currentSize || existingEntry.lastModified != currentModified) {
                        // 文件变化：重置为 PENDING，清空 MD5 和 uploadedSize
                        syncDb.updateEntry("local_entries", relPath) { row ->
                            row.copy(
                                size = currentSize,
                                uploadedSize = 0,
                                lastModified = currentModified,
                                md5 = null,
                                status = com.whmdg.mczj.tools.encryption.data.SyncStatus.PENDING,
                                failReason = null
                            )
                        }
                        updatedState.incrementAndGet()
                    } else if (existingEntry.md5 == null) {
                        // 时间戳和大小都没变，但 MD5 缺失：计算 MD5
                        val md5 = calculateMd5(file)
                        syncDb.updateMd5("local_entries", relPath, md5)
                        updatedState.incrementAndGet()
                    }

                    progressState.incrementAndGet()
                }
            }

            // 前台 UI 更新协程：每 200ms 读取进度
            val step1UiJob = launch(Dispatchers.Main) {
                while (step1Job.isActive) {
                    val current = progressState.get()
                    val total = totalState.get()
                    val updated = updatedState.get()

                    if (total > 0) {
                        step1Progress = 0.1f + (current.toFloat() / total * 0.9f)
                        step1Text = "扫描本地文件 ($current/$total, 更新 $updated 个)"
                    }

                    delay(200)
                }

                // 最后同步一次（确保 100%）
                val finalCurrent = progressState.get()
                val finalTotal = totalState.get()
                val finalUpdated = updatedState.get()
                step1Progress = 1f
                step1Text = "扫描本地文件完成 (共 $finalTotal 个，更新 $finalUpdated 个)"
            }

            // 等待两个协程都完成
            step1Job.join()
            step1UiJob.join()

            // ── 第二步：检查并同步云端 Cloud DB ──
            withContext(Dispatchers.IO) {
                step2Progress = 0.1f
                step2Text = "检查云端数据库元数据"

                val vaultService = com.whmdg.mczj.tools.encryption.services.VaultService(context)
                vaultService.load()
                val vaultRecord = vaultService.vaults.find { it.id == vaultId }
                val webdavConfig = com.whmdg.mczj.tools.fileop.webdav.WebDavServerStore.getAll(context).firstOrNull()

                if (webdavConfig != null && vaultRecord != null) {
                    val webdavClient = com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient(webdavConfig)

                    val remotePath = webdavConfig.relativePath.trimEnd('/').let { base ->
                        if (base.isEmpty()) "/.sync_meta/${vaultName}_vault_sync.db.7z"
                        else "$base/.sync_meta/${vaultName}_vault_sync.db.7z"
                    }
                    val metaFile = java.io.File(com.whmdg.mczj.tools.AppDataPaths.cloudDbMeta(context), "${vaultName}_meta.json")

                    val needsSync = try {
                        val remoteMeta = webdavClient.getFileMetadata(remotePath)
                        if (remoteMeta == null) {
                            withContext(Dispatchers.Main) { step2Text = "云端数据库不存在" }
                            false
                        } else if (!metaFile.exists()) {
                            withContext(Dispatchers.Main) { step2Text = "本地无缓存，需要同步" }
                            true
                        } else {
                            val localMeta = org.json.JSONObject(metaFile.readText())
                            val changed = remoteMeta.size != localMeta.getLong("size") ||
                                         remoteMeta.lastModified != localMeta.getLong("lastModified")
                            withContext(Dispatchers.Main) {
                                step2Text = if (changed) "云端数据库已更新，需要同步" else "云端数据库未变化"
                            }
                            changed
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { step2Text = "检查失败: ${e.message}" }
                        false
                    }

                    if (needsSync) {
                        withContext(Dispatchers.Main) {
                            step2Progress = 0.3f
                            step2Text = "下载云端数据库"
                        }

                        try {
                            val zipFile = java.io.File(context.cacheDir, "${vaultName}_diff_scan.db.7z")
                            webdavClient.downloadFile(remotePath, zipFile) { }

                            withContext(Dispatchers.Main) {
                                step2Progress = 0.6f
                                step2Text = "解压云端数据库"
                            }

                            val extractDir = java.io.File(context.cacheDir, "diff_scan_${vaultName}")
                            extractDir.mkdirs()
                            com.whmdg.mczj.tools.util.JBindingClient.extractAll(
                                archivePath = zipFile.absolutePath,
                                outputDir = extractDir.absolutePath,
                                password = "mczj"
                            ).getOrThrow()

                            withContext(Dispatchers.Main) {
                                step2Progress = 0.8f
                                step2Text = "合并云端数据"
                            }

                            val remoteDbFile = java.io.File(extractDir, "vault_sync.db")
                            if (remoteDbFile.exists()) {
                                val remoteDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                                    remoteDbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                                )
                                try {
                                    val cursor = remoteDb.query("cloud_entries", null, null, null, null, null, null)
                                    var mergedCount = 0
                                    cursor.use {
                                        while (it.moveToNext()) {
                                            val path = it.getString(it.getColumnIndexOrThrow("path"))
                                            val size = it.getLong(it.getColumnIndexOrThrow("size"))
                                            val uploadedSize = it.getLong(it.getColumnIndexOrThrow("uploaded_size"))
                                            val lastModified = it.getString(it.getColumnIndexOrThrow("last_modified"))
                                            val md5 = it.getString(it.getColumnIndexOrThrow("md5"))
                                            val cloudHash = it.getString(it.getColumnIndexOrThrow("cloud_hash"))
                                            val status = com.whmdg.mczj.tools.encryption.data.SyncStatus.valueOf(
                                                it.getString(it.getColumnIndexOrThrow("status"))
                                            )
                                            val lastSyncTime = it.getString(it.getColumnIndexOrThrow("last_sync_time"))
                                            val failReason = it.getString(it.getColumnIndexOrThrow("fail_reason"))

                                            val localEntry = syncDb.getEntry("cloud_entries", path)
                                            if (localEntry == null || (lastSyncTime ?: "") > (localEntry.lastSyncTime ?: "")) {
                                                syncDb.upsertEntry("cloud_entries", com.whmdg.mczj.tools.encryption.data.SyncEntryRow(
                                                    path, size, uploadedSize, lastModified, md5, cloudHash, status, lastSyncTime, failReason
                                                ))
                                                mergedCount++
                                            }
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        step2Text = "合并云端数据完成 (更新 $mergedCount 个)"
                                    }
                                } finally {
                                    remoteDb.close()
                                }

                                // 更新元数据缓存
                                val remoteMeta = webdavClient.getFileMetadata(remotePath)
                                if (remoteMeta != null) {
                                    metaFile.writeText("""{"size":${remoteMeta.size},"lastModified":${remoteMeta.lastModified}}""")
                                }
                            }

                            zipFile.delete()
                            extractDir.deleteRecursively()
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                step2Text = "同步失败: ${e.message}"
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        step2Text = if (webdavConfig == null) "未配置 WebDAV" else "未找到保险箱配置"
                    }
                }

                withContext(Dispatchers.Main) { step2Progress = 1f }
            }

            // ── 第三步：计算差异 ──
            withContext(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    step3Progress = 0.2f
                    step3Text = "读取本地数据库"
                }

                val localEntries = syncDb.getAllEntries("local_entries")
                val localMap = localEntries.filter { !it.path.endsWith("/") }.associateBy { it.path }

                withContext(Dispatchers.Main) {
                    step3Progress = 0.4f
                    step3Text = "读取云端数据库"
                }

                val cloudEntries = syncDb.getAllEntries("cloud_entries")
                val cloudMap = cloudEntries.filter { !it.path.endsWith("/") }.associateBy { it.path }

                withContext(Dispatchers.Main) {
                    step3Progress = 0.6f
                    step3Text = "计算差异"
                }

                val allPaths = (localMap.keys + cloudMap.keys).toSet()
                var diffCount = 0

                for (path in allPaths) {
                    val local = localMap[path]
                    val cloud = cloudMap[path]

                    when {
                        local == null && cloud != null -> diffCount++  // 云端独有
                        local != null && cloud == null -> diffCount++  // 本地独有
                        local != null && cloud != null -> {
                            // 都有：检查是否是同一个文件
                            val isSameFile = local.md5 != null && cloud.md5 != null && local.md5 == cloud.md5
                            if (!isSameFile) diffCount++  // 冲突
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    step3Progress = 0.8f
                    step3Text = "统计文件数和大小"
                }

                val localFileCount = localMap.size
                val cloudFileCount = cloudMap.size
                val localSize = localMap.values.sumOf { it.size }
                val cloudSize = cloudMap.values.sumOf { it.size }

                withContext(Dispatchers.Main) {
                    step3Progress = 1f
                    step3Text = "计算完成 (差异 $diffCount 个)"
                }

                // 保存统计数据到数据库
                syncDb.updateStats(com.whmdg.mczj.tools.encryption.data.SyncStatsRow(
                    localFileCount = localFileCount,
                    cloudFileCount = cloudFileCount,
                    localSize = localSize,
                    cloudSize = cloudSize,
                    diffCount = diffCount,
                    lastUpdate = java.time.Instant.now().toString()
                ))

                delay(300)

                withContext(Dispatchers.Main) {
                    onComplete(DiffScanResult(
                        diffCount = diffCount,
                        localFileCount = localFileCount,
                        cloudFileCount = cloudFileCount,
                        localSize = localSize,
                        cloudSize = cloudSize
                    ))
                }
            }
        } catch (e: Exception) {
            step1Text = "扫描失败: ${e.message}"
            kotlinx.coroutines.delay(2000)
            onComplete(DiffScanResult(0, 0, 0, 0, 0))
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    text = "扫描差异文件",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 第一步：扫描本地文件
                Text(step1Text, fontSize = 13.sp, color = subTextColor)
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { step1Progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Color(0xFF3B82F6),
                    trackColor = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 第二步：检查云端数据库
                Text(step2Text, fontSize = 13.sp, color = subTextColor)
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { step2Progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Color(0xFF3B82F6),
                    trackColor = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 第三步：计算差异
                Text(step3Text, fontSize = 13.sp, color = subTextColor)
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { step3Progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Color(0xFF3B82F6),
                    trackColor = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)
                )
            }
        }
    }
}

// ── 差异扫描结果数据类 ──

data class DiffScanResult(
    val diffCount: Int,
    val localFileCount: Int,
    val cloudFileCount: Int,
    val localSize: Long,
    val cloudSize: Long
)

// ── 差异结果对话框 ──

@Composable
private fun DiffResultDialog(
    result: DiffScanResult,
    onConfirm: () -> Unit,
    confirmError: Throwable?
) {
    val isDarkMode = LocalIsDarkMode.current
    val cardColor = if (isDarkMode) Color(0xFF1E293B) else Color.White
    val textColor = if (isDarkMode) Color(0xFFE2E8F0) else Color(0xFF1E293B)
    val subTextColor = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    text = "差异扫描完成",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text("差异文件：${result.diffCount} 个", fontSize = 14.sp, color = subTextColor)
                Spacer(modifier = Modifier.height(6.dp))
                Text("本地文件：${result.localFileCount} 个 (${com.whmdg.mczj.tools.util.FormatUtils.formatBytes(result.localSize)})",
                    fontSize = 14.sp, color = subTextColor)
                Spacer(modifier = Modifier.height(6.dp))
                Text("云端文件：${result.cloudFileCount} 个 (${com.whmdg.mczj.tools.util.FormatUtils.formatBytes(result.cloudSize)})",
                    fontSize = 14.sp, color = subTextColor)

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("确认", color = Color.White)
                    }
                }
            }
        }
    }
}

/** 从待处理信息创建本地保险箱 */
private suspend fun createVaultFromPending(
    context: Context,
    vaultService: VaultService,
    pending: PendingVaultInfo,
    directoryUri: Uri,
    password: String,
    useSaf: Boolean,
    syncItems: androidx.compose.runtime.snapshots.SnapshotStateList<CloudSyncItem>,
    webdavPath: String
) = withContext(Dispatchers.IO) {
    // 1. 解析目录路径
    val basePath = if (useSaf) {
        directoryUri.toString()
    } else {
        com.whmdg.mczj.tools.AppDataPaths.safUriToAbsolutePath(context, directoryUri)
            ?: throw Exception("无法解析目录路径，请授予所有文件访问权限")
    }

    // 2. 创建保险箱文件夹
    val vaultDir = if (useSaf) {
        // SAF 模式：通过 DocumentFile 创建
        val docTree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, directoryUri)
            ?: throw Exception("无法访问目录")
        val vaultFolder = docTree.createDirectory(pending.vaultName)
            ?: throw Exception("无法创建保险箱文件夹")

        // 复制 vault_config.json
        val configDoc = vaultFolder.createFile("application/json", "vault_config.json")
            ?: throw Exception("无法创建配置文件")
        context.contentResolver.openOutputStream(configDoc.uri)?.use { out ->
            pending.configFile.inputStream().use { inp -> inp.copyTo(out) }
        }

        vaultFolder.uri.toString()
    } else {
        // 文件路径模式
        val dir = java.io.File(basePath, pending.vaultName)
        dir.mkdirs()

        // 复制 vault_config.json 并创建备份副本
        val targetConfig = java.io.File(dir, "vault_config.json")
        pending.configFile.copyTo(targetConfig, overwrite = true)

        // 读取配置并使用 saveWithBackup 创建三份副本（主配置 + 箱内备份 + 私有备份）
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val config = json.decodeFromString<com.whmdg.mczj.tools.encryption.data.VaultConfig>(targetConfig.readText())
        config.saveWithBackup(context, dir)

        dir.absolutePath
    }

    // 3. 调用 VaultService 导入（验证密码）
    val vaultRecord = if (useSaf) {
        vaultService.importVaultWithPasswordSaf(pending.vaultName, Uri.parse(vaultDir), password)
    } else {
        vaultService.importVaultWithPassword(pending.vaultName, vaultDir, password)
    }

    // 4. 创建云盘同步卡片
    withContext(Dispatchers.Main) {
        val stats = pending.stats
        syncItems.add(CloudSyncItem(
            id = "vault_${vaultRecord.id}",
            vaultId = vaultRecord.id,
            vaultName = pending.vaultName,
            type = "保险箱",
            vaultSize = 0L,
            lastSyncTime = stats.lastUpdate ?: "未同步",
            cloudSize = stats.cloudSize,
            diffFileCount = stats.diffCount,
            webdavPath = webdavPath,
            localFileCount = 0,
            cloudFileCount = stats.cloudFileCount
        ))
        CloudSyncStore.save(context, syncItems.toList())
    }

    // 5. 删除临时配置文件
    pending.configFile.delete()
}

// ── MD5 计算辅助函数 ──

private fun calculateMd5(file: java.io.File): String {
    val md = java.security.MessageDigest.getInstance("MD5")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            md.update(buffer, 0, read)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

// ── UUID 迁移全屏弹窗（简化版）──

@Composable
private fun MigrationBlockingDialog(progress: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.padding(32.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "正在升级保险箱数据",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = progress,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!progress.contains("失败")) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "请勿退出应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

// ── WebDAV 设置弹窗 ──