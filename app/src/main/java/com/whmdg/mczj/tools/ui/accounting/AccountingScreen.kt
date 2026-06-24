package com.whmdg.mczj.tools.ui.accounting

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
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
                // "我的"页面
                MinePageContent()
            } else if (selectedTab == 0) {
                // 首页：记录列表
                RecordListContent(
                    bookName = currentBookName,
                    listState = listState,
                    barHeight = barHeight
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    item { Spacer(Modifier.height(barHeight)) }
                }
            }

            // 状态栏背景层（独立控制显隐）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .graphicsLayer { alpha = bgAlpha }
                    .background(MaterialTheme.colorScheme.surface)
            )

            // 顶部功能按钮层（始终可见）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 记账本按钮 + 下拉菜单
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
                // 房子按钮（返回主页）
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Home, contentDescription = "返回主页")
                }
            }

            // 右下角青色加号按钮
            FloatingActionButton(
                onClick = { onNavigate(Screen.AddAccounting(currentBookName)) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 25.dp, bottom = 25.dp),
                containerColor = Color(0xFF00BCD4)
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加记账", tint = Color.White)
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
    barHeight: androidx.compose.ui.unit.Dp
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

            // 日期头
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
            }

            // 记录行
            items(records, key = { it.id }) { record ->
                val catInfo = categoryLookup[record.categoryId]
                val subInfo = record.subcategoryId?.let { categoryLookup[it] }
                val displayName = subInfo?.first ?: catInfo?.first ?: record.categoryId
                val icon = subInfo?.second ?: catInfo?.second ?: "category"
                val isExpense = record.type == "支出" || record.type == "债务"
                val amountPrefix = if (isExpense) "-" else "+"
                val amountColor = if (isExpense) Color(0xFFEF5350) else Color(0xFF4CAF50)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 分类图标
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = materialIcon(icon),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    // 分类名 + 备注
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        if (record.note.isNotEmpty()) {
                            Text(
                                text = record.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    // 金额
                    Text(
                        text = "$amountPrefix¥${record.amount}",
                        style = MaterialTheme.typography.titleMedium,
                        color = amountColor
                    )
                }
            }

            // 分组间隔
            item { Spacer(Modifier.height(4.dp)) }
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
