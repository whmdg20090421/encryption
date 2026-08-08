package com.whmdg.mczj.tools.ui.hook

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whmdg.mczj.tools.ui.hook.usage.AppUsageInfo
import com.whmdg.mczj.tools.ui.hook.usage.UsageTimeViewModel

// ==================== 自定义颜色 ====================

private val AccentPrimary = Color(0xFF7C3AED)
private val GradientPurple = Color(0xFF8B5CF6)
private val GradientPink = Color(0xFFEC4899)
private val GradientCyan = Color(0xFF06B6D4)
private val DarkCard = Color(0xFF1A1A24)
private val DarkCardElevated = Color(0xFF22222E)
private val GlassBorder = Color(0x33FFFFFF)
private val TextSecondary = Color(0xFF94A3B8)
private val UsageLow = Color(0xFF10B981)
private val UsageMedium = Color(0xFFFBBF24)
private val UsageHigh = Color(0xFFEF4444)

// ==================== 主界面 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageTimeScreen(
    onBack: () -> Unit,
    viewModel: UsageTimeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // onResume 时自动刷新
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 计算最大使用时长（用于进度条比例）
    val maxUsageMinutes = remember(uiState.appUsageList) {
        uiState.appUsageList.maxOfOrNull { it.usageTimeMillis / (1000 * 60) } ?: 180L
    }

    // 无权限提示状态
    var showPermissionHint by remember { mutableFloatStateOf(0f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手机使用时长") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 背景装饰：模糊光晕
            BackgroundDecorations()

            // 加载中
            AnimatedVisibility(
                visible = uiState.isLoading && uiState.appUsageList.isEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = AccentPrimary,
                        strokeWidth = 3.dp
                    )
                }
            }

            // 无权限
            AnimatedVisibility(
                visible = !uiState.hasPermission,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                PermissionRequiredState()
            }

            // 空数据（有权限但无使用记录）
            AnimatedVisibility(
                visible = uiState.hasPermission && !uiState.isLoading && uiState.appUsageList.isEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                EmptyState()
            }

            // 正常数据列表
            AnimatedVisibility(
                visible = uiState.appUsageList.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                UsageDataList(
                    totalScreenTime = uiState.totalScreenTime,
                    appUsageList = uiState.appUsageList,
                    maxUsageMinutes = maxUsageMinutes.coerceAtLeast(60L)
                )
            }
        }
    }
}

// ==================== 背景装饰 ====================

@Composable
private fun BackgroundDecorations() {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 20.dp)
                .size(200.dp)
                .blur(100.dp)
                .background(AccentPrimary.copy(alpha = 0.12f), CircleShape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 120.dp, start = 20.dp)
                .size(150.dp)
                .blur(80.dp)
                .background(GradientCyan.copy(alpha = 0.08f), CircleShape)
        )
    }
}

// ==================== 数据列表 ====================

@Composable
private fun UsageDataList(
    totalScreenTime: String,
    appUsageList: List<AppUsageInfo>,
    maxUsageMinutes: Long
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 顶部标题区
        item {
            HeaderSection()
        }

        // 总时长大卡片（带动画渐变）
        item {
            Spacer(modifier = Modifier.height(8.dp))
            UsageCard(
                title = "今日屏幕使用时长",
                value = totalScreenTime
            )
        }

        // 分区标题
        item {
            SectionHeader(title = "应用使用详情")
        }

        // 应用列表（带动画入场 + 进度条）
        itemsIndexed(
            items = appUsageList,
            key = { _, item -> item.packageName }
        ) { index, usage ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(300, delayMillis = index * 50)) +
                        slideInVertically(tween(300, delayMillis = index * 50)) { it / 2 }
            ) {
                AppUsageRow(
                    appUsageInfo = usage,
                    maxUsageMinutes = maxUsageMinutes
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

// ==================== 顶部标题区 ====================

@Composable
private fun HeaderSection() {
    val currentDate = remember {
        val sdf = java.text.SimpleDateFormat("EEEE, M月d日", java.util.Locale.getDefault())
        sdf.format(java.util.Date())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(AccentPrimary, GradientPink)
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.size(14.dp))
            Column {
                Text(
                    text = "使用洞察",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = currentDate,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

// ==================== 总时长大卡片（动画渐变背景） ====================

@Composable
private fun UsageCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "gradient")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
    ) {
        // 动画渐变背景
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            GradientPurple.copy(alpha = 0.8f),
                            AccentPrimary.copy(alpha = 0.9f),
                            GradientPink.copy(alpha = 0.7f)
                        ),
                        start = Offset(offset, 0f),
                        end = Offset(offset + 500f, 500f)
                    )
                )
        )

        // 玻璃态叠加层
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.White.copy(alpha = 0.05f))
        )

        // 装饰模糊圆
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 20.dp, top = 20.dp)
                .size(80.dp)
                .blur(40.dp)
                .background(GradientCyan.copy(alpha = 0.4f), CircleShape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 30.dp, bottom = 10.dp)
                .size(60.dp)
                .blur(30.dp)
                .background(GradientPink.copy(alpha = 0.4f), CircleShape)
        )

        // 内容
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.DateRange,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = value,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 52.sp,
                    letterSpacing = (-2).sp
                ),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 底部装饰条
            Box(
                modifier = Modifier
                    .size(width = 60.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f))
            )
        }
    }
}

// ==================== 分区标题 ====================

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 20.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 24.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(AccentPrimary, GradientPink)
                    )
                )
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

// ==================== 单个应用使用行 ====================

@Composable
private fun AppUsageRow(
    appUsageInfo: AppUsageInfo,
    modifier: Modifier = Modifier,
    maxUsageMinutes: Long = 180L
) {
    val usageMinutes = appUsageInfo.usageTimeMillis / (1000 * 60)
    val progress = (usageMinutes.toFloat() / maxUsageMinutes).coerceIn(0f, 1f)

    // 进度条动画
    var targetProgress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 800),
        label = "progress"
    )
    LaunchedEffect(progress) {
        targetProgress = progress
    }

    // 根据使用程度选择颜色
    val usageColor = when {
        progress < 0.33f -> UsageLow
        progress < 0.66f -> UsageMedium
        else -> UsageHigh
    }
    val gradientColors = when {
        progress < 0.33f -> listOf(GradientCyan, UsageLow)
        progress < 0.66f -> listOf(UsageMedium, GradientPink.copy(alpha = 0.7f))
        else -> listOf(GradientPink, UsageHigh)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = AccentPrimary.copy(alpha = 0.1f),
                spotColor = AccentPrimary.copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = DarkCardElevated
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 应用图标
                AppIconBox(appUsageInfo)

                Spacer(modifier = Modifier.width(16.dp))

                // 应用名 + 使用描述
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appUsageInfo.appName,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = getUsageDescription(usageMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                // 时长标签
                Box(
                    modifier = Modifier
                        .background(
                            brush = Brush.horizontalGradient(
                                gradientColors.map { it.copy(alpha = 0.2f) }
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = appUsageInfo.formattedTime,
                        style = MaterialTheme.typography.titleMedium,
                        color = usageColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 进度条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            brush = Brush.horizontalGradient(gradientColors)
                        )
                )
            }
        }
    }
}

// ==================== 应用图标 ====================

@Composable
private fun AppIconBox(appUsageInfo: AppUsageInfo) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(
                color = GlassBorder,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        appUsageInfo.appIcon?.let { drawable ->
            val bitmap = remember(drawable) {
                try {
                    if (drawable is BitmapDrawable) {
                        drawable.bitmap
                    } else {
                        val bmp = android.graphics.Bitmap.createBitmap(
                            drawable.intrinsicWidth.coerceAtLeast(1),
                            drawable.intrinsicHeight.coerceAtLeast(1),
                            android.graphics.Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(bmp)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bmp
                    }
                } catch (_: Exception) {
                    null
                }
            }

            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = appUsageInfo.appName,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                IconPlaceholder()
            }
        } ?: IconPlaceholder()
    }
}

@Composable
private fun IconPlaceholder() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(GradientPurple.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
    )
}

// ==================== 使用描述文本 ====================

private fun getUsageDescription(minutes: Long): String {
    return when {
        minutes < 30 -> "轻度使用"
        minutes < 60 -> "适度使用"
        minutes < 120 -> "较重使用"
        else -> "重度使用"
    }
}

// ==================== 空状态 ====================

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            AccentPrimary.copy(alpha = 0.2f),
                            GradientPurple.copy(alpha = 0.2f)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = AccentPrimary,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "暂无使用数据",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "开始使用你的应用，稍后再来查看使用统计。",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

// ==================== 无权限状态 ====================

@Composable
private fun PermissionRequiredState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            GradientPink.copy(alpha = 0.2f),
                            UsageHigh.copy(alpha = 0.2f)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = GradientPink,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "需要使用情况访问权限",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "请前往 设置 → 安全 → 有使用权限的应用，授予本应用[使用情况访问权限]后返回此页面。",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}
