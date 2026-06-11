package com.whmdg.mczj.tools.security

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Shizuku UserService 实现。
 * 运行在 Shizuku 进程中，以 shell (uid 2000) 或 root (uid 0) 身份执行命令。
 *
 * 采用持久化 sh 进程：保持一个长驻 shell，通过 stdin 管道发送命令，
 * 用 UUID 哨兵标记读取结果，避免每次调用都 fork+exec 新进程。
 *
 * stderr 重定向到 stdout（2>&1），避免双流读取死锁。
 */
class ShellService : IShellService.Stub() {

    companion object {
        private const val TAG = "ShellService"
    }

    private var shellProcess: Process? = null
    private var shellStdin: OutputStream? = null
    private var shellStdout: BufferedReader? = null
    private val lock = ReentrantLock()

    override fun execute(command: String): String {
        return try {
            executeViaPersistentShell(command)
        } catch (e: Exception) {
            Log.e(TAG, "持久 shell 执行失败: ${e.message}")
            destroyShell()
            // 重试一次（会重建 shell 进程）
            try {
                executeViaPersistentShell(command)
            } catch (e2: Exception) {
                Log.e(TAG, "重试仍失败: ${e2.message}")
                val errB64 = Base64.encodeToString(
                    (e2.message ?: "Shell 执行异常").toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                "\n$errB64\n-1"
            }
        }
    }

    private fun executeViaPersistentShell(command: String): String = lock.withLock {
        ensureShellRunning()

        val stdin = shellStdin!!
        val stdout = shellStdout!!

        // UUID 哨兵：echo "EXIT:<uuid>:$?" 用于标记命令结束和捕获退出码
        val marker = UUID.randomUUID().toString()
        val wrapped = "$command 2>&1; echo \"EXIT:$marker:\$?\"\n"

        // 发送命令
        stdin.write(wrapped.toByteArray(Charsets.UTF_8))
        stdin.flush()

        // 逐行读取直到找到哨兵标记
        val output = StringBuilder()
        var exitCode = -1

        while (true) {
            val line = stdout.readLine() ?: throw IllegalStateException("Shell 进程已退出")
            if (line.startsWith("EXIT:$marker:")) {
                exitCode = line.substringAfter("EXIT:$marker:").trim().toIntOrNull() ?: -1
                break
            }
            if (output.isNotEmpty()) output.append('\n')
            output.append(line)
        }

        // 格式: "stdoutBase64\nstderrBase64\nexitCode"
        val stdoutB64 = Base64.encodeToString(
            output.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        "$stdoutB64\n\n$exitCode"
    }

    /** 确保持久 shell 进程正在运行，调用方需持有 lock */
    private fun ensureShellRunning() {
        if (shellProcess != null) return

        Log.i(TAG, "创建持久 shell 进程")
        val process = Runtime.getRuntime().exec(arrayOf("sh"))
        shellProcess = process
        shellStdin = process.outputStream
        shellStdout = BufferedReader(InputStreamReader(process.inputStream), 8192)
    }

    /** 销毁持久 shell 进程 */
    private fun destroyShell() {
        try { shellStdin?.close() } catch (_: Exception) {}
        try { shellProcess?.destroy() } catch (_: Exception) {}
        shellStdin = null
        shellStdout = null
        shellProcess = null
    }

    override fun destroy() {
        lock.withLock {
            destroyShell()
            Log.i(TAG, "ShellService 已销毁")
        }
    }
}
