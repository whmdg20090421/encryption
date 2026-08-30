package com.whmdg.mczj.tools.security

import android.os.ParcelFileDescriptor
import android.util.Base64

/**
 * Shizuku UserService 实现。
 * 运行在 Shizuku 进程中，以 shell (uid 2000) 或 root (uid 0) 身份执行命令。
 * 通过 Binder IPC 调用，使用 ShellDaemon 维护持久 shell 进程。
 */
class ShellService : IShellService.Stub() {

    override fun execute(command: String): String {
        return try {
            val stdout = ShellDaemon.execute(Permission.APPLICANT, command)
            val stdoutB64 = Base64.encodeToString(stdout.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "$stdoutB64\n\n0"
        } catch (e: ShellException) {
            val stdoutB64 = Base64.encodeToString((e.stderr).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val errB64 = Base64.encodeToString((e.message ?: "").toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "$stdoutB64\n$errB64\n${e.exitCode}"
        } catch (e: Exception) {
            val errB64 = Base64.encodeToString(
                (e.message ?: "执行异常").toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            "\n$errB64\n-1"
        }
    }

    /**
     * 执行命令并将进度实时写入文件。
     */
    override fun executeStreaming(command: String, progressPath: String) {
        val progressFile = java.io.File(progressPath)
        try {
            ShellDaemon.executeStreaming(
                Permission.APPLICANT,
                command,
                onOutputLine = { line ->
                    // 解析进度行: "  75%  1"（百分比 + 文件序号）
                    val match = Regex("""\s*(\d+)%\s+(\d+)""").find(line)
                    if (match != null) {
                        val percent = match.groupValues[1]
                        val fileNum = match.groupValues[2]
                        try { progressFile.writeText("$percent:$fileNum\n") } catch (_: Exception) {}
                    }
                }
            )
            try { progressFile.writeText("DONE:0\n") } catch (_: Exception) {}
        } catch (e: ShellException) {
            try { progressFile.writeText("DONE:${e.exitCode}\n") } catch (_: Exception) {}
        } catch (e: Exception) {
            try { progressFile.writeText("DONE:-1\n") } catch (_: Exception) {}
        }
    }

    /**
     * 执行命令，stderr 通过 PFD 管道实时流式返回。
     */
    override fun executeStreamingStderr(command: String, stderrWriteFd: ParcelFileDescriptor): String {
        return try {
            val os = ParcelFileDescriptor.AutoCloseOutputStream(stderrWriteFd)
            ShellDaemon.executeWithStderr(
                Permission.APPLICANT,
                command,
                onStderrLine = { line ->
                    try { os.write("$line\n".toByteArray()); os.flush() } catch (_: Exception) {}
                }
            )
            os.close()
            "\n\n0"
        } catch (e: ShellException) {
            val errB64 = Base64.encodeToString((e.message ?: "").toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "\n$errB64\n${e.exitCode}"
        } catch (e: Exception) {
            val errB64 = Base64.encodeToString(
                (e.message ?: "执行异常").toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            "\n$errB64\n-1"
        }
    }

    /**
     * 执行命令，stdout 通过 PFD 管道实时流式返回。
     */
    override fun executeStreamingStdout(command: String, stdoutWriteFd: ParcelFileDescriptor): String {
        return try {
            val os = ParcelFileDescriptor.AutoCloseOutputStream(stdoutWriteFd)
            ShellDaemon.executeWithStdout(
                Permission.APPLICANT,
                command,
                onStdoutLine = { line ->
                    try { os.write("$line\n".toByteArray()); os.flush() } catch (_: Exception) {}
                }
            )
            os.close()
            "\n\n0"
        } catch (e: ShellException) {
            val errB64 = Base64.encodeToString((e.message ?: "").toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "\n$errB64\n${e.exitCode}"
        } catch (e: Exception) {
            val errB64 = Base64.encodeToString(
                (e.message ?: "执行异常").toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            "\n$errB64\n-1"
        }
    }

    /**
     * 以提升权限打开文件用于读取，返回 PFD 传回调用方进程。
     */
    override fun openForRead(path: String): ParcelFileDescriptor {
        return ParcelFileDescriptor.open(
            java.io.File(path),
            ParcelFileDescriptor.MODE_READ_ONLY
        )
    }

    /**
     * 以提升权限打开/创建文件用于写入，返回 PFD 传回调用方进程。
     */
    override fun openForWrite(path: String): ParcelFileDescriptor {
        return ParcelFileDescriptor.open(
            java.io.File(path),
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
        )
    }

    override fun destroy() {
        ShellDaemon.destroy()
    }
}
