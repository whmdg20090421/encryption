package com.whmdg.mczj.tools.util

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * P7zip 守护进程入口。
 * 通过 app_process 启动独立 JVM 进程，监听 Unix socket，执行7zzs命令。
 * 不依赖 Android 框架（无 Context、SharedPreferences 等）。
 *
 * 启动方式：
 *   su -c "app_process -Djava.class.path=<dexPath> / com.whmdg.mczj.tools.util.P7zipDaemonMain <binaryPath> <socketPath>"
 */
object P7zipDaemonMain {

    private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L // 10 分钟
    private const val TAG = "P7zipDaemon"

    @Volatile
    private var lastActivityTime = System.currentTimeMillis()

    @Volatile
    private var shutdownRequested = false

    private var serverSocket: ServerSocket? = null

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            System.err.println("Usage: P7zipDaemonMain <binaryPath> <port> <portFilePath>")
            exitProcess(1)
        }

        val binaryPath = args[0]
        val port = args[1].toIntOrNull() ?: 19876
        val portFilePath = args[2]

        serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        val actualPort = serverSocket!!.localPort

        // 写入端口号到文件，供 app 连接
        java.io.File(portFilePath).writeText(actualPort.toString())

        System.out.println("$TAG: 监听 127.0.0.1:$actualPort, binary=$binaryPath")

        lastActivityTime = System.currentTimeMillis()

        // 空闲超时守护线程
        val watchdog = thread(isDaemon = true) {
            while (!shutdownRequested) {
                Thread.sleep(60_000)
                val idle = System.currentTimeMillis() - lastActivityTime
                if (idle > IDLE_TIMEOUT_MS) {
                    System.out.println("$TAG: 空闲 ${idle / 1000}s，自动关闭")
                    shutdown()
                }
            }
        }

        // 主循环：accept 连接
        while (!shutdownRequested) {
            try {
                val client = serverSocket!!.accept()
                lastActivityTime = System.currentTimeMillis()
                thread(isDaemon = true) { handleClient(client, binaryPath) }
            } catch (e: Exception) {
                if (!shutdownRequested) {
                    System.err.println("$TAG: accept 异常: ${e.message}")
                }
            }
        }

        cleanup()
    }

    private fun handleClient(client: Socket, binaryPath: String) {
        try {
            client.use { sock ->
                val inputStream = sock.getInputStream()
                val output = sock.getOutputStream()

                while (!shutdownRequested) {
                    // 读取长度前缀（使用 InputStream 读取原始字节）
                    val lengthBytes = readBytesFromStream(inputStream, 4) ?: break
                    val length = ((lengthBytes[0].toInt() and 0xFF) shl 24) or
                            ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                            ((lengthBytes[2].toInt() and 0xFF) shl 8) or
                            (lengthBytes[3].toInt() and 0xFF)

                    if (length <= 0 || length > 10 * 1024 * 1024) break // 最大 10MB

                    val jsonBytes = readBytesFromStream(inputStream, length) ?: break
                    val request = String(jsonBytes, Charsets.UTF_8)
                    lastActivityTime = System.currentTimeMillis()

                    val response = processRequest(request, binaryPath, output)
                    if (response != null) {
                        sendResponse(output, response)
                    }
                }
            }
        } catch (e: Exception) {
            if (!shutdownRequested) {
                System.err.println("$TAG: 客户端处理异常: ${e.message}")
            }
        }
    }

    /**
     * 处理请求。对于流式命令（compress_stream, extract_stream），直接写入 output 并返回 null。
     * 对于普通命令，返回 JSON 响应字符串。
     */
    private fun processRequest(requestJson: String, binaryPath: String, output: OutputStream): String? {
        return try {
            val cmd = extractJsonField(requestJson, "cmd")
            val args = parseJsonMap(requestJson, "args")

            when (cmd) {
                "list" -> {
                    val archivePath = args["archivePath"] ?: return errorResponse("缺少 archivePath")
                    val password = args["password"] ?: ""
                    val cmdStr = SevenZipCommand.buildListCommand(binaryPath, archivePath, password)
                    val (output, exitCode) = execCommand(cmdStr)
                    if (exitCode == 0) successResponse(output) else errorResponse(output.ifEmpty { "退出码: $exitCode" })
                }
                "list_detail" -> {
                    val archivePath = args["archivePath"] ?: return errorResponse("缺少 archivePath")
                    val password = args["password"] ?: ""
                    val cmdStr = SevenZipCommand.buildListDetailCommand(binaryPath, archivePath, password)
                    val (output, exitCode) = execCommand(cmdStr)
                    if (exitCode == 0) successResponse(output) else errorResponse(output.ifEmpty { "退出码: $exitCode" })
                }
                "extract_single" -> {
                    val archivePath = args["archivePath"] ?: return errorResponse("缺少 archivePath")
                    val fileName = args["fileName"] ?: return errorResponse("缺少 fileName")
                    val outputDir = args["outputDir"] ?: return errorResponse("缺少 outputDir")
                    val password = args["password"] ?: ""
                    val cmdStr = SevenZipCommand.buildExtractSingleCommand(binaryPath, archivePath, fileName, outputDir, password)
                    val (output, exitCode) = execCommand(cmdStr)
                    if (exitCode == 0) successResponse(output) else errorResponse(output.ifEmpty { "退出码: $exitCode" })
                }
                "extract_all" -> {
                    val archivePath = args["archivePath"] ?: return errorResponse("缺少 archivePath")
                    val outputDir = args["outputDir"] ?: return errorResponse("缺少 outputDir")
                    val password = args["password"] ?: ""
                    val cmdStr = SevenZipCommand.buildExtractCommand(binaryPath, archivePath, outputDir, password)
                    val (output, exitCode) = execCommand(cmdStr)
                    if (exitCode == 0) successResponse(output) else errorResponse(output.ifEmpty { "退出码: $exitCode" })
                }
                "compress" -> {
                    val archivePath = args["archivePath"] ?: return errorResponse("缺少 archivePath")
                    val outputDir = args["outputDir"] ?: return errorResponse("缺少 outputDir")
                    val format = args["format"] ?: "zip"
                    val level = args["level"]?.toIntOrNull() ?: 5
                    val password = args["password"] ?: ""
                    val useAes = args["useAes"] == "true"
                    val encryptNames = args["encryptNames"] == "true"
                    val sourcePaths = args["sourcePaths"]?.split("|") ?: return errorResponse("缺少 sourcePaths")

                    val options = CompressService.CompressOptions(
                        sourcePaths = sourcePaths,
                        outputPath = outputDir,
                        format = format,
                        compressionLevel = level,
                        password = password,
                        useAes = useAes,
                        encryptNames = encryptNames
                    )
                    val cmdStr = SevenZipCommand.build(binaryPath, options)
                    val (output, exitCode) = execCommand(cmdStr)
                    if (exitCode == 0) successResponse(output) else errorResponse(output.ifEmpty { "退出码: $exitCode" })
                }
                "compress_stream" -> {
                    val archivePath = args["archivePath"] ?: return errorResponse("缺少 archivePath")
                    val outputDir = args["outputDir"] ?: return errorResponse("缺少 outputDir")
                    val format = args["format"] ?: "zip"
                    val level = args["level"]?.toIntOrNull() ?: 5
                    val password = args["password"] ?: ""
                    val useAes = args["useAes"] == "true"
                    val encryptNames = args["encryptNames"] == "true"
                    val sourcePaths = args["sourcePaths"]?.split("|") ?: return errorResponse("缺少 sourcePaths")

                    val options = CompressService.CompressOptions(
                        sourcePaths = sourcePaths,
                        outputPath = outputDir,
                        format = format,
                        compressionLevel = level,
                        password = password,
                        useAes = useAes,
                        encryptNames = encryptNames
                    )
                    val cmdStr = SevenZipCommand.build(binaryPath, options)
                    execCommandStreaming(cmdStr, output)
                    null // 流式命令已在 output 中写入结果
                }
                "extract_stream" -> {
                    val archivePath = args["archivePath"] ?: return errorResponse("缺少 archivePath")
                    val outputDir = args["outputDir"] ?: return errorResponse("缺少 outputDir")
                    val password = args["password"] ?: ""
                    val cmdStr = SevenZipCommand.buildExtractCommand(binaryPath, archivePath, outputDir, password)
                    execCommandStreaming(cmdStr, output)
                    null
                }
                "detect_password" -> {
                    val archivePath = args["archivePath"] ?: return errorResponse("缺少 archivePath")
                    val cmdStr = SevenZipCommand.buildListDetailCommand(binaryPath, archivePath, password = "dummy")
                    val (output, exitCode) = execCommand(cmdStr)
                    val needsPassword = when {
                        exitCode == 0 && (output.contains("7zAES", ignoreCase = true) || output.contains("Encrypted = +")) -> "true"
                        exitCode == 0 -> "false"
                        output.contains("Cannot open encrypted archive", ignoreCase = true) -> "true"
                        else -> "null"
                    }
                    successResponse(needsPassword)
                }
                "shutdown" -> {
                    shutdownRequested = true
                    successResponse("ok")
                }
                else -> errorResponse("未知命令: $cmd")
            }
        } catch (e: Exception) {
            errorResponse("异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun execCommand(cmd: String): Pair<String, Int> {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$cmd 2>&1"))
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        process.waitFor()
        return Pair(output, process.exitValue())
    }

    /**
     * 流式执行命令，逐行写入输出流。
     * 格式：每行一个 JSON 对象，最后一行是 {"type":"result","success":true/false,"output":"..."}
     */
    private fun execCommandStreaming(cmd: String, outputStream: OutputStream) {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$cmd 2>&1"))
        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        val errorReader = process.errorStream.bufferedReader(Charsets.UTF_8)

        // 逐行发送进度
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            lastActivityTime = System.currentTimeMillis()
            val progressJson = "{\"type\":\"progress\",\"line\":\"${escapeJson(line ?: "")}\"}"
            sendLine(outputStream, progressJson)
        }

        // 读取 stderr
        val stderr = errorReader.readText().trim()
        process.waitFor()
        val exitCode = process.exitValue()

        // 发送最终结果
        val resultJson = if (exitCode == 0) {
            "{\"type\":\"result\",\"success\":true,\"output\":\"\",\"error\":\"\"}"
        } else {
            "{\"type\":\"result\",\"success\":false,\"output\":\"\",\"error\":\"${escapeJson(stderr.ifEmpty { "退出码: $exitCode" })}\"}"
        }
        sendLine(outputStream, resultJson)
        outputStream.flush()
    }

    private fun sendLine(output: OutputStream, line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        output.write(bytes)
        output.flush()
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun sendResponse(output: OutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
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

    private fun readBytesFromStream(stream: java.io.InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = stream.read(buf, offset, count - offset)
            if (read == -1) return null
            offset += read
        }
        return buf
    }

    private fun shutdown() {
        shutdownRequested = true
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun cleanup() {
        try { serverSocket?.close() } catch (_: Exception) {}
        exitProcess(0)
    }

    // ── 简易 JSON 工具（不依赖 org.json）──

    private fun extractJsonField(json: String, field: String): String? {
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]*?)\""
        val match = Regex(pattern).find(json) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
    }

    private fun parseJsonMap(json: String, field: String): Map<String, String> {
        val objMatch = Regex("\"$field\"\\s*:\\s*\\{").find(json) ?: return emptyMap()
        val start = objMatch.range.last + 1
        var depth = 1
        var i = start
        while (i < json.length && depth > 0) {
            when (json[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        val objStr = json.substring(start, i - 1)
        val result = mutableMapOf<String, String>()
        val entryRegex = Regex("\"(\\w+)\"\\s*:\\s*\"([^\"]*?)\"")
        for (match in entryRegex.findAll(objStr)) {
            result[match.groupValues[1]] = match.groupValues[2]
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
        }
        return result
    }

    private fun successResponse(output: String): String {
        val escaped = output.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "{\"success\":true,\"output\":\"$escaped\",\"error\":\"\"}"
    }

    private fun errorResponse(error: String): String {
        val escaped = error.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "{\"success\":false,\"output\":\"\",\"error\":\"$escaped\"}"
    }
}
