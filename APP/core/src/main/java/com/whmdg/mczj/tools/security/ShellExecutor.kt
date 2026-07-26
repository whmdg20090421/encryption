package com.whmdg.mczj.tools.security

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.util.DiagnosticLog
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
 * 执行委托给 ShellDaemon（持久 shell 进程池），避免每次 fork。
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

    // ── 同步执行 ──────────────────────────────────────────────────────

    /**
     * 执行 shell 命令，返回 stdout。
     * 任何错误均抛出 ShellException，UI 层通过 ErrorDialog 展示。
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
        return try {
            ShellDaemon.execute(resolved, command)
        } catch (e: ShellException) {
            Log.e("ShellExecutor", "执行失败: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e("ShellExecutor", "执行异常: ${e.message}", e)
            throw ShellException(
                message = "执行异常: ${e.message}",
                command = command,
                permission = permission,
                stderr = e.message ?: ""
            )
        }
    }

    // ── 流式执行 ──────────────────────────────────────────────────────

    /**
     * 流式执行 shell 命令，实时回调每行输出。
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
        try {
            ShellDaemon.executeStreaming(resolved, command, onOutputLine, cancelFlag)
        } catch (e: ShellException) {
            Log.e("ShellExecutor", "流式执行失败: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e("ShellExecutor", "流式执行异常: ${e.message}", e)
            throw ShellException(
                message = "流式执行异常: ${e.message}",
                command = command,
                permission = permission,
                stderr = e.message ?: ""
            )
        }
    }

    // ── stderr 流式执行 ───────────────────────────────────────────────

    /**
     * 执行命令，实时回调 stderr 每行输出。
     * 用于 PV 等将进度输出到 stderr 的工具。
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
        try {
            ShellDaemon.executeWithStderr(resolved, command, onStderrLine, cancelFlag)
        } catch (e: ShellException) {
            Log.e("ShellExecutor", "stderr执行失败: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e("ShellExecutor", "stderr执行异常: ${e.message}", e)
            throw ShellException(
                message = "stderr执行异常: ${e.message}",
                command = command,
                permission = permission,
                stderr = e.message ?: ""
            )
        }
    }

    // ── stdout 流式执行 ───────────────────────────────────────────────

    /**
     * 执行命令，逐行回调 stdout（用于文件复制等需要实时进度的场景）。
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
        try {
            ShellDaemon.executeWithStdout(resolved, command, onStdoutLine, cancelFlag)
        } catch (e: ShellException) {
            Log.e("ShellExecutor", "stdout执行失败: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e("ShellExecutor", "stdout执行异常: ${e.message}", e)
            throw ShellException(
                message = "stdout执行异常: ${e.message}",
                command = command,
                permission = permission,
                stderr = e.message ?: ""
            )
        }
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

    // ── FD 获取（openForRead / openForWrite）────────────────────────────

    /**
     * 以指定权限打开文件用于读取，返回 PFD。
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
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            }
            Permission.ADB -> {
                if (isShizukuAvailable()) {
                    ShizukuAuthorizer.openForRead(path)
                        ?: throw ShellException(
                            message = "Shizuku 打开文件失败",
                            command = "openForRead($path)",
                            permission = permission
                        )
                } else {
                    ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                }
            }
            Permission.APPLICANT -> {
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            }
            else -> throw ShellException(
                message = "resolvePermission 返回未处理的权限级别: $resolved",
                command = "openForRead($path)",
                permission = permission
            )
        }
    }

    /**
     * 以指定权限打开/创建文件用于写入，返回 PFD。
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
                ParcelFileDescriptor.open(
                    File(path),
                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
                )
            }
            Permission.ADB -> {
                if (isShizukuAvailable()) {
                    ShizukuAuthorizer.openForWrite(path)
                        ?: throw ShellException(
                            message = "Shizuku 创建文件失败",
                            command = "openForWrite($path)",
                            permission = permission
                        )
                } else {
                    ParcelFileDescriptor.open(
                        File(path),
                        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
                    )
                }
            }
            Permission.APPLICANT -> {
                ParcelFileDescriptor.open(
                    File(path),
                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
                )
            }
            else -> throw ShellException(
                message = "resolvePermission 返回未处理的权限级别: $resolved",
                command = "openForWrite($path)",
                permission = permission
            )
        }
    }
}
