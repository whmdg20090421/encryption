package com.whmdg.mczj.tools.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 主题模块的持久化偏好。
 */
class ThemeSettings(context: Context) {
    private val sp = context.getSharedPreferences("theme_settings", Context.MODE_PRIVATE)

    var loaded by mutableStateOf(false)
        private set

    private val _enableGlowEffect = mutableStateOf(true)
    val enableGlowEffect: Boolean get() = _enableGlowEffect.value

    init {
        _enableGlowEffect.value = sp.getBoolean("enableGlowEffect", true)
        loaded = true
    }

    fun setEnableGlowEffect(value: Boolean) {
        if (value == _enableGlowEffect.value) return
        _enableGlowEffect.value = value
        sp.edit().putBoolean("enableGlowEffect", value).apply()
    }
}
