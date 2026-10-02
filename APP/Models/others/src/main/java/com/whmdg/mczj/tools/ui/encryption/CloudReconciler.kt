package com.whmdg.mczj.tools.ui.encryption

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.encryption.data.SyncDatabase
import com.whmdg.mczj.tools.encryption.data.SyncEntryRow
import com.whmdg.mczj.tools.encryption.data.SyncStatus
import com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient
import com.whmdg.mczj.tools.fileop.webdav.WebDavServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.Instant

/**
 * 与云端对齐：手动触发的全量对账。
 *
 * 背景：cloud_entries 是"本设备对云端的认知"，当上传中断导致云端 DB 未及时上传时，
 * 本设备 cloud_entries 可能领先于云端；而反向（其他设备写入新 DB 但文件未传完）时又会
 * 出现本地已上传、云端 DB 缺失的条目。本地实时四色条以 local_entries + cloud_entries
 * 双表判定后，这类条目会显示为红色，但对账机制本身无法自动联网修正。
 *
 * 本类按目录遍历 local_entries 中 status=COMPLETED 的文件，仅对"本地已完成但 cloud_entries
 * 缺失"的条目核对：
 *  - 该目录向云端发一次目录列表（PROPFIND），命中文件 → 云端确实存在 → 补写 cloud_entries；
 *  - 未命中 → 云端确实没有 → 本地改回 PENDING 且清空上传进度（触发重新上传）。
 * 网络失败/结果未知的目录不做任何修改（保守），由用户下次重试。
 */
object CloudReconciler {

    data class Progress(
        val phase: String,
        val current: Int = 0,
        val total: Int = 0
    )

    data class AlignResult(
        val checkedDirs: Int,
        val restored: Int,
        val resetToPending: Int,
        val failedDirs: Int,
        val uploadedDb: Boolean
    )

    /**
     * 执行全量对齐。
     *
     * @param webdavConfig WebDAV 配置（用于定位 remoteBasePath）
     */
    suspend fun align(
        context: Context,
        vaultName: String,
        webdavConfig: WebDavServerConfig,
        onProgress: suspend (Progress) -> Unit = {}
    ): AlignResult = withContext(Dispatchers.IO) {
        val syncDb = SyncDatabase.getInstance(context, vaultName)
        val client = WebDavFileClient(webdavConfig)
        val remoteBase = buildRemoteBase(webdavConfig.relativePath, vaultName)

        // 本地已完成文件（排除目录条目）
        val localCompleted = syncDb.getEntriesByStatus("local_entries", SyncStatus.COMPLETED)
            .filter { !it.path.endsWith("/") }

        // 云端已知路径集合，随补写实时更新
        val cloudPaths = syncDb.getAllEntries("cloud_entries")
            .filter { !it.path.endsWith("/") }
            .map { it.path }
            .toMutableSet()

        // 仅处理"本地完成但云端缺失"的条目，按父目录分组
        val missingByDir = localCompleted
            .filter { it.path !in cloudPaths }
            .groupBy { it.path.substringBeforeLast('/', "") }

        var checkedDirs = 0
        var restored = 0
        var reset = 0
        var failedDirs = 0
        val totalDirs = missingByDir.size

        for ((dir, files) in missingByDir) {
            ensureActive()
            checkedDirs++
            val remoteDirPath = if (dir.isEmpty()) remoteBase else "$remoteBase$dir"
            onProgress(Progress("正在核对目录 $remoteDirPath", checkedDirs, totalDirs))

            // 每目录一次列表：网络异常 → 整目录跳过，不修改任何本地状态
            val remoteChildren = try {
                withTimeout(30_000L) { runInterruptible { client.listChildren(remoteDirPath) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (remoteChildren == null) {
                failedDirs++
                continue
            }
            val remoteNames = remoteChildren.mapNotNull { it.name }.toSet()

            for (entry in files) {
                val name = entry.path.substringAfterLast('/')
                if (name in remoteNames) {
                    // 云端确实存在：补写 cloud_entries（size 取本地权威值，指纹沿用本地）
                    val now = Instant.now().toString()
                    syncDb.upsertEntry("cloud_entries", SyncEntryRow(
                        path = entry.path,
                        size = entry.size,
                        lastModified = now,
                        contentHash = entry.contentHash ?: "",
                        cloudHash = null,
                        status = SyncStatus.COMPLETED,
                        lastSyncTime = now,
                        failReason = null,
                        originalName = entry.originalName
                    ))
                    cloudPaths.add(entry.path)
                    restored++
                } else {
                    // 云端不存在：本地改为 PENDING 并清空上传进度
                    syncDb.updateEntry("local_entries", entry.path) { row ->
                        row.copy(status = SyncStatus.PENDING, uploadedSize = 0, failReason = null)
                    }
                    reset++
                }
            }
        }

        var uploadedDb = false
        if (restored > 0) {
            // 云端认知已补全：刷新时戳并回传云端 DB
            syncDb.touchCloudDbTimestamp()
            onProgress(Progress("正在回传云端数据库", totalDirs, totalDirs))
            val vaultRecord = com.whmdg.mczj.tools.encryption.services.VaultService(context)
                .also { it.load() }.vaults.find { it.name == vaultName }
            val configFile = vaultRecord?.let { record ->
                File(
                    com.whmdg.mczj.tools.encryption.data.VaultPaths.resolveVault(
                        context, record.location, record.relativePath
                    ),
                    "vault_config.json"
                )
            }
            if (configFile != null) {
                uploadedDb = try {
                    val dbFile = File(
                        File(AppDataPaths.encryption(context), "云盘同步/$vaultName"),
                        "vault_sync.db"
                    )
                    CloudVaultCatalogSync.uploadVaultDatabase(
                        context = context,
                        client = client,
                        configPath = webdavConfig.relativePath,
                        vaultName = vaultName,
                        dbFile = dbFile,
                        configFile = configFile
                    )
                } catch (_: Exception) {
                    false
                }
            }
        }

        AlignResult(
            checkedDirs = checkedDirs,
            restored = restored,
            resetToPending = reset,
            failedDirs = failedDirs,
            uploadedDb = uploadedDb
        )
    }

    private fun buildRemoteBase(relativePath: String, vaultName: String): String {
        val base = relativePath.trimEnd('/')
        return if (base.isEmpty()) "/$vaultName" else "$base/$vaultName"
    }
}
