package com.whmdg.mczj.tools.fileop

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.util.SevenZipCommand
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
        try {
            var totalSize = 0L
            for (entry in entries) {
                totalSize += calculateTotalSize(entry.path)
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

                val entrySize = entry.size.takeIf { it > 0 } ?: calculateTotalSize(entry.path)

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
            manager.updateProgress(null)
            manager.notifyRefreshNeeded()
        }
    }

    /**
     * 将文件移到回收站。
     * 回收站路径：<internal_files>/.recycle_bin/
     * 通过 operator（Permission.MAX）执行 mv，确保 ROOT-only 文件也能移动。
     */
    private fun moveToRecycleBin(entry: DeleteEntry) {
        val binDir = AppDataPaths.recycleBin(context)
        operator.mkdir(binDir.absolutePath)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val binName = "${entry.name}_$timestamp"
        val binPath = "${binDir.absolutePath}/$binName"

        // 通过 shell mv 移动（源可能在 ROOT-only 目录）
        val escaped_src = SevenZipCommand.escape(entry.path)
        val escaped_dst = SevenZipCommand.escape(binPath)
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
