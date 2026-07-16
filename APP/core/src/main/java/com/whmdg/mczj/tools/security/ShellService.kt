package com.whmdg.mczj.tools.security

import android.os.ParcelFileDescriptor
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

    /**
     * 执行命令并将 7zzs 进度实时写入文件。
     * 运行在 Shizuku 进程中，UID 2000 或 0。
     * Binder 调用阻塞直到命令完成。
     */
    override fun executeStreaming(command: String, progressPath: String) {
        val progressFile = java.io.File(progressPath)
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))

            // stdout 读取线程：解析进度并写入文件
            val tOut = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (Thread.interrupted()) break
                            val l = line ?: continue
                            // 解析 7zzs 进度行: "  75%  1"（百分比 + 文件序号）
                            val match = Regex("""\s*(\d+)%\s+(\d+)""").find(l)
                            if (match != null) {
                                val percent = match.groupValues[1]
                                val fileNum = match.groupValues[2]
                                try { progressFile.writeText("$percent:$fileNum\n") } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // stderr 读取线程（丢弃，避免管道缓冲满）
            val tErr = Thread {
                try {
                    process.errorStream.bufferedReader().use { reader ->
                        while (reader.readLine() != null) { /* 丢弃 */ }
                    }
                } catch (_: Exception) {}
            }

            tOut.start()
            tErr.start()
            process.waitFor()
            tOut.join(5000)
            tErr.join(5000)

            val exitCode = process.exitValue()
            try { progressFile.writeText("DONE:$exitCode\n") } catch (_: Exception) {}
        } catch (e: Exception) {
            try { progressFile.writeText("DONE:-1\n") } catch (_: Exception) {}
        }
    }

    /**
     * 执行命令，stderr 通过 PFD 管道实时流式返回。
     * 运行在 Shizuku 进程中，UID 2000 或 0。
     * Binder 调用阻塞直到命令完成。
     */
    override fun executeStreamingStderr(command: String, stderrWriteFd: ParcelFileDescriptor): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))

            val stdoutBuf = StringBuilder()
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
                    ParcelFileDescriptor.AutoCloseOutputStream(stderrWriteFd).use { os ->
                        process.errorStream.use { it.copyTo(os) }
                    }
                } catch (_: Exception) {}
            }

            tOut.start()
            tErr.start()
            process.waitFor()
            tOut.join(5000)
            tErr.join(5000)

            val stdout = stdoutBuf.toString().trimEnd()
            val exitCode = process.exitValue()
            val stdoutB64 = Base64.encodeToString(stdout.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "$stdoutB64\n\n$exitCode"
        } catch (e: Exception) {
            val errB64 = Base64.encodeToString(
                (e.message ?: "执行异常").toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            "\n$errB64\n-1"
        }
    }

    /**
     * 执行命令，stdout 通过 PFD 管道实时流式返回。
     * 与 executeStreamingStderr 对称：stdout → pipe，stderr → buffer。
     */
    override fun executeStreamingStdout(command: String, stdoutWriteFd: ParcelFileDescriptor): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))

            val stderrBuf = StringBuilder()
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
            val tOut = Thread {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(stdoutWriteFd).use { os ->
                        process.inputStream.use { it.copyTo(os) }
                    }
                } catch (_: Exception) {}
            }

            tErr.start()
            tOut.start()
            process.waitFor()
            tErr.join(5000)
            tOut.join(5000)

            val stderr = stderrBuf.toString().trimEnd()
            val exitCode = process.exitValue()
            val stderrB64 = Base64.encodeToString(stderr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "\n$stderrB64\n$exitCode"
        } catch (e: Exception) {
            val errB64 = Base64.encodeToString(
                (e.message ?: "执行异常").toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            "\n$errB64\n-1"
        }
    }

    /**
     * 以提升权限打开文件用于读取，返回 PFD 传回调用方进程。
     * ShellService 运行在 Shizuku 进程中，uid 2000 或 0，可打开应用自身无权访问的文件。
     * PFD 通过 Binder IPC 传递回应用进程后，应用可用 FileInputStream(fd) 直接读取。
     */
    override fun openForRead(path: String): ParcelFileDescriptor {
        return ParcelFileDescriptor.open(
            java.io.File(path),
            ParcelFileDescriptor.MODE_READ_ONLY
        )
    }

    /**
     * 以提升权限打开/创建文件用于写入，返回 PFD 传回调用方进程。
     * 若文件已存在则截断为 0。ShellService 运行在 Shizuku 进程中，uid 2000 或 0。
     * PFD 通过 Binder IPC 传递回应用进程后，应用可用 FileOutputStream(fd) 直接写入。
     */
    override fun openForWrite(path: String): ParcelFileDescriptor {
        return ParcelFileDescriptor.open(
            java.io.File(path),
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
        )
    }

    override fun destroy() {
        // Shizuku 调用销毁时的清理（当前无需特殊处理）
    }
}
