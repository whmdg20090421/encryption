package com.whmdg.mczj.tools.fileop

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
import com.whmdg.mczj.tools.encryption.data.FolderSizeInfo
import com.whmdg.mczj.tools.encryption.core.FilenameCodec
import com.whmdg.mczj.tools.encryption.core.FileConstants
import com.whmdg.mczj.tools.encryption.core.EncryptionTraceLog
import com.whmdg.mczj.tools.encryption.services.CryptoService
import com.whmdg.mczj.tools.encryption.services.VaultSession
import com.whmdg.mczj.tools.util.AuditLog
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

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

    companion object {
        /** 多文件加密/解密的固定并发通道数。 */
        const val VAULT_CHANNEL_COUNT = 3
    }

    /** "自动应用此设置"：用户首次确认时记录选择，后续冲突自动应用 */
    @Volatile
    private var conflictAutoAction: ConflictAction? = null

    /** 保险箱目录大小累加器（cancel 时也需持久化） */
    private var folderSizeAccumulator: MutableMap<String, Long>? = null
    private var vaultDirForSave: java.io.File? = null

    /** 保险箱加密中待写入的文件路径（cancel 时需清理未完成的 .whm） */
    private val pendingVaultTargets = mutableListOf<String>()

    // ── 保险箱存储用量 delta 追踪 ──
    private val vaultBytesAdded = AtomicLong(0)
    private val vaultBytesRemoved = AtomicLong(0)
    private val vaultFilesAdded = AtomicInteger(0)
    private val vaultFilesRemoved = AtomicInteger(0)

    /** 根据目的获取阶段文字 */
    private val phaseName: String
        get() = if (purpose == CopyPurpose.MOVE) "正在移动" else "正在复制"

    // ═══════════════════════════════════════════════════════
    //  多通道并发加密/解密引擎
    // ═══════════════════════════════════════════════════════

    /** 每个通道当前正在处理的文件名（索引 = 通道号），用于 UI 多行显示。 */
    private val channelCurrentNames = arrayOfNulls<String>(VAULT_CHANNEL_COUNT)

    /** 保护通道名数组与进度字节累加。 */
    private val channelLock = Any()

    /** 全局已处理字节（所有通道累加），并发下用原子量避免进度回退。 */
    private val progressBytes = AtomicLong(0)

    /** 保证同一时刻只有一个通道占用冲突/错误弹窗，其余通道在占用前等待。 */
    private val dialogMutex = Any()

    /** 用户在错误弹窗选择"跳过全部"后置位：后续文件出错直接跳过，不再逐次弹窗。 */
    @Volatile
    private var skipAllErrors = false

    /**
     * 以固定 [VAULT_CHANNEL_COUNT] 个通道并发消费 [items]，每个通道按队列顺序取任务执行。
     *
     * - 任务数 ≤ 通道数时，多余通道空闲，等价于串行。
     * - [onItem] 内部如弹出冲突/错误弹窗，通过 [dialogMutex] 串行化：同一时刻只有一个
     *   弹窗，其余通道阻塞等待，符合"弹窗时暂停全部通道"的语义。
     * - 取消时 [shouldStop] 返回 true，各通道在下一次取任务时退出；当前进行中的任务由
     *   [onItem] 内部通过 cancelFlag 感知并抛出 InterruptedIOException。
     * - [onItem] 抛出 [SkippedException] 表示该文件被用户跳过，不影响其他任务；
     *   抛出其他异常视为致命错误，向上传播并终止整个任务。
     */
    private fun <T> runWithChannels(
        items: List<T>,
        shouldStop: () -> Boolean,
        onItem: (T, Int) -> Unit
    ) {
        if (items.isEmpty()) return
        val workers = minOf(VAULT_CHANNEL_COUNT, items.size)
        val queue = ConcurrentLinkedQueue(items)
        val pool = Executors.newFixedThreadPool(workers) { r ->
            Thread(r, "vault-channel-${channelSeq.getAndIncrement()}")
        }
        try {
            val tasks = (0 until workers).map { channelId ->
                pool.submit {
                    while (!shouldStop()) {
                        val item = queue.poll() ?: break
                        if (shouldStop()) break
                        try {
                            onItem(item, channelId)
                        } catch (_: SkippedException) {
                            // 用户跳过该文件，继续处理下一个
                        } finally {
                            setChannelName(channelId, null)
                        }
                    }
                }
            }
            // 传播首个致命错误（若有），并等待所有通道结束
            var fatal: Exception? = null
            for (task in tasks) {
                try {
                    task.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    val cause = e.cause
                    if (cause is Exception && fatal == null) fatal = cause
                } catch (_: InterruptedException) {
                    // 强制取消（cancelHard）中断等待线程：立即停止等待，由取消流程统一清理
                    break
                }
            }
            if (fatal != null) throw fatal
        } finally {
            // 无论正常结束还是被中断，都必须等待所有通道线程真正退出，
            // 否则后续的残留文件清理会与通道内正在进行的 .part 写入发生竞态。
            pool.shutdownNow()
            try {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    // 通道未能及时退出（如 native I/O 卡死）：放弃等待，由上层清理兜底
                    pool.shutdownNow()
                }
            } catch (_: InterruptedException) {
                // 复用中断状态：再次等待一小段时间让通道响应中断后退出
                try {
                    pool.awaitTermination(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private val channelSeq = AtomicInteger(0)

    /** 设置某通道当前文件名并刷新进度；[name] 为 null 表示该通道空闲。 */
    private fun setChannelName(channelId: Int, name: String?) {
        synchronized(channelLock) {
            if (channelId in channelCurrentNames.indices) channelCurrentNames[channelId] = name
        }
    }

    /** 当前所有通道正在处理的文件名快照（过滤空闲通道）。 */
    private fun activeChannelNames(): List<String> = synchronized(channelLock) {
        channelCurrentNames.filterNotNull()
    }

    /** 累加已处理字节并返回新值。 */
    private fun addProgressBytes(delta: Long): Long = progressBytes.addAndGet(delta)

    /** 报告保险箱存储用量及文件数量变更 */
    private fun reportVaultSizeChange() {
        val added = vaultBytesAdded.get()
        val removed = vaultBytesRemoved.get()
        val filesAdded = vaultFilesAdded.get()
        val filesRemoved = vaultFilesRemoved.get()
        when (vaultContext) {
            is VaultOperationContext.ExternalToVault -> {
                if (added > 0) manager.notifyVaultSizeChange(vaultContext.targetSession.record.id, added)
                if (filesAdded > 0) manager.notifyVaultFileCountChange(vaultContext.targetSession.record.id, filesAdded)
            }
            is VaultOperationContext.VaultToExternal -> {
                if (removed > 0) manager.notifyVaultSizeChange(vaultContext.sourceSession.record.id, -removed)
                if (filesRemoved > 0) manager.notifyVaultFileCountChange(vaultContext.sourceSession.record.id, -filesRemoved)
            }
            is VaultOperationContext.CrossVault -> {
                if (added > 0) manager.notifyVaultSizeChange(vaultContext.targetSession.record.id, added)
                if (removed > 0) manager.notifyVaultSizeChange(vaultContext.sourceSession.record.id, -removed)
                if (filesAdded > 0) manager.notifyVaultFileCountChange(vaultContext.targetSession.record.id, filesAdded)
                if (filesRemoved > 0) manager.notifyVaultFileCountChange(vaultContext.sourceSession.record.id, -filesRemoved)
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
            // InterruptedIOException + cancelFlag=true 表示用户取消，不作为错误处理
            if (!(e is InterruptedIOException && cancelFlag.get())) {
                errorToShow = e
            }
        } finally {
            // 清除线程中断标志，确保后续清理代码能正常执行 shell 命令
            Thread.interrupted()

            if (cancelFlag.get()) {
                // 取消时也将已累加的目录大小写入 FolderSizeDb
                val acc = folderSizeAccumulator
                val vd = vaultDirForSave
                if (acc != null && vd != null && acc.isNotEmpty()) {
                    try { saveFolderSizes(vd, acc) } catch (_: Exception) {}
                }
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
                // 清理保险箱中未完成的加密文件
                synchronized(pendingVaultTargets) {
                    for (target in pendingVaultTargets) {
                        try { File(target).delete() } catch (_: Exception) {}
                    }
                    pendingVaultTargets.clear()
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
                // 清理保险箱中未完成的加密文件
                synchronized(pendingVaultTargets) {
                    for (target in pendingVaultTargets) {
                        try { File(target).delete() } catch (_: Exception) {}
                    }
                    pendingVaultTargets.clear()
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

    /** 用 du -sb 获取路径总大小（字节）。 */
    private fun duTotalSize(vararg paths: String): Long {
        val proc = Runtime.getRuntime().exec(arrayOf("/system/bin/du", "-sb") + paths)
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return output.lines().sumOf { line ->
            line.split("\t").firstOrNull()?.trim()?.toLongOrNull() ?: 0L
        }
    }

    private fun copyExternalToVault(ctx: VaultOperationContext.ExternalToVault) {
        val trace = EncryptionTraceLog.enabled(context)
        if (trace) {
            EncryptionTraceLog.start(context, "ExternalToVault")
            EncryptionTraceLog.log("copyExternalToVault: sources=${sources.size} totalSize=${duTotalSize(*sources.toTypedArray())} target=${ctx.targetSession.vaultDir.name}")
        }
        val totalSize = duTotalSize(*sources.toTypedArray())
        // 保险箱目录大小累加器（绝对路径 → 累加大小）
        val acc = mutableMapOf<String, Long>()
        folderSizeAccumulator = acc
        vaultDirForSave = ctx.targetSession.vaultDir

        manager.updateProgress(FileOpProgress(
            phase = "正在加密",
            currentBytes = 0,
            totalBytes = totalSize,
            isScanning = false,
            fileIndex = 0,
            fileCount = sources.size
        ))

        // 展开所有源为"待加密文件队列"（目录递归展开），并发通道按队列顺序消费。
        val queue = buildList {
            for (src in sources) {
                val srcFile = File(src)
                val subDir = if (ctx.targetSubDir.isEmpty()) "" else ctx.targetSubDir
                if (srcFile.isDirectory) {
                    val dirSubDir = if (subDir.isEmpty()) srcFile.name else "$subDir/${srcFile.name}"
                    srcFile.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relPath = file.relativeTo(srcFile).parent?.replace('\\', '/') ?: ""
                        val fileSubDir = if (relPath.isEmpty()) dirSubDir else "$dirSubDir/$relPath"
                        add(EncryptItem(file, fileSubDir))
                    }
                } else {
                    add(EncryptItem(srcFile, subDir))
                }
            }
        }

        val processedFiles = AtomicInteger(0)
        // MOVE 目的下记录成功加密的源文件，仅在完成加密后删除，避免取消/失败时误删
        val movedSources = if (purpose == CopyPurpose.MOVE) java.util.concurrent.ConcurrentHashMap.newKeySet<String>() else null

        try {
            runWithChannels(
                items = queue,
                shouldStop = { isGracefulCancelled() }
            ) { item, channelId ->
                if (encryptOneFile(item, ctx.targetSession, totalSize, processedFiles, acc, channelId)) {
                    movedSources?.add(item.file.absolutePath)
                }
            }
        } finally {
            // MOVE：仅删除已成功加密的源文件，以及随之清空的目录
            if (movedSources != null) {
                for (path in movedSources) {
                    try { File(path).delete() } catch (_: Exception) {}
                }
                for (src in sources) {
                    val f = File(src)
                    if (f.isDirectory) {
                        // 仅当目录下不再有源文件残留时才删除空目录
                        val hasLeftover = f.walkTopDown().any { it.isFile }
                        if (!hasLeftover) try { f.deleteRecursively() } catch (_: Exception) {}
                    }
                }
            }
            // 写入 FolderSizeDb
            saveFolderSizes(ctx.targetSession.vaultDir, acc)
            if (trace) EncryptionTraceLog.finish()
        }
    }

    /** 待加密文件及其在保险箱内的目标子目录。 */
    private class EncryptItem(val file: File, val subDir: String)

    /**
     * 加密单个文件：冲突检查、加密、进度与目录大小累加。
     *
     * 文件级错误自动重试一次；仍失败时弹出错误弹窗（暂停其他通道）等待用户选择。
     * @return 该文件是否实际完成加密（冲突跳过或用户跳过返回 false）
     */
    private fun encryptOneFile(
        item: EncryptItem,
        session: VaultSession,
        totalSize: Long,
        processedFiles: AtomicInteger,
        acc: MutableMap<String, Long>,
        channelId: Int
    ): Boolean {
        throwIfCancelled()
        val srcFile = item.file
        // 冲突弹窗串行化：同一时刻仅一个弹窗，其余通道在此阻塞等待
        val overwrite = synchronized(dialogMutex) {
            resolveVaultConflict(session, srcFile, item.subDir)
        }
        if (!overwrite) {
            processedFiles.incrementAndGet()
            addProgressBytes(srcFile.length())
            publishEncryptProgress(totalSize, processedFiles.get())
            return false
        }

        val outName = if (session.record.encryptFilename) {
            val enc = FilenameCodec.encrypt(
                filename = srcFile.name,
                dek = session.dek,
                aad = if (session.record.customEncryption) FileConstants.aadCustomObf else null
            )
            val key = enc.mappingKey
            val value = enc.mappingValue
            if (key != null && value != null) {
                session.nameMapping.set(key, value)
                session.saveNameMapping(context)
            }
            enc.encoded
        } else {
            "${srcFile.name}.whm"
        }
        val outDir = if (item.subDir.isEmpty()) session.vaultDir else File(session.vaultDir, item.subDir)
        val pendingOut = File(outDir, outName).absolutePath
        synchronized(pendingVaultTargets) { pendingVaultTargets.add(pendingOut) }

        setChannelName(channelId, srcFile.name)
        publishEncryptProgress(totalSize, processedFiles.get())

        val encrypted = encryptWithRetry(session, srcFile, item.subDir, outName, totalSize, processedFiles)

        synchronized(pendingVaultTargets) { pendingVaultTargets.remove(pendingOut) }
        vaultBytesAdded.addAndGet(encrypted.length())
        vaultFilesAdded.incrementAndGet()
        processedFiles.incrementAndGet()
        addProgressBytes(srcFile.length())
        synchronized(acc) { accumulateFolderSize(acc, encrypted, session.vaultDir, encrypted.length()) }
        publishEncryptProgress(totalSize, processedFiles.get())
        return true
    }

    /**
     * 执行加密并对"文件级错误"自动重试一次；第二次仍失败时弹出错误弹窗（暂停其他通道
     * 等待用户选择）。用户选择重试则再试，选择跳过则抛 [SkippedException] 放弃该文件。
     */
    private fun encryptWithRetry(
        session: VaultSession,
        srcFile: File,
        subDir: String,
        outName: String,
        totalSize: Long,
        processedFiles: AtomicInteger
    ): File {
        var attempt = 0
        while (true) {
            throwIfCancelled()
            attempt++
            try {
                return CryptoService.encryptIntoVaultWithName(
                    context, session, srcFile, subDir, outName,
                    overwrite = true,
                    onProgress = { encryptedBytes, _ ->
                        publishEncryptProgress(totalSize, processedFiles.get(), encryptedBytes)
                    },
                    cancelFlag = cancelFlag
                )
            } catch (e: InterruptedException) {
                throw e
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: Exception) {
                if (skipAllErrors) {
                    processedFiles.incrementAndGet()
                    addProgressBytes(srcFile.length())
                    publishEncryptProgress(totalSize, processedFiles.get())
                    throw SkippedException()
                }
                if (attempt > 1) {
                    // 已重试过仍失败：弹窗等待用户选择（暂停其他通道）
                    val action = synchronized(dialogMutex) {
                        runBlocking {
                            manager.resolveError(ErrorRequest(
                                fileName = srcFile.name,
                                errorMessage = e.message ?: "加密失败",
                                detailMessage = "${e.javaClass.simpleName}: ${e.message}"
                            ))
                        }
                    }
                    when (action.action) {
                        ErrorAction.RETRY -> { attempt = 1 }
                        ErrorAction.SKIP -> {
                            processedFiles.incrementAndGet()
                            addProgressBytes(srcFile.length())
                            publishEncryptProgress(totalSize, processedFiles.get())
                            throw SkippedException()
                        }
                        ErrorAction.SKIP_ALL -> {
                            skipAllErrors = true
                            processedFiles.incrementAndGet()
                            addProgressBytes(srcFile.length())
                            publishEncryptProgress(totalSize, processedFiles.get())
                            throw SkippedException()
                        }
                        ErrorAction.CANCEL -> {
                            cancelFlag.set(true)
                            throw InterruptedIOException("用户取消")
                        }
                    }
                }
                // 首次失败：静默自动重试，不打扰其他通道
            }
        }
    }

    /** 发布加密总进度：currentBytes 为所有通道累加值。 */
    private fun publishEncryptProgress(totalSize: Long, doneFiles: Int, currentFileBytes: Long = 0L) {
        synchronized(channelLock) {
            manager.updateProgress(FileOpProgress(
                phase = "正在加密",
                currentBytes = progressBytes.get() + currentFileBytes,
                totalBytes = totalSize,
                fileIndex = doneFiles,
                fileCount = sources.size,
                activeFileNames = activeChannelNames()
            ))
        }
    }

    /** 文件被用户选择"跳过"时使用的内部信号。 */
    private class SkippedException : RuntimeException("用户跳过")

    /**
     * 累加加密文件的大小到保险箱目录及其所有祖先目录。
     *
     * 核心原则：保险箱中只存在加密后的 .whm 文件，因此所有大小计算
     * 必须使用加密后的文件大小（encrypted.length()），禁止使用源文件大小。
     * 源文件存在于外部存储，其大小与保险箱无关。
     *
     * 调用时机：仅在加密成功完成后调用。加密中断（取消/报错）时不调用，
     * 因为残留文件会被删除，不产生有效 delta。
     */
    private fun accumulateFolderSize(
        accumulator: MutableMap<String, Long>,
        encryptedFile: File,
        vaultDir: File,
        fileSize: Long
    ) {
        val vaultPath = vaultDir.path.trimEnd('/')
        var dir = encryptedFile.parentFile
        while (dir != null) {
            val normalizedPath = dir.path.trimEnd('/')
            if (!normalizedPath.startsWith(vaultPath)) break
            val oldSize = accumulator[normalizedPath] ?: 0L
            accumulator[normalizedPath] = oldSize + fileSize
            dir = dir.parentFile
        }
    }

    /** 将累加的目录大小写入 FolderSizeDb（存储在应用私有目录，不污染保险箱） */
    private fun saveFolderSizes(vaultDir: File, accumulator: Map<String, Long>) {
        if (accumulator.isEmpty()) return
        val saveDir = AppDataPaths.fileManager(context)
        val db = FolderSizeDb.load(saveDir)
        val updatedSizes = mutableMapOf<String, Long>()
        for ((path, delta) in accumulator) {
            val existing = db.get(path)?.size ?: 0L
            val newSize = existing + delta
            db.put(path, FolderSizeInfo(newSize, System.currentTimeMillis()))
            updatedSizes[path] = newSize
        }
        db.save(saveDir)
        // 通知 UI 局部刷新 FolderSizeDb（传递更新后的完整大小，而非 delta）
        manager.notifyFolderSizeChanged(updatedSizes)
    }

    // ═══════════════════════════════════════════════════════
    //  Vault 操作：保险箱 → 外部（解密导出）
    // ═══════════════════════════════════════════════════════

    private fun copyVaultToExternal(ctx: VaultOperationContext.VaultToExternal) {
        val totalSize = sources.sumOf { File(it).walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } }

        manager.updateProgress(FileOpProgress(
            phase = "正在解密",
            currentBytes = 0,
            totalBytes = totalSize,
            isScanning = false,
            fileIndex = 0,
            fileCount = sources.size
        ))

        // 展开所有源为"待解密文件队列"（目录递归展开），并发通道按队列顺序消费。
        val queue = buildList {
            for (src in sources) {
                val srcFile = File(src)
                if (srcFile.isDirectory) {
                    srcFile.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relParent = file.parentFile?.relativeTo(srcFile)?.path?.replace('\\', '/') ?: ""
                        val outputDir = if (relParent.isEmpty()) File(targetDir) else File(targetDir, relParent)
                        add(DecryptItem(file, outputDir))
                    }
                } else {
                    add(DecryptItem(srcFile, File(targetDir)))
                }
            }
        }

        val processedFiles = AtomicInteger(0)
        // MOVE 目的下记录成功解密的源文件，仅在完成后删除，避免取消/跳过时误删
        val movedSources = if (purpose == CopyPurpose.MOVE) java.util.concurrent.ConcurrentHashMap.newKeySet<String>() else null
        runWithChannels(
            items = queue,
            shouldStop = { isGracefulCancelled() }
        ) { item, channelId ->
            if (decryptOneFile(item, ctx.sourceSession, totalSize, processedFiles, channelId)) {
                movedSources?.add(item.file.absolutePath)
            }
        }

        if (movedSources != null) {
            for (path in movedSources) {
                val srcFile = File(path)
                vaultBytesRemoved.addAndGet(srcFile.length())
                vaultFilesRemoved.incrementAndGet()
                try { srcFile.delete() } catch (_: Exception) {}
            }
        }
    }

    /** 待解密文件及其输出目录。 */
    private class DecryptItem(val file: File, val outputDir: File)

    private fun decryptOneFile(
        item: DecryptItem,
        session: VaultSession,
        totalSize: Long,
        processedFiles: AtomicInteger,
        channelId: Int
    ): Boolean {
        throwIfCancelled()
        setChannelName(channelId, item.file.name)
        publishDecryptProgress(totalSize, processedFiles.get())

        decryptWithRetry(session, item.file, item.outputDir, totalSize, processedFiles)

        processedFiles.incrementAndGet()
        addProgressBytes(item.file.length())
        publishDecryptProgress(totalSize, processedFiles.get())
        return true
    }

    /** 解密并对"文件级错误"自动重试一次；仍失败时弹窗（暂停其他通道）等待用户选择。 */
    private fun decryptWithRetry(
        session: VaultSession,
        srcFile: File,
        outputDir: File,
        totalSize: Long,
        processedFiles: AtomicInteger
    ) {
        var attempt = 0
        while (true) {
            throwIfCancelled()
            attempt++
            try {
                CryptoService.decryptOutOfVault(session, srcFile, outputDir)
                return
            } catch (e: InterruptedException) {
                throw e
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: Exception) {
                if (skipAllErrors) {
                    processedFiles.incrementAndGet()
                    addProgressBytes(srcFile.length())
                    publishDecryptProgress(totalSize, processedFiles.get())
                    throw SkippedException()
                }
                if (attempt > 1) {
                    val action = synchronized(dialogMutex) {
                        runBlocking {
                            manager.resolveError(ErrorRequest(
                                fileName = srcFile.name,
                                errorMessage = e.message ?: "解密失败",
                                detailMessage = "${e.javaClass.simpleName}: ${e.message}"
                            ))
                        }
                    }
                    when (action.action) {
                        ErrorAction.RETRY -> { attempt = 1 }
                        ErrorAction.SKIP -> {
                            processedFiles.incrementAndGet()
                            addProgressBytes(srcFile.length())
                            publishDecryptProgress(totalSize, processedFiles.get())
                            throw SkippedException()
                        }
                        ErrorAction.SKIP_ALL -> {
                            skipAllErrors = true
                            processedFiles.incrementAndGet()
                            addProgressBytes(srcFile.length())
                            publishDecryptProgress(totalSize, processedFiles.get())
                            throw SkippedException()
                        }
                        ErrorAction.CANCEL -> {
                            cancelFlag.set(true)
                            throw InterruptedIOException("用户取消")
                        }
                    }
                }
                // 首次失败：静默自动重试
            }
        }
    }

    /** 发布解密总进度。 */
    private fun publishDecryptProgress(totalSize: Long, doneFiles: Int) {
        synchronized(channelLock) {
            manager.updateProgress(FileOpProgress(
                phase = "正在解密",
                currentBytes = progressBytes.get(),
                totalBytes = totalSize,
                fileIndex = doneFiles,
                fileCount = sources.size,
                activeFileNames = activeChannelNames()
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
                    vaultFilesRemoved.addAndGet(
                        if (srcFile.isDirectory) srcFile.walkTopDown().filter { it.isFile }.count()
                        else 1
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
            vaultFilesAdded.incrementAndGet()
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

        // 自动应用上次选择
        val autoAction = conflictAutoAction
        if (autoAction != null) {
            return when (autoAction) {
                ConflictAction.REPLACE -> target
                ConflictAction.RENAME -> {
                    val parent = target.substringBeforeLast('/')
                    "$parent/${generateUniqueName(parent, sourceName, isDirectory).substringAfterLast('/')}"
                }
                ConflictAction.SKIP -> null
                else -> null
            }
        }

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

        AuditLog.event(
            event = "file.conflict",
            detail = linkedMapOf(
                "kind" to "copy",
                "source" to sourcePath,
                "target" to target,
                "isDir" to isDirectory,
                "srcSize" to request.sourceSize,
                "dstSize" to request.targetSize,
                "srcMtime" to request.sourceModifiedTime,
                "dstMtime" to request.targetModifiedTime,
                "rule" to "target 已存在（目录合并除外）"
            ),
            message = "检测到文件冲突，等待用户选择"
        )

        val result = runBlocking {
            manager.resolveConflict(request)
        }

        AuditLog.event(
            event = "file.conflict.resolved",
            detail = linkedMapOf(
                "source" to sourcePath,
                "target" to target,
                "action" to result.action.name,
                "newName" to result.newName,
                "applyToAll" to result.applyToAll
            )
        )

        if (result.applyToAll) {
            conflictAutoAction = result.action
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
     * 加密引入前检查：目标加密文件已存在时弹冲突弹窗（仅 跳过/替换）。
     * 返回 true 表示应覆盖写入，false 表示跳过。
     */
    private fun resolveVaultConflict(
        session: VaultSession,
        srcFile: File,
        subDir: String
    ): Boolean {
        val outName = if (session.record.encryptFilename) {
            FilenameCodec.encrypt(
                filename = srcFile.name,
                dek = session.dek,
                aad = if (session.record.customEncryption) FileConstants.aadCustomObf else null
            ).encoded
        } else {
            "${srcFile.name}.whm"
        }
        val targetDir = if (subDir.isEmpty()) session.vaultDir else File(session.vaultDir, subDir)
        val outFile = File(targetDir, outName)
        if (!outFile.exists()) return true

        // 自动应用上次选择
        val autoAction = conflictAutoAction
        if (autoAction != null) {
            return when (autoAction) {
                ConflictAction.REPLACE -> true
                ConflictAction.SKIP -> false
                else -> false
            }
        }

        val request = ConflictRequest(
            sourceName = srcFile.name,
            targetName = outName,
            isDirectory = false,
            sourceSize = srcFile.length(),
            targetSize = outFile.length(),
            sourceModifiedTime = srcFile.lastModified(),
            targetModifiedTime = outFile.lastModified(),
            allowRename = false
        )
        AuditLog.event(
            event = "vault.conflict",
            detail = linkedMapOf(
                "kind" to "externalToVault",
                "vault" to session.record.name,
                "source" to srcFile.absolutePath,
                "target" to outFile.absolutePath,
                "srcSize" to request.sourceSize,
                "dstSize" to request.targetSize,
                "srcMtime" to request.sourceModifiedTime,
                "dstMtime" to request.targetModifiedTime,
                "encryptFilename" to session.record.encryptFilename,
                "rule" to "目标加密文件已存在"
            ),
            message = "保险箱加密引入检测到重名，等待用户选择"
        )
        val result = runBlocking { manager.resolveConflict(request) }
        if (result.applyToAll) {
            conflictAutoAction = result.action
        }
        AuditLog.event(
            event = "vault.conflict.resolved",
            detail = linkedMapOf(
                "vault" to session.record.name,
                "target" to outFile.absolutePath,
                "action" to result.action.name,
                "applyToAll" to result.applyToAll
            )
        )
        return when (result.action) {
            ConflictAction.REPLACE -> true
            ConflictAction.SKIP -> false
            ConflictAction.CANCEL -> throw InterruptedIOException("用户取消")
            else -> false
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
