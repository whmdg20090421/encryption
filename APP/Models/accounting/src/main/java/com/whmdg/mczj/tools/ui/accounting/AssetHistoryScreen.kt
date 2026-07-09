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
        val records = AccountingRepository.getRecordsByAccount(context, accountId)
            .sortedBy { it.happenedAt }

        val result = mutableListOf<BalanceEntry>()
        var running = account.initialAmount

        for (r in records) {
            val amount = r.amount.toDoubleOrNull() ?: 0.0
            val delta = when (r.type) {
                "支出" -> -amount
                "收入" -> amount
                "转账" -> if (r.accountId == accountId) -amount else amount
                "存款" -> -amount
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
        }

        // 追加手动调整（如果有）：当余额与最后一条记录计算值不一致时
        val finalBalance = account.currentBalance
        if (result.isEmpty() || kotlin.math.abs(result.last().balance - finalBalance) > 0.01) {
            result.add(BalanceEntry(
                recordId = "",
                time = System.currentTimeMillis(),
                typeName = "手动调整",
                note = "余额调整",
                delta = finalBalance - running,
                balance = finalBalance
            ))
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
