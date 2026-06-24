package com.whmdg.mczj.tools.ui.accounting

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.R
import java.util.Calendar

@Composable
fun AddAccountingScreen(onBack: () -> Unit, bookName: String) {
    var selectedType by remember { mutableIntStateOf(0) }
    val types = listOf("支出", "收入", "转账", "债务")
    var amount by remember { mutableStateOf("0") }
    var note by remember { mutableStateOf("") }
    val now = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableIntStateOf(now.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableIntStateOf(now.get(Calendar.MONTH)) }
    var selectedDay by remember { mutableIntStateOf(now.get(Calendar.DAY_OF_MONTH)) }
    var selectedHour by remember { mutableIntStateOf(now.get(Calendar.HOUR_OF_DAY)) }
    var selectedMinute by remember { mutableIntStateOf(now.get(Calendar.MINUTE)) }
    val selectedDate = remember(selectedYear, selectedMonth, selectedDay, selectedHour, selectedMinute) {
        "%04d-%02d-%02d %02d:%02d".format(selectedYear, selectedMonth + 1, selectedDay, selectedHour, selectedMinute)
    }
    var showDatePicker by remember { mutableStateOf(false) }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val infoRowHeight = screenHeight * 0.05f
    val context = LocalContext.current

    // 从 JSON 动态加载分类数据（跟随选中的记账类型切换）
    val categoryDb = remember { AccountingCategoryDb.ensureDefault(context) }
    val currentType = types[selectedType]
    val categories = remember(selectedType) {
        categoryDb.getCategories("记账页", currentType)
    }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    // 切换记账类型时重置选中分类
    LaunchedEffect(selectedType) {
        selectedCategory = null
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 50dp 功能栏
            Surface(
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                types.forEachIndexed { index, type ->
                    TextButton(onClick = { selectedType = index }) {
                        Text(
                            text = type,
                            color = if (selectedType == index)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = bookName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
            } // Surface

            // 一级分类选择行（自适应 FlowRow，每行 5 个，行间距 10dp）
            val iconSize = 50.dp
            val itemsPerRow = 5
            val totalIcons = categories.size.coerceAtLeast(1)
            val rows = (totalIcons + itemsPerRow - 1) / itemsPerRow
            val lastRowCount = totalIcons - (rows - 1) * itemsPerRow
            val screenWidth = LocalConfiguration.current.screenWidthDp.dp

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = itemsPerRow
            ) {
                categories.forEach { cat ->
                    val isSelected = selectedCategory == cat.id
                    val customDrawable = categoryDrawableOrNull(cat.icon)
                    val iconColor = categoryColor(cat.id)

                    Box(
                        modifier = Modifier
                            .size(iconSize)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFF00BCD4) else Color.White)
                            .then(
                                if (!isSelected) Modifier.border(
                                    BorderStroke(1.dp, Color.Black),
                                    RoundedCornerShape(8.dp)
                                ) else Modifier
                            )
                            .clickable {
                                selectedCategory = if (isSelected) null else cat.id
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (customDrawable != null) {
                            // 自定义彩色 SVG 图标
                            Image(
                                painter = painterResource(customDrawable),
                                contentDescription = cat.name,
                                modifier = Modifier.size(25.dp),
                                colorFilter = if (isSelected) ColorFilter.tint(Color.White) else null
                            )
                        } else {
                            // Material Icons（每个分类不同颜色）
                            Icon(
                                imageVector = materialIcon(cat.icon),
                                contentDescription = cat.name,
                                modifier = Modifier.size(25.dp),
                                tint = if (isSelected) Color.White else iconColor
                            )
                        }
                    }
                }
            }

            // 消费类型选择区（暂空）
            Box(modifier = Modifier.weight(1f).fillMaxWidth())

            // 第一行：左侧20%空 | 中间60%备注输入 | 右侧20%金额
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(infoRowHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.fillMaxHeight().weight(0.2f))
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.6f)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (note.isEmpty()) {
                                Text(
                                    "点击输入备注",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.2f),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    val scrollState = rememberScrollState()
                    // 金额变化时自动滚到最右
                    LaunchedEffect(amount) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                    Text(
                        text = amount,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier
                            .horizontalScroll(scrollState)
                            .padding(end = 8.dp)
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF00BCD4), thickness = 1.dp)

            // 第二行：功能菜单
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(infoRowHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showDatePicker = true }) {
                    Text(selectedDate, style = MaterialTheme.typography.bodySmall)
                }
            }

            // 键盘
            Surface(shadowElevation = 2.dp) {
            CalculatorKeyboard(
                onInput = { key ->
                    amount = when (key) {
                        "←" -> if (amount.length > 1) amount.dropLast(1) else "0"
                        "." -> if (!amount.contains(".")) "$amount." else amount
                        "再记" -> { "0" } // TODO: 保存记录
                        "完成" -> { onBack(); amount }
                        "+", "-", "*", "÷" -> {
                            val ops = listOf("+", "-", "*", "÷")
                            val existingOp = ops.firstOrNull { it in amount.drop(1) }
                            if (existingOp != null) {
                                val idx = amount.indexOf(existingOp, 1)
                                val num1 = amount.substring(0, idx).toDoubleOrNull()
                                val num2 = amount.substring(idx + 1).toDoubleOrNull()
                                if (num1 != null && num2 != null) {
                                    val result = when (existingOp) {
                                        "+" -> num1 + num2
                                        "-" -> num1 - num2
                                        "*" -> num1 * num2
                                        "÷" -> if (num2 != 0.0) {
                                            val r = num1 / num2
                                            if (r == r.toLong().toDouble()) r.toLong().toDouble() else
                                                "%.1f".format(r).toDouble()
                                        } else num1
                                        else -> num1
                                    }
                                    val display = if (result == result.toLong().toDouble())
                                        result.toLong().toString() else result.toString()
                                    "$display$key"
                                } else {
                                    amount + key
                                }
                            } else {
                                amount + key
                            }
                        }
                        else -> if (amount == "0") key else amount + key
                    }
                }
            )
            } // Surface
        }

        // 日期时间选择弹窗
        if (showDatePicker) {
            DateTimePickerDialog(
                initYear = selectedYear,
                initMonth = selectedMonth,
                initDay = selectedDay,
                initHour = selectedHour,
                initMinute = selectedMinute,
                onDismiss = { showDatePicker = false },
                onConfirm = { y, m, d, h, min ->
                    selectedYear = y
                    selectedMonth = m
                    selectedDay = d
                    selectedHour = h
                    selectedMinute = min
                    showDatePicker = false
                }
            )
        }
    }
}

@Composable
private fun DateTimePickerDialog(
    initYear: Int, initMonth: Int, initDay: Int,
    initHour: Int, initMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (year: Int, month: Int, day: Int, hour: Int, minute: Int) -> Unit
) {
    val cyan = Color(0xFF00BCD4)
    var calYear by remember { mutableIntStateOf(initYear) }
    var calMonth by remember { mutableIntStateOf(initMonth) }
    var selDay by remember { mutableIntStateOf(initDay) }
    var selHour by remember { mutableIntStateOf(initHour) }
    var selMinute by remember { mutableIntStateOf(initMinute) }

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val daysInMonth = remember(calYear, calMonth) {
        val cal = Calendar.getInstance()
        cal.set(calYear, calMonth, 1)
        cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
    val firstDayOfWeek = remember(calYear, calMonth) {
        val cal = Calendar.getInstance()
        cal.set(calYear, calMonth, 1)
        (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday=0
    }

    val dialogWidth = screenWidth * 0.80f
    val dialogHeight = screenHeight * 0.7f

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {},
        text = {
            Column(
                modifier = Modifier
                    .width(dialogWidth)
                    .height(dialogHeight)
            ) {
                // 上半：日历（weight均分）
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // 月份导航
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            if (calMonth == 0) { calMonth = 11; calYear-- } else calMonth--
                        }) {
                            Icon(Icons.Filled.ChevronLeft, "上月", tint = cyan)
                        }
                        Text(
                            text = "%04d-%02d".format(calYear, calMonth + 1),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        IconButton(onClick = {
                            if (calMonth == 11) { calMonth = 0; calYear++ } else calMonth++
                        }) {
                            Icon(Icons.Filled.ChevronRight, "下月", tint = cyan)
                        }
                    }
                    // 星期标题
                    Row(modifier = Modifier.fillMaxWidth()) {
                        listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                            Text(
                                text = it,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 日期网格
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(7),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        items(firstDayOfWeek) { Spacer(Modifier.aspectRatio(1f)) }
                        items(daysInMonth) { day ->
                            val d = day + 1
                            val isSelected = d == selDay
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (isSelected) cyan else Color.Transparent)
                                    .clickable { selDay = d },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$d",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) Color.White
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // 青色分割线
                HorizontalDivider(color = cyan, thickness = 1.dp)

                // 下半：时间齿轮（weight均分）
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    TimeWheel(
                        range = 0..23,
                        selected = selHour,
                        onSelect = { selHour = it },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = { "%02d".format(it) }
                    )
                    TimeWheel(
                        range = 0..59,
                        selected = selMinute,
                        onSelect = { selMinute = it },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = { "%02d".format(it) }
                    )
                }

                // 确认按钮
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onConfirm(calYear, calMonth, selDay, selHour, selMinute) }) {
                        Text("确定", color = cyan)
                    }
                }
            }
        }
    )
}

@Composable
private fun TimeWheel(
    range: IntRange,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    label: (Int) -> String
) {
    val cyan = Color(0xFF00BCD4)
    val size = range.last - range.first + 1
    val totalItems = size * 10000
    val initialIndex = totalItems / 2 + (selected - range.first)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val itemHeight = 36.dp
    val itemSpacing = 4.dp
    val totalItemHeight = itemHeight + itemSpacing
    val density = LocalDensity.current

    // 滚动停止时自动吸中
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .collect {
                val info = listState.layoutInfo
                val items = info.visibleItemsInfo
                if (items.isEmpty()) return@collect
                val vpH = info.viewportEndOffset - info.viewportStartOffset
                val centerY = vpH / 2f
                // 找离视口中心最近的 item
                val best = items.minByOrNull {
                    kotlin.math.abs(it.offset + it.size / 2f - centerY)
                } ?: return@collect
                // 让该 item 居中：offset = -(vpH - itemHeight) / 2
                val scrollOffset = -((vpH - best.size) / 2).toInt()
                listState.animateScrollToItem(best.index, scrollOffset)
                onSelect(range.first + (best.index % size + size) % size)
            }
    }

    val scope = rememberCoroutineScope()
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(totalItems) { index ->
                val value = range.first + (index % size + size) % size
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .padding(vertical = itemSpacing / 2)
                        .clickable {
                            onSelect(value)
                            scope.launch {
                                val info = listState.layoutInfo
                                val vpH = info.viewportEndOffset - info.viewportStartOffset
                                val ih = (itemHeight.value * density.density).toInt()
                                listState.animateScrollToItem(index, -((vpH - ih) / 2))
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label(value),
                        style = if (value == selected) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.bodyLarge,
                        color = if (value == selected) cyan
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        // 视口中心青色选择条
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(totalItemHeight)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(4.dp))
                .background(cyan.copy(alpha = 0.12f))
        )
    }
}

@Composable
private fun CalculatorKeyboard(onInput: (String) -> Unit) {
    val keySpacing = 2.dp
    val keyShape = RoundedCornerShape(6.dp)
    val keyColor = MaterialTheme.colorScheme.surfaceVariant
    val isDark = isSystemInDarkTheme()
    val cyanText = if (isDark) Color(0xFF00838F) else Color(0xFF00BCD4)
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val rowHeight = screenHeight * 0.06f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(keySpacing),
        verticalArrangement = Arrangement.spacedBy(keySpacing)
    ) {
        // 第1行: 1 2 3 ←
        Row(
            modifier = Modifier.fillMaxWidth().height(rowHeight),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            KeyButton("1", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("2", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("3", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("←", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput, icon = true)
        }
        // 第2行: 4 5 6 [-|*]
        Row(
            modifier = Modifier.fillMaxWidth().height(rowHeight),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            KeyButton("4", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("5", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("6", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            // 左右分：- 和 *
            Row(modifier = Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(keySpacing)) {
                KeyButton("-", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
                KeyButton("*", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            }
        }
        // 第3行: 7 8 9 [+|÷]
        Row(
            modifier = Modifier.fillMaxWidth().height(rowHeight),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            KeyButton("7", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("8", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("9", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            // 左右分：+ 和 ÷
            Row(modifier = Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(keySpacing)) {
                KeyButton("+", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
                KeyButton("÷", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            }
        }
        // 第4行: 再记 0 . 完成
        Row(
            modifier = Modifier.fillMaxWidth().height(rowHeight),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            KeyButton("再记", Modifier.weight(1f), keyShape, MaterialTheme.colorScheme.primaryContainer, cyanText, onInput)
            KeyButton("0", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton(".", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("完成", Modifier.weight(1f), keyShape, MaterialTheme.colorScheme.primaryContainer, cyanText, onInput)
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    modifier: Modifier,
    shape: RoundedCornerShape,
    containerColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    onInput: (String) -> Unit,
    icon: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val bgColor = if (isPressed)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    else
        containerColor

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = null) { onInput(label) },
        contentAlignment = Alignment.Center
    ) {
        if (icon) {
            Icon(
                Icons.Filled.Backspace,
                contentDescription = "退格",
                tint = textColor
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = textColor
            )
        }
    }
}

/** 图标标识 → 自定义彩色矢量图资源映射（仅原始 5 个分类） */
private fun categoryDrawableOrNull(icon: String): Int? = when (icon) {
    "shopping_bag" -> R.drawable.accounting_ic_cat_shopping
    "utensils" -> R.drawable.accounting_ic_cat_food
    "car" -> R.drawable.accounting_ic_cat_transport
    "gamepad" -> R.drawable.accounting_ic_cat_leisure
    "house" -> R.drawable.accounting_ic_cat_home
    else -> null
}

/** 图标标识 → Material Icons 映射 */
private fun materialIcon(icon: String): ImageVector = when (icon) {
    // 支出分类
    "restaurant" -> Icons.Filled.Restaurant
    "fastfood" -> Icons.Filled.Fastfood
    "eco" -> Icons.Filled.Eco
    "local_cafe" -> Icons.Filled.LocalCafe
    "cake" -> Icons.Filled.Cake
    "kitchen" -> Icons.Filled.Kitchen
    "shopping_cart" -> Icons.Filled.ShoppingCart
    "pets" -> Icons.Filled.Pets
    "directions_car" -> Icons.Filled.DirectionsCar
    "checkroom" -> Icons.Filled.Checkroom
    "local_laundry_service" -> Icons.Filled.LocalLaundryService
    "school" -> Icons.Filled.School
    "trending_down" -> Icons.Filled.TrendingDown
    "movie" -> Icons.Filled.Movie
    "sports_esports" -> Icons.Filled.SportsEsports
    "medication" -> Icons.Filled.Medication
    "subscriptions" -> Icons.Filled.Subscriptions
    "fitness_center" -> Icons.Filled.FitnessCenter
    "home_work" -> Icons.Filled.HomeWork
    "home" -> Icons.Filled.Home
    "face" -> Icons.Filled.Face
    // 收入分类
    "work" -> Icons.Filled.Work
    "account_balance" -> Icons.Filled.AccountBalance
    "card_giftcard" -> Icons.Filled.CardGiftcard
    "emoji_events" -> Icons.Filled.EmojiEvents
    "receipt" -> Icons.Filled.Receipt
    "schedule" -> Icons.Filled.Schedule
    "monetization_on" -> Icons.Filled.MonetizationOn
    "undo" -> Icons.Filled.Undo
    "trending_up" -> Icons.Filled.TrendingUp
    "sell" -> Icons.Filled.Sell
    "health_and_safety" -> Icons.Filled.HealthAndSafety
    "receipt_long" -> Icons.Filled.ReceiptLong
    "account_balance_wallet" -> Icons.Filled.AccountBalanceWallet
    // 兜底
    else -> Icons.Filled.Category
}

/** 分类 ID → 主题色（每个分类不同颜色） */
private fun categoryColor(id: String): Color = when (id) {
    // 支出分类
    "dining" -> Color(0xFFFF6B6B)        // 红色 - 餐饮
    "snacks" -> Color(0xFFFF9F43)        // 橙色 - 零食
    "fruit" -> Color(0xFF2ED573)         // 绿色 - 水果
    "beverage" -> Color(0xFF7C4DFF)      // 紫色 - 饮品
    "pastry" -> Color(0xFFFF6F91)        // 粉色 - 糕点
    "cooking" -> Color(0xFF4CAF50)       // 深绿 - 做饭食材
    "shopping" -> Color(0xFF42A5F5)      // 蓝色 - 购物
    "pets" -> Color(0xFFAB47BC)          // 紫色 - 宠物
    "transport" -> Color(0xFF5C6BC0)     // 靛蓝 - 交通
    "car" -> Color(0xFF1E88E5)           // 深蓝 - 汽车
    "clothing" -> Color(0xFFEC407A)      // 粉红 - 服饰
    "daily_goods" -> Color(0xFF26A69A)   // 青色 - 日用品
    "education" -> Color(0xFF42A5F5)     // 蓝色 - 教育
    "invest_loss" -> Color(0xFFEF5350)   // 红色 - 投资亏损
    "entertainment" -> Color(0xFFAB47BC) // 紫色 - 娱乐
    "game" -> Color(0xFF7E57C2)          // 深紫 - 游戏
    "health_products" -> Color(0xFF66BB6A) // 绿色 - 保健品
    "subscription" -> Color(0xFF29B6F6)  // 浅蓝 - 订阅服务
    "sports" -> Color(0xFFEF5350)        // 红色 - 运动
    "housing" -> Color(0xFF8D6E63)       // 棕色 - 住房
    "home" -> Color(0xFFFFA726)          // 橙色 - 居家
    "beauty" -> Color(0xFFEC407A)        // 粉红 - 美容
    // 收入分类
    "salary" -> Color(0xFF4CAF50)        // 绿色 - 工资
    "investment" -> Color(0xFF2196F3)    // 蓝色 - 理财
    "red_packet" -> Color(0xFFF44336)    // 红色 - 红包
    "bonus" -> Color(0xFFFF9800)         // 橙色 - 奖金
    "reimbursement" -> Color(0xFF9C27B0) // 紫色 - 报销
    "part_time" -> Color(0xFF00BCD4)     // 青色 - 兼职
    "gift" -> Color(0xFFE91E63)          // 粉红 - 礼物
    "interest" -> Color(0xFF4CAF50)      // 绿色 - 利息
    "refund" -> Color(0xFFFF5722)        // 深橙 - 退款
    "invest_income" -> Color(0xFF2196F3) // 蓝色 - 投资收益
    "second_hand" -> Color(0xFF795548)   // 棕色 - 二手交易
    "social_benefit" -> Color(0xFF607D8B) // 灰蓝 - 社会福利
    "tax_refund" -> Color(0xFF009688)    // 青色 - 退税
    "provident_fund" -> Color(0xFF3F51B5) // 靛蓝 - 公积金
    // 兜底
    else -> Color(0xFF9E9E9E)            // 灰色
}

