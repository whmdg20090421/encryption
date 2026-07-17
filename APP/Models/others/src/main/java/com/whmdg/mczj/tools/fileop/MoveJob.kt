package com.whmdg.mczj.tools.fileop

import kotlinx.coroutines.runBlocking
import java.io.InterruptedIOException

/**
 * 移动任务。
 *
 * 流程：
 * 1. 扫描源文件统计总数和大小
 * 2. 逐个源文件复制+删除
 * 3. 冲突时弹窗等待用户选择（挂起等待）
 * 4. I/O 异常时终止任务 → 关闭进度条 → 弹出报错窗口
 */
class MoveJob(
    private val sources: List<String>,
    private val targetDir: String,
    private val manager: FileOperationManager
) : FileOperationJob() {

    @Throws(Exception::class)
    override fun run() {
        var errorToShow: Exception? = null
        try {
            // 1. 扫描（实时回调已扫描字节数）
            val scanInfo = scanWithProgress(sources) { totalSoFar ->
                manager.updateProgress(FileOpProgress(
                    phase = "正在移动",
                    currentBytes = 0,
                    totalBytes = totalSoFar,
                    isScanning = true
                ))
            }
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

            // 2. 逐个源文件移动
            for (source in sources) {
                val targetPath = "$targetDir/${source.substringAfterLast('/')}"
                val result = moveRecursively(source, targetPath, scanInfo, transferredBytes, transferredFiles)
                transferredBytes += result.bytes
                transferredFiles += result.files
                if (isGracefulCancelled()) break
                throwIfCancelled()
            }
        } catch (e: Exception) {
            errorToShow = e
        } finally {
            // 1. 终止任务，关闭进度条
            manager.updateProgress(null)
            manager.notifyRefreshNeeded()
            // 2. 打开报错弹窗（用户主动取消导致的异常不弹）
            if (errorToShow != null && !cancelFlag.get() && errorToShow !is InterruptedIOException) {
                runBlocking {
                    manager.resolveError(ErrorRequest(
                        fileName = "",
                        errorMessage = errorToShow!!.message ?: "移动失败"
                    ))
                }
            }
        }
    }

    private data class MoveResult(val bytes: Long, val files: Int)

    /**
     * 递归移动单个源文件/目录到目标路径。
     * 异常直接传播到 run()，由 run() 统一处理。
     */
    private fun moveRecursively(
        source: String,
        target: String,
        scanInfo: ScanInfo,
        baseBytes: Long,
        baseFiles: Int
    ): MoveResult {
        if (!operator.exists(source)) return MoveResult(0, 0)

        var totalBytes = 0L
        var totalFiles = 0
        val sourceName = source.substringAfterLast('/')

        if (operator.isDirectory(source)) {
            // 目录：冲突检查
            val resolvedTarget = resolveConflictIfNeeded(source, sourceName, target, isDirectory = true)
                ?: return MoveResult(0, 0)

            // 确保目标目录存在
            if (!operator.exists(resolvedTarget)) {
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
            val resolvedTarget = resolveConflictIfNeeded(source, sourceName, target, isDirectory = false)
                ?: return MoveResult(0, 0)

            // 使用 moveFile（复制+删除）
            val fileSize = operator.fileSize(source)
            currentStep = "移动: $sourceName"
            val success = operator.moveFile(source, resolvedTarget, job = this)
            heartbeat()
            if (success) {
                totalBytes += fileSize
                totalFiles++
            }
        }

        return MoveResult(totalBytes, totalFiles)
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

        // 目录→目录：合并
        if (isDirectory && operator.isDirectory(target)) return target

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
