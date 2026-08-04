package com.whmdg.mczj.tools.ui.hook

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.security.UsageStatsReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

private data class AppUsage(val packageName: String, val appName: String, val icon: Drawable?, val durationMs: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageStatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appList = remember { loadAppUsage(context) }
    val totalMs = remember { appList.sumOf { it.durationMs } }
    var showReportDialog by remember { mutableStateOf(false) }

    if (showReportDialog) {
        var result by remember { mutableStateOf<UsageStatsReporter.ReportResult?>(null) }
        var loading by remember { mutableStateOf(true) }
        val clipboard = LocalClipboardManager.current
        LaunchedEffect(Unit) {
            result = withContext(Dispatchers.IO) {
                UsageStatsReporter.reportTestEvent(context)
            }
            loading = false
        }
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text("reportEvent 反射测试") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (loading) {
                        Text(
                            "正在调用 reportEvent...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (result == null) {
                        Text("未知错误", color = MaterialTheme.colorScheme.error)
                    } else {
                        val r = result!!
                        if (!r.success) {
                            Text(
                                "失败：${r.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        } else {
                            Text(
                                "✓ ${r.message}\n\n注入事件：\n${r.eventDetail}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (r.queryVerified) "✓ 查询验证：${r.queryDetail}"
                                else "⚠ 查询验证：${r.queryDetail}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (r.queryVerified) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "说明：事件写入内存缓冲，需等待系统 flush（约 20 分钟）或设备重启后才会持久化到文件。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val r = result ?: return@TextButton
                    val text = buildString {
                        appendLine(if (r.success) "成功: ${r.message}" else "失败: ${r.message}")
                        if (r.eventDetail.isNotEmpty()) appendLine("事件: ${r.eventDetail}")
                        if (r.queryDetail.isNotEmpty()) appendLine("验证: ${r.queryDetail}")
                    }
                    clipboard.setText(AnnotatedString(text))
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("屏幕使用时长") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showReportDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "reportEvent 测试")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── 总时长 ──
            item {
                Text(
                    formatDuration(totalMs),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
                )
                Text(
                    "今日屏幕使用时长",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
                )
            }

            // ── 应用列表卡片 ──
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        appList.forEachIndexed { index, app ->
                            AppUsageRow(app)
                            if (index < appList.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.08f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppUsageRow(app: AppUsage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val iconBitmap = remember(app.packageName) { app.icon?.toBitmapOrNull() }
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = null,
                modifier = Modifier.size(40.dp).padding(2.dp)
            )
        } else {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {}
        }
        Spacer(Modifier.width(12.dp))
        Text(
            app.appName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            formatDuration(app.durationMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── 数据获取：参考 com.liuml.apptimelimiter 的 UsageEventDurationCalculator ──

private fun loadAppUsage(context: Context): List<AppUsage> {
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val pm = context.packageManager
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val startOfDay = cal.timeInMillis
    val now = System.currentTimeMillis()

    val events = usm.queryEvents(startOfDay, now)
    if (!events.hasNextEvent()) return emptyList()

    // 状态追踪
    val foreground = mutableMapOf<String, Long>() // 包名 → 进入前台的时间戳
    var screenOn = true
    val durations = mutableMapOf<String, Long>()  // 包名 → 累计时长

    val event = UsageEvents.Event()
    while (events.getNextEvent(event)) {
        when (event.eventType) {
            // app 进入前台
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                foreground[event.packageName] = event.timeStamp
            }
            // app 退到后台
            UsageEvents.Event.ACTIVITY_PAUSED -> {
                val started = foreground.remove(event.packageName) ?: continue
                if (screenOn) {
                    val pkg = event.packageName
                    durations[pkg] = (durations[pkg] ?: 0L) + (event.timeStamp - started)
                }
            }
            // 屏幕关闭 (type=24 SCREEN_NON_INTERACTIVE, type=23 SCREEN_INTERACTIVE)
            24 -> {
                // 屏幕关闭，停止所有前台 app 计时
                screenOn = false
                for ((pkg, started) in foreground) {
                    durations[pkg] = (durations[pkg] ?: 0L) + (event.timeStamp - started)
                }
                foreground.clear()
            }
            // 屏幕亮起
            23 -> {
                screenOn = true
            }
        }
    }

    // 仍在前台的 app，计算到 now
    if (screenOn) {
        for ((pkg, started) in foreground) {
            durations[pkg] = (durations[pkg] ?: 0L) + (now - started)
        }
    }

    // 过滤系统包、排序、构建列表
    return durations.entries
        .filter { it.value > 0 }
        .sortedByDescending { it.value }
        .mapNotNull { (pkg, ms) ->
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)) }
            catch (_: PackageManager.NameNotFoundException) { pkg }
            val icon = try { pm.getApplicationIcon(pkg) }
            catch (_: PackageManager.NameNotFoundException) { null }
            AppUsage(pkg, label.toString(), icon, ms)
        }
}

private fun formatDuration(ms: Long): String {
    val totalMin = ms / 60000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h} 时 ${m} 分" else "${m} 分"
}

private fun Drawable.toBitmapOrNull(): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val bmp = android.graphics.Bitmap.createBitmap(
            intrinsicWidth.coerceAtLeast(1), intrinsicHeight.coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val c = android.graphics.Canvas(bmp)
        setBounds(0, 0, c.width, c.height); draw(c)
        bmp.asImageBitmap()
    } catch (_: Exception) { null }
}

