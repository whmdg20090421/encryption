package com.whmdg.mczj.tools.ui.accounting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.ui.components.ColorIconImage
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapitalFlowScreen(accountId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // 默认本月范围
    val cal = remember { Calendar.getInstance() }
    var startMillis by remember {
        val c = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        mutableStateOf(c.timeInMillis)
    }
    var endMillis by remember {
        val c = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH)); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }
        mutableStateOf(c.timeInMillis)
    }

    // 加载数据
    var records by remember { mutableStateOf<List<AccountingRecord>>(emptyList()) }
    var categoryLookup by remember { mutableStateOf<Map<String, Triple<String, String, String?>>>(emptyMap()) }

    LaunchedEffect(accountId, startMillis, endMillis) {
        val allRecords = AccountingRepository.getRecordsByAccount(context, accountId)
        records = allRecords.filter { it.happenedAt in startMillis..endMillis }
        val flat = AccountingRepository.getAllCategoriesFlat(context)
        categoryLookup = flat.associate { it.first to Triple(it.second, "", it.third) }
    }

    val totalExpense = records.filter { it.type == "支出" }.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
    val totalIncome = records.filter { it.type == "收入" }.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }

    // 按日期分组
    val grouped = records.groupBy {
        val c = Calendar.getInstance().apply { timeInMillis = it.happenedAt }
        "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH) + 1}-${c.get(Calendar.DAY_OF_MONTH)}"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("流水对账") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 日期范围 + 汇总
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "${dateFormat.format(Date(startMillis))} ~ ${dateFormat.format(Date(endMillis))}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("支出", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = String.format("%.2f", totalExpense),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFFEF5350)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("收入", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = String.format("%.2f", totalIncome),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                        }
                    }
                }
            }

            // 按日期分组的记录列表
            grouped.forEach { (dateStr, dayRecords) ->
                item {
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                }
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column {
                            dayRecords.forEachIndexed { index, record ->
                                val catInfo = categoryLookup[record.categoryId]
                                val subInfo = record.subcategoryId?.let { categoryLookup[it] }
                                val parentName = record.categoryName.ifEmpty { catInfo?.first ?: record.categoryId }
                                val childName = record.subcategoryName ?: subInfo?.first
                                val displayName = if (childName != null) "$parentName-$childName" else parentName
                                val icon = subInfo?.second?.ifEmpty { null } ?: catInfo?.second?.ifEmpty { null } ?: "category"
                                val overlay = subInfo?.third ?: catInfo?.third
                                val isExpense = record.type == "支出" || record.type == "债务"
                                val amountPrefix = if (isExpense) "-" else "+"
                                val amountColor = if (isExpense) Color(0xFFEF5350) else Color(0xFF4CAF50)
                                val amountDisplay = String.format("%.2f", record.amount.toDoubleOrNull() ?: 0.0)
                                val timeStr = timeFormat.format(Date(record.happenedAt))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
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
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1
                                        )
                                        if (record.note.isNotEmpty()) {
                                            Text(
                                                text = record.note,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "$amountPrefix$amountDisplay",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = amountColor
                                        )
                                        Text(
                                            text = timeStr,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (index < dayRecords.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 底部留白
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
