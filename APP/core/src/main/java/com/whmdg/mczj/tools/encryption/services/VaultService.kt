package com.whmdg.mczj.tools.encryption.services

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.compose.runtime.mutableStateListOf
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.auth.Feature
import com.whmdg.mczj.tools.auth.SecurityEnforcer
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.encryption.core.AesGcm256
import com.whmdg.mczj.tools.encryption.core.HexCodec
import com.whmdg.mczj.tools.encryption.core.KeyDerivation
import com.whmdg.mczj.tools.encryption.core.SecureRandom
import com.whmdg.mczj.tools.encryption.data.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 全局保险箱清单服务。
 */
class VaultService(private val context: Context) {
    private var _db = VaultDb.empty()
    private var _loaded = false

    val loaded: Boolean get() = _loaded
    val vaults = mutableStateListOf<VaultRecord>()

    fun load() {
        _db = VaultDb.load(context)
        vaults.clear()
        vaults.addAll(_db.vaults)
        _loaded = true
    }

    fun isNameTaken(name: String) = _db.isNameTaken(name)

    fun createVault(
        name: String,
        location: StorageLocation,
        relativePath: String,
        password: String,
        encryptFilename: Boolean,
        encryptMetadata: Boolean,
        customEncryption: Boolean,
        kdfType: KdfType,
        argonParams: Argon2Params,
        algorithm: String = "AES-256-GCM"
    ): VaultRecord {
        // 业务层权限检查（第二道防线）
        if (!SecurityEnforcer.checkOrDie(context, Feature.ENCRYPTION_VAULT, "VaultService.createVault")) {
            throw SecurityException("权限不足：无法创建保险箱")
        }

        if (_db.isNameTaken(name)) {
            throw IllegalArgumentException("保险箱名称已存在: $name")
        }
        val dir = VaultPaths.resolveVault(context, location, relativePath)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val salt = SecureRandom.bytes(16)
        val dek = SecureRandom.bytes(32)
        val kek = KeyDerivation.derive(password, salt, kdfType, argonParams)
        
        val enc = AesGcm256.encrypt(kek, dek)
        
        // 清零 kek
        kek.fill(0)

        val cfg = VaultConfig(
            version = 2,
            kdfType = kdfType,
            salt = HexCodec.encode(salt),
            argonParams = argonParams,
            kekIv = HexCodec.encode(enc.iv),
            encDek = HexCodec.encode(enc.ciphertext),
            configFlags = ConfigFlags(
                encryptFilename = encryptFilename,
                encryptMetadata = encryptMetadata,
                customEncryption = customEncryption
            ),
            algorithm = algorithm
        )
        cfg.saveWithBackup(context, dir)

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")

        val rec = VaultRecord(
            id = 0,
            name = name,
            location = location,
            relativePath = relativePath,
            encryptFilename = encryptFilename,
            encryptMetadata = encryptMetadata,
            customEncryption = customEncryption,
            algorithm = algorithm,
            createdAt = sdf.format(Date())
        )
        val assigned = _db.addVault(rec)
        _db.save(context)
        
        vaults.clear()
        vaults.addAll(_db.vaults)
        
        return assigned
    }

    fun open(id: Int, password: String): VaultSession {
        // 业务层权限检查（第二道防线）
        if (!SecurityEnforcer.checkOrDie(context, Feature.ENCRYPTION_VAULT, "VaultService.open")) {
            throw SecurityException("权限不足：无法打开保险箱")
        }

        val rec = _db.vaults.find { it.id == id } ?: throw IllegalArgumentException("保险箱不存在: id=$id")
        val dir = VaultPaths.resolveVault(context, rec.location, rec.relativePath)
        val cfg = VaultConfig.readWithFallback(context, dir)

        val saltBytes = HexCodec.decode(cfg.salt)
        val kekIvBytes = HexCodec.decode(cfg.kekIv)
        val encDekBytes = HexCodec.decode(cfg.encDek)

        var dek: ByteArray
        try {
            val kek = KeyDerivation.derive(password, saltBytes, cfg.kdfType, cfg.argonParams)
            try {
                dek = AesGcm256.decrypt(kek, kekIvBytes, encDekBytes)
            } finally {
                kek.fill(0)
            }
        } catch (e: Exception) {
            try {
                val fallback = if (cfg.kdfType == KdfType.ARGON2ID) KdfType.PBKDF2_SHA256 else KdfType.ARGON2ID
                val kek2 = KeyDerivation.derive(password, saltBytes, fallback, cfg.argonParams)
                try {
                    dek = AesGcm256.decrypt(kek2, kekIvBytes, encDekBytes)
                } finally {
                    kek2.fill(0)
                }
            } catch (_: Exception) {
                throw Exception("密码错误或保险箱数据损坏")
            }
        }

        // 记录最后打开时间
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date())
        _db.replaceVault(rec.copy(lastOpenedAt = now))
        _db.save(context)
        vaults.clear()
        vaults.addAll(_db.vaults)

        return VaultSession(
            record = rec.copy(lastOpenedAt = now),
            vaultDir = dir,
            config = cfg,
            dek = dek
        )
    }

    /** 获取指定 ID 的保险箱记录 */
    fun getVault(id: Int): VaultRecord? = _db.vaults.find { it.id == id }

    /**
     * 恢复云端保险箱元数据，保留云端稳定 ID。
     * 返回现有记录表示本地已存在且被复用；ID 冲突时抛出异常交由 UI 处理。
     */
    fun restoreCloudVault(record: VaultRecord): VaultRecord {
        require(record.id > 0) { "云端保险箱 ID 必须为正数" }
        val byId = _db.vaults.find { it.id == record.id }
        if (byId != null) {
            if (byId.name != record.name || byId.relativePath != record.relativePath) {
                throw IllegalStateException("本地保险箱 ID ${record.id} 与云端记录不一致")
            }
            return byId
        }
        val byName = _db.vaults.find { it.name == record.name }
        if (byName != null && byName.id != record.id) {
            throw IllegalStateException("本地已有同名保险箱「${record.name}」")
        }
        val restored = _db.addVaultWithId(record, record.id)
        _db.save(context)
        vaults.clear()
        vaults.addAll(_db.vaults)
        return restored
    }

    /** 用云端记录覆盖占用相同 ID 的本地记录，保留本地目录内容。 */
    fun overwriteVaultId(localId: Int, cloudRecord: VaultRecord): VaultRecord {
        require(cloudRecord.id > 0) { "云端保险箱 ID 必须为正数" }
        val index = _db.vaults.indexOfFirst { it.id == localId }
        require(index >= 0) { "本地保险箱不存在: id=$localId" }
        val existingCloud = _db.vaults.indexOfFirst { it.id == cloudRecord.id && it.id != localId }
        require(existingCloud < 0) { "云端保险箱 ID 已被其他本地记录占用" }
        val replaced = cloudRecord.copy(relativePath = _db.vaults[index].relativePath)
        _db.vaults[index] = replaced
        _db.save(context)
        vaults.clear()
        vaults.addAll(_db.vaults)
        return replaced
    }

    /**
     * 将冲突的本地保险箱作为独立副本保留：重命名目录并释放原云端 ID。
     * 释放后恢复流程才能使用该稳定云端 ID 创建对应的保险箱记录。
     */
    fun renameVaultForConflict(localId: Int, newFolderName: String): VaultRecord {
        val index = _db.vaults.indexOfFirst { it.id == localId }
        require(index >= 0) { "本地保险箱不存在: id=$localId" }
        val current = _db.vaults[index]
        val dir = VaultPaths.resolveVault(context, current.location, current.relativePath)
        require(newFolderName.isNotBlank()) { "文件夹名称不能为空" }
        val renamed = File(dir.parentFile ?: dir, newFolderName.trim())
        require(!renamed.exists()) { "本地冲突目录已存在: ${renamed.name}" }
        if (dir.exists() && !dir.renameTo(renamed)) throw IllegalStateException("本地目录重命名失败")
        val replacementId = (_db.vaults.maxOfOrNull { it.id } ?: 0) + 1
        val updated = current.copy(id = replacementId, relativePath = renamed.absolutePath)
        _db.vaults[index] = updated
        _db.save(context)
        vaults.clear()
        vaults.addAll(_db.vaults)
        return updated
    }

    /** 标记保险箱内容已修改（导入/重命名/删除文件后调用） */
    fun markModified(id: Int) {
        val rec = _db.vaults.find { it.id == id } ?: return
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date())
        _db.replaceVault(rec.copy(lastModifiedAt = now))
        _db.save(context)
        vaults.clear()
        vaults.addAll(_db.vaults)
    }

    fun removeVault(id: Int, deleteFiles: Boolean) {
        // 业务层权限检查（第二道防线）
        if (!SecurityEnforcer.checkOrDie(context, Feature.ENCRYPTION_VAULT, "VaultService.removeVault")) {
            throw SecurityException("权限不足：无法删除保险箱")
        }

        val rec = _db.vaults.find { it.id == id } ?: return
        if (deleteFiles) {
            try {
                val dir = VaultPaths.resolveVault(context, rec.location, rec.relativePath)
                SpecialPermissionVerifier.safeDelete(dir)
            } catch (e: Exception) {}
        }
        _db.removeVault(id)
        _db.save(context)
        vaults.clear()
        vaults.addAll(_db.vaults)
    }

    fun importVaultWithPassword(
        name: String,
        vaultPath: String,
        password: String
    ): VaultRecord {
        if (_db.isNameTaken(name)) {
            throw IllegalArgumentException("保险箱名称已存在: $name")
        }
        val dir = File(vaultPath)
        if (!dir.exists()) {
            throw IllegalArgumentException("目录不存在: $vaultPath")
        }
        val cfg = VaultConfig.readWithFallback(context, dir)

        val saltBytes = HexCodec.decode(cfg.salt)
        val kekIvBytes = HexCodec.decode(cfg.kekIv)
        val encDekBytes = HexCodec.decode(cfg.encDek)

        var dek: ByteArray
        try {
            val kek = KeyDerivation.derive(password, saltBytes, cfg.kdfType, cfg.argonParams)
            try {
                dek = AesGcm256.decrypt(kek, kekIvBytes, encDekBytes)
            } finally {
                kek.fill(0)
            }
        } catch (e: Exception) {
            try {
                val fallback = if (cfg.kdfType == KdfType.ARGON2ID) KdfType.PBKDF2_SHA256 else KdfType.ARGON2ID
                val kek2 = KeyDerivation.derive(password, saltBytes, fallback, cfg.argonParams)
                try {
                    dek = AesGcm256.decrypt(kek2, kekIvBytes, encDekBytes)
                } finally {
                    kek2.fill(0)
                }
            } catch (_: Exception) {
                throw Exception("密码错误或保险箱数据损坏")
            }
        }
        dek.fill(0) // 清零验证后的 DEK

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")

        val rec = VaultRecord(
            id = 0,
            name = name,
            location = StorageLocation.EXTERNAL,
            relativePath = vaultPath,
            encryptFilename = cfg.configFlags.encryptFilename,
            encryptMetadata = cfg.configFlags.encryptMetadata,
            customEncryption = cfg.configFlags.customEncryption,
            algorithm = cfg.algorithm,
            createdAt = sdf.format(Date())
        )
        val assigned = _db.addVault(rec)
        _db.save(context)

        vaults.clear()
        vaults.addAll(_db.vaults)

        return assigned
    }

    /**
     * 通过 SAF 导入保险箱（无需所有文件访问权限）。
     * 用 ContentResolver 从 treeUri 读取 vault_config.json，验证密码后注册。
     */
    fun importVaultWithPasswordSaf(
        name: String,
        treeUri: Uri,
        password: String
    ): VaultRecord {
        if (_db.isNameTaken(name)) {
            throw IllegalArgumentException("保险箱名称已存在: $name")
        }

        // 构建 vault_config.json 的 child URI
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val configDocId = "$treeDocId/vault_config.json"
        val configUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, configDocId)

        // 通过 ContentResolver 读取 JSON
        val jsonStr = try {
            context.contentResolver.openInputStream(configUri)?.use { it.bufferedReader().readText() }
                ?: throw Exception("无法读取 vault_config.json")
        } catch (e: Exception) {
            throw Exception("读取 vault_config.json 失败: ${e.message}")
        }

        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val cfg = try {
            json.decodeFromString<VaultConfig>(jsonStr)
        } catch (e: Exception) {
            throw Exception("vault_config.json 格式错误: ${e.message}")
        }

        // HMAC 完整性校验
        if (cfg.integrityHmac != null && cfg.computeHmac() != cfg.integrityHmac) {
            throw Exception("vault_config.json 完整性校验失败（HMAC 不匹配）")
        }

        // 密码验证：尝试解密 DEK
        val saltBytes = HexCodec.decode(cfg.salt)
        val kekIvBytes = HexCodec.decode(cfg.kekIv)
        val encDekBytes = HexCodec.decode(cfg.encDek)

        try {
            val kek = KeyDerivation.derive(password, saltBytes, cfg.kdfType, cfg.argonParams)
            try {
                val dek = AesGcm256.decrypt(kek, kekIvBytes, encDekBytes)
                dek.fill(0)
            } finally {
                kek.fill(0)
            }
        } catch (e: Exception) {
            try {
                // 回退尝试另一种 KDF
                val fallback = if (cfg.kdfType == KdfType.ARGON2ID) KdfType.PBKDF2_SHA256 else KdfType.ARGON2ID
                val kek2 = KeyDerivation.derive(password, saltBytes, fallback, cfg.argonParams)
                try {
                    val dek = AesGcm256.decrypt(kek2, kekIvBytes, encDekBytes)
                    dek.fill(0)
                } finally {
                    kek2.fill(0)
                }
            } catch (_: Exception) {
                throw Exception("密码错误或保险箱数据损坏")
            }
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")

        val rec = VaultRecord(
            id = 0,
            name = name,
            location = StorageLocation.EXTERNAL,
            relativePath = treeUri.toString(),  // 存 SAF URI 字符串
            encryptFilename = cfg.configFlags.encryptFilename,
            encryptMetadata = cfg.configFlags.encryptMetadata,
            customEncryption = cfg.configFlags.customEncryption,
            algorithm = cfg.algorithm,
            createdAt = sdf.format(Date())
        )
        val assigned = _db.addVault(rec)
        _db.save(context)

        vaults.clear()
        vaults.addAll(_db.vaults)

        return assigned
    }

    fun changePassword(
        id: Int,
        oldPassword: String,
        newPassword: String
    ) {
        // 业务层权限检查（第二道防线）
        if (!SecurityEnforcer.checkOrDie(context, Feature.ENCRYPTION_VAULT, "VaultService.changePassword")) {
            throw SecurityException("权限不足：无法修改保险箱密码")
        }

        val rec = _db.vaults.find { it.id == id } ?: throw IllegalArgumentException("保险箱不存在: id=$id")
        val dir = VaultPaths.resolveVault(context, rec.location, rec.relativePath)
        val cfg = VaultConfig.readWithFallback(context, dir)

        val saltBytes = HexCodec.decode(cfg.salt)
        val kekIvBytes = HexCodec.decode(cfg.kekIv)
        val encDekBytes = HexCodec.decode(cfg.encDek)

        val dek: ByteArray = try {
            val oldKek = KeyDerivation.derive(oldPassword, saltBytes, cfg.kdfType, cfg.argonParams)
            try {
                AesGcm256.decrypt(oldKek, kekIvBytes, encDekBytes)
            } finally {
                oldKek.fill(0)
            }
        } catch (e: Exception) {
            try {
                val fallback = if (cfg.kdfType == KdfType.ARGON2ID) KdfType.PBKDF2_SHA256 else KdfType.ARGON2ID
                val oldKek2 = KeyDerivation.derive(oldPassword, saltBytes, fallback, cfg.argonParams)
                try {
                    AesGcm256.decrypt(oldKek2, kekIvBytes, encDekBytes)
                } finally {
                    oldKek2.fill(0)
                }
            } catch (_: Exception) {
                throw Exception("原密码错误")
            }
        }

        val newSalt = SecureRandom.bytes(16)
        val newKek = KeyDerivation.derive(newPassword, newSalt, cfg.kdfType, cfg.argonParams)
        val enc = try {
            AesGcm256.encrypt(newKek, dek)
        } finally {
            dek.fill(0)
            newKek.fill(0)
        }

        val newCfg = VaultConfig(
            version = cfg.version,
            kdfType = cfg.kdfType,
            salt = HexCodec.encode(newSalt),
            argonParams = cfg.argonParams,
            kekIv = HexCodec.encode(enc.iv),
            encDek = HexCodec.encode(enc.ciphertext),
            configFlags = cfg.configFlags,
            algorithm = cfg.algorithm
        )
        newCfg.saveWithBackup(context, dir)
    }

    private val excludedFiles = setOf(
        "vault_config.json", "vault_config.backup.json", "name_mappings.json", "folder_sizes.json"
    )

    /**
     * 递归计算普通目录大小（字节），排除配置文件
     */
    fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown()
            .filter { it.isFile && it.name !in excludedFiles }
            .sumOf { it.length() }
    }

    /**
     * SAF 模式下递归计算目录大小
     */
    fun calculateDirSizeSaf(treeUri: Uri): Long {
        val docFile = DocumentFile.fromTreeUri(context, treeUri) ?: return 0L
        return calculateDocFileSize(docFile)
    }

    private fun calculateDocFileSize(doc: DocumentFile): Long {
        if (doc.isFile) {
            return if (doc.name in excludedFiles) 0L else doc.length()
        }
        var total = 0L
        for (child in doc.listFiles()) {
            total += calculateDocFileSize(child)
        }
        return total
    }

    /**
     * 更新保险箱存储用量（delta 可正可负）
     */
    fun updateStorageSize(id: Int, delta: Long) {
        val rec = _db.vaults.find { it.id == id } ?: return
        val newSize = (rec.storageSize + delta).coerceAtLeast(0)
        _db.replaceVault(rec.copy(storageSize = newSize))
        _db.save(context)
        vaults.clear()
        vaults.addAll(_db.vaults)
        markModified(id)
    }

    /**
     * 直接设置保险箱存储用量（用于首次统计）
     */
    fun setStorageSize(id: Int, size: Long) {
        val rec = _db.vaults.find { it.id == id } ?: return
        _db.replaceVault(rec.copy(storageSize = size))
        _db.save(context)
        vaults.clear()
        vaults.addAll(_db.vaults)
    }

    /**
     * 更新保险箱文件数量（delta 可正可负）
     */
    fun updateFileCount(id: Int, delta: Int) {
        val rec = _db.vaults.find { it.id == id } ?: return
        val cur = rec.fileCount ?: 0
        val newCount = (cur + delta).coerceAtLeast(0)
        _db.replaceVault(rec.copy(fileCount = newCount))
        _db.save(context)
        vaults.clear()
        vaults.addAll(_db.vaults)
        markModified(id)
    }

    /**
     * 直接设置保险箱文件数量（用于首次统计）
     */
    fun setFileCount(id: Int, count: Int) {
        val rec = _db.vaults.find { it.id == id } ?: return
        _db.replaceVault(rec.copy(fileCount = count))
        _db.save(context)
        vaults.clear()
        vaults.addAll(_db.vaults)
    }

    /**
     * 刷新指定文件夹的大小（增量、自底向上冒泡）。
     *
     * 算法：
     * 1. 收集 folder 下所有子文件夹路径
     * 2. 按路径深度降序排序（叶子在前）
     * 3. 从最深的文件夹开始：
     *    a. 读取该文件夹当前 mtime
     *    b. 若 mtime 未变且 DB 中已有记录 → 跳过
     *    c. 否则：直接子文件大小之和 + DB 中子文件夹 size 之和
     *    d. 写入 DB
     * 4. 最终计算 folder 自身的大小
     * 5. 保存 DB
     *
     * @return folder 的最终大小
     */
    fun refreshFolderSize(vaultDir: File, relativePath: String): Long {
        val saveDir = AppDataPaths.fileManager(context)
        val db = FolderSizeDb.load(saveDir)
        val targetDir = if (relativePath.isEmpty()) vaultDir else File(vaultDir, relativePath)

        if (!targetDir.exists() || !targetDir.isDirectory) {
            db.removeDescendants(relativePath)
            db.save(saveDir)
            return 0L
        }

        // 收集所有子文件夹的相对路径
        val subdirs = mutableListOf<String>()
        fun collectSubdirs(dir: File, relBase: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory && child.name !in excludedFiles) {
                    val childRel = if (relBase.isEmpty()) child.name else "$relBase/${child.name}"
                    subdirs.add(childRel)
                    collectSubdirs(child, childRel)
                }
            }
        }
        collectSubdirs(targetDir, relativePath)

        // 按深度降序排序（叶子在前）
        subdirs.sortByDescending { it.count { c -> c == '/' } }

        // 自底向上计算
        for (rel in subdirs) {
            val dir = File(vaultDir, rel)
            val currentMtime = dir.lastModified()
            val cached = db.get(rel)
            if (cached != null && cached.lastModified == currentMtime) {
                continue // 未变化，跳过
            }
            val size = calcFolderDirectSize(db, vaultDir, rel)
            db.put(rel, FolderSizeInfo(size, currentMtime))
        }

        // 计算目标文件夹自身的大小
        val targetMtime = targetDir.lastModified()
        val targetSize = calcFolderDirectSize(db, vaultDir, relativePath)
        db.put(relativePath, FolderSizeInfo(targetSize, targetMtime))

        db.save(saveDir)
        return targetSize
    }

    /**
     * 计算文件夹直接内容的大小：直接子文件大小之和 + 子文件夹在 DB 中的 size 之和
     */
    private fun calcFolderDirectSize(db: FolderSizeDb, vaultDir: File, relativePath: String): Long {
        val dir = if (relativePath.isEmpty()) vaultDir else File(vaultDir, relativePath)
        val children = dir.listFiles() ?: return 0L
        var total = 0L
        for (child in children) {
            if (child.name in excludedFiles) continue
            if (child.isFile) {
                total += child.length()
            } else if (child.isDirectory) {
                val childRel = if (relativePath.isEmpty()) child.name else "$relativePath/${child.name}"
                val childInfo = db.get(childRel)
                if (childInfo != null) {
                    total += childInfo.size
                }
                // 子文件夹未在 DB 中时跳过（尚未计算）
            }
        }
        return total
    }

    /**
     * 更新保险箱中所有文件夹的大小（全量刷新，用于批量操作后）
     */
    fun refreshAllFolderSizes(vaultDir: File) {
        refreshFolderSize(vaultDir, "")
    }
}
