package com.whmdg.mczj.tools.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.security.MyDeviceAdminReceiver
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier

enum class PermissionType(val displayName: String, val key: String, val desc: String) {
    NORMAL("普通权限", "NORMAL", "标准应用权限，受系统沙盒保护限制较多。"),
    ACCESSIBILITY("无障碍", "ACCESSIBILITY", "无障碍辅助功能，允许模拟屏幕手势操作。"),
    ADB("ADB权限", "ADB", "高级调试授权，可通过计算机运行 ADB 命令授予。"),
    ADMIN("管理员", "ADMIN", "系统级设备管理器，提供强制锁定及防护特权。"),
    ROOT("Root权限", "ROOT", "最高级超级用户控制权限，解除一切系统沙箱约束。")
}

data class MatrixRow(
    val description: String,
    val supportMap: Map<PermissionType, Boolean>
)

val capabilityMatrix = listOf(
    MatrixRow(
        "运行普通工具 (如文件保险箱等)",
        mapOf(
            PermissionType.NORMAL to true,
            PermissionType.ACCESSIBILITY to true,
            PermissionType.ADB to true,
            PermissionType.ADMIN to true,
            PermissionType.ROOT to true
        )
    ),
    MatrixRow(
        "模拟全局手势 (实现免Root屏幕辅助)",
        mapOf(
            PermissionType.NORMAL to false,
            PermissionType.ACCESSIBILITY to true,
            PermissionType.ADB to true,
            PermissionType.ADMIN to false,
            PermissionType.ROOT to true
        )
    ),
    MatrixRow(
        "读取全局系统日志 (Logcat 监控)",
        mapOf(
            PermissionType.NORMAL to false,
            PermissionType.ACCESSIBILITY to false,
            PermissionType.ADB to true,
            PermissionType.ADMIN to false,
            PermissionType.ROOT to true
        )
    ),
    MatrixRow(
        "修改安全系统配置 (Secure Settings)",
        mapOf(
            PermissionType.NORMAL to false,
            PermissionType.ACCESSIBILITY to false,
            PermissionType.ADB to true,
            PermissionType.ADMIN to false,
            PermissionType.ROOT to true
        )
    ),
    MatrixRow(
        "强制锁定屏幕及擦除数据 (防盗防丢)",
        mapOf(
            PermissionType.NORMAL to false,
            PermissionType.ACCESSIBILITY to false,
            PermissionType.ADB to false,
            PermissionType.ADMIN to true,
            PermissionType.ROOT to true
        )
    ),
    MatrixRow(
        "修改任意系统底层文件 (深度定制)",
        mapOf(
            PermissionType.NORMAL to false,
            PermissionType.ACCESSIBILITY to false,
            PermissionType.ADB to false,
            PermissionType.ADMIN to false,
            PermissionType.ROOT to true
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecialPermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("special_permissions", Context.MODE_PRIVATE) }

    var activeType by remember { mutableStateOf(PermissionType.NORMAL) }
    var verificationStatus by remember { mutableStateOf(false) }
    var appliedPermission by remember { mutableStateOf(sp.getString("target_permission_level", "NORMAL") ?: "NORMAL") }

    var showGuideDialog by remember { mutableStateOf(false) }
    var useOnlyWhenNecessary by remember { mutableStateOf(sp.getBoolean("use_only_when_necessary", false)) }

    // 当切换选中的权限时，重置校验状态
    LaunchedEffect(activeType) {
        verificationStatus = false
    }

    fun verifySelectedPermission() {
        val possessed = when (activeType) {
            PermissionType.NORMAL -> true
            PermissionType.ACCESSIBILITY -> SpecialPermissionVerifier.isAccessibilityEnabled(context)
            PermissionType.ADB -> SpecialPermissionVerifier.isAdbEnabled(context)
            PermissionType.ADMIN -> SpecialPermissionVerifier.isDeviceAdminActive(context)
            PermissionType.ROOT -> SpecialPermissionVerifier.isRootAvailable()
        }

        if (possessed) {
            Toast.makeText(context, "【${activeType.displayName}】校验成功！已可应用该特权。", Toast.LENGTH_SHORT).show()
            verificationStatus = true
        } else {
            Toast.makeText(context, "未检测到【${activeType.displayName}】，请先进行授权。", Toast.LENGTH_SHORT).show()
            showGuideDialog = true
        }
    }

    fun applySelectedPermission() {
        sp.edit().putString("target_permission_level", activeType.key).apply()
        appliedPermission = activeType.key
        Toast.makeText(context, "已成功应用特权模式：${activeType.displayName}", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("特殊权限") },
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
                .padding(horizontal = 16.dp)
        ) {
            // 当前生效的特权显示
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "当前应用特权模式",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                        val activeDisplayName = PermissionType.values().find { it.key == appliedPermission }?.displayName ?: "未知"
                        Text(
                            text = activeDisplayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 五个气泡式切换按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PermissionType.values().forEach { type ->
                    val isSelected = activeType == type
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable { activeType = type }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = type.displayName,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // 当前选中权限描述
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                )
            ) {
                Text(
                    text = activeType.desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 表头栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "系统交互与操作能力对比",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "当前支持",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(72.dp)
                )
            }

            // 矩阵对比表
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
                    )
            ) {
                items(capabilityMatrix) { row ->
                    val supported = row.supportMap[activeType] ?: false
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = row.description,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Box(
                            modifier = Modifier.width(72.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (supported) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "支持",
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "不支持",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 非必要时不使用权限开关
            if (activeType != PermissionType.NORMAL) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "非必要时不使用权限",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "开启时，优先以普通APP沙盒运行。仅在发生权限不足报错时，自动临时提权至所选的权限重新执行操作。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useOnlyWhenNecessary,
                        onCheckedChange = { checked ->
                            useOnlyWhenNecessary = checked
                            sp.edit().putBoolean("use_only_when_necessary", checked).apply()
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 校验 / 应用 操作按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (verificationStatus) {
                    Button(
                        onClick = { applySelectedPermission() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32)
                        ),
                        modifier = Modifier.width(120.dp)
                    ) {
                        Text("应用", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                } else {
                    Button(
                        onClick = { verifySelectedPermission() },
                        modifier = Modifier.width(120.dp)
                    ) {
                        Text("校验", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // 授权引导弹窗
    if (showGuideDialog) {
        AlertDialog(
            onDismissRequest = { showGuideDialog = false },
            title = { Text("如何获取【${activeType.displayName}】？") },
            text = {
                Column {
                    when (activeType) {
                        PermissionType.ACCESSIBILITY -> {
                            Text("1. 点击下方“前往设置”按钮，系统将跳转至「无障碍服务」列表。")
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("2. 在已下载的服务/软件列表中找到「艨艟战舰无障碍服务」。")
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("3. 开启服务开关即可成功授权。")
                        }
                        PermissionType.ADMIN -> {
                            Text("需要激活应用高级管理器权限。")
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("激活后本软件将拥有防盗锁屏、数据隔离保障等高级功能。")
                        }
                        PermissionType.ADB -> {
                            Text("ADB 调试特权可以通过电脑或 Shizuku 应用进行授权：")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("【方法一：使用 Shizuku (推荐)】")
                            Text("如果您已安装并激活了 Shizuku，点击下方“启动 Shizuku”按钮，进入 Shizuku 客户端中对「工具箱」应用开启授权开关，即可完成免数据线无感 ADB 授权。")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("【方法二：使用 USB 数据线连接电脑】")
                            Text("请连接电脑，开启手机 USB 调试，并在命令行终端运行以下指令：")
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    text = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        PermissionType.ROOT -> {
                            Text("本软件通过超级用户 su 接口校验 Root 权限。")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("请确保您的设备已经解锁并成功刷入 Magisk 或 Kitsune Mask。")
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("在校验时，超级用户管理器会弹出授权提示，请点击「允许」以授予 Root 访问权限。")
                        }
                        else -> {
                            Text("无特殊获取要求。")
                        }
                    }
                }
            },
            confirmButton = {
                if (activeType == PermissionType.ACCESSIBILITY || activeType == PermissionType.ADMIN || activeType == PermissionType.ADB) {
                    Button(onClick = {
                        showGuideDialog = false
                        try {
                            if (activeType == PermissionType.ACCESSIBILITY) {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            } else if (activeType == PermissionType.ADMIN) {
                                val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)
                                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "激活高级设备管理特权，提供更安全的防卸载及数据保障方案。")
                                }
                                context.startActivity(intent)
                            } else if (activeType == PermissionType.ADB) {
                                val intent = context.packageManager.getLaunchIntentForPackage("rikka.shizuku")
                                if (intent != null) {
                                    context.startActivity(intent)
                                } else {
                                    Toast.makeText(context, "未检测到 Shizuku 应用，请先前往应用商店或官网下载并安装。", Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "无法跳转，请手动开启设置。", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text(if (activeType == PermissionType.ADB) "启动 Shizuku" else "前往设置")
                    }
                } else {
                    Button(onClick = { showGuideDialog = false }) {
                        Text("知道了")
                    }
                }
            },
            dismissButton = {
                if (activeType == PermissionType.ACCESSIBILITY || activeType == PermissionType.ADMIN || activeType == PermissionType.ADB) {
                    TextButton(onClick = { showGuideDialog = false }) {
                        Text("取消")
                    }
                }
            }
        )
    }
}
