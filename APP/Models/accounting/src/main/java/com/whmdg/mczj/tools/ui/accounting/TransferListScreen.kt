package com.whmdg.mczj.tools.ui.accounting

import androidx.compose.foundation.clickable
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
fun TransferListScreen(accountId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    var transfers by remember { mutableStateOf<List<AccountingRecord>>(emptyList()) }
    var accountsMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var editMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(accountId) {
        transfers = AccountingRepository.getTransfersByAccount(context, accountId)
        val accounts = AccountingRepository.getAllAccounts(context)
        accountsMap = accounts.associate { it.id to it.name }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("转账记录") },
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
                            selectedIds = if (selectedIds.size == transfers.size) emptySet()
                            else transfers.map { it.id }.toSet()
                        }) {
                            Text(if (selectedIds.size == transfers.size) "取消全选" else "全选")
                        }
                        TextButton(
                            onClick = {
                                selectedIds.forEach { id ->
                                    AccountingRepository.deleteRecord(context, id)
                                }
                                transfers = AccountingRepository.getTransfersByAccount(context, accountId)
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
        if (transfers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无转账记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding)
            ) {
                items(transfers, key = { it.id }) { record ->
                    val isSelected = record.id in selectedIds
                    val fromName = accountsMap[record.accountId] ?: "未知账户"
                    val toName = accountsMap[record.targetAccountId] ?: "未知账户"
                    val amount = record.amount.toDoubleOrNull() ?: 0.0
                    val timeStr = dateFormat.format(Date(record.happenedAt))

                    ListItem(
                        headlineContent = {
                            Text(
                                text = "$fromName → $toName",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1
                            )
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    text = timeStr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        },
                        trailingContent = {
                            Text(
                                text = String.format("%.2f", amount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingContent = if (editMode) {
                            {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        selectedIds = if (isSelected) selectedIds - record.id else selectedIds + record.id
                                    }
                                )
                            }
                        } else null,
                        modifier = if (editMode) Modifier.fillMaxWidth().clickable {
                            selectedIds = if (isSelected) selectedIds - record.id else selectedIds + record.id
                        } else Modifier.fillMaxWidth()
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }
}
