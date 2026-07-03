package com.whmdg.mczj.tools.ui.encryption

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.whmdg.mczj.tools.AppDataPaths

/**
 * 加密模块的持久化偏好。
 */
class EncryptionSettings(context: Context) {
    private val sp = context.getSharedPreferences(AppDataPaths.PREFS_ENCRYPTION, Context.MODE_PRIVATE)

    var loaded by mutableStateOf(false)
        private set

    private val _confirmBeforeDecrypt = mutableStateOf(false)
    val confirmBeforeDecrypt: Boolean get() = _confirmBeforeDecrypt.value

    private val _enableTeeQuickUnlock = mutableStateOf(false)
    val enableTeeQuickUnlock: Boolean get() = _enableTeeQuickUnlock.value

    private val _compressUseAes = mutableStateOf(false)
    val compressUseAes: Boolean get() = _compressUseAes.value

    init {
        _confirmBeforeDecrypt.value = sp.getBoolean("confirmBeforeDecrypt", false)
        _enableTeeQuickUnlock.value = sp.getBoolean("enableTeeQuickUnlock", false)
        _compressUseAes.value = sp.getBoolean("compress_use_aes", false)
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

    fun setCompressUseAes(value: Boolean) {
        if (value == _compressUseAes.value) return
        _compressUseAes.value = value
        sp.edit().putBoolean("compress_use_aes", value).apply()
    }
}

