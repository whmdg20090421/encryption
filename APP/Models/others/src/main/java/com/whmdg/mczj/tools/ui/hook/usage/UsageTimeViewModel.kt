package com.whmdg.mczj.tools.ui.hook.usage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val error: String? = null
)

/**
 * 使用时长 ViewModel
 *
 * 管理今日使用时长数据的加载和状态。
 * onResume 时自动刷新数据。
 */
class UsageTimeViewModel(application: Application) : AndroidViewModel(application) {

    private val usageStatsHelper = UsageStatsHelper(application)

    private val _uiState = MutableStateFlow(UsageTimeUiState())
    val uiState: StateFlow<UsageTimeUiState> = _uiState.asStateFlow()

    init {
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
                val usageList = withContext(Dispatchers.IO) {
                    usageStatsHelper.getTodayUsage()
                }

                val totalMillis = usageList.sumOf { it.usageTimeMillis }
                val totalScreenTime = usageStatsHelper.formatScreenTime(totalMillis)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    appUsageList = usageList,
                    totalScreenTime = totalScreenTime,
                    totalScreenTimeMillis = totalMillis,
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
     * 页面恢复时调用，自动刷新数据
     */
    fun onResume() {
        checkPermissionAndLoad()
    }
}
