package com.whmdg.mczj.tools.fileop

import kotlinx.coroutines.runBlocking
import java.io.InterruptedIOException

/**
 * 复制任务。
 *
 * 流程：
 * 1. scan() 统计文件数 + 总大小
 * 2. 逐个源文件 walkFileTree 递归复制
 * 3. 冲突时通过 FileOperationManager 弹窗等待用户选择
 * 4. 每个文件复制后回调进度
 */
class CopyJob(
    private val sources: List<String>,
    private val targetDir: String,
    private val manager: FileOperationManager
) : FileOperationJob() {

    /** 应用到全部的状态 */
    private var skipAllErrors = false

    @Throws(Exception::class)
    override fun run() {
        // 1. 扫描（实时回调已扫描字节数）
        val scanInfo = scanWithProgress(sources) { totalSoFar ->
            manager.updateProgress(FileOpProgress(
                phase = "正在复制",
                currentBytes = 0,
                totalBytes = totalSoFar,
                isScanning = true
            ))
        }
        var transferredBytes = 0L
        var transferredFiles = 0

        manager.updateProgress(FileOpProgress(
            phase = "正在复制",
            currentBytes = 0,
            totalBytes = scanInfo.totalBytes,
            currentFileName = "",
            fileIndex = 0,
            fileCount = scanInfo.fileCount
        ))

        // 2. 逐个源文件复制
        for (source in sources) {
            val targetName = source.substringAfterLast('/')
            val sourceParent = source.substringBeforeLast('/')
            val sourceIsDir = operator.isDirectory(source)
            val targetPath = if (targetDir.trimEnd('/') == sourceParent) {
                // 同目录：自动重命名
                generateUniqueName(targetDir, targetName, sourceIsDir)
            } else {
                "$targetDir/$targetName"
            }
            val result = copyRecursively(source, targetPath, scanInfo, transferredBytes, transferredFiles)
            transferredBytes += result.bytes
            transferredFiles += result.files
            if (isGracefulCancelled()) break
            throwIfCancelled()
        }

        // 3. 完成
        manager.updateProgress(null)
        manager.notifyRefreshNeeded()
    }

    private data class CopyResult(val bytes: Long, val files: Int)

    /**
     * 递归复制单个源文件/目录到目标路径。
     */
    private fun copyRecursively(
        source: String,
        target: String,
        scanInfo: ScanInfo,
        baseBytes: Long,
        baseFiles: Int
    ): CopyResult {
        if (!operator.exists(source)) return CopyResult(0, 0)

        var totalCopiedBytes = 0L
        var totalCopiedFiles = 0
        val sourceName = source.substringAfterLast('/')

        if (operator.isDirectory(source)) {
            // 目录处理
            val resolvedTarget = resolveConflictIfNeeded(source, sourceName, target, isDirectory = true)
                ?: return CopyResult(0, 0) // 用户选择跳过

            // 确保目标目录存在
            if (!operator.exists(resolvedTarget)) {
                operator.mkdir(resolvedTarget)
            }

            // 递归子项
            val children = operator.listChildren(source) ?: return CopyResult(0, 0)
            for (child in children) {
                throwIfCancelled()
                val childTarget = "$resolvedTarget/${child.name}"
                val result = copyRecursively(child.path, childTarget, scanInfo, baseBytes + totalCopiedBytes, baseFiles + totalCopiedFiles)
                totalCopiedBytes += result.bytes
                totalCopiedFiles += result.files
            }
        } else {
            // 文件处理
            val resolvedTarget = resolveConflictIfNeeded(source, sourceName, target, isDirectory = false)
                ?: return CopyResult(0, 0) // 用户选择跳过

            // 复制文件，带重试
            var retry: Boolean
            do {
                retry = false
                try {
                    val fileSize = operator.fileSize(source)
                    operator.copyFile(source, resolvedTarget) { copied ->
                        manager.updateProgress(FileOpProgress(
                            phase = "正在复制",
                            currentBytes = baseBytes + totalCopiedBytes + copied,
                            totalBytes = scanInfo.totalBytes,
                            currentFileName = sourceName,
                            fileIndex = baseFiles + totalCopiedFiles,
                            fileCount = scanInfo.fileCount
                        ))
                    }
                    totalCopiedBytes += fileSize
                    totalCopiedFiles++
                } catch (e: InterruptedIOException) {
                    throw e
                } catch (e: Exception) {
                    if (skipAllErrors) {
                        return CopyResult(totalCopiedBytes, totalCopiedFiles)
                    }
                    val result = runBlocking {
                        manager.resolveError(ErrorRequest(
                            fileName = sourceName,
                            errorMessage = e.message ?: "复制失败"
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

        return CopyResult(totalCopiedBytes, totalCopiedFiles)
    }

    /**
     * 如果目标已存在，弹出冲突对话框等待用户选择。
     * 返回 null 表示用户选择跳过/CANCEL。
     */
    private fun resolveConflictIfNeeded(
        sourcePath: String,
        sourceName: String,
        target: String,
        isDirectory: Boolean
    ): String? {
        if (!operator.exists(target)) return target

        // 目录→目录：合并（直接进入递归）
        if (isDirectory && operator.isDirectory(target)) return target

        // 冲突：弹窗
        val request = ConflictRequest(
            sourceName = sourceName,
            targetName = target.substringAfterLast('/'),
            isDirectory = isDirectory,
            sourceSize = if (isDirectory) 0L else operator.fileSize(sourcePath),
            targetSize = if (operator.isDirectory(target)) 0L else operator.fileSize(target),
            sourceModifiedTime = operator.lastModified(sourcePath),
            targetModifiedTime = operator.lastModified(target)
        )

        val result = runBlocking {
            manager.resolveConflict(request)
        }

        return when (result.action) {
            ConflictAction.REPLACE -> target
            ConflictAction.RENAME -> {
                val parent = target.substringBeforeLast('/')
                val newName = result.newName ?: generateUniqueName(parent, sourceName, isDirectory)
                "$parent/$newName"
            }
            ConflictAction.SKIP -> null
            ConflictAction.CANCEL -> throw InterruptedIOException("用户取消")
        }
    }

    /**
     * 生成不重复的文件名。同目录下已存在同名文件时添加 (2)、(3) 等后缀。
     */
    private fun generateUniqueName(dir: String, name: String, isDirectory: Boolean): String {
        if (!operator.exists("$dir/$name")) return "$dir/$name"

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
            if (!operator.exists(candidate)) return candidate
            i++
        }
    }
}

data class ScanInfo(val fileCount: Int, val totalBytes: Long)
