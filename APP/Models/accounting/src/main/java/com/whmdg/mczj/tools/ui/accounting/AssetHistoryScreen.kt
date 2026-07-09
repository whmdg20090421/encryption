package com.whmdg.mczj.tools.ui.accounting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
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

    var adjustments by remember { mutableStateOf<List<BalanceAdjustment>>(emptyList()) }
    var editMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(accountId) {
        adjustments = AccountingRepository.getAdjustmentsByAccount(context, accountId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("资产明细") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (editMode) { editMode = false; selectedIds = emptySet() } else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!editMode) {
                        TextButton(onClick = { editMode = true }) { Text("编辑") }
                    }
                }
            )
        },
        bottomBar = {
            if (editMode) {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            selectedIds = if (selectedIds.size == adjustments.size) emptySet()
                            else adjustments.map { it.id }.toSet()
                        }) {
                            Text(if (selectedIds.size == adjustments.size) "取消全选" else "全选")
                        }
                        TextButton(
                            onClick = {
                                selectedIds.forEach { id ->
                                    AccountingRepository.deleteAdjustment(context, id)
                                }
                                adjustments = AccountingRepository.getAdjustmentsByAccount(context, accountId)
                                selectedIds = emptySet()
                                editMode = false
                            },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Text("删除(${selectedIds.size})", color = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error else Color.Gray)
                        }
                        TextButton(onClick = { editMode = false; selectedIds = emptySet() }) {
                            Text("完成")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (adjustments.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无余额调整记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding)
            ) {
                items(adjustments, key = { it.id }) { adj ->
                    val isSelected = adj.id in selectedIds
                    ListItem(
                        headlineContent = {
                            Text(
                                text = adj.reason.ifEmpty { "余额调整" },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        supportingContent = {
                            Text(
                                text = dateTimeFormat.format(Date(adj.createdAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                val deltaStr = if (adj.delta >= 0) "+${String.format("%.2f", adj.delta)}" else String.format("%.2f", adj.delta)
                                Text(
                                    text = deltaStr,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (adj.delta >= 0) Color(0xFF4CAF50) else Color(0xFFEF5350)
                                )
                                Text(
                                    text = "余额: ${String.format("%.2f", adj.newBalance)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingContent = if (editMode) {
                            {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        selectedIds = if (isSelected) selectedIds - adj.id else selectedIds + adj.id
                                    }
                                )
                            }
                        } else null,
                        modifier = if (editMode) Modifier.fillMaxWidth().clickable {
                            selectedIds = if (isSelected) selectedIds - adj.id else selectedIds + adj.id
                        } else Modifier.fillMaxWidth()
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }
}
