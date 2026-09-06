package com.whmdg.mczj.tools.encryption.data

import com.whmdg.mczj.tools.encryption.core.HexCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.content.Context

@Serializable
enum class KdfType {
    @SerialName("argon2id") ARGON2ID,
    @SerialName("pbkdf2-sha256") PBKDF2_SHA256
}

@Serializable
data class Argon2Params(
    @SerialName("time_cost") val timeCost: Int,
    @SerialName("memory_cost") val memoryCostKb: Int,
    val parallelism: Int
) {
    companion object {
        val LOW = Argon2Params(1, 32768, 1)
        val MEDIUM = Argon2Params(2, 65536, 2)
        val HIGH = Argon2Params(3, 131072, 4)
    }
}

@Serializable
data class ConfigFlags(
    @SerialName("encrypt_filename") val encryptFilename: Boolean = false,
    @SerialName("encrypt_metadata") val encryptMetadata: Boolean = false,
    @SerialName("custom_encryption") val customEncryption: Boolean = false
)

@Serializable
data class VaultConfig(
    val version: Int = 2,
    val uuid: String? = null,
    val name: String? = null,
    @SerialName("kdf_type") val kdfType: KdfType,
    val salt: String, // Hex string
    @SerialName("argon_params") val argonParams: Argon2Params,
    @SerialName("kek_iv") val kekIv: String, // Hex string
    @SerialName("enc_dek") val encDek: String, // Hex string
    @SerialName("config_flags") val configFlags: ConfigFlags,
    val algorithm: String = "AES-256-GCM",
    @SerialName("integrity_hmac") val integrityHmac: String? = null
) {
    fun computeHmac(): String {
        // 使用 CanonicalJson 重新序列化并计算 HMAC
        val saltBytes = HexCodec.decode(salt)
        val keyMat = "vault_integrity_v1".toByteArray() + saltBytes
        val md = MessageDigest.getInstance("SHA-256")
        val hmacKey = md.digest(keyMat)
        
        // 构建不含 integrity_hmac 的 JsonObject 供 CanonicalJson 使用
        val json = Json.encodeToJsonElement(this) as JsonObject
        val filtered = json.filterKeys { it != "integrity_hmac" }
        val canonical = CanonicalJson.encode(JsonObject(filtered))
        
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        val tag = mac.doFinal(canonical.toByteArray())
        return tag.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val json = Json {
            prettyPrint = true
            prettyPrintIndent = "    "
            ignoreUnknownKeys = true
        }

        fun readWithFallback(context: Context, vaultDir: File): VaultConfig {
            val main = File(vaultDir, "vault_config.json")
            val inVault = File(vaultDir, "vault_config.backup.json")
            val priv = VaultPaths.appPrivateBackupDir(context)
            val h = VaultPaths.pathHash(vaultDir.absolutePath)
            val privBackup = File(priv, "vault_config_$h.json")

            for (f in listOf(main, inVault, privBackup)) {
                if (!f.exists()) continue
                try {
                    val cfg = json.decodeFromString<VaultConfig>(f.readText())
                    if (cfg.integrityHmac != null && cfg.computeHmac() == cfg.integrityHmac) {
                        return cfg
                    }
                } catch (e: Exception) {}
            }
            throw Exception("所有 vault_config 备份均损坏或丢失")
        }


        fun verifyAllCopies(context: Context, vaultDir: File): VerifyResult {
            val main = File(vaultDir, "vault_config.json")
            val inVault = File(vaultDir, "vault_config.backup.json")
            val priv = VaultPaths.appPrivateBackupDir(context)
            val h = VaultPaths.pathHash(vaultDir.absolutePath)
            val privBackup = File(priv, "vault_config_$h.json")

            fun tryLoad(f: File): VaultConfig? {
                if (!f.exists()) return null
                try {
                    val cfg = json.decodeFromString<VaultConfig>(f.readText())
                    if (cfg.integrityHmac != null && cfg.computeHmac() == cfg.integrityHmac) {
                        return cfg
                    }
                } catch (e: Exception) {}
                return null
            }

            val results = listOfNotNull(
                tryLoad(main),
                tryLoad(inVault),
                tryLoad(privBackup)
            )

            if (results.isEmpty()) {
                return VerifyResult(null, true)
            }

            var best = results.first()
            var bestCount = 1
            for (candidate in results) {
                val jsonEl = json.encodeToJsonElement(serializer(), candidate) as JsonObject
                val filtered = jsonEl.filterKeys { it != "integrity_hmac" }
                val cJson = CanonicalJson.encode(JsonObject(filtered))
                
                var count = 0
                for (other in results) {
                    val otherEl = json.encodeToJsonElement(serializer(), other) as JsonObject
                    val otherFiltered = otherEl.filterKeys { it != "integrity_hmac" }
                    if (CanonicalJson.encode(JsonObject(otherFiltered)) == cJson) {
                        count++
                    }
                }
                if (count > bestCount) {
                    best = candidate
                    bestCount = count
                }
            }

            val isTampered = results.size >= 2 && bestCount < results.size
            return VerifyResult(best, isTampered)
        }
    }

    fun saveWithBackup(context: Context, vaultDir: File) {
        val cfgWithHmac = this.copy(integrityHmac = computeHmac())
        val text = json.encodeToString(cfgWithHmac)
        
        val main = File(vaultDir, "vault_config.json")
        val inVault = File(vaultDir, "vault_config.backup.json")
        val priv = VaultPaths.appPrivateBackupDir(context)
        val h = VaultPaths.pathHash(vaultDir.absolutePath)
        val privBackup = File(priv, "vault_config_$h.json")

        main.parentFile?.mkdirs()
        main.writeText(text)
        
        for (f in listOf(inVault, privBackup)) {
            try {
                f.parentFile?.mkdirs()
                f.writeText(text)
            } catch (e: Exception) {}
        }
    }

    data class VerifyResult(val validConfig: VaultConfig?, val isTampered: Boolean)
}
