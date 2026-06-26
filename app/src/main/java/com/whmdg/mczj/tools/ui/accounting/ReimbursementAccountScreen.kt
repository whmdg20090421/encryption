package com.whmdg.mczj.tools.ui.accounting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.ui.platform.LocalContext
import com.whmdg.mczj.tools.ui.Screen
import com.whmdg.mczj.tools.ui.theme.LocalIsDarkMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 报销账户数据（叶子节点） */
data class ReimbursementAccount(
    val id: String,
    val name: String,
    val pendingAmount: Double = 0.0,
    val completedAmount: Double = 0.0
)

/** 报销分组（包含多个账户） */
data class ReimbursementGroup(
    val name: String,
    val accounts: List<ReimbursementAccount> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReimbursementAccountScreen(onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    // 从数据库加载报销账户，按分组归类
    val entities = remember { AccountingRepository.getReimbursementAccounts(context) }
    val allRecords = remember { AccountingRepository.getAllRecords(context) }
    val groups = remember(entities, allRecords) {
        // 按报销账户 ID 分组求和
        val amountByReimbId = mutableMapOf<String, Double>()
        for (record in allRecords) {
            val reimbId = record.reimbursementAccountId ?: continue
            amountByReimbId[reimbId] = (amountByReimbId[reimbId] ?: 0.0) +
                (record.amount.toDoubleOrNull() ?: 0.0)
        }
        mutableStateListOf<ReimbursementGroup>().apply {
            val grouped = entities.groupBy { it.groupName }
            if (grouped.isEmpty()) {
                add(ReimbursementGroup(name = "报销"))
            } else {
                grouped.forEach { (groupName, accounts) ->
                    add(ReimbursementGroup(
                        name = groupName,
                        accounts = accounts.map { entity ->
                            ReimbursementAccount(
                                id = entity.id,
                                name = entity.name,
                                pendingAmount = amountByReimbId[entity.id] ?: 0.0,
                                completedAmount = 0.0
                            )
                        }
                    ))
                }
            }
        }
    }
    var groupExpanded by remember { mutableStateOf(true) }

    // 由下向上计算：分组金额 = 子账户之和
    val groupTotals = groups.map { group ->
        group.accounts.sumOf { it.pendingAmount } to group.accounts.sumOf { it.completedAmount }
    }
    // 根数 = 所有分组之和
    val totalPending = groupTotals.sumOf { it.first }
    val totalCompleted = groupTotals.sumOf { it.second }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "报销账户",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigate(Screen.AddReimbursementAccount) }) {
                        Icon(Icons.Default.Add, contentDescription = "添加报销账户")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (LocalIsDarkMode.current) Color(0xFF2A2A2A) else Color(0xFFF5F5F5)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ── 顶部汇总卡片 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("待报销", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(formatAmount(totalPending),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("已报销", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(formatAmount(totalCompleted),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 分组卡片 ──
            groups.forEachIndexed { groupIndex, group ->
                val (gPending, gCompleted) = groupTotals[groupIndex]

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        // 分组头部：点击展开/收起
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (groupExpanded) Icons.Default.KeyboardArrowDown
                                              else Icons.Default.KeyboardArrowRight,
                                contentDescription = if (groupExpanded) "收起" else "展开",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = group.name,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.weight(1f))
                            // 分组待报销金额
                            Text(
                                text = formatAmount(gPending),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // 展开内容
                        AnimatedVisibility(visible = groupExpanded) {
                            Column {
                                if (group.accounts.isEmpty()) {
                                    // 空状态提示
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "暂无报销账户，点击右上角 + 添加",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                } else {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        thickness = 0.5.dp
                                    )
                                    group.accounts.forEach { account ->
                                        // 账户行
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Receipt,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Text(
                                                text = account.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = formatAmount(account.pendingAmount),
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** 格式化金额：整数不显示小数，否则保留两位 */
private fun formatAmount(value: Double): String {
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format("%.2f", value)
    }
}
