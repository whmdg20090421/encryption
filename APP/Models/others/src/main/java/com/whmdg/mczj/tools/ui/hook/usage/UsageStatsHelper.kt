package com.whmdg.mczj.tools.ui.hook.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.whmdg.mczj.tools.AppDataPaths
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import java.util.Calendar
import java.util.Locale

/**
 * 数据来源策略
 */
enum class MergeStrategy {
    PATH_A_ONLY,      // 仅路径 A（queryUsageStats）
    PATH_B_ONLY,      // 仅路径 B（queryEvents）
    MERGED_MAX,       // 两路合并取最大值（默认）
    MERGED_MIN        // 两路合并取最小值
}

/**
 * 单条路径的查询结果（用于设置弹窗显示各路径时间）
 */
data class PathResult(
    val pathATimeMillis: Long,  // 路径 A：queryUsageStats
    val pathBTimeMillis: Long   // 路径 B：queryEvents
)

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

    // ── 包名→应用名缓存 ──
    private val appNameCache: MutableMap<String, String> by lazy {
        loadAppNameCache().toMutableMap()
    }
    private val prefs by lazy {
        context.getSharedPreferences(AppDataPaths.PREFS_HOOK, Context.MODE_PRIVATE)
    }

    companion object {
        /** 最低使用时长阈值（30 秒），过滤掉短暂启动 */
        private const val MIN_USAGE_MS = 0L
        private const val KEY_APP_NAME_CACHE = "app_name_cache"
        private const val SEPARATOR = "\t" // 包名与名称的分隔符
    }

    /**
     * 判断一段使用事件是否与一次性排除时间段重叠
     */
    private fun isExcludedByOneTimeRange(
        resumeTime: Long, pauseTime: Long,
        oneTimeRanges: List<ExcludedTimeRange>
    ): Boolean {
        return oneTimeRanges.any { range ->
            resumeTime < range.endMillis && pauseTime > range.startMillis
        }
    }

    /**
     * 判断某个时间点是否被周期性排除规则命中
     *
     * 条件：时间点所在日期的星期几在 repeatDays 中，
     * 且时间点的分钟在排除范围内。
     */
    private fun isMinuteExcludedByRecurring(
        timeMillis: Long,
        recurringRanges: List<ExcludedTimeRange>
    ): Boolean {
        if (recurringRanges.isEmpty()) return false
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1 // Monday=1..Sunday=7
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return recurringRanges.any { range ->
            dow in range.repeatDays!! && minuteOfDay >= range.startMinuteOfDay && minuteOfDay < range.endMinuteOfDay
        }
    }

    /**
     * 从 SharedPreferences 加载缓存
     */
    private fun loadAppNameCache(): Map<String, String> {
        val set = prefs.getStringSet(KEY_APP_NAME_CACHE, null) ?: return emptyMap()
        return set.mapNotNull { entry ->
            val parts = entry.split(SEPARATOR, limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    /**
     * 将缓存写入 SharedPreferences
     */
    private fun saveAppNameCache() {
        val set = appNameCache.map { "${it.key}$SEPARATOR${it.value}" }.toSet()
        prefs.edit().putStringSet(KEY_APP_NAME_CACHE, set).apply()
    }

    /**
     * 刷新缓存：遍历当前缓存条目，更新已变更的名称，删除已不存在且缓存中没有原始记录的条目
     */
    fun refreshAppNameCache(usedPackageNames: Set<String>) {
        var changed = false
        // 更新已变更的名称
        for (pkg in usedPackageNames) {
            val cachedName = appNameCache[pkg]
            val currentName = queryAppName(pkg)
            if (cachedName != currentName) {
                appNameCache[pkg] = currentName
                changed = true
            }
        }
        if (changed) saveAppNameCache()
    }

    /**
     * 检查是否已授予"使用情况访问权限"
     */
    /** Path A 原始数据条目 */
    data class PathAStatEntry(
        val packageName: String,
        val totalTimeInForeground: Long,
        val lastTimeUsed: Long,
        val firstTimeStamp: Long,
        val totalTimeVisible: Long,
        val totalTimeForegroundServiceUsed: Long,
    )

    /**
     * 获取 Path A（queryUsageStats）的原始数据，用于导出调试
     */
    fun getPathARawStats(startTime: Long, endTime: Long): List<PathAStatEntry> {
        val statsManager = usageStatsManager ?: return emptyList()
        return try {
            statsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
                ?.filter { it.totalTimeInForeground > 0 }
                ?.map { stats ->
                    PathAStatEntry(
                        packageName = stats.packageName,
                        totalTimeInForeground = stats.totalTimeInForeground,
                        lastTimeUsed = stats.lastTimeUsed,
                        firstTimeStamp = stats.firstTimeStamp,
                        totalTimeVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) stats.totalTimeVisible else 0L,
                        totalTimeForegroundServiceUsed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) stats.totalTimeForegroundServiceUsed else 0L,
                    )
                }
                ?.sortedByDescending { it.totalTimeInForeground }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

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
     * @param strategy 数据合并策略，默认取较大值
     * @return 按使用时长降序排列的应用列表，无权限时返回空列表
     */
    fun getTodayUsage(
        strategy: MergeStrategy = MergeStrategy.MERGED_MAX,
        excludedPackages: Set<String> = emptySet(),
        excludedTimeRanges: List<ExcludedTimeRange> = emptyList()
    ): List<AppUsageInfo> {
        if (!hasUsagePermission()) return emptyList()

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        return getUsageForTimeRange(startTime, endTime, isToday = true, strategy = strategy, excludedPackages = excludedPackages, excludedTimeRanges = excludedTimeRanges)
    }

    /**
     * 获取今日各路径的原始时间（用于设置弹窗显示）
     *
     * @return 包含路径 A 和路径 B 的总时长
     */
    fun getTodayPathTimes(): PathResult {
        if (!hasUsagePermission()) return PathResult(0L, 0L)

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val statsManager = usageStatsManager ?: return PathResult(0L, 0L)

        // 路径 A：queryUsageStats
        var pathATotal = 0L
        try {
            val dailyStats = statsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, startTime, endTime
            )
            if (!dailyStats.isNullOrEmpty()) {
                pathATotal = dailyStats.sumOf { it.totalTimeInForeground }
            }
        } catch (_: Exception) {}

        // 路径 B：queryEvents
        val eventsMap = getUsageFromEvents(startTime, endTime)
        val pathBTotal = eventsMap.values.sum()

        return PathResult(pathATotal, pathBTotal)
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
     * 3. 根据 strategy 取较大值或较小值
     */
    private fun getUsageForTimeRange(
        startTime: Long,
        endTime: Long,
        isToday: Boolean,
        strategy: MergeStrategy = MergeStrategy.MERGED_MAX,
        excludedPackages: Set<String> = emptySet(),
        excludedTimeRanges: List<ExcludedTimeRange> = emptyList()
    ): List<AppUsageInfo> {
        val statsManager = usageStatsManager ?: return emptyList()

        // 路径 A：INTERVAL_DAILY 聚合查询
        val pathAStats = mutableMapOf<String, Long>()
        try {
            val dailyStats = statsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, startTime, endTime
            )
            if (!dailyStats.isNullOrEmpty()) {
                for (stats in dailyStats) {
                    val totalTime = stats.totalTimeInForeground
                    if (totalTime > 0) {
                        val current = pathAStats[stats.packageName] ?: 0L
                        pathAStats[stats.packageName] = current + totalTime
                    }
                }
            }
        } catch (_: Exception) {}

        // 路径 B：UsageEvents 实时数据（仅今日）
        val pathBStats = if (isToday) {
            try { getUsageFromEvents(startTime, endTime, excludedTimeRanges) } catch (_: Exception) { emptyMap() }
        } else {
            emptyMap()
        }

        // 根据策略选择数据源
        val aggregatedStats = when (strategy) {
            MergeStrategy.PATH_A_ONLY -> pathAStats
            MergeStrategy.PATH_B_ONLY -> pathBStats
            MergeStrategy.MERGED_MAX -> {
                val merged = mutableMapOf<String, Long>()
                for (key in pathAStats.keys + pathBStats.keys) {
                    merged[key] = maxOf(pathAStats[key] ?: 0L, pathBStats[key] ?: 0L)
                }
                merged
            }
            MergeStrategy.MERGED_MIN -> {
                val merged = mutableMapOf<String, Long>()
                for (key in pathAStats.keys + pathBStats.keys) {
                    val a = pathAStats[key] ?: 0L
                    val b = pathBStats[key] ?: 0L
                    merged[key] = when {
                        a > 0 && b > 0 -> minOf(a, b)
                        a > 0 -> a
                        else -> b
                    }
                }
                merged
            }
        }

        // 过滤 + 转换
        return aggregatedStats
            .filter { it.value >= MIN_USAGE_MS }
            .filter { !isSystemApp(it.key) }
            .filter { it.key !in excludedPackages }
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
    private fun getUsageFromEvents(
        startTime: Long,
        endTime: Long,
        excludedTimeRanges: List<ExcludedTimeRange> = emptyList()
    ): Map<String, Long> {
        val statsManager = usageStatsManager ?: return emptyMap()

        val oneTimeRanges = excludedTimeRanges.filter { !it.isRecurring }
        val recurringRanges = excludedTimeRanges.filter { it.isRecurring }

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
                            if (duration in 1 until 24 * 60 * 60 * 1000
                                && !isExcludedByOneTimeRange(resumeTime, event.timeStamp, oneTimeRanges)
                                && !isMinuteExcludedByRecurring((resumeTime + event.timeStamp) / 2, recurringRanges)) {
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
                                if (duration in 1 until 24 * 60 * 60 * 1000
                                    && !isExcludedByOneTimeRange(resumeTime, event.timeStamp, oneTimeRanges)
                                    && !isMinuteExcludedByRecurring((resumeTime + event.timeStamp) / 2, recurringRanges)) {
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
                    if (duration in 1 until 24 * 60 * 60 * 1000
                        && !isExcludedByOneTimeRange(resumeTime, currentTime, oneTimeRanges)
                        && !isMinuteExcludedByRecurring((resumeTime + currentTime) / 2, recurringRanges)) {
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
     * 判断是否为核心系统进程（不可交互，无用户界面）
     * 只排除 android、SystemUI、Shell、电话服务等核心进程，其他应用全部显示。
     */
    private fun isSystemApp(packageName: String): Boolean {
        return packageName in setOf(
            "android",
            "com.android.systemui",
            "com.android.shell",
            "com.android.phone",
            "com.android.server",
        )
    }

    /**
     * 获取应用名称（优先读缓存，未命中则查询 PackageManager 并写入缓存）
     */
    private fun getAppName(packageName: String): String {
        // 优先读缓存
        appNameCache[packageName]?.let { return it }

        // 缓存未命中，查询系统
        val name = queryAppName(packageName)
        appNameCache[packageName] = name
        saveAppNameCache()
        return name
    }

    /**
     * 直接查询 PackageManager 获取应用名称（不做缓存）
     */
    private fun queryAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            "已卸载应用"
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
            hours > 0 -> "${hours}小时${minutes}分"
            minutes > 0 -> "${minutes}分"
            else -> "0分"
        }
    }

    /**
     * 格式化毫秒为 "Xm Ys" 格式（用于表格单元格）
     */
    fun formatMillisShort(millis: Long): String {
        if (millis <= 0) return ""
        val minutes = millis / (1000 * 60)
        val seconds = (millis / 1000) % 60
        return when {
            minutes > 0 -> "${minutes}m${seconds}s"
            else -> "${seconds}s"
        }
    }

    /**
     * 构建今日每小时使用时长表格（实时获取）
     *
     * 通过 UsageEvents 逐条遍历，将每个 RESUMED→PAUSED 事件对
     * 按小时分桶，支持跨小时拆分。
     *
     * @return 表格数据，无权限时返回 null
     */
    fun buildHourlyTable(
        excludedPackages: Set<String> = emptySet(),
        excludedTimeRanges: List<ExcludedTimeRange> = emptyList()
    ): HourlyUsageTable? {
        if (!hasUsagePermission()) return null

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val statsManager = usageStatsManager ?: return null

        val oneTimeRanges = excludedTimeRanges.filter { !it.isRecurring }
        val recurringRanges = excludedTimeRanges.filter { it.isRecurring }

        // 包名 → 24 小时桶（毫秒）
        val tableData = mutableMapOf<String, LongArray>()

        try {
            val usageEvents = statsManager.queryEvents(startTime, endTime) ?: return null
            val event = UsageEvents.Event()
            val lastResumeTime = mutableMapOf<String, Long>()
            val startOfDay = startTime

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
                        val resumeTime = lastResumeTime.remove(packageName) ?: continue
                        val pauseTime = event.timeStamp
                        if (pauseTime > resumeTime
                            && !isExcludedByOneTimeRange(resumeTime, pauseTime, oneTimeRanges)) {
                            addUsageToHourlyBuckets(
                                tableData, packageName, resumeTime, pauseTime, startOfDay, recurringRanges
                            )
                        }
                    }
                    @Suppress("DEPRECATION")
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            val resumeTime = lastResumeTime.remove(packageName) ?: continue
                            val pauseTime = event.timeStamp
                            if (pauseTime > resumeTime
                                && !isExcludedByOneTimeRange(resumeTime, pauseTime, oneTimeRanges)) {
                                addUsageToHourlyBuckets(
                                    tableData, packageName, resumeTime, pauseTime, startOfDay, recurringRanges
                                )
                            }
                        }
                    }
                }
            }

            // 处理仍在前台的应用
            val currentTime = System.currentTimeMillis().coerceAtMost(endTime)
            for ((packageName, resumeTime) in lastResumeTime) {
                if (resumeTime > 0 && currentTime > resumeTime
                    && !isExcludedByOneTimeRange(resumeTime, currentTime, oneTimeRanges)) {
                    addUsageToHourlyBuckets(
                        tableData, packageName, resumeTime, currentTime, startOfDay, recurringRanges
                    )
                }
            }
        } catch (_: Exception) {
            return null
        }

        // 过滤系统应用，按总时长降序排列
        val rows = tableData
            .filter { !isSystemApp(it.key) }
            .filter { it.key !in excludedPackages }
            .filter { it.value.sum() >= MIN_USAGE_MS }
            .map { (packageName, hourly) ->
                HourlyUsageTable.HourlyRow(
                    packageName = packageName,
                    hourlyMillis = hourly
                )
            }
            .sortedByDescending { it.totalMillis }

        if (rows.isEmpty()) return null

        // 汇总行：每列求和
        val summaryHourly = LongArray(24)
        for (row in rows) {
            for (i in 0 until 24) {
                summaryHourly[i] += row.hourlyMillis[i]
            }
        }

        return HourlyUsageTable(
            rows = rows,
            summaryRow = HourlyUsageTable.HourlySummaryRow(summaryHourly)
        )
    }

    /**
     * 构建按应用类别分组的每小时数据（用于柱状图堆叠显示）
     *
     * 复用 buildHourlyTable 的逻辑，额外按 ApplicationInfo.category 分组。
     * @return 类别分组数据，无权限时返回 null
     */
    fun buildCategoryHourlyData(
        excludedPackages: Set<String> = emptySet(),
        excludedTimeRanges: List<ExcludedTimeRange> = emptyList()
    ): CategoryHourlyData? {
        val table = buildHourlyTable(excludedPackages, excludedTimeRanges) ?: return null

        val gameHourly = LongArray(24)
        val mediaHourly = LongArray(24)
        val otherHourly = LongArray(24)

        for (row in table.rows) {
            val category = getAppCategory(row.packageName)
            val target = when (category) {
                ApplicationInfo.CATEGORY_GAME -> gameHourly
                ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_AUDIO -> mediaHourly
                else -> otherHourly
            }
            for (i in 0 until 24) {
                target[i] += row.hourlyMillis[i]
            }
        }

        return CategoryHourlyData(gameHourly, mediaHourly, otherHourly)
    }

    /**
     * 获取应用的类别
     */
    /**
     * 获取应用类别
     *
     * 三层匹配策略：
     * 1. 包名精确匹配映射表
     * 2. 应用名关键词匹配（通过缓存中的名称）
     * 3. 系统 ApplicationInfo.category
     */
    private fun getAppCategory(packageName: String): Int {
        // ── 第一层：包名精确匹配（常用应用） ──
        val packageCategoryMap = mapOf(
            // 游戏
            "com.tencent.tmgp.sgame" to ApplicationInfo.CATEGORY_GAME,           // 王者荣耀
            "com.tencent.tmgp.pubgmhd" to ApplicationInfo.CATEGORY_GAME,         // 和平精英
            "com.tencent.tmgp.cf" to ApplicationInfo.CATEGORY_GAME,              // 穿越火线
            "com.tencent.tmgp.codev" to ApplicationInfo.CATEGORY_GAME,          // 无畏契约
            "com.tencent.lolm" to ApplicationInfo.CATEGORY_GAME,                 // 英雄联盟手游
            "com.tencent.jkchess" to ApplicationInfo.CATEGORY_GAME,              // 金铲铲之战
            "com.miHoYo.Yuanshen" to ApplicationInfo.CATEGORY_GAME,             // 原神
            "com.miHoYo.hkrpg" to ApplicationInfo.CATEGORY_GAME,                // 崩坏：星穹铁道
            "com.netease.g93na" to ApplicationInfo.CATEGORY_GAME,               // 第五人格
            "com.netease.mrzh" to ApplicationInfo.CATEGORY_GAME,                // 明日之后
            "com.netease.g78na" to ApplicationInfo.CATEGORY_GAME,               // 蛋仔派对
            "com.netease.g95" to ApplicationInfo.CATEGORY_GAME,                 // 永劫无间
            "com.hypergryph.arknights" to ApplicationInfo.CATEGORY_GAME,        // 明日方舟
            "com.supercell.clashofclans" to ApplicationInfo.CATEGORY_GAME,      // 部落冲突

            // 视频
            "com.ss.android.ugc.aweme" to ApplicationInfo.CATEGORY_VIDEO,       // 抖音
            "com.ss.android.ugc.aweme.lite" to ApplicationInfo.CATEGORY_VIDEO,  // 抖音极速版
            "tv.danmaku.bili" to ApplicationInfo.CATEGORY_VIDEO,                // 哔哩哔哩
            "com.youku.phone" to ApplicationInfo.CATEGORY_VIDEO,                // 优酷
            "com.qiyi.video" to ApplicationInfo.CATEGORY_VIDEO,                 // 爱奇艺
            "com.tencent.qqlive" to ApplicationInfo.CATEGORY_VIDEO,             // 腾讯视频
            "com.hunantv.imgo" to ApplicationInfo.CATEGORY_VIDEO,               // 芒果TV
            "com.smile.gifmaker" to ApplicationInfo.CATEGORY_VIDEO,             // 快手
            "com.ss.android.article.video" to ApplicationInfo.CATEGORY_VIDEO,   // 西瓜视频

            // 音频
            "com.netease.cloudmusic" to ApplicationInfo.CATEGORY_AUDIO,         // 网易云音乐
            "com.kugou.android" to ApplicationInfo.CATEGORY_AUDIO,              // 酷狗音乐
            "com.tencent.qqmusic" to ApplicationInfo.CATEGORY_AUDIO,            // QQ音乐
            "com.ximalaya.ting.android" to ApplicationInfo.CATEGORY_AUDIO,      // 喜马拉雅
            "com.kuwo.player" to ApplicationInfo.CATEGORY_AUDIO,                // 酷我音乐
            "fm.qingting.qtradio" to ApplicationInfo.CATEGORY_AUDIO,            // 蜻蜓FM
        )

        // 包名精确匹配
        packageCategoryMap[packageName]?.let { return it }

        // ── 第二层：应用名关键词匹配 ──
        val appName = appNameCache[packageName] ?: queryAppName(packageName)
        val nameCategoryMap = listOf(
            // 游戏关键词
            ApplicationInfo.CATEGORY_GAME to listOf(
                "王者", "荣耀", "和平精英", "穿越火线", "使命召唤", "QQ飞车",
                "地下城", "勇士", "英雄联盟", "金铲铲", "无畏契约", "VALORANT",
                "原神", "崩坏", "星穹铁道", "绝区零", "第五人格", "明日之后",
                "蛋仔派对", "永劫无间", "剑与远征", "部落冲突", "皇室战争",
                "元梦之星", "明日方舟", "白夜极光", "重返未来", "三国志",
                "率土之滨", "阴阳师", "光遇", "光·遇", "我的世界", "Minecraft",
                "迷你世界", "香肠派对", "逃跑吧", "球球大作战", "贪吃蛇",
                "斗地主", "麻将", "棋牌", "三国杀", "狼人杀", "吃鸡",
                "荒野行动", "王牌战争", "暗区突围", "弹壳特攻队", "植物大战僵尸",
                "愤怒的小鸟", "Candy Crush", "Clash", "PUBG", "Roblox",
            ),
            // 视频关键词
            ApplicationInfo.CATEGORY_VIDEO to listOf(
                "抖音", "快手", "哔哩哔哩", "B站", "bilibili", "优酷", "爱奇艺",
                "腾讯视频", "芒果", "乐视", "西瓜视频", "微视", "虎牙", "斗鱼",
                "YY", "直播", "TikTok", "Netflix", "Disney", "YouTube",
                "影视", "视频", "影院", "电影", "电视剧", "剧场",
            ),
            // 音频关键词
            ApplicationInfo.CATEGORY_AUDIO to listOf(
                "网易云", "酷狗", "QQ音乐", "酷我", "蜻蜓", "喜马拉雅",
                "唱吧", "全民K歌", "汽水音乐", "Spotify", "Apple Music",
                "音乐", "电台", "FM", "听书", "有声",
            ),
        )

        for ((category, keywords) in nameCategoryMap) {
            if (keywords.any { appName.contains(it, ignoreCase = true) }) {
                return category
            }
        }

        // ── 第三层：系统 category ──
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            appInfo.category
        } catch (_: PackageManager.NameNotFoundException) {
            ApplicationInfo.CATEGORY_UNDEFINED
        }
    }

    /**
     * 将一段使用时长按小时拆分，填入对应的桶中
     *
     * 例如 09:50 开始，10:05 结束：
     * - 09-10 桶：10 分钟（09:50→10:00）
     * - 10-10 桶：5 分钟（10:00→10:05）
     */
    private fun addUsageToHourlyBuckets(
        tableData: MutableMap<String, LongArray>,
        packageName: String,
        startTime: Long,
        endTime: Long,
        startOfDay: Long,
        recurringRanges: List<ExcludedTimeRange> = emptyList()
    ) {
        val hourly = tableData.getOrPut(packageName) { LongArray(24) }

        // 预计算今天的星期几，用于周期性排除
        val todayDow = if (recurringRanges.isNotEmpty()) {
            val cal = Calendar.getInstance()
            cal.timeInMillis = startOfDay
            (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1 // Monday=1..Sunday=7
        } else 0

        var current = startTime
        while (current < endTime) {
            val hoursSinceMidnight = ((current - startOfDay) / (1000 * 60 * 60)).toInt()
            if (hoursSinceMidnight < 0 || hoursSinceMidnight >= 24) break

            val hourEnd = startOfDay + (hoursSinceMidnight.toLong() + 1) * 1000 * 60 * 60
            val segmentEnd = minOf(endTime, hourEnd)

            // 周期性排除：检查该小时是否在排除范围内
            val hourMinuteStart = hoursSinceMidnight * 60
            val hourMinuteEnd = (hoursSinceMidnight + 1) * 60
            val excludedByRecurring = recurringRanges.any { range ->
                todayDow in range.repeatDays!! &&
                    hourMinuteStart < range.endMinuteOfDay &&
                    hourMinuteEnd > range.startMinuteOfDay
            }

            if (!excludedByRecurring) {
                val duration = segmentEnd - current
                if (duration > 0) {
                    hourly[hoursSinceMidnight] += duration
                }
            }

            current = segmentEnd
        }
    }
}
