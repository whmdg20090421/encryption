package com.whmdg.mczj.tools.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 加密模块的持久化偏好。
 */
class EncryptionSettings(context: Context) {
    private val sp = context.getSharedPreferences("encryption_settings", Context.MODE_PRIVATE)

    var loaded by mutableStateOf(false)
        private set

    private val _confirmBeforeDecrypt = mutableStateOf(false)
    val confirmBeforeDecrypt: Boolean get() = _confirmBeforeDecrypt.value

    private val _enableTeeQuickUnlock = mutableStateOf(false)
    val enableTeeQuickUnlock: Boolean get() = _enableTeeQuickUnlock.value

    init {
        _confirmBeforeDecrypt.value = sp.getBoolean("confirmBeforeDecrypt", false)
        _enableTeeQuickUnlock.value = sp.getBoolean("enableTeeQuickUnlock", false)
        loaded = true
    }

    fun setConfirmBeforeDecrypt(value: Boolean) {
        if (value == _confirmBeforeDecrypt.value) return
        _confirmBeforeDecrypt.value = value
        sp.edit().putBoolean("confirmBeforeDecrypt", value).apply()
    }

    fun setEnableTeeQuickUnlock(value: Boolean) {
        if (value == _enableTeeQuickUnlock.value) return
        _enableTeeQuickUnlock.value = value
        sp.edit().putBoolean("enableTeeQuickUnlock", value).apply()
    }
}

