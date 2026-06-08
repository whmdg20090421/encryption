package com.whmdg.mczj.tools.security

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku UserService 实现。
 * 运行在 Shizuku 进程中，以 shell (uid 2000) 或 root (uid 0) 身份执行命令。
 * 通过 Binder IPC 调用，比 newProcess 的 fork+exec 快约 50 倍。
 */
class ShellService : IShellService.Stub() {

    override fun execute(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()
            "$stdout---STDERR---\n$stderr---EXIT---\n$exitCode"
        } catch (e: Exception) {
            "---STDERR---\n${e.message ?: "执行异常"}---EXIT---\n-1"
        }
    }

    override fun destroy() {
        // Shizuku 调用销毁时的清理（当前无需特殊处理）
    }
}
