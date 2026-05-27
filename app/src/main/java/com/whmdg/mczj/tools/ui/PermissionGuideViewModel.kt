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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        val isCompleted: Boolean = false
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
        _uiState.update { it.copy(selectedPermissionLevel = level) }
    }

    fun savePermissionLevel(context: Context) {
        val level = _uiState.value.selectedPermissionLevel ?: return
        viewModelScope.launch {
            val sp = context.getSharedPreferences("special_permissions", Context.MODE_PRIVATE)
            sp.edit().putString("target_permission_level", level.name).apply()
            _uiState.update { it.copy(isCompleted = true) }
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
}
