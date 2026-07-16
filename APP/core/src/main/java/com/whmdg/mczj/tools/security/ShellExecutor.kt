package com.whmdg.mczj.tools.security

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.util.DiagnosticLog
import com.whmdg.mczj.tools.util.SevenZipCommand
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 权限级别枚举。
 * 外部传入的参数不可信，ShellExecutor 内部会自行校验权限可用性。
 */
enum class Permission {
    /** 应用自身权限（最低），始终可用 */
    APPLICANT,
    /** 自动选择：Shizuku 可用 → Shizuku，否则 → 电脑 ADB（应用 shell） */
    ADB,
    /** Root 权限（最高） */
    ROOT,
    /** 自动使用安全设置中授权的最高权限 */
    MAX,
    /** 最低权限 = APPLICANT */
    MIN
}

/**
 * Shell 执行异常，携带完整错误上下文。
 * UI 层捕获后传入 ErrorDialog 弹窗展示。
 */
class ShellException(
    message: String,
    val command: String,
    val permission: Permission,
    val stderr: String = "",
    val exitCode: Int = -1
) : Exception(buildMessage(message, command, permission, stderr, exitCode)) {
    companion object {
        private fun buildMessage(
            message: String,
            command: String,
            permission: Permission,
            stderr: String,
            exitCode: Int
        ): String {
            val sb = StringBuilder()
            sb.appendLine(message)
            sb.appendLine("权限: $permission")
            sb.appendLine("命令: $command")
            if (exitCode >= 0) sb.appendLine("退出码: $exitCode")
            if (stderr.isNotBlank()) sb.appendLine("stderr: $stderr")
            return sb.toString().trimEnd()
        }
    }
}

/**
 * 统一 Shell 执行入口。
 * 所有 shell 命令必须通过此对象执行，禁止任何代码私自调用 Runtime.exec / ProcessBuilder / Shell.cmd。
 *
 * 核心原则：不信任外部传入参数，执行前自行校验权限可用性。
 *
 * 错误处理：任何错误（参数不合法、权限不可用、命令执行失败）均抛出 ShellException，
 * UI 层统一通过 ErrorDialog 弹窗展示，便于开发阶段发现并修复问题。
 */
object ShellExecutor {

    private var appContext: Context? = null

    /**
     * 初始化，传入 Application Context。在 ToolsApp.onCreate() 中调用一次。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context {
        return appContext ?: throw IllegalStateException(
            "ShellExecutor 未初始化，请在 Application.onCreate() 中调用 ShellExecutor.init(context)"
        )
    }

    /**
     * 执行 shell 命令，返回 stdout。
     * 任何错误均抛出 ShellException，UI 层通过 ErrorDialog 展示。
     *
     * @param permission 所需权限级别，内部会校验实际可用性
     * @param command 要执行的 shell 命令
     * @param debug 是否在异常中包含 stderr 详细信息
     * @return stdout（成功时）
     * @throws ShellException 命令为空、权限不可用、执行失败等任何错误
     */
    fun execute(
        permission: Permission,
        command: String,
        debug: Boolean = false
    ): String {
        if (command.isBlank()) {
            throw ShellException(
                message = "Shell 命令不能为空",
                command = command,
                permission = permission
            )
        }

        DiagnosticLog.log("ShellExecutor", "执行: permission=$permission cmd=${command.take(200)}")

        val resolved = resolvePermission(permission)
        val stdout = when (resolved) {
            Permission.ROOT -> {
                if (!SpecialPermissionVerifier.isRootAvailable()) {
                    throw ShellException(
                        message = "Root 权限不可用",
                        command = command,
                        permission = permission
                    )
                }
                executeRoot(command, permission, debug)
            }
            Permission.ADB -> {
                if (isShizukuAvailable()) {
                    executeShizuku(command, permission, debug)
                } else {
                    executeAppShell(command, permission, debug)
                }
            }
            Permission.APPLICANT -> executeAppShell(command, permission, debug)
            else -> throw ShellException(
                message = "resolvePermission 返回未处理的权限级别: $resolved",
                command = command,
                permission = permission
            )
        }

        DiagnosticLog.log("ShellExecutor", "成功: stdout=${stdout.length}字符")
        return stdout
    }

    /**
     * 流式执行 shell 命令，实时回调每行输出，返回最终 stdout（空串）。
     * 任何错误均抛出 ShellException。
     *
     * @param permission 所需权限级别
     * @param command 要执行的 shell 命令
     * @param onOutputLine 每行 stdout 输出的回调
     * @param cancelFlag 外部设为 true 时立即杀进程并抛 ShellException
     * @param debug 是否在异常中包含 stderr 详细信息
     */
    fun executeStreaming(
        permission: Permission,
        command: String,
        onOutputLine: (String) -> Unit,
        cancelFlag: AtomicBoolean? = null,
        debug: Boolean = false
    ) {
        if (command.isBlank()) {
            throw ShellException(
                message = "Shell 命令不能为空",
                command = command,
                permission = permission
            )
        }

        DiagnosticLog.log("ShellExecutor", "流式执行: permission=$permission cmd=${command.take(200)}")

        val resolved = resolvePermission(permission)
        when (resolved) {
            Permission.ROOT -> {
                if (!SpecialPermissionVerifier.isRootAvailable()) {
                    throw ShellException(
                        message = "Root 权限不可用",
                        command = command,
                        permission = permission
                    )
                }
                executeRootStreaming(command, onOutputLine, cancelFlag, permission, debug)
            }
            Permission.ADB -> {
                if (isShizukuAvailable()) {
                    executeShizukuStreaming(command, onOutputLine, cancelFlag, permission, debug)
                } else {
                    executeAppShellStreaming(command, onOutputLine, cancelFlag, permission, debug)
                }
            }
            Permission.APPLICANT -> executeAppShellStreaming(command, onOutputLine, cancelFlag, permission, debug)
            else -> throw ShellException(
                message = "resolvePermission 返回未处理的权限级别: $resolved",
                command = command,
                permission = permission
            )
        }

        DiagnosticLog.log("ShellExecutor", "流式执行完成")
    }

    // ── 权限解析 ──────────────────────────────────────────────────────

    private fun resolvePermission(permission: Permission): Permission {
        return when (permission) {
            Permission.MAX -> resolveMaxPermission()
            Permission.MIN -> Permission.APPLICANT
            Permission.ROOT, Permission.ADB, Permission.APPLICANT -> permission
        }
    }

    private fun resolveMaxPermission(): Permission {
        val ctx = requireContext()
        val sp = ctx.getSharedPreferences(AppDataPaths.PREFS_LEGACY_SPECIAL_PERMISSIONS, Context.MODE_PRIVATE)
        val target = sp.getString("target_permission_level", "NORMAL") ?: "NORMAL"

        return when (target) {
            "ROOT" -> if (SpecialPermissionVerifier.isRootAvailable()) Permission.ROOT else resolveMaxFallback()
            "ADB" -> if (isShizukuAvailable()) Permission.ADB else resolveMaxFallback()
            else -> resolveMaxFallback()
        }
    }

    private fun resolveMaxFallback(): Permission {
        if (SpecialPermissionVerifier.isRootAvailable()) return Permission.ROOT
        if (isShizukuAvailable()) return Permission.ADB
        return Permission.APPLICANT
    }

    private fun isShizukuAvailable(): Boolean {
        return SpecialPermissionVerifier.isShizukuAuthorized(requireContext())
    }

    // ── 底层执行（失败时抛 ShellException） ───────────────────────────

    private fun executeRoot(command: String, permission: Permission, debug: Boolean): String {
        val result = try {
            Shell.cmd(command).exec()
        } catch (e: Exception) {
            throw ShellException(
                message = "Root 执行异常: ${e.message}",
                command = command,
                permission = permission,
                stderr = e.message ?: ""
            )
        }
        val stdout = result.getOut().joinToString("\n").trimEnd()
        if (!result.isSuccess) {
            val stderr = result.getErr().joinToString("\n").trimEnd()
            // ponytail: libsu 直接读进程 fd，2>&1 不生效，错误可能在 stdout 或 stderr，合并确保不丢
            val allOutput = listOf(stderr, stdout).filter { it.isNotBlank() }.joinToString("\n")
            Log.e("ShellExecutor", "executeRoot失败: exitCode=${result.getCode()}, stdout=${stdout.take(200)}, stderr=${stderr.take(200)}, merged=${allOutput.take(200)}")
            throw ShellException(
                message = "Root 命令执行失败",
                command = command,
                permission = permission,
                stderr = if (debug) allOutput else "exit ${result.getCode()}",
                exitCode = result.getCode()
            )
        }
        return stdout
    }

    private fun executeShizuku(command: String, permission: Permission, debug: Boolean): String {
        val (stdout, stderr, exitCode) = try {
            ShizukuAuthorizer.executeCommand(command)
        } catch (e: Exception) {
            throw ShellException(
                message = "Shizuku 执行异常: ${e.message}",
                command = command,
                permission = permission,
                stderr = e.message ?: ""
            )
        }
        if (exitCode != 0) {
            // ponytail: 合并 stdout+stderr，2>&1 可能不生效
            val allOutput = listOf(stderr, stdout).filter { it.isNotBlank() }.joinToString("\n")
            Log.e("ShellExecutor", "executeShizuku失败: exitCode=$exitCode, stdout=${stdout.take(200)}, stderr=${stderr.take(200)}, merged=${allOutput.take(200)}")
            throw ShellException(
                message = "Shizuku 命令执行失败",
                command = command,
                permission = permission,
                stderr = if (debug) allOutput else "exit $exitCode",
                exitCode = exitCode
            )
        }
        return stdout
    }

    private fun executeAppShell(command: String, permission: Permission, debug: Boolean): String {
        val process = try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        } catch (e: Exception) {
            throw ShellException(
                message = "启动进程异常: ${e.message}",
                command = command,
                permission = permission,
                stderr = e.message ?: ""
            )
        }

        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        val tOut = Thread {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { stdoutBuf.appendLine(it) }
                }
            } catch (_: Exception) {}
        }.apply { start() }
        val tErr = Thread {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { stderrBuf.appendLine(it) }
                }
            } catch (_: Exception) {}
        }.apply { start() }

        process.waitFor()
        tOut.join(2000)
        tErr.join(2000)

        val stdout = stdoutBuf.toString().trimEnd()
        val stderr = stderrBuf.toString().trimEnd()
        val exitCode = process.exitValue()

        if (exitCode != 0) {
            // ponytail: 合并 stdout+stderr，2>&1 可能不生效
            val allOutput = listOf(stderr, stdout).filter { it.isNotBlank() }.joinToString("\n")
            Log.e("ShellExecutor", "executeAppShell失败: exitCode=$exitCode, stdout=${stdout.take(200)}, stderr=${stderr.take(200)}, merged=${allOutput.take(200)}")
            throw ShellException(
                message = "命令执行失败",
                command = command,
                permission = permission,
                stderr = if (debug) allOutput else "exit $exitCode",
                exitCode = exitCode
            )
        }
        return stdout
    }

    // ── 流式执行（失败时抛 ShellException） ───────────────────────────

    private fun executeRootStreaming(
        command: String,
        onOutputLine: (String) -> Unit,
        cancelFlag: AtomicBoolean?,
        permission: Permission,
        debug: Boolean
    ) {
        val process = try {
            ProcessBuilder("su", "-c", command)
                .start()
        } catch (e: Exception) {
            throw ShellException(
                message = "Root 流式启动异常: ${e.message}",
                command = command,
                permission = permission,
                stderr = e.message ?: ""
            )
        }
        readStreamWithCallback(process, onOutputLine, cancelFlag, command, permission, debug)
    }

    private fun executeShizukuStreaming(
        command: String,
        onOutputLine: (String) -> Unit,
        cancelFlag: AtomicBoolean?,
        permission: Permission,
        debug: Boolean
    ) {
        // ponytail: Shizuku AIDL streaming 需要进程级协议，回退到同步方式
        if (cancelFlag?.get() == true) return
        val stdout = executeShizuku(command, permission, debug)
        stdout.lines().forEach { if (it.isNotBlank()) onOutputLine(it) }
    }

    private fun executeAppShellStreaming(
        command: String,
        onOutputLine: (String) -> Unit,
        cancelFlag: AtomicBoolean?,
        permission: Permission,
        debug: Boolean
    ) {
        val process = try {
            ProcessBuilder("sh", "-c", command)
                .start()
        } catch (e: Exception) {
            throw ShellException(
                message = "流式启动异常: ${e.message}",
                command = command,
                permission = permission,
                stderr = e.message ?: ""
            )
        }
        readStreamWithCallback(process, onOutputLine, cancelFlag, command, permission, debug)
    }

    private fun readStreamWithCallback(
        process: Process,
        onOutputLine: (String) -> Unit,
        cancelFlag: AtomicBoolean?,
        command: String,
        permission: Permission,
        debug: Boolean
    ) {
        // 读取 stderr
        val stderrLines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tErr = Thread {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stderrLines.add(line!!)
                    }
                }
            } catch (_: Exception) {}
        }.apply { start() }

        val tOut = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (cancelFlag?.get() == true) break
                        onOutputLine(line!!)
                    }
                }
            } catch (_: Exception) {}
        }.apply { start() }

        // 轮询等待进程完成，期间检测取消
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
                message = "流式命令执行失败",
                command = command,
                permission = permission,
                stderr = stderr.ifBlank { "exit $exitCode" },
                exitCode = exitCode
            )
        }
    }

    // ── stderr 流式执行（用于 PV 进度监控） ───────────────────────────

    /**
     * 执行命令，实时回调 stderr 每行输出。
     * 用于 PV 等将进度输出到 stderr 的工具。
     * PV 进程在正确的 UID 下运行（ROOT→0, Shizuku→2000, APPLICANT→app）。
     *
     * @param permission 权限级别
     * @param command 要执行的 shell 命令
     * @param onStderrLine 每行 stderr 的回调
     * @param cancelFlag 外部设为 true 时立即杀进程并抛 ShellException
     */
    fun executeWithStderr(
        permission: Permission,
        command: String,
        onStderrLine: (String) -> Unit,
        cancelFlag: AtomicBoolean? = null
    ) {
        if (command.isBlank()) {
            throw ShellException(
                message = "Shell 命令不能为空",
                command = command,
                permission = permission
            )
        }

        DiagnosticLog.log("ShellExecutor", "stderr执行: permission=$permission cmd=${command.take(200)}")

        val resolved = resolvePermission(permission)
        when (resolved) {
            Permission.ROOT -> {
                if (!SpecialPermissionVerifier.isRootAvailable()) {
                    throw ShellException(
                        message = "Root 权限不可用",
                        command = command,
                        permission = permission
                    )
                }
                executeWithStderrLocal(arrayOf("su", "-c", command), onStderrLine, cancelFlag, command, permission)
            }
            Permission.ADB -> {
                if (isShizukuAvailable()) {
                    executeWithStderrShizuku(command, onStderrLine, cancelFlag, permission)
                } else {
                    executeWithStderrLocal(arrayOf("sh", "-c", command), onStderrLine, cancelFlag, command, permission)
                }
            }
            Permission.APPLICANT -> {
                executeWithStderrLocal(arrayOf("sh", "-c", command), onStderrLine, cancelFlag, command, permission)
            }
            else -> throw ShellException(
                message = "resolvePermission 返回未处理的权限级别: $resolved",
                command = command,
                permission = permission
            )
        }

        DiagnosticLog.log("ShellExecutor", "stderr执行完成")
    }

    /** ROOT / APPLICANT：本地 ProcessBuilder，直接读 stderr */
    private fun executeWithStderrLocal(
        cmdArray: Array<String>,
        onStderrLine: (String) -> Unit,
        cancelFlag: AtomicBoolean?,
        command: String,
        permission: Permission
    ) {
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

        // stdout 丢弃线程（PV stdout 重定向到目标文件，Process 仍需消费流避免 fd 泄漏）
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

    /** Shizuku：通过 PFD 管道获取 stderr */
    private fun executeWithStderrShizuku(
        command: String,
        onStderrLine: (String) -> Unit,
        cancelFlag: AtomicBoolean?,
        permission: Permission
    ) {
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        val stderrLines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tErr = Thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (cancelFlag?.get() == true) break
                        stderrLines.add(line!!)
                        onStderrLine(line!!)
                    }
                }
            } catch (_: Exception) {}
        }.apply { start() }

        try {
            val (_, _, exitCode) = ShizukuAuthorizer.executeStreamingStderr(command, writeFd)

            tErr.join(5000)

            if (cancelFlag?.get() == true) {
                throw ShellException(
                    message = "命令已取消",
                    command = command,
                    permission = permission
                )
            }
            if (exitCode != 0) {
                val stderr = stderrLines.joinToString("\n").trim()
                throw ShellException(
                    message = "Shizuku stderr 命令执行失败",
                    command = command,
                    permission = permission,
                    stderr = stderr.ifBlank { "exit $exitCode" },
                    exitCode = exitCode
                )
            }
        } catch (e: ShellException) {
            throw e
        } catch (e: Exception) {
            throw ShellException(
                message = "Shizuku stderr 执行异常: ${e.message}",
                command = command,
                permission = permission,
                stderr = e.message ?: ""
            )
        } finally {
            try { readFd.close() } catch (_: Exception) {}
            try { writeFd.close() } catch (_: Exception) {}
        }
    }

    /**
     * 执行 shell 命令，逐行回调 stdout（用于文件复制等需要实时进度的场景）。
     * 与 [executeWithStderr] 对称，stderr 丢弃。
     */
    fun executeWithStdout(
        permission: Permission,
        command: String,
        onStdoutLine: (String) -> Unit,
        cancelFlag: AtomicBoolean? = null
    ) {
        if (command.isBlank()) {
            throw ShellException(
                message = "Shell 命令不能为空",
                command = command,
                permission = permission
            )
        }

        DiagnosticLog.log("ShellExecutor", "stdout执行: permission=$permission cmd=${command.take(200)}")

        val resolved = resolvePermission(permission)
        when (resolved) {
            Permission.ROOT -> {
                if (!SpecialPermissionVerifier.isRootAvailable()) {
                    throw ShellException(
                        message = "Root 权限不可用",
                        command = command,
                        permission = permission
                    )
                }
                executeWithStdoutLocal(arrayOf("su", "-c", command), onStdoutLine, cancelFlag, command, permission)
            }
            Permission.ADB -> {
                if (isShizukuAvailable()) {
                    executeWithStdoutShizuku(command, onStdoutLine, cancelFlag, permission)
                } else {
                    executeWithStdoutLocal(arrayOf("sh", "-c", command), onStdoutLine, cancelFlag, command, permission)
                }
            }
            Permission.APPLICANT -> {
                executeWithStdoutLocal(arrayOf("sh", "-c", command), onStdoutLine, cancelFlag, command, permission)
            }
            else -> throw ShellException(
                message = "resolvePermission 返回未处理的权限级别: $resolved",
                command = command,
                permission = permission
            )
        }

        DiagnosticLog.log("ShellExecutor", "stdout执行完成")
    }

    /** ROOT / APPLICANT：本地 ProcessBuilder，回调 stdout */
    private fun executeWithStdoutLocal(
        cmdArray: Array<String>,
        onStdoutLine: (String) -> Unit,
        cancelFlag: AtomicBoolean?,
        command: String,
        permission: Permission
    ) {
        val process = try {
            ProcessBuilder(*cmdArray)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            throw ShellException(
                message = "stdout 执行启动异常: ${e.message}",
                command = command,
                permission = permission,
                stderr = e.message ?: ""
            )
        }

        val stdoutLines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tOut = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (cancelFlag?.get() == true) break
                        stdoutLines.add(line!!)
                        onStdoutLine(line!!)
                    }
                }
            } catch (_: Exception) {}
        }.apply { start() }

        // stderr 丢弃
        val tErr = Thread {
            try {
                process.errorStream.bufferedReader().use { reader ->
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
            val stdout = stdoutLines.joinToString("\n").trim()
            throw ShellException(
                message = "stdout 命令执行失败",
                command = command,
                permission = permission,
                stderr = stdout.ifBlank { "exit $exitCode" },
                exitCode = exitCode
            )
        }
    }

    /** Shizuku：通过 PFD 管道获取 stdout */
    private fun executeWithStdoutShizuku(
        command: String,
        onStdoutLine: (String) -> Unit,
        cancelFlag: AtomicBoolean?,
        permission: Permission
    ) {
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        val stdoutLines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tOut = Thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (cancelFlag?.get() == true) break
                        stdoutLines.add(line!!)
                        onStdoutLine(line!!)
                    }
                }
            } catch (_: Exception) {}
        }.apply { start() }

        try {
            val (_, _, exitCode) = ShizukuAuthorizer.executeStreamingStdout(command, writeFd)

            tOut.join(5000)

            if (cancelFlag?.get() == true) {
                throw ShellException(
                    message = "命令已取消",
                    command = command,
                    permission = permission
                )
            }
            if (exitCode != 0) {
                val stdout = stdoutLines.joinToString("\n").trim()
                throw ShellException(
                    message = "Shizuku stdout 命令执行失败",
                    command = command,
                    permission = permission,
                    stderr = stdout.ifBlank { "exit $exitCode" },
                    exitCode = exitCode
                )
            }
        } catch (e: ShellException) {
            throw e
        } catch (e: Exception) {
            throw ShellException(
                message = "Shizuku stdout 执行异常: ${e.message}",
                command = command,
                permission = permission,
                stderr = e.message ?: ""
            )
        } finally {
            try { readFd.close() } catch (_: Exception) {}
            try { writeFd.close() } catch (_: Exception) {}
        }
    }

    // ── FD 获取（openForRead / openForWrite）────────────────────────────

    /**
     * 以指定权限打开文件用于读取，返回 PFD。
     * 内部 resolvePermission 后路由到 ROOT/ADB/APPLICANT 三条独立路径。
     */
    fun openForRead(permission: Permission, path: String): ParcelFileDescriptor {
        if (path.isBlank()) {
            throw ShellException(
                message = "文件路径不能为空",
                command = "openForRead",
                permission = permission
            )
        }
        DiagnosticLog.log("ShellExecutor", "openForRead: permission=$permission path=$path")
        val resolved = resolvePermission(permission)
        return when (resolved) {
            Permission.ROOT -> {
                if (!SpecialPermissionVerifier.isRootAvailable()) {
                    throw ShellException(
                        message = "Root 权限不可用",
                        command = "openForRead($path)",
                        permission = permission
                    )
                }
                openForReadRoot(path, permission)
            }
            Permission.ADB -> {
                if (isShizukuAvailable()) {
                    openForReadShizuku(path, permission)
                } else {
                    openForReadLocal(path, permission)
                }
            }
            Permission.APPLICANT -> openForReadLocal(path, permission)
            else -> throw ShellException(
                message = "resolvePermission 返回未处理的权限级别: $resolved",
                command = "openForRead($path)",
                permission = permission
            )
        }
    }

    /**
     * 以指定权限打开/创建文件用于写入，返回 PFD。
     * 内部 resolvePermission 后路由到 ROOT/ADB/APPLICANT 三条独立路径。
     */
    fun openForWrite(permission: Permission, path: String): ParcelFileDescriptor {
        if (path.isBlank()) {
            throw ShellException(
                message = "文件路径不能为空",
                command = "openForWrite",
                permission = permission
            )
        }
        DiagnosticLog.log("ShellExecutor", "openForWrite: permission=$permission path=$path")
        val resolved = resolvePermission(permission)
        return when (resolved) {
            Permission.ROOT -> {
                if (!SpecialPermissionVerifier.isRootAvailable()) {
                    throw ShellException(
                        message = "Root 权限不可用",
                        command = "openForWrite($path)",
                        permission = permission
                    )
                }
                openForWriteRoot(path, permission)
            }
            Permission.ADB -> {
                if (isShizukuAvailable()) {
                    openForWriteShizuku(path, permission)
                } else {
                    openForWriteLocal(path, permission)
                }
            }
            Permission.APPLICANT -> openForWriteLocal(path, permission)
            else -> throw ShellException(
                message = "resolvePermission 返回未处理的权限级别: $resolved",
                command = "openForWrite($path)",
                permission = permission
            )
        }
    }

    // ── FD 获取私有实现 ──

    /** APPLICANT：应用自身 UID 直接打开 */
    private fun openForReadLocal(path: String, permission: Permission): ParcelFileDescriptor {
        return try {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            throw ShellException(
                message = "打开文件失败: ${e.message}",
                command = "openForRead($path)",
                permission = permission,
                stderr = e.message ?: ""
            )
        }
    }

    /** ADB：通过 ShizukuAuthorizer 获取 PFD（ShellService uid 2000） */
    private fun openForReadShizuku(path: String, permission: Permission): ParcelFileDescriptor {
        return ShizukuAuthorizer.openForRead(path)
            ?: throw ShellException(
                message = "Shizuku 打开文件失败",
                command = "openForRead($path)",
                permission = permission
            )
    }

    /** ROOT：su 进程(uid 0) 执行 cat，数据通过 pipe 传回 */
    private fun openForReadRoot(path: String, permission: Permission): ParcelFileDescriptor {
        val escaped = SevenZipCommand.escape(path)
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        val process = try {
            ProcessBuilder("su", "-c", "cat $escaped")
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            try { readEnd.close() } catch (_: Exception) {}
            try { writeEnd.close() } catch (_: Exception) {}
            throw ShellException(
                message = "Root 启动进程失败: ${e.message}",
                command = "cat $escaped",
                permission = permission,
                stderr = e.message ?: ""
            )
        }

        val stderrBuf = StringBuilder()
        val tErr = Thread {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stderrBuf.appendLine(line)
                    }
                }
            } catch (_: Exception) {}
        }.apply { start() }

        // stdout → pipe writeEnd：将 su 进程的输出桥接到 pipe
        val tOut = Thread {
            try {
                val buf = ByteArray(FD_BUFFER_SIZE)
                val srcFd = (process.inputStream as java.io.FileInputStream).fd
                val dstFd = writeEnd.fileDescriptor
                while (true) {
                    val n = Os.read(srcFd, buf, 0, FD_BUFFER_SIZE)
                    if (n == -1) break
                    Os.write(dstFd, buf, 0, n)
                }
            } catch (_: Exception) {}
            finally {
                try { writeEnd.close() } catch (_: Exception) {}
            }
        }.apply { start() }

        // 异步等待进程结束，检查退出码
        Thread {
            try {
                process.waitFor()
                tOut.join(5000)
                tErr.join(5000)
                if (process.exitValue() != 0) {
                    val stderr = stderrBuf.toString().trim()
                    DiagnosticLog.log("ShellExecutor", "openForReadRoot 失败: exitCode=${process.exitValue()}, stderr=$stderr")
                    try { readEnd.close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }.start()

        return readEnd
    }

    /** APPLICANT：应用自身 UID 直接创建 */
    private fun openForWriteLocal(path: String, permission: Permission): ParcelFileDescriptor {
        return try {
            ParcelFileDescriptor.open(
                File(path),
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
            )
        } catch (e: Exception) {
            throw ShellException(
                message = "创建文件失败: ${e.message}",
                command = "openForWrite($path)",
                permission = permission,
                stderr = e.message ?: ""
            )
        }
    }

    /** ADB：通过 ShizukuAuthorizer 获取 PFD（ShellService uid 2000） */
    private fun openForWriteShizuku(path: String, permission: Permission): ParcelFileDescriptor {
        return ShizukuAuthorizer.openForWrite(path)
            ?: throw ShellException(
                message = "Shizuku 创建文件失败",
                command = "openForWrite($path)",
                permission = permission
            )
    }

    /** ROOT：su 进程(uid 0) 执行 cat > file，数据通过 pipe 从应用传入 */
    private fun openForWriteRoot(path: String, permission: Permission): ParcelFileDescriptor {
        val escaped = SevenZipCommand.escape(path)
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        val process = try {
            ProcessBuilder("su", "-c", "cat > $escaped")
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            try { readEnd.close() } catch (_: Exception) {}
            try { writeEnd.close() } catch (_: Exception) {}
            throw ShellException(
                message = "Root 启动进程失败: ${e.message}",
                command = "cat > $escaped",
                permission = permission,
                stderr = e.message ?: ""
            )
        }

        val stderrBuf = StringBuilder()
        val tErr = Thread {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stderrBuf.appendLine(line)
                    }
                }
            } catch (_: Exception) {}
        }.apply { start() }

        // pipe readEnd → stdin：将应用写入 pipe 的数据桥接到 su 进程的 stdin
        val tIn = Thread {
            try {
                val buf = ByteArray(FD_BUFFER_SIZE)
                val srcFd = readEnd.fileDescriptor
                val dstFd = (process.outputStream as java.io.FileOutputStream).fd
                while (true) {
                    val n = Os.read(srcFd, buf, 0, FD_BUFFER_SIZE)
                    if (n == -1) break
                    Os.write(dstFd, buf, 0, n)
                }
            } catch (_: Exception) {}
            finally {
                try { process.outputStream.close() } catch (_: Exception) {}
                try { readEnd.close() } catch (_: Exception) {}
            }
        }.apply { start() }

        // 异步等待进程结束，检查退出码
        Thread {
            try {
                process.waitFor()
                tIn.join(5000)
                tErr.join(5000)
                if (process.exitValue() != 0) {
                    val stderr = stderrBuf.toString().trim()
                    DiagnosticLog.log("ShellExecutor", "openForWriteRoot 失败: exitCode=${process.exitValue()}, stderr=$stderr")
                    try { writeEnd.close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }.start()

        return writeEnd
    }

    private const val FD_BUFFER_SIZE = 8192
}
