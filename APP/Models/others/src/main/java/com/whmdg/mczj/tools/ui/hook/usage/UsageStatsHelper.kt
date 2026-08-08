package com.whmdg.mczj.tools.ui.hook.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import java.util.Calendar
import java.util.Locale

/**
 * 使用时长数据查询助手
 *
 * 通过 UsageStatsManager 查询应用使用时长，
 * 结合 INTERVAL_DAILY 聚合数据 + UsageEvents 实时数据（今日），
 * 确保数据准确性。
 */
class UsageStatsHelper(private val context: Context) {

    private val usageStatsManager: UsageStatsManager? by lazy {
        try {
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        } catch (_: Exception) {
            null
        }
    }

    private val packageManager: PackageManager by lazy {
        context.packageManager
    }

    companion object {
        /** 最低使用时长阈值（30 秒），过滤掉短暂启动 */
        private const val MIN_USAGE_MS = 30_000L
    }

    /**
     * 检查是否已授予"使用情况访问权限"
     */
    fun hasUsagePermission(): Boolean {
        return try {
            val appOpsManager =
                context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                    ?: return false

            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOpsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取今日应用使用时长列表
     *
     * @return 按使用时长降序排列的应用列表，无权限时返回空列表
     */
    fun getTodayUsage(): List<AppUsageInfo> {
        if (!hasUsagePermission()) return emptyList()

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        return getUsageForTimeRange(startTime, endTime, isToday = true)
    }

    /**
     * 获取指定天数前的使用时长
     *
     * @param daysAgo 0=今天，1=昨天，以此类推
     */
    fun getUsageForDate(daysAgo: Int): List<AppUsageInfo> {
        if (!hasUsagePermission()) return emptyList()

        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -daysAgo)

        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis

        val endTime = if (daysAgo == 0) {
            System.currentTimeMillis()
        } else {
            calendar.set(Calendar.HOUR_OF_DAY, 23)
            calendar.set(Calendar.MINUTE, 59)
            calendar.set(Calendar.SECOND, 59)
            calendar.set(Calendar.MILLISECOND, 999)
            calendar.timeInMillis
        }

        return getUsageForTimeRange(startTime, endTime, isToday = daysAgo == 0)
    }

    /**
     * 核心查询方法：获取指定时间范围内的使用时长
     *
     * 策略：
     * 1. 先用 INTERVAL_DAILY 获取聚合数据（跨设备最可靠）
     * 2. 若为今日，再用 UsageEvents 获取实时前台数据（更准确）
     * 3. 取两者中较大值
     */
    private fun getUsageForTimeRange(
        startTime: Long,
        endTime: Long,
        isToday: Boolean
    ): List<AppUsageInfo> {
        val statsManager = usageStatsManager ?: return emptyList()

        val aggregatedStats = mutableMapOf<String, Long>()

        // 主方法：INTERVAL_DAILY 聚合查询
        try {
            val dailyStats = statsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )
            if (!dailyStats.isNullOrEmpty()) {
                for (stats in dailyStats) {
                    val totalTime = stats.totalTimeInForeground
                    if (totalTime > 0) {
                        val current = aggregatedStats[stats.packageName] ?: 0L
                        aggregatedStats[stats.packageName] = current + totalTime
                    }
                }
            }
        } catch (_: Exception) {
            // 忽略查询异常
        }

        // 辅助方法（仅今日）：UsageEvents 实时数据
        if (isToday) {
            try {
                val eventsStats = getUsageFromEvents(startTime, endTime)
                for ((packageName, time) in eventsStats) {
                    if (time > 0) {
                        val current = aggregatedStats[packageName] ?: 0L
                        aggregatedStats[packageName] = maxOf(current, time)
                    }
                }
            } catch (_: Exception) {
                // 忽略查询异常
            }
        }

        // 过滤 + 转换
        return aggregatedStats
            .filter { it.value >= MIN_USAGE_MS }
            .filter { !isSystemApp(it.key) }
            .map { (packageName, usageTime) ->
                AppUsageInfo(
                    packageName = packageName,
                    appName = getAppName(packageName),
                    usageTimeMillis = usageTime,
                    appIcon = getAppIcon(packageName)
                )
            }
            .sortedByDescending { it.usageTimeMillis }
    }

    /**
     * 通过 UsageEvents 计算前台使用时长
     *
     * 跟踪 ACTIVITY_RESUMED / ACTIVITY_PAUSED 事件对，
     * 比 queryUsageStats 更精确（尤其是今日数据）。
     */
    private fun getUsageFromEvents(startTime: Long, endTime: Long): Map<String, Long> {
        val statsManager = usageStatsManager ?: return emptyMap()

        val usageMap = mutableMapOf<String, Long>()
        val lastResumeTime = mutableMapOf<String, Long>()

        try {
            val usageEvents = statsManager.queryEvents(startTime, endTime) ?: return emptyMap()
            val event = UsageEvents.Event()

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                val packageName = event.packageName ?: continue

                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        lastResumeTime[packageName] = event.timeStamp
                    }
                    @Suppress("DEPRECATION")
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            lastResumeTime[packageName] = event.timeStamp
                        }
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        val resumeTime = lastResumeTime.remove(packageName)
                        if (resumeTime != null && resumeTime > 0) {
                            val duration = event.timeStamp - resumeTime
                            if (duration in 1 until 24 * 60 * 60 * 1000) {
                                usageMap[packageName] = (usageMap[packageName] ?: 0L) + duration
                            }
                        }
                    }
                    @Suppress("DEPRECATION")
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            val resumeTime = lastResumeTime.remove(packageName)
                            if (resumeTime != null && resumeTime > 0) {
                                val duration = event.timeStamp - resumeTime
                                if (duration in 1 until 24 * 60 * 60 * 1000) {
                                    usageMap[packageName] = (usageMap[packageName] ?: 0L) + duration
                                }
                            }
                        }
                    }
                }
            }

            // 处理仍在前台的应用（没有 PAUSED 事件）
            val currentTime = System.currentTimeMillis().coerceAtMost(endTime)
            for ((packageName, resumeTime) in lastResumeTime) {
                if (resumeTime > 0 && currentTime > resumeTime) {
                    val duration = currentTime - resumeTime
                    if (duration in 1 until 24 * 60 * 60 * 1000) {
                        usageMap[packageName] = (usageMap[packageName] ?: 0L) + duration
                    }
                }
            }
        } catch (_: SecurityException) {
            // 权限被撤销
        } catch (_: Exception) {
            // 忽略其他异常
        }

        return usageMap
    }

    /**
     * 判断是否为应过滤的系统组件
     *
     * 保留用户可见的系统应用（Google 套件、Play Store 等），
     * 过滤掉 SystemUI、Settings、电话等核心组件。
     */
    private fun isSystemApp(packageName: String): Boolean {
        val whitelistPrefixes = listOf(
            "com.google.android.apps.",
            "com.google.android.youtube",
            "com.android.chrome",
            "com.android.vending",
        )
        if (whitelistPrefixes.any { packageName.startsWith(it) }) return false

        val blacklistPrefixes = listOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.incallui",
            "com.android.server",
            "com.android.providers.",
            "com.android.inputmethod",
            "com.google.android.inputmethod",
            "com.android.internal",
            "com.android.keyguard",
            "com.android.launcher",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
        )
        val blacklistExact = listOf("android", "com.android.shell")

        if (blacklistExact.contains(packageName) ||
            blacklistPrefixes.any { packageName.startsWith(it) }
        ) return true

        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdated = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            isSystemApp && !isUpdated
        } catch (_: PackageManager.NameNotFoundException) {
            true
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast(".")
                .replaceFirstChar { it.uppercase(Locale.getDefault()) }
        }
    }

    private fun getAppIcon(packageName: String): Drawable? {
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 格式化总时长为可读字符串，如 "3h 45m"
     */
    fun formatScreenTime(totalMillis: Long): String {
        val hours = totalMillis / (1000 * 60 * 60)
        val minutes = (totalMillis / (1000 * 60)) % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "0m"
        }
    }
}
