package com.whmdg.mczj.tools.fileop

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException

/**
 * 复制/移动目的枚举。
 */
enum class CopyPurpose {
    COPY,
    MOVE
}

/**
 * 复制/移动任务。
 *
 * 流程：
 * 1. scan() 统计文件数 + 总大小
 * 2. 逐个源文件递归复制
 * 3. 冲突时弹窗等待用户选择（挂起等待）
 * 4. I/O 异常时终止任务 → 关闭进度条 → 弹出报错窗口
 * 5. 每个文件复制后回调进度
 * 6. 如果是移动目的，复制成功后立即删除源文件
 */
class CopyJob(
    private val purpose: CopyPurpose,
    private val sources: List<String>,
    private val targetDir: String,
    private val manager: FileOperationManager
) : FileOperationJob() {

    /** 异常时需要清理的残留目标文件路径 */
    @Volatile
    private var pendingCleanupTarget: String? = null

    /** 根据目的获取阶段文字 */
    private val phaseName: String
        get() = if (purpose == CopyPurpose.MOVE) "正在移动" else "正在复制"

    @Throws(Exception::class)
    override fun run() {
        var errorToShow: Exception? = null
        try {
            // 移动目的：渐进式分区检测，分离可以 mv 和需要复制+删除的节点
            if (purpose == CopyPurpose.MOVE) {
                val (movable, needCopy) = partitionSourcesByDevice(sources, targetDir)

                // 先处理可以 mv 的节点
                if (movable.isNotEmpty()) {
                    moveWithMv(movable, targetDir)
                }

                // 如果没有需要复制+删除的节点，直接返回
                if (needCopy.isEmpty()) {
                    return
                }

                // 更新 sources 为需要复制+删除的节点，继续走复制+删除链路
                // 注意：这里需要递归调用或修改后续逻辑
                // 为了保持代码简洁，我们直接处理 needCopy 列表
                processCopyAndDelete(needCopy, targetDir)
                return
            }

            // 复制目的：直接走复制链路
            processCopyAndDelete(sources, targetDir)
        } catch (e: Exception) {
            errorToShow = e
        } finally {
            // 清除线程中断标志，确保后续清理代码能正常执行 shell 命令
            Thread.interrupted()

            if (cancelFlag.get()) {
                // 步骤一：用户手动取消（文件描述符失效 + 用户取消为真）
                // 1. 面板改为"正在取消"
                manager.updateProgress(FileOpProgress(
                    phase = "正在取消",
                    currentBytes = 0,
                    totalBytes = 0,
                    isRunning = true
                ))
                // 2. 后台清理残留文件
                pendingCleanupTarget?.let { target ->
                    try {
                        if (operator.exists(target)) operator.deleteFile(target)
                    } catch (_: Exception) {}
                    pendingCleanupTarget = null
                }
                // 3. 清理完毕，关闭窗口
                manager.updateProgress(null)
                manager.notifyRefreshNeeded()
            } else {
                // 步骤二：其他错误
                // 1. 清理残留文件
                pendingCleanupTarget?.let { target ->
                    try {
                        if (operator.exists(target)) operator.deleteFile(target)
                    } catch (_: Exception) {}
                    pendingCleanupTarget = null
                }
                // 2. 关闭进度条
                manager.updateProgress(null)
                manager.notifyRefreshNeeded()
                // 3. 打开报错弹窗
                if (errorToShow != null) {
                    val errorMsg = if (purpose == CopyPurpose.MOVE) "移动失败" else "复制失败"
                    runBlocking {
                        manager.resolveError(ErrorRequest(
                            fileName = "",
                            errorMessage = errorToShow!!.message ?: errorMsg
                        ))
                    }
                }
            }
        }
    }

    /**
     * 处理复制+删除逻辑（复制目的，或移动目的中需要跨分区的节点）。
     */
    private fun processCopyAndDelete(nodesToProcess: List<String>, targetDir: String) {
        // 1. 扫描（实时回调已扫描字节数）
        val scanInfo = scanWithProgress(nodesToProcess) { totalSoFar ->
            manager.updateProgress(FileOpProgress(
                phase = phaseName,
                currentBytes = 0,
                totalBytes = totalSoFar,
                isScanning = true
            ))
        }
        var transferredBytes = 0L
        var transferredFiles = 0

        manager.updateProgress(FileOpProgress(
            phase = phaseName,
            currentBytes = 0,
            totalBytes = scanInfo.totalBytes,
            currentFileName = "",
            fileIndex = 0,
            fileCount = scanInfo.fileCount
        ))

        // 2. 逐个源文件复制
        for (source in nodesToProcess) {
            val targetName = source.substringAfterLast('/')
            val sourceParent = source.substringBeforeLast('/')
            val sourceIsDir = operator.isDirectory(source)
            val targetPath = if (targetDir.trimEnd('/') == sourceParent) {
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
    }

    private data class CopyResult(val bytes: Long, val files: Int)

    /**
     * 递归复制单个源文件/目录到目标路径。
     * 异常直接传播到 run()，由 run() 统一处理。
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

            // 移动目的：删除空源目录
            if (purpose == CopyPurpose.MOVE) {
                try {
                    if (operator.listChildren(source)?.isEmpty() == true) {
                        operator.deleteFile(source)
                    }
                } catch (_: Exception) {
                    // 删除失败不致命
                }
            }
        } else {
            // 文件处理
            val resolvedTarget = resolveConflictIfNeeded(source, sourceName, target, isDirectory = false)
                ?: return CopyResult(0, 0) // 用户选择跳过

            // 复制文件
            pendingCleanupTarget = resolvedTarget
            val fileSize = operator.fileSize(source)
            currentStep = if (purpose == CopyPurpose.MOVE) "移动: $sourceName" else "复制: $sourceName"
            operator.copyFile(source, resolvedTarget, onProgress = { copied ->
                heartbeat()
                manager.updateProgress(FileOpProgress(
                    phase = phaseName,
                    currentBytes = baseBytes + totalCopiedBytes + copied,
                    totalBytes = scanInfo.totalBytes,
                    currentFileName = sourceName,
                    fileIndex = baseFiles + totalCopiedFiles,
                    fileCount = scanInfo.fileCount
                ))
            }, job = this)
            pendingCleanupTarget = null

            // 移动目的：复制成功后立即删除源文件
            if (purpose == CopyPurpose.MOVE) {
                currentStep = "删除: $sourceName"
                operator.deleteFile(source)
            }

            totalCopiedBytes += fileSize
            totalCopiedFiles++
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
     * 渐进式分区检测：找到可以 mv 的最大子树和需要复制+删除的节点。
     * 每个节点独立追踪深度，最多检查到深度3。
     *
     * @return Pair(可以mv的节点列表, 需要复制+删除的节点列表)
     */
    private fun partitionSourcesByDevice(sources: List<String>, targetDir: String): Pair<List<String>, List<String>> {
        val movable = mutableListOf<String>()
        val needCopy = mutableListOf<String>()

        try {
            val targetDevice = operator.deviceId(targetDir)

            // 待检查队列：(路径, 深度)
            val queue = ArrayDeque<Pair<String, Int>>()
            for (source in sources) {
                queue.add(Pair(source, 1))
            }

            while (queue.isNotEmpty()) {
                val (path, depth) = queue.removeFirst()
                val device = operator.deviceId(path)

                if (device == targetDevice) {
                    // 同一分区：这个节点及其所有后代都可以 mv
                    movable.add(path)
                } else if (depth < 3 && operator.isDirectory(path)) {
                    // 不在同一分区，且深度未达上限，且是目录：检查子节点
                    val children = operator.listChildren(path)
                    if (children != null) {
                        for (child in children) {
                            queue.add(Pair(child.path, depth + 1))
                        }
                    }
                } else {
                    // 深度达到3，或是文件：走复制+删除
                    needCopy.add(path)
                }
            }
        } catch (_: Exception) {
            // 获取设备号失败，全部走复制+删除
            needCopy.addAll(sources)
        }

        return Pair(movable, needCopy)
    }

    /**
     * 对可以 mv 的节点执行快速移动。
     */
    private fun moveWithMv(nodes: List<String>, targetDir: String) {
        val totalNodes = nodes.size
        var processedNodes = 0

        manager.updateProgress(FileOpProgress(
            phase = "正在移动",
            currentBytes = 0,
            totalBytes = totalNodes.toLong(),
            currentFileName = "",
            fileIndex = 0,
            fileCount = totalNodes,
            isScanning = false
        ))

        for (node in nodes) {
            throwIfCancelled()

            val targetName = node.substringAfterLast('/')
            val sourceParent = node.substringBeforeLast('/')
            val sourceIsDir = operator.isDirectory(node)
            val targetPath = if (targetDir.trimEnd('/') == sourceParent) {
                generateUniqueName(targetDir, targetName, sourceIsDir)
            } else {
                "$targetDir/$targetName"
            }

            // 冲突检查
            val resolvedTarget = resolveConflictIfNeeded(node, targetName, targetPath, isDirectory = sourceIsDir)
                ?: continue

            // 执行 mv
            currentStep = "移动: $targetName"
            val success = operator.moveFile(node, resolvedTarget, job = this)
            if (!success) {
                throw IOException("移动失败: $targetName")
            }

            processedNodes++
            manager.updateProgress(FileOpProgress(
                phase = "正在移动",
                currentBytes = processedNodes.toLong(),
                totalBytes = totalNodes.toLong(),
                currentFileName = targetName,
                fileIndex = processedNodes,
                fileCount = totalNodes
            ))
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
