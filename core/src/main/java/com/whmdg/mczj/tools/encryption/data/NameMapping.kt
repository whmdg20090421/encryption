package com.whmdg.mczj.tools.encryption.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 长文件名哈希 → 原 hex 字符串 的映射。
 */
class NameMapping(val entries: MutableMap<String, String> = mutableMapOf()) {

    companion object {
        private val json = Json {
            prettyPrint = true
            prettyPrintIndent = "    "
            ignoreUnknownKeys = true
        }

        fun empty() = NameMapping()

        fun load(context: Context, vaultDir: File): NameMapping {
            val inVault = File(vaultDir, "name_mappings.json")
            if (inVault.exists()) {
                try {
                    FileInputStream(inVault).use {
                        return NameMapping(json.decodeFromStream(it))
                    }
                } catch (e: Exception) {}
            }
            val priv = VaultPaths.appPrivateBackupDir(context)
            val h = VaultPaths.pathHash(vaultDir.absolutePath)
            val privFile = File(priv, "namemap_$h.json")
            if (privFile.exists()) {
                try {
                    FileInputStream(privFile).use {
                        return NameMapping(json.decodeFromStream(it))
                    }
                } catch (e: Exception) {}
            }
            return empty()
        }
    }

    fun save(context: Context, vaultDir: File) {
        val inVault = File(vaultDir, "name_mappings.json")
        try {
            inVault.parentFile?.mkdirs()
            FileOutputStream(inVault).use {
                json.encodeToStream(entries, it)
            }
        } catch (e: Exception) {}

        val priv = VaultPaths.appPrivateBackupDir(context)
        val h = VaultPaths.pathHash(vaultDir.absolutePath)
        val privFile = File(priv, "namemap_$h.json")
        try {
            FileOutputStream(privFile).use {
                json.encodeToStream(entries, it)
            }
        } catch (e: Exception) {}
    }

    fun set(hash: String, value: String) {
        entries[hash] = value
    }

    fun get(hash: String): String? = entries[hash]
}
