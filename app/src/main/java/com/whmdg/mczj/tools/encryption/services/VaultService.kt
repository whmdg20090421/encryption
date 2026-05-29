package com.whmdg.mczj.tools.encryption.services

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.mutableStateListOf
import com.whmdg.mczj.tools.auth.Feature
import com.whmdg.mczj.tools.auth.SecurityEnforcer
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
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        
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
            val fallback = if (cfg.kdfType == KdfType.ARGON2ID) KdfType.PBKDF2_SHA256 else KdfType.ARGON2ID
            val kek2 = KeyDerivation.derive(password, saltBytes, fallback, cfg.argonParams)
            try {
                dek = AesGcm256.decrypt(kek2, kekIvBytes, encDekBytes)
            } finally {
                kek2.fill(0)
            }
        }

        return VaultSession(
            record = rec,
            vaultDir = dir,
            config = cfg,
            dek = dek
        )
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
                dir.deleteRecursively()
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
        sdf.timeZone = TimeZone.getTimeZone("UTC")

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
        sdf.timeZone = TimeZone.getTimeZone("UTC")

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

        val oldKek = KeyDerivation.derive(oldPassword, saltBytes, cfg.kdfType, cfg.argonParams)
        val dek = try {
            AesGcm256.decrypt(oldKek, kekIvBytes, encDekBytes)
        } finally {
            oldKek.fill(0)
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
}
