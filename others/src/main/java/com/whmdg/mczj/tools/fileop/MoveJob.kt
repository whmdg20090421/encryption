package com.whmdg.mczj.tools.fileop

import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/**
 * 移动任务。
 *
 * 流程：
 * 1. 逐个源文件尝试原子 renameTo（同分区瞬间完成）
 * 2. rename 失败的加入跨分区列表
 * 3. 跨分区文件：scan → walkFileTree 递归复制 → 删除源
 * 4. 冲突时通过 FileOperationManager 弹窗等待用户选择
 */
class MoveJob(
    private val sources: List<String>,
    private val targetDir: String,
    private val manager: FileOperationManager
) : FileOperationJob() {

    private var skipAllErrors = false

    @Throws(Exception::class)
    override fun run() {
        // 阶段 1：尝试原子移动
        val crossPartitionSources = mutableListOf<String>()
        for (source in sources) {
            throwIfCancelled()
            val sourceFile = File(source)
            val targetPath = "$targetDir/${sourceFile.name}"

            // 同目录检查
            if (File(targetDir).canonicalPath == sourceFile.parentFile?.canonicalPath) {
                // 同目录移动 = 重命名（如果名称不同）
                if (sourceFile.name != File(targetPath).name) {
                    try {
                        if (!operator.moveFile(source, targetPath)) {
                            // renameTo 失败，加入跨分区列表
                            crossPartitionSources.add(source)
                        }
                    } catch (e: InterruptedIOException) {
                        throw e
                    } catch (_: Exception) {
                        crossPartitionSources.add(source)
                    }
                }
                continue
            }

            // 尝试原子移动
            try {
                val success = operator.moveFile(source, targetPath)
                if (!success) {
                    crossPartitionSources.add(source)
                }
            } catch (e: InterruptedIOException) {
                throw e
            } catch (_: Exception) {
                crossPartitionSources.add(source)
            }
        }

        // 阶段 2：跨分区文件需要 copy + delete
        if (crossPartitionSources.isEmpty()) {
            manager.updateProgress(null)
            manager.notifyRefreshNeeded()
            return
        }

        // 扫描跨分区文件
        val scanInfo = scan(crossPartitionSources)
        var transferredBytes = 0L
        var transferredFiles = 0

        manager.updateProgress(FileOpProgress(
            phase = "正在移动",
            currentBytes = 0,
            totalBytes = scanInfo.totalBytes,
            currentFileName = "",
            fileIndex = 0,
            fileCount = scanInfo.fileCount
        ))

        // 逐个源文件复制+删除
        for (source in crossPartitionSources) {
            val sourceFile = File(source)
            val targetPath = "$targetDir/${sourceFile.name}"
            val result = moveRecursively(source, targetPath, scanInfo, transferredBytes, transferredFiles)
            transferredBytes += result.bytes
            transferredFiles += result.files
            throwIfCancelled()
        }

        manager.updateProgress(null)
        manager.notifyRefreshNeeded()
    }

    private data class MoveResult(val bytes: Long, val files: Int)

    /**
     * 递归移动单个源文件/目录到目标路径（copy + delete）。
     */
    private fun moveRecursively(
        source: String,
        target: String,
        scanInfo: ScanInfo,
        baseBytes: Long,
        baseFiles: Int
    ): MoveResult {
        val sourceFile = File(source)
        if (!sourceFile.exists()) return MoveResult(0, 0)

        var totalBytes = 0L
        var totalFiles = 0

        if (sourceFile.isDirectory) {
            // 目录：先尝试原子 rename 整个目录
            try {
                val success = operator.moveFile(source, target)
                if (success) {
                    // 整个目录瞬间移动完成
                    val dirSize = calculateDirSize(sourceFile)
                    return MoveResult(dirSize, 1)
                }
            } catch (_: Exception) {
                // rename 失败，回退到逐项处理
            }

            // 冲突检查
            val resolvedTarget = resolveConflictIfNeeded(sourceFile, target, isDirectory = true)
                ?: return MoveResult(0, 0)

            // 确保目标目录存在
            val targetDirFile = File(resolvedTarget)
            if (!targetDirFile.exists()) {
                operator.mkdir(resolvedTarget)
            }

            // 递归子项
            val children = operator.listChildren(source) ?: return MoveResult(0, 0)
            for (child in children) {
                throwIfCancelled()
                val childTarget = "$resolvedTarget/${child.name}"
                val result = moveRecursively(child.path, childTarget, scanInfo, baseBytes + totalBytes, baseFiles + totalFiles)
                totalBytes += result.bytes
                totalFiles += result.files
            }

            // 删除空源目录
            try {
                if (operator.listChildren(source)?.isEmpty() == true) {
                    operator.deleteFile(source)
                }
            } catch (_: Exception) {
                // 删除失败不致命
            }
        } else {
            // 文件：冲突检查
            val resolvedTarget = resolveConflictIfNeeded(sourceFile, target, isDirectory = false)
                ?: return MoveResult(0, 0)

            // 复制 + 删除，带重试
            var retry: Boolean
            do {
                retry = false
                try {
                    val fileSize = operator.fileSize(source)
                    operator.copyFile(source, resolvedTarget) { copied ->
                        manager.updateProgress(FileOpProgress(
                            phase = "正在移动",
                            currentBytes = baseBytes + totalBytes + copied,
                            totalBytes = scanInfo.totalBytes,
                            currentFileName = sourceFile.name,
                            fileIndex = baseFiles + totalFiles,
                            fileCount = scanInfo.fileCount
                        ))
                    }
                    // 复制成功，删除源文件
                    try {
                        operator.deleteFile(source)
                    } catch (e: Exception) {
                        // 删除失败，尝试回滚（删除已复制的目标文件）
                        try {
                            operator.deleteFile(resolvedTarget)
                        } catch (_: Exception) {}
                        throw e
                    }
                    totalBytes += fileSize
                    totalFiles++
                } catch (e: InterruptedIOException) {
                    throw e
                } catch (e: Exception) {
                    if (skipAllErrors) {
                        return MoveResult(totalBytes, totalFiles)
                    }
                    val result = runBlocking {
                        manager.resolveError(ErrorRequest(
                            fileName = sourceFile.name,
                            errorMessage = e.message ?: "移动失败"
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

        return MoveResult(totalBytes, totalFiles)
    }

    /**
     * 如果目标已存在，弹出冲突对话框等待用户选择。
     * 返回 null 表示用户选择跳过/CANCEL。
     */
    private fun resolveConflictIfNeeded(
        sourceFile: File,
        target: String,
        isDirectory: Boolean
    ): String? {
        val targetFile = File(target)
        if (!targetFile.exists()) return target

        // 目录→目录：合并
        if (isDirectory && targetFile.isDirectory) return target

        val request = ConflictRequest(
            sourceName = sourceFile.name,
            targetName = targetFile.name,
            isDirectory = isDirectory,
            sourceSize = if (isDirectory) 0L else operator.fileSize(sourceFile.absolutePath),
            targetSize = if (targetFile.isDirectory) 0L else operator.fileSize(target),
            sourceModifiedTime = operator.lastModified(sourceFile.absolutePath),
            targetModifiedTime = operator.lastModified(target)
        )

        val result = runBlocking {
            manager.resolveConflict(request)
        }

        return when (result.action) {
            ConflictAction.REPLACE -> target
            ConflictAction.RENAME -> {
                val newName = result.newName ?: generateUniqueName(
                    File(target).parent ?: targetDir,
                    sourceFile.name,
                    isDirectory
                )
                val parent = File(target).parent ?: targetDir
                "$parent/$newName"
            }
            ConflictAction.SKIP -> null
            ConflictAction.CANCEL -> throw InterruptedIOException("用户取消")
        }
    }

    private fun scan(sources: List<String>): ScanInfo {
        var fileCount = 0
        var totalBytes = 0L

        for (source in sources) {
            throwIfCancelled()
            val file = File(source)
            if (!file.exists()) continue

            if (file.isDirectory) {
                val stack = ArrayDeque<File>()
                stack.add(file)
                while (stack.isNotEmpty()) {
                    throwIfCancelled()
                    val f = stack.removeLast()
                    if (f.isDirectory) {
                        f.listFiles()?.forEach { stack.add(it) }
                    } else {
                        fileCount++
                        totalBytes += f.length()
                    }
                }
            } else {
                fileCount++
                totalBytes += file.length()
            }
        }

        return ScanInfo(fileCount, totalBytes)
    }

    private fun generateUniqueName(dir: String, name: String, isDirectory: Boolean): String {
        val file = File(dir, name)
        if (!file.exists()) return "$dir/$name"

        val baseName: String
        val extension: String
        if (isDirectory) {
            baseName = name
            extension = ""
        } else {
            val dotIndex = name.lastIndexOf('.')
            if (dotIndex > 0) {
                baseName = name.substring(0, dotIndex)
                extension = name.substring(dotIndex)
            } else {
                baseName = name
                extension = ""
            }
        }

        var i = 2
        while (true) {
            val candidate = "$dir/$baseName ($i)$extension"
            if (!File(candidate).exists()) return candidate
            i++
        }
    }

    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (f.isDirectory) {
                f.listFiles()?.forEach { stack.add(it) }
            } else {
                size += f.length()
            }
        }
        return size
    }
}
