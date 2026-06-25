package com.whmdg.mczj.tools.ui.accounting

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.ui.Screen
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun AccountingScreen(onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val listState = rememberLazyListState()
    var showBookMenu by remember { mutableStateOf(false) }
    var showAccountTypeDialog by remember { mutableStateOf(false) }
    var currentBookName by remember { mutableStateOf("默认记账本") }
    var selectedTab by remember { mutableIntStateOf(0) }

    // 判断是否在顶部：第一个 item 可见且 offset 为 0
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    // 背景 alpha：顶部时 0，非顶部时 1
    val bgAlpha by animateFloatAsState(targetValue = if (isAtTop) 0f else 1f, label = "bgAlpha")
    val barHeight = 75.dp
    val snackbarHostState = remember { SnackbarHostState() }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }

    // 返回手势处理：非首页标签→回首页；首页标签→双击退出
    BackHandler {
        if (selectedTab != 0) {
            selectedTab = 0
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackPressTime < 1500L) {
                onBack()
            } else {
                lastBackPressTime = now
            }
        }
    }

    // 首页标签首次返回时显示提示
    LaunchedEffect(lastBackPressTime) {
        if (lastBackPressTime > 0L && selectedTab == 0) {
            snackbarHostState.showSnackbar(
                message = "再滑一次退出到主页",
                duration = SnackbarDuration.Short
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(if (selectedTab == 0) Icons.Filled.Home else Icons.Outlined.Home, contentDescription = "首页") },
                    label = { Text("首页") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(if (selectedTab == 1) Icons.Filled.AccountBalanceWallet else Icons.Outlined.AccountBalanceWallet, contentDescription = "资产") },
                    label = { Text("资产") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(if (selectedTab == 2) Icons.Filled.BarChart else Icons.Outlined.BarChart, contentDescription = "统计") },
                    label = { Text("统计") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(if (selectedTab == 3) Icons.Filled.CalendarMonth else Icons.Outlined.CalendarMonth, contentDescription = "日历") },
                    label = { Text("日历") }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(if (selectedTab == 4) Icons.Filled.Person else Icons.Outlined.Person, contentDescription = "我的") },
                    label = { Text("我的") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 内容区：根据 selectedTab 显示不同内容
            if (selectedTab == 4) {
                MinePageContent()
            } else if (selectedTab == 0) {
                // 首页：记录列表
                RecordListContent(
                    bookName = currentBookName,
                    listState = listState,
                    barHeight = barHeight,
                    onNavigate = onNavigate
                )

                // 状态栏背景层（首页专属，滚动时渐显）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .graphicsLayer { alpha = bgAlpha }
                        .background(MaterialTheme.colorScheme.surface)
                )

                // 顶部功能按钮层（首页专属，始终可见）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        TextButton(onClick = { showBookMenu = true }) {
                            Text("记账本", style = MaterialTheme.typography.titleMedium)
                        }
                        DropdownMenu(
                            expanded = showBookMenu,
                            onDismissRequest = { showBookMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(currentBookName) },
                                onClick = { showBookMenu = false }
                            )
                        }
                    }
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Home, contentDescription = "返回主页")
                    }
                }
            } else {
                // 其他 tab：简单占位
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }

            // 右下角按钮（首页=记一笔，资产=添加账户，其他tab不显示）
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = { onNavigate(Screen.AddAccounting(currentBookName)) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 25.dp, bottom = 25.dp),
                    containerColor = Color(0xFF00BCD4)
                ) {
                    Icon(Icons.Default.NoteAdd, contentDescription = "记一笔", tint = Color.White)
                }
            } else if (selectedTab == 1) {
                FloatingActionButton(
                    onClick = { showAccountTypeDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 25.dp, bottom = 25.dp),
                    containerColor = Color(0xFF00BCD4)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加账户", tint = Color.White)
                }
            }

            // 资金账户类型选择弹窗
            if (showAccountTypeDialog) {
                var accountTypeTab by remember { mutableIntStateOf(0) }
                var selectedType by remember { mutableStateOf("现金") }
                var accountName by remember { mutableStateOf("") }
                var initialAmount by remember { mutableStateOf("") }
                var accountNote by remember { mutableStateOf("") }
                val scrollState = rememberScrollState()

                val tradableTypes = listOf(
                    Triple(Icons.Outlined.Payments, "现金", Color(0xFFFF9800)),
                    Triple(Icons.Outlined.CurrencyYuan, "支付宝", Color(0xFF1677FF)),
                    Triple(Icons.Outlined.Chat, "微信钱包", Color(0xFF07C160)),
                    Triple(Icons.Outlined.CreditCard, "银行卡", Color(0xFF1890FF)),
                    Triple(Icons.Outlined.Wallet, "QQ钱包", Color(0xFF12B7F5)),
                    Triple(Icons.Outlined.ShoppingCart, "京东金融", Color(0xFFE53935)),
                    Triple(Icons.Outlined.Edit, "自定义", MaterialTheme.colorScheme.primary),
                )
                val valuationTypes = listOf(
                    Triple(Icons.Outlined.Home, "不动产", Color(0xFF795548)),
                    Triple(Icons.Outlined.DirectionsCar, "车辆", Color(0xFF607D8B)),
                    Triple(Icons.Outlined.TrendingUp, "投资", Color(0xFFFF9800)),
                    Triple(Icons.Outlined.HealthAndSafety, "保险", Color(0xFF4CAF50)),
                    Triple(Icons.Outlined.AccountBalance, "公积金", Color(0xFF3F51B5)),
                    Triple(Icons.Outlined.House, "贷款", Color(0xFFE91E63)),
                )
                val currentTypes = if (accountTypeTab == 0) tradableTypes else valuationTypes
                val screenWidth = LocalConfiguration.current.screenWidthDp.dp
                val itemWidth = (screenWidth * 0.85f - 48.dp) / 4

                AlertDialog(
                    onDismissRequest = { showAccountTypeDialog = false },
                    title = {
                        Text("添加资产", style = MaterialTheme.typography.headlineSmall)
                    },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(scrollState)
                        ) {
                            // ── 分段切换标签 ──
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(4.dp)
                            ) {
                                Row {
                                    listOf("资金账户", "估值账户").forEachIndexed { index, label ->
                                        val selected = accountTypeTab == index
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .then(
                                                    if (selected) Modifier.background(
                                                        MaterialTheme.colorScheme.surface,
                                                        RoundedCornerShape(8.dp)
                                                    ) else Modifier
                                                )
                                                .clickable { accountTypeTab = index }
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelLarge.copy(
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                                ),
                                                color = if (selected) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            // ── 图标网格 ──
                            currentTypes.chunked(4).forEach { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    rowItems.forEach { (icon, label, color) ->
                                        val isSelected = selectedType == label
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .width(itemWidth)
                                                .padding(vertical = 8.dp)
                                                .clickable { selectedType = label }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .then(
                                                        if (isSelected) Modifier.border(
                                                            2.dp,
                                                            MaterialTheme.colorScheme.primary,
                                                            RoundedCornerShape(12.dp)
                                                        ) else Modifier
                                                    )
                                                    .background(color.copy(alpha = if (isSelected) 0.3f else 0.15f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = label,
                                                    modifier = Modifier.size(28.dp),
                                                    tint = color
                                                )
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                    repeat(4 - rowItems.size) {
                                        Spacer(Modifier.width(itemWidth))
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            // ── 账户设定表单 ──
                            Text(
                                text = "账户设定",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            OutlinedTextField(
                                value = accountName,
                                onValueChange = { accountName = it },
                                label = { Text("账户名称") },
                                placeholder = { Text("如：我的支付宝") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = initialAmount,
                                onValueChange = { initialAmount = it },
                                label = { Text("初始金额") },
                                placeholder = { Text("0.00") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = accountNote,
                                onValueChange = { accountNote = it },
                                label = { Text("备注") },
                                placeholder = { Text("可选") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAccountTypeDialog = false }) {
                            Text("确认")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAccountTypeDialog = false }) {
                            Text("取消")
                        }
                    }
                )
            }
        }
    }
}

/** "我的"页面内容：个性化设置 → 分类管理 → 分类图标 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinePageContent() {
    // 页面栈：emptyList = 主页面，listOf("个性化设置") = 个性化页面，listOf("个性化设置","分类管理") = 分类管理页
    var pageStack by remember { mutableStateOf<List<String>>(emptyList()) }

    when {
        pageStack.isEmpty() -> {
            // "我的"主页
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { Spacer(Modifier.height(16.dp)) }
                item {
                    SettingCard(
                        icon = Icons.Outlined.Palette,
                        title = "个性化设置",
                        subtitle = "图标风格、主题等",
                        onClick = { pageStack = listOf("个性化设置") }
                    )
                }
            }
        }
        pageStack == listOf("个性化设置") -> {
            // 个性化设置页
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { Spacer(Modifier.height(16.dp)) }
                item {
                    SettingCard(
                        icon = Icons.Outlined.Category,
                        title = "分类管理",
                        subtitle = "分类图标风格",
                        onClick = { pageStack = listOf("个性化设置", "分类管理") }
                    )
                }
            }
        }
        pageStack == listOf("个性化设置", "分类管理") -> {
            // 分类管理页：分类图标开关
            CategoryIconStylePage()
        }
    }
}

/** 设置卡片组件（参考 BeeCount SectionCard + AppListTile 风格） */
@Composable
private fun SettingCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = 180f }
            )
        }
    }
}

/** 首页记录列表 */
@Composable
private fun RecordListContent(
    bookName: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    barHeight: androidx.compose.ui.unit.Dp,
    onNavigate: (Screen) -> Unit
) {
    val context = LocalContext.current
    val recordDb = remember { AccountingRecordDb.load(context) }
    val categoryDb = remember { AccountingCategoryDb.ensureDefault(context) }

    // 按账本筛选、按时间降序排列、按日期分组
    val groupedRecords = remember(recordDb, bookName) {
        val filtered = recordDb.records
            .filter { it.bookName == bookName }
            .sortedByDescending { it.happenedAt }

        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        filtered.groupBy { dateFormat.format(Date(it.happenedAt)) }
            .toSortedMap(compareByDescending { it })
    }

    // 构建分类 id → (name, icon) 的快速查找表
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

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        // 顶部留白
        item { Spacer(Modifier.height(barHeight)) }

        if (groupedRecords.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "暂无记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        groupedRecords.forEach { (dateKey, records) ->
            // 计算当日收支汇总
            var dayExpense = 0.0
            var dayIncome = 0.0
            for (r in records) {
                val v = r.amount.toDoubleOrNull() ?: 0.0
                when (r.type) {
                    "支出" -> dayExpense += v
                    "收入" -> dayIncome += v
                }
            }

            // 整个日期分组包在一个卡片里
            item {
                val first = records.first()
                val cal = Calendar.getInstance().apply { timeInMillis = first.happenedAt }
                val month = cal.get(Calendar.MONTH) + 1
                val day = cal.get(Calendar.DAY_OF_MONTH)
                val weekday = weekdays[cal.get(Calendar.DAY_OF_WEEK) - 1]

                val summaryParts = mutableListOf<String>()
                if (dayExpense > 0) summaryParts.add("支 ¥%.0f".format(dayExpense))
                if (dayIncome > 0) summaryParts.add("收 ¥%.0f".format(dayIncome))
                val summary = summaryParts.joinToString("  ")

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column {
                        // 日期头
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
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

                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // 当天记录列表
                        records.forEachIndexed { index, record ->
                            val catInfo = categoryLookup[record.categoryId]
                            val subInfo = record.subcategoryId?.let { categoryLookup[it] }
                            val parentName = catInfo?.first ?: record.categoryId
                            val childName = subInfo?.first
                            val displayName = if (childName != null) "$parentName-$childName" else parentName
                            val icon = subInfo?.second ?: catInfo?.second ?: "category"
                            val isExpense = record.type == "支出" || record.type == "债务"
                            val amountPrefix = if (isExpense) "-" else "+"
                            val amountColor = if (isExpense) Color(0xFFEF5350) else Color(0xFF4CAF50)
                            val timeStr = timeFormat.format(Date(record.happenedAt))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigate(Screen.AccountingDetail(bookName, record.id)) }
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
                                        tint = MaterialTheme.colorScheme.primary
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
                                            text = "$amountPrefix¥${record.amount}",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = amountColor
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Icon(
                                            imageVector = Icons.Outlined.Payment,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
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

                            // 记录间分隔线（最后一条不加）
                            if (index < records.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 底部留白（给 FAB 让位）
        item { Spacer(Modifier.height(80.dp)) }
    }
}

/** 图标主题色设置页：点击颜色圆圈弹出调色板 */
@Composable
private fun CategoryIconStylePage() {
    val context = LocalContext.current
    var currentColorHex by remember { mutableStateOf(getCategoryIconColor(context)) }
    var showColorPicker by remember { mutableStateOf(false) }

    val presetColors = listOf(
        "#5C6BC0" to "靛蓝",
        "#00BCD4" to "青色",
        "#26A69A" to "青绿",
        "#42A5F5" to "蓝色",
        "#7E57C2" to "紫色",
        "#EC407A" to "粉色",
        "#EF5350" to "红色",
        "#FF7043" to "深橙",
        "#FFA726" to "橙色",
        "#66BB6A" to "绿色",
        "#78909C" to "蓝灰",
        "#8D6E63" to "棕色",
    )

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(16.dp)) }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ColorLens,
                        contentDescription = "图标主题色",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "图标主题色",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    // 颜色圆圈按钮
                    Box {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(android.graphics.Color.parseColor(currentColorHex)))
                                .clickable { showColorPicker = true }
                        )
                        // 弹出调色板
                        DropdownMenu(
                            expanded = showColorPicker,
                            onDismissRequest = { showColorPicker = false }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                // 4行3列
                                for (row in presetColors.chunked(3)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for ((hex, _) in row) {
                                            val isSelected = hex == currentColorHex
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(RoundedCornerShape(18.dp))
                                                    .then(
                                                        if (isSelected) Modifier.border(
                                                            2.dp,
                                                            MaterialTheme.colorScheme.onSurface,
                                                            RoundedCornerShape(18.dp)
                                                        ) else Modifier
                                                    )
                                                    .background(Color(android.graphics.Color.parseColor(hex)))
                                                    .clickable {
                                                        currentColorHex = hex
                                                        setCategoryIconColor(context, hex)
                                                        showColorPicker = false
                                                    }
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
