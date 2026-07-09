package com.whmdg.mczj.tools.ui.accounting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetHistoryScreen(accountId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val dateTimeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    data class BalanceEntry(
        val recordId: String,
        val time: Long,
        val typeName: String,
        val note: String,
        val delta: Double,
        val balance: Double
    )

    var entries by remember { mutableStateOf<List<BalanceEntry>>(emptyList()) }

    LaunchedEffect(accountId) {
        val account = AccountingRepository.getAllAccounts(context).find { it.id == accountId } ?: return@LaunchedEffect
        // 普通记录（收入/支出/存款，account_id = 此账户）
        val normalRecords = AccountingRepository.getRecordsByAccount(context, accountId)
        // 转账记录（此账户作为转出或转入）
        val transferRecords = AccountingRepository.getTransfersByAccount(context, accountId)
        // 合并去重
        val records = (normalRecords + transferRecords).distinctBy { it.id }.sortedBy { it.happenedAt }

        val result = mutableListOf<BalanceEntry>()
        var running = account.initialAmount

        for (r in records) {
            val amount = r.amount.toDoubleOrNull() ?: 0.0
            val delta = when (r.type) {
                "支出" -> -amount
                "收入" -> amount
                "转账" -> if (r.targetAccountId == accountId) amount else -amount
                "存款" -> -amount
                "调整" -> amount  // amount 本身带符号（正=增加，负=减少）
                else -> 0.0
            }
            running += delta
            result.add(BalanceEntry(
                recordId = r.id,
                time = r.happenedAt,
                typeName = r.type,
                note = r.note.ifEmpty { r.subcategoryName ?: r.categoryName },
                delta = delta,
                balance = running
            ))
            // 报销：支出类型且已报销时，追加一条报销入账
            if (r.type == "支出" && r.reimburseStatus && r.reimburseAmount > 0) {
                running += r.reimburseAmount
                result.add(BalanceEntry(
                    recordId = "${r.id}_reimburse",
                    time = r.updatedAt ?: r.happenedAt,
                    typeName = "报销",
                    note = r.note.ifEmpty { r.subcategoryName ?: r.categoryName },
                    delta = r.reimburseAmount,
                    balance = running
                ))
            }
        }

        entries = result.reversed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("资产明细") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无资产变动记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding)
            ) {
                items(entries, key = { "${it.recordId}_${it.time}" }) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = entry.typeName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    text = dateTimeFormat.format(Date(entry.time)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (entry.note.isNotEmpty()) {
                                    Text(
                                        text = entry.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                val deltaStr = if (entry.delta >= 0) "+${String.format("%.2f", entry.delta)}" else String.format("%.2f", entry.delta)
                                Text(
                                    text = deltaStr,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (entry.delta >= 0) Color(0xFF4CAF50) else Color(0xFFEF5350)
                                )
                                Text(
                                    text = "余额: ${String.format("%.2f", entry.balance)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }
}
