package com.whmdg.mczj.tools.ui.encryption

import android.content.Context
import com.whmdg.mczj.tools.encryption.data.SyncDatabase
import com.whmdg.mczj.tools.encryption.data.VaultConfig
import com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File

/** WebDAV 上保险箱同步数据库的管理（基于 UUID 文件名）。 */
object CloudVaultCatalogSync {

    private val json = Json { ignoreUnknownKeys = true }

    /** 构建云端 .sync_meta 目录路径 */
    private fun metaDirPath(configPath: String): String {
        val base = configPath.trimEnd('/').let { if (it.isBlank()) "" else "/$it" }
        return "$base/.sync_meta"
    }

    /** 构建云端同步数据库路径（基于 UUID） */
    private fun vaultDbPath(configPath: String, uuid: String): String =
        "${metaDirPath(configPath)}/${uuid}_vault_sync.db.7z"

    /**
     * 列出云端所有保险箱（通过扫描 .sync_meta/ 下的 .7z 文件）。
     *
     * @param client WebDAV 客户端
     * @param configPath WebDAV 配置路径
     * @return UUID 列表
     */
    suspend fun listCloudVaults(
        client: WebDavFileClient,
        configPath: String
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val metaDir = metaDirPath(configPath)
            val files = withTimeout(30_000L) {
                runInterruptible { client.listChildren(metaDir) }
            } ?: emptyList()
            files.filter { it.name.endsWith("_vault_sync.db.7z") }
                .map { it.name.removeSuffix("_vault_sync.db.7z") }
        } catch (e: Exception) {
            // .sync_meta 目录不存在或为空
            emptyList()
        }
    }

    /**
     * 上传保险箱同步数据库到云端根目录 .sync_meta/。
     *
     * @param context 上下文
     * @param client WebDAV 客户端
     * @param configPath WebDAV 配置路径
     * @param dbFile 本地同步数据库文件
     * @param configFile 保险箱配置文件（vault_config.json，包含 UUID 和 name）
     * @return 成功返回 true，失败抛出异常
     */
    suspend fun uploadVaultDatabase(
        context: Context,
        client: WebDavFileClient,
        configPath: String,
        dbFile: File,
        configFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        // 读取配置文件提取 UUID
        val cfg = try {
            json.decodeFromString<VaultConfig>(configFile.readText())
        } catch (e: Exception) {
            throw IllegalStateException("无法读取配置文件: ${e.message}", e)
        }

        val uuid = cfg.uuid ?: throw IllegalStateException("配置文件缺少 UUID 字段")
        val vaultName = cfg.name ?: uuid

        val zipFile = File(context.cacheDir, "${uuid}_vault_sync_upload.db.7z")
        try {
            if (!dbFile.exists()) throw IllegalStateException("数据库文件不存在: ${dbFile.absolutePath}")

            // 压缩数据库和配置文件
            com.whmdg.mczj.tools.util.JBindingClient.compress(
                sourcePaths = listOf(dbFile.absolutePath, configFile.absolutePath),
                outputPath = zipFile.absolutePath,
                format = "7z", level = 9,
                password = "mczj", useAes = true, encryptNames = true
            ).getOrThrow()

            // 确保云端元数据目录存在
            val metaDir = metaDirPath(configPath)
            try { client.mkdir(metaDir) } catch (_: Exception) {}

            // 上传到云端根目录 .sync_meta/
            val remotePath = vaultDbPath(configPath, uuid)
            withTimeout(120_000L) {
                runInterruptible { client.uploadFile(zipFile, remotePath) { } }
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("上传保险箱「${vaultName}」同步数据库失败: ${e.message}", e)
        } finally {
            zipFile.delete()
        }
    }

    /**
     * 从云端根目录 .sync_meta/ 下载保险箱同步数据库。
     *
     * 下载时检查本地缓存：若本地已有相同大小的文件，则跳过下载直接解压。
     *
     * @param context 上下文
     * @param client WebDAV 客户端
     * @param configPath WebDAV 配置路径
     * @param uuid 保险箱 UUID（指定下载哪个保险箱的数据库）
     * @param targetDb 目标数据库实例（用于导入 cloud_entries）
     * @return Triple<成功标志, vault_config.json 文件路径?, 保险箱名称>，文件不存在返回 Triple(false, null, "")，失败抛出异常
     */
    suspend fun downloadVaultDatabase(
        context: Context,
        client: WebDavFileClient,
        configPath: String,
        uuid: String,
        targetDb: SyncDatabase
    ): Triple<Boolean, File?, String> {
        val remotePath = vaultDbPath(configPath, uuid)
        val zipFile = File(context.cacheDir, "${uuid}_vault_sync_download.db.7z")
        val extractDir = File(context.cacheDir, "vault_db_download_${uuid}")
        return withContext(Dispatchers.IO) {
            try {
                // 检查云端文件是否存在
                val exists = withTimeout(30_000L) { runInterruptible { client.exists(remotePath) } }
                if (!exists) return@withContext Triple(false, null, "")

                // 获取云端文件元数据
                val remoteMeta = withTimeout(30_000L) { runInterruptible { client.getFileMetadata(remotePath) } }

                // 如果本地缓存文件存在且大小一致，跳过下载
                val needDownload = !zipFile.exists() || zipFile.length() != remoteMeta?.size
                if (needDownload) {
                    // 下载 7z 文件
                    withTimeout(120_000L) { runInterruptible { client.downloadFile(remotePath, zipFile) { } } }
                }

                // 解压
                extractDir.mkdirs()
                withTimeout(120_000L) {
                    com.whmdg.mczj.tools.util.JBindingClient.extractAll(
                        archivePath = zipFile.absolutePath,
                        outputDir = extractDir.absolutePath,
                        password = "mczj"
                    ).getOrThrow()
                }

                // 导入 cloud_entries 到目标数据库
                val sourceDb = File(extractDir, "vault_sync.db")
                val sourceConfig = File(extractDir, "vault_config.json")

                if (!sourceDb.exists()) throw IllegalStateException("解压后未找到 vault_sync.db")
                targetDb.importCloudEntriesFromFile(sourceDb)

                // 持久化 vault_config.json
                val configFile = if (sourceConfig.exists()) {
                    val pendingDir = File(context.cacheDir, "pending_vault_configs")
                    pendingDir.mkdirs()
                    val targetFile = File(pendingDir, "${uuid}.json")
                    sourceConfig.copyTo(targetFile, overwrite = true)
                    targetFile
                } else null

                // 从配置文件读取保险箱名称
                val vaultName = if (configFile != null) {
                    try {
                        val cfg = json.decodeFromString<VaultConfig>(configFile.readText())
                        cfg.name ?: uuid // fallback 使用 UUID
                    } catch (e: Exception) {
                        uuid // 解析失败，使用 UUID
                    }
                } else {
                    uuid // 配置文件不存在，使用 UUID
                }

                Triple(true, configFile, vaultName)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("下载保险箱同步数据库失败: ${e.message}", e)
            } finally {
                extractDir.deleteRecursively()
                // 注意：不删除 zipFile，保留作为缓存供下次检查
            }
        }
    }

    /**
     * 批量下载所有保险箱同步数据库。
     *
     * @param context 上下文
     * @param client WebDAV 客户端
     * @param configPath WebDAV 配置路径
     * @param uuids 要下载的保险箱 UUID 列表（若为空则下载全部）
     * @param onProgress 进度回调 (当前索引, 总数, UUID)
     * @return 成功下载的数量
     */
    suspend fun downloadAllVaultDatabases(
        context: Context,
        client: WebDavFileClient,
        configPath: String,
        uuids: List<String> = emptyList(),
        onProgress: (current: Int, total: Int, uuid: String) -> Unit = { _, _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        // 如果未指定 UUID 列表，则列出所有云端保险箱
        val uuidsToDownload = if (uuids.isEmpty()) {
            listCloudVaults(client, configPath)
        } else {
            uuids
        }

        var successCount = 0
        uuidsToDownload.forEachIndexed { index, uuid ->
            onProgress(index + 1, uuidsToDownload.size, uuid)
            val targetDb = SyncDatabase.getInstance(context, uuid)
            try {
                val (success, _, _) = downloadVaultDatabase(context, client, configPath, uuid, targetDb)
                if (success) {
                    successCount++
                }
            } catch (_: Exception) {
                // 单个失败不影响其他保险箱下载
            }
        }
        successCount
    }
}

