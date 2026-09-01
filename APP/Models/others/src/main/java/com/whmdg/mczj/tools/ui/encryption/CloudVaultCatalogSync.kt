package com.whmdg.mczj.tools.ui.encryption

import android.content.Context
import com.whmdg.mczj.tools.encryption.data.CloudVaultCatalog
import com.whmdg.mczj.tools.encryption.data.CloudVaultMetadata
import com.whmdg.mczj.tools.encryption.data.SyncDatabase
import com.whmdg.mczj.tools.encryption.data.VaultRecord
import com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** WebDAV 上保险箱清单的读写。 */
object CloudVaultCatalogSync {
    private const val FILE_NAME = "vault_catalog.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private fun remotePath(configPath: String): String {
        val base = configPath.trim('/').trimEnd('/')
        return if (base.isEmpty()) "/.sync_meta/$FILE_NAME" else "/$base/.sync_meta/$FILE_NAME"
    }

    suspend fun download(client: WebDavFileClient, configPath: String, cacheFile: File): CloudVaultCatalog {
        return withContext(Dispatchers.IO) {
            try {
                withTimeout(120_000L) {
                    runInterruptible { client.downloadFile(remotePath(configPath), cacheFile) { } }
                    json.decodeFromString<CloudVaultCatalog>(cacheFile.readText())
                }
            } finally {
                cacheFile.delete()
            }
        }
    }

    suspend fun upload(
        client: WebDavFileClient,
        configPath: String,
        vaults: List<VaultRecord>,
        cacheFile: File
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val metaDir = remotePath(configPath).substringBeforeLast('/')
                try { client.mkdir(metaDir) } catch (_: Exception) {}
                val remote = try { download(client, configPath, cacheFile) } catch (_: Exception) { null }
                // 云端记录优先，避免另一台设备的同 ID 本地记录覆盖云端权威元数据。
                val merged = (vaults.map { it.toCloudMetadata() } + remote?.vaults.orEmpty())
                    .associateBy { it.id }
                    .values
                    .sortedBy { it.id }
                cacheFile.writeText(json.encodeToString(CloudVaultCatalog(vaults = merged)))
                withTimeout(120_000L) {
                    runInterruptible { client.uploadFile(cacheFile, remotePath(configPath)) { } }
                }
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("上传云端保险箱清单失败: ${e.message}", e)
            } finally {
                cacheFile.delete()
            }
        }
    }

    /** 登录阶段恢复某个保险箱的加密同步数据库。 */
    suspend fun restoreVaultDatabase(
        context: Context,
        client: WebDavFileClient,
        configPath: String,
        meta: CloudVaultMetadata
    ): Boolean {
        val remoteBase = configPath.trimEnd('/').let { base ->
            if (base.isBlank()) "/${meta.remoteFolder}" else "/$base/${meta.remoteFolder}"
        }
        val remotePath = "$remoteBase/.sync_meta/${meta.name}_vault_sync.db.7z"
        val zipFile = File(context.cacheDir, "${meta.id}_vault_sync_restore.db.7z")
        val extractDir = File(context.cacheDir, "cloud_db_restore_${meta.id}")
        return withContext(Dispatchers.IO) {
            try {
                val exists = withTimeout(30_000L) { runInterruptible { client.exists(remotePath) } }
                if (!exists) return@withContext false
                withTimeout(120_000L) { runInterruptible { client.downloadFile(remotePath, zipFile) { } } }
                extractDir.mkdirs()
                withTimeout(120_000L) { com.whmdg.mczj.tools.util.JBindingClient.extractAll(
                    archivePath = zipFile.absolutePath,
                    outputDir = extractDir.absolutePath,
                    password = "mczj"
                ).getOrThrow() }
                val sourceDb = File(extractDir, "vault_sync.db")
                if (!sourceDb.exists()) return@withContext false
                SyncDatabase.getInstance(context, meta.name).importCloudEntriesFromFile(sourceDb)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("恢复保险箱「${meta.name}」的同步数据库失败: ${e.message}", e)
            } finally {
                zipFile.delete()
                extractDir.deleteRecursively()
            }
        }
    }

    private fun VaultRecord.toCloudMetadata() = CloudVaultMetadata(
        id = id,
        name = name,
        remoteFolder = name,
        relativePath = relativePath,
        createdAt = createdAt,
        location = location,
        encryptFilename = encryptFilename,
        encryptMetadata = encryptMetadata,
        customEncryption = customEncryption,
        algorithm = algorithm,
        lastModifiedAt = lastModifiedAt
    )
}
