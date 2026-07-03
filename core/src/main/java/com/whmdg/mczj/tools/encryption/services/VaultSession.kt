package com.whmdg.mczj.tools.encryption.services

import android.content.Context
import com.whmdg.mczj.tools.encryption.data.NameMapping
import com.whmdg.mczj.tools.encryption.data.VaultConfig
import com.whmdg.mczj.tools.encryption.data.VaultRecord
import java.io.File

/**
 * 用户输入正确密码 → 解出 DEK 之后的"在线"保险箱会话。
 */
class VaultSession(
    val record: VaultRecord,
    val vaultDir: File,
    val config: VaultConfig,
    val dek: ByteArray
) {
    private var _names: NameMapping = NameMapping.empty()

    val nameMapping: NameMapping
        get() = _names

    fun loadNameMapping(context: Context) {
        _names = NameMapping.load(context, vaultDir)
    }

    fun saveNameMapping(context: Context) {
        _names.save(context, vaultDir)
    }

    /**
     * 销毁会话：清零 DEK。
     */
    fun dispose() {
        for (i in dek.indices) {
            dek[i] = 0
        }
    }
}
