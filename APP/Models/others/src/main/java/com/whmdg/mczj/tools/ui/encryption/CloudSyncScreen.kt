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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.whmdg.mczj.tools.encryption.data.VaultRecord
import com.whmdg.mczj.tools.encryption.services.VaultService
import com.whmdg.mczj.tools.ui.LocalIsDarkMode
import com.whmdg.mczj.tools.ui.components.glowEffect
import com.whmdg.mczj.tools.util.FormatUtils

/** 云盘同步项（UI 数据模型） */
data class CloudSyncItem(
    val id: String,
    val vaultName: String,
    val type: String,           // "保险箱" 或 "本地文件夹"
    val vaultSize: Long,
    val lastSyncTime: String,
    val cloudSize: Long,
    val diffFileCount: Int
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
    onShowVaultSheet: () -> Unit
) {
    val isDarkMode = LocalIsDarkMode.current
    var fabExpanded by remember { mutableStateOf(false) }
    val syncItems = remember { mutableStateListOf<CloudSyncItem>() }
    val processedVaultIds = remember { mutableSetOf<Int>() }

    // 处理外部传入的保险箱添加请求
    LaunchedEffect(events.requestCounter) {
        val vault = events.addVaultRequest ?: return@LaunchedEffect
        if (vault.id !in processedVaultIds) {
            processedVaultIds.add(vault.id)
            syncItems.add(CloudSyncItem(
                id = "vault_${vault.id}",
                vaultName = vault.name,
                type = "保险箱",
                vaultSize = vault.storageSize,
                lastSyncTime = "未同步",
                cloudSize = 0,
                diffFileCount = 0
            ))
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(syncItems, key = { it.id }) { item ->
                    CloudSyncCard(item)
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
    }
}

// ── 云盘同步卡片 ──
@Composable
private fun CloudSyncCard(item: CloudSyncItem) {
    val isDarkMode = LocalIsDarkMode.current
    val glowEnabled = true

    Box(
        modifier = Modifier
            .fillMaxWidth()
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
                    CloudInfoRow("本地大小", FormatUtils.formatBytes(item.vaultSize), isDarkMode)
                    Spacer(modifier = Modifier.height(4.dp))
                    CloudInfoRow("云端大小", FormatUtils.formatBytes(item.cloudSize), isDarkMode)
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
