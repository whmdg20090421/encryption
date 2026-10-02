package com.whmdg.mczj.tools.encryption.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class VaultDb(
    val vaults: MutableList<VaultRecord> = mutableListOf(),
    // 单调递增的 ID 分配游标：删除保险箱后不复用旧 ID。
    // 旧数据无此字段时默认 0，首次分配时按现有 max(id)+1 自动迁移。
    var nextVaultId: Int = 0
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            prettyPrintIndent = "    "
            encodeDefaults = true
        }

        fun empty() = VaultDb()

        fun load(context: Context): VaultDb {
            val primary = VaultPaths.vaultDbFile(context)
            if (primary.exists()) {
                try {
                    return json.decodeFromString(primary.readText())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            val backup = VaultPaths.vaultDbBackupFile(context)
            if (backup != null && backup.exists()) {
                try {
                    return json.decodeFromString(backup.readText())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return empty()
        }
    }

    fun save(context: Context) {
        val text = json.encodeToString(serializer(), this)
        val primary = VaultPaths.vaultDbFile(context)
        primary.parentFile?.mkdirs()
        primary.writeText(text)

        val backup = VaultPaths.vaultDbBackupFile(context)
        if (backup != null) {
            try {
                backup.parentFile?.mkdirs()
                backup.writeText(text)
            } catch (e: Exception) {
                // 备份失败不抛
            }
        }
    }

    fun addVault(record: VaultRecord): VaultRecord {
        // 用单调递增游标分配 ID，避免删除保险箱后复用旧 ID：
        // 复用会让云端残留同步卡片（id="vault_N"）与新保险箱撞号，导致 LazyColumn 键冲突崩溃。
        // nextVaultId 初始为 0（旧数据无该字段），按现有 max(id)+1 迁移。
        val base = (vaults.maxOfOrNull { it.id } ?: 0) + 1
        val nextId = maxOf(nextVaultId + 1, base)
        nextVaultId = nextId
        val assigned = record.copy(id = nextId)
        vaults.add(assigned)
        return assigned
    }

    /**
     * 添加云端恢复的保险箱记录，保留云端稳定 ID。
     * 若该 ID 已存在则由调用方先完成冲突处理。
     */
    fun addVaultWithId(record: VaultRecord, cloudId: Int): VaultRecord {
        require(cloudId > 0) { "云端保险箱 ID 必须为正数" }
        require(vaults.none { it.id == cloudId }) { "保险箱 ID 已存在: $cloudId" }
        nextVaultId = maxOf(nextVaultId, cloudId)
        val assigned = record.copy(id = cloudId)
        vaults.add(assigned)
        return assigned
    }

    fun removeVault(id: Int) {
        vaults.removeAll { it.id == id }
    }

    fun replaceVault(newRecord: VaultRecord) {
        val index = vaults.indexOfFirst { it.id == newRecord.id }
        if (index >= 0) {
            vaults[index] = newRecord
        }
    }

    fun isNameTaken(name: String): Boolean = vaults.any { it.name == name }
}
