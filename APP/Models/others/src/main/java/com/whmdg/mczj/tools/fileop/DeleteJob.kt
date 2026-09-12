package com.whmdg.mczj.tools.fileop

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
import com.whmdg.mczj.tools.encryption.data.FolderSizeInfo
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.util.ShellEscape
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 删除任务（支持回收站和永久删除）。
 *
 * 流程：
 * 1. 计算总大小
 * 2. 逐个删除/移到回收站
 * 3. 错误时弹窗等待用户选择
 */
class DeleteJob(
    private val entries: List<DeleteEntry>,
    private val toRecycleBin: Boolean,
    private val manager: FileOperationManager,
    private val context: Context,
    private val vaultId: Int? = null,
    private val vaultSizeDelta: Long = 0L,
    private val vaultFileCountDelta: Int = 0,
    private val vaultDir: File? = null
) : FileOperationJob() {

    private var skipAllErrors = false

    /** 已成功删除的文件大小累加（负值），用于向父目录抛出 delta */
    private var deletedSizeDelta = 0L

    /** 删除前预计算的每个 entry 的实际大小（目录递归统计），供 updateFolderSizeDb 使用 */
    private lateinit var entrySizes: LongArray

    @Throws(Exception::class)
    override fun run() {
        try {
            // 删除前预计算每个 entry 的实际大小（目录递归统计）
            entrySizes = LongArray(entries.size) { i ->
                entries[i].size.takeIf { it > 0 } ?: calculateTotalSize(entries[i].path)
            }
            val totalSize = entrySizes.sum()
            var processedBytes = 0L

            manager.updateProgress(FileOpProgress(
                phase = "正在删除",
                currentBytes = 0,
                totalBytes = totalSize,
                currentFileName = "",
                fileIndex = 0,
                fileCount = entries.size
            ))

            for ((index, entry) in entries.withIndex()) {
                throwIfCancelled()

                manager.updateProgress(FileOpProgress(
                    phase = if (toRecycleBin) "正在移到回收站" else "正在删除",
                    currentBytes = processedBytes,
                    totalBytes = totalSize,
                    currentFileName = entry.name,
                    fileIndex = index,
                    fileCount = entries.size
                ))

                val entrySize = entrySizes[index]

                var retry: Boolean
                do {
                    retry = false
                    try {
                        currentStep = if (toRecycleBin) "移到回收站: ${entry.name}" else "删除: ${entry.name}"
                        if (toRecycleBin) {
                            moveToRecycleBin(entry)
                        } else {
                            deleteEntry(entry)
                        }
                        heartbeat()
                        processedBytes += entrySize
                        deletedSizeDelta -= entrySize
                    } catch (e: InterruptedIOException) {
                        throw e
                    } catch (e: Exception) {
                        if (skipAllErrors) {
                            continue
                        }
                        val result = runBlocking {
                            manager.resolveError(ErrorRequest(
                                fileName = entry.name,
                                errorMessage = e.message ?: "删除失败"
                            ))
                        }
                        when (result.action) {
                            ErrorAction.RETRY -> retry = true
                            ErrorAction.SKIP -> { }
                            ErrorAction.SKIP_ALL -> skipAllErrors = true
                            ErrorAction.CANCEL -> throw InterruptedIOException("用户取消")
                        }
                    }
                } while (retry)
            }
        } finally {
            // 清除线程中断标志，确保后续清理代码能正常执行 shell 命令
            Thread.interrupted()

            if (cancelFlag.get()) {
                // 取消时也将已删除部分的 delta 写入 FolderSizeDb
                updateFolderSizeDb()
                // 步骤一：用户手动取消
                // 1. 面板改为"正在取消"
                manager.updateProgress(FileOpProgress(
                    phase = "正在取消",
                    currentBytes = 0,
                    totalBytes = 0,
                    isRunning = true
                ))
                // 2. 清理完毕，关闭窗口
                manager.updateProgress(null)
                manager.notifyRefreshNeeded()
            } else {
                // 步骤二：其他错误或正常完成
                updateFolderSizeDb()
                manager.updateProgress(null)
                manager.notifyRefreshNeeded()
            }
            // 报告保险箱存储用量变更
            if (vaultId != null && vaultSizeDelta != 0L) {
                manager.notifyVaultSizeChange(vaultId, vaultSizeDelta)
            }
            // 报告保险箱文件数量变更
            if (vaultId != null && vaultFileCountDelta != 0) {
                manager.notifyVaultFileCountChange(vaultId, vaultFileCountDelta)
            }
        }
    }

    /**
     * 删除完成后更新 FolderSizeDb：
     * 1. 移除被删除路径及其所有子路径
     * 2. 从所有祖先路径中减去已删除的大小（向上冒泡至根节点）
     * 3. 通知 UI 更新
     *
     * 使用 [deletedSizeDelta]（删除过程中累加的实际删除大小），
     * 而非删除后再计算（此时文件已不存在，calculateTotalSize 返回 0）。
     */
    private fun updateFolderSizeDb() {
        if (deletedSizeDelta == 0L) return
        val saveDir = AppDataPaths.fileManager(context)
        val db = FolderSizeDb.load(saveDir)
        val affectedSizes = mutableMapOf<String, Long>()

        // 按父目录聚合已删除的大小
        val deletedByParent = mutableMapOf<String, Long>()
        for ((i, entry) in entries.withIndex()) {
            val entrySize = entrySizes[i]
            val normalizedEntryPath = entry.path.trimEnd('/')
            val parent = File(entry.path).parentFile?.path?.trimEnd('/') ?: continue
            deletedByParent[parent] = (deletedByParent[parent] ?: 0L) + entrySize

            // 移除被删除路径及其所有子路径
            if (entry.isDirectory) {
                db.removeDescendants(normalizedEntryPath)
            } else {
                db.remove(normalizedEntryPath)
            }
        }

        // 从每个受影响的父目录向上冒泡至根节点
        for ((parentPath, deleted) in deletedByParent) {
            var dir = File(parentPath)
            var remaining = deleted
            while (remaining > 0) {
                // 规范化路径：去除尾部 /，确保与 FolderSizeDb 中的 key 格式一致
                val normalizedPath = dir.path.trimEnd('/')
                val existing = db.get(normalizedPath) ?: break
                val deduction = minOf(remaining, existing.size)
                val newSize = existing.size - deduction
                db.put(normalizedPath, FolderSizeInfo(newSize, System.currentTimeMillis()))
                affectedSizes[normalizedPath] = newSize
                remaining -= deduction
                dir = dir.parentFile ?: break
            }
        }

        db.save(saveDir)
        manager.notifyFolderSizeChanged(affectedSizes)
    }

    private fun moveToRecycleBin(entry: DeleteEntry) {
        val binDir = AppDataPaths.recycleBin(context)
        operator.mkdir(binDir.absolutePath)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val binName = "${entry.name}_$timestamp"
        val binPath = "${binDir.absolutePath}/$binName"

        // 通过 shell mv 移动（源可能在 ROOT-only 目录）
        val escaped_src = ShellEscape.escape(entry.path)
        val escaped_dst = ShellEscape.escape(binPath)
        try {
            com.whmdg.mczj.tools.security.ShellExecutor.execute(
                com.whmdg.mczj.tools.security.Permission.MAX, "mv $escaped_src $escaped_dst"
            )
        } catch (e: Exception) {
            throw IOException("无法移动到回收站: ${entry.name}: ${e.message}")
        }

        // 保存回收站元数据（应用内部存储，Java File API 可用）
        saveRecycleBinMeta(entry, binName)
    }

    /**
     * 永久删除文件/目录。通过 operator（Permission.MAX）执行。
     */
    private fun deleteEntry(entry: DeleteEntry) {
        if (!operator.exists(entry.path)) return
        operator.deleteFile(entry.path)
    }

    /**
     * 计算文件/目录总大小。通过 operator（Permission.MAX）执行。
     */
    private fun calculateTotalSize(path: String): Long {
        if (!operator.exists(path)) return 0L
        if (!operator.isDirectory(path)) return operator.fileSize(path)
        var total = 0L
        val stack = ArrayDeque<String>()
        stack.add(path)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = operator.listChildren(dir) ?: continue
            for (child in children) {
                if (child.isDir) {
                    stack.add(child.path)
                } else {
                    total += operator.fileSize(child.path)
                }
            }
        }
        return total
    }

    private fun saveRecycleBinMeta(entry: DeleteEntry, binName: String) {
        try {
            val binDir = AppDataPaths.recycleBin(context)
            val metaFile = File(binDir, "recycle_bin.json")

            data class RecycleBinEntry(
                val binName: String,
                val originalPath: String,
                val deletedAt: Long,
                val isDirectory: Boolean
            )

            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true
            }

            val metaList = if (metaFile.exists()) {
                try {
                    json.decodeFromString<List<RecycleBinEntry>>(metaFile.readText())
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val newEntry = RecycleBinEntry(
                binName = binName,
                originalPath = entry.path,
                deletedAt = System.currentTimeMillis(),
                isDirectory = entry.isDirectory
            )

            metaFile.writeText(json.encodeToString(metaList + newEntry))
        } catch (_: Exception) {
            // 元数据保存失败不致命
        }
    }

    private fun calculateTotalSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(file)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (f.isDirectory) {
                f.listFiles()?.forEach { stack.add(it) }
            } else {
                total += f.length()
            }
        }
        return total
    }
}
