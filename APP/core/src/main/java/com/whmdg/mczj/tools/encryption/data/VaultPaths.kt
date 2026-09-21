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

    /**
     * 清除某个保险箱在应用私有目录中派生的全部残留产物。
     *
     * 这些产物以 vault 目录路径哈希或保险箱名/ID 派生，不属于保险箱目录本身，
     * 因此删除保险箱时必须一并清理，否则要等到下次创建同名保险箱才会被覆盖。
     * 包含：
     *  - `.vault_private_backup/vault_config_<pathHash>.json`
     *  - `.vault_private_backup/namemap_<pathHash>.json`
     *  - `云盘/db元数据/<vaultName>_meta.json`
     *  - `云盘/锁文件/vault_<vaultId>.lock`
     *
     * 注意：云盘同步索引数据库目录（`云盘同步/<vaultName>/`）不在此处理，
     * 因为 local-only / cloud-only 删除分支有各自的 DB 保留语义，由调用方自行清理。
     *
     * @param vaultDir 保险箱目录（用于计算 pathHash）
     * @param vaultName 保险箱名称（用于定位云盘 db 元数据）
     * @param vaultId 保险箱 ID（用于定位锁文件）
     */
    fun purgeVaultArtifacts(
        context: Context,
        vaultDir: File,
        vaultName: String,
        vaultId: Int
    ) {
        val hash = pathHash(vaultDir.absolutePath)

        val priv = appPrivateBackupDir(context)
        deleteQuietly(File(priv, "vault_config_$hash.json"))
        deleteQuietly(File(priv, "namemap_$hash.json"))

        deleteQuietly(File(AppDataPaths.cloudDbMeta(context), "${vaultName}_meta.json"))
        deleteQuietly(File(AppDataPaths.cloudSyncLocks(context), "vault_$vaultId.lock"))
    }

    private fun deleteQuietly(file: File) {
        try {
            if (file.isDirectory) file.deleteRecursively() else if (file.exists()) file.delete()
        } catch (_: Exception) {
        }
    }
}
