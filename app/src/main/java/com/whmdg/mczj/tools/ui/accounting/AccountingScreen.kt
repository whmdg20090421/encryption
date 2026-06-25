package com.whmdg.mczj.tools.ui.accounting

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
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
    var accountRefreshTrigger by remember { mutableIntStateOf(0) }

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
            } else if (selectedTab == 1) {
                // 资产标签页
                AssetTabContent(
                    onAddAccount = { showAccountTypeDialog = true },
                    refreshTrigger = accountRefreshTrigger
                )
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
                val context = LocalContext.current
                var accountTypeTab by remember { mutableIntStateOf(0) }
                var selectedTypeLabelLabel by remember { mutableStateOf("现金") }
                var accountName by remember { mutableStateOf("") }
                var initialAmount by remember { mutableStateOf("") }
                var accountNote by remember { mutableStateOf("") }
                val scrollState = rememberScrollState()

                // label → typeId 映射
                val labelToTypeId = mapOf(
                    "现金" to "cash", "支付宝" to "alipay", "微信钱包" to "wechat",
                    "银行卡" to "bank_card", "QQ钱包" to "qq_wallet", "京东金融" to "jd_finance",
                    "自定义" to "custom",
                    "不动产" to "real_estate", "车辆" to "vehicle", "投资" to "investment",
                    "保险" to "insurance", "公积金" to "provident_fund", "贷款" to "loan"
                )

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
                                        val isSelected = selectedTypeLabel == label
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .width(itemWidth)
                                                .padding(vertical = 8.dp)
                                                .clickable { selectedTypeLabel = label }
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
                        TextButton(onClick = {
                            val typeId = labelToTypeId[selectedTypeLabel] ?: "custom"
                            val category = if (accountTypeTab == 0) "tradable" else "valuation"
                            val amount = initialAmount.toDoubleOrNull() ?: 0.0
                            val account = AccountingAccount(
                                name = accountName.ifEmpty { selectedTypeLabel },
                                type = typeId,
                                category = category,
                                initialAmount = amount,
                                note = accountNote
                            )
                            AccountingDatabase.getInstance(context).insertAccount(account)
                            accountRefreshTrigger++
                            showAccountTypeDialog = false
                            // 重置表单
                            accountName = ""
                            initialAmount = ""
                            accountNote = ""
                            selectedTypeLabel = "现金"
                            accountTypeTab = 0
                        }) {
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

// ── 账户类型配置 ──

private data class AccountTypeConfig(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val color: Color,
    val category: String
)

private val accountTypeConfigs = mapOf(
    "cash" to AccountTypeConfig(Icons.Outlined.Payments, "现金", Color(0xFFFF9800), "tradable"),
    "alipay" to AccountTypeConfig(Icons.Outlined.CurrencyYuan, "支付宝", Color(0xFF1677FF), "tradable"),
    "wechat" to AccountTypeConfig(Icons.Outlined.Chat, "微信钱包", Color(0xFF07C160), "tradable"),
    "bank_card" to AccountTypeConfig(Icons.Outlined.CreditCard, "银行卡", Color(0xFF1890FF), "tradable"),
    "qq_wallet" to AccountTypeConfig(Icons.Outlined.Wallet, "QQ钱包", Color(0xFF12B7F5), "tradable"),
    "jd_finance" to AccountTypeConfig(Icons.Outlined.ShoppingCart, "京东金融", Color(0xFFE53935), "tradable"),
    "custom" to AccountTypeConfig(Icons.Outlined.Edit, "自定义", Color(0xFF5C6BC0), "tradable"),
    "real_estate" to AccountTypeConfig(Icons.Outlined.Home, "不动产", Color(0xFF795548), "valuation"),
    "vehicle" to AccountTypeConfig(Icons.Outlined.DirectionsCar, "车辆", Color(0xFF607D8B), "valuation"),
    "investment" to AccountTypeConfig(Icons.Outlined.TrendingUp, "投资", Color(0xFFFF9800), "valuation"),
    "insurance" to AccountTypeConfig(Icons.Outlined.HealthAndSafety, "保险", Color(0xFF4CAF50), "valuation"),
    "provident_fund" to AccountTypeConfig(Icons.Outlined.AccountBalance, "公积金", Color(0xFF3F51B5), "valuation"),
    "loan" to AccountTypeConfig(Icons.Outlined.House, "贷款", Color(0xFFE91E63), "valuation"),
)

// ── 资产标签页 ──

@Composable
private fun AssetTabContent(onAddAccount: () -> Unit, refreshTrigger: Int = 0) {
    val context = LocalContext.current
    var accounts by remember { mutableStateOf(AccountingDatabase.getInstance(context).getAllAccounts()) }

    // 刷新触发器变化时重新加载
    LaunchedEffect(refreshTrigger) {
        accounts = AccountingDatabase.getInstance(context).getAllAccounts()
    }

    if (accounts.isEmpty()) {
        // 空状态
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.AccountBalanceWallet,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "暂无账户",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAddAccount) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加账户")
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // 资产构成饼图
            item {
                AssetPieChart(accounts = accounts)
            }

            // 资金账户分组
            val tradableAccounts = accounts.filter { it.category == "tradable" }
            if (tradableAccounts.isNotEmpty()) {
                item {
                    AccountGroupSection(
                        title = "资金账户",
                        icon = Icons.Outlined.AccountBalanceWallet,
                        iconColor = Color(0xFF4CAF50),
                        accounts = tradableAccounts,
                        showStats = true
                    )
                }
            }

            // 估值账户分组
            val valuationAccounts = accounts.filter { it.category == "valuation" }
            if (valuationAccounts.isNotEmpty()) {
                item {
                    AccountGroupSection(
                        title = "估值账户",
                        icon = Icons.Outlined.TrendingUp,
                        iconColor = Color(0xFFFF9800),
                        accounts = valuationAccounts,
                        showStats = false
                    )
                }
            }
        }
    }
}

// ── 资产构成饼图 ──

@Composable
private fun AssetPieChart(accounts: List<AccountingAccount>) {
    // 按类型分组汇总余额
    val typeBalances = accounts
        .groupBy { it.type }
        .mapValues { (_, accs) -> accs.sumOf { it.initialAmount } }
        .filter { it.value > 0 }
        .toList()
        .sortedByDescending { it.second }

    if (typeBalances.isEmpty()) return

    val totalBalance = typeBalances.sumOf { it.second }
    val maxSlices = 8
    val slices = mutableListOf<Triple<String, Double, Color>>()
    var otherTotal = 0.0

    for ((type, balance) in typeBalances) {
        if (slices.size < maxSlices) {
            val config = accountTypeConfigs[type]
            slices.add(Triple(config?.label ?: type, balance, config?.color ?: Color.Gray))
        } else {
            otherTotal += balance
        }
    }
    if (otherTotal > 0) {
        slices.add(Triple("其他", otherTotal, Color(0xFF9E9E9E)))
    }

    var touchedIndex by remember { mutableIntStateOf(-1) }
    val selectedSlice = if (touchedIndex in slices.indices) slices[touchedIndex] else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 饼图
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .pointerInput(slices) {
                        detectTapGestures { offset ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val dx = offset.x - center.x
                            val dy = offset.y - center.y
                            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                            val outerRadius = size.width / 2f
                            val innerRadius = outerRadius * 0.47f
                            if (distance in innerRadius..outerRadius) {
                                var angle = Math.toDegrees(
                                    kotlin.math.atan2(dy.toDouble(), dx.toDouble())
                                ).toFloat()
                                if (angle < 0) angle += 360f
                                // 计算每个扇区的角度
                                var startAngle = -90f
                                for (i in slices.indices) {
                                    val sweep = (slices[i].second / totalBalance * 360f).toFloat()
                                    if (angle in startAngle..(startAngle + sweep)) {
                                        touchedIndex = if (touchedIndex == i) -1 else i
                                        break
                                    }
                                    startAngle += sweep
                                }
                            } else {
                                touchedIndex = -1
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val outerRadius = size.minDimension / 2f
                    val innerRadius = outerRadius * 0.47f
                    val arcSize = Size(outerRadius * 2, outerRadius * 2)
                    val topLeft = Offset(0f, 0f)
                    var startAngle = -90f

                    for (i in slices.indices) {
                        val sweep = (slices[i].second / totalBalance * 360f).toFloat()
                        val isTouched = i == touchedIndex
                        val radius = if (isTouched) outerRadius * 1.06f else outerRadius
                        val arcSz = Size(radius * 2, radius * 2)
                        val tl = Offset(
                            (size.width - radius * 2) / 2f,
                            (size.height - radius * 2) / 2f
                        )
                        // 扇区
                        drawArc(
                            color = slices[i].third,
                            startAngle = startAngle,
                            sweepAngle = sweep - 1.5f,
                            useCenter = false,
                            topLeft = tl,
                            size = arcSz,
                            style = Stroke(width = radius - innerRadius)
                        )
                        startAngle += sweep
                    }
                }
                // 中心文字
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = selectedSlice?.first ?: "资产构成",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "¥${String.format("%.0f", selectedSlice?.second ?: totalBalance)}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 图例
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                slices.forEach { (label, balance, color) ->
                    val pct = (balance / totalBalance * 100)
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(color)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "$label ${String.format("%.1f", pct)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── 账户分组 ──

@Composable
private fun AccountGroupSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    accounts: List<AccountingAccount>,
    showStats: Boolean
) {
    var expanded by remember { mutableStateOf(true) }
    val subtotal = accounts.sumOf { it.initialAmount }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        // 分组标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(iconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = iconColor
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    "${accounts.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            if (showStats) {
                Text(
                    "¥${String.format("%.2f", subtotal)}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = iconColor
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = if (expanded) 0f else -90f },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 账户卡片列表
        if (expanded) {
            accounts.forEach { account ->
                AccountCard(account = account, showStats = showStats)
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ── 账户卡片 ──

@Composable
private fun AccountCard(account: AccountingAccount, showStats: Boolean) {
    val config = accountTypeConfigs[account.type]
    val typeColor = config?.color ?: Color(0xFF5C6BC0)
    val typeLabel = config?.label ?: account.type
    val typeIcon = config?.icon ?: Icons.Outlined.AccountBalance

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
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(typeColor, typeColor.copy(alpha = 0.8f))
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
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )

            Column(
                modifier = Modifier.padding(14.dp)
            ) {
                // 顶部行：图标 + 名称
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                Color.White.copy(alpha = 0.2f),
                                shape = androidx.compose.foundation.shape.CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            typeIcon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color.White
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        account.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(10.dp))

                if (showStats) {
                    // 资金账户：余额 / 收入 / 支出
                    Row {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("余额", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                            Text(
                                "¥${String.format("%.2f", account.initialAmount)}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("收入", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                            Text(
                                "¥0.00",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("支出", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                            Text(
                                "¥0.00",
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
                            "¥${String.format("%.2f", account.initialAmount)}",
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
