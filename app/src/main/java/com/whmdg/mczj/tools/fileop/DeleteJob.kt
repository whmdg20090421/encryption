package com.whmdg.mczj.tools.fileop

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
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
    private val context: Context
) : FileOperationJob() {

    private var skipAllErrors = false

    @Throws(Exception::class)
    override fun run() {
        var totalSize = 0L
        for (entry in entries) {
            totalSize += calculateTotalSize(File(entry.path))
        }
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

            var retry: Boolean
            do {
                retry = false
                try {
                    if (toRecycleBin) {
                        moveToRecycleBin(entry)
                    } else {
                        deleteEntry(entry)
                    }
                    processedBytes += calculateTotalSize(File(entry.path))
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
                        ErrorAction.SKIP -> { /* 跳过 */ }
                        ErrorAction.SKIP_ALL -> skipAllErrors = true
                        ErrorAction.CANCEL -> throw InterruptedIOException("用户取消")
                    }
                }
            } while (retry)
        }

        manager.updateProgress(null)
        manager.notifyRefreshNeeded()
    }

    /**
     * 将文件移到回收站。
     * 回收站路径：<internal_files>/.recycle_bin/
     */
    private fun moveToRecycleBin(entry: DeleteEntry) {
        val binDir = AppDataPaths.recycleBin(context)
        val binDirFile = File(binDir)
        if (!binDirFile.exists()) binDirFile.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val binName = "${entry.name}_$timestamp"
        val binFile = File(binDir, binName)

        val sourceFile = File(entry.path)
        if (!sourceFile.renameTo(binFile)) {
            throw IOException("无法移动到回收站: ${entry.name}")
        }

        // 保存回收站元数据
        saveRecycleBinMeta(entry, binName)
    }

    /**
     * 永久删除文件/目录。
     */
    private fun deleteEntry(entry: DeleteEntry) {
        val file = File(entry.path)
        if (!file.exists()) return

        if (file.isDirectory) {
            // 递归删除目录内容
            deleteRecursively(file)
        } else {
            if (!file.delete()) {
                // 尝试 shell 删除
                try {
                    val escaped = entry.path.replace("'", "'\\''")
                    val (_, stderr, exitCode) = SpecialPermissionVerifier.executeRootCommandFull("rm -f '$escaped'")
                    if (exitCode != 0) {
                        throw IOException("删除失败: $stderr")
                    }
                } catch (e: Exception) {
                    if (e is IOException) throw e
                    throw IOException("删除失败: ${e.message}")
                }
            }
        }
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                throwIfCancelled()
                deleteRecursively(child)
            }
        }
        if (!file.delete()) {
            // 尝试 shell 删除
            try {
                val escaped = file.absolutePath.replace("'", "'\\''")
                val (_, stderr, exitCode) = SpecialPermissionVerifier.executeRootCommandFull("rm -rf '$escaped'")
                if (exitCode != 0) {
                    throw IOException("删除失败: $stderr")
                }
            } catch (e: Exception) {
                if (e is IOException) throw e
                throw IOException("删除失败: ${e.message}")
            }
        }
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
