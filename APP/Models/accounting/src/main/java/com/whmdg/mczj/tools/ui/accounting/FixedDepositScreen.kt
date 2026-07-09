package com.whmdg.mczj.tools.ui.accounting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixedDepositScreen(accountId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    var deposits by remember { mutableStateOf<List<FixedDeposit>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(0) } // 0=利息 1=本金 2=利率 3=存入日 4=到期日
    var maturedDeposits by remember { mutableStateOf<List<FixedDeposit>>(emptyList()) }
    var showMaturityDialog by remember { mutableStateOf(false) }

    fun reload() {
        deposits = AccountingRepository.getFixedDepositsByAccount(context, accountId)
        maturedDeposits = AccountingRepository.getMaturedDeposits(context, accountId)
        if (maturedDeposits.isNotEmpty()) showMaturityDialog = true
    }

    LaunchedEffect(accountId) { reload() }

    val sorted = remember(deposits, sortMode) {
        when (sortMode) {
            0 -> deposits.sortedByDescending { it.principal * it.interestRate / 100.0 * if (it.termUnit == "年") it.termValue.toDouble() else it.termValue / 12.0 }
            1 -> deposits.sortedByDescending { it.principal }
            2 -> deposits.sortedByDescending { it.interestRate }
            3 -> deposits.sortedByDescending { it.startDate }
            4 -> deposits.sortedByDescending { it.maturityDate }
            else -> deposits
        }
    }

    val totalPrincipal = deposits.sumOf { it.principal }
    val now = System.currentTimeMillis()
    val maturedInterest = deposits.filter { it.status == "active" && it.maturityDate <= now }.sumOf {
        val years = if (it.termUnit == "年") it.termValue.toDouble() else it.termValue / 12.0
        it.principal * it.interestRate / 100.0 * years
    }
    val unmaturedInterest = deposits.filter { it.status == "active" && it.maturityDate > now }.sumOf {
        val years = if (it.termUnit == "年") it.termValue.toDouble() else it.termValue / 12.0
        it.principal * it.interestRate / 100.0 * years
    }

    val sortLabels = listOf("按利息", "按本金", "按利率", "按存入日", "按到期日")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("定期存款") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            Text(sortLabels[sortMode])
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            sortLabels.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { sortMode = index; expanded = false }
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "新增定期存款")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            // 汇总卡片
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("本金合计", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(String.format("%.2f", totalPrincipal), style = MaterialTheme.typography.titleMedium)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("已到期利息", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(String.format("%.2f", maturedInterest), style = MaterialTheme.typography.titleMedium, color = Color(0xFFEF5350))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("未到期利息", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(String.format("%.2f", unmaturedInterest), style = MaterialTheme.typography.titleMedium, color = Color(0xFF4CAF50))
                        }
                    }
                }
            }

            if (sorted.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("暂无定期存款", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            items(sorted, key = { it.id }) { deposit ->
                val years = if (deposit.termUnit == "年") deposit.termValue.toDouble() else deposit.termValue / 12.0
                val interest = deposit.principal * deposit.interestRate / 100.0 * years
                val statusText = when (deposit.status) {
                    "active" -> if (deposit.maturityDate <= now) "已到期" else "存续中"
                    "matured" -> "已到期"
                    "withdrawn" -> "已取出"
                    else -> deposit.status
                }
                val statusColor = when {
                    deposit.status == "withdrawn" -> Color.Gray
                    deposit.maturityDate <= now -> Color(0xFFEF5350)
                    else -> Color(0xFF4CAF50)
                }

                ListItem(
                    headlineContent = {
                        Text(
                            text = "${deposit.termValue}${deposit.termUnit} · ${deposit.principal}元 · ${deposit.interestRate}%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    supportingContent = {
                        Text(
                            text = "${dateFormat.format(Date(deposit.startDate))} → ${dateFormat.format(Date(deposit.maturityDate))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = String.format("%.2f", interest),
                                style = MaterialTheme.typography.bodyMedium,
                                color = statusColor
                            )
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor
                            )
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    // ── 新增定期存款弹窗 ──
    if (showAddDialog) {
        var principal by remember { mutableStateOf("") }
        var rate by remember { mutableStateOf("") }
        var termValue by remember { mutableStateOf("") }
        var termUnit by remember { mutableStateOf("年") }
        var startDate by remember { mutableStateOf(System.currentTimeMillis()) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新增定期存款") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = principal,
                        onValueChange = { principal = it },
                        label = { Text("本金") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = rate,
                        onValueChange = { rate = it },
                        label = { Text("年利率（%）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = termValue,
                            onValueChange = { termValue = it },
                            label = { Text("存期") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        var unitExpanded by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { unitExpanded = true }) {
                                Text(termUnit)
                            }
                            DropdownMenu(expanded = unitExpanded, onDismissRequest = { unitExpanded = false }) {
                                listOf("年", "月").forEach { unit ->
                                    DropdownMenuItem(
                                        text = { Text(unit) },
                                        onClick = { termUnit = unit; unitExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = "存入日期: ${dateFormat.format(Date(startDate))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = principal.toDoubleOrNull()
                    val r = rate.toDoubleOrNull()
                    val tv = termValue.toIntOrNull()
                    if (p != null && r != null && tv != null && tv > 0) {
                        val cal = Calendar.getInstance().apply { timeInMillis = startDate }
                        if (termUnit == "年") cal.add(Calendar.YEAR, tv) else cal.add(Calendar.MONTH, tv)
                        val maturityDate = cal.timeInMillis

                        val recordId = java.util.UUID.randomUUID().toString()
                        val depositId = java.util.UUID.randomUUID().toString()
                        val now = System.currentTimeMillis()

                        // 插入 record（type="存款"）
                        AccountingRepository.insertRecord(context, AccountingRecord(
                            id = recordId,
                            bookName = AccountingRepository.getLastBookName(context),
                            type = "存款",
                            amount = p.toString(),
                            categoryId = "D001",
                            subcategoryId = "D001_01",
                            categoryName = "存款",
                            subcategoryName = "定期存款",
                            note = "",
                            happenedAt = startDate,
                            accountId = accountId,
                            excludeFromStats = true,
                            createdAt = now,
                            updatedAt = now
                        ))

                        // 插入 fixed_deposits 扩展记录
                        AccountingRepository.insertFixedDeposit(context, FixedDeposit(
                            id = depositId,
                            recordId = recordId,
                            principal = p,
                            interestRate = r,
                            termValue = tv,
                            termUnit = termUnit,
                            startDate = startDate,
                            maturityDate = maturityDate,
                            status = "active",
                            createdAt = now
                        ))

                        // 扣除账户余额（本金从账户中划出）
                        val allAccounts = AccountingRepository.getAllAccounts(context)
                        val account = allAccounts.find { it.id == accountId }
                        if (account != null) {
                            AccountingRepository.updateAccount(context, account.copy(
                                currentBalance = account.currentBalance - p
                            ))
                        }

                        reload()
                    }
                    showAddDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 到期提醒弹窗 ──
    if (showMaturityDialog && maturedDeposits.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showMaturityDialog = false },
            title = { Text("到期提醒") },
            text = {
                Column {
                    Text("以下存款已到期，是否生成收入账单？")
                    Spacer(Modifier.height(8.dp))
                    maturedDeposits.forEach { deposit ->
                        val years = if (deposit.termUnit == "年") deposit.termValue.toDouble() else deposit.termValue / 12.0
                        val interest = deposit.principal * deposit.interestRate / 100.0 * years
                        Text(
                            text = "${deposit.principal}元 · ${deposit.interestRate}% · 利息${String.format("%.2f", interest)}元",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val now = System.currentTimeMillis()
                    maturedDeposits.forEach { deposit ->
                        val years = if (deposit.termUnit == "年") deposit.termValue.toDouble() else deposit.termValue / 12.0
                        val interest = deposit.principal * deposit.interestRate / 100.0 * years

                        // 生成收入账单
                        val incomeId = java.util.UUID.randomUUID().toString()
                        AccountingRepository.insertRecord(context, AccountingRecord(
                            id = incomeId,
                            bookName = AccountingRepository.getLastBookName(context),
                            type = "收入",
                            amount = String.format("%.2f", interest),
                            categoryId = "B001",
                            subcategoryId = "B001_03",
                            categoryName = "收入",
                            subcategoryName = "其他",
                            note = "定期存款到期利息",
                            happenedAt = now,
                            accountId = accountId,
                            excludeFromStats = false,
                            createdAt = now,
                            updatedAt = now
                        ))

                        // 更新存款状态 + 本金归还到账户
                        AccountingRepository.updateFixedDeposit(context, deposit.copy(
                            status = "matured",
                            incomeBillId = incomeId
                        ))

                        val allAccounts = AccountingRepository.getAllAccounts(context)
                        val account = allAccounts.find { it.id == accountId }
                        if (account != null) {
                            AccountingRepository.updateAccount(context, account.copy(
                                currentBalance = account.currentBalance + deposit.principal + interest
                            ))
                        }
                    }
                    reload()
                    showMaturityDialog = false
                }) { Text("生成收入账单") }
            },
            dismissButton = {
                TextButton(onClick = { showMaturityDialog = false }) { Text("暂不处理") }
            }
        )
    }
}
