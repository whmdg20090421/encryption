package com.whmdg.mczj.tools.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = config.screenWidthDp.toFloat()
    val screenHeightPx = config.screenHeightDp.toFloat()

    // 记录 FAB 的屏幕坐标（dp），动画起点
    var fabScreenX by remember { mutableFloatStateOf(0f) }
    var fabScreenY by remember { mutableFloatStateOf(0f) }
    // FAB 中心坐标（相对于 overlayBox，动画阶段1 使用）
    var fabCenterX by remember { mutableFloatStateOf(0f) }
    var fabCenterY by remember { mutableFloatStateOf(0f) }

    // 动画触发：三阶段顺序执行
    LaunchedEffect(animTrigger) {
        if (animTrigger > 0) {
            animProgress.snapTo(0f)
            // 阶段1：FAB 移动到屏幕中心 (0→0.625)
            animProgress.animateTo(0.625f, animationSpec = tween(500))
            // 阶段2：FAB 渐隐 + 弹窗渐显 (0.625→0.875)
            animProgress.animateTo(0.875f, animationSpec = tween(200))
            // 阶段3：圆形裁剪向外扩展 (0.875→1.0)
            animProgress.animateTo(1f, animationSpec = tween(150))
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
            // 遮罩层（alpha 跟随动画进度）
            if (showDialog) {
                val scrimAlpha = (animProgress.value * 0.4f).coerceIn(0f, 0.4f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = scrimAlpha))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { showDialog = false }
                            )
                        }
                )
            }

            if (showDialog) {
                // 弹窗：始终在最终位置、最终大小，始终渲染内容
                val dialogWidthDp = screenWidthPx * 0.85f
                val dialogOffsetX = (screenWidthPx - dialogWidthDp) / 2f
                val dialogOffsetY = screenHeightPx * 0.25f

                // 阶段1：FAB 移动进度 (0→1)
                val moveProgress = (animProgress.value / 0.625f).coerceIn(0f, 1f)
                // 阶段2：内容渐显进度 (0→1)
                val fadeInProgress = ((animProgress.value - 0.625f) / 0.25f).coerceIn(0f, 1f)
                // 阶段3：圆形扩展进度 (0→1)
                val revealProgress = ((animProgress.value - 0.875f) / 0.125f).coerceIn(0f, 1f)

                // 弹窗 alpha：阶段1 不可见，阶段2 渐显
                val dialogAlpha = fadeInProgress

                // FAB 加号的位置（独立于弹窗）
                val fabCenterTargetX = dialogOffsetX + dialogWidthDp / 2f
                val fabCenterTargetY = dialogOffsetY + 100f  // 大致弹窗上部
                val fabCurrentX = lerp(fabCenterX, fabCenterTargetX, moveProgress) - 28f
                val fabCurrentY = lerp(fabCenterY, fabCenterTargetY, moveProgress) - 28f

                // 弹窗 Box（始终在最终位置）
                Box(
                    modifier = Modifier
                        .offset(x = dialogOffsetX.dp, y = dialogOffsetY.dp)
                        .fillMaxWidth(0.85f)
                        .wrapContentHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .alpha(dialogAlpha)
                        .then(
                            if (revealProgress in 0f..0.99f) {
                                // 阶段3：圆形裁剪从 FAB 中心向外扩展
                                Modifier.drawWithContent {
                                    val circleCenter = Offset(
                                        x = (fabCenterTargetX - dialogOffsetX).dp.toPx(),
                                        y = (fabCenterTargetY - dialogOffsetY).dp.toPx()
                                    )
                                    val maxRadius = kotlin.math.sqrt(
                                        (size.width * size.width + size.height * size.height).toDouble()
                                    ).toFloat()
                                    val circleRadius = maxRadius * revealProgress
                                    val path = Path().apply {
                                        addOval(Rect(circleCenter, circleRadius))
                                    }
                                    clipPath(path) { this@drawWithContent.drawContent() }
                                }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    DiaryDialogContent(
                        selectedDate = dates[selectedIndex],
                        cyanColor = cyanColor,
                        onDismiss = { showDialog = false },
                        onConfirm = { /* 待实现 */ }
                    )
                }

                // FAB 加号（独立移动 + 渐隐）
                val fabAlpha = if (moveProgress < 1f) 1f else (1f - fadeInProgress)
                if (fabAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .offset(x = fabCurrentX.dp, y = fabCurrentY.dp)
                            .size(56.dp)
                            .alpha(fabAlpha)
                            .clip(RoundedCornerShape(28.dp))
                            .background(cyanColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
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
                        .padding(end = 25.dp, bottom = 25.dp)
                        .onGloballyPositioned { coords ->
                            val pos = coords.localToWindow(Offset.Zero)
                            fabScreenX = pos.x / density.density
                            fabScreenY = pos.y / density.density
                            fabCenterX = fabScreenX + 28f
                            fabCenterY = fabScreenY + 28f
                        }
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
            .fillMaxWidth()
            .padding(20.dp),
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
