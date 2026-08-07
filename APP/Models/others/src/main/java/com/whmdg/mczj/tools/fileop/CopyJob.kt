package com.whmdg.mczj.tools.fileop

import android.content.Context
import com.whmdg.mczj.tools.encryption.services.CryptoService
import com.whmdg.mczj.tools.encryption.services.VaultSession
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicLong

/**
 * 复制/移动目的枚举。
 */
enum class CopyPurpose {
    COPY,
    MOVE
}

/**
 * 复制/移动操作中涉及保险箱时的上下文信息。
 *
 * CopyJob 根据此类型决定走字节复制、加密引入、解密导出、还是跨箱转码。
 */
sealed class VaultOperationContext {

    /** 不涉及保险箱，走原始字节复制/移动 */
    data object None : VaultOperationContext()

    /**
     * 外部文件 → 保险箱（加密引入）。
     * @param targetSession 目标保险箱的会话
     * @param targetSubDir 目标相对于 vaultDir 的子路径（空表示根目录）
     */
    data class ExternalToVault(
        val targetSession: VaultSession,
        val targetSubDir: String
    ) : VaultOperationContext()

    /**
     * 保险箱 → 外部（解密导出）。
     * @param sourceSession 源保险箱的会话
     */
    data class VaultToExternal(
        val sourceSession: VaultSession
    ) : VaultOperationContext()

    /**
     * 同一保险箱内部复制/移动（已是同密钥密文，直接字节操作）。
     */
    data object SameVault : VaultOperationContext()

    /**
     * 跨保险箱操作（解密 → 重新加密）。
     * @param sourceSession 源保险箱的会话
     * @param targetSession 目标保险箱的会话
     * @param targetSubDir 目标相对于 vaultDir 的子路径
     */
    data class CrossVault(
        val sourceSession: VaultSession,
        val targetSession: VaultSession,
        val targetSubDir: String
    ) : VaultOperationContext()
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
    private val manager: FileOperationManager,
    private val context: Context,
    private val vaultContext: VaultOperationContext = VaultOperationContext.None
) : FileOperationJob() {

    /** 异常时需要清理的残留目标文件路径 */
    @Volatile
    private var pendingCleanupTarget: String? = null

    // ── 保险箱存储用量 delta 追踪 ──
    private val vaultBytesAdded = AtomicLong(0)
    private val vaultBytesRemoved = AtomicLong(0)

    /** 根据目的获取阶段文字 */
    private val phaseName: String
        get() = if (purpose == CopyPurpose.MOVE) "正在移动" else "正在复制"

    /** 报告保险箱存储用量变更 */
    private fun reportVaultSizeChange() {
        val added = vaultBytesAdded.get()
        val removed = vaultBytesRemoved.get()
        when (vaultContext) {
            is VaultOperationContext.ExternalToVault -> {
                if (added > 0) manager.notifyVaultSizeChange(vaultContext.targetSession.record.id, added)
            }
            is VaultOperationContext.VaultToExternal -> {
                if (removed > 0) manager.notifyVaultSizeChange(vaultContext.sourceSession.record.id, -removed)
            }
            is VaultOperationContext.CrossVault -> {
                if (added > 0) manager.notifyVaultSizeChange(vaultContext.targetSession.record.id, added)
                if (removed > 0) manager.notifyVaultSizeChange(vaultContext.sourceSession.record.id, -removed)
            }
            else -> return
        }
    }

    @Throws(Exception::class)
    override fun run() {
        var errorToShow: Exception? = null
        try {
            when (vaultContext) {
                is VaultOperationContext.SameVault -> {
                    // 同一保险箱内部：已是同密钥密文，直接字节操作
                    runNormalCopy()
                }
                is VaultOperationContext.ExternalToVault -> {
                    copyExternalToVault(vaultContext)
                }
                is VaultOperationContext.VaultToExternal -> {
                    copyVaultToExternal(vaultContext)
                }
                is VaultOperationContext.CrossVault -> {
                    copyCrossVault(vaultContext)
                }
                is VaultOperationContext.None -> {
                    runNormalCopy()
                }
            }
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
                reportVaultSizeChange()
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
                reportVaultSizeChange()
                // 3. 打开报错弹窗
                if (errorToShow != null) {
                    val errorMsg = if (purpose == CopyPurpose.MOVE) "移动失败" else "复制失败"
                    val detail = buildString {
                        var e: Throwable? = errorToShow
                        while (e != null) {
                            if (isNotEmpty()) append("\n\nCaused by: ")
                            append("${e.javaClass.simpleName}: ${e.message}")
                            e = e.cause
                        }
                    }
                    runBlocking {
                        manager.resolveError(ErrorRequest(
                            fileName = "",
                            errorMessage = errorToShow!!.message ?: errorMsg,
                            detailMessage = detail
                        ))
                    }
                }
            }
        }
    }

    /** 原始字节复制/移动逻辑（不含 vault 或同 vault）。 */
    private fun runNormalCopy() {
        // 移动目的：先统一扫描，再分区处理
        if (purpose == CopyPurpose.MOVE) {
            val scanInfo = scanWithProgress(sources) { totalSoFar ->
                manager.updateProgress(FileOpProgress(
                    phase = "正在移动",
                    currentBytes = 0,
                    totalBytes = totalSoFar,
                    isScanning = true
                ))
            }

            val (movable, needCopy) = partitionSourcesByDevice(sources, targetDir)

            if (movable.isNotEmpty()) {
                moveWithMv(movable, targetDir, scanInfo)
            }

            if (needCopy.isEmpty()) {
                return
            }

            processCopyAndDelete(needCopy, targetDir)
            return
        }

        // 复制目的：直接走复制链路
        processCopyAndDelete(sources, targetDir)
    }

    // ═══════════════════════════════════════════════════════
    //  Vault 操作：外部 → 保险箱（加密引入）
    // ═══════════════════════════════════════════════════════

    private fun copyExternalToVault(ctx: VaultOperationContext.ExternalToVault) {
        val totalSize = sources.sumOf { File(it).walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } }
        var doneBytes = 0L
        var doneFiles = 0

        manager.updateProgress(FileOpProgress(
            phase = "正在加密",
            currentBytes = 0,
            totalBytes = totalSize,
            isScanning = false,
            fileIndex = 0,
            fileCount = sources.size
        ))

        for (src in sources) {
            throwIfCancelled()
            val srcFile = File(src)
            val subDir = if (ctx.targetSubDir.isEmpty()) "" else ctx.targetSubDir
            if (srcFile.isDirectory) {
                val dirSubDir = if (subDir.isEmpty()) srcFile.name else "$subDir/${srcFile.name}"
                encryptDirToVault(srcFile, dirSubDir, ctx.targetSession, totalSize, doneBytes, doneFiles)
                doneBytes += srcFile.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                currentStep = "加密: ${srcFile.name}"
                val encrypted = CryptoService.encryptIntoVault(context, ctx.targetSession, srcFile, subDir)
                vaultBytesAdded.addAndGet(encrypted.length())
                doneBytes += srcFile.length()
            }
            doneFiles++
            manager.updateProgress(FileOpProgress(
                phase = "正在加密",
                currentBytes = doneBytes,
                totalBytes = totalSize,
                currentFileName = srcFile.name,
                fileIndex = doneFiles,
                fileCount = sources.size
            ))
            if (isGracefulCancelled()) break
        }

        if (purpose == CopyPurpose.MOVE) {
            for (src in sources) {
                throwIfCancelled()
                File(src).deleteRecursively()
            }
        }
    }

    private fun encryptDirToVault(
        dir: File,
        parentSubDir: String,
        session: VaultSession,
        totalSize: Long,
        baseBytes: Long,
        baseFiles: Int
    ) {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        var doneBytes = 0L
        for (file in files) {
            throwIfCancelled()
            val relPath = file.relativeTo(dir).parent?.replace('\\', '/') ?: ""
            val fileSubDir = if (parentSubDir.isEmpty()) relPath else {
                if (relPath.isEmpty()) parentSubDir else "$parentSubDir/$relPath"
            }
            currentStep = "加密: ${file.name}"
            val encrypted = CryptoService.encryptIntoVault(context, session, file, fileSubDir)
            vaultBytesAdded.addAndGet(encrypted.length())
            doneBytes += file.length()
            manager.updateProgress(FileOpProgress(
                phase = "正在加密",
                currentBytes = baseBytes + doneBytes,
                totalBytes = totalSize,
                currentFileName = file.name,
                fileIndex = baseFiles,
                fileCount = sources.size
            ))
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Vault 操作：保险箱 → 外部（解密导出）
    // ═══════════════════════════════════════════════════════

    private fun copyVaultToExternal(ctx: VaultOperationContext.VaultToExternal) {
        val totalSize = sources.sumOf { File(it).walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } }
        var doneBytes = 0L
        var doneFiles = 0

        manager.updateProgress(FileOpProgress(
            phase = "正在解密",
            currentBytes = 0,
            totalBytes = totalSize,
            isScanning = false,
            fileIndex = 0,
            fileCount = sources.size
        ))

        for (src in sources) {
            throwIfCancelled()
            val srcFile = File(src)
            if (srcFile.isDirectory) {
                decryptDirFromVault(srcFile, targetDir, ctx.sourceSession, totalSize, doneBytes, doneFiles)
                doneBytes += srcFile.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                currentStep = "解密: ${srcFile.name}"
                CryptoService.decryptOutOfVault(ctx.sourceSession, srcFile, File(targetDir))
                doneBytes += srcFile.length()
            }
            doneFiles++
            manager.updateProgress(FileOpProgress(
                phase = "正在解密",
                currentBytes = doneBytes,
                totalBytes = totalSize,
                currentFileName = srcFile.name,
                fileIndex = doneFiles,
                fileCount = sources.size
            ))
            if (isGracefulCancelled()) break
        }

        if (purpose == CopyPurpose.MOVE) {
            for (src in sources) {
                throwIfCancelled()
                val srcFile = File(src)
                vaultBytesRemoved.addAndGet(
                    if (srcFile.isDirectory) srcFile.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    else srcFile.length()
                )
                srcFile.deleteRecursively()
            }
        }
    }

    private fun decryptDirFromVault(
        dir: File,
        outputBase: String,
        session: VaultSession,
        totalSize: Long,
        baseBytes: Long,
        baseFiles: Int
    ) {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        var doneBytes = 0L
        for (file in files) {
            throwIfCancelled()
            val relParent = file.parentFile?.relativeTo(dir)?.path?.replace('\\', '/') ?: ""
            val outputDir = if (relParent.isEmpty()) {
                File(outputBase)
            } else {
                File(outputBase, relParent)
            }
            currentStep = "解密: ${file.name}"
            CryptoService.decryptOutOfVault(session, file, outputDir)
            doneBytes += file.length()
            manager.updateProgress(FileOpProgress(
                phase = "正在解密",
                currentBytes = baseBytes + doneBytes,
                totalBytes = totalSize,
                currentFileName = file.name,
                fileIndex = baseFiles,
                fileCount = sources.size
            ))
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Vault 操作：跨保险箱（解密 → 重新加密）
    // ═══════════════════════════════════════════════════════

    private fun copyCrossVault(ctx: VaultOperationContext.CrossVault) {
        val totalSize = sources.sumOf { File(it).walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } }
        var doneBytes = 0L
        var doneFiles = 0

        manager.updateProgress(FileOpProgress(
            phase = "正在转码",
            currentBytes = 0,
            totalBytes = totalSize,
            isScanning = false,
            fileIndex = 0,
            fileCount = sources.size
        ))

        val tempDir = File(context.cacheDir, "vault_transfer_${System.currentTimeMillis()}")

        try {
            for (src in sources) {
                throwIfCancelled()
                val srcFile = File(src)
                if (srcFile.isDirectory) {
                    copyCrossVaultDir(srcFile, ctx, tempDir, totalSize, doneBytes, doneFiles)
                    doneBytes += srcFile.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                } else {
                    currentStep = "转码: ${srcFile.name}"
                    decryptAndReEncrypt(srcFile, ctx.targetSession, ctx.targetSubDir, ctx.sourceSession, tempDir)
                    doneBytes += srcFile.length()
                }
                doneFiles++
                manager.updateProgress(FileOpProgress(
                    phase = "正在转码",
                    currentBytes = doneBytes,
                    totalBytes = totalSize,
                    currentFileName = srcFile.name,
                    fileIndex = doneFiles,
                    fileCount = sources.size
                ))
                if (isGracefulCancelled()) break
            }

            if (purpose == CopyPurpose.MOVE) {
                for (src in sources) {
                    throwIfCancelled()
                    val srcFile = File(src)
                    vaultBytesRemoved.addAndGet(
                        if (srcFile.isDirectory) srcFile.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        else srcFile.length()
                    )
                    srcFile.deleteRecursively()
                }
            }
        } finally {
            // 清理临时文件
            try { tempDir.deleteRecursively() } catch (_: Exception) {}
        }
    }

    private fun copyCrossVaultDir(
        dir: File,
        ctx: VaultOperationContext.CrossVault,
        tempDir: File,
        totalSize: Long,
        baseBytes: Long,
        baseFiles: Int
    ) {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        var doneBytes = 0L
        for (file in files) {
            throwIfCancelled()
            val relParent = file.parentFile?.relativeTo(dir)?.path?.replace('\\', '/') ?: ""
            val subDir = if (ctx.targetSubDir.isEmpty()) relParent else {
                if (relParent.isEmpty()) ctx.targetSubDir else "${ctx.targetSubDir}/$relParent"
            }
            currentStep = "转码: ${file.name}"
            decryptAndReEncrypt(file, ctx.targetSession, subDir, ctx.sourceSession, tempDir)
            doneBytes += file.length()
            manager.updateProgress(FileOpProgress(
                phase = "正在转码",
                currentBytes = baseBytes + doneBytes,
                totalBytes = totalSize,
                currentFileName = file.name,
                fileIndex = baseFiles,
                fileCount = sources.size
            ))
        }
    }

    /** 用源 session 解密单个文件到临时目录，再用目标 session 加密到目标保险箱。 */
    private fun decryptAndReEncrypt(
        srcFile: File,
        targetSession: VaultSession,
        targetSubDir: String,
        sourceSession: VaultSession,
        tempDir: File
    ) {
        tempDir.mkdirs()
        val tempFile = File(tempDir, srcFile.nameWithoutExtension + "_plain.tmp")
        try {
            // 1. 解密（临时文件名由 FileCodec 内部决定，我们只关心写出路径）
            CryptoService.decryptOutOfVault(sourceSession, srcFile, tempDir, overwrite = true)
            // 找到刚解密的文件（CryptoService 会用原始文件名）
            val decryptedFile = tempDir.listFiles()
                ?.filter { it.isFile && it.name != tempFile.name }
                ?.maxByOrNull { it.lastModified() }
                ?: throw IOException("解密临时文件未找到")

            // 2. 用目标 session 重新加密
            val subDir = if (targetSubDir.isEmpty()) "" else targetSubDir
            val encrypted = CryptoService.encryptIntoVault(context, targetSession, decryptedFile, subDir, overwrite = true)
            vaultBytesAdded.addAndGet(encrypted.length())
        } finally {
            // 清理临时解密文件
            tempDir.listFiles()?.forEach { f ->
                if (f.isFile) try { f.delete() } catch (_: Exception) {}
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  原始复制/移动逻辑（无 vault 感知）
    // ═══════════════════════════════════════════════════════

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
    private fun moveWithMv(nodes: List<String>, targetDir: String, scanInfo: ScanInfo) {
        val totalNodes = nodes.size
        var processedNodes = 0
        var movedBytes = 0L

        manager.updateProgress(FileOpProgress(
            phase = "正在移动",
            currentBytes = 0,
            totalBytes = scanInfo.totalBytes,
            currentFileName = "",
            fileIndex = 0,
            fileCount = scanInfo.fileCount,
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

            // 记录移动前的文件大小
            val fileSize = if (sourceIsDir) 0L else operator.fileSize(node)

            // 执行 mv
            currentStep = "移动: $targetName"
            try {
                operator.moveFile(node, resolvedTarget, onProgress = { copied ->
                    heartbeat()
                    manager.updateProgress(FileOpProgress(
                        phase = "正在移动",
                        currentBytes = movedBytes + copied,
                        totalBytes = scanInfo.totalBytes,
                        currentFileName = targetName,
                        fileIndex = processedNodes,
                        fileCount = scanInfo.fileCount
                    ))
                }, job = this)
            } catch (e: Exception) {
                throw IOException("移动失败: $targetName", e)
            }

            movedBytes += fileSize
            processedNodes++
            manager.updateProgress(FileOpProgress(
                phase = "正在移动",
                currentBytes = movedBytes,
                totalBytes = scanInfo.totalBytes,
                currentFileName = targetName,
                fileIndex = processedNodes,
                fileCount = scanInfo.fileCount
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
