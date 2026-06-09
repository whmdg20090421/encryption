package com.whmdg.mczj.tools.security

import android.util.Base64
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

            // 并发读取 stdout 和 stderr，避免管道缓冲区满导致死锁
            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()

            val tOut = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        val buf = CharArray(8192)
                        var n: Int
                        while (reader.read(buf).also { n = it } != -1) {
                            stdoutBuf.append(buf, 0, n)
                        }
                    }
                } catch (_: Exception) {}
            }
            val tErr = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        val buf = CharArray(8192)
                        var n: Int
                        while (reader.read(buf).also { n = it } != -1) {
                            stderrBuf.append(buf, 0, n)
                        }
                    }
                } catch (_: Exception) {}
            }

            tOut.start()
            tErr.start()
            process.waitFor()
            tOut.join(5000)
            tErr.join(5000)

            val stdout = stdoutBuf.toString().trimEnd()
            val stderr = stderrBuf.toString().trimEnd()
            val exitCode = process.exitValue()

            // 使用 Base64 编码避免分隔符冲突
            val stdoutB64 = Base64.encodeToString(stdout.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val stderrB64 = Base64.encodeToString(stderr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "$stdoutB64\n$stderrB64\n$exitCode"
        } catch (e: Exception) {
            val errB64 = Base64.encodeToString(
                (e.message ?: "执行异常").toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            "\n$errB64\n-1"
        }
    }

    override fun destroy() {
        // Shizuku 调用销毁时的清理（当前无需特殊处理）
    }
}
