package com.whmdg.mczj.tools.ui.accounting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.ui.Screen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountingDetailScreen(
    onBack: () -> Unit,
    onNavigate: (Screen) -> Unit,
    bookName: String,
    recordId: String
) {
    val context = LocalContext.current
    val recordDb = remember { AccountingRecordDb.load(context) }
    val record = remember(recordDb) { recordDb.records.find { it.id == recordId } }
    val categoryDb = remember { AccountingCategoryDb.ensureDefault(context) }

    // 构建分类查找表
    val categoryLookup = remember(categoryDb) {
        val map = mutableMapOf<String, Triple<String, String, String?>>()
        for ((_, typeMap) in categoryDb.pages) {
            for ((_, cats) in typeMap) {
                for (cat in cats) {
                    map[cat.id] = Triple(cat.name, cat.icon, cat.overlay)
                    for (child in cat.children) {
                        map[child.id] = Triple(child.name, child.icon, child.overlay)
                    }
                }
            }
        }
        map
    }

    // 构建账户查找表
    val allAccounts = remember { AccountingRepository.getAllAccounts(context) }
    val accountLookup = remember(allAccounts) { allAccounts.associate { it.id to it.name } }

    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // 报销弹窗状态
    var showReimburseDialog by remember { mutableStateOf(false) }
    val accounts = remember { AccountingRepository.getAllAccounts(context).filter { it.category == "tradable" } }
    var reimburseAccountIndex by remember { mutableIntStateOf(0) }
    val reimburseAmount = record?.amount ?: "0"

    if (record == null) {
        // 记录不存在，返回首页
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val catInfo = categoryLookup[record.categoryId]
    val subInfo = record.subcategoryId?.let { categoryLookup[it] }
    val parentName = catInfo?.first ?: record.categoryId
    val childName = subInfo?.first
    val displayName = if (childName != null) "$parentName-$childName" else parentName
    val icon = subInfo?.second ?: catInfo?.second ?: "category"
    val overlay = subInfo?.third ?: catInfo?.third
    val isExpense = record.type == "支出" || record.type == "债务"
    val amountPrefix = if (isExpense) "-" else "+"
    val amountColor = if (isExpense) Color(0xFFEF5350) else Color(0xFF4CAF50)

    val dateFormat = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(record.happenedAt))

    // 删除确认弹窗
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条账单记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    AccountingRepository.deleteRecord(context, record.id)
                    onBack()
                }) {
                    Text("确认", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 报销弹窗
    if (showReimburseDialog) {
        val currentAccount = accounts.getOrNull(reimburseAccountIndex)
        AlertDialog(
            onDismissRequest = { showReimburseDialog = false },
            title = { Text("报销") },
            text = {
                Column {
                    // 报销金额
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("报销金额", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        Text("¥$reimburseAmount", style = MaterialTheme.typography.bodyLarge)
                    }
                    // 报销账户
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                if (accounts.isNotEmpty()) {
                                    reimburseAccountIndex = (reimburseAccountIndex + 1) % accounts.size
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("报销账户", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = currentAccount?.name ?: "暂无账户",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (currentAccount != null) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (currentAccount != null) {
                            val amt = reimburseAmount.toDoubleOrNull() ?: 0.0
                            // 更新账户余额
                            AccountingRepository.updateAccount(
                                context,
                                currentAccount.copy(
                                    currentBalance = currentAccount.currentBalance + amt,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                            // 更新账单报销状态
                            AccountingRepository.updateRecord(
                                context,
                                record.copy(
                                    reimburseStatus = true,
                                    reimburseAmount = amt
                                )
                            )
                        }
                        showReimburseDialog = false
                    },
                    enabled = currentAccount != null
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReimburseDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账单详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier
                                .width(LocalConfiguration.current.screenWidthDp.dp * 0.4f)
                        ) {
                            DropdownMenuItem(
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                val isReimbursement = record.reimbursementAccountId != null
                if (isReimbursement) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 15.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Button(
                            onClick = { showReimburseDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !record.reimburseStatus
                        ) {
                            Text("报销")
                        }
                        Button(
                            onClick = { onNavigate(Screen.AddAccounting(bookName, recordId)) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("编辑")
                        }
                    }
                } else {
                    Button(
                        onClick = { onNavigate(Screen.AddAccounting(bookName, recordId)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("编辑")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            // 分类信息 + 金额
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 图标
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        ColorIconImage(buildInId = icon, size = 24.dp, overlay = overlay)
                    }
                    Spacer(Modifier.width(12.dp))
                    // 分类名
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    // 金额
                    Text(
                        text = "$amountPrefix${record.amount}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = amountColor
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 详情大卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 账单日期
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("账单日期", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        Text(text = dateStr, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // 备注
                    if (record.note.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text("备注", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = record.note,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(3f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                        }
                    }

                    // 收支账户
                    HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("收支账户", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = record.accountId?.let { accountLookup[it] } ?: "未指定",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 报销账户 + 报销金额（仅报销账单显示）
                    if (record.reimbursementAccountId != null) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("报销账户", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = accountLookup[record.reimbursementAccountId] ?: record.reimbursementAccountId ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("报销金额", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = if (record.reimburseStatus) "已报销: ${String.format("%.2f", record.reimburseAmount)}" else "未报销",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 所属账本
                    HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("所属账本", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        Text(text = bookName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // 地点信息（如果有）
                    if (record.address.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("地点", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.weight(1f))
                            Text(text = record.address, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // 属性（如果有）
                    if (record.excludeFromStats || record.excludeFromBudget) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        if (record.excludeFromStats) {
                            Text("不计入收支统计", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (record.excludeFromBudget) {
                            if (record.excludeFromStats) Spacer(Modifier.height(4.dp))
                            Text("不计入预算", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // 创建时间 / 最后修改
                    HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("创建时间", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = record.createdAt?.let { dateFormat.format(Date(it)) } ?: "未记录",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("最后修改", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = record.updatedAt?.let { dateFormat.format(Date(it)) } ?: "未记录",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
