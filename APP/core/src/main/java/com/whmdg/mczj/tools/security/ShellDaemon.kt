package com.whmdg.mczj.tools.security

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Shell 守护进程管理器。
 * 维护按权限隔离的持久 shell 进程池，避免每次执行命令都 fork。
 *
 * ROOT 权限通过 libsu Shell 执行（正确的 SELinux 上下文 + FLAG_MOUNT_MASTER），
 * ADB 通过 Shizuku 直连 `newProcess` 执行，APPLICANT 通过持久 shell 进程执行。
 *
 * 协议：通过 stdin 发送命令，stdout 读取到 marker 结束，解析输出和 exit code。
 */
object ShellDaemon {
    private const val TAG = "ShellDaemon"
    private const val MARKER_PREFIX = "___EXIT_"
    private const val MARKER_SUFFIX = "___"

    private val shells = ConcurrentHashMap<Permission, PersistentShell>()

    /**
     * 权限回退事件：请求 ADB（Shizuku）权限却不可用、实际以应用自身权限执行时发出。
     * UI 层收集后弹窗提醒，并附诊断信息帮助判断是「ADB 确实不可用」还是「代码/兼容问题」。
     */
    data class PermissionFallbackEvent(
        /** 回退原因 */
        val reason: String,
        /** 触发回退的原始命令 */
        val command: String,
        /** 诊断信息（应用权限 id、Shizuku 各状态等） */
        val diagnostic: String
    )

    private val _fallbackEvents = MutableSharedFlow<PermissionFallbackEvent>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val fallbackEvents: SharedFlow<PermissionFallbackEvent> = _fallbackEvents.asSharedFlow()

    @Volatile
    private var lastFallbackNotifiedAt = 0L

    /** 同一会话内 10 秒内的重复回退不再提示，避免批量命令刷屏。 */
    private const val FALLBACK_THROTTLE_MS = 10_000L

    /**
     * 报告一次 ADB→APPLICANT 回退，并同步采集诊断信息后发出事件。
     * 节流：10 秒内只发一次。供 ShellExecutor 的 FD 路径复用。
     */
    internal fun reportFallback(reason: String, command: String) {
        val now = System.currentTimeMillis()
        if (now - lastFallbackNotifiedAt < FALLBACK_THROTTLE_MS) return
        lastFallbackNotifiedAt = now

        val applicantId = try {
            executeInShell(getOrCreateShell(Permission.APPLICANT), "id", Permission.APPLICANT).trim()
        } catch (e: Exception) {
            "执行 id 失败: ${e.message}"
        }
        val diagnostic = buildString {
            appendLine("回退通道（应用权限）id：")
            appendLine(applicantId)
            appendLine()
            append(ShizukuAuthorizer.diagnose())
        }
        _fallbackEvents.tryEmit(
            PermissionFallbackEvent(reason = reason, command = command, diagnostic = diagnostic)
        )
    }

    /**
     * 持久 shell 进程。
     * 每个权限级别一个实例，通过 stdin/stdout 管道通信。
     */
    private class PersistentShell(
        val process: Process,
        val stdin: OutputStream,
        val stdout: InputStream,
        val lock: ReentrantLock
    ) {
        fun isAlive(): Boolean = process.isAlive
    }

    /**
     * 同步执行命令，返回 stdout（stderr 已合并到 stdout）。
     * @throws ShellException 命令执行失败
     */
    fun execute(permission: Permission, command: String): String {
        if (permission == Permission.ROOT) {
            return executeWithLibsu(command, permission)
        }
        if (permission == Permission.ADB) {
            return executeViaShizuku(command, permission)
        }
        val shell = getOrCreateShell(permission)
        return executeInShell(shell, command, permission)
    }

    /**
     * 通过 Shizuku 直连 `newProcess`（uid 2000 / 0）执行命令。
     * 无阻塞式绑定：不会等待 UserService，直接读取缓存 binder。
     * Shizuku 不可用时回退到应用自身权限（APPLICANT）。
     */
    private fun executeViaShizuku(command: String, permission: Permission): String {
        val result = try {
            ShizukuAuthorizer.executeCommand(command)
        } catch (e: ShizukuUnavailableException) {
            Log.w(TAG, "Shizuku 不可用（${e.message}），回退到应用自身权限执行")
            reportFallback(e.message ?: "Shizuku 不可用", command)
            return executeInShell(getOrCreateShell(Permission.APPLICANT), command, permission)
        }
        val (stdout, stderr, exitCode) = result
        if (exitCode != 0) {
            throw ShellException(
                message = "命令执行失败",
                command = command,
                permission = permission,
                stderr = stderr.ifBlank { stdout }.ifBlank { "exit $exitCode" },
                exitCode = exitCode
            )
        }
        return stdout
    }

    /**
     * 通过 Shizuku 直连 `newProcess` 流式执行命令。
     * Shizuku 不可用时回退到应用自身权限执行。
     */
    private fun executeStreamingViaShizuku(
        command: String,
        permission: Permission,
        useStderr: Boolean,
        onOutputLine: (String) -> Unit,
        cancelFlag: AtomicBoolean?
    ) {
        val exitCode = try {
            ShizukuAuthorizer.executeStreaming(command, useStderr, cancelFlag, onOutputLine)
        } catch (e: ShizukuUnavailableException) {
            // 用户取消不是「Shizuku 不可用」，绝不能回退重跑命令
            if (cancelFlag?.get() == true) {
                throw ShellException(
                    message = "命令已取消",
                    command = command,
                    permission = permission
                )
            }
            Log.w(TAG, "Shizuku 不可用（${e.message}），回退到应用自身权限流式执行")
            reportFallback(e.message ?: "Shizuku 不可用", command)
            executeStreamingApplicant(command, permission, useStderr, onOutputLine, cancelFlag)
            return
        }
        if (exitCode != 0) {
            throw ShellException(
                message = "流式命令执行失败",
                command = command,
                permission = permission,
                exitCode = exitCode
            )
        }
    }

    /**
     * 以应用自身权限（APPLICANT）流式执行的公共回退实现。
     */
    private fun executeStreamingApplicant(
        command: String,
        permission: Permission,
        useStderr: Boolean,
        onOutputLine: (String) -> Unit,
        cancelFlag: AtomicBoolean?
    ) {
        if (useStderr) {
            executeWithStderrFork(command, permission, onOutputLine, cancelFlag)
        } else {
            executeStreamingInShell(getOrCreateShell(Permission.APPLICANT), command, permission, onOutputLine, cancelFlag)
        }
    }

    /**
     * 流式执行命令，逐行回调 stdout。
     * @throws ShellException 命令执行失败
     */
    fun executeStreaming(
        permission: Permission,
        command: String,
        onOutputLine: (String) -> Unit,
        cancelFlag: AtomicBoolean? = null
    ) {
        if (permission == Permission.ROOT) {
            executeStreamingWithLibsu(command, permission, onOutputLine, cancelFlag)
            return
        }
        if (permission == Permission.ADB) {
            executeStreamingViaShizuku(command, permission, useStderr = false, onOutputLine, cancelFlag)
            return
        }
        val shell = getOrCreateShell(permission)
        executeStreamingInShell(shell, command, permission, onOutputLine, cancelFlag)
    }

    /**
     * 执行命令，实时回调 stderr 每行输出。
     * 用于 PV 等将进度输出到 stderr 的工具。
     * @throws ShellException 命令执行失败
     */
    fun executeWithStderr(
        permission: Permission,
        command: String,
        onStderrLine: (String) -> Unit,
        cancelFlag: AtomicBoolean? = null
    ) {
        if (permission == Permission.ADB) {
            executeStreamingViaShizuku(command, permission, useStderr = true, onStderrLine, cancelFlag)
            return
        }
        // ponytail: stderr 流式需要独立进程，无法用持久 shell 的 stdin/stdout 管道
        // 回退到 fork 方式，但复用 shell 进程做其他命令
        executeWithStderrFork(command, permission, onStderrLine, cancelFlag)
    }

    /**
     * 执行命令，实时回调 stdout 每行输出，stderr 丢弃。
     * @throws ShellException 命令执行失败
     */
    fun executeWithStdout(
        permission: Permission,
        command: String,
        onStdoutLine: (String) -> Unit,
        cancelFlag: AtomicBoolean? = null
    ) {
        if (permission == Permission.ROOT) {
            executeStreamingWithLibsu(command, permission, onStdoutLine, cancelFlag)
            return
        }
        if (permission == Permission.ADB) {
            executeStreamingViaShizuku(command, permission, useStderr = false, onStdoutLine, cancelFlag)
            return
        }
        val shell = getOrCreateShell(permission)
        executeStreamingInShell(shell, command, permission, onStdoutLine, cancelFlag)
    }

    /**
     * 扫描专用流式执行：用于文件夹大小统计等长耗时 `find` 命令，实时回调 stdout 每行。
     *
     * 与 [executeWithStdout] 的关键区别：**不复用日常持久 shell**。原因：
     * 长耗时命令被取消时，若复用持久 shell，命令进程仍在管道里继续输出残留数据，
     * 污染后续命令的 stdout，导致解析错乱（目录显示空白、返回无反应）。
     *
     * 三通道均使用「独立一次性进程」，取消时 [Process.destroyForcibly] 真正杀掉进程：
     *   - ROOT：`su -c <command>`
     *   - APPLICANT：`sh -c <command>`
     *   - ADB：Shizuku `newProcess`（由 [ShizukuAuthorizer] 负责取消时 destroy）
     *
     * @throws ShellException 启动失败、非零退出或用户取消
     */
    fun executeScanStreaming(
        permission: Permission,
        command: String,
        onStdoutLine: (String) -> Unit,
        cancelFlag: AtomicBoolean? = null
    ) {
        if (permission == Permission.ADB) {
            try {
                val exitCode = ShizukuAuthorizer.executeStreaming(command, useStderr = false, cancelFlag, onStdoutLine)
                if (exitCode != 0) {
                    throw ShellException(
                        message = "扫描命令执行失败",
                        command = command,
                        permission = permission,
                        exitCode = exitCode
                    )
                }
                return
            } catch (e: ShizukuUnavailableException) {
                // 取消不是「Shizuku 不可用」，绝不回退重跑
                if (cancelFlag?.get() == true) {
                    throw ShellException("命令已取消", command, permission)
                }
                Log.w(TAG, "Shizuku 不可用（${e.message}），扫描回退到应用自身权限独立进程")
                reportFallback(e.message ?: "Shizuku 不可用", command)
                // 扫描回退也必须使用独立进程，禁止污染日常持久 shell
                executeIsolatedScanProcess(arrayOf("sh", "-c", command), command, permission, onStdoutLine, cancelFlag)
                return
            }
        }
        val cmdArray = when (permission) {
            Permission.ROOT -> arrayOf("su", "-c", command)
            else -> arrayOf("sh", "-c", command)
        }
        executeIsolatedScanProcess(cmdArray, command, permission, onStdoutLine, cancelFlag)
    }

    /**
     * 以独立一次性进程执行扫描命令，逐行回调 stdout，支持取消时杀进程。
     * stderr 由守护线程丢弃，避免管道写满阻塞。
     */
    private fun executeIsolatedScanProcess(
        cmdArray: Array<String>,
        command: String,
        permission: Permission,
        onStdoutLine: (String) -> Unit,
        cancelFlag: AtomicBoolean?
    ) {
        val process = try {
            ProcessBuilder(*cmdArray).redirectErrorStream(false).start()
        } catch (e: Exception) {
            throw ShellException(
                message = "扫描命令启动异常",
                command = command,
                permission = permission,
                stderr = e.message ?: ""
            )
        }

        val outThread = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (cancelFlag?.get() == true) break
                        onStdoutLine(line!!)
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        val errThread = Thread {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    while (reader.readLine() != null) { /* 丢弃 */ }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        try {
            while (process.isAlive) {
                if (cancelFlag?.get() == true) {
                    process.destroyForcibly()
                    break
                }
                Thread.sleep(50)
            }
        } finally {
            if (cancelFlag?.get() == true) {
                try { process.destroyForcibly() } catch (_: Exception) {}
            }
        }

        outThread.join(2000)
        errThread.join(1000)

        if (cancelFlag?.get() == true) {
            throw ShellException(
                message = "命令已取消",
                command = command,
                permission = permission
            )
        }
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw ShellException(
                message = "扫描命令执行失败",
                command = command,
                permission = permission,
                exitCode = exitCode
            )
        }
    }

    /**
     * 清理所有持久 shell 进程。
     */
    fun destroy() {
        shells.forEach { (permission, shell) ->
            try {
                shell.process.destroyForcibly()
                Log.i(TAG, "已销毁 $permission 持久 shell")
            } catch (e: Exception) {
                Log.w(TAG, "销毁 $permission shell 失败: ${e.message}")
            }
        }
        shells.clear()
    }

    // ── libsu Shell（ROOT 权限） ──────────────────────────────────────

    /**
     * 通过 libsu Shell 同步执行命令（ROOT 权限）。
     * libsu 正确处理 SELinux 上下文，可访问 /data/data 等受保护目录。
     */
    private fun executeWithLibsu(command: String, permission: Permission): String {
        val result = Shell.cmd("$command 2>&1").exec()
        if (!result.isSuccess) {
            val stderr = result.err.joinToString("\n").trim()
            throw ShellException(
                message = "命令执行失败",
                command = command,
                permission = permission,
                stderr = stderr.ifBlank { "exit ${result.code}" },
                exitCode = result.code
            )
        }
        return result.out.joinToString("\n")
    }

    /**
     * 通过 libsu Shell 流式执行命令（ROOT 权限）。
     * 使用 execTask 获取持久 shell 的原始 stdin/stdout，逐行回调。
     */
    private fun executeStreamingWithLibsu(
        command: String,
        permission: Permission,
        onOutputLine: (String) -> Unit,
        cancelFlag: AtomicBoolean?
    ) {
        try {
            val shell = Shell.getShell()
            shell.execTask { stdin, stdout, _ ->
                val marker = "${MARKER_PREFIX}${System.nanoTime()}${MARKER_SUFFIX}"
                val ps = PrintStream(stdin)
                ps.println("$command 2>&1")
                ps.println("echo ${marker}\$?")
                ps.flush()

                val reader = BufferedReader(InputStreamReader(stdout))
                val lineBuf = StringBuilder()
                var exitCode = -1

                while (true) {
                    if (cancelFlag?.get() == true) {
                        throw ShellException(
                            message = "命令已取消",
                            command = command,
                            permission = permission
                        )
                    }

                    val n = reader.read()
                    if (n == -1) break

                    val ch = n.toChar()
                    if (ch == '\n') {
                        val line = lineBuf.toString()
                        lineBuf.clear()

                        if (line.startsWith(marker)) {
                            exitCode = line.substring(marker.length).trim().toIntOrNull() ?: -1
                            if (exitCode != 0) {
                                throw ShellException(
                                    message = "流式命令执行失败",
                                    command = command,
                                    permission = permission,
                                    exitCode = exitCode
                                )
                            }
                            return@execTask
                        }

                        if (line.isNotBlank()) {
                            onOutputLine(line)
                        }
                    } else if (ch != '\r') {
                        lineBuf.append(ch)
                    }
                }
            }
        } catch (e: ShellException) {
            throw e
        } catch (e: Exception) {
            throw ShellException(
                message = "libsu 流式执行异常: ${e.message}",
                command = command,
                permission = permission,
                stderr = e.message ?: ""
            )
        }
    }

    // ── 持久 Shell（ADB / APPLICANT 权限） ────────────────────────────

    /**
     * 获取或创建指定权限的持久 shell 进程。
     * ROOT 权限通过 libsu Shell 执行，不走此处。
     */
    private fun getOrCreateShell(permission: Permission): PersistentShell {
        shells[permission]?.let { shell ->
            if (shell.isAlive()) return shell
            // 进程已死，移除并重建
            shells.remove(permission)
            try { shell.process.destroyForcibly() } catch (_: Exception) {}
        }

        // 创建新的持久 shell。ADB 权限统一走 Shizuku UserService，不在此建立本地 shell。
        val process = when (permission) {
            Permission.APPLICANT -> ProcessBuilder("sh").start()
            else -> throw ShellException(
                message = "不支持的权限级别: $permission（ADB 权限请走 Shizuku）",
                command = "",
                permission = permission
            )
        }

        val shell = PersistentShell(
            process = process,
            stdin = process.outputStream,
            stdout = process.inputStream,
            lock = ReentrantLock()
        )
        shells[permission] = shell
        Log.i(TAG, "已创建 $permission 持久 shell")
        return shell
    }

    /**
     * 在持久 shell 中同步执行命令。
     */
    private fun executeInShell(
        shell: PersistentShell,
        command: String,
        permission: Permission
    ): String {
        shell.lock.withLock {
            val marker = "${MARKER_PREFIX}${System.nanoTime()}${MARKER_SUFFIX}"
            val fullCommand = "$command 2>&1\necho ${marker}\$?\n"

            try {
                // 发送命令
                shell.stdin.write(fullCommand.toByteArray(Charsets.UTF_8))
                shell.stdin.flush()

                // 读取到 marker
                val output = StringBuilder()
                val buffer = CharArray(8192)
                val reader = shell.stdout.bufferedReader()
                var markerFound = false
                var exitCode = -1

                while (!markerFound) {
                    val n = reader.read(buffer)
                    if (n == -1) {
                        // 进程意外退出
                        throw ShellException(
                            message = "持久 shell 进程意外退出",
                            command = command,
                            permission = permission
                        )
                    }
                    output.append(buffer, 0, n)

                    // 检查是否包含 marker
                    val outputStr = output.toString()
                    val markerIdx = outputStr.indexOf(marker)
                    if (markerIdx >= 0) {
                        markerFound = true
                        // 提取 marker 前的输出（去掉末尾换行）
                        val result = outputStr.substring(0, markerIdx).trimEnd()
                        // 提取 exit code
                        val afterMarker = outputStr.substring(markerIdx + marker.length).trim()
                        exitCode = afterMarker.toIntOrNull() ?: -1

                        if (exitCode != 0) {
                            throw ShellException(
                                message = "命令执行失败",
                                command = command,
                                permission = permission,
                                stderr = result,
                                exitCode = exitCode
                            )
                        }
                        return result
                    }
                }
                // 不会到这里
                return ""
            } catch (e: ShellException) {
                throw e
            } catch (e: Exception) {
                // 进程可能已死，移除
                shells.remove(permission)
                throw ShellException(
                    message = "持久 shell 执行异常: ${e.message}",
                    command = command,
                    permission = permission,
                    stderr = e.message ?: ""
                )
            }
        }
    }

    /**
     * 在持久 shell 中流式执行命令，逐行回调。
     */
    private fun executeStreamingInShell(
        shell: PersistentShell,
        command: String,
        permission: Permission,
        onOutputLine: (String) -> Unit,
        cancelFlag: AtomicBoolean?
    ) {
        shell.lock.withLock {
            val marker = "${MARKER_PREFIX}${System.nanoTime()}${MARKER_SUFFIX}"
            val fullCommand = "$command 2>&1\necho ${marker}\$?\n"

            try {
                shell.stdin.write(fullCommand.toByteArray(Charsets.UTF_8))
                shell.stdin.flush()

                val reader = shell.stdout.bufferedReader()
                val lineBuffer = StringBuilder()
                var exitCode = -1

                // 逐行读取，遇到 marker 停止
                val charBuf = CharArray(4096)
                while (true) {
                    if (cancelFlag?.get() == true) {
                        throw ShellException(
                            message = "命令已取消",
                            command = command,
                            permission = permission
                        )
                    }

                    val n = reader.read(charBuf)
                    if (n == -1) {
                        throw ShellException(
                            message = "持久 shell 进程意外退出",
                            command = command,
                            permission = permission
                        )
                    }

                    lineBuffer.append(charBuf, 0, n)

                    // 处理完整行
                    while (true) {
                        val newlineIdx = lineBuffer.indexOf('\n')
                        if (newlineIdx < 0) break

                        val line = lineBuffer.substring(0, newlineIdx)
                        lineBuffer.delete(0, newlineIdx + 1)

                        // 检查 marker
                        if (line.startsWith(marker)) {
                            exitCode = line.substring(marker.length).trim().toIntOrNull() ?: -1
                            if (exitCode != 0) {
                                throw ShellException(
                                    message = "流式命令执行失败",
                                    command = command,
                                    permission = permission,
                                    exitCode = exitCode
                                )
                            }
                            return
                        }

                        if (line.isNotBlank()) {
                            onOutputLine(line)
                        }
                    }
                }
            } catch (e: ShellException) {
                throw e
            } catch (e: Exception) {
                shells.remove(permission)
                throw ShellException(
                    message = "持久 shell 流式执行异常: ${e.message}",
                    command = command,
                    permission = permission,
                    stderr = e.message ?: ""
                )
            }
        }
    }

    /**
     * stderr 流式执行：fork 独立进程（stderr 需要独立 fd）。
     * ponytail: 持久 shell 的 stdin/stdout 管道无法同时捕获 stderr 流式输出，
     * 只能 fork。这是流式 stderr 的固有限制。
     */
    private fun executeWithStderrFork(
        command: String,
        permission: Permission,
        onStderrLine: (String) -> Unit,
        cancelFlag: AtomicBoolean?
    ) {
        val cmdArray = when (permission) {
            Permission.ROOT -> arrayOf("su", "-c", command)
            else -> arrayOf("sh", "-c", command)
        }

        val process = try {
            ProcessBuilder(*cmdArray)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            throw ShellException(
                message = "stderr 执行启动异常: ${e.message}",
                command = command,
                permission = permission,
                stderr = e.message ?: ""
            )
        }

        val stderrLines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tErr = Thread {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (cancelFlag?.get() == true) break
                        stderrLines.add(line!!)
                        onStderrLine(line!!)
                    }
                }
            } catch (_: Exception) {}
        }.apply { start() }

        // stdout 丢弃
        val tOut = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (reader.readLine() != null) { /* 丢弃 */ }
                }
            } catch (_: Exception) {}
        }.apply { start() }

        while (process.isAlive) {
            if (cancelFlag?.get() == true) {
                process.destroyForcibly()
                break
            }
            Thread.sleep(100)
        }

        tOut.join(5000)
        tErr.join(5000)

        if (cancelFlag?.get() == true) {
            throw ShellException(
                message = "命令已取消",
                command = command,
                permission = permission
            )
        }

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            val stderr = stderrLines.joinToString("\n").trim()
            throw ShellException(
                message = "stderr 命令执行失败",
                command = command,
                permission = permission,
                stderr = stderr.ifBlank { "exit $exitCode" },
                exitCode = exitCode
            )
        }
    }
}
