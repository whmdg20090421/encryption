package com.whmdg.mczj.tools.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// 笔记本详情：点开某个笔记本后的内部界面
@Composable
fun DiaryBookScreen(
    bookName: String,
    onBack: () -> Unit
) {
    val density = LocalDensity.current

    // 生成日期列表：前后各 10 年，共约 7300 天
    val totalPast = 3650
    val totalFuture = 3650
    val todayIndex = totalPast  // 今天在列表中的位置

    val dates = remember {
        val monthFmt = SimpleDateFormat("M月", Locale.CHINA)
        val dayFmt = SimpleDateFormat("dd", Locale.CHINA)
        (-totalPast..totalFuture).map { offset ->
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, offset)
            DateEntry(
                month = monthFmt.format(c.time),
                day = dayFmt.format(c.time)
            )
        }
    }

    // 默认选中今天，滚动到今天的位置
    var selectedIndex by remember { mutableIntStateOf(todayIndex) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        listState.scrollToItem(todayIndex)
    }

    Scaffold { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        // 顶部工具栏 — 名称居中于整个工具栏，不与左侧按钮重叠
        var leftButtonEndPx by remember { mutableIntStateOf(0) }
        val leftButtonEndDp = with(density) { leftButtonEndPx.toDp() }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // 返回按钮
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .onSizeChanged { leftButtonEndPx = it.width }
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }

            // 名称 — 相对整个工具栏居中，左 padding 避让按钮
            Text(
                text = bookName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(start = leftButtonEndDp)
            )
        }

        // 日期时间线 — 竖线贴左边缘，圆圈左边缘与屏幕齐平
        val circleRadius = 8.dp
        val circleDiameter = circleRadius * 2
        val isDark = isSystemInDarkTheme()
        val cyanColor = if (isDark) Color(0xFF4DB6AC) else Color(0xFF00BCD4)  // 暗青 / 亮青

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .offset(x = -(circleRadius))
                .padding(top = 8.dp, bottom = 8.dp)
        ) {
            itemsIndexed(dates) { index, entry ->
                val isSelected = index == selectedIndex
                val circleColor = if (isSelected) cyanColor else cyanColor.copy(alpha = 0.4f)
                val textColor = if (isSelected)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurfaceVariant

                val entryHeight = 44.dp

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
    }
    } // Scaffold
}

private data class DateEntry(
    val month: String,
    val day: String
)
