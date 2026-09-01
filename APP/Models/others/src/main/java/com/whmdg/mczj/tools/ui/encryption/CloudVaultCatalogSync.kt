package com.whmdg.mczj.tools.ui.encryption

import android.content.Context
import com.whmdg.mczj.tools.encryption.data.CloudVaultCatalog
import com.whmdg.mczj.tools.encryption.data.CloudVaultMetadata
import com.whmdg.mczj.tools.encryption.data.SyncDatabase
import com.whmdg.mczj.tools.encryption.data.VaultRecord
import com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient
import kotlinx.serialization.json.Json
import java.io.File

/** WebDAV 上保险箱清单的读写。 */
object CloudVaultCatalogSync {
    private const val FILE_NAME = "vault_catalog.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private fun remotePath(configPath: String): String {
        val base = configPath.trim('/').trimEnd('/')
        return if (base.isEmpty()) "/.sync_meta/$FILE_NAME" else "/$base/.sync_meta/$FILE_NAME"
    }

    fun download(client: WebDavFileClient, configPath: String, cacheFile: File): CloudVaultCatalog? {
        return try {
            client.downloadFile(remotePath(configPath), cacheFile) { }
            json.decodeFromString<CloudVaultCatalog>(cacheFile.readText())
        } catch (_: Exception) {
            null
        } finally {
            cacheFile.delete()
        }
    }

    fun upload(
        client: WebDavFileClient,
        configPath: String,
        vaults: List<VaultRecord>,
        cacheFile: File
    ): Boolean {
        return try {
            val metaDir = remotePath(configPath).substringBeforeLast('/')
            try { client.mkdir(metaDir) } catch (_: Exception) {}
            val remote = download(client, configPath, cacheFile)
            // 云端记录优先，避免另一台设备的同 ID 本地记录覆盖云端权威元数据。
            val merged = (vaults.map { it.toCloudMetadata() } + remote?.vaults.orEmpty())
                .associateBy { it.id }
                .values
                .sortedBy { it.id }
            cacheFile.writeText(json.encodeToString(CloudVaultCatalog(vaults = merged)))
            client.uploadFile(cacheFile, remotePath(configPath)) { }
            true
        } catch (_: Exception) {
            false
        } finally {
            cacheFile.delete()
        }
    }

    /** 登录阶段恢复某个保险箱的加密同步数据库。 */
    fun restoreVaultDatabase(
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
        return try {
            if (!client.exists(remotePath)) return false
            client.downloadFile(remotePath, zipFile) { }
            extractDir.mkdirs()
            com.whmdg.mczj.tools.util.JBindingClient.extractAll(
                archivePath = zipFile.absolutePath,
                outputDir = extractDir.absolutePath,
                password = "mczj"
            ).getOrThrow()
            val sourceDb = File(extractDir, "vault_sync.db")
            if (!sourceDb.exists()) return false
            SyncDatabase.getInstance(context, meta.name).importCloudEntriesFromFile(sourceDb)
            true
        } catch (_: Exception) {
            false
        } finally {
            zipFile.delete()
            extractDir.deleteRecursively()
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
