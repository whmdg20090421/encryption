package com.whmdg.mczj.tools.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime

// 笔记本详情：点开某个笔记本后的内部界面
@Composable
fun DiaryBookScreen(
    bookName: String,
    onBack: () -> Unit
) {

    // 生成日期列表：前后各 10 年，共约 7300 天
    val totalPast = 3650
    val totalFuture = 3650
    val todayIndex = totalPast  // 今天在列表中的位置

    val dates = remember {
        val today = LocalDate.now()
        (totalFuture downTo -totalPast).map { offset ->
            val d = today.plusDays(offset.toLong())
            DateEntry(
                year = d.year.toString(),
                month = "${d.monthValue}月",
                day = d.dayOfMonth.toString().padStart(2, '0')
            )
        }
    }

    // 默认选中今天，滚动到今天的位置
    var selectedIndex by remember { mutableIntStateOf(todayIndex) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        listState.scrollToItem(todayIndex)
    }

    val isDark = isSystemInDarkTheme()
    val cyanColor = if (isDark) Color(0xFF4DB6AC) else Color(0xFF00BCD4)  // 暗青 / 亮青

    // FAB 展开动画状态
    var showDialog by remember { mutableStateOf(false) }
    var animTrigger by remember { mutableStateOf(0) }
    val animProgress = remember { Animatable(0f) }
    val contentAlpha = remember { Animatable(0f) }
    val config = LocalConfiguration.current
    val screenWidthPx = config.screenWidthDp.toFloat()

    LaunchedEffect(animTrigger) {
        if (animTrigger > 0) {
            animProgress.snapTo(0f)
            contentAlpha.snapTo(0f)
            animProgress.animateTo(1f, animationSpec = tween(300))
            contentAlpha.animateTo(1f, animationSpec = tween(150))
        }
    }

    Scaffold { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        // 顶部工具栏 — 名称居中于整个工具栏，不与左侧按钮重叠
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // 返回按钮
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }

            // 名称 — 相对整个工具栏居中，左 padding 避让按钮
            Text(
                text = bookName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center)
            )

            // 设置按钮
            IconButton(
                onClick = { },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        }

        // 日期时间线 — 竖线贴左边缘，圆圈左边缘与屏幕齐平
        val circleRadius = 8.dp
        val circleDiameter = circleRadius * 2
        val timelineSpacing = 70.dp  // 圆心到圆心的距离

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 5.dp, top = 8.dp, bottom = 8.dp)
        ) {
            itemsIndexed(dates) { index, entry ->
                val isSelected = index == selectedIndex
                val circleColor = if (isSelected) cyanColor else cyanColor.copy(alpha = 0.4f)
                val textColor = if (isSelected)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurfaceVariant

                val entryHeight = timelineSpacing

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(entryHeight)
                        .clickable { selectedIndex = index }
                ) {
                    // 竖线段（每个 entry 内绘制一段，拼成连续竖线）
                    Canvas(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(circleDiameter)
                    ) {
                        val lineX = circleRadius.toPx()
                        val lineThickness = 1.5.dp.toPx()
                        drawLine(
                            color = cyanColor,
                            start = Offset(lineX, 0f),
                            end = Offset(lineX, size.height),
                            strokeWidth = lineThickness,
                            cap = StrokeCap.Round
                        )
                    }

                    // 空心圆圈（居中在竖线上）
                    Box(
                        modifier = Modifier
                            .size(circleDiameter)
                            .align(Alignment.CenterStart),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = circleColor,
                                radius = size.minDimension / 2,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 2.dp.toPx()
                                )
                            )
                        }
                        if (isSelected) {
                            Canvas(modifier = Modifier.size(circleRadius)) {
                                drawCircle(
                                    color = circleColor,
                                    radius = size.minDimension / 2
                                )
                            }
                        }
                    }

                    // 月份 + 日期文字（圆圈右侧）
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = circleDiameter + 12.dp)
                    ) {
                        Text(
                            text = entry.month,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = textColor.copy(alpha = 0.7f)
                        )
                        Text(
                            text = entry.day,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                    }
                }
            }
        }

        // 叠加层：FAB + 展开弹窗
        Box(modifier = Modifier.fillMaxSize()) {
            // 遮罩层
            if (showDialog) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { showDialog = false }
                            )
                        }
                )
            }

            if (showDialog) {
                // 动画：从右下角展开到屏幕中心
                val dialogWidthDp = screenWidthPx * 0.85f
                val startOffsetX = screenWidthPx - 56f - 10f
                val startOffsetY = config.screenHeightDp.toFloat() - 56f - 10f
                val endOffsetX = (screenWidthPx - dialogWidthDp) / 2f
                val endOffsetY = config.screenHeightDp * 0.25f
                val startSize = 56f
                val cornerRadius = lerp(28f, 16f, animProgress.value)

                val currentX = lerp(startOffsetX, endOffsetX, animProgress.value)
                val currentY = lerp(startOffsetY, endOffsetY, animProgress.value)
                val currentWidth = lerp(startSize, dialogWidthDp, animProgress.value)
                val currentHeight = if (animProgress.value < 0.5f) startSize else lerp(startSize, 300f, (animProgress.value - 0.5f) * 2f)

                Box(
                    modifier = Modifier
                        .offset(x = currentX.dp, y = currentY.dp)
                        .width(currentWidth.dp)
                        .height(currentHeight.dp)
                        .clip(RoundedCornerShape(cornerRadius.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    // 加号图标（动画过程中渐隐）
                    if (animProgress.value < 0.5f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(1f - animProgress.value * 2f),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                        }
                    }

                    // 弹窗内容（动画后半段渐显）
                    if (animProgress.value > 0.5f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(contentAlpha.value)
                        ) {
                            DiaryDialogContent(
                                selectedDate = dates[selectedIndex],
                                cyanColor = cyanColor,
                                onDismiss = { showDialog = false },
                                onConfirm = { /* 待实现 */ }
                            )
                        }
                    }
                }
            } else {
                // FAB 按钮
                FloatingActionButton(
                    onClick = {
                        showDialog = true
                        animTrigger++
                    },
                    containerColor = cyanColor,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 10.dp, bottom = 10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加", tint = Color.White)
                }
            }
        }
    }
    } // Scaffold
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}

@Composable
private fun DiaryDialogContent(
    selectedDate: DateEntry,
    cyanColor: Color,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    val currentTime = LocalTime.now()
    val timeText = "${currentTime.hour.toString().padStart(2, '0')}:${currentTime.minute.toString().padStart(2, '0')}"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 标题
        Text(
            text = "添加解释",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // 输入框
        BasicTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp)
                )
                .padding(12.dp),
            textStyle = TextStyle(
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box {
                    if (title.isEmpty()) {
                        Text(
                            "输入内容...",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            }
        )

        // 日期
        Text(
            text = "${selectedDate.year}-${selectedDate.month.replace("月", "").padStart(2, '0')}-${selectedDate.day}",
            style = MaterialTheme.typography.bodyLarge
        )

        // 时间
        Text(
            text = timeText,
            style = MaterialTheme.typography.bodyLarge
        )

        // 底部按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
            TextButton(onClick = onConfirm) {
                Text("确定", color = cyanColor)
            }
        }
    }
}

private data class DateEntry(
    val year: String,
    val month: String,
    val day: String
)
