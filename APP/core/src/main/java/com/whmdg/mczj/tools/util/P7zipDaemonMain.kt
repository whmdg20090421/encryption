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

    private fun log(msg: String) {
        System.err.println("[P7zipDaemon] $msg")
        System.err.flush()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val t0 = System.currentTimeMillis()
        log("main() 启动, args=${args.toList()}")

        if (args.isEmpty()) {
            log("错误: 缺少 binaryPath 参数")
            System.err.println("Usage: P7zipDaemonMain <binaryPath>")
            exitProcess(1)
        }

        val binaryPath = args[0]
        log("binaryPath=$binaryPath")
        log("检查二进制文件...")
        val binFile = java.io.File(binaryPath)
        log("二进制存在=${binFile.exists()}, 可执行=${binFile.canExecute()}, 大小=${binFile.length()}")

        val reader = System.`in`.bufferedReader(Charsets.UTF_8)
        log("stdin 已就绪")

        // 通知客户端 daemon 已就绪
        log("发送 $READY (${System.currentTimeMillis() - t0}ms)")
        println(READY)
        System.out.flush()

        // 主循环：逐条读取命令
        var cmdCount = 0
        while (true) {
            try {
                log("等待命令...")
                val command = reader.readLine()
                log("收到命令行: $command")
                if (command == null) {
                    log("stdin EOF，退出")
                    break
                }
                if (command.isBlank()) continue

                val argsList = mutableListOf<String>()
                while (true) {
                    val line = reader.readLine()
                    if (line == null) {
                        log("stdin EOF 读取参数时")
                        break
                    }
                    if (line == MARKER) {
                        log("参数读取完毕，共 ${argsList.size} 个参数")
                        break
                    }
                    argsList.add(line)
                }

                if (command == "shutdown") {
                    log("收到 shutdown 命令")
                    println(OK)
                    println("ok")
                    println(MARKER)
                    System.out.flush()
                    break
                }

                cmdCount++
                log("执行命令 #$cmdCount: $command, 参数=$argsList")
                val cmdStart = System.currentTimeMillis()
                val result = executeCommand(command, argsList, binaryPath)
                val cmdElapsed = System.currentTimeMillis() - cmdStart
                log("命令 #$cmdCount 完成 (${cmdElapsed}ms): marker=${result.first}")

                println(result.first)
                println(result.second)
                println(MARKER)
                System.out.flush()
            } catch (e: Exception) {
                log("异常: ${e.javaClass.simpleName}: ${e.message}")
                println(ERR)
                println("异常: ${e.javaClass.simpleName}: ${e.message}")
                println(MARKER)
                System.out.flush()
            }
        }

        log("主循环结束，共处理 $cmdCount 条命令")
        try { System.`in`.close() } catch (_: Exception) {}
        Thread.sleep(200)
        log("退出")
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
                    log("  7zzs 命令: $cmd")
                    val (output, exitCode) = execCommand(cmd)
                    log("  退出码=$exitCode, 输出长度=${output.length}")
                    if (exitCode == 0) Pair(OK, output) else Pair(ERR, output.ifEmpty { "退出码: $exitCode" })
                }
                "list_detail" -> {
                    if (argsList.isEmpty()) return Pair(ERR, "缺少 archivePath")
                    val archivePath = argsList[0]
                    val password = argsList.getOrElse(1) { "" }
                    val cmd = SevenZipCommand.buildListDetailCommand(binaryPath, archivePath, password)
                    log("  7zzs 命令: $cmd")
                    val (output, exitCode) = execCommand(cmd)
                    log("  退出码=$exitCode, 输出长度=${output.length}")
                    if (exitCode == 0) Pair(OK, output) else Pair(ERR, output.ifEmpty { "退出码: $exitCode" })
                }
                "detect_password" -> {
                    if (argsList.isEmpty()) return Pair(ERR, "缺少 archivePath")
                    val archivePath = argsList[0]
                    val cmd = SevenZipCommand.buildListDetailCommand(binaryPath, archivePath, password = "dummy")
                    log("  7zzs 命令: $cmd")
                    val (output, exitCode) = execCommand(cmd)
                    log("  退出码=$exitCode, 输出长度=${output.length}")
                    val needsPassword = when {
                        exitCode == 0 && (output.contains("7zAES", ignoreCase = true) || output.contains("Encrypted = +")) -> "true"
                        exitCode == 0 -> "false"
                        output.contains("Cannot open encrypted archive", ignoreCase = true) -> "true"
                        else -> "null"
                    }
                    log("  密码检测结果: $needsPassword")
                    Pair(OK, needsPassword)
                }
                "extract_single" -> {
                    if (argsList.size < 3) return Pair(ERR, "参数不足: 需要 archivePath, fileName, outputDir")
                    val cmd = SevenZipCommand.buildExtractSingleCommand(binaryPath, argsList[0], argsList[1], argsList[2], argsList.getOrElse(3) { "" })
                    log("  7zzs 命令: $cmd")
                    val (output, exitCode) = execCommand(cmd)
                    log("  退出码=$exitCode, 输出长度=${output.length}")
                    if (exitCode == 0) Pair(OK, output) else Pair(ERR, output.ifEmpty { "退出码: $exitCode" })
                }
                "extract_all" -> {
                    if (argsList.size < 2) return Pair(ERR, "参数不足: 需要 archivePath, outputDir")
                    val cmd = SevenZipCommand.buildExtractCommand(binaryPath, argsList[0], argsList[1], argsList.getOrElse(2) { "" })
                    log("  7zzs 命令: $cmd")
                    val (output, exitCode) = execCommand(cmd)
                    log("  退出码=$exitCode, 输出长度=${output.length}")
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
                    log("  7zzs 命令: $cmd")
                    val (output, exitCode) = execCommand(cmd)
                    log("  退出码=$exitCode, 输出长度=${output.length}")
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
                    log("  流式压缩命令: $cmd")
                    execStreaming(cmd)
                    return Pair("", "")
                }
                "extract_stream" -> {
                    if (argsList.size < 2) return Pair(ERR, "参数不足: 需要 archivePath, outputDir")
                    val cmd = SevenZipCommand.buildExtractCommand(binaryPath, argsList[0], argsList[1], argsList.getOrElse(2) { "" })
                    log("  流式解压命令: $cmd")
                    execStreaming(cmd)
                    return Pair("", "")
                }
                else -> Pair(ERR, "未知命令: $command")
            }
        } catch (e: Exception) {
            log("executeCommand 异常: ${e.javaClass.simpleName}: ${e.message}")
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

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            println(line)
            System.out.flush()
        }

        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText().trim()
        process.waitFor()
        val exitCode = process.exitValue()
        log("  流式命令完成, 退出码=$exitCode")

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
