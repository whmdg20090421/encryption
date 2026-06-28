package com.whmdg.mczj.tools.ui.accounting

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────
// 数据模型
// ─────────────────────────────────────────────

private data class AccountMappingInfo(val type: String, val keepName: Boolean, val newName: String)

private data class FieldDef(val key: String, val label: String)

private val FIELD_DEFS = listOf(
    FieldDef("时间", "日期"),
    FieldDef("类型", "收支类型"),
    FieldDef("金额", "金额"),
    FieldDef("分类", "类别"),
    FieldDef("二级分类", "二级分类"),
    FieldDef("账户", "账户"),
    FieldDef("账本", "账本"),
    FieldDef("备注", "备注"),
    FieldDef("优惠前金额", "优惠前金额"),
    FieldDef("报销账户", "报销账户"),
    FieldDef("报销金额", "报销金额"),
    FieldDef("退款", "退款"),
    FieldDef("地址", "地址"),
)

private val HEADER_ALIASES = mapOf(
    // 时间
    "时间" to "时间", "日期" to "时间", "date" to "时间", "time" to "时间",
    "交易时间" to "时间", "消费时间" to "时间", "记账时间" to "时间", "创建时间" to "时间",
    // 类型
    "类型" to "类型", "收支类型" to "类型", "type" to "类型", "收支" to "类型", "收/支" to "类型",
    "收入支出" to "类型", "收入/支出" to "类型", "kind" to "类型",
    // 金额
    "金额" to "金额", "amount" to "金额", "花费" to "金额", "消费金额" to "金额", "money" to "金额",
    // 分类
    "分类" to "分类", "类别" to "分类", "category" to "分类", "类目" to "分类",
    "一级分类" to "分类", "消费分类" to "分类",
    // 二级分类
    "二级分类" to "二级分类", "子分类" to "二级分类", "sub_category" to "二级分类",
    "二级类目" to "二级分类", "子类" to "二级分类", "细分" to "二级分类",
    // 账户
    "账户" to "账户", "account" to "账户", "支付方式" to "账户", "付款方式" to "账户", "来源" to "账户",
    // 账本
    "账本" to "账本", "book" to "账本", "本子" to "账本",
    // 备注
    "备注" to "备注", "note" to "备注", "说明" to "备注", "描述" to "备注",
    "商品" to "备注", "项目" to "备注", "内容" to "备注", "remark" to "备注",
    // 优惠前金额
    "优惠前金额" to "优惠前金额", "优惠" to "优惠前金额", "原价" to "优惠前金额",
    // 报销账户
    "报销账户" to "报销账户", "reimbursement" to "报销账户", "报销" to "报销账户",
    // 报销金额
    "报销金额" to "报销金额", "reimbursement_amount" to "报销金额", "报销额" to "报销金额",
    // 退款
    "退款" to "退款", "refund" to "退款", "退款金额" to "退款",
    // 地址
    "地址" to "地址", "address" to "地址", "位置" to "地址", "地点" to "地址",
)

// ─────────────────────────────────────────────
// CSV 解析
// ─────────────────────────────────────────────

private fun parseCsvText(csvText: String): List<List<String>> {
    val cleaned = csvText.removePrefix("﻿") // 去除 UTF-8 BOM
    val lines = cleaned.lines().filter { it.isNotBlank() }
    val parsed = lines.map { line ->
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inQuotes -> inQuotes = true
                c == '"' && inQuotes -> {
                    if (i + 1 < line.length && line[i + 1] == '"') { current.append('"'); i++ }
                    else inQuotes = false
                }
                c == ',' && !inQuotes -> { result.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        result
    }
    // 过滤列数不足的数据行（与 importFromCsv 的 cols.size < 3 一致）
    return parsed.filterIndexed { idx, cols ->
        idx == 0 || cols.size >= 3
    }
}

// ─────────────────────────────────────────────
// 自动检测列映射
// ─────────────────────────────────────────────

private fun autoDetectColumnMapping(headers: List<String>): Map<String, Int?> {
    val mapping = mutableMapOf<String, Int?>()
    for (fieldDef in FIELD_DEFS) mapping[fieldDef.key] = null
    for (i in headers.indices) {
        val raw = headers[i].trim().removePrefix("﻿")
        val h = raw.lowercase().replace(" ", "")
        val key = HEADER_ALIASES[h] ?: HEADER_ALIASES[raw]
        if (key != null && mapping[key] == null) mapping[key] = i
    }
    return mapping
}

/** 从已解析行中提取指定列的不重复值 */
private fun extractDistinctValues(rows: List<List<String>>, columnIndex: Int): List<String> {
    val set = linkedSetOf<String>()
    for (i in 1 until rows.size) {
        val value = rows[i].getOrNull(columnIndex)?.trim() ?: ""
        if (value.isNotEmpty()) set.add(value)
    }
    return set.toList()
}

/** 解析单行 CSV（处理引号转义） */
private fun parseSingleCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && !inQuotes -> inQuotes = true
            c == '"' && inQuotes -> {
                if (i + 1 < line.length && line[i + 1] == '"') { current.append('"'); i++ }
                else inQuotes = false
            }
            c == ',' && !inQuotes -> { result.add(current.toString()); current.clear() }
            else -> current.append(c)
        }
        i++
    }
    result.add(current.toString())
    return result
}

/**
 * 对 CSV 原始文本做早期归一化：
 * 类别="收入"/"支出" → 二级分类提升为一级分类，二级清空。
 * 在解析 rows 之前调用，确保后续所有步骤基于归一化数据。
 */
private fun normalizeCsvText(csvText: String, catIdx: Int?, subCatIdx: Int?): String {
    if (catIdx == null || subCatIdx == null) return csvText
    val lines = csvText.lines().filter { it.isNotBlank() }.toMutableList()
    if (lines.size < 2) return csvText

    for (i in 1 until lines.size) {
        val cols = parseSingleCsvLine(lines[i]).toMutableList()
        if (catIdx >= cols.size || subCatIdx >= cols.size) continue
        val cat = cols[catIdx].trim()
        if (cat == "收入" || cat == "支出") {
            cols[catIdx] = cols[subCatIdx]
            cols[subCatIdx] = ""
            lines[i] = cols.joinToString(",") { v ->
                if (v.contains(',') || v.contains('"') || v.contains('\n'))
                    "\"${v.replace("\"", "\"\"")}\"" else v
            }
        }
    }
    return lines.joinToString("\n")
}

/** 自动匹配 CSV 分类名到应用分类（精确匹配 + 子串模糊匹配，歧义时放弃） */
private fun autoMatchCategories(
    csvNames: List<String>,
    appCategories: List<AccountingCategory>
): Map<String, String?> {
    val result = mutableMapOf<String, String?>()
    for (name in csvNames) {
        // 精确匹配优先
        val exact = appCategories.find { it.name == name }
        if (exact != null) { result[name] = exact.id; continue }
        // 子串模糊匹配：CSV名⊂应用名 或 应用名⊂CSV名
        val candidates = appCategories.filter { app ->
            name.contains(app.name) || app.name.contains(name)
        }
        result[name] = if (candidates.size == 1) candidates[0].id else null
    }
    return result
}

// ─────────────────────────────────────────────
// 主入口
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CsvImportFlowScreen(
    csvText: String,
    onImportDone: (Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 解析 CSV（含"收入"/"支出"归一化）
    var rows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var parsing by remember { mutableStateOf(true) }
    var normalizedCsvText by remember { mutableStateOf(csvText) }
    LaunchedEffect(csvText) {
        rows = withContext(Dispatchers.IO) {
            // 先解析表头，获取列索引用于归一化
            val firstPass = parseCsvText(csvText)
            if (firstPass.size >= 2) {
                val headers = firstPass[0].map { it.trim() }
                val detected = autoDetectColumnMapping(headers)
                val catIdx = detected["分类"]
                val subCatIdx = detected["二级分类"]
                // 归一化"收入"/"支出"行
                val normalized = normalizeCsvText(csvText, catIdx, subCatIdx)
                normalizedCsvText = normalized
                parseCsvText(normalized)
            } else {
                firstPass
            }
        }
        parsing = false
    }

    var step by remember { mutableIntStateOf(0) }

    // 字段→列索引映射
    val columnMapping = remember { mutableStateMapOf<String, Int?>() }

    // 一级分类映射：CSV名 → 目标分类ID（null=保持原名，自动创建）
    val categoryMapping = remember { mutableStateMapOf<String, String?>() }
    // 二级分类映射：CSV名 → 目标子分类ID（null=保持原名，自动创建）
    val subcategoryMapping = remember { mutableStateMapOf<String, String?>() }

    var distinctCategories by remember { mutableStateOf<List<String>>(emptyList()) }
    var distinctSubcategories by remember { mutableStateOf<List<String>>(emptyList()) }

    // 账户映射：CSV账户名 → AccountMappingInfo
    val accountMapping = remember { mutableStateMapOf<String, AccountMappingInfo>() }
    var distinctAccounts by remember { mutableStateOf<List<String>>(emptyList()) }

    // 加载应用已有账户
    val existingAccounts = remember {
        AccountingRepository.getAllAccounts(context)
    }

    // 加载应用分类（一级和二级）
    val parentCategories = remember {
        val db = AccountingCategoryDb.defaultCategories()
        val expense = db.getCategories("记账页", "支出")
        val income = db.getCategories("记账页", "收入")
        expense + income
    }
    // 所有二级分类（展开 children）
    val allSubcategories = remember(parentCategories) {
        parentCategories.flatMap { parent ->
            parent.children.map { child -> parent to child }
        }
    }

    // 自动检测列映射
    LaunchedEffect(rows) {
        if (rows.size >= 2) {
            val headers = rows[0].map { it.trim() }
            val detected = autoDetectColumnMapping(headers)
            columnMapping.clear()
            columnMapping.putAll(detected)
        }
    }

    // 导入状态
    var importing by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showReplaceWarning by remember { mutableStateOf(false) }
    var mappingError by remember { mutableStateOf<String?>(null) }

    // 确认弹窗
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { if (!importing) showConfirmDialog = false },
            title = { Text("确认导入") },
            text = { Text("即将导入 ${rows.size - 1} 条记账记录。") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = false
                    scope.launch {
                        importing = true
                        try {
                            val count = withContext(Dispatchers.IO) {
                                doImport(
                                    context, normalizedCsvText, rows,
                                    columnMapping, categoryMapping, subcategoryMapping,
                                    accountMapping, replaceMode = false
                                )
                            }
                            android.widget.Toast.makeText(context, "成功导入 $count 条记录", android.widget.Toast.LENGTH_SHORT).show()
                            onImportDone(count)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "导入失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                        } finally {
                            importing = false
                        }
                    }
                }) { Text("追加导入") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showConfirmDialog = false }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        showConfirmDialog = false
                        showReplaceWarning = true
                    }) { Text("替换全部", color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }

    // 替换模式二次确认
    if (showReplaceWarning) {
        AlertDialog(
            onDismissRequest = { if (!importing) showReplaceWarning = false },
            title = { Text("警告", color = MaterialTheme.colorScheme.error) },
            text = { Text("此操作将删除所有现有记账记录，然后导入 CSV 数据。\n\n建议在操作前先使用导出功能备份当前数据。") },
            confirmButton = {
                TextButton(onClick = {
                    showReplaceWarning = false
                    scope.launch {
                        importing = true
                        try {
                            val count = withContext(Dispatchers.IO) {
                                doImport(
                                    context, normalizedCsvText, rows,
                                    columnMapping, categoryMapping, subcategoryMapping,
                                    accountMapping, replaceMode = true
                                )
                            }
                            android.widget.Toast.makeText(context, "成功导入 $count 条记录", android.widget.Toast.LENGTH_SHORT).show()
                            onImportDone(count)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "导入失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                        } finally {
                            importing = false
                        }
                    }
                }) { Text("确认替换", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceWarning = false }) { Text("取消") }
            }
        )
    }

    // 导入中遮罩
    if (importing) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 6.dp) {
                Column(modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("正在导入...", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        return
    }

    // ── 页面主体 ──
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(when (step) {
                0 -> "CSV 导入 - 字段映射"
                1 -> "CSV 导入 - 账户映射"
                2 -> "CSV 导入 - 分类映射"
                else -> "CSV 导入"
            }) },
            navigationIcon = {
                IconButton(onClick = { if (step > 0) step-- else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        )

        if (parsing) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        if (rows.size < 2) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("CSV 文件无有效数据行", color = MaterialTheme.colorScheme.error)
            }
            return@Column
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (targetState > initialState) slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                else slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
            },
            modifier = Modifier.weight(1f),
            label = "stepTransition"
        ) { currentStep ->
            if (currentStep == 0) {
                FieldMappingStep(
                    rows = rows,
                    columnMapping = columnMapping,
                    mappingError = mappingError,
                    onNext = {
                        // 校验必需字段
                        val missing = listOf("时间", "类型", "金额").filter { columnMapping[it] == null }
                        if (missing.isNotEmpty()) {
                            mappingError = "请映射必需字段：${missing.joinToString("、")}"
                            return@FieldMappingStep
                        }
                        mappingError = null

                        // 提取账户名
                        val accIdx = columnMapping["账户"]
                        if (accIdx != null) {
                            distinctAccounts = extractDistinctValues(rows, accIdx)
                            // 自动匹配已有账户
                            accountMapping.clear()
                            for (accName in distinctAccounts) {
                                val existing = existingAccounts.find { it.name == accName }
                                if (existing != null) {
                                    accountMapping[accName] = AccountMappingInfo(existing.type, true, "")
                                } else {
                                    accountMapping[accName] = AccountMappingInfo("cash", false, accName)
                                }
                            }
                        }

                        // 提取分类名
                        val catIdx = columnMapping["分类"]
                        val subCatIdx = columnMapping["二级分类"]
                        if (catIdx != null) {
                            distinctCategories = extractDistinctValues(rows, catIdx)
                        }
                        if (subCatIdx != null) {
                            distinctSubcategories = extractDistinctValues(rows, subCatIdx)
                        }
                        if (catIdx != null) {
                            val matched = autoMatchCategories(distinctCategories, parentCategories)
                            categoryMapping.clear()
                            categoryMapping.putAll(matched)
                        }
                        if (subCatIdx != null) {
                            val matched = autoMatchCategories(distinctSubcategories, allSubcategories.map { it.second })
                            subcategoryMapping.clear()
                            subcategoryMapping.putAll(matched)
                        }

                        // 如果没有账户列，直接跳到分类映射
                        step = if (accIdx != null && distinctAccounts.isNotEmpty()) 1 else 2
                    }
                )
            } else if (currentStep == 1) {
                AccountMappingStep(
                    distinctAccounts = distinctAccounts,
                    accountMapping = accountMapping,
                    existingAccounts = existingAccounts,
                    onNext = { step = 2 },
                    onBack = { step = 0 }
                )
            } else {
                CategoryMappingStep(
                    distinctCategories = distinctCategories,
                    distinctSubcategories = distinctSubcategories,
                    categoryMapping = categoryMapping,
                    subcategoryMapping = subcategoryMapping,
                    parentCategories = parentCategories,
                    allSubcategories = allSubcategories,
                    onConfirm = { showConfirmDialog = true },
                    onBack = { step = if (distinctAccounts.isNotEmpty()) 1 else 0 }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────
// Step 1: 字段映射
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldMappingStep(
    rows: List<List<String>>,
    columnMapping: MutableMap<String, Int?>,
    mappingError: String?,
    onNext: () -> Unit
) {
    val headers = rows[0].map { it.trim() }
    val columnOptions = headers.mapIndexed { i, h -> i to h }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            item {
                Text(
                    "已解析 ${rows.size - 1} 条记录，请确认列对应关系：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }
            items(FIELD_DEFS) { fieldDef ->
                FieldMappingRow(
                    label = fieldDef.label,
                    selectedIndex = columnMapping[fieldDef.key],
                    options = columnOptions,
                    onSelect = { columnMapping[fieldDef.key] = it }
                )
            }
            item {
                Spacer(Modifier.height(20.dp))
                Text("数据预览：", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                CsvPreviewTable(headers = headers, dataRows = rows.drop(1).take(5))
            }
        }
        Surface(tonalElevation = 2.dp, shadowElevation = 4.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (mappingError != null) {
                    Text(
                        mappingError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onNext) { Text("下一步: 分类映射") }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// Step 2: 账户映射
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountMappingStep(
    distinctAccounts: List<String>,
    accountMapping: MutableMap<String, AccountMappingInfo>,
    existingAccounts: List<AccountingAccount>,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val typeOptions = listOf(
        "cash" to "现金",
        "wechat" to "微信钱包",
        "alipay" to "支付宝",
        "bank_card" to "银行卡",
        "custom" to "自定义"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            item {
                Text(
                    "以下账户在应用中不存在，请确认映射方式：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }
            items(distinctAccounts) { csvAccName ->
                val info = accountMapping[csvAccName]
                val isMatched = existingAccounts.any { it.name == csvAccName }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isMatched)
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        // CSV 账户名
                        Text(
                            csvAccName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (isMatched) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        if (isMatched) {
                            Text(
                                "已匹配应用账户",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (info != null) {
                            Spacer(Modifier.height(8.dp))

                            // 账户类型选择
                            var typeExpanded by remember { mutableStateOf(false) }
                            val selectedType = typeOptions.find { it.first == info.type }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("类型:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(40.dp))
                                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }, modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = selectedType?.second ?: "现金",
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        singleLine = true
                                    )
                                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                                        typeOptions.forEach { (key, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                onClick = {
                                                    accountMapping[csvAccName] = info.copy(type = key)
                                                    typeExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(4.dp))

                            // 保持原名 / 重命名
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = info.keepName,
                                    onClick = { accountMapping[csvAccName] = info.copy(keepName = true) }
                                )
                                Text("保持原名", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(16.dp))
                                RadioButton(
                                    selected = !info.keepName,
                                    onClick = { accountMapping[csvAccName] = info.copy(keepName = false) }
                                )
                                Text("重命名", style = MaterialTheme.typography.bodySmall)
                            }

                            // 重命名输入框
                            if (!info.keepName) {
                                OutlinedTextField(
                                    value = info.newName,
                                    onValueChange = { accountMapping[csvAccName] = info.copy(newName = it) },
                                    label = { Text("新名称") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    singleLine = true
                                )
                            }
                        }
                    }
                }
            }
        }

        Surface(tonalElevation = 2.dp, shadowElevation = 4.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(onClick = onBack) { Text("上一步") }
                Button(onClick = onNext) { Text("下一步: 分类映射") }
            }
        }
    }
}

// ─────────────────────────────────────────────
// Step 3: 分类映射（一级 + 二级分开）
// ─────────────────────────────────────────────

@Composable
private fun CategoryMappingStep(
    distinctCategories: List<String>,
    distinctSubcategories: List<String>,
    categoryMapping: MutableMap<String, String?>,
    subcategoryMapping: MutableMap<String, String?>,
    parentCategories: List<AccountingCategory>,
    allSubcategories: List<Pair<AccountingCategory, AccountingCategory>>,
    onConfirm: () -> Unit,
    onBack: () -> Unit
) {
    // 一级分类下拉选项：保持原名 + 所有父分类
    val parentDropdownItems = remember(parentCategories) {
        buildList {
            add(null to "保持原名（自动创建）")
            for (cat in parentCategories) {
                val typeLabel = if (isIncomeCategory(cat.id)) "收入" else "支出"
                add(cat.id to "${cat.name} ($typeLabel)")
            }
        }
    }

    // 二级分类下拉选项：保持原名 + 所有子分类（显示 "父 > 子"）
    val childDropdownItems = remember(allSubcategories) {
        buildList {
            add(null to "保持原名（自动创建）")
            for ((parent, child) in allSubcategories) {
                val typeLabel = if (isIncomeCategory(parent.id)) "收入" else "支出"
                add(child.id to "${parent.name} > ${child.name} ($typeLabel)")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // ── 一级分类映射 ──
            if (distinctCategories.isNotEmpty()) {
                item {
                    Text("一级分类映射", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                }
                items(distinctCategories) { csvName ->
                    CategoryMappingRow(
                        csvCategoryName = csvName,
                        selectedId = categoryMapping[csvName],
                        dropdownItems = parentDropdownItems,
                        onSelect = { categoryMapping[csvName] = it }
                    )
                }
            }

            // ── 二级分类映射 ──
            if (distinctSubcategories.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(20.dp))
                    Text("二级分类映射", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                }
                items(distinctSubcategories) { csvName ->
                    CategoryMappingRow(
                        csvCategoryName = csvName,
                        selectedId = subcategoryMapping[csvName],
                        dropdownItems = childDropdownItems,
                        onSelect = { subcategoryMapping[csvName] = it }
                    )
                }
            }
        }

        Surface(tonalElevation = 2.dp, shadowElevation = 4.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(onClick = onBack) { Text("上一步") }
                Button(onClick = onConfirm) { Text("确认导入") }
            }
        }
    }
}

// ─────────────────────────────────────────────
// 子组件
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldMappingRow(
    label: String,
    selectedIndex: Int?,
    options: List<Pair<Int, String>>,
    onSelect: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selectedIndex }?.second

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.3f))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(0.7f)) {
            OutlinedTextField(
                value = selectedLabel ?: "未选择",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (idx, hdr) ->
                    DropdownMenuItem(
                        text = { Text(hdr, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { onSelect(idx); expanded = false }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryMappingRow(
    csvCategoryName: String,
    selectedId: String?,
    dropdownItems: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = dropdownItems.find { it.first == selectedId }?.second ?: "保持原名"

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = csvCategoryName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.3f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(0.7f)) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                dropdownItems.forEach { (id, label) ->
                    DropdownMenuItem(
                        text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { onSelect(id); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun CsvPreviewTable(headers: List<String>, dataRows: List<List<String>>) {
    val cellWidth = 120.dp
    val scrollState = rememberScrollState()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.horizontalScroll(scrollState).padding(8.dp)) {
            Row {
                for (h in headers) {
                    Text(h, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(cellWidth), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            for (row in dataRows) {
                Row {
                    for ((i, cell) in row.withIndex()) {
                        if (i < headers.size) {
                            Text(cell, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(cellWidth), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// 辅助函数
// ─────────────────────────────────────────────

/** 判断分类 ID 是否属于收入类 */
private fun isIncomeCategory(catId: String): Boolean {
    val incomePrefixes = listOf("salary", "investment", "red_packet", "bonus", "reimbursement",
        "part_time", "gift", "interest", "refund", "invest_income", "second_hand",
        "social_benefit", "tax_refund", "provident_fund")
    return incomePrefixes.any { catId.startsWith(it) }
}

// ─────────────────────────────────────────────
// 导入逻辑（带自动创建分类）
// ─────────────────────────────────────────────

/**
 * 执行 CSV 导入。
 *
 * 流程：
 * 1. 扫描 CSV，确定需要创建的分类（用户选择"保持原名"的那些）
 * 2. 通过 AccountingRepository 创建缺失的分类
 * 3. 对 CSV 文本做列级替换：将用户映射的分类名替换为目标分类名
 * 4. 将替换后的 CSV 写入临时文件，调用现有 importFromCsv 走标准流程
 */
private fun doImport(
    context: android.content.Context,
    csvText: String,
    rows: List<List<String>>,
    columnMapping: Map<String, Int?>,
    categoryMapping: Map<String, String?>,
    subcategoryMapping: Map<String, String?>,
    accountMapping: Map<String, AccountMappingInfo>,
    replaceMode: Boolean
): Int {
    val catIdx = columnMapping["分类"] ?: -1
    val subCatIdx = columnMapping["二级分类"] ?: -1
    val accIdx = columnMapping["账户"] ?: -1

    // ── 1. 扫描 CSV，收集需要创建的分类 ──
    val existingCats = AccountingRepository.getAllCategoriesFlat(context)
    val existingNames = existingCats.map { it.second }.toSet()

    // 需要创建的一级分类（保持原名且数据库中不存在的）
    val needCreateParents = mutableSetOf<String>()
    // 需要创建的二级分类 → 其在 CSV 中的父分类名
    val needCreateChildren = mutableMapOf<String, String>()

    for (i in 1 until rows.size) {
        val cols = rows[i]
        val catName = if (catIdx >= 0) cols.getOrNull(catIdx)?.trim()?.ifEmpty { null } else null
        val subCatName = if (subCatIdx >= 0) cols.getOrNull(subCatIdx)?.trim()?.ifEmpty { null } else null

        if (catName != null && categoryMapping[catName] == null && catName !in existingNames) {
            needCreateParents.add(catName)
        }
        if (subCatName != null && subcategoryMapping[subCatName] == null && subCatName !in existingNames) {
            needCreateChildren[subCatName] = catName ?: ""
        }
    }

    // ── 2. 通过 Repository 创建缺失的分类 ──
    // 创建一级分类
    for (name in needCreateParents) {
        AccountingRepository.createParentCategory(context, name)
    }

    // 重新获取（含新创建的），用于查找二级分类的父 ID
    val allCatsAfterCreate = AccountingRepository.getAllCategoriesFlat(context)
    val nameToId = allCatsAfterCreate.associate { it.second to it.first }

    // 创建二级分类
    for ((childName, parentCsvName) in needCreateChildren) {
        // 父分类 ID：优先用用户映射的目标，其次用 CSV 原始名查数据库
        val parentId = categoryMapping[parentCsvName]
            ?: nameToId[parentCsvName]
            ?: ""
        if (parentId.isNotEmpty()) {
            AccountingRepository.createChildCategory(context, childName, parentId)
        }
    }

    // ── 2b. 创建缺失的账户 ──
    val existingAccNames = AccountingRepository.getAllAccounts(context).map { it.name }.toSet()
    for ((csvAccName, info) in accountMapping) {
        if (csvAccName in existingAccNames) continue
        val finalName = if (info.keepName) csvAccName else info.newName.ifEmpty { csvAccName }
        val accountType = info.type
        val category = accountTypeConfigs[accountType]?.category ?: "tradable"
        val account = AccountingAccount(
            name = finalName,
            type = accountType,
            category = category
        )
        AccountingRepository.insertAccount(context, account)
    }

    // ── 3. 对 CSV 文本做列级替换 ──
    // 构建账户名替换映射（重命名的账户）
    val accReplacements = mutableMapOf<String, String>()
    val allAccs = AccountingRepository.getAllAccounts(context)
    for ((csvAccName, info) in accountMapping) {
        if (!info.keepName && info.newName.isNotEmpty()) {
            accReplacements[csvAccName] = info.newName
        } else if (csvAccName !in existingAccNames) {
            // 保持原名但需要创建的账户，名称不变
        }
    }

    val hasMapping = categoryMapping.values.any { it != null } || subcategoryMapping.values.any { it != null }

    val finalCsvText = if (hasMapping && catIdx >= 0) {
        // 从映射中获取目标分类名（ID → name）
        val allCatsFinal = AccountingRepository.getAllCategoriesFlat(context)
        val idToName = allCatsFinal.associate { it.first to it.second }

        val catIdToName = categoryMapping.mapValues { (_, v) -> v?.let { idToName[it] } }
        val subCatIdToName = subcategoryMapping.mapValues { (_, v) -> v?.let { idToName[it] } }

        transformCsvColumns(csvText, rows, catIdx, subCatIdx, accIdx, catIdToName, subCatIdToName, accReplacements)
    } else if (accReplacements.isNotEmpty()) {
        transformCsvColumns(csvText, rows, catIdx, subCatIdx, accIdx, emptyMap(), emptyMap(), accReplacements)
    } else {
        csvText
    }

    // ── 4. 写入临时文件，调用标准导入流程 ──
    val tempFile = java.io.File(context.cacheDir, "import_temp.csv")
    tempFile.writeText(finalCsvText, Charsets.UTF_8)
    val tempUri = android.net.Uri.fromFile(tempFile)

    // 标准导入：先清空再导入（替换模式）或直接导入（追加模式）
    val db = AccountingRepository.getDb(context)
    if (replaceMode) {
        db.writableDatabase.beginTransaction()
        try {
            db.writableDatabase.delete("records", null, null)
            db.writableDatabase.setTransactionSuccessful()
        } finally {
            db.writableDatabase.endTransaction()
        }
    }

    AccountingRepository.importCsv(context, tempUri)

    // 重算账户余额
    AccountingRepository.recalculateBalances(context, replaceMode)

    // 全量重算报销统计
    AccountingRepository.recalculateReimburseTotals(context)

    // 统计导入条数
    val countCursor = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM records", null)
    val count = try { if (countCursor.moveToFirst()) countCursor.getInt(0) else 0 } finally { countCursor.close() }

    // 清理临时文件
    tempFile.delete()

    return count
}

/**
 * 对 CSV 文本做列级替换：
 * 1. 替换 header 行，将非标准列名统一为 importFromCsv 期望的标准名
 * 2. 替换"分类"列和"二级分类"列中的值（用户映射的目标名）
 */
private fun transformCsvColumns(
    csvText: String,
    rows: List<List<String>>,
    catIdx: Int,
    subCatIdx: Int,
    accIdx: Int,
    catMapping: Map<String, String?>,     // csvName -> targetName or null
    subCatMapping: Map<String, String?>,  // csvName -> targetName or null
    accMapping: Map<String, String> = emptyMap()  // csvName -> finalName
): String {
    val catReplacements = catMapping.filterValues { it != null }.mapValues { it.value!! }
    val subCatReplacements = subCatMapping.filterValues { it != null }.mapValues { it.value!! }

    // 标准 header 名（importFromCsv 期望的）
    val headerNormalize = mapOf(
        "日期" to "时间", "收支类型" to "类型", "类别" to "分类",
        "优惠" to "优惠前金额"
    )

    // 从 rows 重建 CSV（rows 已过滤空行，避免索引不一致）
    val output = mutableListOf<String>()

    // header 行：替换列名
    val headerCols = rows[0].map { it.trim() }
    val newHeaders = headerCols.map { h -> headerNormalize[h] ?: h }
    output.add(newHeaders.joinToString(","))

    // 数据行：替换分类名
    for (i in 1 until rows.size) {
        val cols = rows[i].toMutableList()

        if (catIdx >= 0 && catIdx < cols.size) {
            val original = cols[catIdx].trim()
            catReplacements[original]?.let { cols[catIdx] = it }
        }
        if (subCatIdx >= 0 && subCatIdx < cols.size) {
            val original = cols[subCatIdx].trim()
            subCatReplacements[original]?.let { cols[subCatIdx] = it }
        }
        if (accIdx >= 0 && accIdx < cols.size && accMapping.isNotEmpty()) {
            val original = cols[accIdx].trim()
            accMapping[original]?.let { cols[accIdx] = it }
        }

        output.add(cols.joinToString(",") { v ->
            if (v.contains(',') || v.contains('"') || v.contains('\n'))
                "\"${v.replace("\"", "\"\"")}\""
            else v
        })
    }
    return output.joinToString("\n")
}
