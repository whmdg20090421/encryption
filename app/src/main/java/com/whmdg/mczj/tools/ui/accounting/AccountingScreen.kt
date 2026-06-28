package com.whmdg.mczj.tools.ui.accounting

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.whmdg.mczj.tools.ui.Screen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun AccountingScreen(onBack: () -> Unit, onNavigate: (Screen) -> Unit, selectedTab: Int = 0, onTabSelect: (Int) -> Unit = {}) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showBookMenu by remember { mutableStateOf(false) }
    var showAccountTypeDialog by remember { mutableStateOf(false) }
    var showAddBookDialog by remember { mutableStateOf(false) }
    // 从 DB 加载账本列表和上次使用的账本
    var bookList by remember { mutableStateOf(AccountingRepository.getBookList(context)) }
    var currentBookName by remember { mutableStateOf(AccountingRepository.getLastBookName(context)) }
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
    val fabThemeColor = remember { Color(android.graphics.Color.parseColor(getCategoryIconColor(context))) }

    // 返回手势处理：非首页标签→回首页；首页标签→双击退出
    BackHandler {
        if (selectedTab != 0) {
            onTabSelect(0)
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
            val navItems = listOf(
                "首页" to (Icons.Filled.Home to Icons.Outlined.Home),
                "资产" to (Icons.Filled.AccountBalanceWallet to Icons.Outlined.AccountBalanceWallet),
                "统计" to (Icons.Filled.InsertChart to Icons.Outlined.InsertChart),
                "日历" to (Icons.Filled.CalendarMonth to Icons.Outlined.CalendarMonth),
                "我的" to (Icons.Filled.Person to Icons.Outlined.Person),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    shadowElevation = 8.dp
                ) {
                    Row(modifier = Modifier.height(56.dp)) {
                        navItems.forEachIndexed { index, (label, iconPair) ->
                            val isActive = selectedTab == index
                            val iconColor = if (isActive) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable { onTabSelect(index) },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(22.dp))
                                        .background(
                                            if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            else Color.Transparent
                                        )
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isActive) iconPair.first else iconPair.second,
                                        contentDescription = label,
                                        tint = iconColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.height(1.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                                        ),
                                        color = iconColor,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
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
                MinePageContent(bookName = currentBookName)
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
                    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
                    val menuWidth = screenWidth * 0.4f
                    Box {
                        TextButton(onClick = { showBookMenu = true }) {
                            Text(currentBookName, style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(2.dp))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null,
                                modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(
                            expanded = showBookMenu,
                            onDismissRequest = { showBookMenu = false },
                            modifier = Modifier.width(menuWidth)
                        ) {
                            // 第一行：添加记账本
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Add, contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = fabThemeColor)
                                        Spacer(Modifier.width(8.dp))
                                        Text("添加记账本", style = MaterialTheme.typography.bodyMedium,
                                            color = fabThemeColor)
                                    }
                                },
                                onClick = {
                                    showBookMenu = false
                                    showAddBookDialog = true
                                }
                            )
                            HorizontalDivider()
                            // 已有账本列表
                            bookList.forEach { book ->
                                val isCurrent = book == currentBookName
                                DropdownMenuItem(
                                    text = {
                                        Text(book, style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isCurrent) fabThemeColor else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    onClick = {
                                        if (isCurrent) {
                                            showBookMenu = false
                                        } else {
                                            currentBookName = book
                                            AccountingRepository.setLastBookName(context, book)
                                            showBookMenu = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Home, contentDescription = "返回主页")
                    }
                }
            } else if (selectedTab == 1) {
                // 资产标签页
                AssetTabContent(
                    onAddAccount = { showAccountTypeDialog = true },
                    onNavigate = onNavigate,
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
                    containerColor = fabThemeColor
                ) {
                    Icon(Icons.Default.NoteAdd, contentDescription = "记一笔", tint = Color.White)
                }
            } else if (selectedTab == 1) {
                FloatingActionButton(
                    onClick = { showAccountTypeDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 25.dp, bottom = 25.dp),
                    containerColor = fabThemeColor
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加账户", tint = Color.White)
                }
            }

            // 添加记账本对话框
            if (showAddBookDialog) {
                var newBookName by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { showAddBookDialog = false },
                    title = { Text("添加记账本") },
                    text = {
                        OutlinedTextField(
                            value = newBookName,
                            onValueChange = { newBookName = it },
                            label = { Text("记账本名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val name = newBookName.trim()
                            if (name.isNotEmpty()) {
                                val added = AccountingRepository.addBook(context, name)
                                if (added) {
                                    bookList = AccountingRepository.getBookList(context)
                                    currentBookName = name
                                    AccountingRepository.setLastBookName(context, name)
                                }
                            }
                            showAddBookDialog = false
                        }) { Text("确认") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddBookDialog = false }) { Text("取消") }
                    }
                )
            }

            // 资金账户类型选择弹窗
            if (showAccountTypeDialog) {
                var accountTypeTab by remember { mutableIntStateOf(0) }
                var selectedTypeLabel by remember { mutableStateOf("现金") }
                var accountName by remember { mutableStateOf("") }
                var initialAmount by remember { mutableStateOf("") }
                var accountNote by remember { mutableStateOf("") }
                val scrollState = rememberScrollState()

                // label → typeId 映射
                val labelToTypeId = mapOf(
                    "现金" to "cash", "支付宝" to "alipay", "微信钱包" to "wechat",
                    "银行卡" to "bank_card", "自定义" to "custom",
                    "不动产" to "real_estate", "车辆" to "vehicle", "投资" to "investment",
                    "保险" to "insurance", "公积金" to "provident_fund", "贷款" to "loan"
                )

                val tradableTypes = listOf(
                    "file:///android_asset/icons/cash.svg" to "现金",
                    "file:///android_asset/icons/alipay.svg" to "支付宝",
                    "file:///android_asset/icons/wechat.svg" to "微信钱包",
                    "file:///android_asset/icons/bank_card.svg" to "银行卡",
                    "file:///android_asset/icons/other_account.svg" to "自定义",
                )
                val valuationTypes = listOf(
                    "file:///android_asset/icons/real_estate.svg" to "不动产",
                    "file:///android_asset/icons/vehicle.svg" to "车辆",
                    "file:///android_asset/icons/investment.svg" to "投资",
                    "file:///android_asset/icons/insurance.svg" to "保险",
                    "file:///android_asset/icons/social_fund.svg" to "公积金",
                    "file:///android_asset/icons/loan.svg" to "贷款",
                )
                val currentTypes = if (accountTypeTab == 0) tradableTypes else valuationTypes
                val screenWidth = LocalConfiguration.current.screenWidthDp.dp
                val itemWidth = (screenWidth * 0.85f - 48.dp) / 4
                val dialogThemeColor = remember {
                    Color(android.graphics.Color.parseColor(getCategoryIconColor(context)))
                }

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
                                                color = if (selected) dialogThemeColor
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
                                    rowItems.forEach { (svgPath, label) ->
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
                                                            dialogThemeColor,
                                                            RoundedCornerShape(12.dp)
                                                        ) else Modifier
                                                    )
                                                    .background(dialogThemeColor.copy(alpha = if (isSelected) 0.3f else 0.15f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                AsyncImage(
                                                    model = svgPath,
                                                    contentDescription = label,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) dialogThemeColor
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
                            AccountingRepository.insertAccount(context, account)
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

// ── 资产标签页 ──

@Composable
private fun AssetTabContent(onAddAccount: () -> Unit, onNavigate: (Screen) -> Unit, refreshTrigger: Int = 0) {
    val context = LocalContext.current
    var accounts by remember { mutableStateOf(AccountingRepository.getAllAccounts(context)) }

    // 刷新触发器变化时重新加载
    LaunchedEffect(refreshTrigger) {
        accounts = AccountingRepository.getAllAccounts(context)
    }

    // 应用主题色
    val themeColorHex = remember { getCategoryIconColor(context) }
    val themeColor = remember(themeColorHex) {
        Color(android.graphics.Color.parseColor(themeColorHex))
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
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        val totalAssets = remember(accounts) { accounts.sumOf { it.initialAmount } }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // 资产卡片
            item {
                val (reimbPending, _) = remember { AccountingRepository.getReimburseTotals(context) }
                val negativeAssets = 0.0  // 负资产（借入），暂未实现
                val totalAll = totalAssets + negativeAssets + reimbPending

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
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
                        // 左半：净资产
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("净资产", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                String.format("%.2f", totalAssets),
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = themeColor
                            )
                        }
                        // 分隔线
                        VerticalDivider(modifier = Modifier.height(40.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        // 右半：总资产 / 负资产
                        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("总资产", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(String.format("%.2f", totalAll), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("负资产", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(String.format("%.2f", negativeAssets), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }

            // 三个功能卡片：报销 / 债务 / 理财
            item {
                val (reimbPending, reimbDone) = remember { AccountingRepository.getReimburseTotals(context) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf("报销", "债务", "理财").forEach { title ->
                        Card(
                            modifier = Modifier.weight(1f)
                                .then(if (title == "报销") Modifier.clickable { onNavigate(Screen.ReimbursementAccount) } else Modifier),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = themeColor
                                )
                                Spacer(Modifier.height(8.dp))
                                when (title) {
                                    "报销" -> {
                                        Text("可报销: ${formatAmount(reimbPending)}", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("已报销: ${formatAmount(reimbDone)}", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    "债务" -> {
                                        Text("待还款: —", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("已还款: —", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    "理财" -> {
                                        Text("投入: —", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("收益: —", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 资金账户分组
            val tradableAccounts = accounts.filter { it.category == "tradable" }
            if (tradableAccounts.isNotEmpty()) {
                item {
                    AccountGroupSection(
                        title = "资金账户",
                        icon = Icons.Outlined.AccountBalanceWallet,
                        iconColor = themeColor,
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
                        iconColor = themeColor,
                        accounts = valuationAccounts,
                        showStats = false
                    )
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
                    "${String.format("%.2f", subtotal)}",
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
    val context = LocalContext.current
    val config = accountTypeConfigs[account.type]
    val themeColor = remember {
        Color(android.graphics.Color.parseColor(getCategoryIconColor(context)))
    }
    val typeLabel = config?.label ?: account.type
    val typeSvg = config?.svgPath ?: "file:///android_asset/icons/other_account.svg"

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
                        colors = listOf(themeColor, themeColor.copy(alpha = 0.8f))
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
                        AsyncImage(
                            model = typeSvg,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
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
                                "${String.format("%.2f", account.initialAmount)}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("收入", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                            Text(
                                "0.00",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("支出", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                            Text(
                                "0.00",
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
                            "${String.format("%.2f", account.initialAmount)}",
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

/** "我的"页面头部卡片：头像 + 诗意短句 + 签名 + 统计 */
@Composable
private fun MineHeaderCard(bookName: String) {
    val context = LocalContext.current
    var avatarPath by remember { mutableStateOf(AccountingRepository.getAvatarPath(context)) }
    var nickname by remember { mutableStateOf(AccountingRepository.getNickname(context)) }
    var showNicknameDialog by remember { mutableStateOf(false) }
    var nicknameInput by remember { mutableStateOf("") }

    var username by remember { mutableStateOf(AccountingRepository.getUsername(context)) }
    var showUsernameDialog by remember { mutableStateOf(false) }
    var usernameInput by remember { mutableStateOf("") }

    // 图片选择器
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val saved = AccountingRepository.saveAvatar(context, uri)
            if (saved != null) avatarPath = saved
        }
    }

    // 统计数据
    val records = remember(bookName) { AccountingRepository.getRecordsByBook(context, bookName) }
    val totalRecords = records.size
    val dayCount = remember { AccountingRepository.getDayCount(context) }

    // 签名编辑弹窗
    if (showNicknameDialog) {
        AlertDialog(
            onDismissRequest = { showNicknameDialog = false },
            title = { Text("编辑签名") },
            text = {
                OutlinedTextField(
                    value = nicknameInput,
                    onValueChange = { nicknameInput = it },
                    placeholder = { Text("写点什么吧...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    nickname = nicknameInput.trim()
                    AccountingRepository.setNickname(context, nickname)
                    showNicknameDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showNicknameDialog = false }) { Text("取消") }
            }
        )
    }

    // 用户名编辑弹窗
    if (showUsernameDialog) {
        AlertDialog(
            onDismissRequest = { showUsernameDialog = false },
            title = { Text("编辑用户名") },
            text = {
                OutlinedTextField(
                    value = usernameInput,
                    onValueChange = { usernameInput = it },
                    placeholder = { Text("输入用户名...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    username = usernameInput.trim()
                    AccountingRepository.setUsername(context, username)
                    showUsernameDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showUsernameDialog = false }) { Text("取消") }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 统计 + 头像行：[记账天数] [头像] [总笔数]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // 左侧：记账天数
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "$dayCount",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "记账天数",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 中间：头像（80dp 圆形，点击选择图片）
                Box(contentAlignment = Alignment.TopEnd) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), CircleShape)
                            .clickable {
                                photoPickerLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarPath != null) {
                            val bitmap = remember(avatarPath) {
                                try {
                                    BitmapFactory.decodeFile(avatarPath)
                                } catch (_: Exception) { null }
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "头像",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // 编辑小图标（右上角 45° 切线位置）
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .offset(x = 4.dp, y = (-4).dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "更换头像",
                            modifier = Modifier.size(14.dp),
                            tint = Color.White
                        )
                    }
                }

                // 右侧：总笔数
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "$totalRecords",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "总笔数",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 用户名（点击可编辑）
            Text(
                text = username,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable {
                        usernameInput = username
                        showUsernameDialog = true
                    }
                    .padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(8.dp))

            // 签名（默认诗意短句，点击可编辑）
            Text(
                text = nickname,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable {
                        nicknameInput = nickname
                        showNicknameDialog = true
                    }
                    .fillMaxWidth(0.6f)
            )

        }
    }
}

/** "我的"页面内容：个性化设置 → 分类管理 → 分类图标 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinePageContent(bookName: String = "") {
    val context = LocalContext.current
    // 页面栈：emptyList = 主页面，listOf("个性化设置") = 个性化页面，listOf("个性化设置","分类管理") = 分类管理页
    var pageStack by remember { mutableStateOf<List<String>>(emptyList()) }
    // 导入流程状态
    var importError by remember { mutableStateOf<String?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var showImportConfirm by remember { mutableStateOf(false) }
    var showImportDone by remember { mutableStateOf(false) }
    var countdownSeconds by remember { mutableStateOf(5) }
    // 格式切换：false = JSON, true = CSV
    var importUseCsv by remember { mutableStateOf(false) }
    // CSV 映射导入：读取的 CSV 文本
    var csvImportText by remember { mutableStateOf<String?>(null) }
    var showExportConfirm by remember { mutableStateOf(false) }
    var exportUseCsv by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                if (importUseCsv) {
                    // CSV：读取文本，跳转到映射页面
                    val csv = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    if (csv != null) {
                        AccountingRepository.validateImportCsv(context, uri)
                        csvImportText = csv
                        pageStack = listOf("数据管理", "导入数据", "CSV映射导入")
                    } else {
                        importError = "无法读取CSV文件。"
                    }
                } else {
                    AccountingRepository.validateImportData(context, uri)
                    importUri = uri
                    showImportConfirm = true
                }
            } catch (_: Exception) {
                importError = if (importUseCsv) "该CSV文件数据格式不正确或已损坏。"
                              else "该JSON文件数据格式不正确或已损坏。"
            }
        }
    }

    when {
        pageStack.isEmpty() -> {
            // "我的"主页
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { Spacer(Modifier.height(16.dp)) }
                item { MineHeaderCard(bookName = bookName) }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    SettingCard(
                        icon = Icons.Outlined.Palette,
                        title = "个性化设置",
                        subtitle = "图标风格、主题等",
                        onClick = { pageStack = listOf("个性化设置") }
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    SettingCard(
                        icon = Icons.Outlined.Storage,
                        title = "数据管理",
                        subtitle = "导出、导入记账数据",
                        onClick = { pageStack = listOf("数据管理") }
                    )
                }
            }
        }
        pageStack == listOf("数据管理") -> {
            // 数据管理页 — 两个入口卡片
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { Spacer(Modifier.height(16.dp)) }
                item {
                    SettingCard(
                        icon = Icons.Outlined.FileUpload,
                        title = "导出数据",
                        subtitle = "将记账数据导出为文件",
                        onClick = { pageStack = listOf("数据管理", "导出数据") }
                    )
                }
                item {
                    SettingCard(
                        icon = Icons.Outlined.FileDownload,
                        title = "导入数据",
                        subtitle = "从文件导入记账数据",
                        onClick = { pageStack = listOf("数据管理", "导入数据") }
                    )
                }
            }
        }
        pageStack == listOf("数据管理", "导出数据") -> {
            // 导出数据页
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { Spacer(Modifier.height(16.dp)) }
                // 格式切换胶囊
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(3.dp)
                        ) {
                            Row(modifier = Modifier.padding(3.dp)) {
                                listOf(false to "JSON", true to "CSV").forEach { (value, label) ->
                                    val selected = exportUseCsv == value
                                    Surface(
                                        onClick = { exportUseCsv = value },
                                        shape = RoundedCornerShape(50),
                                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        modifier = Modifier.padding(horizontal = 2.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
                // 数据说明卡片
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                            Text(
                                text = if (exportUseCsv) "导出内容说明" else "导出内容说明",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                            if (exportUseCsv) {
                                Text(
                                    text = "将记账记录导出为 CSV 文件，可用 Excel 直接打开编辑。\n\n导出字段：\n• 类型（支出/收入/转账/债务）\n• 分类、二级分类\n• 金额\n• 账本、账户\n• 备注\n• 时间\n• 优惠前金额\n• 报销账户",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = "将全部记账数据导出为 JSON 文件，可在另一台设备导入恢复。\n\n包含数据：\n• 所有记账记录（含金额、分类、备注等）\n• 全部账本和账户信息\n• 分类设置（含自定义分类）\n• 个性化设置（图标颜色等）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
                // 导出按钮
                item {
                    Button(
                        onClick = { showExportConfirm = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (exportUseCsv) "导出 CSV 文件" else "导出 JSON 文件")
                    }
                }
                // 导出结果
                if (exportResult != null) {
                    item { Spacer(Modifier.height(12.dp)) }
                    item {
                        Text(
                            text = "已保存到: $exportResult",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
        pageStack == listOf("数据管理", "导入数据") -> {
            // 导入数据页
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { Spacer(Modifier.height(16.dp)) }
                // 格式切换胶囊
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(3.dp)
                        ) {
                            Row(modifier = Modifier.padding(3.dp)) {
                                listOf(false to "JSON", true to "CSV").forEach { (value, label) ->
                                    val selected = importUseCsv == value
                                    Surface(
                                        onClick = { importUseCsv = value },
                                        shape = RoundedCornerShape(50),
                                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        modifier = Modifier.padding(horizontal = 2.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
                // 数据说明卡片
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                            Text(
                                text = "导入说明",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                            if (importUseCsv) {
                                Text(
                                    text = "从 CSV 文件导入记账记录，将替换现有记录数据。\n\n要求：\n• 文件需为 UTF-8 编码\n• 第一行为表头\n• 列顺序：类型、分类、二级分类、金额、账本、账户、备注、时间、优惠前金额、报销账户\n• 分类和账户名称需与当前数据一致",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = "从 JSON 文件导入全部数据，将完全覆盖当前所有记账数据。\n\n包含：\n• 所有记账记录\n• 账本和账户\n• 分类设置\n• 个性化设置\n\n⚠ 导入前请确认已备份当前数据",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
                // 选择文件按钮
                item {
                    Button(
                        onClick = {
                            val mime = if (importUseCsv) "text/comma-separated-values" else "application/json"
                            importLauncher.launch(arrayOf(mime))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (importUseCsv) "选择 CSV 文件" else "选择 JSON 文件")
                    }
                }
                // 提示
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    Text(
                        text = "导入前建议先导出备份当前数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
        pageStack == listOf("数据管理", "导入数据", "CSV映射导入") -> {
            csvImportText?.let { text ->
                CsvImportFlowScreen(
                    csvText = text,
                    onImportDone = { count ->
                        csvImportText = null
                        pageStack = listOf("数据管理")
                    },
                    onBack = {
                        csvImportText = null
                        pageStack = listOf("数据管理", "导入数据")
                    }
                )
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

    // 导出确认弹窗
    if (showExportConfirm) {
        AlertDialog(
            onDismissRequest = { showExportConfirm = false },
            title = { Text("确认导出") },
            text = {
                Text(if (exportUseCsv)
                    "即将导出全部记账记录为 CSV 文件，保存到 Downloads 目录。"
                else
                    "即将导出全部记账数据为 JSON 文件，保存到 Downloads 目录。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportConfirm = false
                    try {
                        val path = if (exportUseCsv) AccountingRepository.exportCsv(context)
                                   else AccountingRepository.exportData(context)
                        exportResult = path
                        android.widget.Toast.makeText(
                            context, "已导出到: $path", android.widget.Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            context, "导出失败: ${e.message}", android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }) { Text("确认导出") }
            },
            dismissButton = {
                TextButton(onClick = { showExportConfirm = false }) { Text("取消") }
            }
        )
    }

    // 导入错误弹窗
    if (importError != null) {
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("导入失败") },
            text = { Text(importError!!) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text("我知道了") }
            }
        )
    }

    // 导入确认弹窗
    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false; importUri = null },
            title = { Text("确认导入") },
            text = {
                Text("此操作将完全覆盖本地所有记账数据，原有记录将永久丢失。\n\n建议在导入前先使用导出功能备份当前数据。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportConfirm = false
                    try {
                        if (importUseCsv) AccountingRepository.importCsv(context, importUri!!)
                        else AccountingRepository.importData(context, importUri!!)
                        showImportDone = true
                    } catch (_: Exception) {
                        importError = if (importUseCsv) "该CSV文件数据格式不正确或已损坏。"
                                      else "该JSON文件数据格式不正确或已损坏。"
                    }
                    importUri = null
                }) {
                    Text("确认覆盖", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false; importUri = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 导入完成倒计时弹窗（不可关闭，5秒后自杀，拦截所有手势和点击）
    if (showImportDone) {
        BackHandler {}
        LaunchedEffect(Unit) {
            while (countdownSeconds > 0) {
                kotlinx.coroutines.delay(1000L)
                countdownSeconds--
            }
            kotlin.system.exitProcess(0)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("导入成功") },
                text = { Text("应用数据已导入，为保证安全，${countdownSeconds}秒后将关闭应用。") },
                confirmButton = {}
            )
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
    val context = LocalContext.current
    val iconTint = remember { Color(android.graphics.Color.parseColor(getCategoryIconColor(context))) }
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
                tint = iconTint,
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

    // 主题色
    val themeColor = remember {
        Color(android.graphics.Color.parseColor(getCategoryIconColor(context)))
    }

    // 账户列表（用于查找账户信息）
    val accounts = remember { AccountingRepository.getAllAccounts(context) }
    val accountMap = remember(accounts) { accounts.associateBy { it.id } }

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

    // 本月收支统计
    val calendar = remember { Calendar.getInstance() }
    val currentYear = calendar.get(Calendar.YEAR)
    val currentMonth = calendar.get(Calendar.MONTH)
    val monthlyRecords = remember(recordDb, currentYear, currentMonth) {
        recordDb.records.filter {
            val cal = Calendar.getInstance().apply { timeInMillis = it.happenedAt }
            cal.get(Calendar.YEAR) == currentYear && cal.get(Calendar.MONTH) == currentMonth
        }
    }
    val monthlyExpense = remember(monthlyRecords) {
        monthlyRecords.filter { it.type == "支出" && it.reimbursementAccountId == null && !it.excludeFromStats }.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
    }
    val monthlyIncome = remember(monthlyRecords) {
        monthlyRecords.filter { it.type == "收入" && it.reimbursementAccountId == null && !it.excludeFromStats }.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
    }
    val monthlyBalance = remember(monthlyIncome, monthlyExpense) { monthlyIncome - monthlyExpense }
    // 余剩预算（暂无预算功能，默认0）
    val budgetRemaining = remember { 0.0 }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部留白
        item { Spacer(Modifier.height(barHeight)) }

        // 月度统计卡片
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 第一行：本月支出 / 本月收入 / 本月结余
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("本月支出", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${String.format("%.2f", monthlyExpense)}",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFFEF5350)
                            )
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("本月收入", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${String.format("%.2f", monthlyIncome)}",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFF4CAF50)
                            )
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("本月结余", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${String.format("%.2f", monthlyBalance)}",
                                style = MaterialTheme.typography.titleSmall,
                                color = themeColor
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(12.dp))

                    // 第二行：余剩预算
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("余剩预算", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${String.format("%.2f", budgetRemaining)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = themeColor
                        )
                    }
                }
            }
        }

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
                if (r.reimbursementAccountId != null) continue
                val v = r.amount.toDoubleOrNull() ?: 0.0
                when (r.type) {
                    "支出" -> dayExpense += v
                    "收入" -> dayIncome += v
                }
            }

            // 日期分组：日期头在卡片外，卡片只包含记录
            item {
                val first = records.first()
                val cal = Calendar.getInstance().apply { timeInMillis = first.happenedAt }
                val month = cal.get(Calendar.MONTH) + 1
                val day = cal.get(Calendar.DAY_OF_MONTH)
                val weekday = weekdays[cal.get(Calendar.DAY_OF_WEEK) - 1]

                val summaryParts = mutableListOf<String>()
                if (dayExpense > 0) summaryParts.add("支 %.0f".format(dayExpense))
                if (dayIncome > 0) summaryParts.add("收 %.0f".format(dayIncome))
                val summary = summaryParts.joinToString("  ")

                Column {
                    // 日期头（卡片外，背景上）
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

                    // 卡片（悬浮，浅灰色）
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF5F5F5)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column {
                            records.forEachIndexed { index, record ->
                                val catInfo = categoryLookup[record.categoryId]
                                val subInfo = record.subcategoryId?.let { categoryLookup[it] }
                                val parentName = catInfo?.first ?: record.categoryId
                                val childName = subInfo?.first
                                val displayName = if (childName != null) "$parentName-$childName" else parentName
                                val icon = subInfo?.second ?: catInfo?.second ?: "category"
                                val isExpense = record.type == "支出" || record.type == "债务"
                                val isReimbursable = record.reimbursementAccountId != null
                                val amountPrefix = if (isExpense) "-" else "+"
                                val amountColor = if (isReimbursable) MaterialTheme.colorScheme.onSurface
                                                  else if (isExpense) Color(0xFFEF5350)
                                                  else Color(0xFF4CAF50)
                                val amountDisplay = String.format("%.2f", record.amount.toDoubleOrNull() ?: 0.0)
                                val discountBeforeDisplay = record.discountBefore?.toDoubleOrNull()?.let { String.format("%.2f", it) }
                                val timeStr = timeFormat.format(Date(record.happenedAt))
                                // 账户信息
                                val account = record.accountId?.let { accountMap[it] }

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
                                            // 优惠原价（灰色删除线）
                                            if (discountBeforeDisplay != null) {
                                                Text(
                                                    text = "$amountPrefix$discountBeforeDisplay",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color.Gray,
                                                    textDecoration = TextDecoration.LineThrough
                                                )
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text(
                                                text = "$amountPrefix$amountDisplay",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = amountColor
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
                                            } else if (account != null) {
                                                Spacer(Modifier.weight(1f))
                                            }
                                            // 账户信息（如果有）
                                            if (account != null) {
                                                val accountSvg = accountTypeConfigs[account.type]?.svgPath
                                                if (accountSvg != null) {
                                                    AsyncImage(
                                                        model = accountSvg,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                }
                                                Text(
                                                    text = account.name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        tint = Color(android.graphics.Color.parseColor(currentColorHex)),
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
