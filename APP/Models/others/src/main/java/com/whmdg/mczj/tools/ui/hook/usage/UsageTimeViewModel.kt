package com.whmdg.mczj.tools.ui.hook.usage

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whmdg.mczj.tools.AppDataPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 使用时长页面 UI 状态
 */
data class UsageTimeUiState(
    val isLoading: Boolean = false,
    val hasPermission: Boolean = false,
    val totalScreenTime: String = "0分",
    val totalScreenTimeMillis: Long = 0L,
    val appUsageList: List<AppUsageInfo> = emptyList(),
    val hourlyTable: HourlyUsageTable? = null,
    val categoryHourlyData: CategoryHourlyData? = null,
    val mergeStrategy: MergeStrategy = MergeStrategy.PATH_B_ONLY,
    val excludedPackages: Set<String> = emptySet(),
    val excludedTimeRanges: List<ExcludedTimeRange> = emptyList(),
    val error: String? = null
)

/**
 * 使用时长 ViewModel
 *
 * 管理今日使用时长数据的加载和状态。
 * onResume 时自动刷新数据。
 * 合并策略和排除列表通过 SharedPreferences 持久化。
 */
class UsageTimeViewModel(application: Application) : AndroidViewModel(application) {

    private val usageStatsHelper = UsageStatsHelper(application)
    private val prefs = application.getSharedPreferences(AppDataPaths.PREFS_HOOK, Context.MODE_PRIVATE)
    private val packageManager: PackageManager = application.packageManager

    private val _uiState = MutableStateFlow(UsageTimeUiState())
    val uiState: StateFlow<UsageTimeUiState> = _uiState.asStateFlow()

    // 临时排除列表（编辑中，未确认）
    private var _tempExcludedPackages: MutableSet<String> = mutableSetOf()
    private var _tempExcludedTimeRanges: MutableList<ExcludedTimeRange> = mutableListOf()

    companion object {
        private const val KEY_MERGE_STRATEGY = "usage_merge_strategy"
        private const val KEY_EXCLUDED_PACKAGES = "usage_excluded_packages"
        private const val KEY_EXCLUDED_TIME_RANGES = "usage_excluded_time_ranges"
    }

    init {
        // 读取持久化的合并策略
        val savedStrategy = prefs.getString(KEY_MERGE_STRATEGY, null)
        val strategy = if (savedStrategy != null) {
            try { MergeStrategy.valueOf(savedStrategy) } catch (_: Exception) { MergeStrategy.PATH_B_ONLY }
        } else {
            MergeStrategy.PATH_B_ONLY
        }

        // 读取持久化的排除列表
        val excluded = prefs.getStringSet(KEY_EXCLUDED_PACKAGES, null)?.toSet() ?: emptySet()

        // 读取持久化的排除时间段
        val excludedRanges = ExcludedTimeRange.fromJson(
            prefs.getString(KEY_EXCLUDED_TIME_RANGES, null)
        )

        _uiState.value = _uiState.value.copy(
            mergeStrategy = strategy,
            excludedPackages = excluded,
            excludedTimeRanges = excludedRanges
        )

        checkPermissionAndLoad()
    }

    /**
     * 检查权限并加载数据
     */
    fun checkPermissionAndLoad() {
        val hasPermission = usageStatsHelper.hasUsagePermission()
        _uiState.value = _uiState.value.copy(hasPermission = hasPermission)

        if (hasPermission) {
            loadTodayUsage()
        } else {
            _uiState.value = _uiState.value.copy(
                appUsageList = emptyList(),
                totalScreenTime = "0分",
                totalScreenTimeMillis = 0L,
                error = null
            )
        }
    }

    /**
     * 加载今日使用时长数据
     */
    fun loadTodayUsage() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                // 清理过期的一次性排除时间段（结束日期的次日才清理）
                val now = System.currentTimeMillis()
                val currentRanges = _uiState.value.excludedTimeRanges
                val validRanges = currentRanges.filter { range ->
                    if (range.isRecurring) return@filter true
                    val endCal = java.util.Calendar.getInstance().apply {
                        timeInMillis = range.endMillis
                        add(java.util.Calendar.DAY_OF_YEAR, 1)
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    now < endCal.timeInMillis
                }
                if (validRanges.size < currentRanges.size) {
                    prefs.edit()
                        .putString(KEY_EXCLUDED_TIME_RANGES, ExcludedTimeRange.toJson(validRanges))
                        .apply()
                    _uiState.value = _uiState.value.copy(excludedTimeRanges = validRanges)
                }

                val strategy = _uiState.value.mergeStrategy
                val excluded = _uiState.value.excludedPackages
                val excludedRanges = _uiState.value.excludedTimeRanges
                val usageList = withContext(Dispatchers.IO) {
                    usageStatsHelper.getTodayUsage(strategy, excluded, excludedRanges)
                }

                // 刷新包名→应用名缓存
                withContext(Dispatchers.IO) {
                    usageStatsHelper.refreshAppNameCache(usageList.map { it.packageName }.toSet())
                }

                val totalMillis = usageList.sumOf { it.usageTimeMillis }
                val totalScreenTime = usageStatsHelper.formatScreenTime(totalMillis)

                // 加载每小时使用数据（固定使用路径 B）
                val hourlyTable = withContext(Dispatchers.IO) {
                    usageStatsHelper.buildHourlyTable(excluded, excludedRanges)
                }

                // 加载按类别分组的数据
                val categoryData = withContext(Dispatchers.IO) {
                    usageStatsHelper.buildCategoryHourlyData(excluded, excludedRanges)
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    appUsageList = usageList,
                    totalScreenTime = totalScreenTime,
                    totalScreenTimeMillis = totalMillis,
                    hourlyTable = hourlyTable,
                    categoryHourlyData = categoryData,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "加载失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 切换合并策略并持久化
     */
    fun setMergeStrategy(strategy: MergeStrategy) {
        prefs.edit().putString(KEY_MERGE_STRATEGY, strategy.name).apply()
        _uiState.value = _uiState.value.copy(mergeStrategy = strategy)
        loadTodayUsage()
    }

    // ── 排除应用管理 ──

    /**
     * 打开排除面板时调用，复制当前排除列表为临时列表
     */
    fun openExcludePanel() {
        _tempExcludedPackages = _uiState.value.excludedPackages.toMutableSet()
        _tempExcludedTimeRanges = _uiState.value.excludedTimeRanges.toMutableList()
    }

    /**
     * 获取临时排除列表（供 UI 显示）
     */
    fun getTempExcludedPackages(): Set<String> = _tempExcludedPackages.toSet()

    /**
     * 手动添加排除包名（输入框确认时调用）
     * @return 错误信息，成功返回 null
     */
    fun addExcludedPackage(packageName: String): String? {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return "包名不能为空"
        if (_tempExcludedPackages.contains(pkg)) return "该包名已存在"
        if (!isPackageExists(pkg)) return "该包名不存在，请确认后再试"
        _tempExcludedPackages.add(pkg)
        return null
    }

    /**
     * 从临时列表移除排除包名
     */
    fun removeExcludedPackage(packageName: String) {
        _tempExcludedPackages.remove(packageName)
    }

    /**
     * 切换排除状态（选择面板用）
     */
    fun toggleExcludedPackage(packageName: String) {
        if (_tempExcludedPackages.contains(packageName)) {
            _tempExcludedPackages.remove(packageName)
        } else {
            _tempExcludedPackages.add(packageName)
        }
    }

    /**
     * 保存排除列表（主面板确认时调用）
     */
    fun saveExcludedPackages() {
        val newSet = _tempExcludedPackages.toSet()
        val newRanges = _tempExcludedTimeRanges.toList()
        prefs.edit()
            .putStringSet(KEY_EXCLUDED_PACKAGES, newSet)
            .putString(KEY_EXCLUDED_TIME_RANGES, ExcludedTimeRange.toJson(newRanges))
            .apply()
        _uiState.value = _uiState.value.copy(excludedPackages = newSet, excludedTimeRanges = newRanges)
        loadTodayUsage()
    }

    /**
     * 取消排除变更（主面板取消时调用）
     */
    fun cancelExcludeChanges() {
        _tempExcludedPackages.clear()
        _tempExcludedTimeRanges.clear()
    }

    /**
     * 从选择面板确认时调用，将选择结果同步到临时列表
     */
    fun applySelectionToTemp(selectedPackages: Set<String>) {
        _tempExcludedPackages.clear()
        _tempExcludedPackages.addAll(selectedPackages)
    }

    // ── 排除时间段管理 ──

    /**
     * 获取临时排除时间段列表（供 UI 显示）
     */
    fun getTempExcludedTimeRanges(): List<ExcludedTimeRange> = _tempExcludedTimeRanges.toList()

    /**
     * 添加排除时间段到临时列表
     */
    fun addExcludedTimeRange(range: ExcludedTimeRange) {
        _tempExcludedTimeRanges.add(range)
    }

    /**
     * 从临时列表移除排除时间段
     */
    fun removeExcludedTimeRange(id: Long) {
        _tempExcludedTimeRanges.removeAll { it.id == id }
    }

    /**
     * 检查包名是否存在于设备
     */
    fun isPackageExists(packageName: String): Boolean {
        return try {
            packageManager.getApplicationInfo(packageName.trim(), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 获取今日使用的应用列表（用于选择面板）
     * 排除系统应用，按使用时长降序
     */
    fun getTodayAppsForSelection(): List<AppUsageInfo> {
        return _uiState.value.appUsageList
    }

    // ── 其他 ──

    /**
     * 获取各路径的原始时间（用于设置弹窗显示）
     */
    suspend fun getPathTimes(): PathResult = withContext(Dispatchers.IO) {
        usageStatsHelper.getTodayPathTimes()
    }

    /**
     * 构建每小时使用时长表格并生成 XLSX 分享 Intent
     *
     * @return 分享 Intent，无权限或无数据时返回 null
     */
    suspend fun buildAndShareXlsx(): Intent? {
        val excluded = _uiState.value.excludedPackages
        val excludedRanges = _uiState.value.excludedTimeRanges
        val table = withContext(Dispatchers.IO) {
            usageStatsHelper.buildHourlyTable(excluded, excludedRanges)
        } ?: return null

        // 获取 Path A 原始数据（今日 00:00 ~ 现在）
        val pathAStats = withContext(Dispatchers.IO) {
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            usageStatsHelper.getPathARawStats(calendar.timeInMillis, System.currentTimeMillis())
        }

        val file = withContext(Dispatchers.IO) {
            HourlyUsageXlsxGenerator.generate(getApplication(), table, pathAStats)
        }

        return HourlyUsageXlsxGenerator.createShareIntent(getApplication(), file)
    }

    /**
     * 页面恢复时调用，自动刷新数据
     */
    fun onResume() {
        checkPermissionAndLoad()
    }
}
