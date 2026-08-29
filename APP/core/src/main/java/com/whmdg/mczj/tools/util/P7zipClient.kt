package com.whmdg.mczj.tools.util

import android.content.Context
import android.util.Log
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.ShellExecutor
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.security.ShizukuAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket
import kotlin.concurrent.thread

/**
 * P7zip 客户端 API。
 * 管理 daemon 生命周期，通过 TCP socket 与 daemon 通信。
 * 使用 Permission.MAX 自动选择最高可用权限。
 */
object P7zipClient {

    private const val TAG = "P7zipClient"
    private const val PORT = 19876
    private const val MAX_STARTUP_WAIT_MS = 5000L
    private const val CONNECTION_TIMEOUT_MS = 3000

    private var daemonProcess: Process? = null
    private var cachedPort: Int? = null
    private var cachedSocket: Socket? = null
    @Volatile private var lastActivityTime = System.currentTimeMillis()

    // ── 对外 API ──

    suspend fun listArchive(archivePath: String, password: String = ""): Result<String> = withContext(Dispatchers.IO) {
        ensureDaemonOrThrow()
        val args = mutableMapOf("archivePath" to archivePath)
        if (password.isNotEmpty()) args["password"] = password
        sendRequest("list", args)
    }

    suspend fun listArchiveDetail(archivePath: String, password: String = ""): Result<String> = withContext(Dispatchers.IO) {
        ensureDaemonOrThrow()
        val args = mutableMapOf("archivePath" to archivePath)
        if (password.isNotEmpty()) args["password"] = password
        sendRequest("list_detail", args)
    }

    suspend fun extractSingleFile(archivePath: String, fileName: String, outputDir: String, password: String = ""): Result<String> = withContext(Dispatchers.IO) {
        ensureDaemonOrThrow()
        val args = mutableMapOf(
            "archivePath" to archivePath,
            "fileName" to fileName,
            "outputDir" to outputDir
        )
        if (password.isNotEmpty()) args["password"] = password
        sendRequest("extract_single", args)
    }

    suspend fun extractAll(archivePath: String, outputDir: String, password: String = ""): Result<String> = withContext(Dispatchers.IO) {
        ensureDaemonOrThrow()
        val args = mutableMapOf(
            "archivePath" to archivePath,
            "outputDir" to outputDir
        )
        if (password.isNotEmpty()) args["password"] = password
        sendRequest("extract_all", args)
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
        val args = mutableMapOf(
            "sourcePaths" to sourcePaths.joinToString("|"),
            "outputDir" to outputPath,
            "format" to format,
            "level" to level.toString()
        )
        if (password.isNotEmpty()) args["password"] = password
        if (useAes) args["useAes"] = "true"
        if (encryptNames) args["encryptNames"] = "true"
        sendRequest("compress", args)
    }

    suspend fun detectPassword(archivePath: String): Result<String> = withContext(Dispatchers.IO) {
        ensureDaemonOrThrow()
        sendRequest("detect_password", mapOf("archivePath" to archivePath))
    }

    /**
     * 流式压缩（带进度回调）。
     * 通过 streaming 协议逐行读取进度。
     */
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
        val args = mutableMapOf(
            "sourcePaths" to sourcePaths.joinToString("|"),
            "outputDir" to outputPath,
            "format" to format,
            "level" to level.toString()
        )
        if (password.isNotEmpty()) args["password"] = password
        if (useAes) args["useAes"] = "true"
        if (encryptNames) args["encryptNames"] = "true"
        sendStreamingRequest("compress_stream", args, onLine)
    }

    /**
     * 流式解压（带进度回调）。
     */
    suspend fun extractStream(
        archivePath: String,
        outputDir: String,
        password: String = "",
        onLine: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        ensureDaemonOrThrow()
        val args = mutableMapOf(
            "archivePath" to archivePath,
            "outputDir" to outputDir
        )
        if (password.isNotEmpty()) args["password"] = password
        sendStreamingRequest("extract_stream", args, onLine)
    }

    /**
     * 发送流式请求，逐行读取响应。
     * 每行是 JSON 对象，最后一行 {"type":"result",...} 表示结束。
     */
    private fun sendStreamingRequest(cmd: String, args: Map<String, String>, onLine: (String) -> Unit): Result<String> {
        return try {
            val socket = getOrCreateSocket()
            val output = socket.getOutputStream()
            val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)

            // 构建并发送请求
            val argsJson = args.entries.joinToString(",") { (k, v) ->
                val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                "\"$k\":\"$escaped\""
            }
            val request = "{\"cmd\":\"$cmd\",\"args\":{$argsJson}}"
            val bytes = request.toByteArray(Charsets.UTF_8)
            val lengthBytes = byteArrayOf(
                ((bytes.size shr 24) and 0xFF).toByte(),
                ((bytes.size shr 16) and 0xFF).toByte(),
                ((bytes.size shr 8) and 0xFF).toByte(),
                (bytes.size and 0xFF).toByte()
            )
            output.write(lengthBytes)
            output.write(bytes)
            output.flush()

            // 逐行读取响应
            var resultOutput = ""
            var resultError = ""
            var success = false
            var foundResult = false

            while (true) {
                val line = input.readLine() ?: break
                if (line.isBlank()) continue
                lastActivityTime = System.currentTimeMillis()

                // 检查是否是最终结果
                if (line.contains("\"type\":\"result\"")) {
                    foundResult = true
                    success = line.contains("\"success\":true")
                    resultOutput = extractJsonField(line, "output") ?: ""
                    resultError = extractJsonField(line, "error") ?: ""
                    break
                }

                // 进度行，回调给调用方
                onLine(line)
            }

            if (!foundResult) {
                return Result.failure(RuntimeException("Daemon 流式响应未收到结果"))
            }

            if (success) Result.success(resultOutput)
            else Result.failure(RuntimeException(resultError.ifEmpty { "Daemon 执行失败" }))

        } catch (e: Exception) {
            cachedSocket = null
            Result.failure(RuntimeException("Daemon 流式通信失败: ${e.message}", e))
        }
    }

    // ── Daemon 生命周期管理 ──

    /**
     * 确保 daemon 可用。不可用则启动。
     * @throws RuntimeException 启动失败时
     */
    suspend fun ensureDaemonOrThrow() {
        if (isDaemonAlive()) return
        startDaemon()
    }

    /**
     * 启动 daemon 进程。使用 Permission.MAX 选择最高可用权限。
     */
    suspend fun startDaemon() = withContext(Dispatchers.IO) {
        val context = com.whmdg.mczj.tools.ToolsApp.instance
            ?: throw RuntimeException("ToolsApp 未初始化，无法启动 P7zipDaemon")

        val binaryPath = try {
            BinaryExtractor.ensureExtracted(context).absolutePath
        } catch (e: Exception) {
            throw RuntimeException("7zzs 二进制缺失: ${e.message}", e)
        }

        val apkPath = context.applicationInfo.sourceDir
        val portFilePath = File(context.cacheDir, "p7zip_daemon_port")

        // 确定权限级别
        val permission = resolveMaxPermission()

        // 构建启动命令
        val classPath = apkPath
        val mainClass = "com.whmdg.mczj.tools.util.P7zipDaemonMain"
        val daemonCmd = "app_process -Djava.class.path=$classPath / $mainClass $binaryPath $PORT ${portFilePath.absolutePath}"

        val fullCmd = when (permission) {
            Permission.ROOT -> "su -c \"$daemonCmd\""
            Permission.ADB -> {
                // Shizuku 转发
                val shizukuService = ShizukuAuthorizer.peekService()
                if (shizukuService != null) {
                    // 通过 Shizuku 执行
                    daemonCmd
                } else {
                    daemonCmd
                }
            }
            else -> daemonCmd
        }

        Log.d(TAG, "启动 daemon: 权限=$permission")
        Log.d(TAG, "命令: $fullCmd")

        // 启动进程
        val process = try {
            if (permission == Permission.ROOT) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", daemonCmd))
            } else {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", daemonCmd))
            }
        } catch (e: Exception) {
            throw RuntimeException("启动 P7zipDaemon 失败: ${e.message}", e)
        }

        daemonProcess = process

        // 等待端口文件写入（daemon 启动后会写入端口号）
        var waited = 0L
        while (waited < MAX_STARTUP_WAIT_MS) {
            if (portFilePath.exists()) {
                val portStr = portFilePath.readText().trim()
                val port = portStr.toIntOrNull()
                if (port != null) {
                    cachedPort = port
                    Log.d(TAG, "Daemon 已启动，端口: $port")
                    // 持久化权限
                    persistPermission(permission)
                    return@withContext
                }
            }
            delay(100)
            waited += 100
        }

        // 超时：读取进程输出诊断
        val stderr = process.errorStream.bufferedReader().readText()
        val stdout = process.inputStream.bufferedReader().readText()
        throw RuntimeException("P7zipDaemon 启动超时。stdout=$stdout, stderr=$stderr")
    }

    /**
     * 主动关闭 daemon。
     */
    fun shutdownDaemon() {
        try {
            cachedSocket?.close()
        } catch (_: Exception) {}

        try {
            // 发送 shutdown 命令
            val socket = Socket("127.0.0.1", cachedPort ?: PORT)
            socket.use { s ->
                val output = s.getOutputStream()
                val request = "{\"cmd\":\"shutdown\",\"args\":{}}"
                val bytes = request.toByteArray(Charsets.UTF_8)
                val lengthBytes = byteArrayOf(
                    ((bytes.size shr 24) and 0xFF).toByte(),
                    ((bytes.size shr 16) and 0xFF).toByte(),
                    ((bytes.size shr 8) and 0xFF).toByte(),
                    (bytes.size and 0xFF).toByte()
                )
                output.write(lengthBytes)
                output.write(bytes)
                output.flush()
            }
        } catch (_: Exception) {}

        // 杀进程
        try { daemonProcess?.destroy() } catch (_: Exception) {}
        daemonProcess = null
        cachedPort = null
        cachedSocket = null

        // 清除持久化
        clearPersistedPermission()
        Log.d(TAG, "Daemon 已关闭")
    }

    fun isDaemonAlive(): Boolean {
        val port = cachedPort ?: return false
        return try {
            Socket("127.0.0.1", port).use { true }
        } catch (_: Exception) {
            false
        }
    }

    // ── Socket 通信 ──

    private fun sendRequest(cmd: String, args: Map<String, String>): Result<String> {
        return try {
            val socket = getOrCreateSocket()
            val output = socket.getOutputStream()
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))

            // 构建 JSON
            val argsJson = args.entries.joinToString(",") { (k, v) ->
                val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                "\"$k\":\"$escaped\""
            }
            val request = "{\"cmd\":\"$cmd\",\"args\":{$argsJson}}"

            // 发送
            val bytes = request.toByteArray(Charsets.UTF_8)
            val lengthBytes = byteArrayOf(
                ((bytes.size shr 24) and 0xFF).toByte(),
                ((bytes.size shr 16) and 0xFF).toByte(),
                ((bytes.size shr 8) and 0xFF).toByte(),
                (bytes.size and 0xFF).toByte()
            )
            output.write(lengthBytes)
            output.write(bytes)
            output.flush()

            // 读取响应
            val respLengthBytes = readBytes(input, 4) ?: return Result.failure(RuntimeException("Daemon 连接断开"))
            val respLength = ((respLengthBytes[0].toInt() and 0xFF) shl 24) or
                    ((respLengthBytes[1].toInt() and 0xFF) shl 16) or
                    ((respLengthBytes[2].toInt() and 0xFF) shl 8) or
                    (respLengthBytes[3].toInt() and 0xFF)

            val respBytes = readBytes(input, respLength) ?: return Result.failure(RuntimeException("Daemon 响应不完整"))
            val response = String(respBytes, Charsets.UTF_8)

            // 解析响应
            val success = response.contains("\"success\":true")
            val outputStr = extractJsonField(response, "output") ?: ""
            val errorStr = extractJsonField(response, "error") ?: ""

            if (success) Result.success(outputStr)
            else Result.failure(RuntimeException(errorStr.ifEmpty { "Daemon 执行失败" }))

        } catch (e: Exception) {
            // 连接断开，清除缓存
            cachedSocket = null
            Result.failure(RuntimeException("Daemon 通信失败: ${e.message}", e))
        }
    }

    private fun getOrCreateSocket(): Socket {
        cachedSocket?.let {
            if (!it.isClosed && it.isConnected) return it
        }
        val port = cachedPort ?: throw RuntimeException("Daemon 未启动")
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 60_000 // 60秒读取超时
        cachedSocket = socket
        return socket
    }

    private fun readBytes(reader: BufferedReader, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = reader.read(buf, offset, count - offset)
            if (read == -1) return null
            offset += read
        }
        return buf
    }

    // ── 权限解析 ──

    private fun resolveMaxPermission(): Permission {
        // 先检查持久化的权限
        val persisted = getPersistedPermission()
        if (persisted != null) return persisted

        // 自动选择最高可用权限
        if (SpecialPermissionVerifier.isRootAvailable()) return Permission.ROOT
        if (ShizukuAuthorizer.peekService() != null) return Permission.ADB
        return Permission.APPLICANT
    }

    // ── 持久化 ──

    private fun persistPermission(permission: Permission) {
        try {
            val context = com.whmdg.mczj.tools.ToolsApp.instance ?: return
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
            val context = com.whmdg.mczj.tools.ToolsApp.instance ?: return null
            val prefs = context.getSharedPreferences(AppDataPaths.PREFS_P7ZIP_DAEMON, Context.MODE_PRIVATE)
            val level = prefs.getString("permission_level", null) ?: return null
            val pid = prefs.getInt("pid", 0)
            // 检查进程是否还活着
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
            val context = com.whmdg.mczj.tools.ToolsApp.instance ?: return
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

    // ── JSON 工具 ──

    private fun extractJsonField(json: String, field: String): String? {
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]*?)\""
        val match = Regex(pattern).find(json) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
    }
}
