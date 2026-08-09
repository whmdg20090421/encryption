package com.whmdg.mczj.tools.ui.hook.usage

import android.app.Application
import android.content.Context
import android.content.Intent
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
    val totalScreenTime: String = "0h 0m",
    val totalScreenTimeMillis: Long = 0L,
    val appUsageList: List<AppUsageInfo> = emptyList(),
    val hourlyTable: HourlyUsageTable? = null,
    val mergeStrategy: MergeStrategy = MergeStrategy.MERGED_MAX,
    val error: String? = null
)

/**
 * 使用时长 ViewModel
 *
 * 管理今日使用时长数据的加载和状态。
 * onResume 时自动刷新数据。
 * 合并策略通过 SharedPreferences 持久化。
 */
class UsageTimeViewModel(application: Application) : AndroidViewModel(application) {

    private val usageStatsHelper = UsageStatsHelper(application)
    private val prefs = application.getSharedPreferences(AppDataPaths.PREFS_HOOK, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(UsageTimeUiState())
    val uiState: StateFlow<UsageTimeUiState> = _uiState.asStateFlow()

    companion object {
        private const val KEY_MERGE_STRATEGY = "usage_merge_strategy"
    }

    init {
        // 读取持久化的合并策略
        val savedStrategy = prefs.getString(KEY_MERGE_STRATEGY, null)
        val strategy = if (savedStrategy != null) {
            try { MergeStrategy.valueOf(savedStrategy) } catch (_: Exception) { MergeStrategy.PATH_B_ONLY }
        } else {
            MergeStrategy.PATH_B_ONLY
        }
        _uiState.value = _uiState.value.copy(mergeStrategy = strategy)

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
                totalScreenTime = "0h 0m",
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
                val strategy = _uiState.value.mergeStrategy
                val usageList = withContext(Dispatchers.IO) {
                    usageStatsHelper.getTodayUsage(strategy)
                }

                val totalMillis = usageList.sumOf { it.usageTimeMillis }
                val totalScreenTime = usageStatsHelper.formatScreenTime(totalMillis)

                // 加载每小时使用数据（固定使用路径 B）
                val hourlyTable = withContext(Dispatchers.IO) {
                    usageStatsHelper.buildHourlyTable()
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    appUsageList = usageList,
                    totalScreenTime = totalScreenTime,
                    totalScreenTimeMillis = totalMillis,
                    hourlyTable = hourlyTable,
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
        val table = withContext(Dispatchers.IO) {
            usageStatsHelper.buildHourlyTable()
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
