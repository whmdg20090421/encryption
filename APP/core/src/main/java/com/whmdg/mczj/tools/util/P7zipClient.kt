package com.whmdg.mczj.tools.util

import android.content.Context
import android.util.Log
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.security.ShizukuAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread

/**
 * P7zip 客户端 API（stdin/stdout 协议版）。
 * 管理 daemon 生命周期，通过 Process stdin/stdout 与 daemon 通信。
 * 使用 Permission.MAX 自动选择最高可用权限。
 */
object P7zipClient {

    private const val TAG = "P7zipClient"
    private const val MAX_STARTUP_WAIT_MS = 5000L
    private const val MARKER = "<<<END>>>"
    private const val OK = "<<<OK>>>"
    private const val ERR = "<<<ERR>>>"

    private var daemonProcess: Process? = null
    private var daemonStdin: java.io.BufferedWriter? = null
    private var daemonOut: java.io.BufferedReader? = null

    // ── 对外 API ──

    suspend fun listArchive(archivePath: String, password: String = ""): Result<String> = withContext(Dispatchers.IO) {
        ensureDaemonOrThrow()
        sendCommand(listOfNotNull("list", archivePath, password.ifEmpty { null }))
    }

    suspend fun listArchiveDetail(archivePath: String, password: String = ""): Result<String> = withContext(Dispatchers.IO) {
        ensureDaemonOrThrow()
        sendCommand(listOfNotNull("list_detail", archivePath, password.ifEmpty { null }))
    }

    suspend fun extractSingleFile(archivePath: String, fileName: String, outputDir: String, password: String = ""): Result<String> = withContext(Dispatchers.IO) {
        ensureDaemonOrThrow()
        sendCommand(listOfNotNull("extract_single", archivePath, fileName, outputDir, password.ifEmpty { null }))
    }

    suspend fun extractAll(archivePath: String, outputDir: String, password: String = ""): Result<String> = withContext(Dispatchers.IO) {
        ensureDaemonOrThrow()
        sendCommand(listOfNotNull("extract_all", archivePath, outputDir, password.ifEmpty { null }))
    }

    suspend fun compress(
        sourcePaths: List<String>,
        outputPath: String,
        format: String,
        level: Int,
        password: String = "",
        useAes: Boolean = false,
        encryptNames: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        ensureDaemonOrThrow()
        sendCommand(buildList("compress", sourcePaths, outputPath, format, level, password, useAes, encryptNames))
    }

    suspend fun detectPassword(archivePath: String): Result<String> = withContext(Dispatchers.IO) {
        ensureDaemonOrThrow()
        sendCommand(listOf("detect_password", archivePath))
    }

    suspend fun compressStream(
        sourcePaths: List<String>,
        outputPath: String,
        format: String,
        level: Int,
        password: String = "",
        useAes: Boolean = false,
        encryptNames: Boolean = false,
        onLine: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        ensureDaemonOrThrow()
        sendStreamingCommand(
            buildList("compress_stream", sourcePaths, outputPath, format, level, password, useAes, encryptNames),
            onLine
        )
    }

    suspend fun extractStream(
        archivePath: String,
        outputDir: String,
        password: String = "",
        onLine: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        ensureDaemonOrThrow()
        sendStreamingCommand(
            listOfNotNull("extract_stream", archivePath, outputDir, password.ifEmpty { null }),
            onLine
        )
    }

    // ── 命令发送 ──

    // 位置参数顺序: cmd, sourcePaths, outputDir, format, level, password, useAes, encryptNames
    private fun buildList(
        cmd: String, sourcePaths: List<String>, outputDir: String,
        format: String, level: Int, password: String, useAes: Boolean, encryptNames: Boolean
    ): List<String> {
        val list = mutableListOf(cmd, sourcePaths.joinToString("|"), outputDir, format, level.toString())
        list.add(password)
        if (useAes) list.add("true")
        if (encryptNames) list.add("true")
        return list
    }

    private fun sendCommand(args: List<String>): Result<String> {
        return try {
            val stdin = daemonStdin ?: return Result.failure(RuntimeException("Daemon 未启动"))
            val out = daemonOut ?: return Result.failure(RuntimeException("Daemon 未启动"))

            // 写命令
            for (arg in args) {
                stdin.write(arg)
                stdin.newLine()
            }
            stdin.write(MARKER)
            stdin.newLine()
            stdin.flush()

            // 读响应
            readResponse(out)
        } catch (e: Exception) {
            Log.e(TAG, "命令通信失败", e)
            markDead()
            Result.failure(RuntimeException("Daemon 通信失败: ${e.message}", e))
        }
    }

    private fun sendStreamingCommand(args: List<String>, onLine: (String) -> Unit): Result<String> {
        return try {
            val stdin = daemonStdin ?: return Result.failure(RuntimeException("Daemon 未启动"))
            val out = daemonOut ?: return Result.failure(RuntimeException("Daemon 未启动"))

            // 写命令
            for (arg in args) {
                stdin.write(arg)
                stdin.newLine()
            }
            stdin.write(MARKER)
            stdin.newLine()
            stdin.flush()

            // 读响应（流式）
            readStreamingResponse(out, onLine)
        } catch (e: Exception) {
            Log.e(TAG, "流式命令通信失败", e)
            markDead()
            Result.failure(RuntimeException("Daemon 通信失败: ${e.message}", e))
        }
    }

    // ── 响应读取 ──

    /**
     * 读取普通响应：读到 OK/ERR 行提取结果，继续读到 END 标记。
     */
    private fun readResponse(reader: java.io.BufferedReader): Result<String> {
        val sb = StringBuilder()
        var success: Boolean? = null

        while (true) {
            val line = reader.readLine() ?: break
            if (line == MARKER) break
            if (success == null) {
                when (line) {
                    OK -> success = true
                    ERR -> success = false
                    else -> sb.appendLine(line)
                }
            } else {
                sb.appendLine(line)
            }
        }

        if (success == null) return Result.failure(RuntimeException("Daemon 连接中断"))
        val output = sb.toString().trimEnd('\n', '\r')
        return if (success) Result.success(output) else Result.failure(RuntimeException(output.ifEmpty { "Daemon 执行失败" }))
    }

    /**
     * 读取流式响应：进度行回调 onLine，最后 OK/ERR + END。
     */
    private fun readStreamingResponse(reader: java.io.BufferedReader, onLine: (String) -> Unit): Result<String> {
        var success: Boolean? = null
        var lastLine = ""

        while (true) {
            val line = reader.readLine() ?: break
            if (line == MARKER) break

            if (success == null) {
                when (line) {
                    OK -> { success = true; lastLine = "" }
                    ERR -> { success = false; lastLine = "" }
                    else -> onLine(line)
                }
            } else {
                lastLine = if (lastLine.isEmpty()) line else lastLine + "\n" + line
            }
        }

        if (success == null) return Result.failure(RuntimeException("Daemon 流式响应未收到结果"))
        return if (success) Result.success(lastLine) else Result.failure(RuntimeException(lastLine.ifEmpty { "Daemon 执行失败" }))
    }

    // ── Daemon 生命周期管理 ──

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun ensureDaemonOrThrow() {
        if (isDaemonAlive()) return
        startDaemon()
    }

    suspend fun startDaemon() = withContext(Dispatchers.IO) {
        val context = appContext
            ?: throw RuntimeException("P7zipClient 未初始化，请先调用 init(context)")

        val binaryPath = try {
            BinaryExtractor.ensureExtracted(context).absolutePath
        } catch (e: Exception) {
            throw RuntimeException("7zzs 二进制缺失: ${e.message}", e)
        }

        val apkPath = context.applicationInfo.sourceDir
        val permission = resolveMaxPermission()
        val mainClass = "com.whmdg.mczj.tools.util.P7zipDaemonMain"
        val daemonCmd = "app_process -Djava.class.path=$apkPath / $mainClass $binaryPath"

        Log.d(TAG, "启动 daemon: 权限=$permission")

        val process = try {
            when (permission) {
                Permission.ROOT -> Runtime.getRuntime().exec(arrayOf("su", "-c", daemonCmd))
                else -> Runtime.getRuntime().exec(arrayOf("sh", "-c", daemonCmd))
            }
        } catch (e: Exception) {
            throw RuntimeException("启动 P7zipDaemon 失败: ${e.message}", e)
        }

        daemonProcess = process
        daemonStdin = process.outputStream.bufferedWriter(Charsets.UTF_8)
        daemonOut = process.inputStream.bufferedReader(Charsets.UTF_8)

        // 后台排空 stderr，防止 pipe buffer 满导致 daemon 阻塞
        val stderrBuf = StringBuilder()
        thread(isDaemon = true) {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { stderrBuf.appendLine(it) }
            } catch (_: Exception) {}
        }

        // 等待 <<<READY>>>（5 秒超时）
        val ready = withTimeoutOrNull(MAX_STARTUP_WAIT_MS) {
            daemonOut?.readLine()
        }

        if (ready == null || !ready.contains("READY")) {
            val stderr = stderrBuf.toString().trim()
            val stdout = try { daemonOut?.readLine() } catch (_: Exception) { null }
            daemonProcess = null
            daemonStdin = null
            daemonOut = null
            throw RuntimeException(
                "P7zipDaemon 启动超时\n权限=$permission" +
                    (if (stdout != null) "\nstdout: $stdout" else "") +
                    (if (stderr.isNotEmpty()) "\nstderr:\n$stderr" else "")
            )
        }

        persistPermission(permission)
        Log.d(TAG, "Daemon 已启动, PID=${getPid(process)}")
    }

    fun shutdownDaemon() {
        try {
            daemonStdin?.write("shutdown")
            daemonStdin?.newLine()
            daemonStdin?.write(MARKER)
            daemonStdin?.newLine()
            daemonStdin?.flush()
        } catch (_: Exception) {}

        Thread.sleep(200)
        try { daemonProcess?.destroy() } catch (_: Exception) {}
        daemonProcess = null
        daemonStdin = null
        daemonOut = null
        clearPersistedPermission()
        Log.d(TAG, "Daemon 已关闭")
    }

    fun isDaemonAlive(): Boolean {
        val process = daemonProcess ?: return false
        if (process.outputStream.closed()) {
            markDead()
            return false
        }
        val pid = getPid(process)
        return pid > 0 && isProcessAlive(pid)
    }

    private fun markDead() {
        daemonProcess = null
        daemonStdin = null
        daemonOut = null
    }

    // ── 权限解析 ──

    private fun resolveMaxPermission(): Permission {
        val persisted = getPersistedPermission()
        if (persisted != null) return persisted

        if (SpecialPermissionVerifier.isRootAvailable()) return Permission.ROOT
        if (ShizukuAuthorizer.getShellService() != null) return Permission.ADB
        return Permission.APPLICANT
    }

    // ── 持久化 ──

    private fun persistPermission(permission: Permission) {
        try {
            val context = appContext ?: return
            val prefs = context.getSharedPreferences(AppDataPaths.PREFS_P7ZIP_DAEMON, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("permission_level", permission.name)
                .putInt("pid", daemonProcess?.let { getPid(it) } ?: 0)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "持久化权限失败: ${e.message}")
        }
    }

    private fun getPersistedPermission(): Permission? {
        return try {
            val context = appContext ?: return null
            val prefs = context.getSharedPreferences(AppDataPaths.PREFS_P7ZIP_DAEMON, Context.MODE_PRIVATE)
            val level = prefs.getString("permission_level", null) ?: return null
            val pid = prefs.getInt("pid", 0)
            if (pid > 0 && isProcessAlive(pid)) {
                Permission.valueOf(level)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun clearPersistedPermission() {
        try {
            val context = appContext ?: return
            val prefs = context.getSharedPreferences(AppDataPaths.PREFS_P7ZIP_DAEMON, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        } catch (_: Exception) {}
    }

    private fun isProcessAlive(pid: Int): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("kill", "-0", pid.toString())).waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun getPid(process: Process): Int {
        return try {
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(process)
        } catch (_: Exception) {
            0
        }
    }
}
