package com.whmdg.mczj.tools.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.auth.Feature
import com.whmdg.mczj.tools.auth.NoPermissionDialog
import com.whmdg.mczj.tools.auth.PasswordDialog
import com.whmdg.mczj.tools.auth.PermissionManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.whmdg.mczj.tools.encryption.data.VaultRecord
import com.whmdg.mczj.tools.encryption.services.VaultSession

/**
 * 模块级导航路由。open class 以支持跨模块子类化。
 * 子页面路由由各模块自己的 Route sealed class 管理。
 */
open class Screen {
    // 首页/设置
    object Dashboard : Screen()
    object Settings : Screen()

    // 全局页面
    object ThemeSettings : Screen()
    object About : Screen()
    object Changelog : Screen()
    object AuthManagement : Screen()
    object FunctionalTest : Screen()
    data class TextEditor(val filePath: String) : Screen()
    data class ImageViewer(val filePath: String, val imagePaths: List<String> = emptyList(), val startIndex: Int = 0) : Screen()

    // 8 个模块入口
    object Encryption : Screen()
    data class FileManager(
        val vaultSession: VaultSession? = null,
        val cloudMode: Boolean = false,
        val webdavUrl: String = "",
        val webdavUser: String = "",
        val webdavPassword: String = "",
        val webdavPath: String = "",
        val cloudVaultDir: String? = null,
        val cloudVaultId: Int = 0,
        val cloudVaultName: String = ""
    ) : Screen()
    object AppPermissions : Screen()
    object BatchDownloader : Screen()
    object Security : Screen()
    object RpHub : Screen()
    object Diary : Screen()
    object Wifi : Screen()
    object Accounting : Screen()
    object Hook : Screen()
    data class HookDetail(val packageName: String) : Screen()

    // ── 以下为各模块内部子页面（过渡期保留在 core，后续逐步迁移到各模块 Route） ──

    // 加密模块子页面
    object EncryptionHome : Screen()
    object VaultCreate : Screen()
    data class VaultChangePassword(val vault: VaultRecord) : Screen()

    // 文件管理器子页面
    object FileManagerDetail : Screen()

    // 批量下载器子页面
    object BatchDownloaderDetail : Screen()
    object FADownloader : Screen()
    object FALogin : Screen()
    object DeviantDownloader : Screen()
    object DeviantLogin : Screen()

    // 安全模块子页面
    object PermissionSettings : Screen()
    object SpecialPermissions : Screen()
    object PermissionManagementConfig : Screen()

    // 日记模块子页面
    data class DiaryBookDetail(val bookName: String, val createdAt: Long, val lastEditedAt: Long) : Screen()

    // 记账本模块子页面
    data class AccountingDetail(val bookName: String, val recordId: String) : Screen()
    data class AddAccounting(val bookName: String, val recordId: String? = null) : Screen()
    object ReimbursementAccount : Screen()
    data class ReimbursementAccountDetail(val accountId: String) : Screen()
    object AddReimbursementAccount : Screen()
    data class AssetDetail(val accountId: String) : Screen()
    data class CapitalFlow(val accountId: String) : Screen()
    data class AssetHistory(val accountId: String) : Screen()
    data class TransferList(val accountId: String) : Screen()
    data class FixedDepositManager(val accountId: String) : Screen()
}

enum class ModuleId {
    ENCRYPTION,
    FILE_MANAGER,
    APP_PERMISSIONS,
    BATCH_DOWNLOADER,
    SECURITY,
    RP_HUB,
    WIFI,
    DIARY,
    ACCOUNTING,
    HOOK
}

data class ModuleEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val feature: Feature,
    val screen: Screen
)

val MODULE_REGISTRY: Map<ModuleId, ModuleEntry> = mapOf(
    ModuleId.ENCRYPTION to ModuleEntry("加密", "常用加密 / 解密工具", Icons.Default.Lock, Feature.ENCRYPTION_VAULT, Screen.Encryption),
    ModuleId.FILE_MANAGER to ModuleEntry("文件管理器", "双面板文件浏览工具", Icons.Default.Folder, Feature.FILE_MANAGER, Screen.FileManager()),
    ModuleId.APP_PERMISSIONS to ModuleEntry("应用权限管理", "查看和管理应用权限", Icons.Default.Security, Feature.APP_PERMISSIONS, Screen.AppPermissions),
    ModuleId.BATCH_DOWNLOADER to ModuleEntry("批量下载器", "FA 图片批量下载等工具", Icons.Default.Download, Feature.BATCH_DOWNLOADER, Screen.BatchDownloader),
    ModuleId.SECURITY to ModuleEntry("安全", "权限设置与特殊权限管理", Icons.Default.Lock, Feature.SECURITY_SETTINGS, Screen.Security),
    ModuleId.RP_HUB to ModuleEntry("RP-Hub", "本地角色扮演对话工具", Icons.Default.SmartToy, Feature.RP_HUB, Screen.RpHub),
    ModuleId.WIFI to ModuleEntry("WiFi", "WiFi 网络扫描与分析", Icons.Default.Wifi, Feature.WIFI, Screen.Wifi),
    ModuleId.DIARY to ModuleEntry("日记", "记录每日心情与想法", Icons.Default.Edit, Feature.DIARY, Screen.Diary),
    ModuleId.ACCOUNTING to ModuleEntry("记账本", "日常收支记录", Icons.Default.AccountBalance, Feature.ACCOUNTING, Screen.Accounting),
    ModuleId.HOOK to ModuleEntry("Hook", "应用钩子管理", Icons.Default.Extension, Feature.HOOK, Screen.Hook)
)

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
    Feature.WIFI -> "WiFi"
    Feature.DIARY -> "日记"
    Feature.ACCOUNTING -> "记账本"
    Feature.HOOK -> "Hook"
}

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

    data class DebugInfo(
        val keyId: String,
        val features: Set<Feature>,
        val neededFeature: Feature,
        val hasPerm: Boolean,
        val authState: String,
        val targetScreen: Screen
    )
    var debugInfo by remember { mutableStateOf<DebugInfo?>(null) }

    androidx.compose.runtime.LaunchedEffect(toastMsg) {
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

    noPermModule?.let { moduleId ->
        val entry = MODULE_REGISTRY[moduleId]!!
        NoPermissionDialog(
            feature = entry.feature,
            onDismiss = { noPermModule = null }
        )
    }

    debugInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { debugInfo = null },
            title = { Text("密钥已激活") },
            text = {
                Column {
                    if (isDebugAuth(ctx)) {
                        Text("当前已激活权限：", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("keyId = \"${info.keyId}\"")
                        Text("features = ${info.features.joinToString { it.name }}")
                        Text("neededFeature = ${info.neededFeature.name}")
                        Text("hasPerm = ${info.hasPerm}")
                        Text("authState = ${info.authState}")
                        Text("targetScreen = ${info.targetScreen::class.simpleName}")
                    } else {
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
