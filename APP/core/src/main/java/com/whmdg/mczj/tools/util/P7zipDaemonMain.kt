package com.whmdg.mczj.tools.util

import kotlin.system.exitProcess

/**
 * P7zip 守护进程入口（stdin/stdout 协议版）。
 * 通过 app_process 启动，从 stdin 读取命令，执行 7zzs，结果写入 stdout。
 *
 * 协议：
 *   输入：command\narg1\narg2\n...<<<END>>>\n
 *   输出（普通）：<<<OK>>>\nresult\n<<<END>>>\n  或  <<<ERR>>>\nerror\n<<<END>>>\n
 *   输出（流式）：progress lines...<<<OK>>>\nresult\n<<<END>>>\n
 *   启动信号：<<<READY>>>\n
 *
 * 启动方式：
 *   su -c "app_process -Djava.class.path=<dexPath> / com.whmdg.mczj.tools.util.P7zipDaemonMain <binaryPath>"
 */
object P7zipDaemonMain {

    private const val MARKER = "<<<END>>>"
    private const val OK = "<<<OK>>>"
    private const val ERR = "<<<ERR>>>"
    private const val READY = "<<<READY>>>"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: P7zipDaemonMain <binaryPath>")
            exitProcess(1)
        }

        val binaryPath = args[0]
        val reader = System.`in`.bufferedReader(Charsets.UTF_8)

        // 通知客户端 daemon 已就绪
        println(READY)

        // 主循环：逐条读取命令
        while (true) {
            try {
                val command = reader.readLine() ?: break
                if (command.isBlank()) continue

                val argsList = mutableListOf<String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line == MARKER) break
                    argsList.add(line)
                }

                if (command == "shutdown") {
                    println(OK)
                    println("ok")
                    println(MARKER)
                    break
                }

                val result = executeCommand(command, argsList, binaryPath)
                println(result.first)
                println(result.second)
                println(MARKER)
                System.out.flush()
            } catch (e: Exception) {
                println(ERR)
                println("异常: ${e.javaClass.simpleName}: ${e.message}")
                println(MARKER)
                System.out.flush()
            }
        }

        // 关闭 stdin，等待子进程结束
        try { System.`in`.close() } catch (_: Exception) {}
        Thread.sleep(200)
        exitProcess(0)
    }

    /**
     * 执行命令。返回 Pair(OK/ERR, output/error)。
     */
    private fun executeCommand(
        command: String,
        argsList: List<String>,
        binaryPath: String
    ): Pair<String, String> {
        return try {
            when (command) {
                "list" -> {
                    if (argsList.isEmpty()) return Pair(ERR, "缺少 archivePath")
                    val archivePath = argsList[0]
                    val password = argsList.getOrElse(1) { "" }
                    val cmd = SevenZipCommand.buildListCommand(binaryPath, archivePath, password)
                    val (output, exitCode) = execCommand(cmd)
                    if (exitCode == 0) Pair(OK, output) else Pair(ERR, output.ifEmpty { "退出码: $exitCode" })
                }
                "list_detail" -> {
                    if (argsList.isEmpty()) return Pair(ERR, "缺少 archivePath")
                    val archivePath = argsList[0]
                    val password = argsList.getOrElse(1) { "" }
                    val cmd = SevenZipCommand.buildListDetailCommand(binaryPath, archivePath, password)
                    val (output, exitCode) = execCommand(cmd)
                    if (exitCode == 0) Pair(OK, output) else Pair(ERR, output.ifEmpty { "退出码: $exitCode" })
                }
                "detect_password" -> {
                    if (argsList.isEmpty()) return Pair(ERR, "缺少 archivePath")
                    val archivePath = argsList[0]
                    val cmd = SevenZipCommand.buildListDetailCommand(binaryPath, archivePath, password = "dummy")
                    val (output, exitCode) = execCommand(cmd)
                    val needsPassword = when {
                        exitCode == 0 && (output.contains("7zAES", ignoreCase = true) || output.contains("Encrypted = +")) -> "true"
                        exitCode == 0 -> "false"
                        output.contains("Cannot open encrypted archive", ignoreCase = true) -> "true"
                        else -> "null"
                    }
                    Pair(OK, needsPassword)
                }
                "extract_single" -> {
                    if (argsList.size < 3) return Pair(ERR, "参数不足: 需要 archivePath, fileName, outputDir")
                    val cmd = SevenZipCommand.buildExtractSingleCommand(binaryPath, argsList[0], argsList[1], argsList[2], argsList.getOrElse(3) { "" })
                    val (output, exitCode) = execCommand(cmd)
                    if (exitCode == 0) Pair(OK, output) else Pair(ERR, output.ifEmpty { "退出码: $exitCode" })
                }
                "extract_all" -> {
                    if (argsList.size < 2) return Pair(ERR, "参数不足: 需要 archivePath, outputDir")
                    val cmd = SevenZipCommand.buildExtractCommand(binaryPath, argsList[0], argsList[1], argsList.getOrElse(2) { "" })
                    val (output, exitCode) = execCommand(cmd)
                    if (exitCode == 0) Pair(OK, output) else Pair(ERR, output.ifEmpty { "退出码: $exitCode" })
                }
                "compress" -> {
                    if (argsList.size < 4) return Pair(ERR, "参数不足: 需要 sourcePaths, outputDir, format, level")
                    val sourcePaths = argsList[0].split("|")
                    val options = CompressService.CompressOptions(
                        sourcePaths = sourcePaths, outputPath = argsList[1], format = argsList[2],
                        compressionLevel = argsList[3].toIntOrNull() ?: 5,
                        password = argsList.getOrElse(4) { "" },
                        useAes = argsList.getOrElse(5) { "" } == "true",
                        encryptNames = argsList.getOrElse(6) { "" } == "true"
                    )
                    val cmd = SevenZipCommand.build(binaryPath, options)
                    val (output, exitCode) = execCommand(cmd)
                    if (exitCode == 0) Pair(OK, output) else Pair(ERR, output.ifEmpty { "退出码: $exitCode" })
                }
                "compress_stream" -> {
                    if (argsList.size < 4) return Pair(ERR, "参数不足: 需要 sourcePaths, outputDir, format, level")
                    val sourcePaths = argsList[0].split("|")
                    val options = CompressService.CompressOptions(
                        sourcePaths = sourcePaths, outputPath = argsList[1], format = argsList[2],
                        compressionLevel = argsList[3].toIntOrNull() ?: 5,
                        password = argsList.getOrElse(4) { "" },
                        useAes = argsList.getOrElse(5) { "" } == "true",
                        encryptNames = argsList.getOrElse(6) { "" } == "true"
                    )
                    val cmd = SevenZipCommand.build(binaryPath, options)
                    execStreaming(cmd)
                    // 返回值由 execStreaming 直接写入 stdout（含 OK/ERR + END）
                    return Pair("", "") // 不再通过主循环写入
                }
                "extract_stream" -> {
                    if (argsList.size < 2) return Pair(ERR, "参数不足: 需要 archivePath, outputDir")
                    val cmd = SevenZipCommand.buildExtractCommand(binaryPath, argsList[0], argsList[1], argsList.getOrElse(2) { "" })
                    execStreaming(cmd)
                    return Pair("", "")
                }
                else -> Pair(ERR, "未知命令: $command")
            }
        } catch (e: Exception) {
            Pair(ERR, "异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun execCommand(cmd: String): Pair<String, Int> {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$cmd 2>&1"))
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        process.waitFor()
        return Pair(output, process.exitValue())
    }

    /**
     * 流式执行：逐行写 stdout（raw progress），最后写 OK/ERR + END。
     */
    private fun execStreaming(cmd: String) {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$cmd 2>&1"))
        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)

        // 透传进度行
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            println(line)
            System.out.flush()
        }

        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText().trim()
        process.waitFor()
        val exitCode = process.exitValue()

        if (exitCode == 0) {
            println(OK)
            println("")
        } else {
            println(ERR)
            println(stderr.ifEmpty { "退出码: $exitCode" })
        }
        println(MARKER)
        System.out.flush()
    }
}
