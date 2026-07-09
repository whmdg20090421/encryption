package com.whmdg.mczj.tools.ui.accounting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.whmdg.mczj.tools.ui.Screen
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(accountId: String, onBack: () -> Unit, onNavigate: (Screen) -> Unit = {}) {
    val context = LocalContext.current
    var account by remember {
        mutableStateOf(AccountingRepository.getAllAccounts(context).find { it.id == accountId })
    }

    if (account == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("账户不存在", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    val currentAccount = account!!

    // 菜单状态
    var showMenu by remember { mutableStateOf(false) }
    // 弹窗状态
    var showEditInfoDialog by remember { mutableStateOf(false) }
    var showChangeAmountDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showTypeSelector by remember { mutableStateOf(false) }
    // 编辑信息临时状态
    var editType by remember(currentAccount) { mutableStateOf(currentAccount.type) }

    // 刷新账户数据
    fun refreshAccount() {
        account = AccountingRepository.getAllAccounts(context).find { it.id == accountId }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(currentAccount.name, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.shadow(elevation = 4.dp),
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
                            modifier = Modifier.wrapContentWidth()
                        ) {
                            DropdownMenuItem(
                                text = { Text("流水对账") },
                                onClick = {
                                    showMenu = false
                                    onNavigate(Screen.CapitalFlow(accountId))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("资产明细") },
                                onClick = {
                                    showMenu = false
                                    onNavigate(Screen.AssetHistory(accountId))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("转账记录") },
                                onClick = {
                                    showMenu = false
                                    onNavigate(Screen.TransferList(accountId))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("定期存款") },
                                onClick = {
                                    showMenu = false
                                    onNavigate(Screen.FixedDepositManager(accountId))
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("编辑信息") },
                                onClick = {
                                    showMenu = false
                                    showEditInfoDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("更改金额") },
                                onClick = {
                                    showMenu = false
                                    showChangeAmountDialog = true
                                }
                            )
                            HorizontalDivider()
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
        }
    ) { innerPadding ->
        // 加载该账户的交易记录
        val records = remember(account) {
            AccountingRepository.getAllRecords(context)
                .filter { it.accountId == accountId }
                .sortedByDescending { it.happenedAt }
        }
        val categoryDb = remember { AccountingCategoryDb.ensureDefault(context) }
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
        val weekdays = arrayOf("日", "一", "二", "三", "四", "五", "六")
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        // 按日期分组
        val groupedRecords = remember(records) {
            val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            records.groupBy { dateFormat.format(Date(it.happenedAt)) }
                .toSortedMap(compareByDescending { it })
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // 账户卡片
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    AccountCard(account = currentAccount, showStats = currentAccount.category == "tradable")
                }
            }

            // 交易记录
            if (records.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("暂无交易记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                groupedRecords.forEach { (dateKey, dayRecords) ->
                    // 计算当日收支汇总
                    var dayExpense = 0.0
                    var dayIncome = 0.0
                    for (r in dayRecords) {
                        if (r.reimbursementAccountId != null) continue
                        val v = r.amount.toDoubleOrNull() ?: 0.0
                        when (r.type) {
                            "支出" -> dayExpense += v
                            "收入" -> dayIncome += v
                        }
                    }

                    item {
                        val first = dayRecords.first()
                        val cal = Calendar.getInstance().apply { timeInMillis = first.happenedAt }
                        val month = cal.get(Calendar.MONTH) + 1
                        val day = cal.get(Calendar.DAY_OF_MONTH)
                        val weekday = weekdays[cal.get(Calendar.DAY_OF_WEEK) - 1]

                        val summaryParts = mutableListOf<String>()
                        if (dayExpense > 0) summaryParts.add("支 %.0f".format(dayExpense))
                        if (dayIncome > 0) summaryParts.add("收 %.0f".format(dayIncome))
                        val summary = summaryParts.joinToString("  ")

                        Column {
                            // 日期头
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${month}月${day}日 周$weekday",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.weight(1f))
                                if (summary.isNotEmpty()) {
                                    Text(
                                        text = summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // 卡片
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFF5F5F5)
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column {
                                    dayRecords.forEachIndexed { index, record ->
                                        val catInfo = categoryLookup[record.categoryId]
                                        val subInfo = record.subcategoryId?.let { categoryLookup[it] }
                                        val parentName = record.categoryName.ifEmpty { catInfo?.first ?: record.categoryId }
                                        val childName = record.subcategoryName ?: subInfo?.first
                                        val displayName = if (childName != null) "$parentName-$childName" else parentName
                                        val icon = subInfo?.second ?: catInfo?.second ?: "category"
                                        val overlay = subInfo?.third ?: catInfo?.third
                                        val isExpense = record.type == "支出" || record.type == "债务"
                                        val isReimbursable = record.reimbursementAccountId != null
                                        val amountPrefix = if (isExpense) "-" else "+"
                                        val amountColor = if (isReimbursable) MaterialTheme.colorScheme.onSurface
                                                          else if (isExpense) Color(0xFFEF5350)
                                                          else Color(0xFF4CAF50)
                                        val amountDisplay = String.format("%.2f", record.amount.toDoubleOrNull() ?: 0.0)
                                        val discountBeforeDisplay = record.discountBefore?.toDoubleOrNull()?.let { String.format("%.2f", it) }
                                        val timeStr = timeFormat.format(Date(record.happenedAt))

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onNavigate(Screen.AccountingDetail(record.bookName, record.id)) }
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // 左侧图标框
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                ColorIconImage(buildInId = icon, size = 24.dp, overlay = overlay)
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            // 右侧内容
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = displayName,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (discountBeforeDisplay != null) {
                                                        Text(
                                                            text = "$amountPrefix$discountBeforeDisplay",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = Color.Gray,
                                                            textDecoration = TextDecoration.LineThrough
                                                        )
                                                        Spacer(Modifier.width(4.dp))
                                                    }
                                                    Text(
                                                        text = "$amountPrefix$amountDisplay",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = amountColor
                                                    )
                                                }
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = timeStr,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    if (record.note.isNotEmpty()) {
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            text = record.note,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        if (index < dayRecords.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 编辑信息弹窗 ──
    if (showEditInfoDialog) {
        var editName by remember { mutableStateOf(currentAccount.name) }
        var editNote by remember { mutableStateOf(currentAccount.note) }
        val editTypeLabel = accountTypeConfigs[editType]?.label ?: editType

        AlertDialog(
            onDismissRequest = { showEditInfoDialog = false },
            title = { Text("编辑信息") },
            text = {
                Column {
                    TextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("账户名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = editNote,
                        onValueChange = { editNote = it },
                        label = { Text("备注") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    // 类型选择行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showTypeSelector = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("类型", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        Text(editTypeLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editName.isNotBlank()) {
                        val newCategory = accountTypeConfigs[editType]?.category ?: currentAccount.category
                        val updated = currentAccount.copy(name = editName, note = editNote, type = editType, category = newCategory)
                        AccountingRepository.updateAccount(context, updated)
                        refreshAccount()
                    }
                    showEditInfoDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEditInfoDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 类型选择弹窗 ──
    if (showTypeSelector) {
        val tradableTypes = accountTypeConfigs.filter { it.value.category == "tradable" }
        val valuationTypes = accountTypeConfigs.filter { it.value.category == "valuation" }

        AlertDialog(
            onDismissRequest = { showTypeSelector = false },
            title = { Text("选择类型") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("资金账户", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    TypeRadioList(tradableTypes, editType) { editType = it }
                    Spacer(Modifier.height(16.dp))
                    Text("估值账户", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    TypeRadioList(valuationTypes, editType) { editType = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTypeSelector = false
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showTypeSelector = false }) { Text("取消") }
            }
        )
    }

    // ── 更改金额弹窗 ──
    if (showChangeAmountDialog) {
        var newAmount by remember { mutableStateOf(String.format("%.2f", currentAccount.currentBalance)) }

        AlertDialog(
            onDismissRequest = { showChangeAmountDialog = false },
            title = { Text("更改金额") },
            text = {
                Column {
                    Text("当前余额: ${String.format("%.2f", currentAccount.currentBalance)}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = newAmount,
                        onValueChange = { newAmount = it },
                        label = { Text("新余额") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newBalance = newAmount.toDoubleOrNull()
                    if (newBalance != null) {
                        val oldBalance = currentAccount.currentBalance
                        val updated = currentAccount.copy(currentBalance = newBalance)
                        AccountingRepository.updateAccount(context, updated)
                        // 写入余额调整日志
                        AccountingRepository.insertBalanceAdjustment(context, BalanceAdjustment(
                            id = java.util.UUID.randomUUID().toString(),
                            accountId = accountId,
                            oldBalance = oldBalance,
                            newBalance = newBalance,
                            delta = newBalance - oldBalance,
                            reason = "手动调整",
                            createdAt = System.currentTimeMillis()
                        ))
                        refreshAccount()
                    }
                    showChangeAmountDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showChangeAmountDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 删除确认弹窗 ──
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除账户") },
            text = { Text("确定要删除「${currentAccount.name}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    AccountingRepository.deleteAccount(context, accountId)
                    showDeleteDialog = false
                    onBack()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}

// ── 账户卡片 ──

@Composable
private fun AccountCard(account: AccountingAccount, showStats: Boolean) {
    val context = LocalContext.current
    val config = accountTypeConfigs[account.type]
    val themeColor = remember {
        Color(android.graphics.Color.parseColor(getCategoryIconColor(context)))
    }
    val typeLabel = config?.label ?: account.type
    val typeSvg = config?.svgPath ?: "file:///android_asset/icons/other_account.svg"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        // 渐变背景
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(themeColor, themeColor.copy(alpha = 0.8f))
                    )
                )
        ) {
            // 装饰圆圈
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .offset(x = (-20).dp, y = (-20).dp)
                    .align(Alignment.TopEnd)
                    .background(
                        Color.White.copy(alpha = 0.1f),
                        shape = CircleShape
                    )
            )

            Column(
                modifier = Modifier.padding(14.dp)
            ) {
                // 顶部行：图标 + 名称 + 备注
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                Color.White.copy(alpha = 0.2f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = typeSvg,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            account.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (account.note.isNotEmpty()) {
                            Text(
                                account.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (showStats) {
                    // 资金账户：余额 / 收入 / 支出
                    Row {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("余额", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                            Text(
                                "${String.format("%.2f", account.currentBalance)}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("收入", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                            Text(
                                "${String.format("%.2f", account.income)}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("支出", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                            Text(
                                "${String.format("%.2f", account.expense)}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                    }
                } else {
                    // 估值账户：当前估值
                    Text(
                        "当前估值",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            "${String.format("%.2f", account.currentBalance)}",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        // 更新日期
                        val dateStr = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(account.updatedAt))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Update,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color.White.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                dateStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // 备注
                if (account.note.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        account.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TypeRadioList(
    types: Map<String, AccountTypeConfig>,
    selected: String,
    onSelect: (String) -> Unit
) {
    types.forEach { (typeKey, config) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(typeKey) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected == typeKey, onClick = { onSelect(typeKey) })
            Spacer(Modifier.width(8.dp))
            Text(config.label)
        }
    }
}
