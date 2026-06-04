package com.whmdg.mczj.tools.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whmdg.mczj.tools.security.AndroidPermissionLevel
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.AppDataPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PermissionStatus(val name: String, val isGranted: Boolean)

class PermissionGuideViewModel : ViewModel() {

    enum class Step {
        WELCOME,
        BASIC_PERMISSIONS,
        PERMISSION_LEVEL
    }

    data class UiState(
        val currentStep: Step = Step.WELCOME,
        val hasStoragePermission: Boolean = false,
        val hasOverlayPermission: Boolean = false,
        val hasBatteryOptimizationExemption: Boolean = false,
        val hasLocationPermission: Boolean = false,
        val allBasicPermissionsGranted: Boolean = false,
        val selectedPermissionLevel: AndroidPermissionLevel? = null,
        val isCompleted: Boolean = false,
        val validationError: String? = null,
        val showStatusPage: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun checkPermissions(context: Context) {
        val hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

        val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

        val hasBatteryOptimizationExemption = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }

        val hasLocationPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED

        _uiState.update {
            it.copy(
                hasStoragePermission = hasStoragePermission,
                hasOverlayPermission = hasOverlayPermission,
                hasBatteryOptimizationExemption = hasBatteryOptimizationExemption,
                hasLocationPermission = hasLocationPermission,
                allBasicPermissionsGranted = hasStoragePermission && hasOverlayPermission &&
                        hasBatteryOptimizationExemption && hasLocationPermission
            )
        }
    }

    fun setCurrentStep(step: Step) {
        _uiState.update { it.copy(currentStep = step) }
    }

    fun selectPermissionLevel(level: AndroidPermissionLevel) {
        _uiState.update { it.copy(selectedPermissionLevel = level, validationError = null) }
    }

    fun savePermissionLevel(context: Context) {
        val level = _uiState.value.selectedPermissionLevel ?: return

        // 校验权限是否可用
        if (!validatePermissionLevel(context, level)) {
            _uiState.update {
                it.copy(validationError = getValidationErrorMessage(level))
            }
            return
        }

        viewModelScope.launch {
            val sp = context.getSharedPreferences(AppDataPaths.PREFS_LEGACY_SPECIAL_PERMISSIONS, Context.MODE_PRIVATE)
            sp.edit()
                .putString("target_permission_level", level.name)
                .putBoolean("has_completed_guide", true)
                .apply()
            _uiState.update { it.copy(isCompleted = true) }
        }
    }

    fun clearValidationError() {
        _uiState.update { it.copy(validationError = null) }
    }

    fun enterStatusPage() {
        _uiState.update { it.copy(showStatusPage = true) }
    }

    fun resetGuide(context: Context) {
        val sp = context.getSharedPreferences(AppDataPaths.PREFS_LEGACY_SPECIAL_PERMISSIONS, Context.MODE_PRIVATE)
        sp.edit().remove("has_completed_guide").apply()
        _uiState.update {
            UiState() // 重置为初始状态
        }
    }

    fun updateLocationPermission(granted: Boolean) {
        _uiState.update { state ->
            state.copy(hasLocationPermission = granted).let { newState ->
                newState.copy(
                    allBasicPermissionsGranted = newState.hasStoragePermission &&
                            newState.hasOverlayPermission &&
                            newState.hasBatteryOptimizationExemption &&
                            newState.hasLocationPermission
                )
            }
        }
    }

    companion object {
        /** 检查引导是否已完成 */
        fun isGuideCompleted(context: Context): Boolean {
            val sp = context.getSharedPreferences(AppDataPaths.PREFS_LEGACY_SPECIAL_PERMISSIONS, Context.MODE_PRIVATE)
            return sp.getBoolean("has_completed_guide", false)
        }

        /** 获取当前已保存的权限级别 */
        fun getSavedLevel(context: Context): AndroidPermissionLevel? {
            val sp = context.getSharedPreferences(AppDataPaths.PREFS_LEGACY_SPECIAL_PERMISSIONS, Context.MODE_PRIVATE)
            val levelStr = sp.getString("target_permission_level", null) ?: return null
            return try {
                AndroidPermissionLevel.valueOf(levelStr)
            } catch (_: Exception) {
                null
            }
        }

        /** 校验指定权限级别是否可用 */
        fun validatePermissionLevel(context: Context, level: AndroidPermissionLevel): Boolean {
            return when (level) {
                AndroidPermissionLevel.STANDARD -> true
                AndroidPermissionLevel.ACCESSIBILITY -> SpecialPermissionVerifier.isAccessibilityEnabled(context)
                AndroidPermissionLevel.ADB -> SpecialPermissionVerifier.isShizukuAuthorized(context)
                AndroidPermissionLevel.DEBUGGER -> SpecialPermissionVerifier.isAdbEnabled(context)
                AndroidPermissionLevel.ADMIN -> SpecialPermissionVerifier.isDeviceAdminActive(context)
                AndroidPermissionLevel.ROOT -> SpecialPermissionVerifier.isRootAvailable()
            }
        }

        /** 获取校验失败的错误信息 */
        fun getValidationErrorMessage(level: AndroidPermissionLevel): String {
            return when (level) {
                AndroidPermissionLevel.STANDARD -> ""
                AndroidPermissionLevel.ACCESSIBILITY -> "无障碍服务未启用。请前往系统设置 → 无障碍 中启用本应用的无障碍服务。"
                AndroidPermissionLevel.ADB -> "Shizuku 未授权。请先安装并启动 Shizuku，然后在弹出的授权对话框中允许本应用使用。"
                AndroidPermissionLevel.DEBUGGER -> "ADB 权限不可用。请通过 USB 调试授予 WRITE_SECURE_SETTINGS 权限。"
                AndroidPermissionLevel.ADMIN -> "设备管理器未激活。请在特殊权限中激活设备管理器。"
                AndroidPermissionLevel.ROOT -> "Root 权限不可用。请确保已通过 Root 管理器（如 Magisk）授予本应用 su 授权。"
            }
        }

        /** 获取指定级别的所有权限状态 */
        fun getPermissionStatusForLevel(context: Context, level: AndroidPermissionLevel): List<PermissionStatus> {
            val baseStatuses = listOf(
                PermissionStatus("存储权限", isGranted = checkStoragePermission(context)),
                PermissionStatus("悬浮窗权限", isGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true),
                PermissionStatus("电池优化豁免", isGranted = checkBatteryOptimization(context)),
                PermissionStatus("位置权限", isGranted = checkLocationPermission(context))
            )
            return when (level) {
                AndroidPermissionLevel.STANDARD -> baseStatuses
                AndroidPermissionLevel.ACCESSIBILITY -> baseStatuses + PermissionStatus("无障碍服务", isGranted = SpecialPermissionVerifier.isAccessibilityEnabled(context))
                AndroidPermissionLevel.ADB -> baseStatuses + PermissionStatus("Shizuku 权限", isGranted = SpecialPermissionVerifier.isShizukuAuthorized(context))
                AndroidPermissionLevel.DEBUGGER -> baseStatuses + PermissionStatus("ADB 权限", isGranted = SpecialPermissionVerifier.isAdbEnabled(context))
                AndroidPermissionLevel.ADMIN -> baseStatuses + PermissionStatus("设备管理器", isGranted = SpecialPermissionVerifier.isDeviceAdminActive(context))
                AndroidPermissionLevel.ROOT -> baseStatuses + PermissionStatus("Root 权限", isGranted = SpecialPermissionVerifier.isRootAvailable())
            }
        }

        private fun checkStoragePermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        }

        private fun checkBatteryOptimization(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }
        }

        private fun checkLocationPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
}
