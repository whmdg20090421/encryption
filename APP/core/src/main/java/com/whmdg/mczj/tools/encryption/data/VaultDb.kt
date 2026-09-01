package com.whmdg.mczj.tools.encryption.data

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class VaultDb(
    val vaults: MutableList<VaultRecord> = mutableListOf(),
    @SerialName("name_mappings") val nameMappings: MutableMap<String, String> = mutableMapOf()
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
        val assigned = record.copy(id = vaults.size + 1)
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

    fun addNameMapping(hash: String, hexValue: String) {
        nameMappings[hash] = hexValue
    }
}
