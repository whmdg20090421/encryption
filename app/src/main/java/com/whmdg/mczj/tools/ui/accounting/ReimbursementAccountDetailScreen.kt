package com.whmdg.mczj.tools.ui.accounting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.ui.Screen
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReimbursementAccountDetailScreen(accountId: String, onBack: () -> Unit, onNavigate: (Screen) -> Unit = {}) {
    val context = LocalContext.current
    val entities = remember { AccountingRepository.getReimbursementAccounts(context) }
    val account = remember { entities.find { it.id == accountId } }

    if (account == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("报销账户不存在", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    // 计算报销金额
    val allRecords = remember { AccountingRepository.getAllRecords(context) }
    val pendingRecords = remember {
        allRecords
            .filter { it.reimbursementAccountId == accountId && !it.reimburseStatus }
            .sortedByDescending { it.happenedAt }
    }
    val pendingAmount = remember { pendingRecords.sumOf { it.amount.toDoubleOrNull() ?: 0.0 } }
    val completedAmount = remember {
        allRecords
            .filter { it.reimbursementAccountId == accountId && it.reimburseStatus }
            .sumOf { it.reimburseAmount }
    }
    val totalAmount = pendingAmount + completedAmount

    val themeColor = remember {
        Color(android.graphics.Color.parseColor(getCategoryIconColor(context)))
    }

    // 分类信息
    val categoryDb = remember { AccountingCategoryDb.ensureDefault(context) }
    val categoryLookup = remember(categoryDb) {
        val map = mutableMapOf<String, Pair<String, String>>()
        for ((_, typeMap) in categoryDb.pages) {
            for ((_, cats) in typeMap) {
                for (cat in cats) {
                    map[cat.id] = cat.name to cat.icon
                    for (child in cat.children) {
                        map[child.id] = child.name to child.icon
                    }
                }
            }
        }
        map
    }
    val weekdays = arrayOf("日", "一", "二", "三", "四", "五", "六")
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // 按日期分组
    val groupedRecords = remember(pendingRecords) {
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        pendingRecords.groupBy { dateFormat.format(Date(it.happenedAt)) }
            .toSortedMap(compareByDescending { it })
    }

    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(account.name, fontWeight = FontWeight.Bold)
                    }
                },
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
                            onDismissRequest = { showMenu = false }
                        ) {
                            // 三个点的内容先不做
                            DropdownMenuItem(
                                text = { Text("敬请期待") },
                                onClick = { showMenu = false }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // 报销统计卡片
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "待报销",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = formatAmount(pendingAmount),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = themeColor
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "已报销：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatAmount(completedAmount),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeColor
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "总报销：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatAmount(totalAmount),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeColor
                            )
                        }
                    }
                }
            }

            // 未报销账单列表
            if (pendingRecords.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("暂无待报销账单", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                groupedRecords.forEach { (dateKey, dayRecords) ->
                    item {
                        val first = dayRecords.first()
                        val cal = Calendar.getInstance().apply { timeInMillis = first.happenedAt }
                        val month = cal.get(Calendar.MONTH) + 1
                        val day = cal.get(Calendar.DAY_OF_MONTH)
                        val weekday = weekdays[cal.get(Calendar.DAY_OF_WEEK) - 1]

                        Column {
                            // 日期头
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${month}月${day}日 周$weekday",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // 卡片
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 4.dp),
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
                                        val amountDisplay = String.format("%.2f", record.amount.toDoubleOrNull() ?: 0.0)
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
                                                Icon(
                                                    imageVector = materialIcon(icon),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(24.dp),
                                                    tint = themeColor
                                                )
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
                                                    Text(
                                                        text = amountDisplay,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = themeColor
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
}
