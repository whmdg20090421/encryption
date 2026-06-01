package com.whmdg.mczj.tools.encryption.data

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import java.io.File
import java.security.MessageDigest

/**
 * 集中管理加密模块用到的各种目录与文件路径。
 */
object VaultPaths {

    /**
     * 返回某种存储位置的根目录。
     */
    fun rootFor(context: Context, loc: StorageLocation): File {
        return when (loc) {
            StorageLocation.INTERNAL -> AppDataPaths.encryption(context)
            StorageLocation.EXTERNAL -> {
                context.getExternalFilesDir(null) ?: throw Exception("外部应用专属目录不可用，请改用内部目录")
            }
        }
    }

    /**
     * 给定 location + relativePath 拼出 vault 绝对目录。
     */
    fun resolveVault(context: Context, location: StorageLocation, relativePath: String): File {
        val file = File(relativePath)
        if (file.isAbsolute) {
            if (!file.exists()) {
                file.mkdirs()
            }
            return file
        }
        val root = rootFor(context, location)
        return File(root, relativePath)
    }

    /**
     * 内部应用私有备份目录（vault_db 备份 / 配置备份 / 名称映射备份）。
     */
    fun appPrivateBackupDir(context: Context): File {
        val dir = File(AppDataPaths.encryption(context), ".vault_private_backup")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 外部应用私有备份目录（可选；外部存储不可用时返回 null）。
     */
    fun externalBackupDir(context: Context): File? {
        val root = context.getExternalFilesDir(null) ?: return null
        val dir = File(root, ".vault_external_backup")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 全局 vault_db.json（内部）
     */
    fun vaultDbFile(context: Context): File {
        return File(AppDataPaths.encryption(context), "vault_db.json")
    }

    /**
     * 全局 vault_db.json 的外部备份（可能为 null）
     */
    fun vaultDbBackupFile(context: Context): File? {
        val dir = externalBackupDir(context) ?: return null
        return File(dir, "vault_db.json")
    }

    /**
     * 由 vault 目录路径派生的"私有备份文件后缀哈希"，与 Python 一致。
     */
    fun pathHash(absPath: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(absPath.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }
}
